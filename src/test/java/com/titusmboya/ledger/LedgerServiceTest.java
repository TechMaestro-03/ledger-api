package com.titusmboya.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LedgerServiceTest {

    private LedgerService ledger;

    @BeforeEach
    void setUp() {
        ledger = new LedgerService();
        ledger.openAccount("alice", 10_000);
        ledger.openAccount("bob", 0);
    }

    @Test
    void transferMovesMoneyBetweenAccounts() {
        ledger.transfer("k1", "alice", "bob", 2_500);

        assertEquals(7_500, ledger.balanceOf("alice"));
        assertEquals(2_500, ledger.balanceOf("bob"));
    }

    @Test
    void insufficientFundsIsRejectedAndBalancesAreUnchanged() {
        assertThrows(InsufficientFundsException.class, () -> ledger.transfer("k1", "alice", "bob", 20_000));

        assertEquals(10_000, ledger.balanceOf("alice"));
        assertEquals(0, ledger.balanceOf("bob"));
    }

    @Test
    void repeatingARequestWithTheSameKeyDoesNotMoveMoneyTwice() {
        Transaction first = ledger.transfer("k1", "alice", "bob", 1_000);
        Transaction retry = ledger.transfer("k1", "alice", "bob", 1_000);

        assertSame(first, retry);
        assertEquals(9_000, ledger.balanceOf("alice"));
        assertEquals(1_000, ledger.balanceOf("bob"));
    }

    @Test
    void reusingAKeyWithDifferentDetailsIsRejected() {
        ledger.transfer("k1", "alice", "bob", 1_000);

        assertThrows(IllegalStateException.class, () -> ledger.transfer("k1", "alice", "bob", 2_000));
    }

    @Test
    void invalidTransfersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ledger.transfer("k1", "alice", "bob", 0));
        assertThrows(IllegalArgumentException.class, () -> ledger.transfer("k2", "alice", "alice", 100));
        assertThrows(IllegalArgumentException.class, () -> ledger.transfer(" ", "alice", "bob", 100));
        assertThrows(NoSuchElementException.class, () -> ledger.transfer("k3", "alice", "ghost", 100));
    }

    @Test
    void concurrentTransfersNeverCreateOrLoseMoney() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            String key = "k" + i;
            String from = i % 2 == 0 ? "alice" : "bob";
            String to = i % 2 == 0 ? "bob" : "alice";
            futures.add(pool.submit(() -> {
                try {
                    ledger.transfer(key, from, to, 100);
                } catch (InsufficientFundsException ignored) {
                    // expected when the sender is temporarily empty
                }
            }));
        }
        for (Future<?> future : futures) {
            future.get();
        }
        pool.shutdown();

        assertEquals(10_000, ledger.balanceOf("alice") + ledger.balanceOf("bob"));
    }
}

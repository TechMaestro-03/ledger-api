package com.titusmboya.ledger;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * In-memory ledger with idempotent transfers.
 * All state changes are guarded by one lock, so balances stay consistent under concurrency.
 */
public final class LedgerService {

    private final Map<String, Long> balances = new HashMap<>();
    private final Map<String, Transaction> transfersByKey = new HashMap<>();

    public synchronized void openAccount(String id, long openingBalanceCents) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Account id is required");
        }
        if (openingBalanceCents < 0) {
            throw new IllegalArgumentException("Opening balance cannot be negative");
        }
        if (balances.putIfAbsent(id, openingBalanceCents) != null) {
            throw new IllegalStateException("Account already exists: " + id);
        }
    }

    public synchronized long balanceOf(String id) {
        Long balance = balances.get(id);
        if (balance == null) {
            throw new NoSuchElementException("Unknown account: " + id);
        }
        return balance;
    }

    /**
     * Moves money between accounts. Repeating a request with the same idempotency key returns the
     * original transaction without moving money again; reusing a key with different details is rejected.
     */
    public synchronized Transaction transfer(String idempotencyKey, String from, String to, long amountCents) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency key is required");
        }
        if (amountCents <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        if (from.equals(to)) {
            throw new IllegalArgumentException("Cannot transfer to the same account");
        }

        Transaction existing = transfersByKey.get(idempotencyKey);
        if (existing != null) {
            boolean sameRequest = existing.from().equals(from)
                    && existing.to().equals(to)
                    && existing.amountCents() == amountCents;
            if (!sameRequest) {
                throw new IllegalStateException("Idempotency key reused with different details");
            }
            return existing;
        }

        long fromBalance = balanceOf(from);
        long toBalance = balanceOf(to);
        if (fromBalance < amountCents) {
            throw new InsufficientFundsException(from);
        }

        balances.put(from, fromBalance - amountCents);
        balances.put(to, toBalance + amountCents);

        Transaction transaction = new Transaction(
                UUID.randomUUID().toString(), from, to, amountCents, Instant.now().toString());
        transfersByKey.put(idempotencyKey, transaction);
        return transaction;
    }
}

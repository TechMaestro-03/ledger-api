package com.titusmboya.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LedgerServerTest {

    private LedgerServer server;
    private String baseUrl;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws Exception {
        server = new LedgerServer(new LedgerService(), 0);
        server.start();
        baseUrl = "http://localhost:" + server.port();
        post("/accounts", "{\"id\":\"alice\",\"openingBalanceCents\":10000}", null);
        post("/accounts", "{\"id\":\"bob\",\"openingBalanceCents\":0}", null);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void transferUpdatesBalancesAndReturns201() throws Exception {
        HttpResponse<String> response = post("/transfers",
                "{\"from\":\"alice\",\"to\":\"bob\",\"amountCents\":2500}", "key-1");

        assertEquals(201, response.statusCode());
        assertTrue(get("/accounts/bob").body().contains("\"balanceCents\":2500"));
    }

    @Test
    void retryWithSameKeyDoesNotDoubleCharge() throws Exception {
        String body = "{\"from\":\"alice\",\"to\":\"bob\",\"amountCents\":2500}";
        post("/transfers", body, "key-1");
        post("/transfers", body, "key-1");

        assertTrue(get("/accounts/alice").body().contains("\"balanceCents\":7500"));
    }

    @Test
    void errorsMapToTheRightStatusCodes() throws Exception {
        assertEquals(422, post("/transfers", "{\"from\":\"alice\",\"to\":\"bob\",\"amountCents\":999999}", "k1").statusCode());
        assertEquals(400, post("/transfers", "{\"from\":\"alice\",\"to\":\"bob\",\"amountCents\":0}", "k2").statusCode());
        assertEquals(400, post("/transfers", "{\"from\":\"alice\",\"to\":\"bob\",\"amountCents\":100}", null).statusCode());
        assertEquals(404, get("/accounts/ghost").statusCode());
        assertEquals(409, post("/accounts", "{\"id\":\"alice\"}", null).statusCode());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, String idempotencyKey) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}

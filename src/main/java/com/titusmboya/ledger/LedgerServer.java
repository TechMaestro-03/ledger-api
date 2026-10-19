package com.titusmboya.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Minimal REST API over {@link LedgerService}.
 *
 * POST /accounts        {"id": "acc-1", "openingBalanceCents": 10000}  -> 201
 * GET  /accounts/{id}                                                  -> 200 {"id", "balanceCents"}
 * POST /transfers       header Idempotency-Key, {"from","to","amountCents"} -> 201
 */
public final class LedgerServer {

    private final LedgerService ledger;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpServer server;

    public LedgerServer(LedgerService ledger, int port) throws IOException {
        this.ledger = ledger;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/accounts", exchange -> handle(exchange, this::accounts));
        server.createContext("/transfers", exchange -> handle(exchange, this::transfers));
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private interface Route {
        void run(HttpExchange exchange) throws IOException;
    }

    private void handle(HttpExchange exchange, Route route) throws IOException {
        try {
            route.run(exchange);
        } catch (IllegalArgumentException e) {
            send(exchange, 400, Map.of("error", e.getMessage()));
        } catch (NoSuchElementException e) {
            send(exchange, 404, Map.of("error", e.getMessage()));
        } catch (InsufficientFundsException e) {
            send(exchange, 422, Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            send(exchange, 409, Map.of("error", e.getMessage()));
        } finally {
            exchange.close();
        }
    }

    private void accounts(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();

        if (method.equals("POST") && path.equals("/accounts")) {
            JsonNode body = readJson(exchange);
            ledger.openAccount(text(body, "id"), body.path("openingBalanceCents").asLong(0));
            send(exchange, 201, Map.of("id", text(body, "id")));
        } else if (method.equals("GET") && path.startsWith("/accounts/")) {
            String id = path.substring("/accounts/".length());
            send(exchange, 200, Map.of("id", id, "balanceCents", ledger.balanceOf(id)));
        } else {
            send(exchange, 405, Map.of("error", "Method not allowed"));
        }
    }

    private void transfers(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            send(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }
        String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        JsonNode body = readJson(exchange);
        Transaction transaction = ledger.transfer(
                key, text(body, "from"), text(body, "to"), body.path("amountCents").asLong(0));
        send(exchange, 201, transaction);
    }

    private JsonNode readJson(HttpExchange exchange) throws IOException {
        try {
            return json.readTree(exchange.getRequestBody());
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid JSON body");
        }
    }

    private static String text(JsonNode body, String field) {
        String value = body.path(field).asText("");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Field is required: " + field);
        }
        return value;
    }

    private void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    public static void main(String[] args) throws IOException {
        LedgerServer server = new LedgerServer(new LedgerService(), 8080);
        server.start();
        System.out.println("Ledger API listening on http://localhost:" + server.port());
    }
}

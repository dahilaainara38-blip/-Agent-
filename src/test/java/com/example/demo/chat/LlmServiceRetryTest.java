package com.example.demo.chat;

import com.alibaba.fastjson2.JSONObject;
import com.example.demo.config.DashScopeConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmServiceRetryTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void retriesOn429ThenSucceeds() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        startServer(exchange -> {
            int hit = hits.incrementAndGet();
            if (hit == 1) {
                respond(exchange, 429, "{\"error\":\"rate limited\"}");
            } else {
                respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
            }
        });

        LlmService service = newService(3, 10);

        JSONObject response = service.executeChatRequestWithResponse(new JSONObject());

        assertEquals(2, hits.get());
        assertTrue(response.containsKey("choices"));
    }

    @Test
    void retryExhaustionFailsWithLastStatus() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        startServer(exchange -> {
            hits.incrementAndGet();
            respond(exchange, 503, "{\"error\":\"unavailable\"}");
        });

        LlmService service = newService(2, 10);

        IOException error = assertThrows(IOException.class,
                () -> service.executeChatRequestWithResponse(new JSONObject()));

        assertTrue(error.getMessage().contains("503"));
        assertEquals(2, hits.get());
    }

    @Test
    void clientErrorsFailFastWithoutRetry() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        startServer(exchange -> {
            hits.incrementAndGet();
            respond(exchange, 401, "{\"error\":\"bad api key\"}");
        });

        LlmService service = newService(3, 10);

        IOException error = assertThrows(IOException.class,
                () -> service.executeChatRequestWithResponse(new JSONObject()));

        assertTrue(error.getMessage().contains("401"));
        assertEquals(1, hits.get());
    }

    private LlmService newService(int maxAttempts, long backoffMs) {
        DashScopeConfig config = new DashScopeConfig();
        config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        config.setApiKey("test-key");
        config.setModel("test-model");
        return new LlmService(config, 2000, 2000, maxAttempts, backoffMs);
    }

    private interface StubHandler {
        void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException;
    }

    private void startServer(StubHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try (exchange) {
                handler.handle(exchange);
            }
        });
        server.start();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}

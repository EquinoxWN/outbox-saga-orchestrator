package portfolio.outboxsaga.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import portfolio.outboxsaga.outbox.OutboxWriter;
import portfolio.outboxsaga.testsupport.EmbeddedDatabases;

/** Order service against a real PostgreSQL: atomic outbox, validation, database ownership. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderServiceTest {
    private static final NewOrder VALID = new NewOrder("c-1", "SKU-1", 2, 1999);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        EmbeddedDatabases.register(registry, "order_db", "order_svc");
    }

    @Autowired Environment env;
    @Autowired JdbcClient jdbc;
    @Autowired OrderService orders;
    @Autowired OutboxWriter outbox;
    @Autowired TransactionTemplate tx;

    private final HttpClient http = HttpClient.newHttpClient();

    /** POSTs a JSON body to this service. */
    private HttpResponse<String> post(String path, String json) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + env.getProperty("local.server.port") + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /** GETs a path from this service. */
    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + env.getProperty("local.server.port") + path)).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /** Rows in a table. */
    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    /** Asserts this service's role cannot open the other services' databases and is not a superuser. */
    private void assertOwnsOnly(String role, String... otherDatabases) {
        for (String db : otherDatabases) {
            SQLException e = assertThrows(SQLException.class, () -> {
                try (Connection c = EmbeddedDatabases.connect(db, role)) {
                    c.isValid(1);
                }
            });
            assertEquals("42501", e.getSQLState(), role + " reached " + db + ": " + e.getMessage());
        }
        assertFalse(jdbc.sql("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user").query(Boolean.class).single());
        assertEquals(role, jdbc.sql("SELECT current_user").query(String.class).single());
    }

    @BeforeEach
    void clean() {
        jdbc.sql("TRUNCATE orders, outbox").update();
    }

    @Test
    void creatingAnOrderStoresItAndExactlyOneEvent() throws Exception {
        HttpResponse<String> res = post("/orders", "{\"customerId\":\"c-1\",\"sku\":\"SKU-1\",\"quantity\":2,\"amountCents\":1999}");
        assertEquals(201, res.statusCode(), res.body());
        assertEquals(1, count("orders"));
        Map<String, Object> event = jdbc.sql("SELECT aggregatetype, aggregateid, type, payload->>'amountCents' AS amount, payload->>'status' AS status FROM outbox").query().singleRow();
        assertEquals("Order", event.get("aggregatetype"));
        assertEquals("OrderCreated", event.get("type"));
        assertEquals("1999", event.get("amount"));
        assertEquals("PENDING", event.get("status"));
        String id = jdbc.sql("SELECT id::text FROM orders").query(String.class).single();
        assertEquals(id, event.get("aggregateid"));
        assertTrue(res.headers().firstValue("Location").orElseThrow().endsWith(id));
    }

    @Test
    void aRolledBackTransactionLeavesNeitherTheOrderNorItsEvent() {
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(s -> {
            orders.create(VALID);
            throw new IllegalStateException("crash after both writes, before commit");
        }));
        assertEquals(0, count("orders"));
        assertEquals(0, count("outbox"));
    }

    @Test
    void aFailedEventWriteRollsBackTheOrder() {
        assertThrows(RuntimeException.class, () -> tx.executeWithoutResult(s -> {
            jdbc.sql("INSERT INTO orders (id, customer_id, sku, quantity, amount_cents, status) VALUES (gen_random_uuid(), 'c', 'SKU-1', 1, 1, 'PENDING')").update();
            outbox.append("Order", "x", "OrderCreated", new Unserialisable()); // the event write fails
        }));
        assertEquals(0, count("orders"));
        assertEquals(0, count("outbox"));
    }

    /** A payload whose serialisation fails, standing in for any failed event write. */
    public static class Unserialisable {
        public String getValue() {
            throw new IllegalStateException("cannot serialise");
        }
    }

    @Test
    void theOutboxRefusesToWriteOutsideATransaction() {
        assertThrows(IllegalTransactionStateException.class, () -> outbox.append("Order", "x", "OrderCreated", Map.of()));
        assertEquals(0, count("outbox"));
    }

    @Test
    void invalidOrdersAreRejectedAndNothingIsStored() throws Exception {
        for (String body : List.of(
                "{\"customerId\":\"c\",\"sku\":\"SKU-1\",\"quantity\":0,\"amountCents\":10}",
                "{\"customerId\":\"\",\"sku\":\"SKU-1\",\"quantity\":1,\"amountCents\":10}",
                "{\"customerId\":\"c\",\"sku\":\"sku 1; drop table orders\",\"quantity\":1,\"amountCents\":10}",
                "{\"customerId\":\"c\",\"sku\":\"SKU-1\",\"quantity\":1,\"amountCents\":0}",
                "{\"customerId\":\"c\"}",
                "not json")) {
            assertEquals(400, post("/orders", body).statusCode(), body);
        }
        assertEquals(0, count("orders"));
        assertEquals(0, count("outbox"));
    }

    @Test
    void getReturnsTheOrderOrNotFound() throws Exception {
        Order created = orders.create(VALID);
        HttpResponse<String> res = get("/orders/" + created.id());
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains(created.id().toString()));
        assertEquals(404, get("/orders/" + UUID.randomUUID()).statusCode());
        assertEquals(400, get("/orders/not-a-uuid").statusCode());
    }

    @Test
    void concurrentOrdersEachGetExactlyOneEvent() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<Order>> futures = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                futures.add(pool.submit(() -> orders.create(VALID)));
            }
            for (Future<Order> f : futures) {
                f.get();
            }
        }
        assertEquals(50, count("orders"));
        assertEquals(50, jdbc.sql("SELECT count(DISTINCT aggregateid) FROM outbox").query(Long.class).single());
    }

    @Test
    void theOrderServiceOwnsOnlyItsOwnDatabase() {
        assertOwnsOnly("order_svc", "payment_db", "inventory_db");
    }
}

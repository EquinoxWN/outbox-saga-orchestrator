package portfolio.outboxsaga.inventory;

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

/** Inventory service against a real PostgreSQL: no overselling, idempotency, atomic outbox, ownership. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InventoryServiceTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        EmbeddedDatabases.register(registry, "inventory_db", "inventory_svc");
    }

    @Autowired Environment env;
    @Autowired JdbcClient jdbc;
    @Autowired InventoryService inventory;
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
        jdbc.sql("TRUNCATE reservations, outbox").update();
        jdbc.sql("UPDATE stock SET available = CASE sku WHEN 'SKU-1' THEN 100 WHEN 'SKU-2' THEN 5 ELSE 1 END").update();
    }

    /** JSON body for a reservation request. */
    private static String body(UUID orderId, String sku, int quantity) {
        return "{\"orderId\":\"" + orderId + "\",\"sku\":\"" + sku + "\",\"quantity\":" + quantity + "}";
    }

    @Test
    void aReservationTakesStockAndEmitsStockReserved() throws Exception {
        HttpResponse<String> res = post("/reservations", body(UUID.randomUUID(), "SKU-1", 3));
        assertEquals(201, res.statusCode(), res.body());
        assertTrue(res.body().contains("RESERVED"));
        assertEquals(97, inventory.available("SKU-1"));
        assertEquals("StockReserved", jdbc.sql("SELECT type FROM outbox").query(String.class).single());
    }

    @Test
    void notEnoughStockIsRejectedAndLeavesStockUntouched() throws Exception {
        HttpResponse<String> res = post("/reservations", body(UUID.randomUUID(), "SKU-2", 6));
        assertEquals(201, res.statusCode());
        assertTrue(res.body().contains("REJECTED"));
        assertEquals(5, inventory.available("SKU-2"));
        Map<String, Object> e = jdbc.sql("SELECT type, payload->>'reason' AS reason FROM outbox").query().singleRow();
        assertEquals("StockRejected", e.get("type"));
        assertTrue(((String) e.get("reason")).contains("insufficient"));
        assertTrue(post("/reservations", body(UUID.randomUUID(), "SKU-UNKNOWN", 1)).body().contains("REJECTED"));
    }

    @Test
    void aRepeatedRequestReturnsTheFirstOutcomeWithoutTakingMoreStock() throws Exception {
        UUID order = UUID.randomUUID();
        assertEquals(201, post("/reservations", body(order, "SKU-1", 10)).statusCode());
        assertEquals(200, post("/reservations", body(order, "SKU-1", 10)).statusCode());
        assertEquals(90, inventory.available("SKU-1"));
        assertEquals(1, count("outbox"));
    }

    @Test
    void concurrentReservationsNeverOversell() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
            List<Future<InventoryService.Result>> futures = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                futures.add(pool.submit(() -> inventory.reserve(new ReservationRequest(UUID.randomUUID(), "SKU-2", 1))));
            }
            long reserved = 0;
            for (Future<InventoryService.Result> f : futures) {
                reserved += "RESERVED".equals(f.get().reservation().status()) ? 1 : 0;
            }
            assertEquals(5, reserved);
        }
        assertEquals(0, inventory.available("SKU-2"));
        assertEquals(40, count("outbox"));
        assertEquals(5, jdbc.sql("SELECT count(*) FROM outbox WHERE type = 'StockReserved'").query(Long.class).single());
    }

    @Test
    void aRolledBackReservationReturnsTheStockAndLeavesNoEvent() {
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(s -> {
            inventory.reserve(new ReservationRequest(UUID.randomUUID(), "SKU-LAST", 1));
            throw new IllegalStateException("crash before commit");
        }));
        assertEquals(1, inventory.available("SKU-LAST"));
        assertEquals(0, count("reservations"));
        assertEquals(0, count("outbox"));
    }

    @Test
    void invalidRequestsAreRejected() throws Exception {
        assertEquals(400, post("/reservations", body(UUID.randomUUID(), "SKU-1", 0)).statusCode());
        assertEquals(400, post("/reservations", body(UUID.randomUUID(), "sku'; --", 1)).statusCode());
        assertEquals(400, post("/reservations", "{\"sku\":\"SKU-1\",\"quantity\":1}").statusCode());
        assertEquals(0, count("outbox"));
    }

    @Test
    void theInventoryServiceOwnsOnlyItsOwnDatabase() {
        assertOwnsOnly("inventory_svc", "order_db", "payment_db");
    }
}

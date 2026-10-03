package portfolio.outboxsaga.payment;

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

/** Payment service against a real PostgreSQL: decisions, idempotency, atomic outbox, ownership. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentServiceTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        EmbeddedDatabases.register(registry, "payment_db", "payment_svc");
    }

    @Autowired Environment env;
    @Autowired JdbcClient jdbc;
    @Autowired PaymentService payments;
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
        jdbc.sql("TRUNCATE payments, outbox").update();
    }

    /** JSON body for a payment request. */
    private static String body(UUID orderId, long cents) {
        return "{\"orderId\":\"" + orderId + "\",\"amountCents\":" + cents + "}";
    }

    @Test
    void paymentsUpToTheLimitAreAuthorized() throws Exception {
        UUID order = UUID.randomUUID();
        HttpResponse<String> res = post("/payments", body(order, 100_000));
        assertEquals(201, res.statusCode(), res.body());
        assertTrue(res.body().contains("AUTHORIZED"));
        assertEquals("PaymentAuthorized", jdbc.sql("SELECT type FROM outbox").query(String.class).single());
    }

    @Test
    void paymentsAboveTheLimitAreDeclined() throws Exception {
        HttpResponse<String> res = post("/payments", body(UUID.randomUUID(), 100_001));
        assertEquals(201, res.statusCode());
        assertTrue(res.body().contains("DECLINED"));
        assertEquals("PaymentDeclined", jdbc.sql("SELECT type FROM outbox").query(String.class).single());
    }

    @Test
    void aRepeatedRequestReturnsTheFirstDecisionWithoutASecondEvent() throws Exception {
        UUID order = UUID.randomUUID();
        assertEquals(201, post("/payments", body(order, 500)).statusCode());
        HttpResponse<String> again = post("/payments", body(order, 999_999)); // retry with a different amount
        assertEquals(200, again.statusCode());
        assertTrue(again.body().contains("AUTHORIZED"));
        assertEquals(1, count("payments"));
        assertEquals(1, count("outbox"));
    }

    @Test
    void concurrentDuplicatesProduceOneDecisionAndOneEvent() throws Exception {
        UUID order = UUID.randomUUID();
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<PaymentService.Result>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                futures.add(pool.submit(() -> payments.authorize(new PaymentRequest(order, 700))));
            }
            long created = 0;
            for (Future<PaymentService.Result> f : futures) {
                created += f.get().created() ? 1 : 0;
            }
            assertEquals(1, created);
        }
        assertEquals(1, count("payments"));
        assertEquals(1, count("outbox"));
    }

    @Test
    void aRolledBackDecisionLeavesNoEvent() {
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(s -> {
            payments.authorize(new PaymentRequest(UUID.randomUUID(), 10));
            throw new IllegalStateException("crash before commit");
        }));
        assertEquals(0, count("payments"));
        assertEquals(0, count("outbox"));
    }

    @Test
    void invalidRequestsAreRejected() throws Exception {
        assertEquals(400, post("/payments", "{\"amountCents\":10}").statusCode());
        assertEquals(400, post("/payments", body(UUID.randomUUID(), 0)).statusCode());
        assertEquals(400, post("/payments", "{\"orderId\":\"nope\",\"amountCents\":10}").statusCode());
        assertEquals(0, count("outbox"));
    }

    @Test
    void thePaymentServiceOwnsOnlyItsOwnDatabase() {
        assertOwnsOnly("payment_svc", "order_db", "inventory_db");
    }
}

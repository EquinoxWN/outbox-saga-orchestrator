package portfolio.outboxsaga.testsupport;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.test.context.DynamicPropertyRegistry;

/** One real PostgreSQL per test JVM, set up with db/init.sql: a database and a role per service. */
public final class EmbeddedDatabases {
    private static final Map<String, String> PASSWORDS = new ConcurrentHashMap<>();
    private static EmbeddedPostgres postgres;

    private EmbeddedDatabases() {}

    /** Starts PostgreSQL and runs db/init.sql once. */
    public static synchronized EmbeddedPostgres start() {
        if (postgres == null) {
            try {
                postgres = EmbeddedPostgres.builder().start();
                Runtime.getRuntime().addShutdownHook(new Thread(EmbeddedDatabases::stop));
                init();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
        return postgres;
    }

    /** Points Spring at a service's database, connected as that service's role. */
    public static void register(DynamicPropertyRegistry registry, String database, String role) {
        start();
        registry.add("spring.datasource.url", () -> jdbcUrl(database));
        registry.add("spring.datasource.username", () -> role);
        registry.add("spring.datasource.password", () -> password(role));
    }

    /** JDBC URL of one database on the embedded server. */
    public static String jdbcUrl(String database) {
        return "jdbc:postgresql://localhost:" + start().getPort() + "/" + database;
    }

    /** The generated password of a service role. */
    public static String password(String role) {
        start();
        return PASSWORDS.get(role);
    }

    /** Opens a connection as role to database. */
    public static Connection connect(String database, String role) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(database), role, password(role));
    }

    /** Runs db/init.sql as the superuser, with fresh random passwords. */
    private static void init() throws IOException, SQLException {
        String sql;
        try (InputStream in = EmbeddedDatabases.class.getResourceAsStream("/db/init.sql")) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        SecureRandom random = new SecureRandom();
        for (String role : new String[] {"order", "payment", "inventory"}) {
            byte[] secret = new byte[18];
            random.nextBytes(secret);
            String password = HexFormat.of().formatHex(secret);
            PASSWORDS.put(role + "_svc", password);
            sql = sql.replace("${" + role + "_password}", password);
        }
        try (Connection c = postgres.getPostgresDatabase().getConnection(); Statement st = c.createStatement()) {
            String code = sql.lines().filter(l -> !l.strip().startsWith("--")).reduce("", (x, y) -> x + "\n" + y);
            for (String statement : code.split(";")) {
                String body = statement.strip();
                if (!body.isEmpty()) {
                    st.execute(body); // CREATE DATABASE cannot run inside a transaction, so one at a time
                }
            }
        }
    }

    /** Stops PostgreSQL at JVM exit. */
    private static void stop() {
        try {
            postgres.close();
        } catch (IOException ignored) {
            // the process is exiting anyway
        }
    }
}

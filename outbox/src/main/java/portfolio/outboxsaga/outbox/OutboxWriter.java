package portfolio.outboxsaga.outbox;

import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Appends events to the outbox table inside the caller's transaction, and refuses to run outside one. */
@Component
public class OutboxWriter {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public OutboxWriter(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Writes one event; it commits or rolls back together with the state change that caused it. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(String aggregateType, String aggregateId, String type, Object payload) {
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(type, "type");
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO outbox (id, aggregatetype, aggregateid, type, payload) VALUES (?, ?, ?, ?, ?::jsonb)")
                .params(id, aggregateType, aggregateId, type, json.writeValueAsString(payload))
                .update();
        return id;
    }
}

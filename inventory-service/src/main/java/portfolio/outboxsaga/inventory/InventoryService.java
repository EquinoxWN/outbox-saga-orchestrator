package portfolio.outboxsaga.inventory;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import portfolio.outboxsaga.outbox.OutboxWriter;

/** Stock reservations, idempotent per order, each written together with its outbox event. */
@Service
public class InventoryService {
    /** A reservation and whether this call made it. */
    public record Result(Reservation reservation, boolean created) {}

    private final JdbcClient jdbc;
    private final OutboxWriter outbox;

    public InventoryService(JdbcClient jdbc, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    /** Takes stock if enough is available, otherwise rejects; a repeated request returns the first outcome. */
    @Transactional
    public Result reserve(ReservationRequest in) {
        // Claim the order id first: a concurrent duplicate waits on the key, then sees the conflict.
        int claimed = jdbc.sql("INSERT INTO reservations (order_id, sku, quantity, status) VALUES (?, ?, ?, 'PENDING') ON CONFLICT (order_id) DO NOTHING")
                .params(in.orderId(), in.sku(), in.quantity())
                .update();
        if (claimed == 0) {
            return new Result(find(in.orderId()).orElseThrow(), false);
        }
        // The row lock plus the re-checked WHERE clause make overselling impossible under concurrency.
        int taken = jdbc.sql("UPDATE stock SET available = available - ? WHERE sku = ? AND available >= ?")
                .params(in.quantity(), in.sku(), in.quantity())
                .update();
        String status = taken == 1 ? "RESERVED" : "REJECTED";
        jdbc.sql("UPDATE reservations SET status = ? WHERE order_id = ?").params(status, in.orderId()).update();
        Reservation r = new Reservation(in.orderId(), in.sku(), in.quantity(), status);
        if (taken == 1) {
            outbox.append("Reservation", in.orderId().toString(), "StockReserved", r);
        } else {
            outbox.append("Reservation", in.orderId().toString(), "StockRejected", Map.of("reservation", r, "reason", "insufficient stock or unknown sku"));
        }
        return new Result(r, true);
    }

    /** The reservation for this order, if any. */
    @Transactional(readOnly = true)
    public Optional<Reservation> find(UUID orderId) {
        return jdbc.sql("SELECT order_id, sku, quantity, status FROM reservations WHERE order_id = ?")
                .param(orderId)
                .query(Reservation.class)
                .optional();
    }

    /** Units of a SKU still available. */
    @Transactional(readOnly = true)
    public int available(String sku) {
        return jdbc.sql("SELECT available FROM stock WHERE sku = ?").param(sku).query(Integer.class).single();
    }
}

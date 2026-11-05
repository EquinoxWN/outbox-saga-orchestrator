package portfolio.outboxsaga.order;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import portfolio.outboxsaga.outbox.OutboxWriter;

/** Order state changes, each written together with its outbox event. */
@Service
public class OrderService {
    /** An order and whether this call created it. */
    public record Result(Order order, boolean created) {}

    /** The idempotency key was already used for a different order. */
    public static class KeyReusedException extends RuntimeException {
        KeyReusedException() {
            super("Idempotency-Key was already used with a different order");
        }
    }

    private final JdbcClient jdbc;
    private final OutboxWriter outbox;

    public OrderService(JdbcClient jdbc, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    /** Stores a PENDING order and its OrderCreated event in one transaction. */
    @Transactional
    public Order create(NewOrder in) {
        return create(in, null).order();
    }

    /** Like create(in); a repeated key returns the first order and writes no second event. */
    @Transactional
    public Result create(NewOrder in, String idempotencyKey) {
        Order order = new Order(UUID.randomUUID(), in.customerId(), in.sku(), in.quantity(), in.amountCents(), "PENDING");
        // A concurrent duplicate waits on the unique key, then sees the conflict.
        int inserted = jdbc.sql("INSERT INTO orders (id, customer_id, sku, quantity, amount_cents, status, idempotency_key)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (idempotency_key) DO NOTHING")
                .params(order.id(), order.customerId(), order.sku(), order.quantity(), order.amountCents(), order.status(), idempotencyKey)
                .update();
        if (inserted == 0) {
            Order first = jdbc.sql("SELECT id, customer_id, sku, quantity, amount_cents, status FROM orders WHERE idempotency_key = ?")
                    .param(idempotencyKey)
                    .query(Order.class)
                    .single();
            if (!sameRequest(first, in)) {
                throw new KeyReusedException();
            }
            return new Result(first, false);
        }
        outbox.append("Order", order.id().toString(), "OrderCreated", order);
        return new Result(order, true);
    }

    /** The order with this id, if any. */
    @Transactional(readOnly = true)
    public Optional<Order> find(UUID id) {
        return jdbc.sql("SELECT id, customer_id, sku, quantity, amount_cents, status FROM orders WHERE id = ?")
                .param(id)
                .query(Order.class)
                .optional();
    }

    /** True if the stored order was made from the same request body. */
    private static boolean sameRequest(Order o, NewOrder in) {
        return o.customerId().equals(in.customerId()) && o.sku().equals(in.sku())
                && o.quantity() == in.quantity() && o.amountCents() == in.amountCents();
    }
}

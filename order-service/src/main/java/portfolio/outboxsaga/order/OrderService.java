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
    private final JdbcClient jdbc;
    private final OutboxWriter outbox;

    public OrderService(JdbcClient jdbc, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    /** Stores a PENDING order and its OrderCreated event in one transaction. */
    @Transactional
    public Order create(NewOrder in) {
        Order order = new Order(UUID.randomUUID(), in.customerId(), in.sku(), in.quantity(), in.amountCents(), "PENDING");
        jdbc.sql("INSERT INTO orders (id, customer_id, sku, quantity, amount_cents, status) VALUES (?, ?, ?, ?, ?, ?)")
                .params(order.id(), order.customerId(), order.sku(), order.quantity(), order.amountCents(), order.status())
                .update();
        outbox.append("Order", order.id().toString(), "OrderCreated", order);
        return order;
    }

    /** The order with this id, if any. */
    @Transactional(readOnly = true)
    public Optional<Order> find(UUID id) {
        return jdbc.sql("SELECT id, customer_id, sku, quantity, amount_cents, status FROM orders WHERE id = ?")
                .param(id)
                .query(Order.class)
                .optional();
    }
}

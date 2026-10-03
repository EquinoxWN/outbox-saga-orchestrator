package portfolio.outboxsaga.payment;

import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import portfolio.outboxsaga.outbox.OutboxWriter;

/** Payment decisions, idempotent per order, each written together with its outbox event. */
@Service
public class PaymentService {
    /** A decision and whether this call made it. */
    public record Result(Payment payment, boolean created) {}

    private final JdbcClient jdbc;
    private final OutboxWriter outbox;
    private final long limitCents;

    public PaymentService(JdbcClient jdbc, OutboxWriter outbox, @Value("${payment.limit-cents}") long limitCents) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.limitCents = limitCents;
    }

    /** Authorizes up to the limit, declines above it; a repeated request returns the first decision. */
    @Transactional
    public Result authorize(PaymentRequest in) {
        String status = in.amountCents() <= limitCents ? "AUTHORIZED" : "DECLINED";
        int inserted = jdbc.sql("INSERT INTO payments (order_id, amount_cents, status) VALUES (?, ?, ?) ON CONFLICT (order_id) DO NOTHING")
                .params(in.orderId(), in.amountCents(), status)
                .update();
        if (inserted == 0) {
            return new Result(find(in.orderId()).orElseThrow(), false); // duplicate: no second event
        }
        Payment payment = new Payment(in.orderId(), in.amountCents(), status);
        String type = "AUTHORIZED".equals(status) ? "PaymentAuthorized" : "PaymentDeclined";
        outbox.append("Payment", in.orderId().toString(), type, payment);
        return new Result(payment, true);
    }

    /** The decision for this order, if any. */
    @Transactional(readOnly = true)
    public Optional<Payment> find(UUID orderId) {
        return jdbc.sql("SELECT order_id, amount_cents, status FROM payments WHERE order_id = ?")
                .param(orderId)
                .query(Payment.class)
                .optional();
    }
}

package portfolio.outboxsaga.payment;

import java.util.UUID;

/** The payment decision for one order. */
public record Payment(UUID orderId, long amountCents, String status) {}

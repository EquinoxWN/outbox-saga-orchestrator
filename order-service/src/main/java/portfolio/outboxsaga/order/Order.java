package portfolio.outboxsaga.order;

import java.util.UUID;

/** A stored order. */
public record Order(UUID id, String customerId, String sku, int quantity, long amountCents, String status) {}

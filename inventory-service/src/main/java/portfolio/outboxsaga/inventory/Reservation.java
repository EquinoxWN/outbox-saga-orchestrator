package portfolio.outboxsaga.inventory;

import java.util.UUID;

/** The stock reservation for one order. */
public record Reservation(UUID orderId, String sku, int quantity, String status) {}

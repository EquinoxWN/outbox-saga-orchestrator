package portfolio.outboxsaga.payment;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** The body of POST /payments. */
public record PaymentRequest(@NotNull UUID orderId, @Min(1) @Max(100_000_000) long amountCents) {}

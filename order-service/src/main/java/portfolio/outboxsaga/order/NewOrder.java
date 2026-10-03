package portfolio.outboxsaga.order;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** The body of POST /orders. */
public record NewOrder(
        @NotBlank @Size(max = 64) String customerId,
        @NotBlank @Pattern(regexp = "[A-Z0-9-]{1,32}") String sku,
        @Min(1) @Max(1000) int quantity,
        @Min(1) @Max(100_000_000) long amountCents) {}

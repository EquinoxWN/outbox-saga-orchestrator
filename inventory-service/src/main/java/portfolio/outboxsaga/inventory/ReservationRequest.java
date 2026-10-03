package portfolio.outboxsaga.inventory;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

/** The body of POST /reservations. */
public record ReservationRequest(
        @NotNull UUID orderId, @NotNull @Pattern(regexp = "[A-Z0-9-]{1,32}") String sku, @Min(1) @Max(1000) int quantity) {}

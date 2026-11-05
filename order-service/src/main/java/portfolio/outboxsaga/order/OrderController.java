package portfolio.outboxsaga.order;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** HTTP API of the order service. */
@RestController
@RequestMapping("/orders")
class OrderController {
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final OrderService orders;

    OrderController(OrderService orders) {
        this.orders = orders;
    }

    @PostMapping
    ResponseEntity<Order> create(
            @Valid @RequestBody NewOrder in, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        if (key != null && !KEY.matcher(key).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key must be 1-64 letters, digits, '-' or '_'");
        }
        OrderService.Result r = orders.create(in, key);
        return r.created()
                ? ResponseEntity.created(URI.create("/orders/" + r.order().id())).body(r.order())
                : ResponseEntity.ok(r.order());
    }

    @GetMapping("/{id}")
    ResponseEntity<Order> get(@PathVariable UUID id) {
        return ResponseEntity.of(orders.find(id));
    }

    @ExceptionHandler(OrderService.KeyReusedException.class)
    ResponseEntity<String> keyReused(OrderService.KeyReusedException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(e.getMessage());
    }
}

package portfolio.outboxsaga.payment;

import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP API of the payment service. */
@RestController
@RequestMapping("/payments")
class PaymentController {
    private final PaymentService payments;

    PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @PostMapping
    ResponseEntity<Payment> authorize(@Valid @RequestBody PaymentRequest in) {
        PaymentService.Result r = payments.authorize(in);
        return r.created()
                ? ResponseEntity.created(URI.create("/payments/" + in.orderId())).body(r.payment())
                : ResponseEntity.ok(r.payment());
    }
}

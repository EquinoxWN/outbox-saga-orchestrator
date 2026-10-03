package portfolio.outboxsaga.inventory;

import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP API of the inventory service. */
@RestController
@RequestMapping("/reservations")
class InventoryController {
    private final InventoryService inventory;

    InventoryController(InventoryService inventory) {
        this.inventory = inventory;
    }

    @PostMapping
    ResponseEntity<Reservation> reserve(@Valid @RequestBody ReservationRequest in) {
        InventoryService.Result r = inventory.reserve(in);
        return r.created()
                ? ResponseEntity.created(URI.create("/reservations/" + in.orderId())).body(r.reservation())
                : ResponseEntity.ok(r.reservation());
    }
}

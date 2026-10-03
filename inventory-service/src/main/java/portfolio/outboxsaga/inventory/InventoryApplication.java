package portfolio.outboxsaga.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** The inventory service: owns inventory_db. */
@SpringBootApplication(scanBasePackages = {"portfolio.outboxsaga.inventory", "portfolio.outboxsaga.outbox"})
public class InventoryApplication {
    public static void main(String[] args) {
        SpringApplication.run(InventoryApplication.class, args);
    }
}

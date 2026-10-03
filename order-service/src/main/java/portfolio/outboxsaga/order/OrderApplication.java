package portfolio.outboxsaga.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** The order service: owns order_db. */
@SpringBootApplication(scanBasePackages = {"portfolio.outboxsaga.order", "portfolio.outboxsaga.outbox"})
public class OrderApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}

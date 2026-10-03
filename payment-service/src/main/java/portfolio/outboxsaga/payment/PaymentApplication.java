package portfolio.outboxsaga.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** The payment service: owns payment_db. */
@SpringBootApplication(scanBasePackages = {"portfolio.outboxsaga.payment", "portfolio.outboxsaga.outbox"})
public class PaymentApplication {
    public static void main(String[] args) {
        SpringApplication.run(PaymentApplication.class, args);
    }
}

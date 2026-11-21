package lk.thogamura.networkserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;

import java.util.concurrent.CountDownLatch;

/**
 * Spring lifecycle + JDBC/Flyway bootstrap.
 * Hosts TCP command listener and Java NIO notification feed.
 */
@SpringBootApplication
public class NetworkServerApplication {
    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(NetworkServerApplication.class, args);
        CountDownLatch closeLatch = new CountDownLatch(1);
        context.addApplicationListener(event -> {
            if (event instanceof ContextClosedEvent) {
                closeLatch.countDown();
            }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(closeLatch::countDown));
        try {
            closeLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

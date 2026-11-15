package lk.thogamura.networkserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring lifecycle + JDBC/Flyway bootstrap.
 * Socket listeners are NOT implemented in this starter.
 * Owners M1/M2/M3/M5 will add explicit JDK networking services.
 */
@SpringBootApplication
public class NetworkServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(NetworkServerApplication.class, args);
    }
}

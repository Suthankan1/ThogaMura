package lk.thogamura.gateway.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.List;

/**
 * Centralized CORS configuration restricting browser access to approved frontend origins.
 *
 * <p>Prevents arbitrary cross-origin access and wildcards while supporting same-origin
 * reverse-proxying from the Next.js frontend (port 3000) and configured demo origins.</p>
 */
@Configuration
public class GatewayCorsConfiguration implements WebMvcConfigurer {

    private static final Logger logger = LoggerFactory.getLogger(GatewayCorsConfiguration.class);

    private final List<String> allowedOrigins;

    public GatewayCorsConfiguration(
            @Value("${thogamura.cors.allowed-origins:http://localhost:3000,http://127.0.0.1:3000}") String allowedOriginsStr) {
        if (allowedOriginsStr != null && !allowedOriginsStr.isBlank()) {
            this.allowedOrigins = Arrays.stream(allowedOriginsStr.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        } else {
            this.allowedOrigins = List.of("http://localhost:3000", "http://127.0.0.1:3000");
        }
        logger.info("Gateway CORS policy initialized with allowed origins: {}", this.allowedOrigins);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/v1/**")
                .allowedOrigins(allowedOrigins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }
}

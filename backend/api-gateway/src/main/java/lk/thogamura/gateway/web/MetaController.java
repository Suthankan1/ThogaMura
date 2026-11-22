package lk.thogamura.gateway.web;

import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Identifies the gateway build. Connectivity is measured by network-status. */
@RestController
public class MetaController {
    @GetMapping("/api/v1/meta")
    public Map<String, Object> meta() {
        return Map.of(
                "name", "ThogaMura",
                "project", "Java Network Programming Assignment 02",
                "service", "thogamura-api-gateway",
                "version", "0.1.0-SNAPSHOT",
                "timestamp", Instant.now().toString(),
                "scaffold", false,
                "networkingImplemented", true
        );
    }
}

package lk.thogamura.gateway.web;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Honest scaffold endpoint; this does not claim the network server works. */
@RestController
public class MetaController {
    @GetMapping("/api/v1/meta")
    public Map<String, Object> meta() {
        return Map.of(
                "name", "ThogaMura",
                "project", "Java Network Programming Assignment 02",
                "scaffold", true,
                "networkingImplemented", false
        );
    }
}

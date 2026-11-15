package lk.thogamura.gateway.web;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

/**
 * Scaffold test only; intentionally NOT a Java network integration test.
 * Actual socket tests will be written by the five member owners.
 */
class MetaControllerTest {
    @Test
    void describesTheStarterHonestly() {
        Map<String, Object> response = new MetaController().meta();
        assertEquals("ThogaMura", response.get("name"));
        assertTrue((Boolean) response.get("scaffold"));
        assertEquals(false, response.get("networkingImplemented"));
    }
}

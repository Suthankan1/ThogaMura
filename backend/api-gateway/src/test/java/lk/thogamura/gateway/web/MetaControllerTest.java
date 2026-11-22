package lk.thogamura.gateway.web;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

class MetaControllerTest {
    @Test
    void describesTheGatewayBuild() {
        Map<String, Object> response = new MetaController().meta();
        assertEquals("ThogaMura", response.get("name"));
        assertEquals(false, response.get("scaffold"));
        assertEquals(true, response.get("networkingImplemented"));
        assertEquals("thogamura-api-gateway", response.get("service"));
    }
}

package lk.thogamura.protocol.access;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lk.thogamura.protocol.tcp.TcpMessageCodec;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceAccessTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String COMMAND = "REQUEST_STOCK";
    private static final String ID = UUID.randomUUID().toString();
    private static final WorkspaceAccess.Actor ACTOR = new WorkspaceAccess.Actor("north_demo", WorkspaceAccess.Role.OPERATOR, "north");
    private final WorkspaceAccess access = new WorkspaceAccess(KEY);

    @Test
    void verifiesTheAssertionAfterAJsonWireRoundTrip() throws Exception {
        String wire = TcpMessageCodec.formatRequest(WorkspaceAccess.VERSION, COMMAND, ID,
                access.sign(ACTOR, COMMAND, ID, Map.of("sku", "ITEM-007", "quantity", 2)));
        JsonNode node = TcpMessageCodec.parseJsonObject(wire);
        TcpMessageCodec.validateEnvelope(node, WorkspaceAccess.VERSION);
        assertThat(access.verify(COMMAND, ID, node.path("data"))).isEqualTo(ACTOR);
    }

    @Test
    void rejectsChangesToBranchRoleArgumentsCommandAndCorrelationId() {
        assertThat(access.verify("RECEIVE_STOCK", ID, signed())).isNull();
        assertThat(access.verify(COMMAND, UUID.randomUUID().toString(), signed())).isNull();

        ObjectNode altered = signed();
        ((ObjectNode) altered.path("args")).put("quantity", 3);
        assertThat(access.verify(COMMAND, ID, altered)).isNull();

        altered = signed();
        ((ObjectNode) altered.path("access")).put("role", WorkspaceAccess.Role.SUPERVISOR.name());
        assertThat(access.verify(COMMAND, ID, altered)).isNull();

        altered = signed();
        ((ObjectNode) altered.path("access")).put("branch", "south");
        assertThat(access.verify(COMMAND, ID, altered)).isNull();

        altered = signed();
        ((ObjectNode) altered.path("access")).put("expires", 1);
        assertThat(access.verify(COMMAND, ID, altered)).isNull();
    }

    @Test
    void refusesInvalidKeysAndMissingIdentity() {
        assertThatThrownBy(() -> new WorkspaceAccess("aaaa")).isInstanceOf(IllegalArgumentException.class);
        assertThat(access.verify(COMMAND, ID, TcpMessageCodec.getObjectMapper().createObjectNode())).isNull();
    }

    private ObjectNode signed() {
        return TcpMessageCodec.getObjectMapper().valueToTree(
                access.sign(ACTOR, COMMAND, ID, Map.of("sku", "ITEM-007", "quantity", 2)));
    }
}

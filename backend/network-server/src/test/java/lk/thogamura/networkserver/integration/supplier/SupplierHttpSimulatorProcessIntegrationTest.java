package lk.thogamura.networkserver.integration.supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Multi-process integration test verifying that {@link SupplierHttpSimulator} operates
 * in an independent OS process and communicates across real TCP/HTTP socket boundaries.
 *
 * <p>Proves Member 2 coursework criteria:
 * <ul>
 *   <li>The mock supplier runs in a separate Java Virtual Machine (JVM).</li>
 *   <li>Zero in-process method invocation or simulated memory shortcut.</li>
 *   <li>HTTP requests cross local network sockets between two distinct processes.</li>
 *   <li>Clean shutdown via interactive command stream.</li>
 * </ul>
 * </p>
 */
class SupplierHttpSimulatorProcessIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern LISTENING_PATTERN = Pattern.compile("Listening on: http://127\\.0\\.0\\.1:(\\d+)");

    private Process process;
    private BufferedReader processReader;
    private BufferedWriter processWriter;
    private int port;

    @AfterEach
    void tearDown() {
        if (process != null && process.isAlive()) {
            try {
                if (processWriter != null) {
                    processWriter.write("quit\n");
                    processWriter.flush();
                }
                boolean exited = process.waitFor(3, TimeUnit.SECONDS);
                if (!exited) {
                    process.destroyForcibly();
                }
            } catch (Exception e) {
                process.destroyForcibly();
            }
        }
    }

    @Test
    @DisplayName("Verify real HTTP communication across two distinct JVM processes")
    void shouldCommunicateWithIndependentJvmProcessOverHttp() throws Exception {
        // 1. Launch SupplierHttpSimulator in a separate JVM process
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classPath = System.getProperty("java.class.path");

        ProcessBuilder pb = new ProcessBuilder(
                javaBin,
                "-cp", classPath,
                "lk.thogamura.networkserver.integration.supplier.SupplierHttpSimulator",
                "0", // Dynamic port binding
                "127.0.0.1"
        );
        pb.redirectErrorStream(true);
        process = pb.start();

        processReader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        processWriter = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

        // 2. Await startup banner and parse dynamically allocated port
        String line;
        boolean foundListening = false;
        long deadline = System.currentTimeMillis() + 8000;

        while (System.currentTimeMillis() < deadline && (line = processReader.readLine()) != null) {
            Matcher matcher = LISTENING_PATTERN.matcher(line);
            if (matcher.find()) {
                port = Integer.parseInt(matcher.group(1));
                foundListening = true;
                break;
            }
        }

        assertThat(foundListening)
                .as("Simulator in separate JVM must print listening line with port")
                .isTrue();
        assertThat(port).isGreaterThan(0);

        String baseUrl = "http://127.0.0.1:" + port;

        // 3. Send real HTTP GET for known positive item (ITEM-008)
        HttpURLConnection conn1 = open(baseUrl + "/v1/stock?sku=ITEM-008");
        assertThat(conn1.getResponseCode()).isEqualTo(200);
        String body1 = readString(conn1.getInputStream());
        JsonNode json1 = OBJECT_MAPPER.readTree(body1);
        assertThat(json1.get("sku").asText()).isEqualTo("ITEM-008");
        assertThat(json1.get("availableUnits").asInt()).isEqualTo(20);
        assertThat(json1.get("supplier").asText()).isEqualTo("Demo Supplier");

        // 4. Send real HTTP GET for known zero item (ITEM-008-ZERO)
        HttpURLConnection conn2 = open(baseUrl + "/v1/stock?sku=ITEM-008-ZERO");
        assertThat(conn2.getResponseCode()).isEqualTo(200);
        String body2 = readString(conn2.getInputStream());
        JsonNode json2 = OBJECT_MAPPER.readTree(body2);
        assertThat(json2.get("sku").asText()).isEqualTo("ITEM-008-ZERO");
        assertThat(json2.get("availableUnits").asInt()).isEqualTo(0);

        // 5. Send real HTTP GET for unknown SKU -> 404 Not Found
        HttpURLConnection conn3 = open(baseUrl + "/v1/stock?sku=UNKNOWN-ITEM-X");
        assertThat(conn3.getResponseCode()).isEqualTo(404);
        String body3 = readString(conn3.getErrorStream());
        JsonNode json3 = OBJECT_MAPPER.readTree(body3);
        assertThat(json3.get("code").asText()).isEqualTo("SKU_NOT_FOUND");

        // 6. Send real HTTP GET for simulated outage -> 503 Service Unavailable
        HttpURLConnection conn4 = open(baseUrl + "/v1/stock?sku=ITEM-503");
        assertThat(conn4.getResponseCode()).isEqualTo(503);
        String body4 = readString(conn4.getErrorStream());
        JsonNode json4 = OBJECT_MAPPER.readTree(body4);
        assertThat(json4.get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");

        // 7. Verify /v1/status endpoint identifies as MOCK_SUPPLIER
        HttpURLConnection conn5 = open(baseUrl + "/v1/status");
        assertThat(conn5.getResponseCode()).isEqualTo(200);
        String body5 = readString(conn5.getInputStream());
        JsonNode json5 = OBJECT_MAPPER.readTree(body5);
        assertThat(json5.get("service").asText()).isEqualTo("MOCK_SUPPLIER");
        assertThat(json5.get("status").asText()).isEqualTo("UP");

        // 8. Send "quit" command via STDIN and verify graceful process termination
        processWriter.write("quit\n");
        processWriter.flush();

        boolean exited = process.waitFor(5, TimeUnit.SECONDS);
        assertThat(exited).as("Separate JVM process should terminate upon receiving 'quit'").isTrue();
        assertThat(process.exitValue()).isEqualTo(0);
    }

    private HttpURLConnection open(String urlStr) throws IOException {
        URI uri = URI.create(urlStr);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(5000);
        conn.setInstanceFollowRedirects(false);
        return conn;
    }

    private String readString(InputStream is) throws IOException {
        if (is == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = reader.readLine()) != null) {
                sb.append(l).append('\n');
            }
            return sb.toString().trim();
        }
    }
}

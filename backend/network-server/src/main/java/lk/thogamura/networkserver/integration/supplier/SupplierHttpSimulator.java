package lk.thogamura.networkserver.integration.supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Independent lightweight HTTP service simulating an external product supplier.
 *
 * <p>Implements Member 2's assessed networking requirements (Issue #31):
 * <ul>
 *   <li>Runs as an independent HTTP server over actual TCP/HTTP sockets.</li>
 *   <li>Advisory availability inquiry route: {@code GET /v1/stock?sku=...}</li>
 *   <li>Enforces frozen contract in {@code docs/contracts/SUPPLIER_HTTP_V1.md}.</li>
 *   <li>Supports deterministic synthetic data (ITEM-008 positive stock and confirmed zero).</li>
 *   <li>Explicit failure modes: 404 (unknown SKU), 503 (service unavailable), timeout delay, malformed JSON.</li>
 *   <li>Clearly identified as {@code MOCK_SUPPLIER}; never accesses or mutates central PostgreSQL inventory.</li>
 * </ul>
 * </p>
 */
public class SupplierHttpSimulator implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(SupplierHttpSimulator.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 8090;
    public static final String DEFAULT_SUPPLIER_NAME = "Demo Supplier";
    public static final String SOURCE_IDENTIFIER = "MOCK_SUPPLIER";
    public static final String SERVER_HEADER = "ThogaMura-MockSupplier/1.0";

    // Synthetic test SKUs defined in test plan and architecture
    public static final String SKU_AVAILABLE_ITEM_008 = "ITEM-008";
    public static final String SKU_AVAILABLE_ITEM_007 = "ITEM-007";
    public static final String SKU_ZERO_ITEM_008 = "ITEM-008-ZERO";
    public static final String SKU_ZERO_GENERIC = "ITEM-ZERO";
    public static final String SKU_SIMULATE_503 = "ITEM-503";
    public static final String SKU_SIMULATE_OUTAGE = "ITEM-OUTAGE";
    public static final String SKU_SIMULATE_TIMEOUT = "ITEM-DELAY";
    public static final String SKU_SIMULATE_MALFORMED = "ITEM-MALFORMED";
    public static final String SKU_SIMULATE_WRONG_SKU = "ITEM-WRONG-SKU";
    public static final String SKU_SIMULATE_MISSING_SUPPLIER = "ITEM-MISSING-SUPPLIER";
    public static final String SKU_SIMULATE_BAD_TIMESTAMP = "ITEM-BAD-TIMESTAMP";
    public static final String SKU_SIMULATE_NEGATIVE_RESTOCK = "ITEM-NEGATIVE-RESTOCK";

    private final String host;
    private final int requestedPort;
    private final HttpServer server;
    private final ExecutorService executor;

    private final ConcurrentMap<String, SupplierStockItem> catalog = new ConcurrentHashMap<>();
    private final AtomicBoolean serviceUnavailable = new AtomicBoolean(false);
    private final AtomicBoolean malformedPayload = new AtomicBoolean(false);
    private final AtomicLong responseDelayMs = new AtomicLong(0);

    private final AtomicLong requestCounter = new AtomicLong(0);
    private final AtomicReference<String> lastRequestedSku = new AtomicReference<>(null);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Creates a simulator bound to loopback {@code 127.0.0.1} and default port {@code 8090}.
     *
     * @throws IOException if the server socket cannot be opened
     */
    public SupplierHttpSimulator() throws IOException {
        this(DEFAULT_HOST, DEFAULT_PORT);
    }

    /**
     * Creates a simulator bound to loopback {@code 127.0.0.1} and the specified port.
     *
     * @param port target TCP port (pass {@code 0} for OS ephemeral port selection)
     * @throws IOException if the server socket cannot be opened
     */
    public SupplierHttpSimulator(int port) throws IOException {
        this(DEFAULT_HOST, port);
    }

    /**
     * Creates a simulator bound to the specified host and port.
     *
     * @param host bind address (e.g. {@code 127.0.0.1})
     * @param port target TCP port (pass {@code 0} for OS ephemeral port selection)
     * @throws IOException if the server socket cannot be opened
     */
    public SupplierHttpSimulator(String host, int port) throws IOException {
        this.host = Objects.requireNonNull(host, "host must not be null");
        this.requestedPort = port;

        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "mock-supplier-http-worker");
            t.setDaemon(true);
            return t;
        });
        this.server.setExecutor(executor);

        // Register HTTP routes
        this.server.createContext("/v1/stock", new StockHandler());
        this.server.createContext("/v1/status", new StatusHandler());
        this.server.createContext("/", new RootHandler());

        // Initialize deterministic synthetic catalog
        resetDefaults();
    }

    /**
     * Starts the HTTP server listener.
     */
    public synchronized void start() {
        if (closed.get()) {
            throw new IllegalStateException("Cannot start a closed SupplierHttpSimulator");
        }
        if (running.compareAndSet(false, true)) {
            server.start();
            logger.info("Supplier HTTP simulator started on {}:{} [service={}]", host, getPort(), SOURCE_IDENTIFIER);
        }
    }

    /**
     * Stops the HTTP server listener and releases socket resources.
     */
    public synchronized void stop() {
        if (running.compareAndSet(true, false)) {
            logger.info("Stopping Supplier HTTP simulator on {}:{}...", host, getPort());
            server.stop(0);
            executor.shutdownNow();
            logger.info("Supplier HTTP simulator stopped successfully");
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            stop();
        }
    }

    /**
     * Returns the actual bound TCP port (useful when initialized with port 0).
     */
    public int getPort() {
        return server.getAddress().getPort();
    }

    public String getHost() {
        return host;
    }

    public String getBaseUrl() {
        return "http://" + host + ":" + getPort();
    }

    public String getStockUrl(String sku) {
        return getBaseUrl() + "/v1/stock?sku=" + URLEncoder.encode(sku, StandardCharsets.UTF_8);
    }

    public boolean isRunning() {
        return running.get();
    }

    // --- Simulator State Controls (for tests and interactive inspection) ---

    public void setServiceUnavailable(boolean unavailable) {
        this.serviceUnavailable.set(unavailable);
        logger.info("[MOCK_SUPPLIER] Forced serviceUnavailable mode set to: {}", unavailable);
    }

    public boolean isServiceUnavailable() {
        return serviceUnavailable.get();
    }

    public void setMalformedPayload(boolean malformed) {
        this.malformedPayload.set(malformed);
        logger.info("[MOCK_SUPPLIER] Forced malformedPayload mode set to: {}", malformed);
    }

    public boolean isMalformedPayload() {
        return malformedPayload.get();
    }

    public void setResponseDelayMs(long delayMs) {
        this.responseDelayMs.set(Math.max(0, delayMs));
        logger.info("[MOCK_SUPPLIER] Forced response delay set to: {} ms", delayMs);
    }

    public long getResponseDelayMs() {
        return responseDelayMs.get();
    }

    public void setStock(String sku, int availableUnits, int estimatedRestockDays) {
        setStock(new SupplierStockItem(sku, DEFAULT_SUPPLIER_NAME, availableUnits, estimatedRestockDays));
    }

    public void setStock(SupplierStockItem item) {
        Objects.requireNonNull(item, "item must not be null");
        catalog.put(item.sku(), item);
        logger.info("[MOCK_SUPPLIER] Updated synthetic stock for SKU '{}': {} units, {} days restock",
                item.sku(), item.availableUnits(), item.estimatedRestockDays());
    }

    public void removeStock(String sku) {
        catalog.remove(sku);
    }

    public long getRequestCount() {
        return requestCounter.get();
    }

    public String getLastRequestedSku() {
        return lastRequestedSku.get();
    }

    public Map<String, SupplierStockItem> getCatalog() {
        return Collections.unmodifiableMap(catalog);
    }

    /**
     * Resets catalog items and simulator failure modes to baseline deterministic state.
     */
    public final void resetDefaults() {
        catalog.clear();
        serviceUnavailable.set(false);
        malformedPayload.set(false);
        responseDelayMs.set(0);

        // Baseline synthetic entries
        setStock(SKU_AVAILABLE_ITEM_008, 20, 3);
        setStock(SKU_AVAILABLE_ITEM_007, 50, 1);
        setStock(SKU_ZERO_ITEM_008, 0, 7);
        setStock(SKU_ZERO_GENERIC, 0, 5);
    }

    // --- HTTP Request Handlers ---

    private class StockHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            requestCounter.incrementAndGet();

            // Enforce GET method
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, createErrorJson("METHOD_NOT_ALLOWED", "Only GET requests are supported on /v1/stock"));
                return;
            }

            Map<String, String> queryParams = parseQuery(exchange.getRequestURI().getRawQuery());
            String sku = queryParams.get("sku");
            String mode = queryParams.get("mode");
            String delayParam = queryParams.get("delayMs");

            lastRequestedSku.set(sku);

            // Validate SKU parameter presence
            if (sku == null || sku.trim().isEmpty()) {
                sendJsonResponse(exchange, 400, createErrorJson("MISSING_PARAMETER", "Query parameter 'sku' is required"));
                return;
            }

            // 1. Timeout simulation: delayed response
            long delay = computeDelayMs(sku, mode, delayParam);
            if (delay > 0) {
                logger.info("[MOCK_SUPPLIER] Delaying response for SKU '{}' by {} ms to simulate timeout", sku, delay);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.warn("[MOCK_SUPPLIER] Sleep interrupted during simulated delay for SKU '{}'", sku);
                    return;
                }
            }

            // 2. Outage simulation: 503 Service Unavailable
            if (isSimulateUnavailable(sku, mode)) {
                logger.info("[MOCK_SUPPLIER] Responding 503 SERVICE_UNAVAILABLE for SKU '{}'", sku);
                sendJsonResponse(exchange, 503, createErrorJson("SERVICE_UNAVAILABLE", "Supplier inventory system undergoing maintenance"));
                return;
            }

            // 3. Malformed JSON payload simulation
            if (isSimulateMalformed(sku, mode)) {
                logger.info("[MOCK_SUPPLIER] Responding with MALFORMED JSON payload for SKU '{}'", sku);
                byte[] rawBrokenJson = ("{\"sku\":\"" + escapeJson(sku) + "\",\"supplier\":\"" + DEFAULT_SUPPLIER_NAME + "\",\"availableUnits\":[MALFORMED_JSON_PAYLOAD_UNCLOSED").getBytes(StandardCharsets.UTF_8);
                sendRawResponse(exchange, 200, "application/json; charset=UTF-8", rawBrokenJson);
                return;
            }

            // 3b. Mismatched or invalid field simulations for contract verification (Issue #65)
            if (SKU_SIMULATE_WRONG_SKU.equalsIgnoreCase(sku)) {
                ObjectNode wrongRoot = OBJECT_MAPPER.createObjectNode();
                wrongRoot.put("sku", "DIFFERENT-SKU-999");
                wrongRoot.put("supplier", DEFAULT_SUPPLIER_NAME);
                wrongRoot.put("availableUnits", 15);
                wrongRoot.put("estimatedRestockDays", 2);
                wrongRoot.put("checkedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
                sendJsonResponse(exchange, 200, wrongRoot);
                return;
            }

            if (SKU_SIMULATE_MISSING_SUPPLIER.equalsIgnoreCase(sku)) {
                ObjectNode missingRoot = OBJECT_MAPPER.createObjectNode();
                missingRoot.put("sku", sku);
                missingRoot.put("availableUnits", 10);
                missingRoot.put("estimatedRestockDays", 2);
                missingRoot.put("checkedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
                sendJsonResponse(exchange, 200, missingRoot);
                return;
            }

            if (SKU_SIMULATE_BAD_TIMESTAMP.equalsIgnoreCase(sku)) {
                ObjectNode badTimeRoot = OBJECT_MAPPER.createObjectNode();
                badTimeRoot.put("sku", sku);
                badTimeRoot.put("supplier", DEFAULT_SUPPLIER_NAME);
                badTimeRoot.put("availableUnits", 10);
                badTimeRoot.put("estimatedRestockDays", 2);
                badTimeRoot.put("checkedAt", "invalid-non-iso-timestamp");
                sendJsonResponse(exchange, 200, badTimeRoot);
                return;
            }

            if (SKU_SIMULATE_NEGATIVE_RESTOCK.equalsIgnoreCase(sku)) {
                ObjectNode negRestockRoot = OBJECT_MAPPER.createObjectNode();
                negRestockRoot.put("sku", sku);
                negRestockRoot.put("supplier", DEFAULT_SUPPLIER_NAME);
                negRestockRoot.put("availableUnits", 10);
                negRestockRoot.put("estimatedRestockDays", -5);
                negRestockRoot.put("checkedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
                sendJsonResponse(exchange, 200, negRestockRoot);
                return;
            }

            // 4. Stock lookup: Known stock (positive or confirmed zero) vs Unknown SKU (404)
            SupplierStockItem item = catalog.get(sku);

            // Support query param mode=zero override for testing confirmed zero state
            if ("zero".equalsIgnoreCase(mode) && item != null) {
                item = new SupplierStockItem(item.sku(), item.supplier(), 0, 7);
            } else if ("zero".equalsIgnoreCase(mode) && item == null) {
                item = new SupplierStockItem(sku, DEFAULT_SUPPLIER_NAME, 0, 7);
            }

            if (item == null) {
                logger.info("[MOCK_SUPPLIER] SKU '{}' not found in supplier catalog -> 404 SKU_NOT_FOUND", sku);
                sendJsonResponse(exchange, 404, createErrorJson("SKU_NOT_FOUND", "Item is not cataloged by supplier"));
                return;
            }

            logger.info("[MOCK_SUPPLIER] Found SKU '{}' -> 200 OK (availableUnits={}, estimatedRestockDays={})",
                    sku, item.availableUnits(), item.estimatedRestockDays());

            ObjectNode root = OBJECT_MAPPER.createObjectNode();
            root.put("sku", item.sku());
            root.put("supplier", item.supplier());
            root.put("availableUnits", item.availableUnits());
            root.put("estimatedRestockDays", item.estimatedRestockDays());
            root.put("checkedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));

            sendJsonResponse(exchange, 200, root);
        }
    }

    private class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            ObjectNode root = OBJECT_MAPPER.createObjectNode();
            root.put("service", SOURCE_IDENTIFIER);
            root.put("status", "UP");
            root.put("version", "1.0.0");
            root.put("host", host);
            root.put("port", getPort());
            root.put("advisoryOnly", true);
            root.put("catalogSize", catalog.size());
            root.put("requestCount", requestCounter.get());
            root.put("serviceUnavailable", serviceUnavailable.get());
            root.put("malformedPayload", malformedPayload.get());
            root.put("responseDelayMs", responseDelayMs.get());
            sendJsonResponse(exchange, 200, root);
        }
    }

    private class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("/".equals(exchange.getRequestURI().getPath())) {
                ObjectNode root = OBJECT_MAPPER.createObjectNode();
                root.put("service", SOURCE_IDENTIFIER);
                root.put("status", "UP");
                root.put("endpoint", "/v1/stock?sku=ITEM-008");
                sendJsonResponse(exchange, 200, root);
            } else {
                sendJsonResponse(exchange, 404, createErrorJson("NOT_FOUND", "Resource not found"));
            }
        }
    }

    // --- Helper Methods ---

    private long computeDelayMs(String sku, String mode, String delayParam) {
        if (delayParam != null) {
            try {
                return Long.parseLong(delayParam);
            } catch (NumberFormatException ignored) {
            }
        }
        if ("timeout".equalsIgnoreCase(mode) || "delay".equalsIgnoreCase(mode)
                || SKU_SIMULATE_TIMEOUT.equalsIgnoreCase(sku)
                || "ITEM-TIMEOUT".equalsIgnoreCase(sku)) {
            return 6000L; // 6 seconds (exceeds default 5s socket read timeout)
        }
        return responseDelayMs.get();
    }

    private boolean isSimulateUnavailable(String sku, String mode) {
        return serviceUnavailable.get()
                || "503".equalsIgnoreCase(mode)
                || "unavailable".equalsIgnoreCase(mode)
                || SKU_SIMULATE_503.equalsIgnoreCase(sku)
                || SKU_SIMULATE_OUTAGE.equalsIgnoreCase(sku)
                || "ITEM-UNAVAILABLE".equalsIgnoreCase(sku);
    }

    private boolean isSimulateMalformed(String sku, String mode) {
        return malformedPayload.get()
                || "malformed".equalsIgnoreCase(mode)
                || SKU_SIMULATE_MALFORMED.equalsIgnoreCase(sku);
    }

    private ObjectNode createErrorJson(String code, String message) {
        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        node.put("code", code);
        node.put("message", message);
        return node;
    }

    private void sendJsonResponse(HttpExchange exchange, int statusCode, ObjectNode jsonNode) throws IOException {
        byte[] bytes = OBJECT_MAPPER.writeValueAsBytes(jsonNode);
        sendRawResponse(exchange, statusCode, "application/json; charset=UTF-8", bytes);
    }

    private void sendRawResponse(HttpExchange exchange, int statusCode, String contentType, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Server", SERVER_HEADER);
        exchange.getResponseHeaders().set("X-Supplier-Source", SOURCE_IDENTIFIER);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
            os.flush();
        } finally {
            exchange.close();
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> params = new HashMap<>();
        String[] pairs = rawQuery.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                params.put(key, value);
            } else if (!pair.isEmpty()) {
                String key = URLDecoder.decode(pair, StandardCharsets.UTF_8);
                params.put(key, "");
            }
        }
        return params;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\"", "\\\"");
    }

    // --- Standalone Process CLI Entrypoint ---

    /**
     * Standalone main method to run the Supplier HTTP Simulator as an independent Java process.
     *
     * <p>Usage: {@code java -cp ... SupplierHttpSimulator [port] [host]}</p>
     *
     * @param args optional command line arguments: [0]=port (default: 8090), [1]=host (default: 127.0.0.1)
     */
    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        String host = args.length > 1 ? args[1] : DEFAULT_HOST;

        try {
            SupplierHttpSimulator simulator = new SupplierHttpSimulator(host, port);
            simulator.start();

            int actualPort = simulator.getPort();
            System.out.println("==================================================================");
            System.out.println("  ThogaMura Independent Supplier HTTP Simulator (Member 2)");
            System.out.println("  Assessed Concept: Java URL/HTTP Networking (Advisory Availability)");
            System.out.println("  Listening on: http://" + host + ":" + actualPort);
            System.out.println("  Stock Route: http://" + host + ":" + actualPort + "/v1/stock?sku=ITEM-008");
            System.out.println("  Status Route: http://" + host + ":" + actualPort + "/v1/status");
            System.out.println("  Identified as: " + SOURCE_IDENTIFIER);
            System.out.println("  NOTICE: Advisory test service only. No access to PostgreSQL.");
            System.out.println("==================================================================");
            System.out.println("Interactive Commands:");
            System.out.println("  status         : Print simulator statistics");
            System.out.println("  503            : Toggle 503 SERVICE_UNAVAILABLE mode");
            System.out.println("  malformed      : Toggle malformed JSON response mode");
            System.out.println("  delay <ms>     : Set response delay in milliseconds (e.g. 'delay 6000')");
            System.out.println("  reset          : Reset synthetic catalog and flags to defaults");
            System.out.println("  quit / exit    : Stop the simulator cleanly");
            System.out.println("==================================================================");

            CountDownLatch shutdownLatch = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                simulator.close();
                shutdownLatch.countDown();
            }, "supplier-shutdown-hook"));

            Scanner scanner = new Scanner(System.in);
            if (!scanner.hasNextLine()) {
                System.out.println("Running in non-interactive background mode (waiting for termination signal)...");
                shutdownLatch.await();
            } else {
                do {
                    String line = scanner.nextLine().trim();
                    if (line.equalsIgnoreCase("quit") || line.equalsIgnoreCase("exit")) {
                        break;
                    }
                    if (line.equalsIgnoreCase("status")) {
                        System.out.println("Requests: " + simulator.getRequestCount() +
                                ", Last SKU: " + simulator.getLastRequestedSku() +
                                ", 503 Mode: " + simulator.isServiceUnavailable() +
                                ", Malformed Mode: " + simulator.isMalformedPayload() +
                                ", Delay: " + simulator.getResponseDelayMs() + "ms");
                        continue;
                    }
                    if (line.equalsIgnoreCase("503") || line.equalsIgnoreCase("unavailable")) {
                        boolean newVal = !simulator.isServiceUnavailable();
                        simulator.setServiceUnavailable(newVal);
                        System.out.println("Toggled 503 SERVICE_UNAVAILABLE mode to: " + newVal);
                        continue;
                    }
                    if (line.equalsIgnoreCase("malformed")) {
                        boolean newVal = !simulator.isMalformedPayload();
                        simulator.setMalformedPayload(newVal);
                        System.out.println("Toggled malformed JSON mode to: " + newVal);
                        continue;
                    }
                    if (line.startsWith("delay")) {
                        String[] parts = line.split("\\s+");
                        if (parts.length > 1) {
                            try {
                                long d = Long.parseLong(parts[1]);
                                simulator.setResponseDelayMs(d);
                                System.out.println("Set response delay to: " + d + "ms");
                            } catch (NumberFormatException e) {
                                System.out.println("Invalid delay format. Usage: delay <ms>");
                            }
                        } else {
                            System.out.println("Current delay: " + simulator.getResponseDelayMs() + "ms. Usage: delay <ms>");
                        }
                        continue;
                    }
                    if (line.equalsIgnoreCase("reset")) {
                        simulator.resetDefaults();
                        System.out.println("Reset simulator catalog and failure modes to defaults.");
                        continue;
                    }
                    if (!line.isEmpty()) {
                        System.out.println("Unknown command: '" + line + "'. Enter 'status', '503', 'malformed', 'delay <ms>', 'reset', or 'quit'.");
                    }
                } while (scanner.hasNextLine());
            }

            System.out.println("Shutting down Supplier HTTP Simulator...");
            simulator.close();
            System.out.println("Supplier HTTP Simulator stopped.");
        } catch (Exception e) {
            System.err.println("Failed to start Supplier HTTP Simulator: " + e.getMessage());
            e.printStackTrace();
        }
    }
}

package lk.thogamura.networkserver.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayloadFingerprintCalculatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Produces identical 64-character hex fingerprint for identical inputs")
    void shouldProduceDeterministicFingerprint() {
        String fp1 = PayloadFingerprintCalculator.calculateStockRequestFingerprint("ITEM-007", 8);
        String fp2 = PayloadFingerprintCalculator.calculateStockRequestFingerprint("ITEM-007", 8);

        assertThat(fp1).isEqualTo(fp2);
        assertThat(fp1).hasSize(64);
        assertThat(fp1).matches("^[a-f0-9]{64}$");
    }

    @Test
    @DisplayName("Trims whitespace from SKU to maintain canonical representation")
    void shouldTrimWhitespaceFromSku() {
        String fp1 = PayloadFingerprintCalculator.calculateStockRequestFingerprint("ITEM-007", 8);
        String fp2 = PayloadFingerprintCalculator.calculateStockRequestFingerprint("  ITEM-007  ", 8);

        assertThat(fp1).isEqualTo(fp2);
    }

    @Test
    @DisplayName("Produces distinct fingerprints for different quantities")
    void shouldDifferentiateQuantities() {
        String fp1 = PayloadFingerprintCalculator.calculateStockRequestFingerprint("ITEM-007", 8);
        String fp2 = PayloadFingerprintCalculator.calculateStockRequestFingerprint("ITEM-007", 5);

        assertThat(fp1).isNotEqualTo(fp2);
    }

    @Test
    @DisplayName("Produces distinct fingerprints for different SKUs")
    void shouldDifferentiateSkus() {
        String fp1 = PayloadFingerprintCalculator.calculateStockRequestFingerprint("ITEM-007", 8);
        String fp2 = PayloadFingerprintCalculator.calculateStockRequestFingerprint("ITEM-008", 8);

        assertThat(fp1).isNotEqualTo(fp2);
    }

    @Test
    @DisplayName("Matches fingerprint calculated from JsonNode regardless of key order")
    void shouldMatchJsonNodeFingerprint() throws Exception {
        String json1 = "{\"sku\":\"ITEM-007\",\"quantity\":8}";
        String json2 = "{\"quantity\":8,\"sku\":\"ITEM-007\"}";

        String fpExpected = PayloadFingerprintCalculator.calculateStockRequestFingerprint("ITEM-007", 8);
        String fpJson1 = PayloadFingerprintCalculator.calculateFingerprint(objectMapper.readTree(json1));
        String fpJson2 = PayloadFingerprintCalculator.calculateFingerprint(objectMapper.readTree(json2));

        assertThat(fpJson1).isEqualTo(fpExpected);
        assertThat(fpJson2).isEqualTo(fpExpected);
    }

    @Test
    @DisplayName("Produces canonical fingerprint for stockRequestId payload")
    void shouldProduceCanonicalFingerprintForStockRequestId() throws Exception {
        java.util.UUID reqId = java.util.UUID.randomUUID();
        String fp1 = PayloadFingerprintCalculator.calculateStockRequestIdFingerprint(reqId);
        String fp2 = PayloadFingerprintCalculator.calculateStockRequestIdFingerprint(reqId);

        assertThat(fp1).isEqualTo(fp2);
        assertThat(fp1).hasSize(64);
        assertThat(fp1).matches("^[a-f0-9]{64}$");

        String json = "{\"stockRequestId\":\"" + reqId + "\"}";
        String fpJson = PayloadFingerprintCalculator.calculateFingerprint(objectMapper.readTree(json));
        assertThat(fpJson).isEqualTo(fp1);
    }
}

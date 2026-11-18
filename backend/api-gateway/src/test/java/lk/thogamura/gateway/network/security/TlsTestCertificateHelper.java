package lk.thogamura.gateway.network.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Test fixture utility that executes standard Java keytool commands to generate
 * synthetic, ephemeral certificates for gateway truststore and hostname verification.
 */
public final class TlsTestCertificateHelper {

    public static final char[] TEST_PASSWORD = ("testPass_" + System.nanoTime()).toCharArray();

    public record CertBundle(
            Path serverKeystore,
            char[] serverPassword,
            Path gatewayTruststore,
            char[] trustPassword,
            Path caCert,
            Path caKeystore
    ) {}

    private TlsTestCertificateHelper() {
    }

    public static String resolveKeytoolBinary() {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null && !javaHome.isBlank()) {
            Path candidate = Path.of(javaHome, "bin", "keytool");
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
            Path candidateExe = Path.of(javaHome, "bin", "keytool.exe");
            if (Files.isExecutable(candidateExe)) {
                return candidateExe.toString();
            }
        }
        return "keytool";
    }

    public static void runKeytool(String... args) throws IOException, InterruptedException {
        String keytoolBin = resolveKeytoolBinary();
        List<String> command = new ArrayList<>();
        command.add(keytoolBin);
        command.addAll(Arrays.asList(args));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        byte[] outputBytes = process.getInputStream().readAllBytes();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            String output = new String(outputBytes);
            throw new IllegalStateException("keytool failed with exit code " + exitCode + ":\n" + output);
        }
    }

    public static CertBundle generateValidBundle(Path workDir, String... extraSan) throws Exception {
        Files.createDirectories(workDir);

        Path caKeystore = workDir.resolve("ca-keystore.p12");
        Path caCert = workDir.resolve("ca.crt");
        Path serverKeystore = workDir.resolve("server-keystore.p12");
        Path gatewayTruststore = workDir.resolve("gateway-truststore.p12");
        Path serverCsr = workDir.resolve("server.csr");
        Path serverCert = workDir.resolve("server.crt");

        String pass = new String(TEST_PASSWORD);

        // 1. Root CA
        runKeytool("-genkeypair",
                "-alias", "thogamura-ca",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "365",
                "-keystore", caKeystore.toString(),
                "-storetype", "PKCS12",
                "-storepass", pass,
                "-dname", "CN=ThogaMura Dev Root CA, OU=Security, O=ThogaMura Coursework, L=Colombo, ST=Western, C=LK",
                "-ext", "BasicConstraints=ca:true,pathlen:0",
                "-ext", "KeyUsage=keyCertSign,cRLSign");

        runKeytool("-exportcert",
                "-alias", "thogamura-ca",
                "-keystore", caKeystore.toString(),
                "-storepass", pass,
                "-rfc",
                "-file", caCert.toString());

        // 2. Server keypair
        runKeytool("-genkeypair",
                "-alias", "thogamura-warehouse",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "90",
                "-keystore", serverKeystore.toString(),
                "-storetype", "PKCS12",
                "-storepass", pass,
                "-dname", "CN=localhost, OU=Warehouse, O=ThogaMura Coursework, L=Colombo, ST=Western, C=LK");

        // 3. CSR
        runKeytool("-certreq",
                "-alias", "thogamura-warehouse",
                "-keystore", serverKeystore.toString(),
                "-storepass", pass,
                "-file", serverCsr.toString());

        // 4. Sign with SAN
        StringBuilder sanBuilder = new StringBuilder("dns:localhost,ip:127.0.0.1");
        for (String san : extraSan) {
            sanBuilder.append(",").append(san);
        }

        runKeytool("-gencert",
                "-alias", "thogamura-ca",
                "-keystore", caKeystore.toString(),
                "-storepass", pass,
                "-infile", serverCsr.toString(),
                "-outfile", serverCert.toString(),
                "-rfc",
                "-validity", "90",
                "-ext", "BasicConstraints=ca:false",
                "-ext", "KeyUsage=digitalSignature,keyEncipherment",
                "-ext", "ExtendedKeyUsage=serverAuth",
                "-ext", "SAN=" + sanBuilder);

        // 5. Chain import
        runKeytool("-importcert",
                "-alias", "thogamura-ca",
                "-keystore", serverKeystore.toString(),
                "-storepass", pass,
                "-noprompt",
                "-file", caCert.toString());

        runKeytool("-importcert",
                "-alias", "thogamura-warehouse",
                "-keystore", serverKeystore.toString(),
                "-storepass", pass,
                "-file", serverCert.toString());

        // 6. Gateway Truststore with CA only
        runKeytool("-importcert",
                "-alias", "thogamura-ca",
                "-keystore", gatewayTruststore.toString(),
                "-storetype", "PKCS12",
                "-storepass", pass,
                "-noprompt",
                "-file", caCert.toString());

        Files.deleteIfExists(serverCsr);

        return new CertBundle(serverKeystore, TEST_PASSWORD, gatewayTruststore, TEST_PASSWORD, caCert, caKeystore);
    }

    public static Path generateUntrustedServerKeystore(Path workDir) throws Exception {
        Files.createDirectories(workDir);

        Path rogueCaKeystore = workDir.resolve("rogue-ca.p12");
        Path rogueCaCert = workDir.resolve("rogue-ca.crt");
        Path rogueServerKeystore = workDir.resolve("rogue-server-keystore.p12");
        Path rogueServerCsr = workDir.resolve("rogue-server.csr");
        Path rogueServerCert = workDir.resolve("rogue-server.crt");

        String pass = new String(TEST_PASSWORD);

        runKeytool("-genkeypair",
                "-alias", "rogue-ca",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "365",
                "-keystore", rogueCaKeystore.toString(),
                "-storetype", "PKCS12",
                "-storepass", pass,
                "-dname", "CN=Rogue Untrusted CA, OU=Attacker, O=Untrusted Entities Inc, L=Nowhere, C=XX",
                "-ext", "BasicConstraints=ca:true,pathlen:0",
                "-ext", "KeyUsage=keyCertSign,cRLSign");

        runKeytool("-exportcert",
                "-alias", "rogue-ca",
                "-keystore", rogueCaKeystore.toString(),
                "-storepass", pass,
                "-rfc",
                "-file", rogueCaCert.toString());

        runKeytool("-genkeypair",
                "-alias", "thogamura-warehouse",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "90",
                "-keystore", rogueServerKeystore.toString(),
                "-storetype", "PKCS12",
                "-storepass", pass,
                "-dname", "CN=localhost, OU=Rogue Warehouse, O=Attacker, L=Nowhere, C=XX");

        runKeytool("-certreq",
                "-alias", "thogamura-warehouse",
                "-keystore", rogueServerKeystore.toString(),
                "-storepass", pass,
                "-file", rogueServerCsr.toString());

        runKeytool("-gencert",
                "-alias", "rogue-ca",
                "-keystore", rogueCaKeystore.toString(),
                "-storepass", pass,
                "-infile", rogueServerCsr.toString(),
                "-outfile", rogueServerCert.toString(),
                "-rfc",
                "-validity", "90",
                "-ext", "BasicConstraints=ca:false",
                "-ext", "KeyUsage=digitalSignature,keyEncipherment",
                "-ext", "ExtendedKeyUsage=serverAuth",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1");

        runKeytool("-importcert",
                "-alias", "rogue-ca",
                "-keystore", rogueServerKeystore.toString(),
                "-storepass", pass,
                "-noprompt",
                "-file", rogueCaCert.toString());

        runKeytool("-importcert",
                "-alias", "thogamura-warehouse",
                "-keystore", rogueServerKeystore.toString(),
                "-storepass", pass,
                "-file", rogueServerCert.toString());

        Files.deleteIfExists(rogueServerCsr);
        return rogueServerKeystore;
    }

    public static Path generateMismatchedSanServerKeystore(Path workDir, Path caKeystore, Path caCert) throws Exception {
        Files.createDirectories(workDir);

        Path mismatchedKeystore = workDir.resolve("mismatched-server-keystore.p12");
        Path mismatchedCsr = workDir.resolve("mismatched.csr");
        Path mismatchedCert = workDir.resolve("mismatched.crt");

        String pass = new String(TEST_PASSWORD);

        runKeytool("-genkeypair",
                "-alias", "thogamura-warehouse",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "90",
                "-keystore", mismatchedKeystore.toString(),
                "-storetype", "PKCS12",
                "-storepass", pass,
                "-dname", "CN=wrong-host.thogamura.internal, OU=Warehouse, O=ThogaMura Coursework, L=Colombo, ST=Western, C=LK");

        runKeytool("-certreq",
                "-alias", "thogamura-warehouse",
                "-keystore", mismatchedKeystore.toString(),
                "-storepass", pass,
                "-file", mismatchedCsr.toString());

        // SAN explicitly only has wrong-host.thogamura.internal and 192.0.2.1
        runKeytool("-gencert",
                "-alias", "thogamura-ca",
                "-keystore", caKeystore.toString(),
                "-storepass", pass,
                "-infile", mismatchedCsr.toString(),
                "-outfile", mismatchedCert.toString(),
                "-rfc",
                "-validity", "90",
                "-ext", "BasicConstraints=ca:false",
                "-ext", "KeyUsage=digitalSignature,keyEncipherment",
                "-ext", "ExtendedKeyUsage=serverAuth",
                "-ext", "SAN=dns:wrong-host.thogamura.internal,ip:192.0.2.1");

        runKeytool("-importcert",
                "-alias", "thogamura-ca",
                "-keystore", mismatchedKeystore.toString(),
                "-storepass", pass,
                "-noprompt",
                "-file", caCert.toString());

        runKeytool("-importcert",
                "-alias", "thogamura-warehouse",
                "-keystore", mismatchedKeystore.toString(),
                "-storepass", pass,
                "-file", mismatchedCert.toString());

        Files.deleteIfExists(mismatchedCsr);
        return mismatchedKeystore;
    }
}

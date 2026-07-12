package server;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

final class LocalHttpsCertificate {
    private static final Path TLS_DIR = Path.of(".local", "tls");
    private static final Path KEYSTORE_PATH = TLS_DIR.resolve("localhost.p12");
    private static final Path SAN_PATH = TLS_DIR.resolve("localhost.san.txt");
    private static final char[] STORE_PASSWORD = "local-https-changeit".toCharArray();
    private static final String ALIAS = "localhost";

    private LocalHttpsCertificate() {
    }

    static SSLContext sslContext() throws IOException {
        return sslContext(KEYSTORE_PATH, SAN_PATH, detectSubjectAlternativeNames());
    }

    static SSLContext sslContext(Path keystorePath, Path sanPath, List<SubjectAlternativeName> names)
            throws IOException {
        ensureKeystore(keystorePath, sanPath, names);
        try {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try (InputStream input = Files.newInputStream(keystorePath)) {
                keyStore.load(input, STORE_PASSWORD);
            }

            KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, STORE_PASSWORD);
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(keyManagerFactory.getKeyManagers(), null, null);
            return sslContext;
        } catch (GeneralSecurityException e) {
            throw new IOException("failed to load local HTTPS certificate", e);
        }
    }

    static List<String> accessUrls(int port) throws IOException {
        List<String> urls = new ArrayList<>();
        for (SubjectAlternativeName name : detectSubjectAlternativeNames()) {
            if ("DNS".equals(name.type())) {
                urls.add("https://" + name.value() + ":" + port + "/");
            } else if (name.value().contains(":")) {
                urls.add("https://[" + name.value() + "]:" + port + "/");
            } else {
                urls.add("https://" + name.value() + ":" + port + "/");
            }
        }
        return urls;
    }

    static List<SubjectAlternativeName> detectSubjectAlternativeNames() throws IOException {
        Set<SubjectAlternativeName> names = new LinkedHashSet<>();
        names.add(new SubjectAlternativeName("DNS", "localhost"));
        names.add(new SubjectAlternativeName("IP", "127.0.0.1"));
        names.add(new SubjectAlternativeName("IP", "::1"));
        addHostNames(names);
        addNetworkAddresses(names);
        return List.copyOf(names);
    }

    private static void addHostNames(Set<SubjectAlternativeName> names) {
        try {
            addDnsName(names, InetAddress.getLocalHost().getHostName());
            addDnsName(names, InetAddress.getLocalHost().getCanonicalHostName());
        } catch (UnknownHostException ignored) {
            // ホスト名が引けない環境でも localhost 証明書として起動できるようにする。
        }
    }

    private static void addDnsName(Set<SubjectAlternativeName> names, String hostName) {
        if (hostName == null || hostName.isBlank()) {
            return;
        }
        String normalized = hostName.strip();
        if ("0.0.0.0".equals(normalized) || "::".equals(normalized)) {
            return;
        }
        if (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        names.add(new SubjectAlternativeName("DNS", normalized));
        if (!normalized.endsWith(".local") && !normalized.contains(".")) {
            names.add(new SubjectAlternativeName("DNS", normalized + ".local"));
        }
    }

    private static void addNetworkAddresses(Set<SubjectAlternativeName> names) throws SocketException {
        List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
        for (NetworkInterface networkInterface : interfaces) {
            if (!networkInterface.isUp() || networkInterface.isLoopback() || networkInterface.isVirtual()) {
                continue;
            }
            for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()) {
                    continue;
                }
                if (address instanceof Inet4Address || address instanceof Inet6Address) {
                    names.add(new SubjectAlternativeName("IP", address.getHostAddress().split("%", 2)[0]));
                }
            }
        }
    }

    private static void ensureKeystore(Path keystorePath, Path sanPath, List<SubjectAlternativeName> names)
            throws IOException {
        String sanText = sanText(names);
        if (Files.isRegularFile(keystorePath) && Files.isRegularFile(sanPath)
                && sanText.equals(Files.readString(sanPath, StandardCharsets.UTF_8))) {
            return;
        }

        Files.createDirectories(keystorePath.getParent());
        Files.deleteIfExists(keystorePath);
        runKeytool(keystorePath, keytoolSan(names));
        Files.writeString(sanPath, sanText, StandardCharsets.UTF_8);
    }

    private static void runKeytool(Path keystorePath, String san) throws IOException {
        List<String> command = List.of(
                "keytool",
                "-genkeypair",
                "-alias", ALIAS,
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "3650",
                "-storetype", "PKCS12",
                "-keystore", keystorePath.toString(),
                "-storepass", new String(STORE_PASSWORD),
                "-keypass", new String(STORE_PASSWORD),
                "-dname", "CN=localhost, OU=Local Development, O=multimodal-llm-server, L=Local, ST=Local, C=JP",
                "-ext", san,
                "-noprompt");
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new IOException("failed to start keytool for local HTTPS certificate", e);
        }

        String output;
        try {
            output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("keytool failed with exit code " + exitCode + ": " + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while generating local HTTPS certificate", e);
        }
    }

    private static String keytoolSan(List<SubjectAlternativeName> names) {
        List<String> values = new ArrayList<>();
        for (SubjectAlternativeName name : names) {
            values.add(name.type().toLowerCase() + ":" + name.value());
        }
        return "SAN=" + String.join(",", values);
    }

    private static String sanText(List<SubjectAlternativeName> names) {
        List<String> values = new ArrayList<>();
        for (SubjectAlternativeName name : names) {
            values.add(name.type() + ":" + name.value());
        }
        return String.join("\n", values) + "\n";
    }

    record SubjectAlternativeName(String type, String value) {
    }
}

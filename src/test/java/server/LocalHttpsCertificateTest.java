package server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalHttpsCertificateTest {
    @Test
    void createsHttpsServerWithSelfSignedCertificate() throws Exception {
        try (MlServer server = new MlServer("127.0.0.1", 0)) {
            server.start();

            HttpResponse<String> response = localHttpsClient().send(
                    HttpRequest.newBuilder(URI.create("https://localhost:" + server.port() + "/bot.html"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("<!doctype html>"));
        }
    }

    @Test
    void regeneratesKeystoreWhenSubjectAlternativeNamesChange(@TempDir Path tempDir) throws Exception {
        Path keystorePath = tempDir.resolve("localhost.p12");
        Path sanPath = tempDir.resolve("localhost.san.txt");
        List<LocalHttpsCertificate.SubjectAlternativeName> firstNames = List.of(
                new LocalHttpsCertificate.SubjectAlternativeName("DNS", "localhost"),
                new LocalHttpsCertificate.SubjectAlternativeName("IP", "127.0.0.1"));
        List<LocalHttpsCertificate.SubjectAlternativeName> secondNames = List.of(
                new LocalHttpsCertificate.SubjectAlternativeName("DNS", "localhost"),
                new LocalHttpsCertificate.SubjectAlternativeName("IP", "127.0.0.1"),
                new LocalHttpsCertificate.SubjectAlternativeName("IP", "192.168.0.117"));

        LocalHttpsCertificate.sslContext(keystorePath, sanPath, firstNames);
        String firstSan = Files.readString(sanPath, StandardCharsets.UTF_8);

        LocalHttpsCertificate.sslContext(keystorePath, sanPath, secondNames);
        String secondSan = Files.readString(sanPath, StandardCharsets.UTF_8);

        assertTrue(Files.isRegularFile(keystorePath));
        assertEquals("DNS:localhost\nIP:127.0.0.1\n", firstSan);
        assertEquals("DNS:localhost\nIP:127.0.0.1\nIP:192.168.0.117\n", secondSan);
    }

    private static HttpClient localHttpsClient() throws Exception {
        TrustManager[] trustManagers = {
                new X509TrustManager() {
                    @Override
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return new java.security.cert.X509Certificate[0];
                    }

                    @Override
                    public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                    }
                }
        };
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustManagers, null);
        return HttpClient.newBuilder().sslContext(sslContext).build();
    }
}

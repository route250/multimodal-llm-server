package server;

import java.io.IOException;

import javax.net.ssl.SSLContext;

import audio.AudioDiagnostics;

public class Main {
    private static final String DEFAULT_HOST = "0.0.0.0";
    private static final int DEFAULT_PORT = 8443;

    public static void main(String[] args) throws IOException {
        AudioDiagnostics.clearOnStartup();
        new StartupCheck().verify();
        SSLContext sslContext = LocalHttpsCertificate.sslContext();
        MlServer server = new MlServer(resolveHost(args), resolvePort(args), sslContext);
        server.start();
        System.out.printf("HTTPS server listening: %s:%d%n", server.host(), server.port());
        for (String url : LocalHttpsCertificate.accessUrls(server.port())) {
            System.out.println("HTTPS server started: " + url);
        }
    }

    private static int resolvePort(String[] args) {
        if (args.length == 0) {
            return DEFAULT_PORT;
        }
        return Integer.parseInt(args[0]);
    }

    private static String resolveHost(String[] args) {
        if (args.length < 2) {
            return DEFAULT_HOST;
        }
        return args[1];
    }
}

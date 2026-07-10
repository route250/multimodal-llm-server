package server;

import audio.AudioDiagnostics;
import java.io.IOException;

public class Main {
    private static final int DEFAULT_PORT = 8080;

    public static void main(String[] args) throws IOException {
        AudioDiagnostics.clearOnStartup();
        MlServer server = new MlServer(resolvePort(args));
        server.start();
        System.out.printf("HTTP server started: http://localhost:%d/%n", server.port());
    }

    private static int resolvePort(String[] args) {
        if (args.length == 0) {
            return DEFAULT_PORT;
        }
        return Integer.parseInt(args[0]);
    }
}

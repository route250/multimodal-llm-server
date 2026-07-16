package server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.URI;
import org.junit.jupiter.api.Test;

class MlServerHttpRedirectTest {
    @Test
    void permitsPlainHttpOnlyFromLoopbackAddress() throws Exception {
        assertTrue(MlServer.permitsPlainHttp(InetAddress.getByName("127.0.0.1")));
        assertTrue(MlServer.permitsPlainHttp(InetAddress.getByName("::1")));
        assertFalse(MlServer.permitsPlainHttp(InetAddress.getByName("192.168.1.10")));
    }

    @Test
    void createsHttpsRedirectLocationWithOriginalPathAndQuery() {
        String location = MlServer.httpsRedirectLocation(
                "chat.example.local:13080",
                URI.create("/chat/connect?group=group-1&sessionId=user-a"),
                13443);

        assertEquals(
                "https://chat.example.local:13443/chat/connect?group=group-1&sessionId=user-a",
                location);
    }

    @Test
    void createsHttpsRedirectLocationForIpv6Host() {
        String location = MlServer.httpsRedirectLocation(
                "[2001:db8::10]:13080",
                URI.create("/bot.html"),
                13443);

        assertEquals("https://[2001:db8::10]:13443/bot.html", location);
    }

    @Test
    void rejectsInvalidHostHeader() {
        assertThrows(
                IllegalArgumentException.class,
                () -> MlServer.httpsRedirectLocation("example.local/path", URI.create("/bot.html"), 13443));
    }
}

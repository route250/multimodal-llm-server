package server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QBFaceSvgTest {
    @TempDir
    Path tempDir;

    @Test
    void generatesSvgWithScaledConnectedMouth() {
        String svg = QBFaceSvg.generate();

        assertTrue(svg.contains("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"2560\" height=\"1600\""));
        assertTrue(svg.contains("<circle cx=\"720\" cy=\"653\" r=\"164\" fill=\"#20232a\"/>"));
        assertTrue(svg.contains("M 1052.4 997.8 Q 1137.6 1096.2 1224 997.8 Q 1310.4 1096.2 1396.8 997.8"));
        assertTrue(svg.contains("stroke-width=\"21.6\""));
        assertTrue(svg.contains("stroke-linejoin=\"round\""));
    }

    @Test
    void writesGeneratedSvg() throws Exception {
        Path output = tempDir.resolve("sample.svg");

        QBFaceSvg.write(output);

        assertEquals(QBFaceSvg.generate(), Files.readString(output, StandardCharsets.UTF_8));
    }
}

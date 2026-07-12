package server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * テスト用の顔画像 SVG を数値パラメータから生成します。
 */
public final class QBFaceSvg {
    private static final int WIDTH = 2560;
    private static final int HEIGHT = 1600;

    private static final double EYE_RADIUS = 160.0;
    private static final double EYE_BORDER_WIDTH = 4.0;
    private static final double PUPIL_RADIUS = 98.0;
    private static final double HIGHLIGHT_RADIUS = 54.0;
    private static final double HIGHLIGHT_OFFSET_X = 54.0;
    private static final double HIGHLIGHT_OFFSET_Y = -73.0;

    private static final double MOUTH_CENTER_X = 1224.0;
    private static final double MOUTH_CENTER_Y = 1047.0;
    private static final double MOUTH_SIZE_SCALE = 1.2;
    private static final double MOUTH_STROKE_WIDTH = 18.0 * 1.2;

    private QBFaceSvg() {
    }

    public static void main(String[] args) throws IOException {
        Path output = args.length == 0
                ? Path.of("src/test/test-data/pict/sample.svg")
                : Path.of(args[0]);
        write(output);
    }

    public static void write(Path output) throws IOException {
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(output, generate(), StandardCharsets.UTF_8);
    }

    public static String generate() {
        StringBuilder svg = new StringBuilder();
        svg.append("""
                <svg xmlns="http://www.w3.org/2000/svg" width="2560" height="1600" viewBox="0 0 2560 1600" role="img" aria-labelledby="title desc">
                  <title id="title">Simple face sample</title>
                  <desc id="desc">Two circular eyes with filled pupils and highlights, and a mouth made from two downward arcs.</desc>

                  <!-- JPEG 元画像の角丸背景を再現する。 -->
                  <rect x="0" y="0" width="2560" height="1600" rx="24" ry="24" fill="#f6f6f6"/>

                """);
        appendEye(svg, 720.0, 653.0, "左目");
        svg.append('\n');
        appendEye(svg, 1747.0, 653.0, "右目");
        svg.append('\n');
        appendMouth(svg);
        svg.append("</svg>\n");
        return svg.toString();
    }

    private static void appendEye(StringBuilder svg, double cx, double cy, String label) {
        svg.append("  <!-- ")
                .append(label)
                .append(": 外円、同心円の塗りつぶし、円形ハイライト。 -->\n");
        appendCircle(svg, cx, cy, EYE_RADIUS + EYE_BORDER_WIDTH, "#20232a");
        appendCircle(svg, cx, cy, EYE_RADIUS, "#c04078");
        appendCircle(svg, cx, cy, PUPIL_RADIUS, "#740d38");
        appendCircle(svg, cx + HIGHLIGHT_OFFSET_X, cy + HIGHLIGHT_OFFSET_Y, HIGHLIGHT_RADIUS, "#ffffff");
    }

    private static void appendMouth(StringBuilder svg) {
        Point left = scaled(1081.0, 1006.0);
        Point leftControl = scaled(1152.0, 1088.0);
        Point centerTop = scaled(1224.0, 1006.0);
        Point rightControl = scaled(1296.0, 1088.0);
        Point right = scaled(1368.0, 1006.0);

        svg.append("  <!-- 口: 中央の接続部にも ")
                .append(format(MOUTH_STROKE_WIDTH))
                .append("px の太さを持たせた下向き円弧 2 本。 -->\n");
        svg.append("  <path d=\"")
                .append("M ").append(left)
                .append(" Q ").append(leftControl).append(' ').append(centerTop)
                .append(" Q ").append(rightControl).append(' ').append(right)
                .append("\" fill=\"none\" stroke=\"#000000\" stroke-width=\"")
                .append(format(MOUTH_STROKE_WIDTH))
                .append("\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>\n");
    }

    private static Point scaled(double x, double y) {
        double scaledX = MOUTH_CENTER_X + (x - MOUTH_CENTER_X) * MOUTH_SIZE_SCALE;
        double scaledY = MOUTH_CENTER_Y + (y - MOUTH_CENTER_Y) * MOUTH_SIZE_SCALE;
        return new Point(scaledX, scaledY);
    }

    private static void appendCircle(StringBuilder svg, double cx, double cy, double r, String fill) {
        svg.append("  <circle cx=\"")
                .append(format(cx))
                .append("\" cy=\"")
                .append(format(cy))
                .append("\" r=\"")
                .append(format(r))
                .append("\" fill=\"")
                .append(fill)
                .append("\"/>\n");
    }

    private static String format(double value) {
        if (value == Math.rint(value)) {
            return Long.toString(Math.round(value));
        }
        return String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private record Point(double x, double y) {
        @Override
        public String toString() {
            return format(x) + " " + format(y);
        }
    }
}

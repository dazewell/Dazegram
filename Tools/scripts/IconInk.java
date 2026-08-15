// Measures the ink geometry of Android vector drawables: what fraction of the 24dp canvas a glyph
// actually covers, the bounding box of that coverage, and where its centre sits on both axes.
//
// Why it exists: the composer toolbar sizes every icon into one shared box, so two glyphs with the
// same nominal 24dp canvas can read as very different sizes if their ink fills different amounts of
// it. Eyeballing that from a screenshot bakes in cell size, density, theme and compression noise, so
// the numbers are taken from the source geometry instead.
//
// Run with no build step and no dependencies (JDK 21 single-file source launch):
//
//     java Tools/scripts/IconInk.java TMessagesProj/src/main/res/drawable/formatting_code.xml ...
//     java Tools/scripts/IconInk.java --csv <files...>       machine-readable output
//     java Tools/scripts/IconInk.java --size 2048 <files...> override the render resolution
//     java Tools/scripts/IconInk.java --as-drawn <files...>  honour <group> transforms instead
//
// How it works: every <path> is rendered into ONE grayscale raster - filled if it has a fillColor,
// and its stroke outline filled too if it has a strokeColor. That union is what makes the result
// order-independent and safe when paths overlap, which summing per-path areas would not be. Stroke
// expansion is done by BasicStroke.createStrokedShape, i.e. by the same kind of geometry kernel the
// renderer uses, rather than by anything hand-rolled here.
//
// The mapping onto Android's vector semantics is one-to-one:
//   pathData grammar (M/m L/l H/h V/v C/c S/s Q/q T/t A/a Z)  ->  Path2D.Double
//   fillType="evenOdd" / default                              ->  WIND_EVEN_ODD / WIND_NON_ZERO
//   strokeWidth / strokeLineCap / strokeLineJoin / MiterLimit ->  BasicStroke
//   viewportWidth / viewportHeight                            ->  Graphics2D.scale(N/vw, N/vh)
//
// Expect results to differ from what Skia rasterises on device by up to about 1%. That is the
// antialiasing policy differing, not a defect: coverage at glyph edges is a fraction either way, and
// every number this feeds into is rounded to four decimal places long before it reaches a pixel.
// Do not "fix" that gap - chasing it changes the numbers without making them more true.
//
// Group transforms are STRIPPED by default, deliberately and loudly. Several fork-owned nax_* assets
// already carry a baked <group> scale, and measuring one of those as-drawn and then multiplying a
// fresh correction on top of it would double-apply the old one. The default therefore reports raw
// geometry and prints a warning naming any transform it dropped, so the omission is never silent.
//
// That default is only right for a group that IS a correction. Some upstream artwork uses a group as
// part of the drawing - menu_link_create2 rotates one of its bars by -45 degrees - and stripping that
// does not recover an earlier geometry, it draws a different glyph. Pass --as-drawn for those, and
// read the warning on every file that has a group rather than assuming which kind it is.
//
// The test is provenance, not what the transform does. Ask who added the file:
//
//     git log --diff-filter=A -- TMessagesProj/src/main/res/drawable/<asset>.xml
//
// A file this fork authored (the nax_* set) carries transforms this fork baked in as corrections, so
// measure it RAW and replace the transform outright - never multiply a new scale onto the old one. A
// file that arrived from upstream carries transforms its illustrator drew, so measure it --as-drawn:
// those numbers are the glyph the user actually sees. A rotation is the usual upstream case, but do
// not shortcut to "rotation means artwork" - an upstream asset could be scaled too, and this fork
// could rotate one. Check who added it.

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public final class IconInk {

    private static final String NS = "http://schemas.android.com/apk/res/android";
    private static final int DEFAULT_SIZE = 1024;

    public static void main(String[] args) throws Exception {
        int size = DEFAULT_SIZE;
        boolean csv = false;
        boolean asDrawn = false;
        List<String> files = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--csv" -> csv = true;
                case "--as-drawn" -> asDrawn = true;
                case "--size" -> size = Integer.parseInt(args[++i]);
                default -> files.add(args[i]);
            }
        }
        if (files.isEmpty()) {
            System.err.println("usage: java Tools/scripts/IconInk.java [--csv] [--as-drawn] [--size N] <vector.xml...>");
            System.exit(2);
        }
        if (csv) {
            System.out.println("asset,inkAreaPct,bboxWPct,bboxHPct,aspect,centreXPct,centreYPct,centroidXPct,centroidYPct");
        }
        for (String file : files) {
            try {
                report(measure(new File(file), size, asDrawn), csv);
            } catch (Exception e) {
                System.err.println("FAILED " + file + ": " + e);
            }
        }
    }

    // ---------------------------------------------------------------- measuring

    private record Result(String name, double inkAreaPct, double minXPct, double maxXPct,
                          double minYPct, double maxYPct, double centroidXPct, double centroidYPct,
                          List<String> warnings) {

        double widthPct() {
            return maxXPct - minXPct;
        }

        double heightPct() {
            return maxYPct - minYPct;
        }

        double centreXPct() {
            return (minXPct + maxXPct) / 2;
        }

        double centreYPct() {
            return (minYPct + maxYPct) / 2;
        }
    }

    private static Result measure(File file, int size, boolean asDrawn) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Element root = builder.parse(file).getDocumentElement();
        if (!"vector".equals(root.getTagName())) {
            throw new IllegalArgumentException("not a <vector>: " + root.getTagName());
        }
        double viewportWidth = attrFloat(root, "viewportWidth", 24);
        double viewportHeight = attrFloat(root, "viewportHeight", 24);

        List<String> warnings = new ArrayList<>();
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, size, size);
        g.scale(size / viewportWidth, size / viewportHeight);
        g.setColor(Color.WHITE);
        paint(root, g, warnings, asDrawn);
        g.dispose();

        return summarise(file.getName().replaceFirst("\\.xml$", ""), image, size, warnings);
    }

    private static void paint(Element parent, Graphics2D g, List<String> warnings, boolean asDrawn) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element element = (Element) node;
            switch (element.getTagName()) {
                case "group" -> {
                    String transform = describeTransform(element);
                    if (transform == null) {
                        paint(element, g, warnings, asDrawn);
                        break;
                    }
                    if (asDrawn) {
                        warnings.add("applied <group> transform (" + transform + ") - numbers below are AS DRAWN,"
                                + " so they already contain any correction baked into this asset");
                        AffineTransform saved = g.getTransform();
                        g.transform(groupTransform(element));
                        paint(element, g, warnings, true);
                        g.setTransform(saved);
                    } else {
                        warnings.add("stripped <group> transform (" + transform + ") - numbers below are RAW"
                                + " geometry, do not multiply a new scale onto the old one."
                                + " If this transform is upstream artwork rather than a correction, re-run with --as-drawn");
                        paint(element, g, warnings, false);
                    }
                }
                case "path" -> paintPath(element, g, warnings);
                case "clip-path" -> warnings.add("<clip-path> ignored - measured ink may overstate what is drawn");
                default -> {
                    // aapt:attr gradients and the like carry no geometry of their own.
                }
            }
        }
    }

    /**
     * The group matrix VectorDrawable builds: translate(-pivot), scale, rotate, translate(offset +
     * pivot). Written here in the reverse order AWT pre-concatenates in, which composes to the same
     * transform.
     */
    private static AffineTransform groupTransform(Element group) {
        double pivotX = attrFloat(group, "pivotX", 0);
        double pivotY = attrFloat(group, "pivotY", 0);
        AffineTransform t = new AffineTransform();
        t.translate(attrFloat(group, "translateX", 0) + pivotX, attrFloat(group, "translateY", 0) + pivotY);
        t.rotate(Math.toRadians(attrFloat(group, "rotation", 0)));
        t.scale(attrFloat(group, "scaleX", 1), attrFloat(group, "scaleY", 1));
        t.translate(-pivotX, -pivotY);
        return t;
    }

    private static void paintPath(Element element, Graphics2D g, List<String> warnings) {
        String data = attr(element, "pathData");
        if (data == null || data.isBlank()) {
            return;
        }
        String fillColor = attr(element, "fillColor");
        String strokeColor = attr(element, "strokeColor");
        double fillAlpha = attrFloat(element, "fillAlpha", 1);
        double strokeAlpha = attrFloat(element, "strokeAlpha", 1);
        if (fillAlpha < 1 || strokeAlpha < 1) {
            warnings.add("partially transparent path counted as fully opaque ink"
                    + " (fillAlpha=" + fillAlpha + ", strokeAlpha=" + strokeAlpha + ")");
        }

        Path2D.Double path = PathParser.parse(data);
        if (isPainted(fillColor, warnings)) {
            path.setWindingRule("evenOdd".equals(attr(element, "fillType"))
                    ? Path2D.WIND_EVEN_ODD : Path2D.WIND_NON_ZERO);
            g.fill(path);
        }
        if (isPainted(strokeColor, warnings)) {
            float width = (float) attrFloat(element, "strokeWidth", 0);
            if (width > 0) {
                Shape outline = new BasicStroke(width, cap(attr(element, "strokeLineCap")),
                        join(attr(element, "strokeLineJoin")),
                        Math.max(1f, (float) attrFloat(element, "strokeMiterLimit", 4)))
                        .createStrokedShape(path);
                g.fill(outline);
            }
        }
    }

    private static Result summarise(String name, BufferedImage image, int size, List<String> warnings) {
        Raster raster = image.getRaster();
        int[] row = new int[size];
        double coverage = 0;
        double weightedX = 0;
        double weightedY = 0;
        int minX = size;
        int maxX = -1;
        int minY = size;
        int maxY = -1;
        for (int y = 0; y < size; y++) {
            raster.getSamples(0, y, size, 1, 0, row);
            for (int x = 0; x < size; x++) {
                int sample = row[x];
                if (sample == 0) {
                    continue;
                }
                double value = sample / 255.0;
                coverage += value;
                weightedX += value * (x + 0.5);
                weightedY += value * (y + 0.5);
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }
        if (maxX < 0) {
            throw new IllegalStateException("no ink rendered - check pathData parsing");
        }
        double n = size;
        return new Result(name,
                100.0 * coverage / (n * n),
                100.0 * minX / n, 100.0 * (maxX + 1) / n,
                100.0 * minY / n, 100.0 * (maxY + 1) / n,
                100.0 * (weightedX / coverage) / n, 100.0 * (weightedY / coverage) / n,
                warnings);
    }

    private static void report(Result r, boolean csv) {
        if (csv) {
            System.out.printf(Locale.ROOT, "%s,%.2f,%.2f,%.2f,%.3f,%.2f,%.2f,%.2f,%.2f%n",
                    r.name(), r.inkAreaPct(), r.widthPct(), r.heightPct(),
                    r.widthPct() / r.heightPct(), r.centreXPct(), r.centreYPct(),
                    r.centroidXPct(), r.centroidYPct());
            r.warnings().forEach(w -> System.err.println("  ! " + r.name() + ": " + w));
            return;
        }
        System.out.printf(Locale.ROOT, "%-32s ink %6.2f%%  bbox %6.2f x %6.2f  aspect %5.3f  "
                        + "bbox centre %6.2f / %6.2f  centroid %6.2f / %6.2f%n",
                r.name(), r.inkAreaPct(), r.widthPct(), r.heightPct(),
                r.widthPct() / r.heightPct(), r.centreXPct(), r.centreYPct(),
                r.centroidXPct(), r.centroidYPct());
        r.warnings().forEach(w -> System.out.println("    ! " + w));
    }

    // ---------------------------------------------------------------- attributes

    /**
     * Whether a colour attribute actually puts ink down. A fully transparent colour is a real idiom
     * here - nax_composer_expand declares fillColor="@android:color/transparent" so its bracket paths
     * are stroked but not filled - and counting it as ink would credit the glyph with four solid
     * triangles it never draws.
     */
    private static boolean isPainted(String color, List<String> warnings) {
        if (color == null || color.isBlank()) {
            return false;
        }
        String value = color.trim();
        if (value.startsWith("@")) {
            if (value.endsWith("/transparent")) {
                return false;
            }
            warnings.add("colour " + value + " is a resource reference and cannot be resolved here;"
                    + " counted as opaque ink");
            return true;
        }
        if (!value.startsWith("#")) {
            return true;
        }
        String hex = value.substring(1);
        return switch (hex.length()) {
            case 4 -> Character.digit(hex.charAt(0), 16) != 0;                        // #ARGB
            case 8 -> Integer.parseInt(hex.substring(0, 2), 16) != 0;                 // #AARRGGBB
            default -> true;                                                          // #RGB, #RRGGBB
        };
    }

    private static String attr(Element element, String name) {
        String value = element.getAttributeNS(NS, name);
        return value == null || value.isEmpty() ? null : value;
    }

    private static double attrFloat(Element element, String name, double fallback) {
        String value = attr(element, name);
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String describeTransform(Element group) {
        StringBuilder sb = new StringBuilder();
        for (String name : new String[]{"scaleX", "scaleY", "translateX", "translateY", "rotation", "pivotX", "pivotY"}) {
            String value = attr(group, name);
            if (value == null) {
                continue;
            }
            if (name.startsWith("pivot") && sb.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(name).append('=').append(value);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private static int cap(String value) {
        if (value == null) {
            return BasicStroke.CAP_BUTT;
        }
        return switch (value) {
            case "round" -> BasicStroke.CAP_ROUND;
            case "square" -> BasicStroke.CAP_SQUARE;
            default -> BasicStroke.CAP_BUTT;
        };
    }

    private static int join(String value) {
        if (value == null) {
            return BasicStroke.JOIN_MITER;
        }
        return switch (value) {
            case "round" -> BasicStroke.JOIN_ROUND;
            case "bevel" -> BasicStroke.JOIN_BEVEL;
            default -> BasicStroke.JOIN_MITER;
        };
    }

    // ---------------------------------------------------------------- path data

    /** Turns an android:pathData string into the equivalent Path2D. */
    private static final class PathParser {

        private final String data;
        private int index;

        private PathParser(String data) {
            this.data = data;
        }

        static Path2D.Double parse(String data) {
            return new PathParser(data).run();
        }

        private Path2D.Double run() {
            Path2D.Double path = new Path2D.Double(Path2D.WIND_NON_ZERO);
            double x = 0, y = 0;          // current point
            double startX = 0, startY = 0; // start of the current subpath
            double lastCubicX = 0, lastCubicY = 0;  // last cubic control point, for S/s
            double lastQuadX = 0, lastQuadY = 0;    // last quadratic control point, for T/t
            char previous = 0;
            char command = 0;
            boolean started = false;

            while (true) {
                skipSeparators();
                if (index >= data.length()) {
                    break;
                }
                char c = data.charAt(index);
                if (Character.isLetter(c)) {
                    command = c;
                    index++;
                } else if (command == 0) {
                    throw new IllegalArgumentException("pathData starts with a number: " + data);
                } else if (command == 'M') {
                    command = 'L'; // repeated moveto arguments are implicit linetos, per the grammar
                } else if (command == 'm') {
                    command = 'l';
                }

                boolean relative = Character.isLowerCase(command);
                char upper = Character.toUpperCase(command);
                if (upper == 'Z') {
                    path.closePath();
                    x = startX;
                    y = startY;
                    previous = upper;
                    continue;
                }
                if (!started && upper != 'M') {
                    throw new IllegalArgumentException("pathData draws before its first moveto: " + data);
                }

                switch (upper) {
                    case 'M' -> {
                        double nx = number() + (relative && started ? x : 0);
                        double ny = number() + (relative && started ? y : 0);
                        path.moveTo(nx, ny);
                        x = startX = nx;
                        y = startY = ny;
                        started = true;
                    }
                    case 'L' -> {
                        double nx = number() + (relative ? x : 0);
                        double ny = number() + (relative ? y : 0);
                        path.lineTo(nx, ny);
                        x = nx;
                        y = ny;
                    }
                    case 'H' -> {
                        double nx = number() + (relative ? x : 0);
                        path.lineTo(nx, y);
                        x = nx;
                    }
                    case 'V' -> {
                        double ny = number() + (relative ? y : 0);
                        path.lineTo(x, ny);
                        y = ny;
                    }
                    case 'C' -> {
                        double x1 = number() + (relative ? x : 0);
                        double y1 = number() + (relative ? y : 0);
                        double x2 = number() + (relative ? x : 0);
                        double y2 = number() + (relative ? y : 0);
                        double nx = number() + (relative ? x : 0);
                        double ny = number() + (relative ? y : 0);
                        path.curveTo(x1, y1, x2, y2, nx, ny);
                        lastCubicX = x2;
                        lastCubicY = y2;
                        x = nx;
                        y = ny;
                    }
                    case 'S' -> {
                        boolean smooth = previous == 'C' || previous == 'S';
                        double x1 = smooth ? 2 * x - lastCubicX : x;
                        double y1 = smooth ? 2 * y - lastCubicY : y;
                        double x2 = number() + (relative ? x : 0);
                        double y2 = number() + (relative ? y : 0);
                        double nx = number() + (relative ? x : 0);
                        double ny = number() + (relative ? y : 0);
                        path.curveTo(x1, y1, x2, y2, nx, ny);
                        lastCubicX = x2;
                        lastCubicY = y2;
                        x = nx;
                        y = ny;
                    }
                    case 'Q' -> {
                        double x1 = number() + (relative ? x : 0);
                        double y1 = number() + (relative ? y : 0);
                        double nx = number() + (relative ? x : 0);
                        double ny = number() + (relative ? y : 0);
                        path.quadTo(x1, y1, nx, ny);
                        lastQuadX = x1;
                        lastQuadY = y1;
                        x = nx;
                        y = ny;
                    }
                    case 'T' -> {
                        boolean smooth = previous == 'Q' || previous == 'T';
                        double x1 = smooth ? 2 * x - lastQuadX : x;
                        double y1 = smooth ? 2 * y - lastQuadY : y;
                        double nx = number() + (relative ? x : 0);
                        double ny = number() + (relative ? y : 0);
                        path.quadTo(x1, y1, nx, ny);
                        lastQuadX = x1;
                        lastQuadY = y1;
                        x = nx;
                        y = ny;
                    }
                    case 'A' -> {
                        double rx = number();
                        double ry = number();
                        double rotation = number();
                        boolean largeArc = flag();
                        boolean sweep = flag();
                        double nx = number() + (relative ? x : 0);
                        double ny = number() + (relative ? y : 0);
                        arcTo(path, x, y, nx, ny, rx, ry, rotation, largeArc, sweep);
                        x = nx;
                        y = ny;
                    }
                    default -> throw new IllegalArgumentException("unsupported path command '" + command + "' in " + data);
                }
                previous = upper;
            }
            return path;
        }

        private void skipSeparators() {
            while (index < data.length()) {
                char c = data.charAt(index);
                if (c == ',' || Character.isWhitespace(c)) {
                    index++;
                } else {
                    return;
                }
            }
        }

        /** Arc flags are single characters and may run straight into the next number, unseparated. */
        private boolean flag() {
            skipSeparators();
            char c = data.charAt(index++);
            if (c != '0' && c != '1') {
                throw new IllegalArgumentException("expected an arc flag, found '" + c + "' in " + data);
            }
            return c == '1';
        }

        private double number() {
            skipSeparators();
            int start = index;
            if (index < data.length() && (data.charAt(index) == '-' || data.charAt(index) == '+')) {
                index++;
            }
            boolean seenDot = false;
            while (index < data.length()) {
                char c = data.charAt(index);
                if (Character.isDigit(c)) {
                    index++;
                } else if (c == '.' && !seenDot) {
                    seenDot = true;
                    index++;
                } else if ((c == 'e' || c == 'E') && index > start) {
                    index++;
                    if (index < data.length() && (data.charAt(index) == '-' || data.charAt(index) == '+')) {
                        index++;
                    }
                } else {
                    break;
                }
            }
            if (index == start) {
                throw new IllegalArgumentException("expected a number at offset " + start + " in " + data);
            }
            return Double.parseDouble(data.substring(start, index));
        }
    }

    /**
     * Appends an SVG elliptical arc as cubic segments, following the endpoint-to-centre conversion in
     * the SVG implementation notes (F.6.5) that Android's own parser also implements.
     */
    private static void arcTo(Path2D.Double path, double x0, double y0, double x1, double y1,
                              double rx, double ry, double rotationDegrees, boolean largeArc, boolean sweep) {
        if (x0 == x1 && y0 == y1) {
            return;
        }
        rx = Math.abs(rx);
        ry = Math.abs(ry);
        if (rx == 0 || ry == 0) {
            path.lineTo(x1, y1);
            return;
        }
        double phi = Math.toRadians(rotationDegrees);
        double cosPhi = Math.cos(phi);
        double sinPhi = Math.sin(phi);

        double dx = (x0 - x1) / 2;
        double dy = (y0 - y1) / 2;
        double x1p = cosPhi * dx + sinPhi * dy;
        double y1p = -sinPhi * dx + cosPhi * dy;

        double lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry);
        if (lambda > 1) {
            double scale = Math.sqrt(lambda);
            rx *= scale;
            ry *= scale;
        }

        double numerator = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p;
        double denominator = rx * rx * y1p * y1p + ry * ry * x1p * x1p;
        double factor = Math.sqrt(Math.max(0, numerator / denominator));
        if (largeArc == sweep) {
            factor = -factor;
        }
        double cxp = factor * rx * y1p / ry;
        double cyp = -factor * ry * x1p / rx;
        double cx = cosPhi * cxp - sinPhi * cyp + (x0 + x1) / 2;
        double cy = sinPhi * cxp + cosPhi * cyp + (y0 + y1) / 2;

        double theta = angle(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry);
        double delta = angle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry);
        if (!sweep && delta > 0) {
            delta -= 2 * Math.PI;
        } else if (sweep && delta < 0) {
            delta += 2 * Math.PI;
        }

        int segments = (int) Math.ceil(Math.abs(delta) / (Math.PI / 2));
        double step = delta / segments;
        double alpha = 4.0 / 3.0 * Math.tan(step / 4);
        for (int i = 0; i < segments; i++) {
            double a0 = theta + i * step;
            double a1 = a0 + step;
            double cos0 = Math.cos(a0), sin0 = Math.sin(a0);
            double cos1 = Math.cos(a1), sin1 = Math.sin(a1);
            double px0 = cx + rx * cosPhi * cos0 - ry * sinPhi * sin0;
            double py0 = cy + rx * sinPhi * cos0 + ry * cosPhi * sin0;
            double px1 = cx + rx * cosPhi * cos1 - ry * sinPhi * sin1;
            double py1 = cy + rx * sinPhi * cos1 + ry * cosPhi * sin1;
            double dx0 = -rx * cosPhi * sin0 - ry * sinPhi * cos0;
            double dy0 = -rx * sinPhi * sin0 + ry * cosPhi * cos0;
            double dx1 = -rx * cosPhi * sin1 - ry * sinPhi * cos1;
            double dy1 = -rx * sinPhi * sin1 + ry * cosPhi * cos1;
            path.curveTo(px0 + alpha * dx0, py0 + alpha * dy0,
                    px1 - alpha * dx1, py1 - alpha * dy1, px1, py1);
        }
    }

    private static double angle(double ux, double uy, double vx, double vy) {
        double dot = ux * vx + uy * vy;
        double length = Math.sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy));
        double value = Math.acos(Math.max(-1, Math.min(1, dot / length)));
        return (ux * vy - uy * vx) < 0 ? -value : value;
    }

    private IconInk() {
    }
}

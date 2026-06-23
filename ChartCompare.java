import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * Simple chart generator that reads compareD.csv and writes two PNGs:
 * - compare_time.png (log-log: n vs ns_per_op)
 * - compare_bytes.png (log-log: n vs bytes_per_element)
 *
 * No external libraries required; uses Java2D.
 */
public class ChartCompare {
    static class Series {
        String key; // e.g. ArrayList|JDK|get(index)
        TreeMap<Integer, Double> xs = new TreeMap<>(); // n -> value
        Series(String k){ key = k; }
        void put(int n, double v){ xs.put(n, v); }
    }

    public static void main(String[] args) throws Exception {
        File csv = new File("compareD.csv");
        if (!csv.exists()) { System.err.println("compareD.csv not found in cwd"); return; }

        // parse CSV into a map keyed by (structure,operation) -> map of impl->Series
        Map<String, Map<String, Series>> byCase = new LinkedHashMap<>();
        try (BufferedReader r = new BufferedReader(new FileReader(csv))) {
            String hdr = r.readLine();
            if (hdr == null) { System.err.println("empty CSV"); return; }
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim(); if (line.isEmpty()) continue;
                String[] parts = line.split(",");
                if (parts.length < 6) continue;
                String structure = parts[0];
                String impl = parts[1];
                String operation = parts[2];
                int n = Integer.parseInt(parts[3]);
                double ns = Double.parseDouble(parts[4]);
                double bytes = Double.parseDouble(parts[5]);
                String caseKey = structure + "|" + operation;
                byCase.computeIfAbsent(caseKey, k -> new LinkedHashMap<>());
                Map<String, Series> map = byCase.get(caseKey);
                map.computeIfAbsent(impl + "|ns", k -> new Series(impl+"|ns")).put(n, ns);
                map.computeIfAbsent(impl + "|bytes", k -> new Series(impl+"|bytes")).put(n, bytes);
            }
        }

        if (byCase.isEmpty()) { System.err.println("no data parsed"); return; }

        // For each structure+operation produce two plots (time and bytes)
        for (Map.Entry<String, Map<String, Series>> e : byCase.entrySet()){
            String caseKey = e.getKey(); // structure|operation
            String filenameBase = "compare_" + caseKey.replace('|', '_').replaceAll("\\s+", "_");
            // collect time series (impl|ns)
            Map<String, Series> map = e.getValue();
            Map<String, Series> timeMap = new LinkedHashMap<>();
            Map<String, Series> bytesMap = new LinkedHashMap<>();
            for (Map.Entry<String, Series> s : map.entrySet()){
                String k = s.getKey();
                if (k.endsWith("|ns")) timeMap.put(k.replace("|ns", ""), s.getValue());
                if (k.endsWith("|bytes")) bytesMap.put(k.replace("|bytes", ""), s.getValue());
            }
            if (!timeMap.isEmpty()) drawLogLogPlot(timeMap, "n", "ns/op", new File(filenameBase + "_time.png"));
            if (!bytesMap.isEmpty()) drawLogLogPlot(bytesMap, "n", "bytes_per_element", new File(filenameBase + "_bytes.png"));
            System.out.println("Wrote plots for " + caseKey + " -> " + filenameBase + "_*.png");
        }
    }

    static void drawLogLogPlot(Map<String, Series> seriesMap, String xlabel, String ylabel, File out) throws IOException {
        int W = 1200, H = 800;
        int left = 120, right = 40, top = 60, bottom = 120;
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE); g.fillRect(0,0,W,H);

        // collect all x and y values
        double xmin = Double.POSITIVE_INFINITY, xmax = Double.NEGATIVE_INFINITY;
        double ymin = Double.POSITIVE_INFINITY, ymax = Double.NEGATIVE_INFINITY;
        List<Series> seriesList = new ArrayList<>(seriesMap.values());
        for (Series s : seriesList) {
            for (Map.Entry<Integer, Double> e : s.xs.entrySet()) {
                double x = e.getKey();
                double y = e.getValue();
                if (x <= 0) continue;
                if (!(Double.isFinite(y))) continue;
                // floor for positive-only log
                double yf = Math.max(y, 1e-6);
                xmin = Math.min(xmin, x); xmax = Math.max(xmax, x);
                ymin = Math.min(ymin, yf); ymax = Math.max(ymax, yf);
            }
        }
        if (!Double.isFinite(xmin) || !Double.isFinite(ymin)) {
            System.err.println("no valid numeric points to plot"); return;
        }
        // convert to log10 domain
        double lxmin = Math.log10(xmin), lxmax = Math.log10(Math.max(xmax, xmin*1.001));
        double lymin = Math.log10(Math.max(ymin, 1e-6)), lymax = Math.log10(Math.max(ymax, lymin+1e-3));

        // draw axes
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(1f));
        // axes lines
        g.drawLine(left, H - bottom, W - right, H - bottom); // x axis
        g.drawLine(left, top, left, H - bottom); // y axis

        // grid and ticks: draw integer powers of 10 ticks along x and y
        int xTicksLow = (int)Math.floor(lxmin), xTicksHigh = (int)Math.ceil(lxmax);
        int yTicksLow = (int)Math.floor(lymin), yTicksHigh = (int)Math.ceil(lymax);

        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        // x ticks
        for (int k = xTicksLow; k <= xTicksHigh; k++){
            double xv = Math.pow(10, k);
            if (xv < xmin*0.9 || xv > xmax*1.1) continue;
            double tx = mapLog(xv, lxmin, lxmax, left, W-right);
            g.setColor(new Color(230,230,230));
            g.drawLine((int)tx, top, (int)tx, H-bottom);
            g.setColor(Color.BLACK);
            String lbl = String.format("10^%d", k);
            drawCenteredString(g, lbl, (int)tx, H-bottom+20);
        }
        // y ticks
        for (int k = yTicksLow; k <= yTicksHigh; k++){
            double yv = Math.pow(10, k);
            if (yv < ymin*0.9 || yv > ymax*1.1) continue;
            double ty = mapLog(yv, lymin, lymax, H-bottom, top);
            g.setColor(new Color(230,230,230));
            g.drawLine(left, (int)ty, W-right, (int)ty);
            g.setColor(Color.BLACK);
            String lbl = String.format("10^%d", k);
            drawRightAlignedString(g, lbl, left-8, (int)ty+4);
        }

        // draw series
        Color[] palette = new Color[]{Color.BLACK, Color.RED, Color.BLUE, Color.GREEN.darker(), Color.MAGENTA, Color.ORANGE.darker(), Color.CYAN.darker(), Color.PINK.darker(), Color.GRAY, new Color(128,0,128)};
        int ci = 0;
        int legendX = left + 10, legendY = top + 10;
        for (Series s : seriesList){
            g.setStroke(new BasicStroke(2f));
            Color col = palette[ci % palette.length]; ci++;
            g.setColor(col);
            // draw polyline
            int prevX = -1, prevY = -1;
            for (Map.Entry<Integer, Double> e : s.xs.entrySet()){
                int x = e.getKey(); double y = e.getValue(); if (x<=0) continue; if (!Double.isFinite(y)) continue;
                double yf = Math.max(y, 1e-6);
                double lx = Math.log10(x), ly = Math.log10(yf);
                int px = (int)map(lx, lxmin, lxmax, left, W-right);
                int py = (int)map(ly, lymin, lymax, H-bottom, top);
                if (prevX >= 0){ g.drawLine(prevX, prevY, px, py); }
                // draw point
                g.fill(new Ellipse2D.Double(px-2, py-2, 4,4));
                prevX = px; prevY = py;
            }
            // legend entry
            g.fillRect(legendX, legendY, 12,8);
            g.setColor(Color.BLACK);
            drawString(g, " " + s.key.replace('|', ' '), legendX+16, legendY+8);
            legendY += 18;
        }

        // labels
        g.setColor(Color.BLACK);
        g.setFont(new Font("SansSerif", Font.BOLD, 16));
        drawCenteredString(g, xlabel + " (log)", left + (W-right - left)/2, H - bottom + 50);
        drawCenteredStringVertical(g, ylabel + " (log)", 20, top + (H-bottom - top)/2);

        // title
        drawCenteredString(g, "Compare: " + seriesList.get(0).key.split("\\|")[0] + " (multiple impl/ops)", W/2, 30);

        // save
        ImageIO.write(img, "PNG", out);
        g.dispose();
    }

    static double map(double v, double a, double b, double A, double B){ return A + (v-a)/(b-a) * (B-A); }
    static double mapLog(double x, double la, double lb, double A, double B){ double v = Math.log10(x); return map(v, la, lb, A, B); }

    static void drawCenteredString(Graphics2D g, String s, int x, int y){ FontMetrics fm = g.getFontMetrics(); int w = fm.stringWidth(s); g.drawString(s, x - w/2, y + fm.getAscent()/2 - 2); }
    static void drawString(Graphics2D g, String s, int x, int y){ g.drawString(s, x, y); }
    static void drawRightAlignedString(Graphics2D g, String s, int x, int y){ FontMetrics fm = g.getFontMetrics(); int w = fm.stringWidth(s); g.drawString(s, x - w, y); }
    static void drawCenteredStringVertical(Graphics2D g, String s, int x, int y){ AffineTransform orig = g.getTransform(); g.rotate(-Math.PI/2, x, y); drawCenteredString(g, s, x, y); g.setTransform(orig); }
}

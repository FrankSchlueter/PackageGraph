package de.tk.dependencyanalyse.packagegraph.report;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import de.tk.dependencyanalyse.packagegraph.analysis.OutlierEntry;

/**
 * Schreibt einen self-contained HTML-Report (inline CSS, keine
 * externen Resourcen) ueber die erkannten Outlier.
 *
 * <p>Sortierung: globale Reihenfolge nach Abweichung absteigend,
 * wie sie der {@link de.tk.dependencyanalyse.packagegraph.analysis.BundleOutlierDetector}
 * liefert. Pro Bundle wird eine eigene Sektion mit Statistik
 * (Mittel, Min, Max) und einer Tabelle der Outlier angelegt.
 */
public final class HtmlReportWriter {

    /** Erzeugt den Report und schreibt ihn in {@code out}. */
    public void write(Path out,
                      List<OutlierEntry> outliers,
                      int totalNodes,
                      int totalEdges,
                      int bundleCount,
                      double percentile,
                      Path inputFile) throws IOException {
        try (Writer w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            writeHeader(w, totalNodes, totalEdges, bundleCount, percentile, inputFile);
            writeSummary(w, outliers, bundleCount);
            writeBundles(w, outliers);
            writeFooter(w);
        }
    }

    private void writeHeader(Writer w,
                             int nodes, int edges, int bundles,
                             double percentile, Path input) throws IOException {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        w.write("<!DOCTYPE html>\n<html lang=\"de\">\n<head>\n");
        w.write("<meta charset=\"UTF-8\">\n");
        w.write("<title>PackageGraph Outlier Report</title>\n");
        w.write("<style>\n");
        w.write(css());
        w.write("</style>\n</head>\n<body>\n");
        w.write("<header><h1>PackageGraph Outlier Report</h1>\n");
        w.write("<p class=\"meta\">Datei: " + esc(input.getFileName().toString()) +
                " &middot; erstellt: " + ts + "</p>\n");
        w.write("<p class=\"meta\">Knoten: " + nodes +
                " &middot; Kanten: " + edges +
                " &middot; Bundles: " + bundles +
                " &middot; Top-" + formatPercent(percentile) + "-Auswertung</p>\n");
        w.write("</header>\n");
    }

    private void writeSummary(Writer w, List<OutlierEntry> outliers, int bundleCount) throws IOException {
        int reported = outliers.size();
        long high = outliers.stream().filter(o -> o.side() == OutlierEntry.Side.HIGH).count();
        long low  = outliers.stream().filter(o -> o.side() == OutlierEntry.Side.LOW).count();
        w.write("<section id=\"summary\">\n");
        w.write("<h2>Zusammenfassung</h2>\n");
        w.write("<table class=\"stats\"><tbody>\n");
        w.write("<tr><th>Outlier gesamt</th><td>" + reported + "</td></tr>\n");
        w.write("<tr><th>davon HIGH</th><td>" + high + "</td></tr>\n");
        w.write("<tr><th>davon LOW</th><td>" + low + "</td></tr>\n");
        w.write("<tr><th>Bundles mit Outlier</th><td>" + bundleCount + "</td></tr>\n");
        w.write("</tbody></table>\n");
        w.write("</section>\n");
    }

    private void writeBundles(Writer w, List<OutlierEntry> outliers) throws IOException {
        if (outliers.isEmpty()) {
            w.write("<section><p class=\"empty\">Keine Outlier gefunden.</p></section>\n");
            return;
        }
        String currentBundle = null;
        int section = 0;
        for (OutlierEntry o : outliers) {
            if (!o.bundle().equals(currentBundle)) {
                if (currentBundle != null) w.write("</table></section>\n");
                currentBundle = o.bundle();
                section++;
                w.write("<section class=\"bundle\">\n");
                w.write("<h2>" + esc(currentBundle) + "</h2>\n");
                w.write("<table>\n<thead>\n<tr>");
                w.write("<th>Node</th><th>Level</th><th>nodeDependencyLevel</th>");
                w.write("<th>Bundle-&Oslash;</th><th>Abweichung</th><th>Seite</th>");
                w.write("</tr>\n</thead>\n<tbody>\n");
            }
            String sideCls = o.side() == OutlierEntry.Side.HIGH ? "high" : "low";
            String sideLbl = o.side() == OutlierEntry.Side.HIGH ? "HIGH" : "LOW";
            w.write("<tr class=\"" + sideCls + "\">");
            w.write("<td class=\"name\">" + esc(o.nodeName()) + "</td>");
            w.write("<td>" + o.level() + "</td>");
            w.write("<td>" + format(o.nodeDependencyLevel()) + "</td>");
            w.write("<td>" + format(o.bundleMean()) + "</td>");
            w.write("<td class=\"dev\">" + format(o.deviation()) + "</td>");
            w.write("<td class=\"side\">" + sideLbl + "</td>");
            w.write("</tr>\n");
        }
        if (currentBundle != null) w.write("</tbody></table></section>\n");
    }

    private void writeFooter(Writer w) throws IOException {
        w.write("<footer><p class=\"meta\">de.tk.dependencyanalyse:packagegraph-analyzer</p></footer>\n");
        w.write("</body>\n</html>\n");
    }

    private static String css() {
        return """
            body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif;
                   margin: 2em; color: #1f2328; background: #fafbfc; }
            header h1 { margin-bottom: 0.2em; color: #0969da; }
            .meta { color: #6e7781; font-size: 0.9em; margin: 0.2em 0; }
            section { margin: 2em 0; padding: 1em; background: #fff;
                      border: 1px solid #d0d7de; border-radius: 6px; }
            h2 { margin-top: 0; padding-bottom: 0.3em; border-bottom: 1px solid #d0d7de; }
            table { border-collapse: collapse; width: 100%; }
            th, td { padding: 0.4em 0.7em; border: 1px solid #d0d7de; text-align: left; }
            th { background: #f6f8fa; }
            td.name { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 0.92em; }
            td.dev { text-align: right; font-variant-numeric: tabular-nums; }
            td.side { text-align: center; font-weight: bold; }
            tr.high { background: #fff8c5; }
            tr.high td.side { color: #9a6700; }
            tr.low  { background: #ddf4ff; }
            tr.low  td.side { color: #0969da; }
            table.stats td { font-variant-numeric: tabular-nums; }
            .empty { color: #6e7781; font-style: italic; }
            footer { margin-top: 3em; padding-top: 1em; border-top: 1px solid #d0d7de; }
            """;
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<' -> b.append("<");
                case '>' -> b.append(">");
                case '&' -> b.append("&");
                case '"' -> b.append("&quot;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    private static String format(double d) {
        if (Double.isNaN(d)) return "-";
        return String.format(java.util.Locale.ROOT, "%.3f", d);
    }

    private static String formatPercent(double p) {
        if (p == Math.floor(p)) return String.valueOf((int) p);
        return String.format(java.util.Locale.ROOT, "%.2f", p);
    }
}

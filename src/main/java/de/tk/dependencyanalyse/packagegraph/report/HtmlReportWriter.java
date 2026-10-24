package de.tk.dependencyanalyse.packagegraph.report;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import de.tk.dependencyanalyse.packagegraph.analysis.CoreExtraktion;
import de.tk.dependencyanalyse.packagegraph.analysis.OverlayAnalyse;
import de.tk.dependencyanalyse.packagegraph.analysis.OutlierEntry;
import de.tk.dependencyanalyse.packagegraph.analysis.ScsZerlegung;

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

    /** Erzeugt den Report mit zusaetzlicher SCS-Zerlegungs-Sektion. */
    public void write(Path out,
                      List<OutlierEntry> outliers,
                      int totalNodes,
                      int totalEdges,
                      int bundleCount,
                      double percentile,
                      Path inputFile,
                      CoreExtraktion.Varianten coreVarianten,
                      ScsZerlegung.Zerlegung scs,
                      java.util.Map<String, String> bundleOf,
                      String subsystemSvg,
                      String topEdgesHtml,
                      OverlayAnalyse overlayAnalyse,
                      de.tk.dependencyanalyse.packagegraph.graph.PackageGraph pg,
                      java.util.List<ScsZerlegung.IterationStep> iterHistory,
                      ScsZerlegung.MetrikDetail metrikVorher,
                      ScsZerlegung.MetrikDetail metrikNachher,
                      int packagesAusgelagert) throws IOException {
        try (Writer w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            writeHeader(w, totalNodes, totalEdges, bundleCount, percentile, inputFile);
            writeSummary(w, outliers, bundleCount);
            writeScsOverview(w, coreVarianten, scs);
            writeScsGraph(w, subsystemSvg, topEdgesHtml, scs);
            writeOverlayAnalysis(w, overlayAnalyse, scs, pg, bundleOf);
            writeIterativeAuslagerung(w, iterHistory, metrikVorher, metrikNachher,
                    packagesAusgelagert);
            writeSmallCandidates(w, scs, bundleOf);
            writeCoreDetails(w, coreVarianten, bundleOf);
            writeScsDetails(w, scs, bundleOf);
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
        // Summary der Auslagerungs-Bewertung.
        int sinnvoll = 0, bedenklich = 0, problematisch = 0;
        for (OutlierEntry o : outliers) {
            switch (o.auslagerungsBewertung()) {
                case SINNVOLL      -> sinnvoll++;
                case BEDENKLICH    -> bedenklich++;
                case PROBLEMATISCH -> problematisch++;
                default -> { /* UNBEKANNT */ }
            }
        }
        w.write("<section>\n");
        w.write("<h2>Auslagerungs-Bewertung der Outlier</h2>\n");
        if (sinnvoll + bedenklich + problematisch == 0) {
            w.write("<p class=\"empty\">Bewertung noch nicht berechnet (OutlierAuslagerungsPruefer "
                  + "wurde nicht aufgerufen).</p>\n");
        } else {
            w.write("<p>Von <b>" + (sinnvoll + bedenklich + problematisch)
                  + "</b> bewerteten Outliern sind "
                  + "<span style=\"color:#1a7f37;font-weight:bold\">" + sinnvoll + "</span> "
                  + "<em>sinnvoll auslagerbar</em>, "
                  + "<span style=\"color:#9a6700;font-weight:bold\">" + bedenklich + "</span> "
                  + "<em>bedenklich</em> (Beziehungen ins Restbundle), und "
                  + "<span style=\"color:#cf222e;font-weight:bold\">" + problematisch + "</span> "
                  + "<em>problematisch</em> (wuerde Zyklus erzeugen).</p>\n");
            w.write("<p>Bewertung pro Outlier-Package (nur direkte Kanten, 80%-Core-Referenz):</p>\n");
            w.write("<ul>"
                  + "<li><b>#&rarr;Core</b>: Beziehungen des Outliers zu Packages im selben Bundle, "
                  + "die im 80%-Core liegen (= natuerlicher Anker).</li>"
                  + "<li><b>#&rarr;Rest</b>: Beziehungen zu Packages im selben Bundle, die "
                  + "<em>nicht</em> im Core sind (= Restkopplung).</li>"
                  + "<li><b>R&uuml;ckk.</b>: Rueckkanten aus Restbundle-Packages zum Outlier "
                  + "(= zirkulaere Abhaengigkeit).</li>"
                  + "</ul>\n");
        }
        w.write("</section>\n");

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
                w.write("<th>#&rarr;Core</th><th>#&rarr;Rest</th>"
                        + "<th>R&uuml;ckk.</th><th>Bewertung</th>");
                w.write("</tr>\n</thead>\n<tbody>\n");
            }
            String sideCls = o.side() == OutlierEntry.Side.HIGH ? "high" : "low";
            String sideLbl = o.side() == OutlierEntry.Side.HIGH ? "HIGH" : "LOW";
            String bewertungCls;
            String bewertungLbl;
            switch (o.auslagerungsBewertung()) {
                case SINNVOLL      -> { bewertungCls = "ok";      bewertungLbl = "sinnvoll"; }
                case BEDENKLICH    -> { bewertungCls = "warn";    bewertungLbl = "bedenklich"; }
                case PROBLEMATISCH -> { bewertungCls = "error";   bewertungLbl = "problematisch"; }
                default -> { bewertungCls = ""; bewertungLbl = "?"; }
            }
            w.write("<tr class=\"" + sideCls + "\">");
            w.write("<td class=\"name\">" + esc(o.nodeName()) + "</td>");
            w.write("<td>" + o.level() + "</td>");
            w.write("<td>" + format(o.nodeDependencyLevel()) + "</td>");
            w.write("<td>" + format(o.bundleMean()) + "</td>");
            w.write("<td class=\"dev\">" + format(o.deviation()) + "</td>");
            w.write("<td class=\"side\">" + sideLbl + "</td>");
            w.write("<td class=\"dev\">" + o.beziehungenZuCore() + "</td>");
            w.write("<td class=\"dev\">" + o.beziehungenZuRestBundle() + "</td>");
            w.write("<td class=\"dev\">" + o.rueckkantenAusRestbundle() + "</td>");
            w.write("<td class=\"" + bewertungCls + "\">" + bewertungLbl + "</td>");
            w.write("</tr>\n");
        }
        if (currentBundle != null) w.write("</tbody></table></section>\n");
    }

    private void writeFooter(Writer w) throws IOException {
        w.write("<footer><p class=\"meta\">de.tk.dependencyanalyse:packagegraph-analyzer</p></footer>\n");
        w.write("</body>\n</html>\n");
    }

    /** SCS-Zerlegungs-Uebersicht: Core-Groessen, Modul-DAG-Status. */
    private void writeScsOverview(Writer w,
                                 CoreExtraktion.Varianten coreVarianten,
                                 ScsZerlegung.Zerlegung scs) throws IOException {
        w.write("<section>\n");
        w.write("<h2>SCS-Zerlegung</h2>\n");
        w.write("<table class=\"stats\">\n");
        w.write("<tr><th>Core-Variante</th><th>Packages</th><th>Abdeckung</th>"
                + "<th>Offene Kopplung</th></tr>\n");
        if (coreVarianten != null) {
            for (CoreExtraktion.Core c : new CoreExtraktion.Core[]{
                    coreVarianten.einfach(), coreVarianten.streng()}) {
                w.write("<tr><td>" + (c == coreVarianten.einfach() ? "80 %" : "95 %")
                        + "</td>");
                w.write("<td class=\"dev\">" + c.size() + "</td>");
                w.write("<td class=\"dev\">" + formatPercent(c.abdeckung() * 100) + " %</td>");
                w.write("<td class=\"dev\">" + c.nochOffeneKopplung() + "</td></tr>\n");
            }
        }
        w.write("</table>\n");

        w.write("<p><b>Modul-DAG azyklisch:</b> "
                + (scs.modulDagAzyklisch() ? "ja" : "<b style='color:#cf222e'>NEIN</b>")
                + "</p>\n");
        if (!scs.modulDagAzyklisch()) {
            w.write("<p><b>SCC-Hinweis:</b> Beim Kontrahieren der Bundles zu "
                    + "Subsystemen entstehen ");
            w.write(scs.zyklischePaare().size() + " Zyklen. Die betroffenen SCS-Knoten "
                    + "bilden jeweils eine stark zusammenhängende Komponente &mdash; "
                    + "die fachliche Schichtung muss diese Rueckkopplung aufbrechen.</p>\n");
            w.write("<ul>\n");
            for (String z : scs.zyklischePaare()) {
                w.write("<li>" + esc(z) + "</li>\n");
            }
            w.write("</ul>\n");
        }
        w.write("<table>\n");
        w.write("<tr><th>Subsystem</th><th>Bundles</th><th>Packages</th>"
                + "<th>dom. Produkt</th><th>Schicht</th>"
                + "<th>ReinP</th><th>ReinS</th></tr>\n");
        for (ScsZerlegung.Scs s : scs.scs()) {
            w.write("<tr>");
            w.write("<td>SCS" + s.id() + "</td>");
            w.write("<td class=\"dev\">" + s.anzahlBundles() + "</td>");
            w.write("<td class=\"dev\">" + s.packages().size() + "</td>");
            w.write("<td class=\"name\">" + esc(s.dominantesProdukt()) + "</td>");
            w.write("<td>" + esc(s.dominanteSchicht()) + "</td>");
            w.write("<td class=\"dev\">" + format(s.reinheitProdukt()) + "</td>");
            w.write("<td class=\"dev\">" + format(s.reinheitSchicht()) + "</td>");
            w.write("</tr>\n");
        }
        w.write("</table>\n");
        w.write("</section>\n");
    }

    /**
     * Bettet den Subsystem-Beziehungsgraph (self-contained SVG) als
     * eigene Sektion ein. Inline-SVG, kein externes Tool noetig.
     */
    private void writeScsGraph(Writer w, String svg, String topEdgesHtml,
                               ScsZerlegung.Zerlegung scs) throws IOException {
        w.write("<section>\n");
        w.write("<h2>Subsystem-Beziehungsgraph</h2>\n");
        if (svg == null || svg.isBlank()) {
            w.write("<p class=\"empty\">SVG-Visualisierung nicht verfuegbar.</p>\n");
            w.write("</section>\n");
            return;
        }
        w.write("<p>Self-contained SVG &mdash; keine externen Tools noetig. ");
        w.write("Knoten sind Subsysteme (inkl. <em>Core</em> und <em>Overlay</em>). ");
        w.write("Kantenstaerke ist logarithmisch aus der aggregierten Cardinality ");
        w.write("zwischen den Bundle-Paaren zweier Subsysteme skaliert. ");
        w.write("<b>Rot</b> markiert bidirektionale Kopplung (R%uuml;ckkopplung / SCC). ");
        w.write("Die staerksten Kanten sind im Graph beschriftet; die vollst&auml;ndige ");
        w.write("Top-Liste steht in der Tabelle darunter.</p>\n");
        w.write("<div style=\"text-align:center;overflow-x:auto;background:#fafbfc;"
                + "padding:1em;border:1px solid #d0d7de;border-radius:6px\">\n");
        w.write(svg);
        w.write("\n</div>\n");
        if (topEdgesHtml != null && !topEdgesHtml.isBlank()) {
            w.write("<h3>Top-30 Kanten nach Cardinality</h3>\n");
            w.write(topEdgesHtml);
            w.write("\n");
        }
        if (!scs.modulDagAzyklisch()) {
            w.write("<p><b>Hinweis:</b> Der Modul-DAG ist zyklisch. ");
            w.write(scs.zyklischePaare().size());
            w.write(" Subsystem-Paare bilden stark zusammenh&auml;ngende Komponenten &mdash; ");
            w.write("diese Knoten sind die dringendsten Kandidaten fuer eine fachliche ");
            w.write("Auftrennung in eigene Bounded Contexts.</p>\n");
        }
        w.write("</section>\n");
    }

    /** Overlay-Block: Erklaerung der Kriterien + Metriktabelle + Befundungen. */
    private void writeOverlayAnalysis(Writer w,
                                      OverlayAnalyse analyse,
                                      ScsZerlegung.Zerlegung scs,
                                      de.tk.dependencyanalyse.packagegraph.graph.PackageGraph pg,
                                      java.util.Map<String, String> bundleOf) throws IOException {
        if (analyse == null) return;
        java.util.List<OverlayAnalyse.Metrik> metrics = analyse.analysiere(scs, pg, bundleOf);
        analyse.writeHtml(w, metrics);
    }

    /** Iterative Auslagerung: Tabelle der Iterationen + Vorher/Nachher-Vergleich. */
    private void writeIterativeAuslagerung(Writer w,
                                           java.util.List<ScsZerlegung.IterationStep> history,
                                           ScsZerlegung.MetrikDetail metrikVorher,
                                           ScsZerlegung.MetrikDetail metrikNachher,
                                           int packagesAusgelagert) throws IOException {
        w.write("<section>\n<h2>Ungewollte Beziehungen &mdash; vorher / nachher</h2>\n");
        if (metrikVorher == null || metrikNachher == null) {
            w.write("<p class=\"empty\">Keine Optimierung durchgefuehrt "
                  + "(--auslagerung-iterations 0).</p>\n");
            w.write("</section>\n");
            return;
        }
        double gesamtVorher = metrikVorher.gesamt();
        double gesamtNachher = metrikNachher.gesamt();
        double reduction = gesamtVorher > 0
                ? (gesamtVorher - gesamtNachher) / gesamtVorher * 100.0 : 0.0;

        w.write("<p>Nach der Auslagerung von <b>" + packagesAusgelagert
              + " Packages</b> wurde das System neu geclustert (Leiden, "
              + "Bundle-Ebene). Erwartet wird eine <b>Reduktion der ungewollten "
              + "Beziehungen</b> &mdash; andernfalls ist das Vorgehen fehlerhaft.</p>\n");

        w.write("<table class=\"stats\">\n");
        w.write("<tr><th>Kategorie</th><th>vorher</th><th>nachher</th>"
              + "<th>&Delta; (nachher&minus;vorher)</th><th>&Delta; %</th></tr>\n");
        writeMetrikZeile(w, "SCS &harr; SCS", metrikVorher.scsZuScs(), metrikNachher.scsZuScs());
        writeMetrikZeile(w, "Core &rarr; SCS", metrikVorher.coreZuScs(), metrikNachher.coreZuScs());
        writeMetrikZeile(w, "Overlay &rarr; SCS", metrikVorher.overlayZuScs(), metrikNachher.overlayZuScs());
        w.write("<tr class=\"total\">");
        w.write("<td><b>GESAMT (ungewollte Beziehungen)</b></td>");
        w.write("<td class=\"dev\"><b>" + fmtCard(gesamtVorher) + "</b></td>");
        w.write("<td class=\"dev\"><b>" + fmtCard(gesamtNachher) + "</b></td>");
        w.write("<td class=\"dev\"><b>" + fmtCard(gesamtNachher - gesamtVorher) + "</b></td>");
        // Negative Veraenderung = weniger Beziehungen = gut (gruen).
        String redCls = reduction > 0 ? "ok" : (reduction < 0 ? "error" : "warn");
        w.write("<td class=\"" + redCls + "\"><b>"
              + String.format(java.util.Locale.ROOT, "%+.1f%%", -reduction) + "</b></td>");
        w.write("</tr>\n");
        w.write("</table>\n");

        if (history != null && !history.isEmpty()) {
            w.write("<h3>Auslagerungsschritt</h3>\n");
            w.write("<table>\n<thead><tr>"
                  + "<th>#</th><th>Package</th><th>Bundle</th>"
                  + "<th>&Delta; Metrik</th><th>Reduktion</th>"
                  + "</tr></thead>\n<tbody>\n");
            for (ScsZerlegung.IterationStep s : history) {
                w.write("<tr>");
                w.write("<td class=\"dev\">" + s.iteration() + "</td>");
                w.write("<td class=\"name\">" + esc(s.pkg()) + "</td>");
                w.write("<td class=\"name\">" + esc(s.bundle()) + "</td>");
                w.write("<td class=\"dev\">" + fmtCard(s.deltaMetric()) + "</td>");
                w.write("<td class=\"dev\">"
                      + String.format(java.util.Locale.ROOT, "%.1f", s.cumulativeReduction() * 100)
                      + " %</td>");
                w.write("</tr>\n");
            }
            w.write("</tbody>\n</table>\n");
        }
        w.write("</section>\n");
    }

    private void writeMetrikZeile(Writer w, String label,
                                  double vorher, double nachher) throws IOException {
        double diff = vorher > 0 ? (nachher - vorher) / vorher * 100.0 : 0.0;
        String cls = diff < 0 ? "ok" : (diff > 0 ? "error" : "warn");
        w.write("<tr>");
        w.write("<td>" + label + "</td>");
        w.write("<td class=\"dev\">" + fmtCard(vorher) + "</td>");
        w.write("<td class=\"dev\">" + fmtCard(nachher) + "</td>");
        w.write("<td class=\"dev\">" + fmtCard(nachher - vorher) + "</td>");
        w.write("<td class=\"" + cls + "\">"
              + String.format(java.util.Locale.ROOT, "%+.1f%%", diff) + "</td>");
        w.write("</tr>\n");
    }

    /** SmallCandidates-Sektion. */
    private void writeSmallCandidates(Writer w,
                                      ScsZerlegung.Zerlegung scs,
                                      java.util.Map<String, String> bundleOf) throws IOException {
        if (scs.smallCandidates() == null || scs.smallCandidates().isEmpty()) {
            w.write("<section>\n<h2>SmallCandidates</h2>\n");
            w.write("<p class=\"empty\">Keine Packages in SmallCandidates "
                  + "(alle SCS haben genuegend Packages).</p>\n");
            w.write("</section>\n");
            return;
        }
        w.write("<section>\n<h2>SmallCandidates</h2>\n");
        w.write("<p>" + scs.smallCandidates().size() + " Packages, deren SCS nach "
              + "Auslagerung unter die Mindestgroesse gefallen sind. Diese Packages "
              + "sind <em>keine</em> Cross-Cutting-Bundles und gehoeren deshalb nicht "
              + "ins Overlay &mdash; sie sollten manuell einem SCS zugeordnet oder zu "
              + "einem groesseren SCS verschmolzen werden.</p>\n");
        // Gruppierung nach Bundle.
        java.util.Map<String, java.util.List<String>> byBundle = new java.util.TreeMap<>();
        for (String pkg : scs.smallCandidates()) {
            String b = bundleOf.getOrDefault(pkg, "?");
            byBundle.computeIfAbsent(b, k -> new ArrayList<>()).add(pkg);
        }
        w.write("<table>\n<thead><tr><th>Bundle</th><th>Packages</th></tr></thead>\n<tbody>\n");
        java.util.List<java.util.Map.Entry<String, java.util.List<String>>> entries =
                new ArrayList<>(byBundle.entrySet());
        entries.sort((x, y) -> Integer.compare(y.getValue().size(), x.getValue().size()));
        for (java.util.Map.Entry<String, java.util.List<String>> e : entries) {
            w.write("<tr><td class=\"name\">" + esc(e.getKey()) + "</td>");
            w.write("<td class=\"dev\">" + e.getValue().size() + "</td></tr>\n");
        }
        w.write("</tbody>\n</table>\n");
        w.write("</section>\n");
    }

    /** Core-Bundles und deren Package-Aufschluesselung. */
    private void writeCoreDetails(Writer w,
                                  CoreExtraktion.Varianten coreVarianten,
                                  java.util.Map<String, String> bundleOf) throws IOException {
        w.write("<section>\n");
        w.write("<h2>Core-Bundles (95 %-Variante)</h2>\n");
        w.write("<p>Packages, deren Herausloesen aus dem Bundle die meisten "
                + "bunduebergreifenden Kanten aufloest. Reihenfolge nach Anzahl "
                + "Packages im Bundle.</p>\n");
        java.util.Map<String, Integer> corePkgCount = new java.util.TreeMap<>();
        if (coreVarianten != null) {
            for (String pkg : coreVarianten.streng().packages()) {
                String bn = bundleOf.get(pkg);
                if (bn != null) corePkgCount.merge(bn, 1, Integer::sum);
            }
        }
        java.util.List<java.util.Map.Entry<String, Integer>> top =
                new java.util.ArrayList<>(corePkgCount.entrySet());
        top.sort(Comparator.<java.util.Map.Entry<String, Integer>>comparingInt(e -> e.getValue()).reversed());
        w.write("<table>\n");
        w.write("<tr><th>Bundle</th><th>Packages im Core</th></tr>\n");
        for (java.util.Map.Entry<String, Integer> e : top) {
            w.write("<tr><td class=\"name\">" + esc(e.getKey()) + "</td>");
            w.write("<td class=\"dev\">" + e.getValue() + "</td></tr>\n");
        }
        w.write("</table>\n");
        w.write("</section>\n");
    }

    /** SCS-Detail: jedes Subsystem mit seinen Bundles. */
    private void writeScsDetails(Writer w, ScsZerlegung.Zerlegung scs,
                                 java.util.Map<String, String> bundleOf) throws IOException {
        w.write("<section>\n");
        w.write("<h2>Subsystem-Details</h2>\n");
        for (ScsZerlegung.Scs s : scs.scs()) {
            w.write("<h3>SCS" + s.id() + " &middot; "
                    + esc(s.dominantesProdukt()) + " &middot; "
                    + s.packages().size() + " Packages &middot; "
                    + s.anzahlBundles() + " Bundles</h3>\n");
            w.write("<details open><summary>Bundles dieses Subsystems</summary>\n");
            w.write("<ul>\n");
            java.util.List<String> bundles = new java.util.ArrayList<>(s.bundles());
            java.util.Collections.sort(bundles);
            for (String b : bundles) {
                w.write("<li class=\"name\">" + esc(b) + "</li>\n");
            }
            w.write("</ul>\n");
            w.write("</details>\n");

            // Top-15 Packages nach Haeufigkeit im Subsystem.
            java.util.Map<String, Integer> pkgRank = new java.util.HashMap<>();
            for (String p : s.packages()) pkgRank.merge(p, 1, Integer::sum);
            java.util.List<java.util.Map.Entry<String, Integer>> topPackages =
                    new java.util.ArrayList<>(pkgRank.entrySet());
            topPackages.sort(Comparator.<java.util.Map.Entry<String, Integer>>comparingInt(e -> e.getValue()).reversed());
            w.write("<details><summary>Top-20 Packages nach Zaehlung im Subsystem</summary>\n");
            w.write("<table>\n<tr><th>Package</th><th>Bundle</th></tr>\n");
            int shown = 0;
            for (java.util.Map.Entry<String, Integer> e : topPackages) {
                if (shown++ >= 20) break;
                w.write("<tr><td class=\"name\">" + esc(e.getKey())
                        + "</td><td class=\"name\">" + esc(bundleOf.getOrDefault(e.getKey(), "?"))
                        + "</td></tr>\n");
            }
            w.write("</table>\n</details>\n");
        }
        w.write("</section>\n");
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
            td.ok { color: #1a7f37; font-weight: 600; }
            td.warn { color: #9a6700; font-weight: 600; }
            td.error { color: #cf222e; font-weight: 600; }
            table.stats td { font-variant-numeric: tabular-nums; }
            tr.total { background: #f6f8fa; }
            .empty { color: #6e7781; font-style: italic; }
            details { margin: 0.5em 0; }
            summary { cursor: pointer; font-weight: 500; }
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

    private static String fmtCard(double w) {
        if (w >= 1_000_000) return String.format(java.util.Locale.ROOT, "%.1fM", w / 1_000_000);
        if (w >= 1000)      return String.format(java.util.Locale.ROOT, "%.1fk", w / 1000);
        return String.valueOf((long) w);
    }
}

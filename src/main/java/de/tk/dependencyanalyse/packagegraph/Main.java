package de.tk.dependencyanalyse.packagegraph;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultWeightedEdge;

import de.tk.dependencyanalyse.packagegraph.analysis.BundleOutlierDetector;
import de.tk.dependencyanalyse.packagegraph.analysis.CoreExtraktion;
import de.tk.dependencyanalyse.packagegraph.analysis.NodeDependencyLevelCalculator;
import de.tk.dependencyanalyse.packagegraph.analysis.OutlierAuslagerungsPruefer;
import de.tk.dependencyanalyse.packagegraph.analysis.OutlierEntry;
import de.tk.dependencyanalyse.packagegraph.analysis.OverlayAnalyse;
import de.tk.dependencyanalyse.packagegraph.analysis.ScsZerlegung;
import de.tk.dependencyanalyse.packagegraph.analysis.StrukturDivergenz;
import de.tk.dependencyanalyse.packagegraph.analysis.SubsystemGraphViz;
import de.tk.dependencyanalyse.packagegraph.dsm.DsmSorter;
import de.tk.dependencyanalyse.packagegraph.dsm.LevelCalculator;
import de.tk.dependencyanalyse.packagegraph.gml.GmlReader;
import de.tk.dependencyanalyse.packagegraph.graph.LeidenClusterAnalyse;
import de.tk.dependencyanalyse.packagegraph.graph.LeidenHierarchie;
import de.tk.dependencyanalyse.packagegraph.graph.PackageGraph;
import de.tk.dependencyanalyse.packagegraph.graph.PackageGraphBuilder;
import de.tk.dependencyanalyse.packagegraph.report.HtmlReportWriter;

/**
 * CLI-Einstiegspunkt.
 *
 * <p>Liest die GML-Datei ein, baut den JGraphT-Graphen, sortiert ihn
 * via DSM, berechnet Level und nodeDependencyLevel, erkennt Outlier
 * pro Bundle und schreibt einen HTML-Report.
 *
 * <pre>
 *   java -jar packagegraph-analyzer.jar \
 *        --input  packageGraph.gml \
 *        --output report.html \
 *        [--percentile 5]
 * </pre>
 */
public final class Main {

    private static final double DEFAULT_PERCENTILE = 5.0;
    private static final int DEFAULT_MIN_BUNDLE_SIZE = 5;
    private static final double DEFAULT_MIN_BUNDLE_SHARE = 0.5;
    private static final double DEFAULT_SCS_RESOLUTION = 0.35;
    private static final int DEFAULT_AUSLAGERUNG_ITERATIONS = 50;
    private static final int DEFAULT_MIN_SCS_SIZE = 100;
    private static final int DEFAULT_AUSLAGERUNG_MAX_KANDIDATEN = 3000;

    public static void main(String[] args) throws IOException {
        CliArgs cli = CliArgs.parse(args);
        if (cli.help) {
            printHelp();
            return;
        }
        if (cli.input == null || cli.output == null) {
            System.err.println("Fehler: --input und --output sind Pflicht.");
            printHelp();
            System.exit(2);
        }

        long t0 = System.currentTimeMillis();
        System.out.println("[1/7] Lese GML: " + cli.input);
        GmlReader.Graph raw = GmlReader.read(cli.input);
        System.out.println("      -> " + raw.nodes().size() + " Knoten, "
                + raw.edges().size() + " Kanten (roh)");

        System.out.println("[2/7] Baue JGraphT-Graphen");
        PackageGraph pg = new PackageGraphBuilder().build(raw);
        System.out.println("      -> " + pg.nodeCount() + " Knoten, "
                + pg.edgeCount() + " Kanten (nach Self-Edge-Filter)");

        Graph<String, DefaultWeightedEdge> graph = pg.graph();
        long start = System.currentTimeMillis();
        Map<Integer, List<String>> leidenCommunities = LeidenClusterAnalyse.getLeidenCommunities(graph);
        long dauerMs = System.currentTimeMillis() - start;
        System.out.println("      -> Leiden-Analyse in " + dauerMs + " ms");
        System.out.println("      -> " + leidenCommunities.size() + " Bundles (Leiden-Analyse)");

        // --- Hierarchie: Stufe 2 verdichtet die Stufe-1-Cluster weiter ---
        start = System.currentTimeMillis();
        LeidenHierarchie.Hierarchy hier = LeidenHierarchie.getHierarchy(graph);
        System.out.println("      -> Hierarchie in " + (System.currentTimeMillis() - start) + " ms");
        System.out.println("      -> Stufe 1 (res=" + hier.fine().resolution() + "): "
                + hier.fine().size() + " Cluster");
        System.out.println("      -> Stufe 2 (res=" + hier.coarse().resolution() + "): "
                + hier.coarse().size() + " Meta-Cluster");

        // --- Divergenz: welche Packages sind in Bundle/Product nicht aufgehoben? ---
        System.out.println();
        System.out.println("      Kantenbasierte Divergenz — ohne generierten Schema-Code:");
        List<StrukturDivergenz.KantenDivergenz> kantenAlle =
                StrukturDivergenz.kantenDivergenz(pg, false);
        List<StrukturDivergenz.KantenDivergenz> kanten =
                StrukturDivergenz.kantenDivergenz(pg, true);
        int generiert = kantenAlle.size() - kanten.size();
        System.out.println("      -> ausgeklammert: " + generiert + " Packages ("
                + (100.0 * generiert / Math.max(1, kantenAlle.size())) + " %),"
                + " betrachtet: " + kanten.size());

        long cc = kanten.stream().filter(k -> k.outShare() > 0)
                .filter(k -> k.outShare() >= StrukturDivergenz.CROSS_CUTTING_OUT).count();
        long withOut = kanten.stream().filter(k -> k.outShare() > 0).count();
        System.out.printf("      -> cross-cutting (aus >= %.0f%%): %d von %d Packages mit"
                        + " ausgehenden Kanten (%.1f%%)%n",
                StrukturDivergenz.CROSS_CUTTING_OUT * 100, cc, withOut,
                100.0 * cc / Math.max(1, withOut));

        System.out.println();
        System.out.println("      Permutationstest gegen Zufalls-Bundle (n=20):");
        StrukturDivergenz.Baseline base =
                StrukturDivergenz.permutationstest(pg, 20, 20261023L);
        System.out.printf("        gemessen : avg-aus=%.4f  cross>=90%%=%.4f%n",
                base.gemessenAvg(), base.gemessenCross());
        System.out.printf("        zufaellig : avg-aus=%.4f  cross>=90%%=%.4f%n",
                base.zufallAvg(), base.zufallCross());
        System.out.printf("        Signal    : %+.4f  %s%n", base.signal(),
                base.istInformativ()
                        ? "-> Bundle-Labels tragen architektonische Information"
                        : "-> Bundle-Labels sind NICHT besser als Zufall");

        System.out.println("      Top-Bundles nach Auswaertsanteil:");
        Map<String, double[]> bstat = new TreeMap<>();
        for (StrukturDivergenz.KantenDivergenz k : kanten) {
            if (k.outShare() <= 0) continue;
            bstat.computeIfAbsent(k.bundle(), z -> new double[3]);
            double[] v = bstat.get(k.bundle());
            v[0] += k.outShare();
            v[1] += 1;
            v[2] = k.bundleSize();
        }
        List<Map.Entry<String, double[]>> top = new ArrayList<>(bstat.entrySet());
        top.sort((x, y) -> Double.compare(y.getValue()[0] / y.getValue()[1],
                                          x.getValue()[0] / x.getValue()[1]));
        int shown = 0;
        for (Map.Entry<String, double[]> e : top) {
            double[] v = e.getValue();
            if (v[2] < cli.minBundleSize) continue;
            System.out.printf("        %-42s avg-aus=%.0f%%  packages=%-5d%n",
                    e.getKey(), 100 * v[0] / v[1], (int) v[1]);
            if (++shown >= 10) break;
        }

        System.out.println();
        System.out.println("      Clusterbasierte Divergenz Stufe 1 (Schwelle "
                + (int) (cli.minBundleShare * 100) + " %, ohne generierten Code):");
        List<StrukturDivergenz.Divergenz> div1 =
                StrukturDivergenz.analyse(pg, hier.fine().clusters(), cli.minBundleShare);
        System.out.println("      -> " + div1.size() + " Packages in fremd-dominanten Clustern");

        System.out.println();
        System.out.println("      Top Bundle nach Zersplitterung (Stufe 2):");
        Map<String, Integer> zersplitterung =
                StrukturDivergenz.bundleZersplitterung(pg, hier.coarse().clusters());
        int shownZ = 0;
        for (Map.Entry<String, Integer> e : zersplitterung.entrySet()) {
            System.out.printf("        %-42s -> %d Meta-Cluster%n", e.getKey(), e.getValue());
            if (++shownZ >= 10) break;
        }

        // --- SCS-Zerlegung: Core + vertikale Subsysteme + Top-System ---
        System.out.println();
        System.out.println("[3/7] Core-Extraktion (Greedy-Set-Cover)");
        CoreExtraktion.Varianten varianten = new CoreExtraktion(pg).beideVarianten();
        System.out.printf("      Kandidaten: %d (abgelehnt wegen eigener Bundle-Nutzung: %d)%n",
                varianten.einfach().kandidatenGesamt(), varianten.einfach().abgelehntWegenInternerNutzung());
        System.out.printf("      Variante 80%%: %4d Packages, deckt %.1f%% der bunduebergreifenden Kopplung%n",
                varianten.einfach().size(), varianten.einfach().abdeckung() * 100);
        System.out.printf("      Variante 95%%: %4d Packages, deckt %.1f%% der bunduebergreifenden Kopplung%n",
                varianten.streng().size(), varianten.streng().abdeckung() * 100);

        System.out.println();
        System.out.println("[4/7] SCS-Zerlegung auf Bundle-Ebene (DAG, 438 Knoten)");
        // Bundle-Index wird sowohl fuer die SCS-Zerlegung als auch fuer den
        // Bericht benoetigt — wir berechnen ihn hier einmal.
        Map<String, String> bundleOf = new HashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : pg.nodeProps().entrySet()) {
            Object b = e.getValue().get(PackageGraphBuilder.PROP_BUNDLE);
            bundleOf.put(e.getKey(), b == null ? "?" : b.toString());
        }
        // WICHTIG: Core und SCS sind alternative Zielarchitekturen, keine
        // kombinierbaren Schichten. Core-extrahiertes Material wird als
        // Kandidat berichtet; die SCS-Zerlegung laeuft ueber den vollen
        // Bundle-Bestand. Uebergibt man hier die Core-Bundles, bleiben nur
        // ~160 Bundles fuer die Subsysteme uebrig und alle Cluster werden
        // zwangslaeufig klein (6-26 Bundles statt der 18-259er-Klassen).
        ScsZerlegung.Zerlegung zerlegung = new ScsZerlegung(pg)
                .zerlegen(new HashSet<>(), cli.scsResolution,
                        new HashSet<>(varianten.einfach().packages()));
        System.out.printf("      Overlay-Bundles=%d  Subsysteme=%d%n",
                zerlegung.overlayBundles().size(), zerlegung.scs().size());
        System.out.printf("      Modul-DAG azyklisch: %s%n",
                zerlegung.modulDagAzyklisch() ? "ja" : "NEIN");
        if (!zerlegung.modulDagAzyklisch()) {
            System.out.println("      -> zyklische Kopplung zwischen Subsystemen:");
            for (int i = 0; i < Math.min(5, zerlegung.zyklischePaare().size()); i++) {
                System.out.println("         " + zerlegung.zyklischePaare().get(i));
            }
        }
        System.out.println("      Subsysteme (nach Groesse):");
        System.out.printf("        %-4s %-7s %-8s %-34s %-22s %-7s %-7s%n",
                "ID", "Bundles", "Packages", "dominantes Produkt", "Schicht", "ReinP", "ReinS");
        for (ScsZerlegung.Scs s : zerlegung.scs()) {
            System.out.printf("        %-4d %-7d %-8d %-34s %-22s %-7.2f %-7.2f%n",
                    s.id(), s.anzahlBundles(), s.packages().size(),
                    abkuerzen(s.dominantesProdukt(), 33),
                    abkuerzen(s.dominanteSchicht(), 21),
                    s.reinheitProdukt(), s.reinheitSchicht());
        }

        // Overlay-Bundles im Detail: warum sind sie NICHT in einem SCS gelandet?
        System.out.println("      Overlay-Bundles im Detail (warum nicht im SCS?):");
        // Haeufigkeiten produkt/schicht pro Bundle.
        Map<String, Map<String, Integer>> prodHist = new HashMap<>();
        Map<String, Map<String, Integer>> clsHist = new HashMap<>();
        for (String bundle : zerlegung.overlayBundles()) {
            prodHist.put(bundle, new HashMap<>());
            clsHist.put(bundle, new HashMap<>());
        }
        for (Map.Entry<String, String> e : bundleOf.entrySet()) {
            String bundle = e.getValue();
            Map<String, Object> p = pg.nodeProps().get(e.getKey());
            if (p == null) continue;
            Object prod = p.get("product");
            Object cls = p.get("classification");
            if (prod != null) prodHist.computeIfAbsent(bundle, k -> new HashMap<>())
                    .merge(prod.toString(), 1, Integer::sum);
            if (cls != null) clsHist.computeIfAbsent(bundle, k -> new HashMap<>())
                    .merge(cls.toString(), 1, Integer::sum);
        }
        // Sortiert nach Package-Anzahl im Bundle.
        List<String> overlay = new ArrayList<>(zerlegung.overlayBundles());
        overlay.sort((x, y) -> {
            int sx = prodHist.getOrDefault(x, Map.of()).values().stream().mapToInt(Integer::intValue).sum();
            int sy = prodHist.getOrDefault(y, Map.of()).values().stream().mapToInt(Integer::intValue).sum();
            return Integer.compare(sy, sx);
        });
        for (String b : overlay) {
            int pkgCount = prodHist.getOrDefault(b, Map.of()).values().stream().mapToInt(Integer::intValue).sum();
            String topProd = prodHist.getOrDefault(b, Map.of()).entrySet().stream()
                    .max(java.util.Map.Entry.comparingByValue())
                    .map(java.util.Map.Entry::getKey).orElse("?");
            String topCls = clsHist.getOrDefault(b, Map.of()).entrySet().stream()
                    .max(java.util.Map.Entry.comparingByValue())
                    .map(java.util.Map.Entry::getKey).orElse("?");
            System.out.printf("        %-40s %5d Pkg  domProd=%-30s domSchicht=%s%n",
                    abkuerzen(b, 39), pkgCount,
                    abkuerzen(topProd, 29), abkuerzen(topCls, 18));
        }

        System.out.println();
        System.out.println("[5/7] DSM-Sortierung");
        List<String> order = new DsmSorter(pg).sort();
        System.out.println("      -> DSM-Reihenfolge gespeichert (erste 3: "
                + String.join(", ", order.subList(0, Math.min(3, order.size()))) + ")");

        System.out.println("[6/7] Berechne Level + nodeDependencyLevel");
        Map<String, Integer> levels = new LevelCalculator(pg).compute();
        Map<String, Double> ndls = new NodeDependencyLevelCalculator(pg, levels).compute();
        System.out.println("      -> Level-Range: "
                + levels.values().stream().mapToInt(Integer::intValue).min().orElse(0)
                + " .. " + levels.values().stream().mapToInt(Integer::intValue).max().orElse(0));
        System.out.println("      -> ndl-Range: "
                + ndls.values().stream().mapToDouble(Double::doubleValue).min().orElse(0)
                + " .. " + ndls.values().stream().mapToDouble(Double::doubleValue).max().orElse(0));

        System.out.println("[7/7] Outlier pro Bundle (Top-" + cli.percentile + " %)");
        BundleOutlierDetector detector = new BundleOutlierDetector(pg, cli.minBundleSize);
        List<OutlierEntry> outliers = detector.detect(cli.percentile);
        int bundles = (int) outliers.stream().map(OutlierEntry::bundle).distinct().count();
        System.out.println("      -> " + outliers.size() + " Outlier in " + bundles + " Bundles");

        // Outlier-Auslagerungs-Bewertung (nur direkte Kanten, 80%-Core-Referenz).
        Set<String> coreSet80 = new HashSet<>(varianten.einfach().packages());
        OutlierAuslagerungsPruefer pruefer =
                new OutlierAuslagerungsPruefer(pg, bundleOf, coreSet80);
        outliers = pruefer.bewerte(outliers);
        int[] verteilung = OutlierAuslagerungsPruefer.verteilung(outliers);
        System.out.printf("      -> Bewertung: %d sinnvoll, %d bedenklich, %d problematisch%n",
                verteilung[0], verteilung[1], verteilung[2]);

        // SCS-Optimierung: Auslagerung der sinnvoll/bedenklich-bewerteten Outlier
        // in einem Schritt, dann EIN Leiden-Run auf der bereinigten Struktur.
        List<ScsZerlegung.IterationStep> iterHistory = new ArrayList<>();
        ScsZerlegung.MetrikDetail metrikVorher = null;
        ScsZerlegung.MetrikDetail metrikNachher = null;
        int packagesAusgelagert = 0;
        if (cli.auslagerungIterations > 0) {
            List<OutlierEntry> kandidaten =
                    OutlierAuslagerungsPruefer.auslagerungsKandidaten(outliers);
            Set<String> auszulagernde = new HashSet<>();
            for (OutlierEntry o : kandidaten) auszulagernde.add(o.nodeId());

            // PROBLEMATISCH-Outlier separat sammeln — sie werden NICHT vorab
            // ausgelagert, sondern in der Paket-Optimierungsphase in das SCS
            // mit der staerksten Bindung verschoben.
            List<String> problematische = new ArrayList<>();
            for (OutlierEntry o : outliers) {
                if (o.auslagerungsBewertung() == OutlierEntry.AuslagerungsBewertung.PROBLEMATISCH) {
                    problematische.add(o.nodeId());
                }
            }

            if (auszulagernde.size() > cli.auslagerungMaxKandidaten) {
                System.out.printf("%n      WARNUNG: %d Kandidaten > Limit %d, ueberspringe Optimierung.%n",
                        auszulagernde.size(), cli.auslagerungMaxKandidaten);
                System.out.println("             Setze --auslagerung-max-kandidaten hoeher und/oder filtere Outlier im Voraus.");
            } else {
            System.out.println();
            System.out.printf("[+] SCS-Optimierung mit Auslagerung (min SCS-Size %d, %d Kandidaten, %d problematisch)%n",
                    cli.minScsSize, auszulagernde.size(), problematische.size());
            long tOpt = System.currentTimeMillis();
            ScsZerlegung scsEngine = new ScsZerlegung(pg);

            ScsZerlegung.OptimierungsErgebnis opt =
                    scsEngine.optimiereMitAuslagerung(
                            new HashSet<>(), cli.scsResolution,
                            auszulagernde, cli.minScsSize, coreSet80, problematische);
            zerlegung = opt.zerlegung();
            iterHistory = opt.history();
            metrikVorher = opt.metrikVorher();
            metrikNachher = opt.metrikNachher();
            packagesAusgelagert = opt.packagesAusgelagert();
            long msOpt = System.currentTimeMillis() - tOpt;

            System.out.println("      Ungewoellte Beziehungen (Cardinality):");
            System.out.printf("        %-28s %12s %12s %10s%n",
                    "Kategorie", "vorher", "nachher", "Aenderung");
            printMetrikZeile("SCS <-> SCS", metrikVorher.scsZuScs(), metrikNachher.scsZuScs());
            printMetrikZeile("Core -> SCS", metrikVorher.coreZuScs(), metrikNachher.coreZuScs());
            printMetrikZeile("Overlay -> SCS", metrikVorher.overlayZuScs(), metrikNachher.overlayZuScs());
            System.out.printf("        %-28s %12s %12s %10s%n",
                    "GESAMT", fmtCard(metrikVorher.gesamt()), fmtCard(metrikNachher.gesamt()),
                    String.format(java.util.Locale.ROOT, "%+.1f%%",
                            metrikVorher.gesamt() > 0
                                    ? (metrikNachher.gesamt() - metrikVorher.gesamt())
                                      / metrikVorher.gesamt() * 100.0
                                    : 0.0));
            System.out.printf("      Reduktion der ungewollten Beziehungen: %.1f%%%n",
                    opt.reductionProzent());
            System.out.printf("      Final: %d SCS + %d SmallCand-Pakete (%d Packages ausgelagert, %d ms)%n",
                    zerlegung.scs().size(), zerlegung.smallCandidates().size(),
                    packagesAusgelagert, msOpt);

            System.out.println("      Subsysteme nach Optimierung (nach Groesse):");
            System.out.printf("        %-4s %-7s %-8s %-34s %-22s %-7s %-7s%n",
                    "ID", "Bundles", "Packages", "dominantes Produkt", "Schicht", "ReinP", "ReinS");
            for (ScsZerlegung.Scs s : zerlegung.scs()) {
                System.out.printf("        %-4d %-7d %-8d %-34s %-22s %-7.2f %-7.2f%n",
                        s.id(), s.anzahlBundles(), s.packages().size(),
                        abkuerzen(s.dominantesProdukt(), 33),
                        abkuerzen(s.dominanteSchicht(), 21),
                        s.reinheitProdukt(), s.reinheitSchicht());
            }
            } // end else (Kandidaten <= Limit)
        }

        // Subsystem-Graph als inline-SVG (im HTML) und als DOT (extern renderbar).
        SubsystemGraphViz viz = new SubsystemGraphViz(pg);
        String subsystemSvg = viz.toSvg(zerlegung,
                varianten.streng().packages(), bundleOf);
        String topEdgesHtml = viz.topEdgesAsHtml(zerlegung,
                varianten.streng().packages(), bundleOf, 30);

        new HtmlReportWriter().write(cli.output, outliers,
                pg.nodeCount(), pg.edgeCount(), bundles,
                cli.percentile, cli.input,
                varianten, zerlegung, bundleOf, subsystemSvg, topEdgesHtml,
                new OverlayAnalyse(), pg,
                iterHistory, metrikVorher, metrikNachher, packagesAusgelagert);

        // DOT-Datei parallel — fuer externe Render-Tools (graphviz/dot).
        Path dotPath = dotPathFor(cli.output);
        viz.write(dotPath, zerlegung);
        System.out.println("Subsystem-Graph (DOT): " + dotPath);

        long ms = System.currentTimeMillis() - t0;
        System.out.println("Report geschrieben: " + cli.output + " (" + ms + " ms)");
    }

    private static String kuerze(String s, int max) {
        if (s == null) return "?";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** Gibt eine Metrik-Zeile (vorher/nachher/Aenderung) formatiert aus. */
    private static void printMetrikZeile(String label, double vorher, double nachher) {
        double diff = vorher > 0 ? (nachher - vorher) / vorher * 100.0 : 0.0;
        System.out.printf("        %-28s %12s %12s %10s%n",
                label, fmtCard(vorher), fmtCard(nachher),
                String.format(java.util.Locale.ROOT, "%+.1f%%", diff));
    }

    /** Formatiert eine Cardinality-Zahl kompakt (1.2k, 345k, 1.2M). */
    private static String fmtCard(double w) {
        if (w >= 1_000_000) return String.format(java.util.Locale.ROOT, "%.1fM", w / 1_000_000);
        if (w >= 1000)      return String.format(java.util.Locale.ROOT, "%.1fk", w / 1000);
        return String.valueOf((long) w);
    }

    private static String abkuerzen(String s, int max) {
        if (s == null) return "?";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** Liefert den Pfad fuer die DOT-Datei neben dem HTML-Report. */
    private static Path dotPathFor(Path html) {
        String name = html.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return html.resolveSibling(base + ".dot");
    }

    private static void printTop(List<StrukturDivergenz.Divergenz> list, int n) {
        for (int i = 0; i < Math.min(n, list.size()); i++) {
            StrukturDivergenz.Divergenz d = list.get(i);
            System.out.printf("        %-46s bundle=%-28s %s%n",
                    d.packageName(), d.bundle(), d.reason());
        }
    }

    private static void printKantenTop(List<StrukturDivergenz.KantenDivergenz> list, int n) {
        for (int i = 0; i < Math.min(n, list.size()); i++) {
            StrukturDivergenz.KantenDivergenz k = list.get(i);
            System.out.printf("        %-52s%n", k.packageName());
            System.out.printf("            bundle=%s (n=%d)  aus=%.0f%%  ein=%.0f%%  eigen=%.0f%%%n",
                    k.bundle(), (int) k.bundleSize(),
                    k.outShare() * 100, k.inShare() * 100, k.ownShare() * 100);
        }
    }

    private static void printHelp() {
        String s = """
            PackageGraph DSM Analyzer

            Usage:
              java -jar packagegraph-analyzer.jar --input <gml> --output <html>
                                                  [--percentile N] [--min-bundle-size N] [--help]

            Options:
              --input <path>         Pflicht: GML-Datei (tk-dependencyanalyse-Export)
              --output <path>        Pflicht: HTML-Report
              --percentile N         Top-N-%% pro Bundle, default 5 (Werte 1..100)
              --min-bundle-size N    Minimale Bundle-Groesse, default 5
              --min-bundle-share F   Anteil im eigenen Bundle, ab dem ein Package
                                    als aufgehoben gilt, default 0.5
              --scs-resolution F     Aufloesung der SCS-Zerlegung,
                                    default 0.35 (kleiner = weniger Cluster)
              --auslagerung-iterations N
                                    SCS-Optimierung mit Auslagerung der als
                                    sinnvoll/bedenklich bewerteten Outlier,
                                    default 50 (0 = keine Optimierung)
              --min-scs-size N      Mindestgroesse (Packages) eines SCS, darunter
                                    wandert es in smallCandidates, default 100
              --auslagerung-max-kandidaten N
                                    Sicherheitsnetz: ueber dieser Anzahl
                                    Outlier-Kandidaten wird die Optimierung
                                    uebersprungen, default 3000
              --help                 Diese Hilfe
            """;
        System.out.println(s);
    }

    // ---------------------------------------------------------------------
    // CLI-Argument-Parsing (kein externes Dep noetig)
    // ---------------------------------------------------------------------

    static final class CliArgs {
        Path input;
        Path output;
        double percentile = DEFAULT_PERCENTILE;
        int minBundleSize = DEFAULT_MIN_BUNDLE_SIZE;
        double minBundleShare = DEFAULT_MIN_BUNDLE_SHARE;
        double scsResolution = DEFAULT_SCS_RESOLUTION;
        int auslagerungIterations = DEFAULT_AUSLAGERUNG_ITERATIONS;
        int minScsSize = DEFAULT_MIN_SCS_SIZE;
        int auslagerungMaxKandidaten = DEFAULT_AUSLAGERUNG_MAX_KANDIDATEN;
        boolean help;

        static CliArgs parse(String[] args) {
            CliArgs c = new CliArgs();
            if( args.length == 0) {
                args = new String[] {
                    "--input", "packageGraph.gml",
                    "--output", "report.html"
                };
            }
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "--help", "-h" -> c.help = true;
                    case "--input" -> { c.input = Paths.get(require(args, ++i, "--input")); }
                    case "--output" -> { c.output = Paths.get(require(args, ++i, "--output")); }
                    case "--percentile" -> {
                        c.percentile = parseDouble(require(args, ++i, "--percentile"), a);
                        if (c.percentile <= 0 || c.percentile > 100)
                            throw new IllegalArgumentException("--percentile muss in (0,100] sein");
                    }
                    case "--min-bundle-size" -> {
                        c.minBundleSize = parseInt(require(args, ++i, "--min-bundle-size"), a);
                        if (c.minBundleSize < 2)
                            throw new IllegalArgumentException("--min-bundle-size muss >= 2 sein");
                    }
                    case "--min-bundle-share" -> {
                        c.minBundleShare = parseDouble(require(args, ++i, "--min-bundle-share"), a);
                        if (c.minBundleShare <= 0 || c.minBundleShare > 1)
                            throw new IllegalArgumentException("--min-bundle-share muss in (0,1] sein");
                    }
                    case "--scs-resolution" -> {
                        c.scsResolution = parseDouble(require(args, ++i, "--scs-resolution"), a);
                        if (c.scsResolution <= 0)
                            throw new IllegalArgumentException("--scs-resolution muss > 0 sein");
                    }
                    case "--auslagerung-iterations" -> {
                        c.auslagerungIterations = parseInt(require(args, ++i, "--auslagerung-iterations"), a);
                        if (c.auslagerungIterations < 0)
                            throw new IllegalArgumentException("--auslagerung-iterations muss >= 0 sein");
                    }
                    case "--min-scs-size" -> {
                        c.minScsSize = parseInt(require(args, ++i, "--min-scs-size"), a);
                        if (c.minScsSize < 1)
                            throw new IllegalArgumentException("--min-scs-size muss >= 1 sein");
                    }
                    case "--auslagerung-max-kandidaten" -> {
                        c.auslagerungMaxKandidaten = parseInt(require(args, ++i, "--auslagerung-max-kandidaten"), a);
                        if (c.auslagerungMaxKandidaten < 1)
                            throw new IllegalArgumentException("--auslagerung-max-kandidaten muss >= 1 sein");
                    }
                    default -> throw new IllegalArgumentException("Unbekannte Option: " + a);
                }
            }
            return c;
        }

        private static String require(String[] args, int i, String name) {
            if (i >= args.length) throw new IllegalArgumentException(name + " erwartet einen Wert");
            return args[i];
        }

        private static double parseDouble(String s, String name) {
            try { return Double.parseDouble(s); }
            catch (NumberFormatException e) {
                throw new IllegalArgumentException(name + " muss eine Zahl sein: " + s);
            }
        }

        private static int parseInt(String s, String name) {
            try { return Integer.parseInt(s); }
            catch (NumberFormatException e) {
                throw new IllegalArgumentException(name + " muss eine Ganzzahl sein: " + s);
            }
        }
    }
}

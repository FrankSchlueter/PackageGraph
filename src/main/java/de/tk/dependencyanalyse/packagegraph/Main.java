package de.tk.dependencyanalyse.packagegraph;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.tk.dependencyanalyse.packagegraph.analysis.BundleOutlierDetector;
import de.tk.dependencyanalyse.packagegraph.analysis.NodeDependencyLevelCalculator;
import de.tk.dependencyanalyse.packagegraph.analysis.OutlierEntry;
import de.tk.dependencyanalyse.packagegraph.dsm.DsmSorter;
import de.tk.dependencyanalyse.packagegraph.dsm.LevelCalculator;
import de.tk.dependencyanalyse.packagegraph.gml.GmlReader;
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
        System.out.println("[1/5] Lese GML: " + cli.input);
        GmlReader.Graph raw = GmlReader.read(cli.input);
        System.out.println("      -> " + raw.nodes().size() + " Knoten, "
                + raw.edges().size() + " Kanten (roh)");

        System.out.println("[2/5] Baue JGraphT-Graphen");
        PackageGraph pg = new PackageGraphBuilder().build(raw);
        System.out.println("      -> " + pg.nodeCount() + " Knoten, "
                + pg.edgeCount() + " Kanten (nach Self-Edge-Filter)");

        System.out.println("[3/5] DSM-Sortierung");
        List<String> order = new DsmSorter(pg).sort();
        System.out.println("      -> DSM-Reihenfolge gespeichert (erste 3: "
                + String.join(", ", order.subList(0, Math.min(3, order.size()))) + ")");

        System.out.println("[4/5] Berechne Level + nodeDependencyLevel");
        Map<String, Integer> levels = new LevelCalculator(pg).compute();
        Map<String, Double> ndls = new NodeDependencyLevelCalculator(pg, levels).compute();
        System.out.println("      -> Level-Range: "
                + levels.values().stream().mapToInt(Integer::intValue).min().orElse(0)
                + " .. " + levels.values().stream().mapToInt(Integer::intValue).max().orElse(0));
        System.out.println("      -> ndl-Range: "
                + ndls.values().stream().mapToDouble(Double::doubleValue).min().orElse(0)
                + " .. " + ndls.values().stream().mapToDouble(Double::doubleValue).max().orElse(0));

        System.out.println("[5/5] Outlier pro Bundle (Top-" + cli.percentile + " %)");
        BundleOutlierDetector detector = new BundleOutlierDetector(pg, cli.minBundleSize);
        List<OutlierEntry> outliers = detector.detect(cli.percentile);
        int bundles = (int) outliers.stream().map(OutlierEntry::bundle).distinct().count();
        System.out.println("      -> " + outliers.size() + " Outlier in " + bundles + " Bundles");

        new HtmlReportWriter().write(cli.output, outliers,
                pg.nodeCount(), pg.edgeCount(), bundles,
                cli.percentile, cli.input);
        long ms = System.currentTimeMillis() - t0;
        System.out.println("Report geschrieben: " + cli.output + " (" + ms + " ms)");
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

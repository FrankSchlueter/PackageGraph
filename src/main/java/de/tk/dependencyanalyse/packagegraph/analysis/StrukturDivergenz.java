package de.tk.dependencyanalyse.packagegraph.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

import org.jgrapht.graph.DefaultWeightedEdge;

import de.tk.dependencyanalyse.packagegraph.graph.PackageGraph;

/**
 * Vergleicht strukturelle Cluster (z. B. Leiden) mit den fachlichen
 * Gruppierungen des Package-Graphen ({@code bundle}, {@code product}).
 *
 * <p>Gesucht sind Packages, die in Bundles und Products strukturell
 * <em>nicht gut aufgehoben</em> sind, also deren strukturelle Umgebung
 * (Cluster) nicht von der fachlichen Einordnung getragen wird. Typische
 * Faelle:
 *
 * <ul>
 *   <li>Das Package liegt in einem strukturellen Cluster, dessen
 *       Leit-Package-Bundle ein anderes ist (Fremd-Cluster).</li>
 *   <li>Das Package liegt in einem Cluster, in dem es strukturell
 *       allein steht (Isoliert im eigenen Fach-Bundle).</li>
 *   <li>Das Bundle ist ueber viele strukturelle Cluster verstreut
 *       (Bundle-Zersplitterung).</li>
 * </ul>
 */
/**
 * Kennzeichnet Packages, deren strukturelle Einbindung nicht zur eigenen
 * Fachgruppe passt — gemessen an den Kanten selbst, nicht an der
 * Clusterzugehoerigkeit.
 *
 * <p><b>Warum nicht ueber Clusterzugehoerigkeit:</b> Clusterzugehoerigkeit
 * ist bei dieser Datenlage nicht differenzierend. Das groesste Bundle
 * (eine ubiquitäre Datenaustausch-Library) umfasst Tausende von Packages
 * und stellt damit in praktisch jedem Cluster die Mehrheit. Jedes Package,
 * das strukturell an eine solche geteilte Library andockt, wuerde dann als
 * "fremder Cluster" markiert — bei 98 % aller Packages ist das keine
 * Information mehr.
 *
 * <p><b>Die Kantenmessung ist dagegen scharf:</b> Sie vergleicht den
 * Anteil des Abhaengigkeitsgewichts, der innerhalb der eigenen Fachgruppe
 * verbleibt, mit dem Anteil, der nach aussen zeigt. Ein Package mit hohem
 * Auswaertsanteil hat mehrere fachliche Zustaende zugleich und ist damit
 * ein Kandidat fuer eine Aufspaltung oder eine falsche Bundlezuordnung.
 *
 * <p><b>Generierter Code wird ausgeklammert:</b> Rund 4400 der 21800
 * Packages sind aus XML-Schema/JAXB generiert ({@code document},
 * {@code xml}, {@code xsd}, {@code jaxb}, {@code generiert} als
 * Pfadsegment, dazu XSD-Versionsmuster wie {@code v03m00} und
 * UMLS-Codes wie {@code s053cnds}). Diese Pakete bilden reine Daten- und
 * Schema-Grenzen ohne eigene fachliche Architektur und wuerden jede
 * Divergenzkennzahl verrauschen. Handgeschriebene Domänenklassen
 * ({@code entity}, {@code model}) bleiben bewusst <em>enthalten</em>.
 */
public final class StrukturDivergenz {

    // ---------------------------------------------------------------------
    // Filter: generierter Code
    // ---------------------------------------------------------------------

    /**
     * Pfadsegmente, die generierten Schema-/Mapping-Code kennzeichnen.
     * Bewusst NICHT enthalten: {@code entity}, {@code model}, {@code dto} —
     * das sind handgeschriebene Domänenklassen.
     */
    private static final Set<String> GENERATED_SEGMENTS = Set.of(
            "document", "xml", "xsd", "jaxb", "generiert");

    /** XSD-Versionsmuster, z. B. {@code v03m00}, {@code v4m02}. */
    private static final Pattern XSD_VERSION =
            Pattern.compile("\\.v\\d+m\\d+");

    /** UMLS-/XSD-Nachrichten-Codes, z. B. {@code s053cnds}, {@code syn901n}. */
    private static final Pattern XSD_MESSAGE_CODE =
            Pattern.compile("\\.(s|syn)\\d{3}[a-z]*(\\.|$)");

    /**
     * Prueft, ob ein Package-Namen auf generierten Code deutet.
     *
     * @param packageName vollstaendiger Package-Name
     * @return true bei generiertem Schema-/Mapping-Code
     */
    public static boolean isGenerated(String packageName) {
        if (packageName == null) return false;
        String lower = packageName.toLowerCase(java.util.Locale.ROOT);
        for (String seg : lower.split("\\.")) {
            if (GENERATED_SEGMENTS.contains(seg)) return true;
        }
        return XSD_VERSION.matcher(lower).find()
            || XSD_MESSAGE_CODE.matcher(lower).find();
    }

    /** Attribut-Schluessel der fachlichen Gruppierung. */
    public static final String PROP_BUNDLE = "bundle";
    public static final String PROP_PRODUCT = "product";
    public static final String PROP_NAME = "name";

    private StrukturDivergenz() {
        // Utility-Klasse.
    }

    /** Ein als problematisch eingestuftes Package. */
    public record Divergenz(String packageId,
                            String packageName,
                            String bundle,
                            String product,
                            int clusterId,
                            int clusterSize,
                            String dominantBundle,
                            double bundleShare,
                            String reason) {
    }

    /**
     * Berechnet die Divergenz zwischen strukturellen Clustern und Fach-Bundles.
     *
     * @param pg          Package-Graph (liefert die GML-Attribute)
     * @param clusters    Cluster-Id -&gt; Package-Ids (z. B. aus Leiden)
     * @param minShare    Ab diesem Anteil im eigenen Bundle gilt ein Package
     *                    als aufgehoben. Kleinere Werte sind strenger.
     * @return Liste der Divergenzen, absteigend nach Schwere
     */
    public static List<Divergenz> analyse(PackageGraph pg,
                                          Map<Integer, List<String>> clusters,
                                          double minShare) {
        List<Divergenz> result = new ArrayList<>();
        Map<String, String> bundleOf = attr(pg, PROP_BUNDLE);
        Map<String, String> productOf = attr(pg, PROP_PRODUCT);
        Map<String, String> nameOf = attr(pg, PROP_NAME);

        // Generierten Code ausklammern: er bildet keine fachliche Architektur
        // ab und wuerde jeden Cluster in der Leit-Bundle-Wahl verzerren.
        Map<Integer, List<String>> gefiltert = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<String>> e : clusters.entrySet()) {
            List<String> keep = new ArrayList<>();
            for (String p : e.getValue())
                if (!isGenerated(nameOf.getOrDefault(p, p))) keep.add(p);
            if (!keep.isEmpty()) gefiltert.put(e.getKey(), keep);
        }

        // 1. Fuer jeden Cluster das haeufigste Bundle ermitteln.
        Map<Integer, String> clusterDominant = new HashMap<>();
        Map<Integer, Map<String, Integer>> clusterBundles = new HashMap<>();
        for (Map.Entry<Integer, List<String>> e : gefiltert.entrySet()) {
            Map<String, Integer> cnt = new HashMap<>();
            for (String p : e.getValue()) cnt.merge(bundleOf.getOrDefault(p, "?"), 1, Integer::sum);
            clusterBundles.put(e.getKey(), cnt);
            clusterDominant.put(e.getKey(),
                    cnt.entrySet().stream().max(Map.Entry.comparingByValue())
                       .map(Map.Entry::getKey).orElse("?"));
        }

        // 2. Pro Package: Anteil im eigenen Bundle innerhalb seines Clusters.
        for (Map.Entry<Integer, List<String>> e : gefiltert.entrySet()) {
            int cid = e.getKey();
            List<String> members = e.getValue();
            int size = members.size();
            String dom = clusterDominant.get(cid);

            for (String p : members) {
                String b = bundleOf.getOrDefault(p, "?");
                int sameInCluster = clusterBundles.get(cid).getOrDefault(b, 0);
                double share = size == 0 ? 0 : (double) sameInCluster / size;

                String reason = null;
                if (!b.equals(dom) && sameInCluster < size) {
                    reason = "Fremd-Cluster (Leit-Bundle " + dom + ", eigener Anteil "
                           + String.format("%.0f%%", share * 100) + ")";
                } else if (share < minShare) {
                    reason = "Schwach eingebunden (eigener Anteil "
                           + String.format("%.0f%%", share * 100) + " < "
                           + String.format("%.0f%%", minShare * 100) + ")";
                }
                if (reason != null) {
                    result.add(new Divergenz(p, nameOf.getOrDefault(p, p), b,
                            productOf.getOrDefault(p, "?"), cid, size, dom, share, reason));
                }
            }
        }

        result.sort(Comparator.comparingDouble(Divergenz::bundleShare)
                             .thenComparing(Comparator.comparingInt(Divergenz::clusterSize).reversed()));
        return result;
    }

    /**
     * Zersplitterung der Fach-Bundles ueber die strukturellen Cluster.
     *
     * @return Bundle -&gt; Anzahl Cluster, absteigend sortiert
     */
    public static Map<String, Integer> bundleZersplitterung(
            PackageGraph pg, Map<Integer, List<String>> clusters) {

        Map<String, String> bundleOf = attr(pg, PROP_BUNDLE);
        Map<String, String> nameOf = attr(pg, PROP_NAME);
        Map<String, Set<Integer>> per = new HashMap<>();
        for (Map.Entry<Integer, List<String>> e : clusters.entrySet())
            for (String p : e.getValue()) {
                if (isGenerated(nameOf.getOrDefault(p, p))) continue;
                per.computeIfAbsent(bundleOf.getOrDefault(p, "?"), k -> new HashSet<>())
                  .add(e.getKey());
            }

        List<Map.Entry<String, Integer>> list = new ArrayList<>();
        for (Map.Entry<String, Set<Integer>> e : per.entrySet())
            list.add(Map.entry(e.getKey(), e.getValue().size()));
        list.sort(Map.Entry.<String, Integer>comparingByValue().reversed());

        Map<String, Integer> sorted = new LinkedHashMap<>();
        list.forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    private static Map<String, String> attr(PackageGraph pg, String key) {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : pg.nodeProps().entrySet()) {
            Object v = e.getValue().get(key);
            result.put(e.getKey(), v == null ? "?" : v.toString());
        }
        return result;
    }

    // ---------------------------------------------------------------------
    // Kantenbasierte Divergenz (die aussagekraeftige Variante)
    // ---------------------------------------------------------------------

    /** Kantenmessung fuer ein einzelnes Package. */
    public record KantenDivergenz(String packageId,
                                  String packageName,
                                  String bundle,
                                  String product,
                                  double bundleSize,
                                  double outShare,
                                  double inShare,
                                  double ownShare) {

        /** Gesamtbewertung: je haeher der Auswaertsanteil, desto schlechter. */
        public double score() {
            return (outShare + inShare) / 2.0;
        }

        /** Anzahl fremder Bundle, mit denen das Package verbunden ist. */
        public boolean isCrossCutting() {
            return outShare >= CROSS_CUTTING_OUT || inShare >= CROSS_CUTTING_IN;
        }
    }

    /** Schwelle, ab der der ausgehende Anteil als cross-cutting gilt. */
    public static final double CROSS_CUTTING_OUT = 0.9;

    /** Schwelle, ab der der eingehende Anteil als cross-cutting gilt. */
    public static final double CROSS_CUTTING_IN = 0.9;

    /** Schwelle, ab der die eigene Bundle-Gruppe vernachlaessigbar ist. */
    public static final double OWN_SHARE_MIN = 0.1;

    /** Ergebnis eines Permutationstests gegen eine Zufalls-Background. */
    public record Baseline(double gemessenAvg, double gemessenCross,
                            double zufallAvg, double zufallCross, int permutationen) {

        /** Positives Signal: ueber wie viel liegt die Messung ueber dem Zufall? */
        public double signal() {
            return zufallCross - gemessenCross;
        }

        /** Ab wann gilt ein Bundle-Label als informativ? */
        public boolean istInformativ() {
            return signal() > 0.02;
        }
    }

    /**
     * Permutationstest: Vergleicht die gemessene Auswaertsquote gegen
     * zufaellig neu vergebene Bundles bei erhaltenen Bundle-Groessen.
     *
     * <p>Ohne diese Baseline ist eine hohe Trefferquote nicht interpretierbar:
     * Sie bedeutet nur, dass Packages selten Kanten innerhalb ihres eigenen
     * Bundles haben. Ob das an der Bundle-Zuordnung oder an der Natur des
     * Graphen liegt, beantwortet erst der Vergleich mit Zufall.
     *
     * @param pg         Package-Graph
     * @param permutationen Anzahl der Zufallsläufe
     * @param seed       Zufalls-Seed fuer Reproduzierbarkeit
     */
    public static Baseline permutationstest(PackageGraph pg, int permutationen, long seed) {
        Map<String, String> bundleOf = attr(pg, PROP_BUNDLE);
        Map<String, String> nameOf = attr(pg, PROP_NAME);

        List<String> nodes = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (Map.Entry<String, String> e : bundleOf.entrySet()) {
            if (isGenerated(nameOf.getOrDefault(e.getKey(), e.getKey()))) continue;
            nodes.add(e.getKey());
            labels.add(e.getValue());
        }
        if (nodes.isEmpty()) return new Baseline(0, 0, 0, 0, 0);

        double[] gemessen = quote(nodes, labels, pg);
        List<String> shuffled = new ArrayList<>(labels);
        Random rnd = new Random(seed);
        double sumAvg = 0, sumCross = 0;
        for (int rep = 0; rep < permutationen; rep++) {
            Collections.shuffle(shuffled, rnd);
            double[] r = quote(nodes, shuffled, pg);
            sumAvg += r[0];
            sumCross += r[1];
        }
        return new Baseline(gemessen[0], gemessen[1],
                sumAvg / permutationen, sumCross / permutationen, permutationen);
    }

    /**
     * Durchschnittlicher Auswaertsanteil und Anteil cross-cutting.
     *
     * @return {"avg", "cross"} bei Packages mit ausgehenden Kanten
     */
    private static double[] quote(List<String> nodes, List<String> labels,
                                  PackageGraph pg) {
        Map<String, String> lab = new HashMap<>();
        Set<String> set = new HashSet<>(nodes);
        Map<String, Double> out = new HashMap<>();
        Map<String, Double> own = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) lab.put(nodes.get(i), labels.get(i));

        for (DefaultWeightedEdge e : pg.graph().edgeSet()) {
            String s = pg.graph().getEdgeSource(e);
            String t = pg.graph().getEdgeTarget(e);
            if (!set.contains(s) || !set.contains(t)) continue;
            double w = pg.graph().getEdgeWeight(e);
            out.merge(s, w, Double::sum);
            if (lab.get(s).equals(lab.get(t))) own.merge(s, w, Double::sum);
        }
        double sum = 0;
        int n = 0, cross = 0;
        for (String s : nodes) {
            double ot = out.getOrDefault(s, 0.0);
            if (ot <= 0) continue;
            double sh = 1.0 - own.getOrDefault(s, 0.0) / ot;
            sum += sh;
            n++;
            if (sh >= CROSS_CUTTING_OUT) cross++;
        }
        return n == 0 ? new double[]{0, 0} : new double[]{sum / n, (double) cross / n};
    }

    /**
     * Misst fuer jedes Package, wie stark seine Kanten aus der eigenen
     * Fachgruppe herauszeigen.
     *
     * @param pg Package-Graph
     * @return Kantenmessung je Package, absteigend nach {@code score()}
     */
    public static List<KantenDivergenz> kantenDivergenz(PackageGraph pg) {
        return kantenDivergenz(pg, true);
    }

    /**
     * Misst fuer jedes Package, wie stark seine Kanten aus der eigenen
     * Fachgruppe herauszeigen.
     *
     * @param pg                Package-Graph
     * @param ohneGenerierter   true = generierter Schema-Code ausklammern
     */
    public static List<KantenDivergenz> kantenDivergenz(PackageGraph pg,
                                                        boolean ohneGenerierter) {
        Map<String, String> bundleOf = attr(pg, PROP_BUNDLE);
        Map<String, String> productOf = attr(pg, PROP_PRODUCT);
        Map<String, String> nameOf = attr(pg, PROP_NAME);

        Set<String> relevant = new HashSet<>();
        for (String p : bundleOf.keySet()) {
            if (ohneGenerierter && isGenerated(nameOf.getOrDefault(p, p))) continue;
            relevant.add(p);
        }

        // Gewichtete Ein-/Ausgangssummen je Package.
        // Kanten zu ausgeklammerten Packages werden NICHT mitgezaehlt, damit
        // ein reiner Schema-Callback nicht als Auswaertsanteil erscheint.
        Map<String, Double> outTotal = new HashMap<>();
        Map<String, Double> outOwn = new HashMap<>();
        Map<String, Double> inTotal = new HashMap<>();
        Map<String, Double> inOwn = new HashMap<>();

        for (DefaultWeightedEdge e : pg.graph().edgeSet()) {
            String s = pg.graph().getEdgeSource(e);
            String t = pg.graph().getEdgeTarget(e);
            if (!relevant.contains(s) && !relevant.contains(t)) continue;
            double w = pg.graph().getEdgeWeight(e);
            String bs = bundleOf.getOrDefault(s, "?");
            String bt = bundleOf.getOrDefault(t, "?");

            if (relevant.contains(s)) {
                outTotal.merge(s, w, Double::sum);
                if (relevant.contains(t) && bs.equals(bt)) outOwn.merge(s, w, Double::sum);
            }
            if (relevant.contains(t)) {
                inTotal.merge(t, w, Double::sum);
                if (relevant.contains(s) && bs.equals(bt)) inOwn.merge(t, w, Double::sum);
            }
        }

        // Bundle-Groessen (Anzahl Packages) fuer den Kontext.
        Map<String, Integer> bundleSize = new HashMap<>();
        for (String b : bundleOf.values()) bundleSize.merge(b, 1, Integer::sum);

        List<KantenDivergenz> result = new ArrayList<>();
        for (String p : relevant) {
            double ot = outTotal.getOrDefault(p, 0.0);
            double it = inTotal.getOrDefault(p, 0.0);
            double oo = outOwn.getOrDefault(p, 0.0);
            double io = inOwn.getOrDefault(p, 0.0);

            double outShare = ot > 0 ? 1.0 - oo / ot : 0.0;
            double inShare = it > 0 ? 1.0 - io / it : 0.0;
            double ownShare = ot > 0 ? oo / ot : 0.0;

            result.add(new KantenDivergenz(p, nameOf.getOrDefault(p, p),
                    bundleOf.get(p), productOf.get(p),
                    bundleSize.getOrDefault(bundleOf.get(p), 0),
                    outShare, inShare, ownShare));
        }
        result.sort(Comparator.comparingDouble(KantenDivergenz::score).reversed());
        return result;
    }
}
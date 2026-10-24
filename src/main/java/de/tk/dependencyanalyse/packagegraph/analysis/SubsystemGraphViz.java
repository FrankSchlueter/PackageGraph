package de.tk.dependencyanalyse.packagegraph.analysis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultWeightedEdge;

import de.tk.dependencyanalyse.packagegraph.graph.PackageGraph;
import de.tk.dependencyanalyse.packagegraph.graph.PackageGraphBuilder;

/**
 * Erzeugt eine Graphviz-DOT-Darstellung der Subsystem-Beziehungen.
 *
 * <p><b>Was gezeigt wird.</b> Ein Knoten pro SCS plus <em>Core</em> und
 * <em>Overlay</em>. Kanten zwischen SCSs sind aggregiert: ueber alle
 * Bundle-Paare zwischen den beiden SCSs summiert die Cardinality. Eine
 * gerichtete Kante besagt, dass die Bundles der Quelle von den Bundles des
 * Ziels abhaengen.
 *
 * <p><b>Wie die Cardinality sichtbar wird.</b>
 * <ul>
 *   <li>Kantenstaerke proportional zum Logarithmus der Summe (visuelle
 *       Spreizung zwischen "leicht" und "schwer").</li>
 *   <li>Spaltenbezeichnung mit dem exakten Wert.</li>
 *   <li>Bidirektionale Kanten (Rueckkopplung zwischen Subsystemen)
 *       rot eingefaerbt — das ist genau das Stoerbild, das die SCS-
 *       Zerlegung vermeiden muss.</li>
 * </ul>
 *
 * <p>Die DOT-Datei kann mit {@code dot -Tsvg subsysteme.dot -o subsysteme.svg}
 * oder {@code -Tpng} gerendert werden.
 */
public final class SubsystemGraphViz {

    /** Id eines Knotens im Graph. */
    private record NodeId(String name) {
        @Override public String toString() { return name; }
    }

    /** Aggregierte Kante zwischen zwei Knoten. */
    private record AggEdge(NodeId from, NodeId to, double weight) {
    }

    private final PackageGraph pg;

    public SubsystemGraphViz(PackageGraph pg) {
        this.pg = pg;
    }

    /** Schreibt das DOT nach {@code out}. */
    public void write(Path out, ScsZerlegung.Zerlegung zerlegung) throws IOException {
        Files.writeString(out, toDot(zerlegung), StandardCharsets.UTF_8);
    }

    /** Liefert die DOT-Darstellung als String. */
    public String toDot(ScsZerlegung.Zerlegung z) {
        StringBuilder sb = new StringBuilder();
        sb.append("// SCS-Zerlegung: Subsystem-Graph\n");
        sb.append("// Kantenstaerke = Summe der Cardinalities zwischen den Bundles\n");
        sb.append("// Bidirektionale Kanten (rot) weisen auf Rueckkopplung hin\n");
        sb.append("digraph Subsysteme {\n");
        sb.append("  rankdir=LR;\n");
        sb.append("  graph [fontname=\"Helvetica\", fontsize=11, "
                + "bgcolor=\"#fafbfc\", pad=\"0.5\", nodesep=\"0.6\", ranksep=\"1.2\"];\n");
        sb.append("  node  [shape=box, style=\"filled,rounded\", fontname=\"Helvetica\", fontsize=10];\n");
        sb.append("  edge  [fontname=\"Helvetica\", fontsize=9];\n\n");

        // ---- Knoten ----
        Map<String, NodeId> knoten = new HashMap<>();
        knoten.put("Core",       new NodeId("Core"));
        knoten.put("Overlay",    new NodeId("Overlay"));
        for (ScsZerlegung.Scs s : z.scs()) {
            String key = "SCS" + s.id();
            knoten.put(key, new NodeId(key));
        }

        // Core- und Overlay-Inhalte bestimmen.
        Set<String> coreBundleNames = new HashSet<>(z.coreBundles());
        Set<String> overlayBundleNames = new HashSet<>(z.overlayBundles());
        Map<String, String> nameVonBundle = new HashMap<>();
        Map<String, Integer> sizeVonBundle = new TreeMap<>();
        for (Map.Entry<String, Map<String, Object>> e : pg.nodeProps().entrySet()) {
            Object bv = e.getValue().get(PackageGraphBuilder.PROP_BUNDLE);
            Object nv = e.getValue().get(PackageGraphBuilder.PROP_NAME);
            String b = bv == null ? "(?)" : bv.toString();
            String n = nv == null ? "" : nv.toString();
            String prev = nameVonBundle.putIfAbsent(b, n);
            sizeVonBundle.merge(b, 1, Integer::sum);
            if (prev == null) nameVonBundle.put(b, n);
        }

        // SCS-Labels (Package-Zahl + dominanter Produkt).
        for (ScsZerlegung.Scs s : z.scs()) {
            String key = "SCS" + s.id();
            String label = String.format(
                    "SCS%d\\n%d Bundles / %d Packages\\n%s\\n%s",
                    s.id(), s.anzahlBundles(), s.packages().size(),
                    kuerzen(s.dominantesProdukt(), 28),
                    kuerzen(s.dominanteSchicht(), 18));
            double reinP = s.reinheitProdukt();
            String farbe = farbeFuerReinheit(reinP);
            sb.append("  \"").append(key).append("\"")
             .append(" [label=\"").append(label).append("\"")
             .append(", fillcolor=\"").append(farbe).append("\"];\n");
        }

        // Core-Knoten.
        int coreBundles = coreBundleNames.size();
        int corePackages = sizeVonBundle(coreBundleNames, sizeVonBundle);
        sb.append("  \"Core\"")
         .append(" [label=\"Core\\n").append(corePackages)
         .append(" Packages\\n").append(coreBundles).append(" Bundles\"")
         .append(", fillcolor=\"#bfd4fe\", shape=component];\n");

        // Overlay-Knoten.
        int overlayPackages = sizeVonBundle(overlayBundleNames, sizeVonBundle);
        sb.append("  \"Overlay\"")
         .append(" [label=\"Overlay\\n").append(overlayPackages)
         .append(" Packages\\n").append(overlayBundleNames.size()).append(" Bundles\"")
         .append(", fillcolor=\"#ffd8a8\", shape=component];\n\n");

        // ---- Kanten ----
        // Aggregiere Bundle-Beziehungen auf SCS/Core/Overlay-Ebene.
        Map<String, String> bundleToNode = new HashMap<>();
        for (String bn : coreBundleNames) bundleToNode.put(bn, "Core");
        for (String bn : overlayBundleNames) bundleToNode.put(bn, "Overlay");
        for (ScsZerlegung.Scs s : z.scs()) {
            String key = "SCS" + s.id();
            for (String bn : s.bundles()) bundleToNode.put(bn, key);
        }

        Map<NodeId, Map<NodeId, Double>> directed = new HashMap<>();
        for (DefaultWeightedEdge e : pg.graph().edgeSet()) {
            String s = pg.graph().getEdgeSource(e);
            String t = pg.graph().getEdgeTarget(e);
            if (s.equals(t)) continue;
            String bs = nodeBundle(s, pg);
            String bt = nodeBundle(t, pg);
            if (bs == null || bt == null) continue;
            String ns = bundleToNode.get(bs);
            String nt = bundleToNode.get(bt);
            if (ns == null || nt == null || ns.equals(nt)) continue;
            double w = pg.graph().getEdgeWeight(e);
            NodeId from = knoten.get(ns);
            NodeId to = knoten.get(nt);
            directed.computeIfAbsent(from, k -> new HashMap<>())
                    .merge(to, w, Double::sum);
        }

        // Kanten ausgeben. Paare mit Kanten in beide Richtungen rot faerben.
        Set<String> bereits = new HashSet<>();
        for (Map.Entry<NodeId, Map<NodeId, Double>> e : directed.entrySet()) {
            for (Map.Entry<NodeId, Double> ee : e.getValue().entrySet()) {
                String keyA = e.getKey().name;
                String keyB = ee.getKey().name;
                String sortKey = keyA.compareTo(keyB) <= 0
                        ? keyA + "|" + keyB
                        : keyB + "|" + keyA;
                if (!bereits.add(sortKey)) continue;
                double wAtoB = ee.getValue();
                double wBtoA = directed.getOrDefault(ee.getKey(),
                        Map.of()).getOrDefault(e.getKey(), 0.0);
                boolean bidirektional = wAtoB > 0 && wBtoA > 0;
                double maxW = Math.max(wAtoB, wBtoA);
                String farbe = bidirektional ? "#cf222e" : "#0969da";
                String stil = bidirektional ? "bold" : "solid";
                sb.append("  \"").append(keyA).append("\" -> \"").append(keyB)
                 .append("\" [label=\"").append(formatCard(wAtoB))
                 .append("\", penwidth=\"").append(penwidthFor(maxW))
                 .append("\", color=\"").append(farbe)
                 .append("\", style=\"").append(stil).append("\"]");
                if (bidirektional) {
                    sb.append(";\n  \"").append(keyB).append("\" -> \"").append(keyA)
                     .append("\" [label=\"").append(formatCard(wBtoA))
                     .append("\", penwidth=\"").append(penwidthFor(maxW))
                     .append("\", color=\"").append(farbe)
                     .append("\", style=\"").append(stil).append("\", constraint=false]");
                }
                sb.append(";\n");
            }
        }

        // ---- SCC-Markierung ----
        // Falls der SCS-Graph Zyklen enthaelt, weist das auf ein Stoerbild
        // hin. Die fehlerhaften Module werden als rotes Subgraph-Band gezeichnet.
        if (!z.modulDagAzyklisch()) {
            sb.append("\n  // Zyklen zwischen Subsystemen:\n");
            for (String zyklus : z.zyklischePaare()) {
                sb.append("  // ").append(zyklus).append("\n");
            }
        }

        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Liefert die Top-N Kanten zwischen Subsystemen als HTML-Tabelle.
     * Dargestellt werden Quelle, Ziel, A-&gt;B, B-&gt;A und Markierung
     * "Zyklus", wenn bidirektional.
     */
    public String topEdgesAsHtml(ScsZerlegung.Zerlegung z,
                                 java.util.Collection<String> corePackages,
                                 java.util.Map<String, String> bundleOfPackage,
                                 int limit) {
        Map<String, Integer> bundleSize = new HashMap<>();
        for (Map<String, Object> p : pg.nodeProps().values()) {
            Object b = p.get(PackageGraphBuilder.PROP_BUNDLE);
            if (b != null) bundleSize.merge(b.toString(), 1, Integer::sum);
        }
        Set<String> coreBundleSet = new java.util.HashSet<>();
        if (corePackages != null && bundleOfPackage != null) {
            for (String pkg : corePackages) {
                String b = bundleOfPackage.get(pkg);
                if (b != null) coreBundleSet.add(b);
            }
        }

        Map<String, String> bundleToNode = new HashMap<>();
        for (String bn : coreBundleSet) bundleToNode.put(bn, "Core");
        for (String bn : z.overlayBundles()) bundleToNode.put(bn, "Overlay");
        for (ScsZerlegung.Scs s : z.scs()) {
            for (String bn : s.bundles()) bundleToNode.put(bn, "SCS" + s.id());
        }
        // Packages in SmallCandidates: Bundle auf eigenen Knoten mappen.
        for (String pkg : z.smallCandidates()) {
            String b = bundleOfPackage.get(pkg);
            if (b != null) bundleToNode.put(b, "SmallCand");
        }

        List<String> nodeIds = new ArrayList<>();
        nodeIds.add("Core"); nodeIds.add("Overlay");
        if (!z.smallCandidates().isEmpty()) nodeIds.add("SmallCand");
        for (ScsZerlegung.Scs s : z.scs()) nodeIds.add("SCS" + s.id());

        @SuppressWarnings("unchecked")
        Map<Integer, Double>[] directed = new HashMap[nodeIds.size()];
        for (int i = 0; i < nodeIds.size(); i++) directed[i] = new HashMap<>();

        for (DefaultWeightedEdge e : pg.graph().edgeSet()) {
            String srcId = pg.graph().getEdgeSource(e);
            String tgtId = pg.graph().getEdgeTarget(e);
            if (srcId.equals(tgtId)) continue;
            Map<String, Object> srcP = pg.nodeProps().get(srcId);
            Map<String, Object> tgtP = pg.nodeProps().get(tgtId);
            if (srcP == null || tgtP == null) continue;
            Object srcB = srcP.get(PackageGraphBuilder.PROP_BUNDLE);
            Object tgtB = tgtP.get(PackageGraphBuilder.PROP_BUNDLE);
            if (srcB == null || tgtB == null) continue;
            String ns = bundleToNode.get(srcB.toString());
            String nt = bundleToNode.get(tgtB.toString());
            if (ns == null || nt == null || ns.equals(nt)) continue;
            int idxS = nodeIds.indexOf(ns);
            int idxT = nodeIds.indexOf(nt);
            directed[idxS].merge(idxT, pg.graph().getEdgeWeight(e), Double::sum);
        }

        // Aggregiere pro Knoten-Paar.
        List<double[]> rows = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (int a = 0; a < nodeIds.size(); a++) {
            for (Map.Entry<Integer, Double> ee : directed[a].entrySet()) {
                int b = ee.getKey();
                long key = ((long) Math.min(a, b) << 32) | Math.max(a, b);
                if (!seen.add(key)) continue;
                double wAtoB = ee.getValue();
                double wBtoA = directed[b].getOrDefault(a, 0.0);
                double maxW = Math.max(wAtoB, wBtoA);
                if (maxW <= 0) continue;
                boolean bidi = wAtoB > 0 && wBtoA > 0;
                rows.add(new double[]{maxW, a, b, wAtoB, wBtoA, bidi ? 1 : 0});
            }
        }
        rows.sort((x, y) -> Double.compare(y[0], x[0]));

        StringBuilder h = new StringBuilder();
        h.append("<table>\n<thead><tr>")
          .append("<th>#</th><th>Quelle</th><th>Ziel</th>")
          .append("<th>A &rarr; B</th><th>B &rarr; A</th>")
          .append("<th>max</th><th>Typ</th>")
          .append("</tr></thead>\n<tbody>\n");
        int n = Math.min(limit, rows.size());
        for (int i = 0; i < n; i++) {
            double[] r = rows.get(i);
            String a = nodeIds.get((int) r[1]);
            String b = nodeIds.get((int) r[2]);
            boolean bidi = r[5] > 0;
            String farbe = bidi ? "style=\"background:#fff5f5\"" : "";
            h.append("<tr ").append(farbe).append(">")
              .append("<td class=\"dev\">").append(i + 1).append("</td>")
              .append("<td class=\"name\">").append(esc(a)).append("</td>")
              .append("<td class=\"name\">").append(esc(b)).append("</td>")
              .append("<td class=\"dev\">").append((long) r[3]).append("</td>")
              .append("<td class=\"dev\">").append((long) r[4]).append("</td>")
              .append("<td class=\"dev\"><b>").append(formatCard(r[0])).append("</b></td>")
              .append("<td>").append(bidi
                      ? "<span style=\"color:#cf222e;font-weight:bold\">Zyklus</span>"
                      : "einseitig").append("</td>")
              .append("</tr>\n");
        }
        h.append("</tbody>\n</table>");
        return h.toString();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Liefert eine self-contained SVG-Darstellung der Subsystem-Beziehungen
     * (kein externes CSS, kein JS, kein Graphiz noetig). Layout: alle Knoten
     * gleichmaessig auf einem Kreis, Kanten als quadratische Kurven zwischen
     * den Boxen, Kontrollpunkt zum Kreismittelpunkt hingezogen.
     *
     * <p><b>Lesart.</b>
     * <ul>
     *   <li>Knoten: <em>Core</em> (oben), <em>Overlay</em>, darum herum die
     *       9 SCS-Knoten. Farbe nach Reinheit (gruen / gelb / orange / rot).</li>
     *   <li>Kanten: aggregierte Cardinality zwischen den Bundle-Paaren
     *       zweier Subsysteme, logarithmisch skaliert in der Strichstaerke.</li>
     *   <li>Farbe: blau = einseitig, rot = bidirektional (SCC). Pfeilspitzen
     *       markieren die Richtung. Bidirektional = Pfeile an beiden Enden.</li>
     * </ul>
     *
     * @param z                SCS-Zerlegung (Bundle-Bestand ohne Core).
     * @param corePackages     Liste der Packages, die im Core landen wuerden
     *                         (aus CoreExtraktion). Dient nur als Label-
     *                         Information, da Core und SCS alternative
     *                         Zielarchitekturen sind und nicht kombiniert
     *                         werden.
     * @param bundleOfPackage  Package-Id -> Bundle-Name Lookup.
     */
    public String toSvg(ScsZerlegung.Zerlegung z,
                        java.util.Collection<String> corePackages,
                        java.util.Map<String, String> bundleOfPackage) {
        // 1. Bundle-Groessen (Package-Anzahl pro Bundle) fuer Labels.
        Map<String, Integer> bundleSize = new HashMap<>();
        for (Map<String, Object> p : pg.nodeProps().values()) {
            Object b = p.get(PackageGraphBuilder.PROP_BUNDLE);
            if (b != null) bundleSize.merge(b.toString(), 1, Integer::sum);
        }

        // 2. Knoten-Liste: Core, Overlay, dann SCSs in der gegebenen Reihenfolge.
        List<String> nodeIds = new ArrayList<>();
        List<List<String>> nodeLabelLines = new ArrayList<>();
        List<String> nodeFills = new ArrayList<>();

        // Core-Knoten zeigt die Core-Kandidaten aus der CoreExtraktion.
        // Core und SCS sind ALTERNATIVE Zielarchitekturen — daher ist der
        // Core-Knoten nur informativ und fliesst NICHT in die Kanten-
        // aggregation ein (sonst wuerde er 265 Bundles verschlucken und
        // alle Subsysteme kuenstlich verkleinern).
        Set<String> coreBundleSet = new java.util.HashSet<>();
        if (corePackages != null && bundleOfPackage != null) {
            for (String pkg : corePackages) {
                String b = bundleOfPackage.get(pkg);
                if (b != null) coreBundleSet.add(b);
            }
        }
        nodeIds.add("Core");
        nodeLabelLines.add(Arrays.asList("Core",
                (corePackages == null ? 0 : corePackages.size()) + " Packages",
                coreBundleSet.size() + " Bundles (Kandidat)"));
        nodeFills.add("#bfd4fe");

        nodeIds.add("Overlay");
        nodeLabelLines.add(Arrays.asList("Overlay",
                bundleSizeSum(z.overlayBundles(), bundleSize) + " Packages",
                z.overlayBundles().size() + " Bundles"));
        nodeFills.add("#ffd8a8");

        if (!z.smallCandidates().isEmpty()) {
            nodeIds.add("SmallCand");
            nodeLabelLines.add(Arrays.asList("SmallCand",
                    z.smallCandidates().size() + " Packages",
                    "<Mindestgroesse"));
            nodeFills.add("#e1e4e8");
        }

        for (ScsZerlegung.Scs s : z.scs()) {
            nodeIds.add("SCS" + s.id());
            nodeLabelLines.add(Arrays.asList(
                    "SCS" + s.id(),
                    kuerzen(s.dominantesProdukt(), 22),
                    s.packages().size() + " Pkg / " + s.anzahlBundles() + " B"));
            nodeFills.add(farbeFuerReinheit(s.reinheitProdukt()));
        }
        int N = nodeIds.size();

        // 3. Layout auf Kreis: Core oben, Overlay faehrt mit - Knoten
        // gleichmaessig verteilt, jeweils 360/N Grad Abstand.
        int W = 1500, H = 980;
        int cx = W / 2, cy = H / 2 + 20;
        int r = Math.min(W, H) / 2 - 150;

        double[] xs = new double[N];
        double[] ys = new double[N];
        for (int i = 0; i < N; i++) {
            double angle = -Math.PI / 2 + (2 * Math.PI * i) / N;
            xs[i] = cx + r * Math.cos(angle);
            ys[i] = cy + r * Math.sin(angle);
        }

        // 4. Kanten aggregieren. Die Core-Kandidaten-Bundles werden als "Core"
        // gebucht, damit die Kantenstaerke zwischen Core und SCS sichtbar
        // wird — das ist genau die Information, die man fuer die
        // Aufteilungs-Entscheidung braucht.
        Map<String, String> bundleToNode = new HashMap<>();
        for (String bn : coreBundleSet) bundleToNode.put(bn, "Core");
        for (String bn : z.overlayBundles()) bundleToNode.put(bn, "Overlay");
        for (ScsZerlegung.Scs s : z.scs()) {
            for (String bn : s.bundles()) bundleToNode.put(bn, "SCS" + s.id());
        }
        // Packages in SmallCandidates: Bundle auf eigenen Knoten mappen.
        for (String pkg : z.smallCandidates()) {
            String b = bundleOfPackage.get(pkg);
            if (b != null) bundleToNode.put(b, "SmallCand");
        }

        @SuppressWarnings("unchecked")
        Map<Integer, Double>[] directed = new HashMap[N];
        for (int i = 0; i < N; i++) directed[i] = new HashMap<>();

        for (DefaultWeightedEdge e : pg.graph().edgeSet()) {
            String srcId = pg.graph().getEdgeSource(e);
            String tgtId = pg.graph().getEdgeTarget(e);
            if (srcId.equals(tgtId)) continue;
            Map<String, Object> srcP = pg.nodeProps().get(srcId);
            Map<String, Object> tgtP = pg.nodeProps().get(tgtId);
            if (srcP == null || tgtP == null) continue;
            Object srcB = srcP.get(PackageGraphBuilder.PROP_BUNDLE);
            Object tgtB = tgtP.get(PackageGraphBuilder.PROP_BUNDLE);
            if (srcB == null || tgtB == null) continue;
            String ns = bundleToNode.get(srcB.toString());
            String nt = bundleToNode.get(tgtB.toString());
            if (ns == null || nt == null || ns.equals(nt)) continue;
            int idxS = nodeIds.indexOf(ns);
            int idxT = nodeIds.indexOf(nt);
            directed[idxS].merge(idxT, pg.graph().getEdgeWeight(e), Double::sum);
        }

        // 5. Vorab die staerksten Kanten bestimmen — nur diese bekommen
        // ein Label im Graph. Bei sehr vielen Kanten wuerden sich Labels
        // sonst unleserlich ueberlagern. Die vollstaendige Top-Liste
        // wird zusaetzlich als HTML-Tabelle unter dem Graphen ausgegeben.
        List<double[]> topEdges = new ArrayList<>(); // [maxW, a, b, bidi]
        for (int a = 0; a < N; a++) {
            for (Map.Entry<Integer, Double> ee : directed[a].entrySet()) {
                int b = ee.getKey();
                if (a >= b) continue; // nur eine Richtung, Paare nicht doppelt
                double wAtoB = ee.getValue();
                double wBtoA = directed[b].getOrDefault(a, 0.0);
                double maxW = Math.max(wAtoB, wBtoA);
                if (maxW <= 0) continue;
                topEdges.add(new double[]{maxW, a, b,
                        (wAtoB > 0 && wBtoA > 0) ? 1 : 0});
            }
        }
        topEdges.sort((x, y) -> Double.compare(y[0], x[0]));
        int labelLimit = Math.min(15, topEdges.size()); // max 15 Labels im Graph
        Set<Long> labeledKeys = new HashSet<>();
        for (int i = 0; i < labelLimit; i++) {
            double[] e = topEdges.get(i);
            int a = (int) e[1], b = (int) e[2];
            long key = ((long) Math.min(a, b) << 32) | Math.max(a, b);
            labeledKeys.add(key);
        }

        // 5. SVG-Output.
        StringBuilder sb = new StringBuilder();
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" ")
          .append("viewBox=\"0 0 ").append(W).append(" ").append(H).append("\" ")
          .append("preserveAspectRatio=\"xMidYMid meet\" ")
          .append("style=\"max-width:100%;height:auto;background:#fafbfc;display:block;margin:0 auto\">");

        // Marker-Definitionen (Pfeilspitzen).
        sb.append("<defs>")
          .append("<marker id=\"ar-bl\" viewBox=\"0 0 10 10\" refX=\"9\" refY=\"5\" "
                  + "markerWidth=\"6\" markerHeight=\"6\" orient=\"auto\">")
          .append("<path d=\"M0,0 L10,5 L0,10 z\" fill=\"#0969da\"/></marker>")
          .append("<marker id=\"ar-rd\" viewBox=\"0 0 10 10\" refX=\"9\" refY=\"5\" "
                  + "markerWidth=\"6\" markerHeight=\"6\" orient=\"auto-start-reverse\">")
          .append("<path d=\"M0,0 L10,5 L0,10 z\" fill=\"#cf222e\"/></marker>")
          .append("</defs>");

        // Titel.
        sb.append("<text x=\"").append(cx).append("\" y=\"32\" text-anchor=\"middle\" ")
          .append("font-family=\"Helvetica,Arial,sans-serif\" font-size=\"16\" font-weight=\"bold\" fill=\"#1f2328\">")
          .append("Subsystem-Beziehungen (aggregierte Cardinality)")
          .append("</text>");

        // 6. Kanten zuerst zeichnen (Knoten sollen oben liegen).
        sb.append("<g class=\"edges\">");
        Set<Long> gezeichnet = new HashSet<>();
        for (int a = 0; a < N; a++) {
            for (Map.Entry<Integer, Double> ee : directed[a].entrySet()) {
                int b = ee.getKey();
                long key = ((long) Math.min(a, b) << 32) | Math.max(a, b);
                if (!gezeichnet.add(key)) continue;

                double wAtoB = ee.getValue();
                double wBtoA = directed[b].getOrDefault(a, 0.0);
                boolean bidi = wAtoB > 0 && wBtoA > 0;
                String color = bidi ? "#cf222e" : "#0969da";
                double maxW = Math.max(wAtoB, wBtoA);
                boolean showLabel = labeledKeys.contains(key) && maxW >= 100;

                double dx = xs[b] - xs[a];
                double dy = ys[b] - ys[a];
                double len = Math.sqrt(dx * dx + dy * dy);
                if (len < 1) continue;
                double ux = dx / len, uy = dy / len;

                // Start/End um den halben Box-Durchmesser zurueckversetzt,
                // damit Pfeilspitzen nicht in der Box verschwinden.
                double boxR = 55;
                double x1 = xs[a] + boxR * ux;
                double y1 = ys[a] + boxR * uy;
                double x2 = xs[b] - boxR * ux;
                double y2 = ys[b] - boxR * uy;

                // Kontrollpunkt: Mittelpunkt zum Kreismittelpunkt gezogen.
                double mx = (x1 + x2) / 2;
                double my = (y1 + y2) / 2;
                double tdx = cx - mx;
                double tdy = cy - my;
                double tlen = Math.sqrt(tdx * tdx + tdy * tdy);
                if (tlen > 0) { tdx /= tlen; tdy /= tlen; }
                double curveStrength = Math.min(100, len * 0.18);
                double cpx = mx + tdx * curveStrength;
                double cpy = my + tdy * curveStrength;

                double thickness = Math.max(0.6, Math.min(7, 0.5 + Math.log10(Math.max(1, maxW))));

                String pathD = String.format(Locale.ROOT,
                        "M %.1f %.1f Q %.1f %.1f %.1f %.1f",
                        x1, y1, cpx, cpy, x2, y2);

                if (bidi) {
                    sb.append("<path d=\"").append(pathD).append("\" fill=\"none\" stroke=\"")
                      .append(color).append("\" stroke-width=\"")
                      .append(String.format(Locale.ROOT, "%.1f", thickness))
                      .append("\" marker-start=\"url(#ar-rd)\" marker-end=\"url(#ar-rd)\" opacity=\"0.7\"/>");
                } else {
                    sb.append("<path d=\"").append(pathD).append("\" fill=\"none\" stroke=\"")
                      .append(color).append("\" stroke-width=\"")
                      .append(String.format(Locale.ROOT, "%.1f", thickness))
                      .append("\" marker-end=\"url(#ar-bl)\" opacity=\"0.75\"/>");
                }

                // Label (Cardinality) am Kontrollpunkt, wenn nennenswert.
// Individueller Offset je Kante (key-Hash), damit Labels sich bei
// vielen Kantenpaaren nicht im Zentrum ueberlagern. Bei sehr vielen
// Kanten werden die Labels weggelassen — die Tabelle unterhalb des
// Graphen enthaelt dann die vollstaendige Aufschluesselung.
                if (showLabel) {
                    String label = formatCard(maxW);
                    int h = Long.hashCode(key);
                    double ox = ((h & 0xFF) - 128) * 0.35;
                    double oy = (((h >> 8) & 0xFF) - 128) * 0.25;
                    double lx = cpx + ox;
                    double ly = cpy + oy;
                    sb.append("<rect x=\"").append(fmt(lx - 18)).append("\" y=\"")
                      .append(fmt(ly - 8)).append("\" width=\"36\" height=\"16\" rx=\"2\" ")
                      .append("fill=\"#fafbfc\" stroke=\"").append(color)
                      .append("\" stroke-width=\"0.6\" opacity=\"0.9\"/>");
                    sb.append("<text x=\"").append(fmt(lx)).append("\" y=\"")
                      .append(fmt(ly + 3)).append("\" text-anchor=\"middle\" ")
                      .append("font-family=\"Helvetica,Arial,sans-serif\" font-size=\"9\" fill=\"")
                      .append(color).append("\" font-weight=\"bold\">")
                      .append(label).append("</text>");
                }
            }
        }
        sb.append("</g>");

        // 7. Knoten.
        sb.append("<g class=\"nodes\">");
        double boxW = 140, boxH = 62;
        for (int i = 0; i < N; i++) {
            double x = xs[i], y = ys[i];
            String fill = nodeFills.get(i);
            List<String> lines = nodeLabelLines.get(i);

            sb.append("<rect x=\"").append(fmt(x - boxW / 2)).append("\" y=\"")
              .append(fmt(y - boxH / 2)).append("\" width=\"")
              .append(fmt(boxW)).append("\" height=\"").append(fmt(boxH))
              .append("\" rx=\"8\" ry=\"8\" fill=\"").append(fill)
              .append("\" stroke=\"#1f2328\" stroke-width=\"1.2\"/>");

            for (int li = 0; li < lines.size(); li++) {
                double ly = y - boxH / 2 + 17 + li * 14;
                String fw = (li == 0) ? "bold" : "normal";
                double fs = (li == 0) ? 12 : 10;
                sb.append("<text x=\"").append(fmt(x)).append("\" y=\"")
                  .append(fmt(ly)).append("\" text-anchor=\"middle\" ")
                  .append("font-family=\"Helvetica,Arial,sans-serif\" font-size=\"")
                  .append(String.format(Locale.ROOT, "%.0f", fs))
                  .append("\" font-weight=\"").append(fw).append("\" fill=\"#1f2328\">")
                  .append(esc(lines.get(li))).append("</text>");
            }
        }
        sb.append("</g>");

        // 8. Legende unten.
        int legY = H - 50;
        sb.append("<g class=\"legend\">");
        sb.append("<rect x=\"20\" y=\"").append(legY - 18).append("\" width=\"")
          .append(W - 40).append("\" height=\"42\" rx=\"4\" ")
          .append("fill=\"#ffffff\" stroke=\"#d0d7de\" stroke-width=\"1\" opacity=\"0.92\"/>");
        sb.append("<line x1=\"40\" y1=\"").append(legY).append("\" x2=\"100\" y2=\"")
          .append(legY).append("\" stroke=\"#0969da\" stroke-width=\"2.5\" marker-end=\"url(#ar-bl)\"/>");
        sb.append("<text x=\"110\" y=\"").append(legY + 4).append("\" ")
          .append("font-family=\"Helvetica,Arial,sans-serif\" font-size=\"11\" fill=\"#1f2328\">")
          .append("einseitige Abhängigkeit").append("</text>");
        sb.append("<line x1=\"320\" y1=\"").append(legY).append("\" x2=\"380\" y2=\"")
          .append(legY).append("\" stroke=\"#cf222e\" stroke-width=\"2.5\" ")
          .append("marker-start=\"url(#ar-rd)\" marker-end=\"url(#ar-rd)\"/>");
        sb.append("<text x=\"390\" y=\"").append(legY + 4).append("\" ")
          .append("font-family=\"Helvetica,Arial,sans-serif\" font-size=\"11\" fill=\"#1f2328\">")
          .append("bidirektionale Kopplung (SCC / Zyklus)").append("</text>");
        sb.append("<text x=\"720\" y=\"").append(legY + 4).append("\" ")
          .append("font-family=\"Helvetica,Arial,sans-serif\" font-size=\"11\" fill=\"#1f2328\">")
          .append("Knotenfarbe SCS: ").append("</text>");
        sb.append("<rect x=\"800\" y=\"").append(legY - 10).append("\" width=\"14\" height=\"14\" ")
          .append("fill=\"#dafbe1\" stroke=\"#1f2328\" stroke-width=\"0.6\"/>");
        sb.append("<text x=\"817\" y=\"").append(legY + 4).append("\" ")
          .append("font-family=\"Helvetica,Arial,sans-serif\" font-size=\"10\" fill=\"#1f2328\">≥0.50</text>");
        sb.append("<rect x=\"855\" y=\"").append(legY - 10).append("\" width=\"14\" height=\"14\" ")
          .append("fill=\"#fff8c5\" stroke=\"#1f2328\" stroke-width=\"0.6\"/>");
        sb.append("<text x=\"872\" y=\"").append(legY + 4).append("\" ")
          .append("font-family=\"Helvetica,Arial,sans-serif\" font-size=\"10\" fill=\"#1f2328\">≥0.25</text>");
        sb.append("<rect x=\"910\" y=\"").append(legY - 10).append("\" width=\"14\" height=\"14\" ")
          .append("fill=\"#ffe2c0\" stroke=\"#1f2328\" stroke-width=\"0.6\"/>");
        sb.append("<text x=\"927\" y=\"").append(legY + 4).append("\" ")
          .append("font-family=\"Helvetica,Arial,sans-serif\" font-size=\"10\" fill=\"#1f2328\">≥0.15</text>");
        sb.append("<rect x=\"965\" y=\"").append(legY - 10).append("\" width=\"14\" height=\"14\" ")
          .append("fill=\"#ffcecb\" stroke=\"#1f2328\" stroke-width=\"0.6\"/>");
        sb.append("<text x=\"982\" y=\"").append(legY + 4).append("\" ")
          .append("font-family=\"Helvetica,Arial,sans-serif\" font-size=\"10\" fill=\"#1f2328\">&lt;0.15 ReinP</text>");
        sb.append("</g>");

        sb.append("</svg>");
        return sb.toString();
    }

    /** Schreibt das SVG nach {@code out}. */
    public void writeSvg(Path out,
                         ScsZerlegung.Zerlegung z,
                         java.util.Collection<String> corePackages,
                         java.util.Map<String, String> bundleOfPackage) throws IOException {
        Files.writeString(out, toSvg(z, corePackages, bundleOfPackage), StandardCharsets.UTF_8);
    }

    private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.1f", d);
    }

    private static int bundleSizeSum(java.util.Collection<String> bundles, Map<String, Integer> sizeMap) {
        int s = 0;
        for (String b : bundles) s += sizeMap.getOrDefault(b, 0);
        return s;
    }

    private static String nodeBundle(String packageId, PackageGraph pg) {
        Map<String, Object> p = pg.nodeProps().get(packageId);
        if (p == null) return null;
        Object v = p.get(PackageGraphBuilder.PROP_BUNDLE);
        return v == null ? null : v.toString();
    }

    private static int sizeVonBundle(Set<String> bundles, Map<String, Integer> sizeVonBundle) {
        int s = 0;
        for (String b : bundles) s += sizeVonBundle.getOrDefault(b, 0);
        return s;
    }

    /** Penwidth logarithmisch aus dem Gewicht. */
    private static String penwidthFor(double w) {
        double p = Math.log10(Math.max(1.0, w));
        // 1.0..10.0 -> 1.0..5.0
        return String.format(java.util.Locale.ROOT, "%.1f",
                Math.max(1.0, Math.min(5.0, p)));
    }

    /** Farbe nach Reinheit (Anteil dominantes Produkt) fuer SCS-Knoten. */
    private static String farbeFuerReinheit(double r) {
        if (r >= 0.5) return "#dafbe1";   // gruen
        if (r >= 0.25) return "#fff8c5";  // gelb
        if (r >= 0.15) return "#ffe2c0";  // orange
        return "#ffcecb";                 // rot
    }

    private static String formatCard(double w) {
        if (w >= 1000) return String.format(java.util.Locale.ROOT, "%.1fk", w / 1000);
        return String.valueOf((long) w);
    }

    private static String kuerzen(String s, int max) {
        if (s == null) return "?";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
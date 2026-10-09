package de.tk.dependencyanalyse.packagegraph.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.tk.dependencyanalyse.packagegraph.graph.PackageGraph;
import de.tk.dependencyanalyse.packagegraph.graph.PackageGraphBuilder;

/**
 * Erkennt Outlier pro Bundle mittels rangbasiertem Top-N-%.
 *
 * <p>Pro Bundle werden die obersten und untersten
 * {@code ceil(p * n / 100)} Knoten nach {@code nodeDependencyLevel}
 * als Outlier markiert. Die globale Ergebnisliste ist nach
 * Abweichung vom Bundle-Mittelwert absteigend sortiert.
 *
 * <p>Bundles unterhalb der {@code minBundleSize} werden uebersprungen.
 */
public final class BundleOutlierDetector {

    private final PackageGraph pg;
    private final int minBundleSize;

    public BundleOutlierDetector(PackageGraph pg, int minBundleSize) {
        this.pg = pg;
        this.minBundleSize = minBundleSize;
    }

    /** Berechnet die Outlier. {@code percentile} in (0, 100]. */
    public List<OutlierEntry> detect(double percentile) {
        Map<String, List<String>> bundleNodes = groupByBundle();
        List<OutlierEntry> all = new ArrayList<>();

        for (Map.Entry<String, List<String>> e : bundleNodes.entrySet()) {
            String bundle = e.getKey();
            List<String> nodes = e.getValue();
            if (nodes.size() < minBundleSize) continue;

            // Map node -> ndl
            Map<String, Double> ndl = new LinkedHashMap<>();
            for (String id : nodes) {
                Map<String, Object> p = pg.nodeProps().get(id);
                if (p == null) continue;
                Object v = p.get(PackageGraphBuilder.PROP_NODE_DEPENDENCY_LEVEL);
                if (v instanceof Double d) ndl.put(id, d);
                else if (v instanceof Number n) ndl.put(id, n.doubleValue());
            }

            if (ndl.isEmpty()) continue;

            double mean = ndl.values().stream().mapToDouble(Double::doubleValue).average().orElse(0d);
            int k = Math.max(1, (int) Math.ceil(percentile * ndl.size() / 100.0));

            // sortiert nach ndl
            List<Map.Entry<String, Double>> sorted = new ArrayList<>(ndl.entrySet());
            sorted.sort(Comparator.comparingDouble(Map.Entry::getValue));

            List<Map.Entry<String, Double>> low = sorted.subList(0, Math.min(k, sorted.size()));
            List<Map.Entry<String, Double>> high = sorted.subList(
                Math.max(0, sorted.size() - k), sorted.size());

            for (Map.Entry<String, Double> ne : low) {
                all.add(makeEntry(bundle, ne.getKey(), ne.getValue(), mean, OutlierEntry.Side.LOW));
            }
            for (Map.Entry<String, Double> ne : high) {
                all.add(makeEntry(bundle, ne.getKey(), ne.getValue(), mean, OutlierEntry.Side.HIGH));
            }
        }

        // global nach Abweichung absteigend sortieren
        all.sort((a, b) -> Double.compare(b.deviation(), a.deviation()));
        return all;
    }

    private OutlierEntry makeEntry(String bundle, String nodeId, double ndl,
                                   double mean, OutlierEntry.Side side) {
        Map<String, Object> p = pg.nodeProps().get(nodeId);
        String name = p != null && p.get(PackageGraphBuilder.PROP_NAME) != null
            ? String.valueOf(p.get(PackageGraphBuilder.PROP_NAME)) : nodeId;
        int level = 0;
        if (p != null) {
            Object l = p.get(PackageGraphBuilder.PROP_LEVEL);
            if (l instanceof Integer i) level = i;
            else if (l instanceof Number n) level = n.intValue();
        }
        return new OutlierEntry(bundle, nodeId, name, level, ndl, mean, Math.abs(ndl - mean), side);
    }

    private Map<String, List<String>> groupByBundle() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : pg.nodeProps().entrySet()) {
            String nodeId = e.getKey();
            Object b = e.getValue().get(PackageGraphBuilder.PROP_BUNDLE);
            String bundle = b == null ? "(unbekannt)" : b.toString();
            map.computeIfAbsent(bundle, k -> new ArrayList<>()).add(nodeId);
        }
        return map;
    }
}

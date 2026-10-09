package de.tk.dependencyanalyse.packagegraph.graph;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DirectedMultigraph;

import de.tk.dependencyanalyse.packagegraph.gml.GmlReader;

/**
 * Baut aus dem GML-Reader-Ergebnis einen JGraphT-Graphen.
 *
 * <p>Jeder Knoten und jede Kante bekommt eine eigene HashMap, in der
 * die GML-Attribute (Strings) abgelegt werden. Self-Edges
 * ({@code source == target}) werden uebersprungen, weil sie keine
 * echte Abhaengigkeit repraesentieren.
 */
public final class PackageGraphBuilder {

    /** Attribut-Schluessel fuer berechnete Werte (Level etc.). */
    public static final String PROP_LEVEL = "level";
    public static final String PROP_NODE_DEPENDENCY_LEVEL = "nodeDependencyLevel";
    public static final String PROP_DSM_ORDER = "dsmOrder";
    public static final String PROP_BUNDLE = "bundle";
    public static final String PROP_NAME = "name";

    public PackageGraph build(GmlReader.Graph raw) {
        DirectedMultigraph<String, DefaultEdge> g = new DirectedMultigraph<>(DefaultEdge.class);
        Map<String, Map<String, Object>> nodeProps = new LinkedHashMap<>();
        Map<DefaultEdge, Map<String, Object>> edgeProps = new HashMap<>();

        for (Map<String, String> attrs : raw.nodes()) {
            String id = attrs.get(GmlReader.ATTR_ID);
            if (id == null) continue;
            if (!g.containsVertex(id)) g.addVertex(id);
            Map<String, Object> props = new HashMap<>();
            for (Map.Entry<String, String> e : attrs.entrySet()) {
                props.put(e.getKey(), e.getValue());
            }
            nodeProps.put(id, props);
        }

        for (Map<String, String> attrs : raw.edges()) {
            String src = attrs.get(GmlReader.ATTR_SOURCE);
            String tgt = attrs.get(GmlReader.ATTR_TARGET);
            if (src == null || tgt == null) continue;
            if (src.equals(tgt)) continue; // Self-Edges ignorieren
            if (!g.containsVertex(src)) g.addVertex(src);
            if (!g.containsVertex(tgt)) g.addVertex(tgt);
            DefaultEdge e = g.addEdge(src, tgt);
            if (e == null) continue;
            Map<String, Object> props = new HashMap<>();
            for (Map.Entry<String, String> ae : attrs.entrySet()) {
                props.put(ae.getKey(), ae.getValue());
            }
            edgeProps.put(e, props);
        }

        return new PackageGraph(g, nodeProps, edgeProps);
    }
}

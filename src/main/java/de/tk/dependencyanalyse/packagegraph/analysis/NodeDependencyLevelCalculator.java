package de.tk.dependencyanalyse.packagegraph.analysis;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.jgrapht.graph.DefaultEdge;

import de.tk.dependencyanalyse.packagegraph.graph.PackageGraph;
import de.tk.dependencyanalyse.packagegraph.graph.PackageGraphBuilder;

/**
 * Berechnet das {@code nodeDependencyLevel} pro Knoten:
 * Summe der Levels aller Direkt-Abhaengigkeiten geteilt durch die
 * Anzahl dieser Abhaengigkeiten.
 *
 * <p>Bei Knoten ohne Abhaengigkeiten wird {@code 1.0} gesetzt
 * (analog zur Level-Definition: Quellen = 1).
 *
 * <p>Das Ergebnis wird als Double-Property
 * {@link PackageGraphBuilder#PROP_NODE_DEPENDENCY_LEVEL}
 * auf jedem Knoten abgelegt.
 */
public final class NodeDependencyLevelCalculator {

    private final PackageGraph pg;
    private final Map<String, Integer> levels;

    public NodeDependencyLevelCalculator(PackageGraph pg, Map<String, Integer> levels) {
        this.pg = pg;
        this.levels = levels;
    }

    public Map<String, Double> compute() {
        Map<String, Double> result = new LinkedHashMap<>();
        for (String id : pg.graph().vertexSet()) {
            Set<DefaultEdge> out = pg.graph().outgoingEdgesOf(id);
            if (out.isEmpty()) {
                result.put(id, 1.0);
                Map<String, Object> p = pg.nodeProps().get(id);
                if (p != null) {
                    p.put(PackageGraphBuilder.PROP_NODE_DEPENDENCY_LEVEL, 1.0);
                }
                continue;
            }
            double sum = 0;
            int count = 0;
            for (DefaultEdge e : out) {
                String t = pg.graph().getEdgeTarget(e);
                Integer lvl = levels.get(t);
                if (lvl != null) {
                    sum += lvl;
                    count++;
                }
            }
            double ndl = count == 0 ? 1.0 : (sum / count);
            result.put(id, ndl);
            Map<String, Object> p = pg.nodeProps().get(id);
            if (p != null) {
                p.put(PackageGraphBuilder.PROP_NODE_DEPENDENCY_LEVEL, ndl);
            }
        }
        return result;
    }
}

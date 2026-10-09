package de.tk.dependencyanalyse.packagegraph.graph;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DirectedMultigraph;

/**
 * Container fuer den Package-Graphen.
 *
 * <p>Der eigentliche JGraphT-Graph wird um zwei parallele
 * Attribut-Maps erweitert: Knoten-Properties und Kanten-Properties
 * jeweils als {@link HashMap}. So lassen sich die GML-Attribute
 * 1:1 ablegen und spaeter mit dem Graph mitfuehren.
 */
public final class PackageGraph {

    /** JGraphT-Graph: gerichtet, Mehrfachkanten zulaessig. */
    private final DirectedMultigraph<String, DefaultEdge> graph;

    /** Knoten-Id -> Attribut-Map. */
    private final Map<String, Map<String, Object>> nodeProps;

    /** Kante -> Attribut-Map. */
    private final Map<DefaultEdge, Map<String, Object>> edgeProps;

    public PackageGraph(DirectedMultigraph<String, DefaultEdge> graph,
                        Map<String, Map<String, Object>> nodeProps,
                        Map<DefaultEdge, Map<String, Object>> edgeProps) {
        this.graph = graph;
        this.nodeProps = nodeProps;
        this.edgeProps = edgeProps;
    }

    public DirectedMultigraph<String, DefaultEdge> graph() { return graph; }
    public Map<String, Map<String, Object>> nodeProps() { return nodeProps; }
    public Map<DefaultEdge, Map<String, Object>> edgeProps() { return edgeProps; }

    public List<String> vertexList() {
        return List.copyOf(graph.vertexSet());
    }

    public List<DefaultEdge> edgeList() {
        return List.copyOf(graph.edgeSet());
    }

    public int nodeCount() { return graph.vertexSet().size(); }
    public int edgeCount() { return graph.edgeSet().size(); }
}

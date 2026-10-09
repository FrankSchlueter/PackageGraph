package de.tk.dependencyanalyse.packagegraph.dsm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jgrapht.graph.DefaultEdge;

import de.tk.dependencyanalyse.packagegraph.graph.PackageGraph;
import de.tk.dependencyanalyse.packagegraph.graph.PackageGraphBuilder;

/**
 * DSM-Sortierung (Dependency Structure Matrix).
 *
 * <ol>
 *   <li>Tarjan-SCC &mdash; stark zusammenhaengende Komponenten
 *       (Zyklen) werden als Bloecke zusammengehalten.</li>
 *   <li>Topologische Sortierung des SCC-DAG &mdash; Komponenten, von
 *       denen nichts abhaengt, landen unten (visuell oben in der
 *       Matrix).</li>
 *   <li>Innerhalb-SCC-Sortierung &mdash; Kopplungsgrad (Summe rein +
 *       raus) absteigend.</li>
 * </ol>
 *
 * <p>Die DSM-Reihenfolge wird als Property {@code dsmOrder}
 * (Integer, 0..n-1) auf jedem Knoten abgelegt.
 */
public final class DsmSorter {

    private final PackageGraph pg;

    public DsmSorter(PackageGraph pg) {
        this.pg = pg;
    }

    /** Liefert die sortierte ID-Reihenfolge und schreibt sie ins Modell. */
    public List<String> sort() {
        List<String> ids = new ArrayList<>(pg.graph().vertexSet());
        int n = ids.size();
        Map<String, Integer> index = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) index.put(ids.get(i), i);

        // Adjazenzlisten
        int[][] out = new int[n][];
        for (int i = 0; i < n; i++) {
            List<Integer> nb = new ArrayList<>();
            String v = ids.get(i);
            for (DefaultEdge e : pg.graph().outgoingEdgesOf(v)) {
                String t = pg.graph().getEdgeTarget(e);
                Integer ti = index.get(t);
                if (ti != null) nb.add(ti);
            }
            int[] arr = new int[nb.size()];
            for (int k = 0; k < nb.size(); k++) arr[k] = nb.get(k);
            out[i] = arr;
        }

        // 1) Tarjan SCC
        List<List<Integer>> sccs = tarjan(out, n);

        // 2) Coupling-Score pro Knoten und pro SCC
        double[] nodeScore = new double[n];
        for (DefaultEdge e : pg.graph().edgeSet()) {
            String s = pg.graph().getEdgeSource(e);
            String t = pg.graph().getEdgeTarget(e);
            Integer si = index.get(s);
            Integer ti = index.get(t);
            if (si == null || ti == null) continue;
            nodeScore[si] += 1;
            nodeScore[ti] += 1;
        }
        Map<Integer, Double> coupling = new HashMap<>();
        for (int s = 0; s < sccs.size(); s++) {
            double sum = 0;
            for (int m : sccs.get(s)) sum += nodeScore[m];
            coupling.put(s, sum);
        }

        // 3) Topo-Sortierung des SCC-DAG (Kahn)
        List<Integer> sccOrder = topoSortSccs(sccs, out);

        // 4) IDs expandieren, innerhalb SCC nach Coupling sortieren
        List<String> ordered = new ArrayList<>(n);
        for (int sccId : sccOrder) {
            List<Integer> members = new ArrayList<>(sccs.get(sccId));
            members.sort((a, b) -> {
                double ca = coupling.getOrDefault(a, 0d);
                double cb = coupling.getOrDefault(b, 0d);
                if (ca != cb) return Double.compare(cb, ca);
                return ids.get(a).compareTo(ids.get(b));
            });
            for (int m : members) ordered.add(ids.get(m));
        }

        // DSM-Reihenfolge als Property speichern
        for (int i = 0; i < ordered.size(); i++) {
            String id = ordered.get(i);
            Map<String, Object> p = pg.nodeProps().get(id);
            if (p != null) p.put(PackageGraphBuilder.PROP_DSM_ORDER, i);
        }
        return ordered;
    }

    // ---------------------------------------------------------------------
    // Tarjan SCC
    // ---------------------------------------------------------------------

    private static List<List<Integer>> tarjan(int[][] g, int n) {
        int[] idx = new int[n];
        int[] low = new int[n];
        boolean[] onStack = new boolean[n];
        Deque<Integer> stack = new ArrayDeque<>();
        List<List<Integer>> sccs = new ArrayList<>();
        Arrays.fill(idx, -1);
        int[] counter = { 0 };
        for (int v = 0; v < n; v++) {
            if (idx[v] == -1) tarjanDfs(v, g, idx, low, onStack, stack, counter, sccs);
        }
        return sccs;
    }

    private static void tarjanDfs(int v, int[][] g,
                                  int[] idx, int[] low, boolean[] onStack,
                                  Deque<Integer> stack, int[] counter,
                                  List<List<Integer>> sccs) {
        idx[v] = low[v] = counter[0]++;
        stack.push(v);
        onStack[v] = true;
        for (int w : g[v]) {
            if (idx[w] == -1) {
                tarjanDfs(w, g, idx, low, onStack, stack, counter, sccs);
                low[v] = Math.min(low[v], low[w]);
            } else if (onStack[w]) {
                low[v] = Math.min(low[v], idx[w]);
            }
        }
        if (low[v] == idx[v]) {
            List<Integer> scc = new ArrayList<>();
            while (true) {
                int w = stack.pop();
                onStack[w] = false;
                scc.add(w);
                if (w == v) break;
            }
            sccs.add(scc);
        }
    }

    // ---------------------------------------------------------------------
    // Topo-Sortierung des SCC-DAG (Kahn)
    // ---------------------------------------------------------------------

    private List<Integer> topoSortSccs(List<List<Integer>> sccs, int[][] out) {
        int m = sccs.size();
        int[] indeg = new int[m];
        @SuppressWarnings("unchecked")
        List<Integer>[] dag = new ArrayList[m];
        for (int i = 0; i < m; i++) dag[i] = new ArrayList<>();

        int[] nodeToScc = new int[out.length];
        for (int i = 0; i < m; i++) {
            for (int v : sccs.get(i)) nodeToScc[v] = i;
        }

        // SCC-DAG aus out-Listen, dedupliziert
        Map<Integer, Set<Integer>> dagEdges = new LinkedHashMap<>();
        for (int i = 0; i < m; i++) dagEdges.put(i, new LinkedHashSet<>());
        for (int u = 0; u < out.length; u++) {
            int sccU = nodeToScc[u];
            for (int v : out[u]) {
                int sccV = nodeToScc[v];
                if (sccU != sccV) dagEdges.get(sccU).add(sccV);
            }
        }
        for (int u = 0; u < m; u++) {
            for (int v : dagEdges.get(u)) {
                dag[u].add(v);
                indeg[v]++;
            }
        }

        Deque<Integer> ready = new ArrayDeque<>();
        for (int i = 0; i < m; i++) if (indeg[i] == 0) ready.add(i);

        List<Integer> order = new ArrayList<>(m);
        while (!ready.isEmpty()) {
            int pick = ready.pollFirst();
            order.add(pick);
            for (int v : dag[pick]) {
                if (--indeg[v] == 0) ready.add(v);
            }
        }
        if (order.size() != m) {
            Set<Integer> seen = new HashSet<>(order);
            for (int i = 0; i < m; i++) if (!seen.contains(i)) order.add(i);
        }
        return order;
    }
}

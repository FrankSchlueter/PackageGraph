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
import org.jgrapht.graph.DefaultWeightedEdge;

import de.tk.dependencyanalyse.packagegraph.graph.PackageGraph;
import de.tk.dependencyanalyse.packagegraph.graph.PackageGraphBuilder;

/**
 * Berechnet fuer jeden Knoten ein hierarchisches Level:
 *
 * <ul>
 *   <li>Knoten ohne ausgehende Kanten (keine Abhaengigkeiten) = Level 1</li>
 *   <li>Knoten, die nur von Level-1-Knoten abhaengen = Level 2</li>
 *   <li>...</li>
 * </ul>
 *
 * <p>Formal: {@code level(v) = 1 + max(level(u) | u ist Dep von v)}.
 *
 * <p>Zyklen (SCCs) werden als Block behandelt: alle Mitglieder
 * erhalten denselben Level, der sich aus der Block-Topologie ergibt.
 *
 * <p>Das Ergebnis wird als Integer-Property {@code level}
 * auf jedem Knoten abgelegt.
 */
public final class LevelCalculator {

    private final PackageGraph pg;

    public LevelCalculator(PackageGraph pg) {
        this.pg = pg;
    }

    /** Berechnet die Levels und schreibt sie in die Knoten-Properties. */
    public Map<String, Integer> compute() {
        List<String> ids = new ArrayList<>(pg.graph().vertexSet());
        int n = ids.size();
        Map<String, Integer> index = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) index.put(ids.get(i), i);

        int[][] out = new int[n][];
        int[][] in = new int[n][];
        for (int i = 0; i < n; i++) {
            List<Integer> nbl = new ArrayList<>();
            List<Integer> inl = new ArrayList<>();
            String v = ids.get(i);
            for (DefaultWeightedEdge e : pg.graph().outgoingEdgesOf(v)) {
                Integer ti = index.get(pg.graph().getEdgeTarget(e));
                if (ti != null) nbl.add(ti);
            }
            for (DefaultWeightedEdge e : pg.graph().incomingEdgesOf(v)) {
                Integer si = index.get(pg.graph().getEdgeSource(e));
                if (si != null) inl.add(si);
            }
            out[i] = toIntArray(nbl);
            in[i] = toIntArray(inl);
        }

        // Tarjan SCC
        List<List<Integer>> sccs = tarjan(out, n);

        // SCC-DAG (dedupliziert)
        int m = sccs.size();
        int[] nodeToScc = new int[n];
        for (int i = 0; i < m; i++) {
            for (int v : sccs.get(i)) nodeToScc[v] = i;
        }
        Map<Integer, Set<Integer>> dag = new LinkedHashMap<>();
        Map<Integer, Set<Integer>> dagIn = new LinkedHashMap<>();
        for (int i = 0; i < m; i++) {
            dag.put(i, new LinkedHashSet<>());
            dagIn.put(i, new LinkedHashSet<>());
        }
        for (int u = 0; u < n; u++) {
            int sccU = nodeToScc[u];
            for (int v : out[u]) {
                int sccV = nodeToScc[v];
                if (sccU != sccV) {
                    dag.get(sccU).add(sccV);
                    dagIn.get(sccV).add(sccU);
                }
            }
        }

        // Level per SCC: Knoten ohne ausgehende Kanten im Originalgraph
        // (keine Deps) = Level 1. level(u) = max(level(v) fuer alle v in
        // deps(u)) + 1. D. h. wir propagieren rueckwaerts: hat ein SCC u
        // ein Level, dann koennen SCCs, die auf u zeigen (also von u
        // abhaengen), ihr Level auf level(u) + 1 anheben.
        int[] sccLevel = new int[m];
        Arrays.fill(sccLevel, -1);
        Deque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < m; i++) {
            if (dag.get(i).isEmpty()) {
                sccLevel[i] = 1;
                queue.add(i);
            }
        }
        while (!queue.isEmpty()) {
            int u = queue.pollFirst();
            // 'u' ist auf Level sccLevel[u]. Konsumenten von u
            // (SCCs mit eingehender Kante aus u) werden Level sccLevel[u]+1.
            for (int consumer : dagIn.get(u)) {
                int candidate = sccLevel[u] + 1;
                if (candidate > sccLevel[consumer]) {
                    sccLevel[consumer] = candidate;
                    queue.add(consumer);
                }
            }
        }
        // Verbleibende SCCs ohne ermitteltes Level (z. B. reine Zyklen
        // ohne Topo-Anschluss) bekommen Level 1.
        for (int i = 0; i < m; i++) {
            if (sccLevel[i] == -1) sccLevel[i] = 1;
        }

        // Auf Knoten mappen
        Map<String, Integer> result = new LinkedHashMap<>(n);
        for (int i = 0; i < m; i++) {
            int lvl = sccLevel[i];
            for (int v : sccs.get(i)) {
                String id = ids.get(v);
                result.put(id, lvl);
                Map<String, Object> p = pg.nodeProps().get(id);
                if (p != null) p.put(PackageGraphBuilder.PROP_LEVEL, lvl);
            }
        }
        return result;
    }

    private static int[] toIntArray(List<Integer> list) {
        int[] a = new int[list.size()];
        for (int i = 0; i < list.size(); i++) a[i] = list.get(i);
        return a;
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
}

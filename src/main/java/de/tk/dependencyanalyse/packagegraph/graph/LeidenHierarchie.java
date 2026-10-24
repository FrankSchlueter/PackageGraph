package de.tk.dependencyanalyse.packagegraph.graph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultWeightedEdge;

import nl.cwts.networkanalysis.Clustering;
import nl.cwts.networkanalysis.LeidenAlgorithm;
import nl.cwts.networkanalysis.Network;
import nl.cwts.util.LargeDoubleArray;
import nl.cwts.util.LargeIntArray;

/**
 * Mehrstufige Leiden-Hierarchie auf einem Package-Graphen.
 *
 * <p>Stufe 1 clustert die Packages direkt. Stufe 2 verdichtet das Ergebnis
 * weiter: mit {@link Network#createReducedNetwork(Clustering)} werden die
 * Stufe-1-Cluster zu Knoten aggregiert (Kantengewichte zwischen zwei
 * Clustern werden summiert) und darauf erneut Leiden angewandt.
 *
 * <p>Die Aufloesung des Constant-Potts-Models steuert die Aufloesung:
 * kleinere Werte erzeugen weniger, groessere Communities, hoehere Werte
 * mehr, feinere Cluster.
 */
public final class LeidenHierarchie {

    /** Deterministischer Seed fuer die Reproduzierbarkeit. */
    private static final long DEFAULT_SEED = 42L;

    /** Zufaelligkeit der lokalen Verschiebung (0..1). */
    private static final double DEFAULT_RANDOMNESS = 0.1;

    /** Maximale Anzahl Improvement-Iterationen je Stufe. */
    private static final int DEFAULT_MAX_ITERATIONS = 1000;

    /** Aufloesung der groben Stufe 1 (alignt noch am ehesten mit Fachgrenzen). */
    public static final double RESOLUTION_FINE = 1.0;

    /** Aufloesung der groben Stufe 1. */
    public static final double RESOLUTION_COARSE = 0.1;

    private LeidenHierarchie() {
        // Utility-Klasse.
    }

    /** Eine Cluster-Stufe: Cluster-Id -> Knoten-Ids (JGraphT-Namen). */
    public record Level(int number, double resolution, Map<Integer, List<String>> clusters) {

        public int size() {
            return clusters.size();
        }
    }

    /** Zwei-Ebenen-Hierarchie. */
    public record Hierarchy(Level fine, Level coarse) {
    }

    /**
     * Berechnet eine Zwei-Ebenen-Leiden-Hierarchie.
     *
     * @param graph JGraphT-Graph (gerichtet oder ungerichtet)
     * @return Hierarchie mit feiner und grober Stufe
     */
    public static Hierarchy getHierarchy(Graph<String, DefaultWeightedEdge> graph) {
        return getHierarchy(graph, RESOLUTION_FINE, RESOLUTION_COARSE);
    }

    /**
     * Berechnet eine Zwei-Ebenen-Leiden-Hierarchie mit gegebenen Aufloesungen.
     *
     * @param graph           JGraphT-Graph
     * @param resFine         Aufloesung der feinen Stufe
     * @param resCoarse       Aufloesung der groben Stufe
     */
    public static Hierarchy getHierarchy(Graph<String, DefaultWeightedEdge> graph,
                                         double resFine, double resCoarse) {
        Network network = toNetwork(graph);
        List<String> nodeIds = nodeIds(graph);
        int n = nodeIds.size();

        // --- Stufe 1 ---
        Clustering fine = runLeiden(network, resFine);
        int nFine = fine.getNNonEmptyClusters();
        Map<Integer, List<String>> fineMap = toResultMap(fine, nodeIds, n);

        // --- Stufe 2: Cluster aggregieren, erneut clustern ---
        // createReducedNetwork erwartet ein Clustering mit dichten
        // Cluster-Ids 0..nClusters-1.
        int[] fineIds = fine.getClusters();
        int[] dense = new int[nFine];
        Arrays.fill(dense, -1);
        int next = 0;
        for (int i = 0; i < n; i++) {
            int c1 = fineIds[i];
            if (c1 < 0) continue;
            if (dense[c1] < 0) dense[c1] = next++;
        }
        int[] reducedIds = new int[n];
        Arrays.fill(reducedIds, -1);
        for (int i = 0; i < n; i++)
            if (fineIds[i] >= 0) reducedIds[i] = dense[fineIds[i]];

        Clustering reducedInput = new Clustering(reducedIds);
        Network reduced = network.createReducedNetwork(reducedInput);
        Clustering coarseResult = runLeiden(reduced, resCoarse);
        coarseResult.removeEmptyClusters();

        // Grobe Cluster zurueck auf Package-Namen abbilden.
        Map<Integer, List<String>> coarseMap = new TreeMap<>();
        for (int i = 0; i < n; i++) {
            int c1 = reducedIds[i];
            if (c1 < 0) continue;
            int c2 = coarseResult.getCluster(c1);
            if (c2 < 0) continue;
            coarseMap.computeIfAbsent(c2, k -> new ArrayList<>()).add(nodeIds.get(i));
        }

        return new Hierarchy(
                new Level(1, resFine, fineMap),
                new Level(2, resCoarse, coarseMap));
    }

    // ---------------------------------------------------------------------
    // Netzwerk-Aufbau (identisch zur Semantik von LeidenClusterAnalyse)
    // ---------------------------------------------------------------------

    /**
     * Baut das CWTS-Netzwerk aus dem JGraphT-Graphen und normalisiert es
     * ueber Assoziationsstaerken.
     */
    private static Network toNetwork(Graph<String, DefaultWeightedEdge> graph) {
        List<String> nodeIds = nodeIds(graph);
        int n = nodeIds.size();
        Map<String, Integer> index = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) index.put(nodeIds.get(i), i);

        // Gerichtete Kanten auf ungerichtete Paare abbilden, bidirektionale
        // zusammenfassen (Summe der Gewichte).
        int m = graph.edgeSet().size();
        long[] keys = new long[m];
        double[] wts = new double[m];
        int cnt = 0;
        for (DefaultWeightedEdge e : graph.edgeSet()) {
            Integer si = index.get(graph.getEdgeSource(e));
            Integer ti = index.get(graph.getEdgeTarget(e));
            if (si == null || ti == null) continue;
            int a = si.intValue();
            int b = ti.intValue();
            if (a == b) continue;
            keys[cnt] = ((long) Math.min(a, b) << 32) | (Math.max(a, b) & 0xFFFFFFFFL);
            wts[cnt] = graph.getEdgeWeight(e);
            cnt++;
        }
        Integer[] ord = new Integer[cnt];
        for (int i = 0; i < cnt; i++) ord[i] = i;
        Arrays.sort(ord, (x, y) -> Long.compare(keys[x], keys[y]));

        double[] nodeWeights = new double[n];
        LargeIntArray[] edges = new LargeIntArray[2];
        edges[0] = new LargeIntArray(0);
        edges[1] = new LargeIntArray(0);
        LargeDoubleArray edgeWeights = new LargeDoubleArray(0);
        for (int i = 0; i < cnt; i++) {
            long key = keys[ord[i]];
            int u = (int) (key >>> 32);
            int v = (int) (key & 0xFFFFFFFFL);
            double s = wts[ord[i]];
            while (i + 1 < cnt && keys[ord[i + 1]] == key) s += wts[ord[++i]];
            edges[0].append(u);
            edges[1].append(v);
            edgeWeights.append(s);
            nodeWeights[u] += s;
            nodeWeights[v] += s;
        }
        return new Network(nodeWeights, edges, edgeWeights, false, true)
                .createNormalizedNetworkUsingAssociationStrength();
    }

    private static List<String> nodeIds(Graph<String, DefaultWeightedEdge> graph) {
        return new ArrayList<>(graph.vertexSet());
    }

    private static Clustering runLeiden(Network network, double resolution) {
        // ACHTUNG: (resolution, nIterations, randomness, random)
        LeidenAlgorithm alg = new LeidenAlgorithm(
                resolution, DEFAULT_MAX_ITERATIONS, DEFAULT_RANDOMNESS, new Random(DEFAULT_SEED));
        Clustering c = alg.findClustering(network);
        c.removeEmptyClusters();
        return c;
    }

    private static Map<Integer, List<String>> toResultMap(Clustering c, List<String> nodeIds, int n) {
        Map<Integer, List<String>> result = new TreeMap<>();
        for (int i = 0; i < n; i++) {
            int cl = c.getCluster(i);
            if (cl < 0) continue;
            result.computeIfAbsent(cl, k -> new ArrayList<>()).add(nodeIds.get(i));
        }
        return result;
    }
}
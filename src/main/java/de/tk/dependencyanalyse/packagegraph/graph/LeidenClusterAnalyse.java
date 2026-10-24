package de.tk.dependencyanalyse.packagegraph.graph;

import java.util.ArrayList;
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
 * Berechnet Leiden-Communities auf einem JGraphT-Graphen mit der
 * CWTS-{"@"}code networkanalysis{"@"}-Bibliothek.
 *
 * <p>Die CWTS-Bibliothek arbeitet auf ungerichteten Graphen, der
 * uebergebene JGraphT-Graph ist typischerweise gerichtet. Jede
 * gerichtete Kante wird daher in beide Richtungen in das
 * CWTS-{"@"}code Network{"@"}-Format uebertragen, so dass die
 * Clustering-Ergebnisse ungerichteten Communities entsprechen.
 *
 * <p>Ergebnis ist eine Map{"@"}<ClusterId, List<Knoten-Id>>{"@"}.
 * Isolierte Knoten landen jeweils in einem eigenen Cluster
 * (Singleton-Cluster, durch die Bibliothek vergeben).
 */
public final class LeidenClusterAnalyse {

    /** Deterministischer Seed fuer die Reproduzierbarkeit. */
    private static final long DEFAULT_SEED = 42L;

    /**
     * Aufloesung des Constant-Potts-Models.
     *
     * <p>Hoehere Werte erzeugen mehr und kleinere Cluster. Der Wert wurde
     * empirisch so gewaehlt, dass der Package-Graph in Community-Groessen
     * zerfaellt, die mit der JavaScript-Visualisierung vergleichbar sind,
     * statt in einen einzigen Riesenchunk.
     */
    private static final double RESOLUTION = 1.0;

    /** Zufaelligkeit der lokalen Verschiebung (0..1). */
    private static final double RANDOMNESS = 0.1;

    /** Maximale Anzahl Improvement-Iterationen. */
    private static final int MAX_ITERATIONS = 1000;

    private LeidenClusterAnalyse() {
        // Utility-Klasse, keine Instanzen.
    }

    /**
     * Berechnet die Leiden-Communities fuer den uebergebenen Graphen.
     *
     * <p>Verwendet werden die Library-Defaults fuer Randomness,
     * Aufloesung (resolution) und Anzahl Iterationen sowie ein
     * deterministischer Seed, damit die Ergebnisse reproduzierbar sind.
     *
     * @param graph JGraphT-Graph mit String-Knoten und
     *              {"@"}code DefaultWeightedEdge{"@"}-Kanten. Darf
     *              leer sein, dann wird eine leere Map zurueckgegeben.
     * @return Map von Cluster-Id (Integer) auf die Liste der in
     *         diesem Cluster enthaltenen Knoten-Ids (Strings). Die
     *         Cluster-Ids sind durch die Bibliothek vergeben und
     *         ganzzahlig >= 0. Die Reihenfolge innerhalb einer Liste
     *         entspricht der Iteration ueber {"@"}code vertexSet(){"@"}.
     */
    public static Map<Integer, List<String>> getLeidenCommunities(
            Graph<String, DefaultWeightedEdge> graph) {

        if (graph == null || graph.vertexSet().isEmpty()) {
            return new TreeMap<>();
        }

        // 1. Knoten in stabile Reihenfolge bringen und Index-Map bauen.
        List<String> nodeIds = new ArrayList<>(graph.vertexSet());
        int n = nodeIds.size();
        Map<String, Integer> index = new LinkedHashMap<>(n);
        for (int i = 0; i < n; i++) {
            index.put(nodeIds.get(i), i);
        }

        // 2. Gerichteten Graphen in einen ungerichteten ueberfuehren.
        //
        // Die CWTS-Bibliothek unterstuetzt ausschliesslich ungerichtete
        // Netzwerke ("Directed edges are not supported"). Wir bilden daher
        // jede gerichtete Kante {u,v} auf das ungerichtete Paar
        // (min(u,v), max(u,v)) ab.
        //
        // Bidirektionale Beziehungen: Existieren sowohl (u,v) als auch
        // (v,u), MUESsen diese zu EINER ungerichteten Kante zusammen-
        // gefasst werden. Andernfalls enthaelt die Nachbarliste von u den
        // Knoten v doppelt, was die interne Invariante der Bibliothek
        // verletzt (Network.checkIntegrity: "must not include duplicate
        // values") und das Clustering auf ungueltiger Eingabe rechnen
        // laesst. Das Gewicht beider Richtungen wird summiert, sodass die
        // Gesamtabhaengigkeitsstaerke zwischen den beiden Packages
        // erhalten bleibt.
        int m = graph.edgeSet().size();
        long[] keys = new long[m];
        double[] weightOf = new double[m];
        int cnt = 0;
        for (DefaultWeightedEdge e : graph.edgeSet()) {
            Integer si = index.get(graph.getEdgeSource(e));
            Integer ti = index.get(graph.getEdgeTarget(e));
            if (si == null || ti == null) continue;   // sollte nicht passieren
            int a = si.intValue();
            int b = ti.intValue();
            if (a == b) continue;                     // Self-Edges ignorieren
            keys[cnt] = ((long) Math.min(a, b) << 32) | (Math.max(a, b) & 0xFFFFFFFFL);
            weightOf[cnt] = graph.getEdgeWeight(e);
            cnt++;
        }

        // Nach ungerichtetem Paar sortieren und gleichartige zusammenfassen.
        Integer[] order = new Integer[cnt];
        for (int i = 0; i < cnt; i++) order[i] = i;
        java.util.Arrays.sort(order, (x, y) -> Long.compare(keys[x], keys[y]));

        // WICHTIG: Der Network-Konstruktor erwartet ein ZWEIELEMENTIGES
        // Array [sources, targets] mit jeder ungerichteten Kante genau EINMAL.
        // Bei sortedEdges=false ergaenzt die Bibliothek die Gegenrichtung
        // selbst. Ein Array pro Knoten wuerde stillschweigend nur die erste
        // Nachbarschaftsliste als Kantenliste gelesen.
        double[] nodeWeights = new double[n];
        LargeIntArray[] edges = new LargeIntArray[2];
        edges[0] = new LargeIntArray(0);
        edges[1] = new LargeIntArray(0);
        LargeDoubleArray edgeWeights = new LargeDoubleArray(0);

        for (int i = 0; i < cnt; i++) {
            long key = keys[order[i]];
            int u = (int) (key >>> 32);
            int v = (int) (key & 0xFFFFFFFFL);
            double sum = weightOf[order[i]];
            // Gleiches Paar? Gewicht addieren statt zweite Kante anlegen.
            while (i + 1 < cnt && keys[order[i + 1]] == key) {
                sum += weightOf[order[++i]];
            }
            edges[0].append(u);
            edges[1].append(v);
            edgeWeights.append(sum);
            // Knotenstaerke s_i: Summe der Kantengewichte am Knoten.
            // Voraussetzung fuer die Association-Strength-Normalisierung.
            nodeWeights[u] += sum;
            nodeWeights[v] += sum;
        }

        // 3. CWTS-Network bauen und ueber Assoziationsstaerke normalisieren.
        // Die Normalisierung teilt jede Kante durch (s_i * s_j)/2m, sodass
        // eine Kante relativ zur Erwartung im Zufallskonfigurationsmodell
        // bewertet wird. Sie greift nur, wenn die Knotengewichte tatsaechlich
        // die Knotenstaerken s_i sind.
        Network network = new Network(
                nodeWeights, edges, edgeWeights,
                /*sortedEdges=*/false, /*checkIntegrity=*/true)
                .createNormalizedNetworkUsingAssociationStrength();

        // 4. Leiden-Algorithmus ausfuehren.
        // ACHTUNG Parameterreihenfolge der Bibliothek:
        //     LeidenAlgorithm(resolution, nIterations, randomness, random)
        // Hoehere resolution erzeugt MEHR (kleinere) Cluster,
        // hoehere randomness erzeugt groessere Communities.
        LeidenAlgorithm algorithm = new LeidenAlgorithm(
                RESOLUTION,
                MAX_ITERATIONS,
                RANDOMNESS,
                new Random(DEFAULT_SEED));

        Clustering clustering = algorithm.findClustering(network);
        clustering.removeEmptyClusters();

        // 5. Ergebnis-Map befuellen.
        return toResultMap(clustering, nodeIds);
    }

    /**
     * Wandelt ein CWTS-{"@"}code Clustering{"@"}-Objekt in eine Map
     * Cluster-Id -&gt; Liste der enthaltenen Knoten-Ids.
     */
    private static Map<Integer, List<String>> toResultMap(
            Clustering clustering, List<String> nodeIds) {

        Map<Integer, List<String>> result = new TreeMap<>();
        int n = nodeIds.size();
        for (int i = 0; i < n; i++) {
            int c = clustering.getCluster(i);
            if (c < 0) continue;
            result.computeIfAbsent(c, k -> new ArrayList<>()).add(nodeIds.get(i));
        }
        return result;
    }
}
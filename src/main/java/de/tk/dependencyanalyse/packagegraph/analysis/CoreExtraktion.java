package de.tk.dependencyanalyse.packagegraph.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultWeightedEdge;

import de.tk.dependencyanalyse.packagegraph.graph.PackageGraph;
import de.tk.dependencyanalyse.packagegraph.graph.PackageGraphBuilder;

/**
 * Extrahiert einen gemeinsamen Core-Layer aus dem Package-Graphen.
 *
 * <p><b>Ausgangslage.</b> Der Bundle-Graph dieser Anwendung ist ein echter
 * DAG (438 Bundles, keine Zyklen). Die fachliche Ordnung ist also tragfaehig.
 * Die bunduebergreifende Kopplung wird jedoch von wenigen Packages getragen:
 * rund 2300 Packages werden aus zehn oder mehr Bundles heraus benutzt, sechs
 * Bundles haben Fan-in ueber 300. Der Core existiert also strukturell bereits
 * - er ist nur ueber die Bundles verteilt statt als eigener Layer angelegt.
 *
 * <p><b>Verfahren.</b> Greedy-Set-Cover ueber bunduebergreifende Kanten:
 * Wiederholt wird das Package gewaehlt, das die meisten noch ungedeckten
 * Kanten schliesst, bis eine Zielabdeckung erreicht ist. Ein Package kommt
 * nur in den Core, wenn es bunduebergreifend stark genutzt wird <em>und</em>
 * sein eigenes Bundle nicht aufreisst - genau die Bedingung "hoeherwertige
 * Funktionalitaet herausziehen, wenn sie vom Rest des Bundles nicht
 * wesentlich genutzt wird".
 *
 * <p><b>Charakter des Core.</b> Core-Packages sind Infrastruktur: sie werden
 * breit benutzt, treten aber selbst kaum als Quelle in Abhaengigkeiten auf.
 * Der Core bleibt dadurch klein und liegt an der Basis der Schichtung.
 */
public final class CoreExtraktion {

    /** Minimale Anzahl Bundles, ab der ein Package Core-Kandidat ist. */
    public static final int MIN_BUNDLES = 10;

    /** Zielabdeckung der konservativen Core-Variante. */
    public static final double COVERAGE_SIMPLE = 0.80;

    /** Zielabdeckung der aggressiven Core-Variante. */
    public static final double COVERAGE_TIGHT = 0.95;

    /**
     * Reisserverbot: Ein Package wandert nur in den Core, wenn sein eigener
     * Bundle es nicht wesentlich mitnutzt. Massgeblich ist
     * {@code intern <= extern / INTERN_FAKTOR}.
     */
    public static final int INTERN_FAKTOR = 4;

    private final PackageGraph pg;

    public CoreExtraktion(PackageGraph pg) {
        this.pg = pg;
    }

    /** Ergebnis der Core-Extraktion. */
    public record Core(Set<String> packages,
                       double abdeckung,
                       int nochOffeneKopplung,
                       int kandidatenGesamt,
                       int abgelehntWegenInternerNutzung) {

        public int size() {
            return packages.size();
        }
    }

    /** Beide Core-Varianten im Vergleich. */
    public record Varianten(Core einfach, Core streng) {
    }

    /** Berechnet beide Core-Varianten (80 % und 95 % Abdeckung). */
    public Varianten beideVarianten() {
        return new Varianten(extrahiere(COVERAGE_SIMPLE), extrahiere(COVERAGE_TIGHT));
    }

    /**
     * Berechnet den Core bis zur gewuenschten Abdeckung der
     * bunduebergreifenden Kopplung.
     *
     * @param zielabdeckung Anteil der zu deckenden bunduebergreifenden Kanten
     */
    public Core extrahiere(double zielabdeckung) {
        Graph<String, DefaultWeightedEdge> g = pg.graph();
        Map<String, String> bundleOf = bundleIndex();

        // --- Kanteninventar ---
        // Nur Kanten zwischen verschiedenen Bundles zaehlen: Kanten innerhalb
        // eines Bundles sind kein Core-Problem, sondern internes Bundle-Detail.
        List<String[]> kanten = new ArrayList<>();
        Map<String, Integer> internGenutzt = new HashMap<>();
        Map<String, Set<String>> aufrufendeBundles = new HashMap<>();

        for (DefaultWeightedEdge e : g.edgeSet()) {
            String s = g.getEdgeSource(e);
            String t = g.getEdgeTarget(e);
            if (s.equals(t)) continue;
            String bs = bundleOf.get(s);
            String bt = bundleOf.get(t);
            if (bs == null || bt == null) continue;
            if (bs.equals(bt)) {
                internGenutzt.merge(s, 1, Integer::sum);
                internGenutzt.merge(t, 1, Integer::sum);
                continue;
            }
            kanten.add(new String[]{s, t});
            // Als Ziel heisst "wird benutzt"; als Quelle heisst "benutzt selbst".
            aufrufendeBundles.computeIfAbsent(t, x -> new HashSet<>()).add(bs);
        }

        int gesamt = kanten.size();
        if (gesamt == 0) {
            return new Core(new LinkedHashSet<>(), 0, 0, 0, 0);
        }

        // --- Kandidaten: von mindestens MIN_BUNDLES Bundles benutzt ---
        List<String> kandidaten = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : aufrufendeBundles.entrySet()) {
            if (e.getValue().size() >= MIN_BUNDLES) {
                kandidaten.add(e.getKey());
            }
        }

        // --- Reisserverbot ---
        int abgelehnt = 0;
        List<String> zugelassen = new ArrayList<>(kandidaten.size());
        for (String p : kandidaten) {
            int intern = internGenutzt.getOrDefault(p, 0);
            // Externe Nutzung = Kanten, in denen das Package als Ziel auftritt.
            int extern = aufrufendeBundles.getOrDefault(p, Collections.emptySet()).size();
            if (intern * INTERN_FAKTOR > Math.max(1, extern)) {
                abgelehnt++;
            } else {
                zugelassen.add(p);
            }
        }

        // --- Greedy-Set-Cover ueber bunduebergreifende Kanten ---
        Set<Integer> ungedeckt = new java.util.HashSet<>(gesamt * 2);
        for (int i = 0; i < gesamt; i++) ungedeckt.add(i);
        Map<String, Integer> pos = new HashMap<>(zugelassen.size() * 2);
        for (int i = 0; i < zugelassen.size(); i++) {
            pos.put(zugelassen.get(i), i);
        }

        List<List<Integer>> kantenMitKandidaten = new ArrayList<>(gesamt);
        List<Set<Integer>> kandidatZuKanten = new ArrayList<>(zugelassen.size());
        for (int i = 0; i < zugelassen.size(); i++) {
            kandidatZuKanten.add(new HashSet<>());
        }
        for (int k = 0; k < kanten.size(); k++) {
            List<Integer> beteiligt = new ArrayList<>(2);
            Integer si = pos.get(kanten.get(k)[0]);
            Integer ti = pos.get(kanten.get(k)[1]);
            if (si != null) beteiligt.add(si);
            if (ti != null) beteiligt.add(ti);
            kantenMitKandidaten.add(beteiligt);
            for (int c : beteiligt) {
                kandidatZuKanten.get(c).add(k);
            }
        }

        // Delta-Buchfuehrung: Restwert je Kandidat. Damit laeuft die
        // Greedy-Schleife linear ueber die Kandidaten, statt pro Schritt
        // ueber alle Kanten zu iterieren.
        int[] rest = new int[zugelassen.size()];
        for (int i = 0; i < zugelassen.size(); i++) {
            rest[i] = kandidatZuKanten.get(i).size();
        }
        boolean[] imCore = new boolean[zugelassen.size()];
        List<Integer> reihenfolge = new ArrayList<>(zugelassen.size());
        for (int i = 0; i < zugelassen.size(); i++) reihenfolge.add(i);
        reihenfolge.sort(Comparator.comparingInt((Integer i) -> -rest[i]));

        Set<String> core = new LinkedHashSet<>();
        int ziel = (int) Math.ceil(zielabdeckung * gesamt);
        int abgedeckt = 0;

        for (int r = 0; r < reihenfolge.size() && abgedeckt < ziel; r++) {
            int best = reihenfolge.get(r);
            if (imCore[best] || rest[best] == 0) continue;
            imCore[best] = true;
            core.add(zugelassen.get(best));
            for (int k : kandidatZuKanten.get(best)) {
                if (ungedeckt.remove(k)) {
                    abgedeckt++;
                    for (int c : kantenMitKandidaten.get(k)) {
                        if (!imCore[c]) rest[c]--;
                    }
                }
            }
        }

        double abdeckung = (double) abgedeckt / gesamt;
        return new Core(core, abdeckung, gesamt - abgedeckt, kandidaten.size(), abgelehnt);
    }

    /** Liefert die Bundle-Zuordnung aller Knoten. */
    private Map<String, String> bundleIndex() {
        Map<String, String> map = new HashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : pg.nodeProps().entrySet()) {
            Object b = e.getValue().get(PackageGraphBuilder.PROP_BUNDLE);
            map.put(e.getKey(), b == null ? "(unbekannt)" : b.toString());
        }
        return map;
    }
}
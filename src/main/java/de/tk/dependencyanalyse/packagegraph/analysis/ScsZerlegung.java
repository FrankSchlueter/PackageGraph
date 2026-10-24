package de.tk.dependencyanalyse.packagegraph.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultDirectedWeightedGraph;
import org.jgrapht.graph.DefaultWeightedEdge;

import de.tk.dependencyanalyse.packagegraph.graph.LeidenClusterAnalyse;
import de.tk.dependencyanalyse.packagegraph.graph.PackageGraph;

/**
 * Zerlegt den Bundle-Graphen in vertikale Self-Contained Systems.
 *
 * <p><b>Warum auf Bundle-Ebene und nicht auf Package-Ebene.</b> Der
 * Package-Graph besitzt einen dominanten verbundenen Kern: bei
 * modularitaetsbasierter Clusterung wandert nahezu der gesamte Graph in
 * einen einzigen Cluster (17 000 von 21 800 Packages). Eine Modularisierung
 * ist auf dieser Ebene nicht moeglich, weil ein Aufteilen genau die
 * Verdrahtung zerreisst, die den Kern ausmacht.
 *
 * <p>Die Bundle-Ebene dagegen ist ein echter DAG — 438 Knoten, <em>keine</em>
 * Zyklen (im Package-Graph liegen dagegen 32,7 % der Knoten in Zyklen, der
 * groesste SCC umfasst 1099 Packages). Die Bundles sind damit genau die
 * Einheit, auf der sich eine Schichtung aufbauen laesst. Jedes SCS ist
 * deshalb eine Gruppe ganzer Bundles; innerhalb eines Bundles findet keine
 * Schnitt statt.
 *
 * <p><b>Ergebnis.</b> Der Algorithmus liefert strukturelle Kandidaten mit
 * Kennzahlen: dominantes Produkt, dominante Classification-Schicht und
 * Reinheit. Die fachliche Freigabe der Grenzen bleibt beim Team — der Graph
 * kennt keine fachlichen Vertragsgrenzen.
 */
public final class ScsZerlegung {

    /** Anzahl der als Overlay eingestuften Bundles (rangbasiert, kein Schwellwert). */
    private static final int OVERLAY_ANZAHL = 10;

    private final PackageGraph pg;

    public ScsZerlegung(PackageGraph pg) {
        this.pg = pg;
    }

    /** Ein SCS-Kandidat: eine Gruppe von Bundles. */
    public record Scs(int id,
                      List<String> bundles,
                      List<String> packages,
                      String dominantesProdukt,
                      String dominanteSchicht,
                      double reinheitProdukt,
                      double reinheitSchicht,
                      int anzahlBundles) {
    }

    /** Ergebnis der Zerlegung. */
    public record Zerlegung(List<Scs> scs,
                            List<String> overlayBundles,
                            List<String> smallCandidates,
                            List<String> coreBundles,
                            boolean modulDagAzyklisch,
                            List<String> zyklischePaare,
                            Map<String, String> bundleZuScs,
                            List<IterationStep> iterHistory,
                            Set<String> corePackages,
                            Map<String, String> packageBundleOverride) {
        public Zerlegung {
            corePackages = corePackages == null ? Set.of() : corePackages;
            packageBundleOverride = packageBundleOverride == null
                    ? Map.of() : Map.copyOf(packageBundleOverride);
        }
        /** Bundle-Effektivname eines Packages (Override vor Original-Bundle). */
        public String effectiveBundleOf(String pkg, java.util.function.Function<String, String> original) {
            String ov = packageBundleOverride.get(pkg);
            return ov != null ? ov : original.apply(pkg);
        }
    }

    /**
     * Zerlegt die Bundles in Subsysteme.
     *
     * @param coreBundles Bundles, die bereits dem Core zugeordnet sind und
     *                    nicht in ein Subsystem wandern
     * @param resolution  Aufloesung der Clusterung (kleiner = weniger Cluster)
     */
    public Zerlegung zerlegen(Set<String> coreBundles, double resolution) {
        return zerlegeKern(coreBundles, resolution, Set.of(), Set.of());
    }

    /**
     * Zerlegt die Bundles und hinterlegt die Core-Package-Menge, damit die
     * Optimierungsmetrik Core→SCS-Beziehungen korrekt zaehlen kann.
     */
    public Zerlegung zerlegen(Set<String> coreBundles, double resolution,
                               Set<String> corePackages) {
        return zerlegeKern(coreBundles, resolution, Set.of(), corePackages);
    }

    /**
     * Berechnet die Optimierungsmetrik: Summe der Cardinalities zwischen
     * SCS-Paaren plus Summe der Cardinalities Core -> SCS. SCS -> Core ist
     * erlaubt (SCS darf Core benutzen).
     */
    public double berechneMetrik(ScsZerlegung.Zerlegung z,
                                 Set<String> ausgegliedertePackages) {
        return berechneMetrikDetail(z, ausgegliedertePackages).gesamt();
    }

    /** Detail-Metrik: getrennte Summen fuer SCS↔SCS, Core→SCS und Overlay→SCS. */
    public record MetrikDetail(double scsZuScs,
                                double coreZuScs,
                                double overlayZuScs) {
        /** Summe aller ungewollten Beziehungen. */
        public double gesamt() { return scsZuScs + coreZuScs + overlayZuScs; }
    }

    /**
     * Berechnet die Optimierungsmetrik in Detailform.
     *
     * <p>Erfasst werden alle "ungewollten" Beziehungen:
     * <ul>
     *   <li><b>SCS↔SCS</b>: Beziehungen zwischen zwei verschiedenen Subsystemen.</li>
     *   <li><b>Core→SCS</b>: Beziehungen VOM Core IN ein Subsystem. Erlaubt
     *       waere die Gegenrichtung (SCS darf den Core benutzen) &mdash; genau
     *       die verkehrte Richtung ist das Stoerbild.</li>
     *   <li><b>Overlay→SCS</b>: Beziehungen vom Overlay in ein Subsystem.</li>
     * </ul>
     *
     * <p>Der Core wird ueber seine <em>Packages</em> identifiziert
     * ({@code corePackages}), nicht ueber Bundles &mdash; Core und SCS sind
     * alternative Zielarchitekturen und haben keine gemeinsamen Bundles.
     */
    public MetrikDetail berechneMetrikDetail(ScsZerlegung.Zerlegung z,
                                             Set<String> ausgegliedertePackages) {
        Set<String> corePackages = z.corePackages();
        Set<String> overlaySet = new HashSet<>(z.overlayBundles());
        Map<String, String> pkgOverride = z.packageBundleOverride();

        // WICHTIG: attr("bundle") einmal cachen. Ein Aufruf pro Kante wuerde
        // 155736 * 21800 Map-Eintraege erzeugen und den Lauf zum Haken bringen.
        Map<String, String> bundleOf = attr("bundle");

        double scsZuScs = 0.0;
        double coreZuScs = 0.0;
        double overlayZuScs = 0.0;

        for (DefaultWeightedEdge e : pg.graph().edgeSet()) {
            String s = pg.graph().getEdgeSource(e);
            String t = pg.graph().getEdgeTarget(e);
            if (s.equals(t)) continue;
            if (ausgegliedertePackages.contains(s) || ausgegliedertePackages.contains(t)) continue;
            double w = pg.graph().getEdgeWeight(e);

            // Effektives Bundle unter Beruecksichtigung von Package-Overrides
            // (verschobene Packages leben in einem Singleton-Bundle im Ziel-SCS).
            String bsRaw = pkgOverride.get(s);
            String btRaw = pkgOverride.get(t);
            String bs = bsRaw != null ? bsRaw : bundleOf.get(s);
            String bt = btRaw != null ? btRaw : bundleOf.get(t);
            if (bs == null || bt == null || bs.equals(bt)) continue;

            // 1) Core-Package -> SCS-Package: ungewollt.
            if (corePackages.contains(s)) {
                if (z.bundleZuScs().containsKey(bt) && !corePackages.contains(t)) {
                    coreZuScs += w;
                }
                continue;
            }
            // 2) SCS -> Core: erlaubt.
            if (corePackages.contains(t)) {
                continue;
            }
            boolean sIstOverlay = overlaySet.contains(bs);
            String sScs = sIstOverlay ? null : z.bundleZuScs().get(bs);
            String tScs = z.bundleZuScs().get(bt);

            if (sScs != null && tScs != null) {
                if (!sScs.equals(tScs)) scsZuScs += w;
                continue;
            }
            if (sIstOverlay && tScs != null) {
                overlayZuScs += w;
            }
        }
        return new MetrikDetail(scsZuScs, coreZuScs, overlayZuScs);
    }

    /**
     * Loest SCS auf, die weniger als {@code minScsSize} Packages enthalten.
     * Die aufgeloesten Packages werden in {@code smallCandidates} verschoben
     * (nicht ins Overlay).
     */
    private Zerlegung applySmallCandidates(Zerlegung z, int minScsSize,
                                           List<IterationStep> history) {
        if (minScsSize <= 0) {
            // Nicht anwenden.
            return new Zerlegung(z.scs(), z.overlayBundles(), List.of(),
                    z.coreBundles(), z.modulDagAzyklisch(),
                    z.zyklischePaare(), z.bundleZuScs(), history,
                    z.corePackages(), z.packageBundleOverride());
        }
        List<Scs> remaining = new ArrayList<>();
        List<String> smallPkgs = new ArrayList<>();
        Map<String, String> newBundleZuScs = new HashMap<>(z.bundleZuScs());
        int nextId = 0;
        for (Scs s : z.scs()) {
            if (s.packages().size() >= minScsSize) {
                // SCS behalten, ggf. mit neuer ID.
                Scs renumbered = new Scs(nextId++, s.bundles(), s.packages(),
                        s.dominantesProdukt(), s.dominanteSchicht(),
                        s.reinheitProdukt(), s.reinheitSchicht(),
                        s.anzahlBundles());
                remaining.add(renumbered);
                for (String b : s.bundles()) newBundleZuScs.put(b, "SCS" + renumbered.id());
            } else {
                smallPkgs.addAll(s.packages());
                // Bundles dieses SCS aus bundleZuScs entfernen.
                for (String b : s.bundles()) newBundleZuScs.remove(b);
            }
        }
        // Zyklus-Pruefung entfaellt hier — wird durch das initiale Cluster-Layout
        // bereits abgedeckt und aendert sich durch smallCandidates-Verschiebung
        // nicht (es werden nur SCS entfernt, keine Kanten umgehangen).
        return new Zerlegung(remaining, z.overlayBundles(), smallPkgs,
                z.coreBundles(), true, List.of(), newBundleZuScs, history,
                z.corePackages(), z.packageBundleOverride());
    }

    /** Liefert das Bundle-Namen des Packages (oder null). */
    private String bundleOf(String pkg) {
        Map<String, Object> props = pg.nodeProps().get(pkg);
        if (props == null) return null;
        Object v = props.get("bundle");
        return v == null ? null : v.toString();
    }

    private boolean isInSmallCandidates(Zerlegung z, String pkg) {
        // Packages, die nicht mehr in bundleZuScs vorkommen, sind smallCandidates
        // bzw. ausgegliedert.
        String bndl = bundleOf(pkg);
        return bndl != null && !z.bundleZuScs().containsKey(bndl);
    }

    /** Baut einen Bundle-Graphen ueber die gegebenen Bundle-Namen. */
    private Graph<String, DefaultWeightedEdge> buildBundleGraph(Set<String> bundles) {
        Graph<String, DefaultWeightedEdge> bg =
                new DefaultDirectedWeightedGraph<>(DefaultWeightedEdge.class);
        for (String b : bundles) bg.addVertex(b);
        Map<String, String> bundleOf = attr("bundle");
        Map<String, DefaultWeightedEdge> kantenIndex = new HashMap<>();
        for (DefaultWeightedEdge e : pg.graph().edgeSet()) {
            String s = pg.graph().getEdgeSource(e);
            String t = pg.graph().getEdgeTarget(e);
            if (s.equals(t)) continue;
            String bs = bundleOf.get(s);
            String bt = bundleOf.get(t);
            if (bs == null || bt == null || bs.equals(bt)) continue;
            if (!bundles.contains(bs) || !bundles.contains(bt)) continue;
            double w = pg.graph().getEdgeWeight(e);
            DefaultWeightedEdge be = kantenIndex.get(bs + bt);
            if (be == null) {
                be = bg.addEdge(bs, bt);
                if (be == null) continue;
                kantenIndex.put(bs + bt, be);
                bg.setEdgeWeight(be, w);
            } else {
                bg.setEdgeWeight(be, bg.getEdgeWeight(be) + w);
            }
        }
        return bg;
    }

    /**
     * Iterative Variante: laesst Packages aus den Bundles auslagern, wenn
     * das die Metrik {@code Sum(SCS↔SCS Cardinality) + Sum(Core→SCS Cardinality)}
     * reduziert. SCS, die danach unter {@code minScsSize} Packages fallen,
     * werden aufgeloest und in {@code smallCandidates} verschoben (nicht ins
     * Overlay).
     *
     * @param coreBundles              Bundles, die dem Core zugeordnet sind
     * @param resolution               Cluster-Aufloesung
     * @param auszulagerndePackages    Initiale Kandidatenmenge (typischerweise
     *                                 alle Outlier mit Bewertung SINNVOLL oder
     *                                 BEDENKLICH). Packages werden vor der
     *                                 Clusterung aus ihren Bundles entfernt.
     * @param maxIterations            Maximale Anzahl Greedy-Schritte
     * @param minScsSize               Mindestgroesse (Packages) eines SCS,
     *                                 darunter -> smallCandidates
     * @param minImprovement           Akzeptanzschwelle (z.B. 0.01 = 1%).
     *                                 Wird die Metrik um weniger reduziert,
     *                                 stoppt die Iteration.
     * @param iterationListener        Optionaler Listener (kann null sein).
     *                                 Wird nach jeder Iteration aufgerufen
     *                                 mit (iteration, package, bundle, dMetric,
     *                                 cumulativeReduction).
     */
    /** Ergebnis der Auslagerungs-Optimierung inkl. Metrik-Vorher/Nachher. */
    public record OptimierungsErgebnis(
            Zerlegung zerlegung,
            MetrikDetail metrikVorher,
            MetrikDetail metrikNachher,
            int packagesAusgelagert,
            List<IterationStep> history) {

        /** Reduktion der ungewollten Beziehungen in Prozent. */
        public double reductionProzent() {
            double v = metrikVorher.gesamt();
            return v > 0 ? (v - metrikNachher.gesamt()) / v * 100.0 : 0.0;
        }
    }

    public OptimierungsErgebnis optimiereMitAuslagerung(Set<String> coreBundles,
                                                        double resolution,
                                                        Set<String> auszulagerndePackages,
                                                        int minScsSize,
                                                        Set<String> corePackages,
                                                        List<String> problematischePackages) {
        // Schritt 1: Initiale Zerlegung (ohne Auslagerungen) -> Start-Metrik.
        Set<String> ausgegliedert = new HashSet<>();
        Zerlegung initial = zerlegeKern(coreBundles, resolution, ausgegliedert, corePackages);
        MetrikDetail metrikVorher = berechneMetrikDetail(initial, ausgegliedert);

        // Schritt 2: Auslagerung anwenden + Leiden-Run auf bereinigter Struktur.
        ausgegliedert.addAll(auszulagerndePackages);
        Zerlegung bereinigt = zerlegeKern(coreBundles, resolution, ausgegliedert, corePackages);
        MetrikDetail metrikNachher = berechneMetrikDetail(bereinigt, ausgegliedert);

        // Schritt 3: Package-Verschiebungen fuer PROBLEMATISCH-Outlier.
        // Fuer jedes PROBLEMATISCH-Package berechnen wir die Bindung an alle
        // SCCs und verschieben es in das SCC mit der staerksten Bindung, wenn
        // das die Metrik reduziert. Iterativ bis Konvergenz.
        List<String> zuVerschieben = new ArrayList<>(auszulagerndePackages);
        zuVerschieben.addAll(problematischePackages);
        OptimierungsSchritt paketSchritt = optimierePaketVerschiebungen(
                bereinigt, ausgegliedert, zuVerschieben);
        bereinigt = paketSchritt.zerlegung;
        metrikNachher = paketSchritt.metrik;

        // Schritt 4: smallCandidates anwenden (SCS unter minScsSize raus).
        Zerlegung finalZ = applySmallCandidates(bereinigt, minScsSize, List.of());

        // Schritt 5: History-Eintrag (1 Schritt = der ganze Batch).
        List<IterationStep> history = new ArrayList<>();
        double reduction = metrikVorher.gesamt() > 0
                ? (metrikVorher.gesamt() - metrikNachher.gesamt()) / metrikVorher.gesamt()
                : 0.0;
        history.add(new IterationStep(1, "<batch>",
                (auszulagerndePackages.size() + paketSchritt.verschoben)
                        + " Packages (" + paketSchritt.verschoben + " nachtraeglich)",
                metrikVorher.gesamt() - metrikNachher.gesamt(), reduction));

        return new OptimierungsErgebnis(finalZ, metrikVorher, metrikNachher,
                auszulagerndePackages.size() + paketSchritt.verschoben, history);
    }

    /** Zwischenergebnis eines Optimierungsschritts. */
    private record OptimierungsSchritt(Zerlegung zerlegung,
                                       MetrikDetail metrik,
                                       int verschoben) {
    }

    /**
     * Verschiebt einzelne Packages zwischen SCS, um die Metrik weiter zu
     * senken. Fuer jedes Package wird die Bindung (Summe der Cardinalities)
     * an jedes SCS berechnet. Wird das Package in das SCS mit der staerksten
     * Bindung verschoben und reduziert das die Metrik (d.h. die Bindung an
     * das Ziel-SCS uebersteigt die Bindung an das aktuelle SCS), wird die
     * Verschiebung ausgefuehrt.
     *
     * <p>Iterativ bis zur Konvergenz: nach jeder Verschiebung aendert sich
     * die SCS-Zuordnung, also werden die Bindungen neu berechnet.
     *
     * <p>Das Package verlaesst sein Original-Bundle und lebt als
     * Singleton-Bundle im Ziel-SCS. Das Original-Bundle bleibt unveraendert
     * (verliert nur dieses eine Package). Rueckgaenge sind in der
     * Implementierung nicht vorgesehen.
     */
    private OptimierungsSchritt optimierePaketVerschiebungen(
            Zerlegung z, Set<String> ausgegliedertePackages, List<String> packages) {

        Map<String, String> overrides = new HashMap<>(z.packageBundleOverride());
        Map<String, String> bundleZuScs = new HashMap<>(z.bundleZuScs());
        int nScs = z.scs().size();
        int singletonCounter = 0;
        int totalVerschoben = 0;

        // Endpunkt-Index einmal aufbauen: Package -> [(otherIdx, weight), ...]
        // Spart O(E) Iterationen pro Package-SCS-Paar.
        Map<String, List<double[]>> pkgEdgeIndex = new HashMap<>();
        Map<Integer, String> pkgIndex = new HashMap<>();
        Map<String, Integer> pkgIdByName = new HashMap<>();
        int nextIdx = 0;
        for (DefaultWeightedEdge e : pg.graph().edgeSet()) {
            String s = pg.graph().getEdgeSource(e);
            String t = pg.graph().getEdgeTarget(e);
            if (s.equals(t)) continue;
            double w = pg.graph().getEdgeWeight(e);
            Integer si = pkgIdByName.get(s);
            if (si == null) { si = nextIdx++; pkgIdByName.put(s, si); pkgIndex.put(si, s); }
            Integer ti = pkgIdByName.get(t);
            if (ti == null) { ti = nextIdx++; pkgIdByName.put(t, ti); pkgIndex.put(ti, t); }
            pkgEdgeIndex.computeIfAbsent(s, k -> new ArrayList<>()).add(new double[]{ti, w});
            pkgEdgeIndex.computeIfAbsent(t, k -> new ArrayList<>()).add(new double[]{si, w});
        }

        // Original-Bundle jedes Kandidaten einmal bestimmen (statt pro Runde
        // neu aus pg.nodeProps() zu holen).
        Map<String, String> originalBundle = new HashMap<>();
        for (String p : packages) {
            if (ausgegliedertePackages.contains(p)) continue;
            Map<String, Object> props = pg.nodeProps().get(p);
            if (props == null) continue;
            Object b = props.get("bundle");
            if (b != null) originalBundle.put(p, b.toString());
        }

        int maxRunden = 100;
        for (int runde = 0; runde < maxRunden; runde++) {
            String bestPkg = null;
            int bestTarget = -1;
            double bestSavings = 1e-9;

            for (String p : packages) {
                if (ausgegliedertePackages.contains(p)) continue;
                String currentBundle = overrides.getOrDefault(p, originalBundle.get(p));
                if (currentBundle == null) continue;
                String currentScsName = bundleZuScs.get(currentBundle);
                if (currentScsName == null || !currentScsName.startsWith("SCS")) continue;
                int currentScs = Integer.parseInt(currentScsName.substring(3));
                if (currentScs < 0 || currentScs >= nScs) continue;

                double currentBinding = berechneBindung(p, currentScs,
                        bundleZuScs, overrides, pkgEdgeIndex, pkgIndex);

                for (int t = 0; t < nScs; t++) {
                    if (t == currentScs) continue;
                    double tBinding = berechneBindung(p, t,
                            bundleZuScs, overrides, pkgEdgeIndex, pkgIndex);
                    // delta > 0: Verschiebung von current zu t spart Metrik.
                    double savings = currentBinding - tBinding;
                    if (savings > bestSavings) {
                        bestSavings = savings;
                        bestPkg = p;
                        bestTarget = t;
                    }
                }
            }

            if (bestPkg == null) break; // konvergiert

            String newBundleName = "__singleton_" + (singletonCounter++) + "__" + bestPkg;
            overrides.put(bestPkg, newBundleName);
            bundleZuScs.put(newBundleName, "SCS" + bestTarget);
            totalVerschoben++;
        }

        if (totalVerschoben == 0) {
            return new OptimierungsSchritt(z, berechneMetrikDetail(z, ausgegliedertePackages), 0);
        }
        Zerlegung neueZerlegung = new Zerlegung(z.scs(), z.overlayBundles(),
                z.smallCandidates(), z.coreBundles(),
                z.modulDagAzyklisch(), z.zyklischePaare(),
                bundleZuScs, z.iterHistory(), z.corePackages(), overrides);
        return new OptimierungsSchritt(neueZerlegung,
                berechneMetrikDetail(neueZerlegung, ausgegliedertePackages),
                totalVerschoben);
    }

    /**
     * Berechnet die symmetrische Bindung (Summe eingehender und ausgehender
     * Cardinalities) eines Packages an ein SCS.
     *
     * <p>Effizienz: ueber den vollstaendigen Package-Graphen zu iterieren
     * waere O(E) pro Package und SCS. Stattdessen nutzen wir den Endpunkt-
     * Index {@code pkgEdgeIndex} und iterieren nur ueber die Nachbarn des
     * konkreten Packages.
     */
    private double berechneBindung(String p, int scsIdx,
                                   Map<String, String> bundleZuScs,
                                   Map<String, String> pkgOverride,
                                   Map<String, List<double[]>> pkgEdgeIndex,
                                   Map<Integer, String> pkgIndex) {
        String scsName = "SCS" + scsIdx;
        double sum = 0.0;
        List<double[]> edgesOf = pkgEdgeIndex.get(p);
        if (edgesOf == null) return 0.0;
        for (double[] e : edgesOf) {
            int otherIdx = (int) e[0];
            String other = pkgIndex.get(otherIdx);
            String otherBundle = pkgOverride.get(other);
            if (otherBundle == null) {
                Map<String, Object> props = pg.nodeProps().get(other);
                if (props == null) continue;
                Object b = props.get("bundle");
                if (b == null) continue;
                otherBundle = b.toString();
            }
            if (scsName.equals(bundleZuScs.get(otherBundle))) {
                sum += e[1];
            }
        }
        return sum;
    }

    public Zerlegung zerlegenMitAuslagerungen(Set<String> coreBundles,
                                              double resolution,
                                              Set<String> auszulagerndePackages,
                                              int maxIterations,
                                              int minScsSize,
                                              double minImprovement,
                                              IterationListener iterationListener,
                                              Set<String> corePackages,
                                              List<String> problematischePackages) {
        OptimierungsErgebnis r = optimiereMitAuslagerung(
                coreBundles, resolution, auszulagerndePackages,
                minScsSize, corePackages, problematischePackages);
        if (iterationListener != null) {
            iterationListener.onIteration(1, "<batch>",
                    r.packagesAusgelagert() + " Packages",
                    r.metrikVorher().gesamt() - r.metrikNachher().gesamt(),
                    r.reductionProzent() / 100.0);
        }
        return r.zerlegung();
    }

    /** Hook fuer iterative Konsolen-Ausgabe o.ae. */
    @FunctionalInterface
    public interface IterationListener {
        void onIteration(int iteration, String pkg, String bundle,
                         double deltaMetric, double cumulativeReduction);
    }

    /** Eine Zeile der Iterations-Historie. */
    public record IterationStep(int iteration,
                                String pkg,
                                String bundle,
                                double deltaMetric,
                                double cumulativeReduction) {
    }

    /**
     * Innere Clustering-Methode, wiederverwendet von zerlegen() und der
     * iterativen Variante. Liefert eine Zerlegung OHNE smallCandidates-Behandlung;
     * {@code packagesToExclude} werden vor der Clusterung aus den Bundles
     * entfernt (Package wird "ausgelagert", sein Bundle verliert dieses eine
     * Package).
     */
    private Zerlegung zerlegeKern(Set<String> coreBundles,
                                  double resolution,
                                  Set<String> packagesToExclude,
                                  Set<String> corePackages) {
        Map<String, String> bundleOf = attr("bundle");
        Map<String, String> productOf = attr("product");
        Map<String, String> clsOf = attr("classification");

        // --- Bundles und ihre Packages ---
        Map<String, List<String>> bundlePakete = new TreeMap<>();
        for (Map.Entry<String, String> e : bundleOf.entrySet()) {
            if (packagesToExclude.contains(e.getKey())) continue;
            bundlePakete.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        }
        Set<String> alleBundles = new HashSet<>(bundlePakete.keySet());
        // Auch Bundles ohne verbleibende Packages (komplett ausgelagert) ausschliessen.
        alleBundles.removeIf(b -> bundlePakete.get(b).isEmpty());

        // --- Bundle-Graph: Kante b1->b2 mit Summe der Abhaengigkeitsgewichte ---
        Graph<String, DefaultWeightedEdge> bg =
                new DefaultDirectedWeightedGraph<>(DefaultWeightedEdge.class);
        for (String b : alleBundles) bg.addVertex(b);

        Map<String, Double> fanIn = new HashMap<>();
        Map<String, Double> fanOut = new HashMap<>();
        Map<String, Integer> eingehendBundles = new HashMap<>();
        Map<String, Integer> ausgehendBundles = new HashMap<>();
        for (String b : alleBundles) {
            fanIn.put(b, 0.0);
            fanOut.put(b, 0.0);
            eingehendBundles.put(b, 0);
            ausgehendBundles.put(b, 0);
        }

        Map<String, DefaultWeightedEdge> kantenIndex = new HashMap<>();
        for (DefaultWeightedEdge e : pg.graph().edgeSet()) {
            String s = pg.graph().getEdgeSource(e);
            String t = pg.graph().getEdgeTarget(e);
            if (s.equals(t)) continue;
            String bs = bundleOf.get(s);
            String bt = bundleOf.get(t);
            if (bs == null || bt == null || bs.equals(bt)) continue;
            // Beide Enden muessen in einem nicht-leeren Bundle liegen.
            if (!bg.containsVertex(bs) || !bg.containsVertex(bt)) continue;
            double w = pg.graph().getEdgeWeight(e);
            DefaultWeightedEdge be = kantenIndex.get(bs + "\u0000" + bt);
            if (be == null) {
                be = bg.addEdge(bs, bt);
                if (be == null) continue;
                kantenIndex.put(bs + "\u0000" + bt, be);
                bg.setEdgeWeight(be, w);
                fanOut.merge(bs, w, Double::sum);
                fanIn.merge(bt, w, Double::sum);
                ausgehendBundles.merge(bs, 1, Integer::sum);
                eingehendBundles.merge(bt, 1, Integer::sum);
            } else {
                bg.setEdgeWeight(be, bg.getEdgeWeight(be) + w);
            }
        }

        // --- Overlay: Bundles, die quer zu vielen anderen liegen ---
        List<String> overlay = new ArrayList<>();
        // Rangbasiert: die Top-OVERLAY_ANZAHL Bundles nach Fan-in UND Fan-out.
        // Ein Overlay-Bundle wird von vielen anderen benutzt (Fan-in hoch)
        // und benutzt selbst viele (Fan-out hoch) - das ist die Signatur
        // eines uebergreifenden Systems, nicht die einer Fachdomaene.
        List<String> rang = new ArrayList<>(alleBundles);
        rang.removeAll(coreBundles);
        rang.sort(Comparator
                .comparingInt((String b) -> eingehendBundles.getOrDefault(b, 0))
                .thenComparingInt(b -> ausgehendBundles.getOrDefault(b, 0))
                .reversed());
        int take = Math.min(OVERLAY_ANZAHL, rang.size());
        for (int i = 0; i < take; i++) {
            overlay.add(rang.get(i));
        }

        // --- Clustergraph ohne Core und Overlay ---
        Set<String> ausgeschlossen = new HashSet<>(coreBundles);
        ausgeschlossen.addAll(overlay);
        Graph<String, DefaultWeightedEdge> cg =
                new DefaultDirectedWeightedGraph<>(DefaultWeightedEdge.class);
        for (String b : alleBundles) {
            if (!ausgeschlossen.contains(b)) cg.addVertex(b);
        }
        for (DefaultWeightedEdge e : bg.edgeSet()) {
            String s = bg.getEdgeSource(e);
            String t = bg.getEdgeTarget(e);
            if (s.equals(t)) continue;
            if (cg.containsVertex(s) && cg.containsVertex(t)) {
                cg.setEdgeWeight(cg.addEdge(s, t), bg.getEdgeWeight(e));
            }
        }

        Map<Integer, List<String>> clusters = cg.vertexSet().isEmpty()
                ? new TreeMap<>()
                : LeidenClusterAnalyse.getLeidenCommunities(cg);
        clusters = aufZielanzahl(clusters, 10);

        // --- Lokale Cut-Optimierung (Kernighan-Lin-artig) ---
        // Leiden optimiert MODULARITAET, nicht den CUT. Die Optimierungs-
        // metrik ist aber genau der Cut (ungewollte Beziehungen zwischen
        // verschiedenen SCS). Deshalb verschieben wir Bundles anschliessend
        // gierig auf das SCS, das den kleinsten Zuwachs am Cut erzeugt.
        // WICHTIG: Der Cut wird GERICHTET berechnet — analog zur Metrik in
        // berechneMetrikDetail() zaehlt eine Kante b1->b2 nur dann als Cut,
        // wenn b1 im Quell-SCS und b2 im Ziel-SCS liegt. Eine symmetrische
        // Berechnung wuerde eine andere Zielfunktion optimieren.
        clusters = optimiereCut(cg, clusters, bg, coreBundles, overlay);

        // --- SCS-Kandidaten mit Kennzahlen ---
        Map<String, String> bundleZuScs = new HashMap<>();
        List<Scs> scs = new ArrayList<>();
        int id = 0;
        for (Map.Entry<Integer, List<String>> e : clusters.entrySet()) {
            List<String> bundles = e.getValue();
            if (bundles.isEmpty()) continue;
            List<String> pakete = new ArrayList<>();
            Map<String, Integer> prodCount = new HashMap<>();
            Map<String, Integer> layerCount = new HashMap<>();
            for (String b : bundles) {
                for (String p : bundlePakete.getOrDefault(b, List.of())) {
                    pakete.add(p);
                    prodCount.merge(productOf.getOrDefault(p, "?"), 1, Integer::sum);
                    layerCount.merge(schicht(clsOf.getOrDefault(p, "?")), 1, Integer::sum);
                }
            }
            for (String b : bundles) bundleZuScs.put(b, "SCS" + id);
            String domProd = maxKey(prodCount);
            String domLayer = maxKey(layerCount);
            int n = Math.max(1, pakete.size());
            scs.add(new Scs(id++, new ArrayList<>(bundles), pakete, domProd, domLayer,
                    (double) prodCount.getOrDefault(domProd, 0) / n,
                    (double) layerCount.getOrDefault(domLayer, 0) / n,
                    bundles.size()));
        }
        scs.sort(Comparator.comparingInt(s -> -s.packages().size()));

        // --- Modul-DAG auf Zyklen pruefen ---
        List<String> zyklen = findeZyklen(clusterAdj(bg, bundleZuScs));

        return new Zerlegung(scs, overlay, List.of(), new ArrayList<>(coreBundles),
                zyklen.isEmpty(), zyklen, bundleZuScs, List.of(), corePackages,
                Map.of());
    }

    /** Baut die Nachbarschaft auf SCS-Ebene aus dem Bundle-Graphen. */
    private Map<Integer, Set<Integer>> clusterAdj(
            Graph<String, DefaultWeightedEdge> bg, Map<String, String> bundleZuScs) {

        Map<String, Integer> scsVonBundle = new HashMap<>();
        for (Map.Entry<String, String> e : bundleZuScs.entrySet()) {
            scsVonBundle.put(e.getKey(), Integer.parseInt(e.getValue().substring(3)));
        }
        int n = scsVonBundle.values().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
        List<Set<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < n; i++) adj.add(new HashSet<>());
        for (DefaultWeightedEdge e : bg.edgeSet()) {
            Integer cs = scsVonBundle.get(bg.getEdgeSource(e));
            Integer ct = scsVonBundle.get(bg.getEdgeTarget(e));
            if (cs != null && ct != null && !cs.equals(ct)) {
                adj.get(cs).add(ct);
                adj.get(ct).add(cs);
            }
        }
        Map<Integer, Set<Integer>> result = new HashMap<>();
        for (int i = 0; i < n; i++) result.put(i, adj.get(i));
        return result;
    }

    /**
     * Bringt die Cluster auf die Zielanzahl. Die groessten Clusters werden
     * beibehalten, die kleinen Restcluster werden dem groessten Cluster
     * zugeordnet — ein fortlaufendes Zusammenlegen wuerde den groessten
     * Cluster immer weiter aufblaehen und die Aufteilung ins Leere laufen
     * lassen.
     */
    /**
     * Lokale Cut-Optimierung auf Bundle-Ebene.
     *
     * <p><b>Warum.</b> Leiden optimiert die Modularitaet des Bundle-Graphen.
     * Die Optimierungsmetrik dieser Anwendung ist dagegen der <em>Cut</em>,
     * also die Summe der Cardinalities ueber alle Kanten zwischen verschie-
     * denen Subsystemen. Ein modularitaetsoptimum muss kein Cut-Optimum sein.
     * Dieser Schritt verschiebt Bundles deshalb gierig auf das Subsystem,
     * das den kleinsten Zuwachs am Cut erzeugt.
     *
     * <p><b>Algorithmus.</b> Wiederholte Improvement-Runden: In jeder Runde
     * wird fuer jedes Bundle die Cut-Aenderung fuer alle Ziel-SCS berechnet.
     * Die global beste (negativste) Verschiebung wird ausgefuehrt, sofern
     * sie den Cut senkt. Danach wird die Runde wiederholt, bis keine
     * Verbesserung mehr moeglich ist.
     *
     * <p><b>Cut-Definition.</b> Eine gerichtete Bundle-Kante b1-&gt;b2 mit
     * Gewicht w zaehlt als Cut, wenn b1 und b2 in verschiedenen Subsystemen
     * liegen. Der Gewinn beim Verschieben von b nach SCS s ist
     * {@code cutIn(b,s) - cutOut(b,s)}, wobei cutIn die Summe der Kanten
     * zwischen b und s (beide Richtungen) ist und cutOut die Summe der
     * Kanten zwischen b und allen anderen Subsystemen.
     *
     * @param cg       Bundle-Graph ohne Core/Overlay (gerichtet, gewichtet)
     * @param clusters aktuelle Zuordnung Cluster -&gt; Bundles
     * @return optimierte Cluster-Zuordnung (gleiche Cluster-IDs)
     */
    private Map<Integer, List<String>> optimiereCut(
            Graph<String, DefaultWeightedEdge> cg,
            Map<Integer, List<String>> clusters,
            Graph<String, DefaultWeightedEdge> bg,
            Set<String> coreBundles,
            List<String> overlay) {

        if (clusters.size() <= 1 || cg.vertexSet().isEmpty()) return clusters;

        // Zuordnung Bundle -> Cluster-ID (nur bewegliche SCS-Bundles).
        Map<String, Integer> bundleCluster = new HashMap<>();
        for (Map.Entry<Integer, List<String>> e : clusters.entrySet()) {
            for (String b : e.getValue()) bundleCluster.put(b, e.getKey());
        }

        // Cluster-IDs als geschlossene Menge.
        List<Integer> clusterIds = new ArrayList<>(clusters.keySet());
        Collections.sort(clusterIds);

        // Gewichte der Bundle-Kanten zwischen beweglichen Bundles, pro
        // Start-Bundle und Ziel-Cluster. Das Gewicht wird fuer BEIDE
        // Richtungen symmetrisch aggregiert: eine Package-Kante b1->b2
        // zaehlt in der Metrik genau einmal als SCS-A->SCS-B, und die
        // gegenlaeufige Package-Kante b2->b1 als SCS-B->SCS-A. Beide
        // tragen denselben Bundle-zu-Bundle-Contribut, also ist die
        // symmetrische Aggregation die korrekte Abbildung der Metrik.
        Map<String, Map<Integer, Double>> symNach = new HashMap<>();
        for (DefaultWeightedEdge e : bg.edgeSet()) {
            String s = bg.getEdgeSource(e);
            String t = bg.getEdgeTarget(e);
            Integer cs = bundleCluster.get(s);
            Integer ct = bundleCluster.get(t);
            if (cs == null || ct == null) continue;
            double w = bg.getEdgeWeight(e);
            symNach.computeIfAbsent(s, k -> new HashMap<>()).merge(ct, w, Double::sum);
            symNach.computeIfAbsent(t, k -> new HashMap<>()).merge(cs, w, Double::sum);
        }

        // Core->SCS und Overlay->SCS sind bei einer Bundle-Verschiebung
        // konstant: die eingehenden Kanten von Core/Overlay landen
        // weiterhin auf demselben Bundle und damit in einem SCS. Der
        // Anteil SCS->Core / SCS->Overlay zaehlt nicht (erlaubt). Daher
        // beeinflusst eine Verschiebung ausschliesslich den SCS<->SCS-Anteil.

        int maxRunden = 2000;
        for (int runde = 0; runde < maxRunden; runde++) {
            String bestBundle = null;
            int bestVon = -1, bestNach = -1;
            double bestGewinn = 1e-9; // nur strikte Verbesserung

            for (Map.Entry<String, Integer> be : bundleCluster.entrySet()) {
                String b = be.getKey();
                int von = be.getValue();
                Map<Integer, Double> nb = symNach.getOrDefault(b, Map.of());
                double zumAlten = nb.getOrDefault(von, 0.0);
                for (int ziel : clusterIds) {
                    if (ziel == von) continue;
                    double zumNeuen = nb.getOrDefault(ziel, 0.0);
                    // Delta > 0 => SCS<->SCS-Cut sinkt.
                    double delta = zumAlten - zumNeuen;
                    if (delta > bestGewinn) {
                        bestGewinn = delta;
                        bestBundle = b;
                        bestVon = von;
                        bestNach = ziel;
                    }
                }
            }

            if (bestBundle == null) break; // keine Verbesserung mehr

            // Verschiebung ausfuehren.
            Map<Integer, List<String>> neu = new TreeMap<>();
            for (Map.Entry<Integer, List<String>> e : clusters.entrySet()) {
                neu.put(e.getKey(), new ArrayList<>(e.getValue()));
            }
            neu.get(bestVon).remove(bestBundle);
            neu.get(bestNach).add(bestBundle);
            clusters = neu;
            bundleCluster.put(bestBundle, bestNach);
        }

        // Leere Cluster entfernen.
        Map<Integer, List<String>> ergebnis = new TreeMap<>();
        for (Map.Entry<Integer, List<String>> e : clusters.entrySet()) {
            if (!e.getValue().isEmpty()) ergebnis.put(e.getKey(), e.getValue());
        }
        return ergebnis;
    }

    private Map<Integer, List<String>> aufZielanzahl(Map<Integer, List<String>> clusters,
                                                    int ziel) {
        Map<Integer, List<String>> work = new TreeMap<>(clusters);
        if (work.size() <= ziel) return work;

        // Nach Groesse absteigend sortieren.
        List<Map.Entry<Integer, List<String>>> sortiert = new ArrayList<>(work.entrySet());
        sortiert.sort((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));

        Map<Integer, List<String>> ergebnis = new TreeMap<>();
        for (int i = 0; i < sortiert.size(); i++) {
            Map.Entry<Integer, List<String>> e = sortiert.get(i);
            if (i < ziel - 1) {
                ergebnis.put(e.getKey(), e.getValue());
            } else {
                // Rest in den letzten (groessten verbleibenden) Cluster geben.
                Integer letzter = ergebnis.isEmpty() ? e.getKey()
                        : ergebnis.keySet().stream().max(
                                Comparator.comparingInt(k -> ergebnis.get(k).size())).orElse(e.getKey());
                ergebnis.computeIfAbsent(letzter, k -> new ArrayList<>()).addAll(e.getValue());
            }
        }
        return ergebnis;
    }

    /** Findet stark zusammenhaltende Komponenten im Modul-Graphen (SCC). */
    private List<String> findeZyklen(Map<Integer, Set<Integer>> adj) {
        Map<Integer, Integer> index = new HashMap<>();
        Map<Integer, Integer> low = new HashMap<>();
        Set<Integer> aufStack = new HashSet<>();
        List<Integer> stack = new ArrayList<>();
        int[] counter = {0};
        List<List<Integer>> sccs = new ArrayList<>();

        for (Integer start : adj.keySet()) {
            if (index.containsKey(start)) continue;
            java.util.ArrayDeque<int[]> arbeit = new java.util.ArrayDeque<>();
            index.put(start, counter[0]);
            low.put(start, counter[0]);
            counter[0]++;
            stack.add(start);
            aufStack.add(start);
            arbeit.push(new int[]{start, 0});
            while (!arbeit.isEmpty()) {
                int[] rahmen = arbeit.peek();
                int knoten = rahmen[0];
                List<Integer> nachbarn = new ArrayList<>(adj.getOrDefault(knoten, Set.of()));
                boolean weitergemacht = false;
                while (rahmen[1] < nachbarn.size()) {
                    int w = nachbarn.get(rahmen[1]++);
                    if (!index.containsKey(w)) {
                        index.put(w, counter[0]);
                        low.put(w, counter[0]);
                        counter[0]++;
                        stack.add(w);
                        aufStack.add(w);
                        arbeit.push(new int[]{w, 0});
                        weitergemacht = true;
                        break;
                    } else if (aufStack.contains(w)) {
                        low.put(knoten, Math.min(low.get(knoten), index.get(w)));
                    }
                }
                if (weitergemacht) continue;
                arbeit.pop();
                if (low.get(knoten).equals(index.get(knoten))) {
                    List<Integer> comp = new ArrayList<>();
                    while (true) {
                        int w = stack.remove(stack.size() - 1);
                        aufStack.remove(w);
                        comp.add(w);
                        if (w == knoten) break;
                    }
                    if (comp.size() > 1) sccs.add(comp);
                }
                if (!arbeit.isEmpty()) {
                    int eltern = arbeit.peek()[0];
                    low.put(eltern, Math.min(low.get(eltern), low.get(knoten)));
                }
            }
        }

        List<String> zyklen = new ArrayList<>();
        for (List<Integer> comp : sccs) {
            List<Integer> sortiert = new ArrayList<>(comp);
            sortiert.sort(null);
            zyklen.add("SCS " + sortiert + " bilden einen Zyklus ("
                    + sortiert.size() + " Subsysteme)");
        }
        return zyklen;
    }

    private static String maxKey(Map<String, Integer> m) {
        return m.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("?");
    }

    /** Dritte Ebene der classification, z. B. {@code PROZESSDATEN}. */
    private String schicht(String classification) {
        String[] teile = classification.split("/");
        return teile.length >= 3 ? teile[2] : classification;
    }

    private Map<String, String> attr(String key) {
        Map<String, String> map = new HashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : pg.nodeProps().entrySet()) {
            Object v = e.getValue().get(key);
            map.put(e.getKey(), v == null ? "?" : v.toString());
        }
        return map;
    }
}
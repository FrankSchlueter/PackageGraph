package de.tk.dependencyanalyse.packagegraph.analysis;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultWeightedEdge;

import de.tk.dependencyanalyse.packagegraph.graph.PackageGraph;

/**
 * Prueft fuer jeden Outlier, ob eine Auslagerung aus dem Bundle sauber
 * moeglich waere.
 *
 * <p><b>Bewertungskriterien (nur direkte Kanten):</b>
 * <ul>
 *   <li><b>Beziehungen zu Core</b>: Anzahl direkter Kanten vom Outlier zu
 *       Packages, die in der uebergebenen Core-Menge liegen UND im selben
 *       Bundle sind. &mdash; das ist der "natuerliche Anker" des Outliers.</li>
 *   <li><b>Beziehungen zu Restbundle</b>: Anzahl direkter Kanten vom Outlier
 *       zu Packages im selben Bundle, die NICHT zur Core-Menge gehoeren.
 *       &mdash; das sind die Kopplungen, die bei einer Auslagerung ins
 *       Restbundle zurueckbleiben wuerden.</li>
 *   <li><b>Rueckkanten aus Restbundle</b>: Anzahl direkter Kanten VON
 *       Restbundle-Packages ZUM Outlier. &mdash; das sind zirkulaere
 *       Abhaengigkeiten; eine Auslagerung wuerde einen neuen Zyklus erzeugen.</li>
 * </ul>
 *
 * <p><b>Bewertungsstufen:</b>
 * <ul>
 *   <li>{@code SINNVOLL}: Beziehungen nur in Core-Packages des Bundles,
 *       keine Rueckkanten.</li>
 *   <li>{@code BEDENKLICH}: Beziehungen ins Restbundle vorhanden, aber
 *       keine Rueckkanten.</li>
 *   <li>{@code PROBLEMATISCH}: Rueckkanten aus Restbundle.</li>
 * </ul>
 */
public final class OutlierAuslagerungsPruefer {

    private final PackageGraph pg;
    private final Map<String, String> bundleOf;
    private final Set<String> corePackages;

    public OutlierAuslagerungsPruefer(PackageGraph pg,
                                      Map<String, String> bundleOf,
                                      Set<String> corePackages) {
        this.pg = pg;
        this.bundleOf = bundleOf;
        this.corePackages = corePackages;
    }

    /**
     * Bewertet die uebergebene Liste von Outliers und gibt eine neue Liste
     * zurueck, in der jeder Outlier um Auslagerungs-Metriken ergaenzt ist.
     */
    public List<OutlierEntry> bewerte(List<OutlierEntry> outliers) {
        Graph<String, DefaultWeightedEdge> g = pg.graph();
        List<OutlierEntry> result = new ArrayList<>(outliers.size());
        for (OutlierEntry o : outliers) {
            String outlierId = o.nodeId();
            String outlierBundle = o.bundle();

            int zuCore = 0;
            double cardCore = 0.0;
            int zuRest = 0;
            double cardRest = 0.0;
            int rueck = 0;

            // Ausgehend vom Outlier -> Nachbarn.
            for (DefaultWeightedEdge e : g.outgoingEdgesOf(outlierId)) {
                String nb = g.getEdgeTarget(e);
                if (nb.equals(outlierId)) continue;
                String nbBundle = bundleOf.get(nb);
                if (!outlierBundle.equals(nbBundle)) continue;
                double w = g.getEdgeWeight(e);
                if (corePackages.contains(nb)) {
                    zuCore++;
                    cardCore += w;
                } else {
                    zuRest++;
                    cardRest += w;
                }
            }
            // Eingehend zum Outlier <- Nachbarn.
            for (DefaultWeightedEdge e : g.incomingEdgesOf(outlierId)) {
                String src = g.getEdgeSource(e);
                if (src.equals(outlierId)) continue;
                String srcBundle = bundleOf.get(src);
                if (!outlierBundle.equals(srcBundle)) continue;
                if (!corePackages.contains(src)) {
                    rueck++;
                }
            }

            OutlierEntry.AuslagerungsBewertung bewertung;
            if (rueck > 0) {
                bewertung = OutlierEntry.AuslagerungsBewertung.PROBLEMATISCH;
            } else if (zuRest > 0) {
                bewertung = OutlierEntry.AuslagerungsBewertung.BEDENKLICH;
            } else {
                bewertung = OutlierEntry.AuslagerungsBewertung.SINNVOLL;
            }

            result.add(o.withAuslagerung(zuCore, zuRest, rueck,
                    cardCore, cardRest, bewertung));
        }
        return result;
    }

    /** Liefert die Outliers mit Bewertung != PROBLEMATISCH (Kandidaten fuer Auslagerung). */
    public static List<OutlierEntry> auslagerungsKandidaten(List<OutlierEntry> bewertete) {
        List<OutlierEntry> r = new ArrayList<>();
        for (OutlierEntry o : bewertete) {
            if (o.auslagerungsBewertung() != OutlierEntry.AuslagerungsBewertung.PROBLEMATISCH) {
                r.add(o);
            }
        }
        return r;
    }

    /** Convenience: zaehlt pro Bewertungsstufe. */
    public static int[] verteilung(List<OutlierEntry> bewertete) {
        int sinnvoll = 0, bedenklich = 0, problematisch = 0;
        for (OutlierEntry o : bewertete) {
            switch (o.auslagerungsBewertung()) {
                case SINNVOLL     -> sinnvoll++;
                case BEDENKLICH   -> bedenklich++;
                case PROBLEMATISCH -> problematisch++;
                default -> { /* UNBEKANNT */ }
            }
        }
        return new int[]{sinnvoll, bedenklich, problematisch};
    }

    /** Eindeutige Bundle-Menge, aus der die Outliers stammen. */
    public static Set<String> betroffeneBundles(List<OutlierEntry> bewertete) {
        Set<String> s = new HashSet<>();
        for (OutlierEntry o : bewertete) s.add(o.bundle());
        return s;
    }
}
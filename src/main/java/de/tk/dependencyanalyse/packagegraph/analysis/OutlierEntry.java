package de.tk.dependencyanalyse.packagegraph.analysis;

/**
 * DTO fuer einen Outlier-Eintrag im Report.
 *
 * <p>{@code deviation} = |{@code nodeDependencyLevel} - Bundle-Mittelwert|.
 * Die Bundle-Statistik ist redundant, aber nuetzlich fuer den Report.
 *
 * <p>Die Felder {@code beziehungenZuCore}, {@code beziehungenZuRestBundle},
 * {@code rueckkantenAusRestbundle} und {@code auslagerungsBewertung} werden
 * durch den {@link OutlierAuslagerungsPruefer} befuellt und sind optional
 * (Standardwerte 0 / {@code UNBEKANNT}).
 */
public final class OutlierEntry {

    public enum Side { HIGH, LOW }

    /** Bewertung der Auslagerungs-Sinnhaftigkeit. */
    public enum AuslagerungsBewertung {
        /** Beziehungen nur in Core-Packages des Bundles, keine Rueckkanten. */
        SINNVOLL,
        /** Beziehungen ins Restbundle, aber keine Rueckkanten. */
        BEDENKLICH,
        /** Rueckkanten aus Restbundle: Auslagerung wuerde neuen Zyklus erzeugen. */
        PROBLEMATISCH,
        /** Noch nicht bewertet (Initialzustand). */
        UNBEKANNT
    }

    private final String bundle;
    private final String nodeId;
    private final String nodeName;
    private final int level;
    private final double nodeDependencyLevel;
    private final double bundleMean;
    private final double deviation;
    private final Side side;

    // Auslagerungs-Bewertung (vom OutlierAuslagerungsPruefer befuellt).
    private final int beziehungenZuCore;
    private final int beziehungenZuRestBundle;
    private final int rueckkantenAusRestbundle;
    private final double cardZuCore;
    private final double cardZuRest;
    private final AuslagerungsBewertung auslagerungsBewertung;

    public OutlierEntry(String bundle, String nodeId, String nodeName,
                        int level, double nodeDependencyLevel,
                        double bundleMean, double deviation, Side side) {
        this(bundle, nodeId, nodeName, level, nodeDependencyLevel,
                bundleMean, deviation, side,
                0, 0, 0, 0.0, 0.0, AuslagerungsBewertung.UNBEKANNT);
    }

    public OutlierEntry(String bundle, String nodeId, String nodeName,
                        int level, double nodeDependencyLevel,
                        double bundleMean, double deviation, Side side,
                        int beziehungenZuCore, int beziehungenZuRestBundle,
                        int rueckkantenAusRestbundle,
                        double cardZuCore, double cardZuRest,
                        AuslagerungsBewertung auslagerungsBewertung) {
        this.bundle = bundle;
        this.nodeId = nodeId;
        this.nodeName = nodeName;
        this.level = level;
        this.nodeDependencyLevel = nodeDependencyLevel;
        this.bundleMean = bundleMean;
        this.deviation = deviation;
        this.side = side;
        this.beziehungenZuCore = beziehungenZuCore;
        this.beziehungenZuRestBundle = beziehungenZuRestBundle;
        this.rueckkantenAusRestbundle = rueckkantenAusRestbundle;
        this.cardZuCore = cardZuCore;
        this.cardZuRest = cardZuRest;
        this.auslagerungsBewertung = auslagerungsBewertung;
    }

    public OutlierEntry withAuslagerung(int beziehungenZuCore,
                                        int beziehungenZuRestBundle,
                                        int rueckkantenAusRestbundle,
                                        double cardZuCore, double cardZuRest,
                                        AuslagerungsBewertung bewertung) {
        return new OutlierEntry(bundle, nodeId, nodeName, level, nodeDependencyLevel,
                bundleMean, deviation, side,
                beziehungenZuCore, beziehungenZuRestBundle,
                rueckkantenAusRestbundle, cardZuCore, cardZuRest, bewertung);
    }

    public String bundle() { return bundle; }
    public String nodeId() { return nodeId; }
    public String nodeName() { return nodeName; }
    public int level() { return level; }
    public double nodeDependencyLevel() { return nodeDependencyLevel; }
    public double bundleMean() { return bundleMean; }
    public double deviation() { return deviation; }
    public Side side() { return side; }

    public int beziehungenZuCore() { return beziehungenZuCore; }
    public int beziehungenZuRestBundle() { return beziehungenZuRestBundle; }
    public int rueckkantenAusRestbundle() { return rueckkantenAusRestbundle; }
    public double cardZuCore() { return cardZuCore; }
    public double cardZuRest() { return cardZuRest; }
    public AuslagerungsBewertung auslagerungsBewertung() { return auslagerungsBewertung; }
}
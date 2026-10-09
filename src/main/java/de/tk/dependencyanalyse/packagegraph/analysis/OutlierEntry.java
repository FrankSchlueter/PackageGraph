package de.tk.dependencyanalyse.packagegraph.analysis;

/**
 * DTO fuer einen Outlier-Eintrag im Report.
 *
 * <p>{@code deviation} = |{@code nodeDependencyLevel} - Bundle-Mittelwert|.
 * Die Bundle-Statistik ist redundant, aber nuetzlich fuer den Report.
 */
public final class OutlierEntry {

    public enum Side { HIGH, LOW }

    private final String bundle;
    private final String nodeId;
    private final String nodeName;
    private final int level;
    private final double nodeDependencyLevel;
    private final double bundleMean;
    private final double deviation;
    private final Side side;

    public OutlierEntry(String bundle, String nodeId, String nodeName,
                        int level, double nodeDependencyLevel,
                        double bundleMean, double deviation, Side side) {
        this.bundle = bundle;
        this.nodeId = nodeId;
        this.nodeName = nodeName;
        this.level = level;
        this.nodeDependencyLevel = nodeDependencyLevel;
        this.bundleMean = bundleMean;
        this.deviation = deviation;
        this.side = side;
    }

    public String bundle() { return bundle; }
    public String nodeId() { return nodeId; }
    public String nodeName() { return nodeName; }
    public int level() { return level; }
    public double nodeDependencyLevel() { return nodeDependencyLevel; }
    public double bundleMean() { return bundleMean; }
    public double deviation() { return deviation; }
    public Side side() { return side; }
}

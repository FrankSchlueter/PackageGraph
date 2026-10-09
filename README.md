# PackageGraph DSM Analyzer

Liest ein GML-Paket-Graph (tk-dependencyanalyse-Export), berechnet
Levels via DSM-Sortierung und erzeugt einen HTML-Outlier-Report pro
Bundle.

## Build

```bash
mvn package
```

Ergebnis: `target/packagegraph-analyzer-0.1.0-SNAPSHOT.jar` (fat-jar).

## Run

```bash
java -jar target/packagegraph-analyzer-0.1.0-SNAPSHOT.jar \
     --input  /home/frank/projects/PackageGraph/packageGraph.gml \
     --output /home/frank/projects/PackageGraph/report.html \
     [--percentile 5]                # default 5
     [--min-bundle-size 5]           # default 5
```

## Was passiert

1. **GML einlesen** (`gml/GmlReader`) — rekursiver Descent-Parser,
   Self-Edges (`source == target`) werden gefiltert.
2. **JGraphT-Graph bauen** (`graph/PackageGraphBuilder`) —
   `DirectedMultigraph<String, DefaultEdge>` plus parallele
   HashMaps fuer Knoten- und Kanten-Properties.
3. **DSM-Sortierung** (`dsm/DsmSorter`) — Tarjan-SCC + topologische
   Sortierung des SCC-DAG; Reihenfolge wird in jeder Node als
   Property `dsmOrder` abgelegt.
4. **Level berechnen** (`dsm/LevelCalculator`) — Longest-Path-
   Layering: Knoten ohne Abhaengigkeiten = Level 1, jede weitere
   Schicht erhoeht das Level um 1. Property `level` (Integer).
5. **nodeDependencyLevel** (`analysis/NodeDependencyLevelCalculator`) —
   Mittelwert der Levels aller Direkt-Abhaengigkeiten. Property
   `nodeDependencyLevel` (Double).
6. **Outlier pro Bundle** (`analysis/BundleOutlierDetector`) —
   rangbasiert Top-N % (oberste und unterste), sortiert nach
   Abweichung vom Bundle-Mittelwert absteigend.
7. **HTML-Report** (`report/HtmlReportWriter`) — self-contained,
   inline CSS, eine Section pro Bundle.

## Tests

```bash
mvn test
```

# PackageGraph — DSM Analyzer und SCS-Optimierer

Liest ein GML-Paket-Graph-Export (tk-dependencyanalyse), baut daraus
einen Package- und Bundle-Graphen auf, berechnet DSM-Sortierung +
Level-Metriken, identifiziert Outlier pro Bundle und schlägt eine
Zerlegung in Core-Layer + Self-Contained Systems (SCS) + Cross-Cutting
Overlay vor. Liefert einen self-contained HTML-Report mit Vorher/
Nachher-Vergleich der ungewollten Beziehungen nach iterativer
Outlier-Auslagerung.

## Build

```bash
mvn package
```

Ergebnis: `target/packagegraph-analyzer-0.1.0-SNAPSHOT.jar` (fat-jar).

## Run

```bash
java -Xmx6g -jar target/packagegraph-analyzer-0.1.0-SNAPSHOT.jar \
     --input  /home/frank/projects/PackageGraph/packageGraph.gml \
     --output /home/frank/projects/PackageGraph/report.html \
     [--percentile 5]                   # Outlier-Anteil pro Bundle, default 5%
     [--min-bundle-size 5]              # min. Packages pro Bundle, default 5
     [--scs-resolution 1.0]             # Leiden-Aufloesung, default 1.0
     [--min-scs-size 100]               # SCS-Mindestgroesse, default 100
     [--auslagerung-iterations 50]      # SCS-Optimierung (0 = aus), default 50
     [--auslagerung-max-kandidaten 3000] # Sicherheitslimit, default 3000
```

End-to-End-Lauf auf einem 21 800-Pakete-Graph: ~65 s, ~6 GB Heap.

## Vorgehen — die 7 Stufen

Die Pipeline läuft strikt sequentiell. Jede Stufe schreibt ihre
Ergebnisse als Properties in den Graphen, sodass nachfolgende Stufen
darauf aufbauen können.

### 1. GML einlesen

`gml/GmlReader` parst die GML-Datei rekursiv (Descent-Parser). Encoding
wird in dieser Reihenfolge erkannt:

1. **UTF-8 BOM** (`EF BB BF`) → striktes UTF-8
2. **Heuristik** auf Umlaute/Sonderzeichen → striktes UTF-8
3. **Fallback** → ISO-8859-1 (deutsche Umlaute)

Self-Edges (`source == target`) werden beim Parsen gefiltert. Das
Ergebnis ist ein typsicherer `GmlReader.Graph` (JGraphT-Graph +
Properties-Maps für Knoten und Kanten).

### 2. Package-Graph aufbauen

`graph/PackageGraphBuilder` erzeugt aus dem GML-Graphen den
Package-Graph `PackageGraph`:

- **Knoten**: alle GML-Knoten mit Properties (`bundle`, `product`,
  `classification`, `level`, `nodeDependencyLevel`, `dsmOrder`).
- **Kanten**: gerichtete Kanten mit Gewicht = `cardinality`
  (Summe der Aufruf-Beziehungen zwischen zwei Packages).
- **Bereinigung**: Generierte Pakete werden über eine Filterliste
  aussortiert (`document|xml|xsd|jaxb|generiert`, XSD-Versionsmuster
  `v\d+m\d+`, Message-Codes `(s|syn)\d{3}[a-z]*`). Entities, Models
  und DTOs bleiben explizit drin.

### 3. DSM-Sortierung

`dsm/DsmSorter` berechnet die optimale Lese-Reihenfolge im
Dependency-Structure-Matrix:

1. **Tarjan-SCC**: identifiziert stark zusammenhängende Komponenten.
2. **Topologische Sortierung** auf dem SCC-DAG.
3. **Property `dsmOrder`**: ganzzahlige Reihenfolge pro Knoten.

Die DSM-Reihenfolge minimiert die Diagonale und gruppiert
zusammengehörige Knoten — sie ist die Basis für die spätere
Top-30-Kanten-Tabelle im Report.

### 4. Level + nodeDependencyLevel

`dsm/LevelCalculator` berechnet zwei komplementäre Metriken:

- **`level`** (Integer): Longest-Path-Layering. Pakete ohne
  Abhängigkeiten sind auf Level 1, jede weitere Schicht +1. Pakete mit
  zyklischen Abhängigkeiten bekommen das Maximum ihrer SCC-Levels.
- **`nodeDependencyLevel`** (Double): Mittelwert der Levels aller
  direkten Abhängigkeiten. Ein Outlier hat ein niedriges
  `nodeDependencyLevel` (zeigt nach unten) oder hohes `nodeDependencyLevel`
  (zeigt nach oben), gemessen am Mittelwert seines Bundles.

### 5. Outlier pro Bundle

`analysis/BundleOutlierDetector` markiert pro Bundle die obersten und
untersten N % (Perzentil) der Packages nach Abweichung vom
Bundle-Mittelwert. Das liefert die initiale Outlier-Menge.

### 6. Outlier-Bewertung (SINNVOLL / BEDENKLICH / PROBLEMATISCH)

`analysis/OutlierAuslagerungsPruefer` bewertet jeden Outlier
hinsichtlich seiner Beziehungen:

| Bewertung | Kriterium | Folge |
|---|---|---|
| **SINNVOLL** (2 213) | Outlier hat **nur** Beziehungen zum Core, **keine** Rückkanten ins eigene Bundle | Wird vor der SCS-Clusterung ausgelagert |
| **BEDENKLICH** (52) | Outlier hat Beziehungen zum Core **und** zu anderen Packages im eigenen Bundle, **keine** Rückkanten | Wird ebenfalls vorab ausgelagert |
| **PROBLEMATISCH** (155) | Outlier hat **Rückkanten** ins eigene Bundle | Wird nicht vorab ausgelagert, sondern im Package-Shift-Schritt einzeln behandelt |

Die Unterscheidung SINNVOLL/BEDENKLICH/PROBLEMATISCH garantiert, dass
keine Auslagerung Zyklen erzeugt (Reisser-Verbot). Die Bewertung
arbeitet auf direkten Kanten, mit der 80-%-Core-Variante als Referenz
für die Core-Menge.

### 7. SCS-Optimierung (der Kern)

`analysis/ScsZerlegung` zerlegt den Bundle-Graphen in 5–10 vertikale
Self-Contained Systems. Die Optimierung läuft in **vier Phasen**:

#### Phase A: Initiale Leiden-Clusterung

- **Bundle-Graph** `bg`: Knoten = alle Bundles, Kanten = Summe der
  Cardinalities zwischen den Bundles, gerichtet und gewichtet.
- **Overlay-Auswahl**: rangbasiert Top-10 nach `eingehend` und
  `ausgehend` (Fan-in/Fan-out). Diese 10 Bundles sind die "Klebstoff"-
  Bundles — sie werden in `overlay` extrahiert.
- **Leiden-Clusterung** auf dem restlichen Bundle-Graphen
  (`cg = bg \ overlay`) mit konfigurierbarer Auflösung
  (`--scs-resolution`, default 1.0).
- **`aufZielanzahl(clusters, 10)`**: reduziert auf maximal 10 Cluster,
  Rest wandert in den größten verbleibenden Cluster.
- **SCS-Metadaten**: pro SCS werden `dominantesProdukt`,
  `dominanteSchicht`, `reinheitProdukt`, `reinheitSchicht`,
  Package- und Bundle-Anzahl berechnet.

**Wichtig**: Die initiale Leiden-Clusterung optimiert die
**Modularität** des Bundle-Graphen, nicht den Cut zwischen SCS. Das
ist die strukturelle Ursache dafür, dass die SCS-Struktur nicht
optimal zur späteren Cut-Metrik passt.

#### Phase B: Bundle-Cut-Optimierung

`optimiereCut` (Fiduccia-Mattheyses-artig) verschiebt einzelne Bundles
zwischen SCS, um den SCS↔SCS-Cut lokal zu reduzieren:

- Für jedes Bundle `b` in SCS `c` und jeden Ziel-SCS `d`:
  ```
  delta(b, c → d) = symmetrische_Bindung(b, c) - symmetrische_Bindung(b, d)
  ```
  Die symmetrische Bindung summiert ein- und ausgehende Cardinalities.
- Pro Iteration wird die global beste Verschiebung (maximale
  Cut-Reduktion) ausgeführt, sofern `delta > 0`.
- Iteriert bis zur Konvergenz (max. 2 000 Runden).
- Core→SCS und Overlay→SCS bleiben während Bundle-Shifts konstant
  (diese Bundles sind nicht beweglich).

Diese Phase reduziert den SCS↔SCS-Cut typischerweise um 10–15 %.

#### Phase C: Package-Auslagerung + Re-Clusterung

Die 2 265 SINNVOLL + BEDENKLICH-Packages werden **vor** der
Re-Clusterung aus ihren Bundles entfernt:

- `bundlePakete` wird ohne diese Packages neu berechnet.
- Bundle-Graph wird neu konstruiert (Kanten ohne die ausgelagerten
  Packages).
- **Neuer Leiden-Run** auf dem bereinigten Bundle-Graphen.
- Neue SCS-Struktur entsteht, weil viele Brücken-Kanten verschwinden.

#### Phase D: Package-Shift für PROBLEMATISCH-Outlier

`optimierePaketVerschiebungen` verschiebt einzelne PROBLEMATISCH-Pakete
in das SCS mit der stärksten Bindung, sofern das die Metrik reduziert:

- Für jedes PROBLEMATISCH-Package `p` in SCS `c`:
  ```
  bindung(p, X) = Σ Cardinality(p, x) für x in X (beide Richtungen)
  delta(p, c → t) = bindung(p, c) - bindung(p, t)
  ```
- Verschiebe `p` in das SCS `t`, das `delta` maximiert (positiv).
- `p` verlässt sein Original-Bundle und lebt als **Singleton-Bundle**
  im Ziel-SCS (gespeichert in `packageBundleOverride`).
- Iterativ bis zur Konvergenz (max. 100 Runden).

**Effizienz**: Ein Endpunkt-Index `pkgEdgeIndex` wird einmal
aufgebaut (Package → Nachbar-Liste mit Weights), sodass die
Bindungs-Berechnung O(deg(p)) statt O(E) ist.

#### Phase E: SmallCandidates

`applySmallCandidates` löst SCS auf, die nach der Optimierung unter
`--min-scs-size` (default 100) Packages fallen. Die aufgelösten
Packages werden in den Container `smallCandidates` verschoben — **nicht**
ins Overlay (Overlay bleibt für Cross-Cutting).

### 8. Vorher/Nachher-Metrik

`MetrikDetail` ist die Optimierungsmetrik in drei Komponenten:

| Komponente | Beschreibung |
|---|---|
| **`scsZuScs`** | Beziehungen zwischen zwei verschiedenen Subsystemen (Summe der Cardinalities, beide Richtungen) |
| **`coreZuScs`** | Beziehungen VOM Core IN ein Subsystem. SCS→Core ist erlaubt (SCS darf Core benutzen) |
| **`overlayZuScs`** | Beziehungen VOM Overlay IN ein Subsystem. SCS→Overlay ist erlaubt |

`berechneMetrikDetail(z, ausgegliedertePackages)` aggregiert diese
drei Werte über alle Package-Kanten, die nicht zu ausgelagerten
Packages gehören. Das ist der Wert, den der Report als Vorher/Nachher
anzeigt.

**Performance-kritisch**: `attr("bundle")` muss einmal pro Aufruf
gecached werden, sonst sind es 155 736 × 21 800 = 3,4 Mrd. Map-Lookups
→ Timeout.

## Container-Hierarchie

```
                    ┌──────────────────┐
                    │  Core (1998 Pkg) │  80 %-Abdeckung, gemeinsame Basis
                    └────────┬─────────┘
                             │
              ┌──────────────┴──────────────┐
              │                             │
        ┌─────▼─────┐                ┌──────▼──────┐
        │  Overlay  │                │     SCS     │  9 Subsysteme
        │  (10 Bdl) │                │  (417 Bdl)  │  Geschäftspartner, CRM, ...
        └───────────┘                └──────┬──────┘
                                           │
                                    ┌──────▼─────────┐
                                    │ SmallCandidates│  Pakete aus SCS < minScsSize
                                    └────────────────┘
```

## Verzeichnisstruktur

```
src/main/java/de/tk/dependencyanalyse/packagegraph/
├── Main.java                       # CLI-Einstieg, Pipeline-Orchestrierung
├── gml/
│   └── GmlReader.java              # GML-Parser mit Encoding-Fallback
├── graph/
│   ├── PackageGraph.java           # Wert-Objekt: Graph + Properties
│   ├── PackageGraphBuilder.java    # Baut PackageGraph aus GML
│   └── LeidenClusterAnalyse.java   # Leiden-Wrapper (CWTS-Bibliothek)
├── dsm/
│   ├── DsmSorter.java              # Tarjan-SCC + topologische Sortierung
│   └── LevelCalculator.java        # Longest-Path-Layering
├── analysis/
│   ├── BundleOutlierDetector.java  # Rangbasierte Outlier-Erkennung
│   ├── OutlierAuslagerungsPruefer.java  # SINNVOLL/BEDENKLICH/PROBLEMATISCH
│   ├── OutlierEntry.java           # Outlier-DTO mit Bewertung
│   ├── NodeDependencyLevelCalculator.java
│   ├── StrukturDivergenz.java      # Permutationstest für Divergenz
│   ├── CoreExtraktion.java         # Greedy-Set-Cover für Core (80% + 95%)
│   ├── OverlayAnalyse.java         # Befundung der 10 Overlay-Bundles
│   ├── ScsZerlegung.java           # Hauptklasse: SCS-Clusterung + Optimierung
│   └── SubsystemGraphViz.java      # Self-contained SVG-Subsystem-Graph
└── report/
    └── HtmlReportWriter.java       # Self-contained HTML-Report
```

## Tests

```bash
mvn test
```

Drei Tests prüfen:

- GML-Parsing + Encoding-Fallback
- Leiden-Clusterung auf kleinem Beispielgraphen
- SCS-Optimierung mit Package-Shifts auf kontrolliertem Eingabe-Graphen

## Beispiel-Output

```
[1/7] Lese GML: packageGraph.gml
      -> 21800 Knoten, 174809 Kanten (roh)
[2/7] Baue JGraphT-Graphen
[5/7] DSM-Sortierung
      -> DSM-Reihenfolge gespeichert (erste 3: 4, 5, 10)
[6/7] Berechne Level + nodeDependencyLevel
      -> Level-Range: 1 .. 53
      -> ndl-Range: 1.0 .. 49.5
[7/7] Outlier pro Bundle (Top-5.0 %)
      -> 2420 Outlier in 290 Bundles
      -> Bewertung: 2213 sinnvoll, 52 bedenklich, 155 problematisch

[+] SCS-Optimierung mit Auslagerung (min SCS-Size 100, 2265 Kandidaten, 155 problematisch)
      Ungewoellte Beziehungen (Cardinality):
        Kategorie                          vorher      nachher  Aenderung
        SCS <-> SCS                         92.5k        79.3k     -14.3%
        Core -> SCS                         29.4k        25.7k     -12.5%
        Overlay -> SCS                      99.3k        85.6k     -13.8%
        GESAMT                             221.2k       190.6k     -13.8%
      Reduktion der ungewollten Beziehungen: 13,8%
      Final: 8 SCS + 5 SmallCand-Pakete (2299 Packages ausgelagert, 3959 ms)
```

## Was der Report enthält

1. **Header**: Eingabedatei, Knoten-/Kanten-Anzahl, Bundle-Anzahl
2. **Summary**: Outlier-Statistik, Core-Varianten mit Abdeckung
3. **Subsysteme (SCS-Übersicht)**: Tabelle aller SCS mit Kennzahlen
4. **Subsystem-Graph**: self-contained SVG mit allen SCS, Top-30-Kanten
5. **Overlay & kritische Bundles**: Befundung der 10 Overlay-Bundles
6. **Ungewollte Beziehungen — vorher/nachher**: Detail-Metrik mit Δ%
7. **SmallCandidates**: zu kleine SCS-Pakete
8. **Core-Details**: Bundle- und Package-Zuordnung
9. **SCS-Details**: pro SCS die Bundle-Liste
10. **Outlier pro Bundle**: volle Tabelle mit 4 Bewertungs-Spalten

## Bekannte Grenzen

- Die **Cut-Optimierung konvergiert in einem lokalen Optimum**, das nur
  10–15 % unter dem Leiden-Ergebnis liegt. Topologische Brücken
  (z. B. Overlay↔SCS0 mit 437 k Cardinality) sind strukturell und
  können durch Bundle- oder Package-Verschiebung nicht aufgelöst
  werden.
- Die **SCS-Zielanzahl 10** ist ein Trade-off: mehr SCS erzeugen
  mehr SCS↔SCS-Paare, aber jede einzelne Kante wird kleiner. Die
  Gesamtsumme kann mit mehr SCS sinken — aber das verletzt die
  Anforderung "5–10 SCS".
- **PROBLEMATISCH-Outlier** haben per Definition Rückkanten zu ihrem
  eigenen Bundle. Die Package-Shift-Optimierung kann sie nur dann
  sinnvoll verschieben, wenn die Bindung an ein anderes SCS die
  Bindung ans aktuelle SCS übersteigt — was selten vorkommt.

package de.tk.dependencyanalyse.packagegraph.analysis;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.jgrapht.graph.DefaultWeightedEdge;

import de.tk.dependencyanalyse.packagegraph.graph.PackageGraph;

/**
 * Tiefer gehende Analyse der Overlay-Bundles.
 *
 * <p>Ergaenzt die SCS-Zerlegung um:
 * <ul>
 *   <li>eine Metrik pro Overlay-Bundle (Fan-in, Fan-out, bidirektionale Partner,
 *       Rolle im Gesamtgraph),</li>
 *   <li>eine kurze fachliche Befundung der bekannten kritischen Bundles
 *       (EESSI, Krankenhausaufenthalt, ...),</li>
 *   <li>eine Beschreibung des Overlay-Auswahlkriteriums.</li>
 * </ul>
 *
 * <p><b>Was wird NICHT gemacht.</b> Die Liste der "kritischen Bundles" ist
 * eine manuelle Kuratierung auf Basis der Beobachtungen aus dem Datensatz
 * (siehe {@link #BESCHREIBUNGEN}). Aenderungen am Bundle-Bestand erfordern
 * eine manuelle Pflege dieser Eintraege.
 */
public final class OverlayAnalyse {

    /** Metrik pro Overlay-Bundle. */
    public record Metrik(
            String bundle,
            int packages,
            int fanIn,                       // # distinct bundles, die dieses Bundle aufrufen
            int fanOut,                      // # distinct bundles, die dieses Bundle aufruft
            int bidirektionalePartner,       // davon mit Rueckruf
            double cardSumIn,                // Summe eingehender Cardinalities
            double cardSumOut,               // Summe ausgehender Cardinalities
            String rolle,                    // "Cross-Cutting" | "Hub" | "Orchestrator" | "Schwach gekoppelt"
            String topProdukt,
            String topSchicht) {
    }

    /**
     * Manuelle Befundung der bekannten kritischen Bundles. Key = exakter
     * Bundle-Name (so wie er im GML-Export steht). Value = HTML-Text mit
     * Beobachtung + Empfehlung.
     */
    private static final Map<String, String> BESCHREIBUNGEN;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("datenaustausch-eessi-xml.biz", text(
            "<p><b>EU-Sozialversicherungs-Datenaustausch (EESSI), XML-Variante.</b> ",
            "Das groesste Overlay-Bundle mit 2117 Packages. Es vermittelt zwischen ",
            "allen Fachverfahren und dem EU-Austausch und ist nach SCS6 (Geschaeftspartner, ",
            "8781 Pkg) der zweithaeufigste Knoten im gesamten Graphen.</p>",
            "<p><b>Warum Overlay?</b> EESSI wird von praktisch jedem SCS aufgerufen und ",
            "ruft seinerseits viele zurueck. Es ist <em>cross-cutting</em> &mdash; es gehoert ",
            "fachlich in keine der 9 SCS-Klassen und ist gleichzeitig zu gross, um ignoriert ",
            "zu werden.</p>",
            "<p><b>Empfehlung.</b> Als eigenes Bounded Context <em>EESSI-Datenaustausch</em> ",
            "herausloesen. Mit 3853 Pkg (zusammen mit der binaeren Variante) ist es um Faktor ",
            "~9 groesser als SCS3 (Datenaustausch-Basis) und verdient damit einen gleichrangigen ",
            "Platz in der Zielarchitektur.</p>"));
        m.put("datenaustausch-eessi.biz", text(
            "<p><b>EU-Sozialversicherungs-Datenaustausch (EESSI), binaere Variante.</b> ",
            "Gegenstueck zu datenaustausch-eessi-xml.biz &mdash; gleiche fachliche Domäne, ",
            "anderes Datenformat. 1736 Packages.</p>",
            "<p><b>Warum Overlay?</b> Genau wie die XML-Variante: quer zu allen Fachverfahren. ",
            "Beide Varianten werden parallel betrieben und sind architektonisch dasselbe ",
            "Modul.</p>",
            "<p><b>Empfehlung.</b> Mit datenaustausch-eessi-xml.biz zu einem gemeinsamen ",
            "Subsystem <em>EESSI-Datenaustausch</em> konsolidieren (3853 Pkg).</p>"));
        m.put("krankenhausaufenthalt.biz", text(
            "<p><b>Operatives Fachverfahren fuer Krankenhausaufenthalts-Meldungen.</b> ",
            "1307 Packages, dominantes Produkt <em>Krankenhausaufenthalt</em>. ",
            "Thematisch verwandt mit SCS7 (Unfall, 535 Pkg) und SCS2 (Kur, 675 Pkg), ",
            "aber eigenstaendig genug, um als Verfahren identifizierbar zu sein.</p>",
            "<p><b>Warum Overlay?</b> Es passt in keinen der 9 SCS-Cluster sauber rein &mdash; ",
            "weder zum Geschaeftspartner-Block (Stammdaten, 8781 Pkg), noch zu den anderen ",
            "kleinen Fachverfahren. Mit 1307 Pkg ist es gleichzeitig zu gross, um bei der ",
            "aktuellen SCS-Aufloesung verworfen zu werden.</p>",
            "<p><b>Empfehlung.</b> Entweder als eigenes SCS <em>Krankenhausaufenthalt</em> ",
            "fuehren (Aufloesung erhoehen) oder an SCS7 (Unfall) angliedern. ",
            "Die Variante '--scs-resolution 0.20' (statt 0.35) sollte Krankenhausaufenthalt ",
            "als eigenstaendiges Subsystem ergeben.</p>"));
        m.put("pflegeversicherung.biz.impl", text(
            "<p><b>Pflegeversicherung, fachliche Implementierung.</b> 840 Packages, ",
            "thematisch klar abgegrenzt (Versorgungsarten, Leistungsbescheide), aber ",
            "nicht gross genug fuer ein eigenes Subsystem auf der aktuellen Aufloesung.</p>",
            "<p><b>Warum Overlay?</b> Aehnlich wie Krankenhausaufenthalt: thematisch eigen-",
            "staendig, aber unterhalb der Aufloesungsschwelle. Fan-out moderat, Fan-in ",
            "ueberschaubar.</p>",
            "<p><b>Empfehlung.</b> Bei einer hoeheren SCS-Resolution als eigenes Subsystem ",
            "<em>Pflege</em> fuehrbar. Alternativ an SCS2 (Kur) angliedern, da die ",
            "Versorgungsarten strukturell verwandt sind.</p>"));
        m.put("korrespondenz-datenmodelle.generiert", text(
            "<p><b>Generierte Datenmodelle fuer die Korrespondenz.</b> 818 Packages, ",
            "markiert mit Suffix <em>.generiert</em>. Es sind die automatisch erzeugten ",
            "Klassen fuer Briefe, Bescheide und Schreiben.</p>",
            "<p><b>Warum Overlay?</b> Generierter Code haengt strukturell an allen anderen ",
            "Korrespondenz-Bundles und produziert einen Kanten-Overflow, der die ",
            "Clusterung verwaerft.</p>",
            "<p><b>Empfehlung.</b> Nicht manuell pflegen, sondern aus einer Schema-Definition ",
            "(XSD, OpenAPI) regenerieren. Im Zielzustand sollte generierter Code nicht im ",
            "fachlichen Modul-Graph auftauchen &mdash; er wird als Build-Artefact behandelt.</p>"));
        m.put("kur.biz", text(
            "<p><b>Fachverfahren fuer Kuren (stationaere Heilbehandlungen).</b> 697 Packages, ",
            "thematisch verwandt mit SCS2 (Kur, 675 Pkg).</p>",
            "<p><b>Warum Overlay?</b> Trotz aehnlicher Package-Groesse und gleichem Produkt ",
            "<em>Kur</em> ist <code>kur.biz</code> nicht im SCS2-Cluster gelandet. Das deutet ",
            "darauf hin, dass <code>kur.biz</code> <em>Querschnitts-Funktionalitaet</em> ",
            "liefert (Datenmodelle, allgemeine Kur-Logik), die SCS2 selbst nicht enthaelt.</p>",
            "<p><b>Empfehlung.</b> Mit SCS2 verschmelzen oder als Library dem SCS2-Projekt ",
            "zuordnen. Die Trennung ist technisch, nicht fachlich.</p>"));
        m.put("rechnungswesen.biz", text(
            "<p><b>TKeasy-Rechnungswesen (Vorbezahlung).</b> 370 Packages, ",
            "plattform-spezifisch (TKeasy-Stack).</p>",
            "<p><b>Warum Overlay?</b> Wird von mehreren Fachverfahren als gemeinsamer ",
            "Abrechnungs-Weg benutzt. Hat keinen klaren fachlichen Eigentuemer.</p>",
            "<p><b>Empfehlung.</b> Als eigenes SCS <em>Rechnungswesen</em> fuehren oder ",
            "an SCS0 (CRM, der auch zahlungsnahe Logik enthaelt) anbinden.</p>"));
        m.put("versichertenbestandsfuehrung.biz", text(
            "<p><b>Stammdatenverwaltung Versichertenbestand.</b> 228 Packages, klares ",
            "Stammdaten-Thema.</p>",
            "<p><b>Warum Overlay?</b> Konzeptionell gehoert es zum Bereich Identity &amp; ",
            "Stammdaten. SCS6 (Geschaeftspartner, 8781 Pkg) behandelt ebenfalls Stammdaten, ",
            "ist aber zu gross, um dieses Bundle aufzunehmen.</p>",
            "<p><b>Empfehlung.</b> Bei Aufteilung von SCS6 als eigenes Sub-Modul ",
            "<em>Stammdaten / Versichertenbestand</em> vorschlagen. Bis dahin als Anhaengsel ",
            "an SCS6 (CRM-nahe Logik).</p>"));
        m.put("haertefall.proc", text(
            "<p><b>Haertefallregelung / Belastungsgrenze.</b> 127 Packages, kleines ",
            "Spezialverfahren innerhalb des Leistungsrechts.</p>",
            "<p><b>Warum Overlay?</b> Im aktuellen Graph ohne klare Heimat. Wird von ",
            "mehreren SCS aufgerufen, fuehlt sich aber wie ein Anhaengsel an.</p>",
            "<p><b>Empfehlung.</b> SCS7 (Unfall) oder SCS2 (Kur) zuordnen, beide haben ",
            "aehnliche Belastungs-Logik.</p>"));
        m.put("psychotherapie.biz", text(
            "<p><b>Psychotherapie-spezifisches Fachverfahren.</b> 66 Packages, das ",
            "kleinste Overlay-Bundle.</p>",
            "<p><b>Warum Overlay?</b> Klar abgegrenzt, aber zu klein fuer ein eigenes ",
            "SCS. Hoher Fan-in macht es zum natuerlichen Anhaengsel.</p>",
            "<p><b>Empfehlung.</b> SCS7 (Unfall) zuordnen, da gleicher Behandlungs-",
            "Leistungs-Kontext.</p>"));
        BESCHREIBUNGEN = Collections.unmodifiableMap(m);
    }

    /** Heuristik: Bundle-Rolle nach (Fan-in, Fan-out). */
    private static String klassifiziere(int fanIn, int fanOut) {
        if (fanIn >= 10 && fanOut >= 10) return "Cross-Cutting";
        if (fanIn >= 10 && fanOut <  10) return "Hub (viele Caller)";
        if (fanIn <  10 && fanOut >= 10) return "Orchestrator (viele Callees)";
        return "Schwach gekoppelt";
    }

    /**
     * Berechnet pro Overlay-Bundle die Kopplungsmetrik.
     * Erfordert den vollstaendigen Package-Graphen (pg) und den
     * Package-Id -&gt; Bundle-Name Lookup (bundleOf).
     */
    public List<Metrik> analysiere(ScsZerlegung.Zerlegung z,
                                   PackageGraph pg,
                                   Map<String, String> bundleOf) {
        // Bundle-Graph: src-bundle -> tgt-bundle mit aufsummierter Cardinality.
        Map<String, Map<String, Double>> fanOut = new HashMap<>();
        Map<String, Map<String, Double>> fanIn  = new HashMap<>();
        for (DefaultWeightedEdge e : pg.graph().edgeSet()) {
            String src = pg.graph().getEdgeSource(e);
            String tgt = pg.graph().getEdgeTarget(e);
            if (src.equals(tgt)) continue;
            String sb = bundleOf.get(src);
            String tb = bundleOf.get(tgt);
            if (sb == null || tb == null || sb.equals(tb)) continue;
            double w = pg.graph().getEdgeWeight(e);
            fanOut.computeIfAbsent(sb, k -> new HashMap<>()).merge(tb, w, Double::sum);
            fanIn.computeIfAbsent(tb, k -> new HashMap<>()).merge(sb, w, Double::sum);
        }

        // Package-/Produkt-/Schicht-Histogramme pro Bundle.
        Map<String, Integer> pkgCount = new HashMap<>();
        Map<String, Map<String, Integer>> prodHist = new HashMap<>();
        Map<String, Map<String, Integer>> clsHist  = new HashMap<>();
        for (Map.Entry<String, String> e : bundleOf.entrySet()) {
            String b = e.getValue();
            Map<String, Object> props = pg.nodeProps().get(e.getKey());
            if (props == null) continue;
            pkgCount.merge(b, 1, Integer::sum);
            Object p = props.get("product");
            Object c = props.get("classification");
            if (p != null) prodHist.computeIfAbsent(b, k -> new HashMap<>())
                    .merge(p.toString(), 1, Integer::sum);
            if (c != null) clsHist.computeIfAbsent(b, k -> new HashMap<>())
                    .merge(c.toString(), 1, Integer::sum);
        }

        List<Metrik> result = new ArrayList<>();
        for (String b : z.overlayBundles()) {
            Set<String> fo = fanOut.getOrDefault(b, Map.of()).keySet();
            Set<String> fi = fanIn.getOrDefault(b, Map.of()).keySet();
            Set<String> bidir = new HashSet<>(fo);
            bidir.retainAll(fi);

            double cardIn  = fanIn.getOrDefault(b, Map.of()).values().stream()
                    .mapToDouble(Double::doubleValue).sum();
            double cardOut = fanOut.getOrDefault(b, Map.of()).values().stream()
                    .mapToDouble(Double::doubleValue).sum();

            String topP = prodHist.getOrDefault(b, Map.of()).entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey).orElse("?");
            String topC = clsHist.getOrDefault(b, Map.of()).entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey).orElse("?");

            result.add(new Metrik(
                    b,
                    pkgCount.getOrDefault(b, 0),
                    fi.size(),
                    fo.size(),
                    bidir.size(),
                    cardIn,
                    cardOut,
                    klassifiziere(fi.size(), fo.size()),
                    topP,
                    topC
            ));
        }
        // Sortierung: nach Package-Anzahl absteigend.
        result.sort(Comparator.<Metrik>comparingInt(m -> m.packages()).reversed());
        return result;
    }

    /** Schreibt die Analyse als HTML-Block in {@code w}. */
    public void writeHtml(Writer w, List<Metrik> metrics) throws IOException {
        w.write("<section>\n");
        w.write("<h2>Overlay &amp; Kritische Bundles</h2>\n");

        // ---- Erklaerung der Overlay-Kriterien ----
        w.write("<h3>Wann landet ein Bundle im Overlay?</h3>\n");
        w.write("<p>Die SCS-Zerlegung arbeitet in zwei Schritten. Zuerst wird auf dem "
              + "vollstaendigen Bundle-Graph (438 Knoten, DAG) eine hierarchische "
              + "Clusterung berechnet. Anschliessend werden die Top-10 Bundles nach "
              + "<em>Fan-in</em> (Anzahl Bundles, die dieses Bundle aufrufen) und "
              + "<em>Fan-out</em> (Anzahl Bundles, die dieses Bundle aufruft) als "
              + "<em>Overlay</em> aus dem Clustering herausgenommen. "
              + "Die Idee: ein Bundle, das quer zu vielen anderen liegt, gehoert "
              + "fachlich in <em>kein</em> einzelnes Subsystem &mdash; es ist die "
              + "Signatur eines uebergreifenden Systems.</p>\n");
        w.write("<p>Formal gilt:</p>\n");
        w.write("<ul>\n");
        w.write("<li><b>Fan-in &ge; 10, Fan-out &ge; 10</b>: <em>Cross-Cutting</em> &mdash; "
              + "wird von vielen aufgerufen und ruft selbst viele. Klassisches "
              + "Infrastruktur-Bundle oder Datenaustausch-Kernel.</li>\n");
        w.write("<li><b>Fan-in &ge; 10, Fan-out &lt; 10</b>: <em>Hub</em> &mdash; "
              + "viele Caller, wenige Callees. Eine zentrale Bibliothek, die breit "
              + "genutzt wird.</li>\n");
        w.write("<li><b>Fan-in &lt; 10, Fan-out &ge; 10</b>: <em>Orchestrator</em> &mdash; "
              + "koordiniert viele andere, wird selbst selten aufgerufen. Workflow-"
              + "oder Batch-Steuerung.</li>\n");
        w.write("<li><b>beides &lt; 10</b>: <em>Schwach gekoppelt</em> &mdash; isolierte "
              + "oder zu kleine Bundles, die der Cluster-Algorithmus nicht sauber "
              + "zuordnen konnte.</li>\n");
        w.write("</ul>\n");
        w.write("<p>Die Auswahl selbst ist <b>rangbasiert</b> und nicht schwellwertbasiert "
              + "&mdash; die Top-10 nach absteigendem Fan-in (Tie-Breaker: Fan-out) "
              + "kommen ins Overlay, unabhaengig davon, ob sie die obigen Schwellen "
              + "exakt erreichen. Die Konstante ist in "
              + "<code>ScsZerlegung.OVERLAY_ANZAHL = 10</code> hart codiert; eine "
              + "andere Bundle-Topologie kann eine andere Anzahl sinnvoll machen.</p>\n");

        // ---- Metrik-Tabelle ----
        w.write("<h3>Die 10 Overlay-Bundles im Detail</h3>\n");
        w.write("<table>\n");
        w.write("<thead><tr>"
              + "<th>Bundle</th><th>Pkg</th><th>Fan-In</th><th>Fan-Out</th>"
              + "<th>Bidir.</th><th>Card &rarr;</th><th>Card &larr;</th>"
              + "<th>Rolle</th><th>Top-Produkt</th><th>Top-Schicht</th>"
              + "</tr></thead>\n<tbody>\n");
        for (Metrik m : metrics) {
            w.write("<tr>");
            w.write("<td class=\"name\">" + esc(m.bundle()) + "</td>");
            w.write("<td class=\"dev\">" + m.packages() + "</td>");
            w.write("<td class=\"dev\">" + m.fanIn() + "</td>");
            w.write("<td class=\"dev\">" + m.fanOut() + "</td>");
            w.write("<td class=\"dev\">" + m.bidirektionalePartner() + "</td>");
            w.write("<td class=\"dev\">" + fmt(m.cardSumOut()) + "</td>");
            w.write("<td class=\"dev\">" + fmt(m.cardSumIn()) + "</td>");
            w.write("<td>" + esc(m.rolle()) + "</td>");
            w.write("<td class=\"name\">" + esc(m.topProdukt()) + "</td>");
            w.write("<td class=\"name\">" + esc(kuerzen(m.topSchicht(), 30)) + "</td>");
            w.write("</tr>\n");
        }
        w.write("</tbody>\n</table>\n");

        // ---- Per-Bundle Befundung ----
        w.write("<h3>Befundung der kritischen Bundles</h3>\n");
        int written = 0;
        for (Metrik m : metrics) {
            String text = BESCHREIBUNGEN.get(m.bundle());
            if (text == null) continue;
            w.write("<details");
            if (written < 3) w.write(" open");   // Top-3 zunaechst ausgeklappt
            w.write(">\n");
            w.write("<summary><b>" + esc(m.bundle()) + "</b> &middot; "
                  + m.packages() + " Pkg &middot; Fan-In=" + m.fanIn()
                  + " &middot; Fan-Out=" + m.fanOut()
                  + " &middot; <i>" + esc(m.rolle()) + "</i></summary>\n");
            w.write(text);
            w.write("\n</details>\n");
            written++;
        }
        if (written == 0) {
            w.write("<p class=\"empty\">Keine kuratierten Befunde vorhanden.</p>\n");
        }

        // ---- Cross-cutting Beobachtungen ----
        w.write("<h3>Was das Overlay ueber die Architektur verraet</h3>\n");
        long crossCutting = metrics.stream()
                .filter(m -> "Cross-Cutting".equals(m.rolle())).count();
        long hubs = metrics.stream()
                .filter(m -> m.rolle().startsWith("Hub")).count();
        long totalCards = (long) metrics.stream()
                .mapToDouble(m -> m.cardSumIn() + m.cardSumOut()).sum();
        long overlayBundles = metrics.size();
        long overlayPackages = metrics.stream().mapToInt(Metrik::packages).sum();
        w.write("<p>Das Overlay umfasst <b>" + overlayBundles + " Bundles</b> mit "
              + "<b>" + overlayPackages + " Packages</b>. Die aggregierte Cardinality "
              + "(Summe eingehender und ausgehender Kanten) betraegt <b>"
              + fmt(totalCards) + "</b>.</p>\n");
        w.write("<p><b>" + crossCutting + "</b> Bundles sind <em>Cross-Cutting</em> "
              + "(Fan-in und Fan-out beide &ge; 10) &mdash; das sind die wahren "
              + "Infrastruktur-Knoten der Anwendung. <b>" + hubs + "</b> sind Hub-"
              + "Bundles (viele Caller).</p>\n");
        w.write("<p><b>Zentrale Erkenntnis:</b> Die Anwendung hat keinen sauberen "
              + "Schichten-Stack. Was sich als 'Overlay' darstellt, sind die Punkte, an "
              + "denen die fachliche Schichtung bricht &mdash; insbesondere die "
              + "<em>EESSI-Datenaustausch-Bundles</em> (zusammen 3853 Pkg) und "
              + "<em>krankenhausaufenthalt.biz</em> (1307 Pkg). Diese vier Bundles "
              + "vermitteln zwischen 3 und 9 Subsystemen und sind die ersten Kandidaten "
              + "fuer eine Auftrennung in eigene Bounded Contexts.</p>\n");

        w.write("</section>\n");
    }

    /** Schreibt die Analyse direkt in eine HTML-Datei. */
    public void writeHtmlFile(Path out,
                              ScsZerlegung.Zerlegung z,
                              PackageGraph pg,
                              Map<String, String> bundleOf) throws IOException {
        List<Metrik> metrics = analysiere(z, pg, bundleOf);
        try (Writer w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            writeHtml(w, metrics);
        }
    }

    // ----- Helper -----

    /** Komfort: String-Konkatenation aus mehreren Fragmenten. */
    private static String text(String... parts) {
        return String.join("", parts);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String fmt(double w) {
        if (w >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", w / 1_000_000);
        if (w >= 1000)      return String.format(Locale.ROOT, "%.1fk", w / 1000);
        return String.valueOf((long) w);
    }

    private static String kuerzen(String s, int max) {
        if (s == null) return "?";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
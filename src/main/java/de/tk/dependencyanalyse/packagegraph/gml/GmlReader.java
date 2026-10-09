package de.tk.dependencyanalyse.packagegraph.gml;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimalistischer GML-Reader fuer den Package-Graph.
 *
 * <p>GML erlaubt beliebig verschachtelte Blockstrukturen mit
 * eckigen Klammern. Uns interessieren nur Bloecke vom Typ
 * {@code node} oder {@code edge}, deren Inhalte aus
 * Key/Value-Paaren besteht:
 *
 * <pre>
 *   node
 *   [
 *     id "abc"
 *     name "foo"
 *   ]
 * </pre>
 *
 * <p>Implementierung: rekursiver Descent-Parser. Bei jedem
 * oeffnenden {@code [} wird das vorher gelesene Schluesselwort
 * als Block-Typ interpretiert; nur Typen {@code node} und
 * {@code edge} werden als attributhaltige Bloecke geparst,
 * andere (z. B. {@code graph}) werden rekursiv durchsucht.
 */
public final class GmlReader {

    /** Knoten-Attribut {@code id} (Pflicht). */
    public static final String ATTR_ID = "id";

    /** Knoten-Attribut {@code name}. */
    public static final String ATTR_NAME = "name";

    /** Knoten-Attribut {@code bundle}. */
    public static final String ATTR_BUNDLE = "bundle";

    /** Kanten-Attribut {@code source} (Pflicht). */
    public static final String ATTR_SOURCE = "source";

    /** Kanten-Attribut {@code target} (Pflicht). */
    public static final String ATTR_TARGET = "target";

    /** Liest die Datei und liefert Knoten + Kanten-Attribut-Maps. */
    public static Graph read(Path path) throws IOException {
        List<Map<String, String>> nodes = new ArrayList<>();
        List<Map<String, String>> edges = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(path, StandardCharsets.UTF_8);
             PushbackReader r = new PushbackReader(br, 16)) {
            Parser p = new Parser(r);
            List<Map<String, String>> blocks = p.parseAll();
            for (Map<String, String> b : blocks) {
                String type = b.remove("__type__");
                if ("node".equals(type)) {
                    nodes.add(b);
                } else if ("edge".equals(type)) {
                    edges.add(b);
                }
            }
        }
        return new Graph(nodes, edges);
    }

    /** Ergebnis: alle Knoten- und Kanten-Blocks. */
    public static final class Graph {
        private final List<Map<String, String>> nodes;
        private final List<Map<String, String>> edges;

        Graph(List<Map<String, String>> nodes, List<Map<String, String>> edges) {
            this.nodes = nodes;
            this.edges = edges;
        }

        public List<Map<String, String>> nodes() { return nodes; }
        public List<Map<String, String>> edges() { return edges; }
    }

    // ---------------------------------------------------------------------
    // Parser
    // ---------------------------------------------------------------------

    private static final class Parser {
        private final PushbackReader r;
        private final List<Map<String, String>> blocks = new ArrayList<>();

        Parser(PushbackReader r) {
            this.r = r;
        }

        List<Map<String, String>> parseAll() throws IOException {
            while (true) {
                String kw = readKeyword();
                if (kw == null) return blocks; // EOF
                // skip whitespace, expect '['
                skipWhitespace();
                int c = r.read();
                if (c == -1) return blocks;
                if (c != '[') {
                    // Kein Block — zurueck und weiter.
                    continue;
                }
                Map<String, String> block = parseBlock();
                if (block != null && !block.isEmpty()) {
                    block.put("__type__", kw);
                    blocks.add(block);
                }
            }
        }

        /**
         * Parst einen Block-Inhalt bis zum passenden ']'.
         * Verschachtelte Bloecke werden rekursiv mit-geparst.
         */
        private Map<String, String> parseBlock() throws IOException {
            Map<String, String> attrs = new LinkedHashMap<>();
            while (true) {
                // Skip whitespace
                int c;
                while ((c = r.read()) != -1) {
                    if (!Character.isWhitespace((char) c)) break;
                }
                if (c == -1) return attrs;
                char ch = (char) c;
                if (ch == ']') return attrs;
                // Keyword? Wenn der Char ein Buchstabe ist und das
                // gelesene Wort mit '[' folgt, ist es ein neuer Block.
                if (Character.isLetter(ch)) {
                    String kw = readWord(ch);
                    skipWhitespace();
                    int next = r.read();
                    if (next == -1) return attrs;
                    if (next == '[') {
                        // Verschachtelter Block: rekursiv lesen
                        Map<String, String> nested = parseBlock();
                        if (nested != null && !nested.isEmpty()) {
                            nested.put("__type__", kw);
                            blocks.add(nested);
                        }
                        continue;
                    } else {
                        // Key-Value-Paar
                        skipWhitespaceAfter(r, next);
                        int v = r.read();
                        if (v == -1) return attrs;
                        String value = readValue((char) v);
                        if (value != null) attrs.put(kw, value);
                        continue;
                    }
                } else {
                    // Wert beginnt nicht mit Buchstabe (z. B. Direktive
                    // oder Sonderfall). Wir lesen ihn und verwerfen ihn.
                    readValue(ch);
                }
            }
        }

        private String readKeyword() throws IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = r.read()) != -1) {
                char ch = (char) c;
                if (Character.isWhitespace(ch)) {
                    if (sb.length() > 0) return sb.toString();
                    continue;
                }
                if (ch == '[' || ch == ']') {
                    // Key/Punctuator ohne Keyword — zurueck
                    if (sb.length() > 0) return sb.toString();
                    r.unread(c);
                    return null;
                }
                if (sb.length() == 0 && (Character.isLetter(ch) || ch == '_')) {
                    sb.append(ch);
                } else {
                    sb.append(ch);
                }
            }
            return sb.length() > 0 ? sb.toString() : null;
        }

        private String readWord(char first) throws IOException {
            StringBuilder sb = new StringBuilder();
            sb.append(first);
            int c;
            while ((c = r.read()) != -1) {
                char ch = (char) c;
                if (Character.isWhitespace(ch) || ch == '[' || ch == ']') {
                    r.unread(c);
                    break;
                }
                sb.append(ch);
            }
            return sb.toString();
        }

        private String readValue(char first) throws IOException {
            if (first == '"') return readQuoted();
            StringBuilder sb = new StringBuilder();
            sb.append(first);
            int c;
            while ((c = r.read()) != -1) {
                char ch = (char) c;
                if (Character.isWhitespace(ch) || ch == '[' || ch == ']') {
                    r.unread(c);
                    break;
                }
                sb.append(ch);
            }
            return sb.toString();
        }

        private String readQuoted() throws IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = r.read()) != -1) {
                char ch = (char) c;
                if (ch == '"') return sb.toString();
                if (ch == '\\') {
                    int esc = r.read();
                    if (esc == -1) return sb.toString();
                    sb.append((char) esc);
                } else {
                    sb.append(ch);
                }
            }
            return sb.toString();
        }

        private void skipWhitespace() throws IOException {
            int c;
            while ((c = r.read()) != -1) {
                if (!Character.isWhitespace((char) c)) {
                    r.unread(c);
                    return;
                }
            }
        }

        private void skipWhitespaceAfter(PushbackReader r, int alreadyRead) throws IOException {
            if (!Character.isWhitespace((char) alreadyRead)) {
                r.unread(alreadyRead);
                return;
            }
            int c;
            while ((c = r.read()) != -1) {
                if (!Character.isWhitespace((char) c)) {
                    r.unread(c);
                    return;
                }
            }
        }
    }
}

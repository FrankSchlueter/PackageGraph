package de.tk.dependencyanalyse.packagegraph;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.List;
import java.util.Map;

import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DirectedMultigraph;
import org.junit.jupiter.api.Test;

import de.tk.dependencyanalyse.packagegraph.analysis.NodeDependencyLevelCalculator;
import de.tk.dependencyanalyse.packagegraph.dsm.LevelCalculator;
import de.tk.dependencyanalyse.packagegraph.gml.GmlReader;
import de.tk.dependencyanalyse.packagegraph.graph.PackageGraph;
import de.tk.dependencyanalyse.packagegraph.graph.PackageGraphBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LevelAndNdlTest {

    @Test
    void gmlReader_handlesNestedBlocks() throws IOException {
        String gml = """
            graph [
              node [ id "n1" name "alpha" bundle "b1" ]
              node [ id "n2" name "beta"  bundle "b1" ]
              node [ id "n3" name "gamma" bundle "b1" ]
              edge [ source "n1" target "n2" ]
              edge [ source "n2" target "n3" ]
              edge [ source "n1" target "n1" ]
            ]
            """;
        GmlReader.Graph g = parse(gml);
        assertEquals(3, g.nodes().size());
        assertEquals(3, g.edges().size()); // self-edge wird im raw count mitgezaehlt
    }

    @Test
    void levels_longestPathFromSources() throws IOException {
        // n1 -> n2 -> n3
        // n4 -> n2
        // n5 (isoliert)
        String gml = """
            graph [
              node [ id "n1" name "a" bundle "b" ]
              node [ id "n2" name "b" bundle "b" ]
              node [ id "n3" name "c" bundle "b" ]
              node [ id "n4" name "d" bundle "b" ]
              node [ id "n5" name "e" bundle "b" ]
              edge [ source "n1" target "n2" ]
              edge [ source "n2" target "n3" ]
              edge [ source "n4" target "n2" ]
            ]
            """;
        PackageGraph pg = new PackageGraphBuilder().build(parse(gml));
        Map<String, Integer> levels = new LevelCalculator(pg).compute();
        // n3 (keine deps) = 1; n2 haengt an n3 = 2; n1,n4 haengen an n2 = 3; n5 = 1
        assertEquals(3, levels.get("n1"));
        assertEquals(2, levels.get("n2"));
        assertEquals(1, levels.get("n3"));
        assertEquals(3, levels.get("n4"));
        assertEquals(1, levels.get("n5"));
    }

    @Test
    void ndl_isAverageOfDependencyLevels() throws IOException {
        // n1 -> n2 (L2) und n1 -> n3 (L1)  => ndl = (2+1)/2 = 1.5
        // n4 -> n3 (L1)                    => ndl = 1
        // n5 (isoliert)                     => ndl = 1
        String gml = """
            graph [
              node [ id "n1" bundle "b" ]
              node [ id "n2" bundle "b" ]
              node [ id "n3" bundle "b" ]
              node [ id "n4" bundle "b" ]
              node [ id "n5" bundle "b" ]
              edge [ source "n2" target "n3" ]
              edge [ source "n1" target "n2" ]
              edge [ source "n1" target "n3" ]
              edge [ source "n4" target "n3" ]
            ]
            """;
        PackageGraph pg = new PackageGraphBuilder().build(parse(gml));
        Map<String, Integer> levels = new LevelCalculator(pg).compute();
        Map<String, Double> ndl = new NodeDependencyLevelCalculator(pg, levels).compute();
        // n3 = 1; n2 -> n3 => n2 = 2; n4 -> n3 => n4 = 2; n1 -> n2,n3 => n1 = 3
        assertEquals(1, levels.get("n3"));
        assertEquals(2, levels.get("n2"));
        assertEquals(2, levels.get("n4"));
        assertEquals(3, levels.get("n1"));
        // ndl: n1 avg(2,1)=1.5; n2 avg(1)=1; n4 avg(1)=1; n3=1; n5=1
        assertEquals(1.5, ndl.get("n1"), 1e-9);
        assertEquals(1.0, ndl.get("n2"), 1e-9);
        assertEquals(1.0, ndl.get("n4"), 1e-9);
        assertEquals(1.0, ndl.get("n3"), 1e-9);
        assertEquals(1.0, ndl.get("n5"), 1e-9);
    }

    private static GmlReader.Graph parse(String gml) throws IOException {
        // Same trick as Main uses, via Files... here we go via a temp file.
        java.nio.file.Path tmp = java.nio.file.Files.createTempFile("gml-test", ".gml");
        java.nio.file.Files.writeString(tmp, gml);
        try {
            return GmlReader.read(tmp);
        } finally {
            java.nio.file.Files.deleteIfExists(tmp);
        }
    }
}

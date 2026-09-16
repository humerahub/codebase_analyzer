package org.example.export;

import org.example.model.DependencyEdge;
import org.example.model.LabeledEdge;
import org.jgrapht.Graph;
import org.jgrapht.graph.GraphTypeBuilder;
import org.jgrapht.nio.Attribute;
import org.jgrapht.nio.DefaultAttribute;
import org.jgrapht.nio.dot.DOTExporter;

import java.io.FileWriter;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Turns the collected edges into a JGraphT graph and exports it as a Graphviz DOT file. */
public final class DotGraphExporter {

    private DotGraphExporter() {
    }

    public static Graph<String, LabeledEdge> buildGraph(List<DependencyEdge> edges) {
        // Directed multigraph with self-loops allowed — we have real parallel edges,
        // e.g. both a LAYER3_INJECTS and a LAYER3_RESOLVED edge between the same two
        // nodes (a simple graph would silently collapse those into one edge), and real
        // self-loops, e.g. a recursive method calling itself (LAYER1_CALLS A -> A).
        Graph<String, LabeledEdge> graph = GraphTypeBuilder.<String, LabeledEdge>directed()
                .allowingMultipleEdges(true)
                .allowingSelfLoops(true)
                .edgeClass(LabeledEdge.class)
                .buildGraph();

        for (DependencyEdge e : edges) {
            graph.addVertex(e.from());
            graph.addVertex(e.to());
            graph.addEdge(e.from(), e.to(), new LabeledEdge(e.layer(), e.detail()));
        }

        System.out.println("JGraphT graph built: " + graph.vertexSet().size()
                + " vertices, " + graph.edgeSet().size() + " edges");
        return graph;
    }

    public static void export(Graph<String, LabeledEdge> graph, String outPath) throws IOException {
        DOTExporter<String, LabeledEdge> exporter = new DOTExporter<>(v -> "\"" + v + "\"");

        exporter.setVertexAttributeProvider(v -> {
            Map<String, Attribute> map = new LinkedHashMap<>();
            map.put("label", DefaultAttribute.createAttribute(v));
            map.put("shape", DefaultAttribute.createAttribute("box"));
            return map;
        });

        exporter.setEdgeAttributeProvider(e -> {
            Map<String, Attribute> map = new LinkedHashMap<>();
            map.put("color", DefaultAttribute.createAttribute(e.layer.dotColor));
            map.put("label", DefaultAttribute.createAttribute(e.layer.name().replace("LAYER", "L")));
            map.put("fontsize", DefaultAttribute.createAttribute("8"));
            return map;
        });

        try (FileWriter writer = new FileWriter(outPath)) {
            exporter.exportGraph(graph, writer);
        }
        System.out.println("DOT file written to: " + outPath
                + "  (run: dot -Tpng " + outPath + " -o graph.png)");
    }
}

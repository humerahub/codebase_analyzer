package org.example;

import org.example.analysis.DependencyGraphBuilder;
import org.example.analysis.SpoonModelLoader;
import org.example.config.AppConfig;
import org.example.export.DotGraphExporter;
import org.example.export.JsonGraphExporter;
import org.example.model.DependencyEdge;
import org.example.model.LabeledEdge;
import org.example.report.ConsoleReporter;
import org.jgrapht.Graph;
import spoon.reflect.declaration.CtType;

import java.io.IOException;
import java.util.List;

/**
 * Parses a Java project with Spoon, builds a 3-layer dependency graph
 * (direct calls, inheritance/interface resolution, DI wiring), prints
 * it, and exports it to DOT for Graphviz. No persistence step.
 */
public class Main {

    public static void main(String[] args) throws IOException {
        List<CtType<?>> allTypes = SpoonModelLoader.load(AppConfig.SOURCE_ROOT);
        List<DependencyEdge> edges = new DependencyGraphBuilder().buildEdges(allTypes);

        ConsoleReporter.printByLayer(edges);

        Graph<String, LabeledEdge> graph = DotGraphExporter.buildGraph(edges);
        DotGraphExporter.export(graph, AppConfig.DOT_OUTPUT_PATH);

        JsonGraphExporter.export(edges, AppConfig.JSON_OUTPUT_PATH);
    }
}

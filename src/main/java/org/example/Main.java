package org.example;

import org.example.analysis.DependencyGraphBuilder;
import org.example.analysis.SpoonModelLoader;
import org.example.config.AppConfig;
import org.example.export.DotGraphExporter;
import org.example.model.DependencyEdge;
import org.example.model.LabeledEdge;
import org.example.persistence.GraphRepository;
import org.example.report.ConsoleReporter;
import org.jgrapht.Graph;
import spoon.reflect.declaration.CtType;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

/**
 * Parses a Spring Boot project with Spoon, builds a 3-layer dependency graph
 * (direct calls, inheritance/interface resolution, Spring DI wiring), prints
 * it, persists it into Postgres, and exports it to DOT for Graphviz.
 */
public class Main {

    public static void main(String[] args) throws IOException, SQLException {
        List<CtType<?>> allTypes = SpoonModelLoader.load(AppConfig.SOURCE_ROOT);
        List<DependencyEdge> edges = new DependencyGraphBuilder().buildEdges(allTypes);

        ConsoleReporter.printByLayer(edges);

        GraphRepository repository = new GraphRepository(AppConfig.DB_URL, AppConfig.DB_USER, AppConfig.DB_PASSWORD);
        repository.persist(edges);

        Graph<String, LabeledEdge> graph = DotGraphExporter.buildGraph(edges);
        DotGraphExporter.export(graph, AppConfig.DOT_OUTPUT_PATH);
    }
}

package org.example.report;

import org.example.model.DependencyEdge;
import org.example.model.Layer;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Prints the collected edges to stdout, grouped by layer. */
public final class ConsoleReporter {

    private ConsoleReporter() {
    }

    public static void printByLayer(List<DependencyEdge> edges) {
        Map<Layer, List<DependencyEdge>> byLayer = edges.stream()
                .collect(Collectors.groupingBy(DependencyEdge::layer, () -> new EnumMap<>(Layer.class), Collectors.toList()));

        byLayer.forEach((layer, layerEdges) -> {
            System.out.println("===== " + layer + " (" + layerEdges.size() + " edges) =====");
            layerEdges.forEach(e -> System.out.println("  " + e.from() + " -> " + e.to() + "   [" + e.detail() + "]"));
            System.out.println();
        });

        System.out.println("Total edges collected: " + edges.size());
    }
}

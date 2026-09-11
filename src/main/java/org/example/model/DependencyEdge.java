package org.example.model;

/** One relationship in the dependency graph — the raw material JGraphT consumes. */
public record DependencyEdge(String from, String to, Layer layer, String detail) {
}

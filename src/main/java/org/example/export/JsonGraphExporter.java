package org.example.export;

import org.example.model.DependencyEdge;

import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.TreeSet;

/**
 * Writes the collected edges out as a single JSON file: a flat "nodes" array (every
 * distinct fully-qualified class name) and a flat "edges" array (from/to/layer/detail,
 * one entry per DependencyEdge) — the same shape as the DOT/DB output, just as JSON.
 * Hand-written rather than pulling in a JSON library, since the structure is simple
 * (strings and one level of nesting) and this avoids an extra dependency.
 */
public final class JsonGraphExporter {

    private JsonGraphExporter() {
    }

    public static void export(List<DependencyEdge> edges, String outPath) throws IOException {
        TreeSet<String> nodes = new TreeSet<>();
        for (DependencyEdge e : edges) {
            nodes.add(e.from());
            nodes.add(e.to());
        }

        StringBuilder json = new StringBuilder();
        json.append("{\n  \"nodes\": [\n");
        appendStringArray(json, nodes, "    ");
        json.append("  ],\n  \"edges\": [\n");

        for (int i = 0; i < edges.size(); i++) {
            DependencyEdge e = edges.get(i);
            json.append("    {\n");
            json.append("      \"from\": ").append(quote(e.from())).append(",\n");
            json.append("      \"to\": ").append(quote(e.to())).append(",\n");
            json.append("      \"layer\": ").append(quote(e.layer().name())).append(",\n");
            json.append("      \"detail\": ").append(quote(e.detail())).append("\n");
            json.append("    }").append(i < edges.size() - 1 ? "," : "").append("\n");
        }

        json.append("  ]\n}\n");

        try (FileWriter writer = new FileWriter(outPath)) {
            writer.write(json.toString());
        }
        System.out.println("JSON graph written to: " + outPath
                + "  (" + nodes.size() + " nodes, " + edges.size() + " edges)");
    }

    private static void appendStringArray(StringBuilder json, Iterable<String> values, String indent) {
        List<String> list = new java.util.ArrayList<>();
        values.forEach(list::add);
        for (int i = 0; i < list.size(); i++) {
            json.append(indent).append(quote(list.get(i))).append(i < list.size() - 1 ? "," : "").append("\n");
        }
    }

    // Minimal JSON string escaping — covers the characters that actually show up in class
    // names, method signatures, and our own detail messages (quotes, backslashes, newlines).
    private static String quote(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append("\"").toString();
    }
}

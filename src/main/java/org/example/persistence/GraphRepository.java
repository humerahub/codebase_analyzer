package org.example.persistence;

import org.example.model.DependencyEdge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes the graph into Postgres: a graph_nodes table (one row per unique class/method
 * name) and a graph_edges table (one row per relationship, referencing node ids).
 * Table DDL lives in resources/schema.sql, not inline in this class.
 *
 * Every run TRUNCATEs both tables first and reloads from scratch — a full rebuild,
 * not an incremental update. That's the deliberate starting point; incremental
 * (touch only changed files) is a later upgrade once full rebuild is too slow.
 */
public final class GraphRepository {

    private static final String SCHEMA_RESOURCE = "schema.sql";

    private final String dbUrl;
    private final String dbUser;
    private final String dbPassword;

    public GraphRepository(String dbUrl, String dbUser, String dbPassword) {
        this.dbUrl = dbUrl;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
    }

    public void persist(List<DependencyEdge> edges) throws SQLException {
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
            conn.setAutoCommit(false);

            createSchema(conn);
            truncateTables(conn);

            Map<String, Integer> nodeIds = insertNodes(conn, edges);
            insertEdges(conn, edges, nodeIds);

            conn.commit();
            System.out.println("Persisted " + nodeIds.size() + " nodes and "
                    + edges.size() + " edges to Postgres.");
        }
    }

    private void createSchema(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            for (String statement : readSchemaStatements()) {
                stmt.execute(statement);
            }
        }
    }

    private List<String> readSchemaStatements() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(SCHEMA_RESOURCE + " not found on classpath");
            }
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return List.of(sql.split(";"))
                    .stream()
                    .map(String::strip)
                    .filter(s -> !s.isEmpty())
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + SCHEMA_RESOURCE, e);
        }
    }

    private void truncateTables(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("TRUNCATE TABLE graph_edges");
            stmt.execute("TRUNCATE TABLE graph_nodes RESTART IDENTITY CASCADE");
        }
    }

    private Map<String, Integer> insertNodes(Connection conn, List<DependencyEdge> edges) throws SQLException {
        // Collect every distinct node name across all edges first, so each one is
        // inserted into graph_nodes exactly once.
        Set<String> nodeNames = new LinkedHashSet<>();
        for (DependencyEdge e : edges) {
            nodeNames.add(e.from());
            nodeNames.add(e.to());
        }

        Map<String, Integer> nodeIds = new HashMap<>();
        String insertNodeSql = "INSERT INTO graph_nodes (name) VALUES (?) ON CONFLICT (name) DO NOTHING RETURNING id";
        String selectNodeSql = "SELECT id FROM graph_nodes WHERE name = ?";

        try (PreparedStatement insertNode = conn.prepareStatement(insertNodeSql);
             PreparedStatement selectNode = conn.prepareStatement(selectNodeSql)) {
            for (String name : nodeNames) {
                insertNode.setString(1, name);
                try (ResultSet rs = insertNode.executeQuery()) {
                    if (rs.next()) {
                        nodeIds.put(name, rs.getInt("id"));
                        continue;
                    }
                }
                // ON CONFLICT DO NOTHING returns no row when the name already existed
                // (shouldn't happen right after a TRUNCATE, but handled defensively).
                selectNode.setString(1, name);
                try (ResultSet rs = selectNode.executeQuery()) {
                    if (rs.next()) {
                        nodeIds.put(name, rs.getInt("id"));
                    }
                }
            }
        }
        return nodeIds;
    }

    private void insertEdges(Connection conn, List<DependencyEdge> edges, Map<String, Integer> nodeIds)
            throws SQLException {
        String insertEdgeSql = "INSERT INTO graph_edges (from_node_id, to_node_id, layer, detail) VALUES (?, ?, ?, ?)";
        try (PreparedStatement insertEdge = conn.prepareStatement(insertEdgeSql)) {
            for (DependencyEdge e : edges) {
                insertEdge.setInt(1, nodeIds.get(e.from()));
                insertEdge.setInt(2, nodeIds.get(e.to()));
                insertEdge.setString(3, e.layer().name());
                insertEdge.setString(4, e.detail());
                insertEdge.addBatch();
            }
            insertEdge.executeBatch();
        }
    }
}

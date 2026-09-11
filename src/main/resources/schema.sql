CREATE TABLE IF NOT EXISTS graph_nodes (
    id   SERIAL PRIMARY KEY,
    name VARCHAR(500) NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS graph_edges (
    id            SERIAL PRIMARY KEY,
    from_node_id  INTEGER NOT NULL REFERENCES graph_nodes(id),
    to_node_id    INTEGER NOT NULL REFERENCES graph_nodes(id),
    layer         VARCHAR(50) NOT NULL,
    detail        TEXT,
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

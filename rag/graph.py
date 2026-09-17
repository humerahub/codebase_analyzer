"""
Reads the dependency graph from dependency-graph.json -- the Java side's
JsonGraphExporter, added specifically as a clean machine-readable interface
(the .dot file exists for Graphviz rendering, not for programmatic reads).
No Java re-run needed at Python's end; no database.

As of the Java side's fully-qualified-name refactor, node names here are
fully qualified ("com.example.service.impl.FooServiceImpl"), not simple
names -- chunker.py's id scheme matches this exactly, so no translation
layer is needed between "a graph node" and "a retrievable chunk."
"""

from __future__ import annotations

import json
from dataclasses import dataclass


@dataclass(frozen=True)
class Edge:
    from_id: str
    to_id: str
    layer: str
    detail: str


def load(json_path: str) -> list[Edge]:
    with open(json_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    return [
        Edge(e["from"], e["to"], e["layer"], e["detail"])
        for e in data.get("edges", [])
    ]

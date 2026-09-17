"""
Config for the RAG rehearsal. No secrets here (unlike the Java side's
gitignored application.properties) -- just local paths, so this file is
committed and overridable per-machine via environment variables.

JSON_PATH defaults to dependency-graph.json, produced by the Java side's
JsonGraphExporter -- a clean, machine-readable interface between the two
languages. The Python RAG pipeline reads that file as its graph source
rather than re-running any Java code, or parsing the .dot file (which exists
for Graphviz rendering, not programmatic reads): the Java side owns
AST/DI extraction (Spoon does that far better than any Python Java-parser),
Python owns everything RAG -- chunking, retrieval, and graph-expansion
consume the graph's JSON export as a plain-text interface between the two.
"""

import os

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

SOURCE_ROOT = os.environ.get(
    "RAG_SOURCE_ROOT",
    r"D:\Github\Inventory-Management-System\src\main\java",
)

JSON_PATH = os.environ.get(
    "RAG_JSON_PATH",
    os.path.join(_REPO_ROOT, "output", "inventory-dependency-graph.json"),
)

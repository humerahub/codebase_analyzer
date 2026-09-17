#!/usr/bin/env python
"""
The demo CLI -- ask.py, exactly as named in CLAUDE.md's Task 6.

Usage:
    python ask.py "how does restocking inventory work?"
"""

import sys

from rag import chunker, config, graph
from rag.adapter import QueryAdapter, TriggerSource
from rag.pipeline import RagPipeline


def main() -> None:
    if len(sys.argv) < 2:
        print('usage: python ask.py "<question about the codebase>"', file=sys.stderr)
        sys.exit(1)
    query = " ".join(sys.argv[1:])

    chunks = chunker.extract(config.SOURCE_ROOT)
    edges = graph.load(config.JSON_PATH)

    pipeline = RagPipeline(chunks, edges)
    adapter = QueryAdapter(pipeline)
    adapter.handle(TriggerSource.IDE_CHAT, query)


if __name__ == "__main__":
    main()

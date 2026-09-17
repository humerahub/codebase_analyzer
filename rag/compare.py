"""
Compares TF-IDF retrieval against a real embedding model on the same
chunks and query -- specifically to test the report's hypothesis that the
"restocking inventory" dilution miss was a lexical-scoring artifact, not a
retrieval-approach problem.

Usage:
    python -m rag.compare "how does restocking inventory work"
"""

from __future__ import annotations

import sys
import time

from rag import chunker, config
from rag.embedding_retrieval import EmbeddingIndex
from rag.retrieval import LexicalIndex


def main() -> None:
    if len(sys.argv) < 2:
        print('usage: python -m rag.compare "<question>"', file=sys.stderr)
        sys.exit(1)
    query = " ".join(sys.argv[1:])

    chunks = chunker.extract(config.SOURCE_ROOT)
    print(f"[compare] {len(chunks)} chunks\n")

    t0 = time.perf_counter()
    lexical = LexicalIndex(chunks)
    t1 = time.perf_counter()
    print(f"TF-IDF (lexical) -- index built in {(t1 - t0) * 1000:.0f}ms")
    for sc in lexical.search(query, 5):
        print(f"  {sc.chunk.id:<42} score={sc.score:.3f}")

    print()
    t2 = time.perf_counter()
    embedded = EmbeddingIndex(chunks)
    t3 = time.perf_counter()
    print(f"all-MiniLM-L6-v2 (embedding) -- index built in {(t3 - t2) * 1000:.0f}ms")
    for sc in embedded.search(query, 5):
        print(f"  {sc.chunk.id:<42} score={sc.score:.3f}")


if __name__ == "__main__":
    main()

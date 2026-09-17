"""
A real embedding-model retriever, built specifically to test a hypothesis
from the report: that TF-IDF's lexical dilution (a short, keyword-dense
controller method outscoring the longer method that actually does the work)
is a lexical-scoring artifact, not a retrieval-approach problem -- and that a
real embedding model wouldn't make the same mistake.

Same ScoredChunk interface as LexicalIndex.search(), so the two are directly
swappable for comparison (see rag/compare.py). Uses a small general-purpose
model (all-MiniLM-L6-v2, ~80MB) rather than a code-specific one -- good
enough to test the hypothesis; a code-aware model is the real recommendation
for production (see report decision table), this just needs to be self-hosted
and runnable offline once downloaded, which this already is.
"""

from __future__ import annotations

from dataclasses import dataclass

from sentence_transformers import SentenceTransformer, util

from rag.chunker import Chunk

_MODEL_NAME = "sentence-transformers/all-MiniLM-L6-v2"


@dataclass(frozen=True)
class ScoredChunk:
    chunk: Chunk
    score: float


class EmbeddingIndex:
    def __init__(self, chunks: list[Chunk]):
        self.chunks = chunks
        self._model = SentenceTransformer(_MODEL_NAME)
        self._embeddings = self._model.encode(
            [c.text() for c in chunks], convert_to_tensor=True, show_progress_bar=False
        )

    def search(self, query: str, k: int) -> list[ScoredChunk]:
        qvec = self._model.encode(query, convert_to_tensor=True)
        sims = util.cos_sim(qvec, self._embeddings)[0]
        ranked = sorted(range(len(self.chunks)), key=lambda i: sims[i], reverse=True)
        return [ScoredChunk(self.chunks[i], float(sims[i])) for i in ranked[:k]]

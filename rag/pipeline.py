"""
Chunk, index, retrieve, graph-expand. run() returns structured data;
answer() formats and prints it for the CLI -- kept as two separate methods
so a caller that wants the structured result (rag/compare.py, or any future
caller) doesn't have to scrape printed text to get it.
"""

from __future__ import annotations

import time
from dataclasses import dataclass

from rag.chunker import Chunk
from rag.expander import ExpansionResult, GraphExpander
from rag.graph import Edge
from rag.retrieval import LexicalIndex, ScoredChunk

TOP_K = 3


@dataclass
class AnswerResult:
    query: str
    top_k: list[ScoredChunk]
    expansion: ExpansionResult
    structural: list[tuple[Chunk, str]]  # (chunk, name of the class it belongs to)
    retrieve_ms: float
    expand_ms: float
    total_ms: float

    def context_bundle(self) -> list[Chunk]:
        seeds = [sc.chunk for sc in self.top_k]
        expanded = [h.chunk for h in self.expansion.hits]
        structural = [c for c, _ in self.structural]
        return seeds + expanded + structural


class RagPipeline:
    # First version of the structural-siblings step pulled in *every* sibling
    # method unconditionally: it recovered TransactionServiceImpl.restockInventory()
    # correctly for one query, but on another it flooded the context bundle
    # with 12 unrelated methods. Ranking candidates against the query before
    # including them (reusing the already-fit TF-IDF vocabulary, no re-fit)
    # fixes that -- the same "log explicitly rather than guess" principle as
    # ambiguous-interface flagging, applied to noise instead of correctness.
    _MAX_STRUCTURAL_PER_CLASS = 2
    _MIN_STRUCTURAL_SCORE = 0.03

    def __init__(self, chunks: list[Chunk], edges: list[Edge]):
        t0 = time.perf_counter()
        self.chunks = chunks
        t1 = time.perf_counter()
        self.index = LexicalIndex(chunks)
        t2 = time.perf_counter()
        self._expander = GraphExpander(edges, chunks)
        t3 = time.perf_counter()

        # class name -> its own method chunks. Not a graph edge -- the graph
        # never records "class contains method" -- but structurally certain,
        # not a guess. This is what closes the gap once described as "would
        # need a second hop": no hop reaches a class's own method through
        # graph_edges, at any depth, because no such edge exists.
        self._methods_by_class: dict[str, list[Chunk]] = {}
        for c in chunks:
            if c.id != c.class_name:  # method chunk, id is "Class.method()"
                self._methods_by_class.setdefault(c.class_name, []).append(c)

        self.index_ms = _ms(t0, t3)
        self.chunk_ms = _ms(t0, t1)
        self.tfidf_ms = _ms(t1, t2)
        print(f"[rag] indexed {len(chunks)} chunks in {self.index_ms:.0f}ms "
              f"(chunk={self.chunk_ms:.0f}ms, index={self.tfidf_ms:.0f}ms)")

    def run(self, query: str) -> AnswerResult:
        t0 = time.perf_counter()
        top_k = self.index.search(query, TOP_K)
        t1 = time.perf_counter()

        seeds = [sc.chunk for sc in top_k]
        expansion = self._expander.expand(seeds)
        t2 = time.perf_counter()

        in_play = seeds + [h.chunk for h in expansion.hits]
        structural = self._structural_siblings(in_play, query)

        return AnswerResult(
            query=query, top_k=top_k, expansion=expansion, structural=structural,
            retrieve_ms=_ms(t0, t1), expand_ms=_ms(t1, t2), total_ms=_ms(t0, t2),
        )

    def answer(self, query: str) -> None:
        r = self.run(query)

        print()
        print(f"QUERY: {r.query}")

        print()
        print("-- plain top-k retrieval --------------------------------")
        if not r.top_k:
            print("  (no lexical match -- try different wording)")
        for sc in r.top_k:
            print(f"  {sc.chunk.id:<42} score={sc.score:.3f}  {sc.chunk.citation()}")

        print()
        print("-- graph-expanded: added by one hop through graph_edges --")
        if not r.expansion.hits:
            print("  (nothing new -- retrieved nodes have no outgoing domain edges)")
        for h in r.expansion.hits:
            label = f"+ {h.chunk.id:<40}"
            print(f"  {label} [{h.layer} via {h.from_id}]  {h.chunk.citation()}")
        if r.expansion.flagged_ambiguous:
            print()
            print("  flagged, NOT auto-expanded (ambiguous -- multiple candidate implementations):")
            for f in r.expansion.flagged_ambiguous:
                print(f"    ! {f}")

        print()
        print("-- structural: methods of expanded classes (not a graph edge) --")
        if not r.structural:
            print("  (none of the classes above have methods not already retrieved)")
        for c, of_class in r.structural:
            print(f"  + {c.id:<40} [method of {of_class}]  {c.citation()}")

        print()
        print("-- context bundle for generation (not wired in this rehearsal) --")
        for c in r.context_bundle():
            print(f"  {c.id}  ({c.citation()})")
        print("  [this is real retrieved context, nothing synthesized -- generation")
        print("   needs a self-hosted LLM call here per the team's decision, not built yet]")

        print()
        print(f"retrieve={r.retrieve_ms:.0f}ms  expand={r.expand_ms:.0f}ms  total={r.total_ms:.0f}ms  "
              f"(top-k={len(r.top_k)}, +{len(r.expansion.hits)} from graph, +{len(r.structural)} structural)")

    def _structural_siblings(self, in_play: list[Chunk], query: str) -> list[tuple[Chunk, str]]:
        seen = {c.id for c in in_play}
        out: list[tuple[Chunk, str]] = []
        for c in in_play:
            if c.id != c.class_name:  # only expand from class-level chunks
                continue
            candidates = [s for s in self._methods_by_class.get(c.class_name, []) if s.id not in seen]
            if not candidates:
                continue
            scores = self.index.score_against(query, candidates)
            ranked = sorted(zip(candidates, scores), key=lambda pair: pair[1], reverse=True)
            for sibling, score in ranked[: self._MAX_STRUCTURAL_PER_CLASS]:
                if score < self._MIN_STRUCTURAL_SCORE:
                    continue
                seen.add(sibling.id)
                out.append((sibling, c.class_name))
        return out


def _ms(start: float, end: float) -> float:
    return (end - start) * 1000

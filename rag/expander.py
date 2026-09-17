"""
The differentiator step: given the chunks plain top-k retrieval returned,
walk one hop through the same dependency graph the Java side already built
and pull in what's actually wired to them.

Two policy choices, decided deliberately rather than left implicit (see the
report's decision table):
  - Only domain edge types are followed; generic utility injections
    (ModelMapper, ObjectMapper) are excluded so they don't bury the chunks
    that actually matter.
  - A LAYER3_AMBIGUOUS edge (an interface with more than one implementation)
    is never auto-expanded into a specific implementation -- that would be
    exactly the guess CLAUDE.md warns against. It's surfaced as a flag
    instead. (This test project has zero such edges today -- see the report.)
"""

from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass

from rag.chunker import Chunk
from rag.graph import Edge

_EXPANSION_LAYERS = {
    "LAYER3_INJECTS", "LAYER3_RESOLVED", "LAYER3_BEAN_RESOLVED",
    "LAYER1_CALLS", "LAYER2_IMPLEMENTED_BY", "LAYER2_EXTENDS",
}
_EXCLUDED_TARGETS = {"ModelMapper", "ObjectMapper"}


@dataclass(frozen=True)
class ExpansionHit:
    chunk: Chunk
    from_id: str
    layer: str
    detail: str


@dataclass(frozen=True)
class ExpansionResult:
    hits: list[ExpansionHit]
    flagged_ambiguous: list[str]


class GraphExpander:
    def __init__(self, edges: list[Edge], chunks: list[Chunk]):
        self._by_from: dict[str, list[Edge]] = defaultdict(list)
        for e in edges:
            self._by_from[e.from_id].append(e)
        self._chunks_by_id: dict[str, Chunk] = {c.id: c for c in chunks}

    def expand(self, seeds: list[Chunk]) -> ExpansionResult:
        hits: list[ExpansionHit] = []
        flagged: list[str] = []
        seen = {c.id for c in seeds}

        for seed in seeds:
            for edge in self._by_from.get(seed.id, []):
                if edge.layer == "LAYER3_AMBIGUOUS":
                    flagged.append(f"{seed.id} -> {edge.to_id}  [{edge.detail}]")
                    continue
                if edge.layer not in _EXPANSION_LAYERS:
                    continue
                if edge.to_id in _EXCLUDED_TARGETS:
                    continue
                target = self._chunks_by_id.get(edge.to_id)
                if target is None:  # external type (JDK/framework) -- no chunk to pull in
                    continue
                if target.id in seen:
                    continue
                seen.add(target.id)
                hits.append(ExpansionHit(target, seed.id, edge.layer, edge.detail))

        return ExpansionResult(hits, flagged)

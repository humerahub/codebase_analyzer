"""
The routing layer: which path a query takes is decided entirely by which
trigger fired, not by inspecting the query's content. An IDE chat call
always goes to Layer 1 (RAG); a GitLab MR event always goes to Layer 2
(impact/variant engine). No classification step, because there's nothing
ambiguous to classify.
"""

from __future__ import annotations

from enum import Enum, auto


class TriggerSource(Enum):
    IDE_CHAT = auto()
    GITLAB_MR = auto()


class QueryAdapter:
    def __init__(self, rag_pipeline):
        self._rag_pipeline = rag_pipeline

    def handle(self, source: TriggerSource, payload: str) -> None:
        if source is TriggerSource.IDE_CHAT:
            print("[adapter] IDE chat query received -> routing to Layer 1 (RAG)")
            self._rag_pipeline.answer(payload)
        elif source is TriggerSource.GITLAB_MR:
            print("[adapter] GitLab MR event received -> routing to Layer 2 (impact/variant engine)")
            print("[adapter] Layer 2 isn't built in this rehearsal yet -- needs Task 2 (layer 5")
            print("[adapter] config/profile extraction), Task 3 (upstream traversal) and Task 4")
            print("[adapter] (simulated variants) first. Nothing to run for this trigger yet.")

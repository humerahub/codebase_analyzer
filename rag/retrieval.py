"""
Stand-in for a code-aware embedding model: TF-IDF + cosine similarity over
camelCase-tokenized text, via scikit-learn. This rehearsal doesn't wire a
real embedding model -- a stated placeholder, not a hidden shortcut (see the
open decision on a self-hosted model in the report). What this is meant to
prove -- that graph expansion recovers real dependencies plain top-k misses
-- holds regardless of which scoring function sits at this stage.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.metrics.pairwise import cosine_similarity

from rag.chunker import Chunk

_STOPWORDS = {
    "the", "a", "an", "of", "to", "is", "are", "this", "that", "public", "private",
    "protected", "void", "return", "new", "and", "for", "with", "implements",
    "extends", "package", "class", "interface", "final", "static",
}

_CAMEL_1 = re.compile(r"([a-z0-9])([A-Z])")
_CAMEL_2 = re.compile(r"([A-Z]+)([A-Z][a-z])")
_NON_ALNUM = re.compile(r"[^A-Za-z0-9]+")


def tokenize(text: str) -> list[str]:
    spaced = _CAMEL_1.sub(r"\1 \2", text)
    spaced = _CAMEL_2.sub(r"\1 \2", spaced)
    spaced = _NON_ALNUM.sub(" ", spaced).lower()
    return [_stem(t) for t in spaced.split() if len(t) >= 2 and t not in _STOPWORDS]


def _stem(tok: str) -> str:
    # Deliberately crude suffix-stripping, not a real stemmer -- enough to
    # match "restocking" against "restock" without a dependency for it.
    if tok.endswith("ies") and len(tok) > 4:
        return tok[:-3] + "y"
    if tok.endswith("ing") and len(tok) > 5:
        return tok[:-3]
    if tok.endswith("ed") and len(tok) > 4:
        return tok[:-2]
    if tok.endswith("es") and len(tok) > 4:
        return tok[:-2]
    if tok.endswith("s") and not tok.endswith("ss") and len(tok) > 3:
        return tok[:-1]
    return tok


@dataclass(frozen=True)
class ScoredChunk:
    chunk: Chunk
    score: float


class LexicalIndex:
    def __init__(self, chunks: list[Chunk]):
        self.chunks = chunks
        self._vectorizer = TfidfVectorizer(tokenizer=tokenize, preprocessor=lambda s: s, token_pattern=None)
        self._matrix = self._vectorizer.fit_transform(c.text() for c in chunks)

    def search(self, query: str, k: int) -> list[ScoredChunk]:
        qvec = self._vectorizer.transform([query])
        sims = cosine_similarity(qvec, self._matrix)[0]
        ranked = sorted(range(len(self.chunks)), key=lambda i: sims[i], reverse=True)
        return [ScoredChunk(self.chunks[i], float(sims[i])) for i in ranked[:k] if sims[i] > 0]

    def score_against(self, query: str, chunks: list[Chunk]) -> list[float]:
        """Scores arbitrary chunks against a query using this index's already-fit
        vocabulary, without re-fitting. Used to rank candidates that came from
        somewhere other than search() -- e.g. structural siblings -- instead of
        including all of them unconditionally."""
        if not chunks:
            return []
        qvec = self._vectorizer.transform([query])
        cvecs = self._vectorizer.transform([c.text() for c in chunks])
        return list(cosine_similarity(qvec, cvecs)[0])

"""
Structural chunking: one chunk per method, plus one class-level summary chunk
per type (always) -- mirrors the CLAUDE.md header format (package, type +
annotations, implements/extends) and the id scheme the existing Java graph
uses ("fully.qualified.ClassName" / "fully.qualified.ClassName.methodName()"),
so graph edges from dependency-graph.json join straight onto these chunks
with no separate mapping table.

Uses javalang (pure-Python Java parser) instead of Spoon -- weaker type
resolution, but the AST + positions is all structural chunking needs. Method
body extraction uses a hand-rolled brace matcher (see _find_block_end_line)
since javalang gives a node's start position but not its end line.
"""

from __future__ import annotations

import bisect
import os
from dataclasses import dataclass

import javalang


@dataclass(frozen=True)
class Chunk:
    id: str
    kind: str  # "class" | "interface" | "enum"
    package: str
    class_name: str
    header: str
    body: str
    file_path: str
    start_line: int
    end_line: int

    def text(self) -> str:
        return self.header + "\n\n" + self.body

    def citation(self) -> str:
        return f"{self.file_path}:{self.start_line}-{self.end_line}"


def extract(source_root: str) -> list[Chunk]:
    chunks: list[Chunk] = []
    for dirpath, _dirs, files in os.walk(source_root):
        for fname in files:
            if not fname.endswith(".java"):
                continue
            path = os.path.join(dirpath, fname)
            chunks.extend(_extract_file(path))
    return chunks


def _extract_file(path: str) -> list[Chunk]:
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        text = f.read()

    try:
        tree = javalang.parse.parse(text)
    except javalang.parser.JavaSyntaxError:
        # Log and skip rather than guess at a broken parse.
        print(f"[chunker] skipping {path}: javalang could not parse it")
        return []

    line_starts = _line_starts(text)
    package = tree.package.name if tree.package else "(default package)"

    out: list[Chunk] = []
    for type_decl in tree.types:
        out.append(_class_chunk(type_decl, package, path, text, line_starts))
        for method in _methods(type_decl):
            if method.body is None:  # interface/abstract method, no body to chunk
                continue
            out.append(_method_chunk(type_decl, method, package, path, text, line_starts))
    return out


# ---------- chunk builders ----------

def _qualified_name(package: str, simple_name: str) -> str:
    # Matches Spoon's CtType.getQualifiedName() on the Java side, which is what
    # the graph's node identity is now built from (fully-qualified, not simple
    # names -- see DependencyGraphBuilder.java). Chunk ids have to match that
    # exactly, or graph expansion silently stops finding anything at all.
    return simple_name if package == "(default package)" else f"{package}.{simple_name}"


def _class_chunk(type_decl, package, path, text, line_starts) -> Chunk:
    header = _header(type_decl, package)
    body = _class_summary(type_decl)
    start = type_decl.position.line if type_decl.position else 1
    end = _find_block_end_line(text, line_starts, start) or start
    qname = _qualified_name(package, type_decl.name)
    return Chunk(qname, _kind(type_decl), package, qname, header, body, path, start, end)


def _method_chunk(type_decl, method, package, path, text, line_starts) -> Chunk:
    header = _header(type_decl, package)
    start = method.position.line if method.position else 1
    end = _find_block_end_line(text, line_starts, start) or start
    body = _slice(text, line_starts, start, end)
    qname = _qualified_name(package, type_decl.name)
    chunk_id = f"{qname}.{method.name}()"
    return Chunk(chunk_id, _kind(type_decl), package, qname, header, body, path, start, end)


def _class_summary(type_decl) -> str:
    lines = []
    fields = _fields(type_decl)
    if fields:
        lines.append("fields:")
        for f in fields:
            type_name = _type_name(f.type)
            for decl in f.declarators:
                lines.append(f"  {type_name} {decl.name}")
    methods = _methods(type_decl)
    if methods:
        lines.append("methods:")
        for m in methods:
            lines.append(f"  {_signature(m)}")
    return "\n".join(lines)


def _header(type_decl, package: str) -> str:
    lines = [f"package {package}"]
    ann = _annotation_list(getattr(type_decl, "annotations", None))
    kind_line = f"{_kind(type_decl)} {type_decl.name}"
    if ann:
        kind_line += f"  [{ann}]"
    lines.append(kind_line)

    if isinstance(type_decl, javalang.tree.ClassDeclaration):
        if type_decl.implements:
            lines.append("implements " + ", ".join(_type_name(t) for t in type_decl.implements))
        if type_decl.extends:
            lines.append("extends " + _type_name(type_decl.extends))
    elif isinstance(type_decl, javalang.tree.InterfaceDeclaration):
        if type_decl.extends:
            lines.append("extends " + ", ".join(_type_name(t) for t in type_decl.extends))
    elif isinstance(type_decl, javalang.tree.EnumDeclaration):
        if type_decl.implements:
            lines.append("implements " + ", ".join(_type_name(t) for t in type_decl.implements))

    return "\n".join(lines)


# ---------- javalang helpers ----------

def _kind(type_decl) -> str:
    if isinstance(type_decl, javalang.tree.InterfaceDeclaration):
        return "interface"
    if isinstance(type_decl, javalang.tree.EnumDeclaration):
        return "enum"
    return "class"


def _methods(type_decl):
    if hasattr(type_decl, "methods"):
        return list(type_decl.methods)
    decls = getattr(getattr(type_decl, "body", None), "declarations", None) or []
    return [d for d in decls if isinstance(d, javalang.tree.MethodDeclaration)]


def _fields(type_decl):
    if hasattr(type_decl, "fields"):
        return list(type_decl.fields)
    decls = getattr(getattr(type_decl, "body", None), "declarations", None) or []
    return [d for d in decls if isinstance(d, javalang.tree.FieldDeclaration)]


def _type_name(type_node) -> str:
    return getattr(type_node, "name", None) or type_node.__class__.__name__


def _signature(method) -> str:
    ret = _type_name(method.return_type) if method.return_type else "void"
    params = ", ".join(f"{_type_name(p.type)} {p.name}" for p in method.parameters)
    return f"{ret} {method.name}({params})"


def _annotation_list(annotations) -> str:
    if not annotations:
        return ""
    return ", ".join(_format_annotation(a) for a in annotations)


def _format_annotation(a) -> str:
    if a.element is None:
        return f"@{a.name}"
    if isinstance(a.element, list):
        parts = []
        for item in a.element:
            if hasattr(item, "name") and hasattr(item, "value"):
                parts.append(f"{item.name}={_literal(item.value)}")
            else:
                parts.append(_literal(item))
        return f"@{a.name}(" + ", ".join(parts) + ")"
    return f"@{a.name}({_literal(a.element)})"


def _literal(node) -> str:
    return str(getattr(node, "value", node))


# ---------- source-position helpers ----------

def _line_starts(text: str) -> list[int]:
    starts = [0]
    for i, c in enumerate(text):
        if c == "\n":
            starts.append(i + 1)
    return starts


def _line_of(offset: int, line_starts: list[int]) -> int:
    return bisect.bisect_right(line_starts, offset)  # 1-based


def _slice(text: str, line_starts: list[int], start_line: int, end_line: int) -> str:
    start = line_starts[start_line - 1]
    end = line_starts[end_line] - 1 if end_line < len(line_starts) else len(text)
    return text[start:end].rstrip("\n")


def _find_block_end_line(text: str, line_starts: list[int], decl_start_line: int) -> int | None:
    """
    Scans forward from a declaration's start line for its first '{' (the
    body/class opening brace), then tracks nesting depth -- skipping string,
    char, and comment content -- until that brace's match closes. Returns the
    1-based line of the matching '}'. Doesn't special-case Java 15+ text
    blocks (\"\"\"...\"\"\"), a known, narrow limitation for this codebase.
    """
    i = line_starts[decl_start_line - 1]
    n = len(text)
    depth = 0
    started = False
    while i < n:
        c = text[i]
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            j = text.find("\n", i)
            i = n if j == -1 else j + 1
            continue
        if c == "/" and i + 1 < n and text[i + 1] == "*":
            j = text.find("*/", i + 2)
            i = n if j == -1 else j + 2
            continue
        if c == '"':
            i += 1
            while i < n and text[i] != '"':
                i += 2 if text[i] == "\\" else 1
            i += 1
            continue
        if c == "'":
            i += 1
            while i < n and text[i] != "'":
                i += 2 if text[i] == "\\" else 1
            i += 1
            continue
        if c == "{":
            depth += 1
            started = True
            i += 1
            continue
        if c == "}":
            depth -= 1
            i += 1
            if started and depth == 0:
                return _line_of(i - 1, line_starts)
            continue
        i += 1
    return None

#!/usr/bin/env python3
# This file is part of Headway.
# Copyright (C) 2026 The Headway Authors
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Two compile errors that CI is otherwise the only place to find.
#
# The Android modules cannot be compiled without the SDK, so a mistake in
# `app/` costs a full CI round trip to discover -- and both of the shapes
# checked here have cost one.
#
#   1. Two functions with the same name and the same parameter types in the
#      same scope. Kotlin rejects it as "conflicting overloads" AND reports an
#      ambiguity at every call site, so one duplicate produces a page of errors
#      that never names the cause.
#
#   2. A `"..."` left open at a newline. Kotlin only lets `"""` span lines, so
#      this is always an error -- and it reports as a pile of "unresolved
#      reference" on the lines *after* it, none of which mention the string.
#
#   3. A project singleton used as `Name.member` from another package without
#      an import. CI reports that as "unresolved reference" on the *member*
#      access, plus a cascade of inference failures under it, none of which
#      names the missing import.
#
#   4. `import` of an android name that is @hide and so absent from the public
#      SDK. Only the ones this project has actually tripped over are listed,
#      because a real answer needs android.jar and this has to run anywhere.
#
# Not a substitute for compiling. A substitute for finding out an hour later.
import re
import sys
from pathlib import Path

MODULES = (
    "app", "core-audio", "core-dash", "core-input", "core-protocol",
    "core-transport", "core-video", "core-voice", "headunit-emulator",
)

# Names that do not exist in the public SDK. Each was a real build failure.
NOT_PUBLIC = (
    "android.view.Display.TYPE_",
    "android.view.Display.FLAG_TRUSTED",
    "android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED",
)

TYPE = re.compile(
    r"^\s*(?:@\w+\s+)*"
    r"(?:private |internal |public |protected |abstract |open |sealed |data |value |inner |enum |annotation )*"
    r"(?:companion\s+object|class|object|interface)\b\s*([A-Za-z_][A-Za-z0-9_]*)?"
)
FUN = re.compile(
    r"^\s*(?:private |internal |public |protected |inline |suspend |override |open |external |operator |infix |tailrec )*"
    r"fun\s+(?:<[^>]*>\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*\((.*)\)"
)


def strip_noise(line: str) -> str:
    """Good enough: drops line comments and string bodies before counting braces."""
    out, index, in_string = [], 0, False
    while index < len(line):
        char = line[index]
        if not in_string and line.startswith("//", index):
            break
        if char == '"':
            in_string = not in_string
        elif char == "\\" and in_string:
            index += 2
            continue
        elif not in_string:
            out.append(char)
        index += 1
    return "".join(out)


def split_top_level(params: str) -> list:
    """Splits a parameter list on the commas that are not inside <>, () or []."""
    parts, balance, segment = [], 0, ""
    for char in params:
        if char in "<([":
            balance += 1
        elif char in ">)]":
            balance -= 1
        if char == "," and balance == 0:
            parts.append(segment)
            segment = ""
        else:
            segment += char
    parts.append(segment)
    return parts


def unterminated_strings(path: Path) -> list:
    """Every line where a one-quote string literal is left open at the newline.

    A one-quote Kotlin string may not span lines; only a triple-quoted one may.
    So an open quote at a newline is always an error -- and one produced by a
    generated edit, where an escaped newline became a real one on the way in,
    reports as a cascade of unresolved references on the lines *after* it, none
    of which mention the string. This names the string.

    A small lexer with a mode stack rather than a regex or a quote count. Three
    things defeat counting, and this codebase has all three in quantity:
    comments full of quoted prose, apostrophes in that prose, and `${...}`
    interpolations that contain string literals of their own -- the first
    version of this check reported two false positives, both from a nested
    string inside an interpolation desynchronising everything after it.
    """
    text = path.read_text(encoding="utf-8")
    found = []
    # Each entry is (kind, brace_depth). "code" is the top of the file and also
    # the inside of every ${...}; "str" and "raw" are the two string forms.
    stack = [["code", 0]]
    index, line = 0, 1
    while index < len(text):
        kind, braces = stack[-1]
        char = text[index]
        two, three = text[index:index + 2], text[index:index + 3]

        if char == "\n":
            if kind == "str":
                found.append(f"{path}:{line}: string literal is not closed on this line")
                stack.pop()
            line += 1
            index += 1
            continue

        if kind == "line_comment":
            index += 1
            continue

        if kind == "block_comment":
            if two == "/*":
                stack.append(["block_comment", 0])
                index += 2
            elif two == "*/":
                stack.pop()
                index += 2
            else:
                index += 1
            continue

        if kind in ("str", "raw"):
            if kind == "str" and char == "\\":
                index += 2
                continue
            if two == "${":
                stack.append(["code", 0])
                index += 2
                continue
            if kind == "raw" and three == '\"\"\"':
                stack.pop()
                index += 3
                continue
            if kind == "str" and char == '"':
                stack.pop()
                index += 1
                continue
            index += 1
            continue

        # kind == "code"
        if two == "//":
            stack.append(["line_comment", 0])
            index += 2
            continue
        if two == "/*":
            stack.append(["block_comment", 0])
            index += 2
            continue
        if three == '\"\"\"':
            stack.append(["raw", 0])
            index += 3
            continue
        if char == '"':
            stack.append(["str", 0])
            index += 1
            continue
        if char == "'":
            # A char literal, which also may not span a line. Skipped rather
            # than reported: a stray apostrophe in code is a syntax error the
            # compiler will name far better than this can.
            index += 1
            while index < len(text) and text[index] not in ("'", "\n"):
                index += 2 if text[index] == "\\" else 1
            if index < len(text) and text[index] == "'":
                index += 1
            continue
        if char == "{":
            stack[-1][1] = braces + 1
            index += 1
            continue
        if char == "}":
            # The close of an interpolation is the one that takes the depth
            # below where the ${ started, which is zero for that frame.
            if braces == 0 and len(stack) > 1:
                stack.pop()
            else:
                stack[-1][1] = braces - 1
            index += 1
            continue
        index += 1
    return found


# Project singletons that are used as `Name.member` and must be imported when
# they live in another package. Curated rather than inferred: an inferred list
# produces false positives on every local variable that happens to start with a
# capital, and a false positive in a build gate is worse than a miss.
SINGLETONS = {
    "CarStyle": "dev.headway.app.dash.tiles",
    "CarSheet": "dev.headway.app.dash",
    "CarGlyph": "dev.headway.app.dash",
    "CarShell": "dev.headway.app.dash",
    "Headway": "dev.headway.app.ui.theme",
    "Phone": "dev.headway.app.ui.theme",
    "HeadwaySettings": "dev.headway.app.ui",
    "HeadwayTheme": "dev.headway.app.ui.theme",
    "SessionLog": "dev.headway.app.log",
    "AllowedApps": "dev.headway.dash",
    "CarUiScale": "dev.headway.dash",
    "CarUnits": "dev.headway.dash",
    "CornerStyle": "dev.headway.dash",
    "OverlaySpot": "dev.headway.dash",
    "PaneKind": "dev.headway.dash",
    "TabIcon": "dev.headway.dash",
    "CarSensors": "dev.headway.protocol.channel",
}


def unimported(path: Path) -> list:
    """Every project singleton used as `Name.member` without being reachable.

    "Unresolved reference 'CarStyle'" from CI is a whole page of cascading
    errors -- every member access under it fails too, and every lambda whose
    type depended on it -- none of which name the missing import. This names it.

    Only the curated `SINGLETONS` are checked, and only when the file's own
    package differs from theirs, so a same-package use needs nothing.
    """
    text = path.read_text(encoding="utf-8")
    package = ""
    for line in text.split("\n"):
        if line.startswith("package "):
            package = line[len("package "):].strip()
            break
    # Comments and string bodies blanked first. Prose in this codebase is full
    # of sentences that end "... over Headway." and a naive match reported every
    # one of them -- which is how a check gets deleted rather than fixed.
    code = code_only(text)
    found = []
    for name, home in SINGLETONS.items():
        if package == home:
            continue
        if f"import {home}.{name}" in text:
            continue
        # Word-boundary, followed by a dot: `CarStyle.panel`, not `MyCarStyle`.
        for number, line in enumerate(code.split("\n"), start=1):
            if re.search(rf"(?<![A-Za-z0-9_.]){name}\.", line):
                found.append(
                    f"{path}:{number}: {name} is used but not imported "
                    f"(it lives in {home})"
                )
                break
    return found


def code_only(text: str) -> str:
    """The same text with comments and string bodies blanked, newlines kept.

    Line numbers survive, so a finding still points at the right line. Uses the
    same mode stack as [unterminated_strings] because the same three things --
    nested block comments, escapes, and `${...}` inside a string -- defeat
    anything simpler.
    """
    out = []
    stack = [["code", 0]]
    index = 0
    while index < len(text):
        kind, braces = stack[-1]
        char = text[index]
        two, three = text[index:index + 2], text[index:index + 3]

        if char == "\n":
            if kind in ("line_comment", "str"):
                stack.pop()
            out.append("\n")
            index += 1
            continue

        if kind in ("line_comment", "block_comment", "str", "raw"):
            if kind == "block_comment" and two == "/*":
                stack.append(["block_comment", 0])
                out.append("  ")
                index += 2
                continue
            if kind == "block_comment" and two == "*/":
                stack.pop()
                out.append("  ")
                index += 2
                continue
            if kind == "str" and char == "\\":
                out.append("  ")
                index += 2
                continue
            if kind in ("str", "raw") and two == "${":
                stack.append(["code", 0])
                out.append("  ")
                index += 2
                continue
            if kind == "raw" and three == '\"\"\"':
                stack.pop()
                out.append("   ")
                index += 3
                continue
            if kind == "str" and char == '"':
                stack.pop()
                out.append(" ")
                index += 1
                continue
            out.append(" ")
            index += 1
            continue

        # kind == "code"
        if two == "//":
            stack.append(["line_comment", 0])
            out.append("  ")
            index += 2
            continue
        if two == "/*":
            stack.append(["block_comment", 0])
            out.append("  ")
            index += 2
            continue
        if three == '\"\"\"':
            stack.append(["raw", 0])
            out.append("   ")
            index += 3
            continue
        if char == '"':
            stack.append(["str", 0])
            out.append(" ")
            index += 1
            continue
        if char == "{":
            stack[-1][1] = braces + 1
        elif char == "}":
            if braces == 0 and len(stack) > 1:
                stack.pop()
            else:
                stack[-1][1] = braces - 1
        out.append(char)
        index += 1
    return "".join(out)


def conflicts(path: Path) -> list:
    """Every function this file declares twice in one scope."""
    found, seen, stack, depth, pending = [], {}, [], 0, None
    for number, raw in enumerate(path.read_text(encoding="utf-8").split("\n"), start=1):
        line = strip_noise(raw)
        function = FUN.match(raw)
        declaration = TYPE.match(raw)
        if function and line.count("(") == line.count(")"):
            name, params = function.group(1), function.group(2)
            types = tuple(
                part.split(":", 1)[1].split("=")[0].strip()
                for part in split_top_level(params)
                if ":" in part
            )
            key = (tuple(entry for _, entry in stack), name, types)
            if key in seen:
                where = ".".join(entry for _, entry in stack) or "<file>"
                found.append(
                    f"{path}:{number}: fun {name}({', '.join(types)}) in {where} "
                    f"is already declared at line {seen[key]}"
                )
            else:
                seen[key] = number
        elif declaration:
            pending = declaration.group(1) or "companion"
        opens, closes = line.count("{"), line.count("}")
        for _ in range(opens):
            # An anonymous scope is named after the line that opened it, so two
            # sibling `object : Callback() { }` blocks -- of which this codebase
            # has many, each overriding the same method -- are different scopes
            # rather than one. Without that every override was a duplicate of
            # its neighbour and the check reported nothing but noise.
            stack.append((depth, pending or f"<anon@{number}>"))
            pending = None
            depth += 1
        for _ in range(closes):
            depth = max(0, depth - 1)
            if stack:
                stack.pop()
        if opens or closes:
            pending = None
    return found


# Top-level `object` names, by the file that declares them. Built at run time
# rather than curated, because it is only ever used to answer "is this name a
# *top-level* declaration", and a name missing from it simply means no check.
def top_level_objects() -> dict:
    found = {}
    for module in MODULES:
        for path in Path(module).rglob("*.kt"):
            for raw in code_only(path.read_text(encoding="utf-8")).split("\n"):
                match = TOP_LEVEL_OBJECT.match(raw)
                if match:
                    found[match.group(1)] = path
    return found


TOP_LEVEL_OBJECT = re.compile(r"^(?:internal |public )?object ([A-Z]\w*)\b")

# `Outer.Inner.MEMBER` where both names are top-level objects.
NESTED_USE = re.compile(r"\b([A-Z]\w*)\.([A-Z]\w*)\.[A-Za-z_]")


def misnested(path: Path, objects: dict) -> list:
    """Every `A.B.C` that reads B as nested in A when B is top-level.

    Kotlin reports this as "unresolved reference", which names the member and
    not the reason -- and the reason is invisible at the call site, because
    `MediaPlaybackChannel.MediaPlaybackMessageId.MEDIA_PLAYBACK_INPUT` looks
    exactly like a legal nested access. It only fails because those two objects
    are siblings in one file rather than one inside the other. That shape cost
    a CI round, so it is worth a rule.

    Conservative on purpose: it fires only when *both* names are top-level
    `object` declarations somewhere in this project. Same-file pairs are *not*
    exempt -- the pattern above anchors at column 0, so anything it matched is
    top-level by construction and cannot be nested inside anything, and the
    case that cost the CI round was exactly two siblings in one file.
    """
    problems = []
    for number, raw in enumerate(code_only(path.read_text(encoding="utf-8")).split("\n"), 1):
        for outer, inner in NESTED_USE.findall(raw):
            if outer not in objects or inner not in objects:
                continue
            problems.append(
                f"{path}:{number}: {outer}.{inner} reads as nested, but {inner} is a "
                f"top-level object in {objects[inner]} -- import and use it directly"
            )
    return problems


TOP_LEVEL_TYPE = re.compile(
    r"^(?:internal |private |public )?(?:open |abstract |sealed |data )*"
    r"(?:class|object|interface|enum class) (\w+)"
)

PRIVATE_CONST = re.compile(r"^\s+private const val ([A-Z][A-Z0-9_]*)\b")

WORD = re.compile(r"\b([A-Z][A-Z0-9_]{2,})\b")


def stranded_constants(path: Path) -> list:
    """Every `private const val` used from a different top-level type in one file.

    A file holding several classes gives each its own companion, and a constant
    added to the wrong one compiles nowhere and reads fine everywhere -- the
    declaration and the use are hundreds of lines apart and both look right.
    That shape has now cost three CI rounds in this repository, always in the
    same 2500-line file.

    Private is the whole point: a `private const val` in one type's companion is
    invisible to its siblings, while an internal or public one is not. Only
    same-file uses are considered, so a constant that is genuinely shared by
    being non-private never appears here.
    """
    text = code_only(path.read_text(encoding="utf-8"))
    lines = text.split("\n")
    owner = {}
    current = None
    for raw in lines:
        match = TOP_LEVEL_TYPE.match(raw)
        if match:
            current = match.group(1)
            continue
        constant = PRIVATE_CONST.match(raw)
        if constant and current:
            # A *set* of owners, not the first one. Two classes in one file may
            # each declare a private constant of the same name -- ContentTiles
            # has two NO_ACCESS_HINTs, deliberately, because the two panes word
            # the same absence differently. Keeping only the first owner made
            # every use in the second class look stranded.
            owner.setdefault(constant.group(1), set()).add(current)
    if not owner:
        return []
    problems = []
    current = None
    for number, raw in enumerate(lines, 1):
        match = TOP_LEVEL_TYPE.match(raw)
        if match:
            current = match.group(1)
            continue
        if PRIVATE_CONST.match(raw) or current is None:
            continue
        for name in WORD.findall(raw):
            homes = owner.get(name)
            if homes and current not in homes:
                where = " and ".join(sorted(homes))
                problems.append(
                    f"{path}:{number}: {name} is a private const in {where}, and this is "
                    f"{current} -- it resolves to nothing here"
                )
    return problems


# Assertion libraries that are not on a source set's test classpath, by the
# path fragment that identifies it. `:app`'s unit tests are JUnit 5 with the
# Jupiter assertions and no kotlin-test dependency; the jvm-only modules do
# have kotlin-test, so this is per source set rather than global.
TEST_LIBRARY_TRAPS = {
    "app/src/test/": ("kotlin.test", "org.junit.jupiter.api.Assertions"),
}


def wrong_test_library(path: Path) -> list:
    """Every test importing an assertion library its module does not have.

    `:app`'s unit tests are the only Kotlin in this repository that no local
    gradle task compiles -- `tools/jvm-only` covers the core modules and the
    emulator, and everything else waits for CI. So a one-line import mistake in
    a test costs a full CI round, which is what `import kotlin.test.assertTrue`
    did: the module has JUnit 5 and no kotlin-test, and the report was ten
    "Unresolved reference" lines pointing at the assertions rather than at the
    import.
    """
    text = path.as_posix()
    problems = []
    for fragment, (banned, instead) in TEST_LIBRARY_TRAPS.items():
        if fragment not in text:
            continue
        for number, raw in enumerate(path.read_text(encoding="utf-8").split("\n"), 1):
            if raw.startswith(f"import {banned}."):
                problems.append(
                    f"{path}:{number}: {raw.strip()} -- this source set has no {banned}; "
                    f"use {instead}"
                )
    return problems


def main() -> int:
    problems = []
    objects = top_level_objects()
    for module in MODULES:
        for path in sorted(Path(module).rglob("*.kt")):
            problems += conflicts(path)
            problems += unterminated_strings(path)
            problems += unimported(path)
            problems += misnested(path, objects)
            problems += stranded_constants(path)
            problems += wrong_test_library(path)
            text = path.read_text(encoding="utf-8")
            for line in text.split("\n"):
                if not line.startswith("import "):
                    continue
                for name in NOT_PUBLIC:
                    if line[len("import "):].startswith(name):
                        problems.append(
                            f"{path}: {line.strip()} is @hide and not in the public SDK"
                        )
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        print("Kotlin shape check FAILED.", file=sys.stderr)
        return 1
    print("Kotlin shapes OK.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

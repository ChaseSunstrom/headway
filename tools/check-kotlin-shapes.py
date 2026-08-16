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


def main() -> int:
    problems = []
    for module in MODULES:
        for path in sorted(Path(module).rglob("*.kt")):
            problems += conflicts(path)
            problems += unterminated_strings(path)
            problems += unimported(path)
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

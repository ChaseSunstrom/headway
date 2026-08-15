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
#   2. `import` of an android name that is @hide and so absent from the public
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

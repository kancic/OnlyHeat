#!/usr/bin/env python3
"""Diff two dashboard snapshots, ignoring data that changes on every load.

Usage: python3 -I diff.py OLD_DIR NEW_DIR
Strips chart points, asset ?v= stamps and whitespace, then prints a unified diff per changed file.
Live values (temperatures, clock, login history, weather) still show up; skip them when reading.
"""
import difflib, os, re, sys

old, new = sys.argv[1], sys.argv[2]


def norm(path):
    if not os.path.exists(path):
        return []
    t = open(path, errors="replace").read()
    t = re.sub(r'\[\d{13}, ?"?[-\d.]+"?\],?', "", t)  # [epochMillis, value] chart points
    t = re.sub(r'\{"x":"p?\w+", ?"y":[-\d.]+\},?', "", t)  # bar chart points
    t = re.sub(r"\?v=[\d.]+", "?v=X", t)
    return [line.strip() for line in t.splitlines() if line.strip()]


files = set()
for root in (old, new):
    for d, _, names in os.walk(root):
        files |= {os.path.relpath(os.path.join(d, n), root) for n in names}
for f in sorted(files):
    a, b = norm(os.path.join(old, f)), norm(os.path.join(new, f))
    if a != b:
        print("".join(difflib.unified_diff([x + "\n" for x in a], [x + "\n" for x in b], f"old/{f}", f"new/{f}", n=1)))

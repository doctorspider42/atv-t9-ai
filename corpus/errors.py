#!/usr/bin/env python3
"""Turns a typing record into the error model the decoder will be tuned against.

    python3 errors.py ../bench/typing.tsv

Reads what `./gradlew :core:harness` wrote while somebody copied out a text at full speed, and
reports how their thumb actually behaves: how often a press lands on the wrong key and which one,
how often a press never arrives, how often one arrives twice, and how often the space is simply
skipped. The phrase that was on screen is known, so the sequence it *should* have produced is
derivable, and every row is therefore a fully aligned pair. Nothing here is trained; it is all
counting.

The numbers exist because the alternative is inventing them. A decoder tuned against a made-up
noise model is a decoder tuned to the imagination of whoever made it up, and the whole reason
this method might beat a larger model is that it uses the structure of *this* problem.

Two findings from the first real recording shape what the decoder has to do, and both were
invisible before it existed:

  - A press that repeats the same key is swallowed an order of magnitude more often than an
    isolated one. On a keypad where `mnońó` share a key that is one in six of every doubled
    press, and it is not a rare event the decoder may treat as noise.
  - Slips land next door. Four in five substitutions are a physically adjacent key, which is
    what justifies an adjacency-weighted substitution cost rather than a flat one - but only
    once the physical layout is known, which is why the record carries the `pad` column.

The alignment is plain Levenshtein. Damerau's transposition was measured and is not worth its
complexity: it accounted for three errors out of a hundred and eight.
"""

import argparse
import statistics
import sys
from collections import Counter, defaultdict

from alphabet import ALPHABETS  # noqa: F401 - imported to fail early if the package is wrong

# Must agree with core/.../Keypad.kt character for character.
KEYS = {
    "2": "abcąć",
    "3": "defę",
    "4": "ghi",
    "5": "jklł",
    "6": "mnońó",
    "7": "pqrsś",
    "8": "tuv",
    "9": "wxyzźż",
}
DIGIT = {letter: digit for digit, letters in KEYS.items() for letter in letters}

# Where each key sits on a numeric keypad, and which logical digit the keypad's key produces
# under each reading. `remote` is the numpad turned over: a remote runs 1-2-3 along the top where
# a numpad runs 7-8-9, so the same nine keys in the same grid mean different digits.
POSITION = {
    "7": (0, 0), "8": (0, 1), "9": (0, 2),
    "4": (1, 0), "5": (1, 1), "6": (1, 2),
    "1": (2, 0), "2": (2, 1), "3": (2, 2),
    "0": (3, 1),
}
PHYSICAL = {
    "numpad": {digit: digit for digit in POSITION},
    "remote": {"1": "7", "2": "8", "3": "9", "4": "4", "5": "5", "6": "6",
               "7": "1", "8": "2", "9": "3", "0": "0"},
}


def sequence_of(phrase: str) -> str | None:
    """The keys a phrase should produce, with the space as `0`."""
    keys = []
    for character in phrase:
        if character == " ":
            keys.append("0")
        elif character in DIGIT:
            keys.append(DIGIT[character])
        else:
            return None
    return "".join(keys)


def align(reference: str, typed: str) -> list[tuple[str, int, int]]:
    """Levenshtein with a traceback, as (operation, index in reference, index in typed).

    Deliberately not weighted. The weights are what this script exists to produce, and aligning
    with them would be assuming the answer - a substitution cost that already believed slips land
    next door would find that slips land next door.
    """
    rows, columns = len(reference), len(typed)
    distance = [[0] * (columns + 1) for _ in range(rows + 1)]
    for row in range(rows + 1):
        distance[row][0] = row
    for column in range(columns + 1):
        distance[0][column] = column
    for row in range(1, rows + 1):
        for column in range(1, columns + 1):
            distance[row][column] = min(
                distance[row - 1][column - 1] + (reference[row - 1] != typed[column - 1]),
                distance[row - 1][column] + 1,
                distance[row][column - 1] + 1,
            )

    operations = []
    row, column = rows, columns
    while row > 0 or column > 0:
        swap = reference[row - 1] != typed[column - 1] if row > 0 and column > 0 else False
        if row > 0 and column > 0 and distance[row][column] == distance[row - 1][column - 1] + swap:
            operations.append(("ok" if not swap else "substitution", row - 1, column - 1))
            row -= 1
            column -= 1
        elif row > 0 and distance[row][column] == distance[row - 1][column] + 1:
            operations.append(("deletion", row - 1, column))
            row -= 1
        else:
            operations.append(("insertion", row, column - 1))
            column -= 1
    return operations[::-1]


def read(path: str) -> list[dict]:
    rows = []
    with open(path, encoding="utf-8") as handle:
        header = next(handle).rstrip("\n").split("\t")
        for line in handle:
            columns = line.rstrip("\n").split("\t")
            if len(columns) == len(header):
                rows.append(dict(zip(header, columns)))
    return rows


def report(rows: list[dict]) -> None:
    kinds = Counter()
    confusion = defaultdict(Counter)
    deletions = Counter()
    pressed = Counter()
    wanted = Counter()
    repeated_missed = repeated_total = isolated_missed = 0
    adjacent = far = 0
    slips = Counter()
    gaps = []
    perfect = 0

    for row in rows:
        reference = sequence_of(row["target"])
        if reference is None:
            continue
        typed = row["keys"]
        layout = PHYSICAL.get(row.get("pad", "numpad"), PHYSICAL["numpad"])
        gaps.extend(int(gap) for gap in row["millis"].split(",")[1:])
        if reference == typed:
            perfect += 1

        for position in range(1, len(reference)):
            if reference[position] == reference[position - 1]:
                repeated_total += 1

        for kind, at, hit in align(reference, typed):
            kinds[kind] += 1
            if kind != "insertion":
                wanted[reference[at]] += 1
            if kind == "substitution":
                confusion[reference[at]][typed[hit]] += 1
                here, there = POSITION[layout[reference[at]]], POSITION[layout[typed[hit]]]
                if max(abs(here[0] - there[0]), abs(here[1] - there[1])) == 1:
                    adjacent += 1
                    slips[f"{layout[reference[at]]} to {layout[typed[hit]]}"] += 1
                else:
                    far += 1
            elif kind == "deletion":
                deletions[reference[at]] += 1
                inside = (at > 0 and reference[at - 1] == reference[at]) or (
                    at + 1 < len(reference) and reference[at + 1] == reference[at]
                )
                if inside:
                    repeated_missed += 1
                else:
                    isolated_missed += 1
            else:
                pressed[typed[hit]] += 1

    total = sum(wanted.values())
    errors = kinds["substitution"] + kinds["deletion"] + kinds["insertion"]
    print(f"{len(rows)} phrases, {total} keys the phrases should have produced\n")
    print(f"  right        {kinds['ok']:5d}   {100 * kinds['ok'] / total:5.1f}%")
    print(f"  wrong key    {kinds['substitution']:5d}   {100 * kinds['substitution'] / total:5.1f}%")
    print(f"  missed       {kinds['deletion']:5d}   {100 * kinds['deletion'] / total:5.1f}%")
    print(f"  extra        {kinds['insertion']:5d}   {100 * kinds['insertion'] / total:5.1f}%")
    print(f"\n  {100 * errors / total:.1f}% of presses are wrong, and "
          f"{len(rows) - perfect} of {len(rows)} phrases carry at least one")

    # The single most useful number here: a doubled press is a different event from a lone one.
    isolated_total = total - repeated_total
    print(f"\nmissed presses, split by whether the same key was being pressed twice:")
    print(f"  in a repeat    {repeated_missed:3d} of {repeated_total:4d}"
          f"   {100 * repeated_missed / max(repeated_total, 1):5.1f}%")
    print(f"  on its own     {isolated_missed:3d} of {isolated_total:4d}"
          f"   {100 * isolated_missed / max(isolated_total, 1):5.1f}%")
    if kinds["deletion"]:
        print(f"  the space      {deletions['0']:3d} of {wanted['0']:4d}"
              f"   {100 * deletions['0'] / max(wanted['0'], 1):5.1f}%")

    if adjacent + far:
        print(f"\nwrong keys, against the physical grid the record says was under the thumb:")
        print(f"  next door (diagonals included)  {adjacent:3d}"
              f"   {100 * adjacent / (adjacent + far):.0f}%")
        print(f"  further away                    {far:3d}"
              f"   {100 * far / (adjacent + far):.0f}%")
        print("  commonest: " + ", ".join(f"{name} x{count}" for name, count in slips.most_common(6)))

    print("\nper key, as a share of the times that key was wanted:")
    for key in sorted(wanted):
        missed = deletions[key]
        swapped = sum(confusion[key].values())
        worst = confusion[key].most_common(1)
        note = f"  mostly to {worst[0][0]}" if worst else ""
        print(f"  {key}  wanted {wanted[key]:4d}   missed {100 * missed / wanted[key]:4.1f}%"
              f"   wrong {100 * swapped / wanted[key]:4.1f}%{note}")

    if gaps:
        ordered = sorted(gaps)
        print(f"\npace: median {statistics.median(gaps):.0f} ms between presses,"
              f" {1000 / statistics.median(gaps):.1f} a second")
        print(f"  10th percentile {ordered[len(ordered) // 10]} ms,"
              f" 90th {ordered[9 * len(ordered) // 10]} ms,"
              f" longest {ordered[-1]} ms")


def summarise(rows: list[dict]) -> tuple[int, int, int]:
    """Keys, wrong presses and clean phrases, for a one-line comparison between sources."""
    keys = errors = clean = 0
    for row in rows:
        reference = sequence_of(row["target"])
        if reference is None:
            continue
        keys += len(reference)
        wrong = sum(1 for kind, _, _ in align(reference, row["keys"]) if kind != "ok")
        errors += wrong
        clean += wrong == 0
    return keys, errors, clean


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("record", nargs="?", default="../bench/typing.tsv")
    arguments = parser.parse_args()

    rows = read(arguments.record)
    if not rows:
        raise SystemExit(f"nothing recorded in {arguments.record}")

    # Split by source before pooling them, because the two kinds of text are typed differently:
    # a sentence has a rhythm and a drill of unrelated words has none, and if the drill turns out
    # to be the harder of the two then the weights fitted to it are the wrong weights.
    sources = sorted({row.get("source", "") for row in rows})
    if len(sources) > 1:
        print("by source, before they are pooled:")
        for source in sources:
            here = [row for row in rows if row.get("source") == source]
            keys, errors, clean = summarise(here)
            print(f"  {source:<16} {len(here):4d} phrases, {keys:6,d} keys,"
                  f" {100 * errors / max(keys, 1):5.1f}% wrong,"
                  f" {100 * clean / len(here):4.0f}% of phrases clean")
        print()

    report(rows)
    return 0


if __name__ == "__main__":
    sys.exit(main())

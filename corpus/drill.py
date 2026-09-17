#!/usr/bin/env python3
"""Writes a text to copy out that exercises the keypad evenly, instead of like poetry.

    python3 drill.py --language pl --words 2000 --out ../bench/drill-pl.txt

Real Polish words, taken from the dictionary the keyboard ships, chosen so that every key gets
pressed about as often as every other and doubled presses turn up far more than they naturally
would. Then `errors.py` has something to count.

**Why this is not cheating.** What the error model needs is a set of conditional probabilities:
given that key `8` was wanted, how often did the thumb miss it; given a doubled press, how often
did one of the two go missing. Choosing what to type changes how many times each condition comes
up, not what happens once it does — this is stratified sampling, and the estimates it produces
are the same ones running text would give, reached sooner. What it would be wrong to do is weight
the *scoring* of the finished decoder by these frequencies, and nothing here touches that.

**Why it is worth doing.** In Pan Tadeusz, key `2` is 14% of presses and key `8` is 4.6% — three
to one. The rarest key is what decides when the matrix is usable, so copying out verse means
paying for a surplus of `2` in order to scrape together enough `8`. Worse, doubled presses are
5.8% of positions there, and they are the single largest error mode in the record: swallowed one
time in six against one time in seventy for a lone press. The thing most worth measuring is the
thing natural text supplies least.

The words stay real and stay common. Invented strings or rare vocabulary would be read slowly and
carefully, and a record of somebody reading carefully measures a different activity from the one
the decoder has to survive.
"""

import argparse
import os
import random
import re
import struct
import sys
from collections import Counter

HERE = os.path.dirname(__file__)
DEFAULT_ASSETS = os.path.join(HERE, os.pardir, "app", "src", "main", "assets")

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
LETTER_KEYS = sorted(KEYS)


def sequence_of(word: str) -> str | None:
    keys = []
    for character in word:
        digit = DIGIT.get(character)
        if digit is None:
            return None
        keys.append(digit)
    return "".join(keys)


def read_dictionary(path: str) -> list[tuple[str, int]]:
    """The shipped dictionary, as (word, score). A third reader of the format, for one script.

    Deliberately not shared with `build.py`, which writes it, or with `Dictionary.kt`, which the
    keyboard reads it through. This one is throwaway tooling and a dependency between it and
    either of those would mean a script nobody runs could break the thing that ships.
    """
    with open(path, "rb") as handle:
        data = handle.read()
    if data[:4] != b"T9D1":
        raise SystemExit(f"{path} is not a dictionary")

    at = 5
    alphabet_length = data[at]
    at += 1
    alphabet = [chr(struct.unpack_from(">H", data, at + 2 * i)[0]) for i in range(alphabet_length)]
    at += 2 * alphabet_length
    at += 4  # wordCount
    at += 2  # indexStep
    index_length, = struct.unpack_from(">I", data, at)
    at += 4 + 4 * index_length
    at += 4  # entriesLen

    words = []
    previous = ""
    while at < len(data):
        head = data[at]
        if head == 0xFF:
            shared, suffix_length = data[at + 1], data[at + 2]
            at += 3
        else:
            shared, suffix_length = head >> 4, head & 0x0F
            at += 1
        word = previous[:shared] + "".join(
            alphabet[data[at + step] - 1] for step in range(suffix_length)
        )
        at += suffix_length
        words.append((word, data[at]))
        at += 1
        previous = word
    return words


VOWELS = set("aąeęioóuy")


def readable(word: str) -> bool:
    """Rejects what a corpus leaves behind that nobody can read at speed.

    Narrow on purpose. Of the top twelve thousand Polish words only twenty-three look wrong at
    all, and most of those are real: `hollywood`, `russell`, `bennett`, `halloween`. What is left
    is `aaccff`, `ddaacc`, `iii`, `ooo` — subtitle debris that survived because it is spellable —
    and abbreviations like `mln`, `płk`, `kgb`, which are readable but are typed letter by letter
    from memory rather than read as words, and so belong in a drill even less than the debris.

    A wider filter would start dropping names, and names are the workload this keyboard exists
    for.
    """
    if re.search(r"(.)\1\1", word):
        return False
    if len(re.findall(r"(.)\1", word)) >= 3:
        return False
    return bool(VOWELS & set(word))


def pool(words: list[tuple[str, int]], size: int, shortest: int, longest: int) -> list[str]:
    """The commonest words of a usable length, which is what somebody can read at speed."""
    chosen = sorted(words, key=lambda entry: -entry[1])
    return [
        word for word, _ in chosen
        if shortest <= len(word) <= longest and sequence_of(word) and readable(word)
    ][:size]


def repeats_in(sequence: str) -> int:
    return sum(1 for at in range(1, len(sequence)) if sequence[at] == sequence[at - 1])


def generate(
    candidates: list[str],
    count: int,
    repeat_share: float,
    sample: int,
    seed: int,
) -> list[str]:
    """Greedy: at each step take the word that most reduces what the text is still short of.

    Greedy rather than anything cleverer because the objective is a set of counts and the supply
    of words is enormous — every step has a hundred candidates that all help, so the difference
    between this and an optimal selection is not measurable in the thing it is for.

    Candidates come from a random sample each step rather than the whole pool. Scanning everything
    would pick the same handful of words forever: the best word for a deficit stays the best word
    until the deficit moves, and a drill made of forty words repeated is a drill somebody types
    from memory rather than reads.
    """
    random.seed(seed)
    sequences = {word: sequence_of(word) for word in candidates}

    # The pool is ordered commonest first, and drawing from it evenly makes a drill of words
    # nobody says: the twelve thousandth Polish word turns up as often as the tenth. Worse, it
    # skews foreign — `v` and `x` are rare in Polish, so anything chasing keys 8 and 9 reaches for
    # English, and the whole text drifts into film titles. Weighting the draw towards the top
    # leaves the balancing to the deficit, where it belongs, and keeps the words ordinary.
    weights = [1.0 / (rank + 40) for rank in range(len(candidates))]

    pressed = Counter()
    repeats = 0
    total = 0
    recent = set()
    order = []

    for _ in range(count):
        best = None
        best_score = None
        for word in random.choices(candidates, weights, k=sample):
            if word in recent:
                continue
            sequence = sequences[word]

            # What the text is short of, key by key. A key already over its share scores negative,
            # so a word made of common keys is rejected rather than merely not preferred.
            wanted = (total + len(sequence)) / len(LETTER_KEYS)
            score = sum(wanted - pressed[key] for key in set(sequence)) / len(sequence)

            # Doubled presses are the largest error mode and the rarest thing in natural text, so
            # they are chased explicitly until they reach their share.
            doubled = repeats_in(sequence)
            if doubled and (total == 0 or repeats / max(total, 1) < repeat_share):
                score += 40 * doubled

            if best_score is None or score > best_score:
                best, best_score = word, score

        if best is None:
            continue
        order.append(best)
        sequence = sequences[best]
        pressed.update(sequence)
        repeats += repeats_in(sequence)
        total += len(sequence)

        recent.add(best)
        if len(recent) > 600:
            recent.clear()

    return order


def report(words: list[str]) -> None:
    pressed = Counter()
    repeats = 0
    for word in words:
        sequence = sequence_of(word)
        pressed.update(sequence)
        repeats += repeats_in(sequence)
    total = sum(pressed.values())
    presses = total + len(words)  # one space after each word

    print(f"  {len(words):,} words, {total:,} letter presses, {presses:,} with the spaces")
    share = {key: 100 * pressed[key] / total for key in LETTER_KEYS}
    print("  " + "   ".join(f"{key} {share[key]:.1f}%" for key in LETTER_KEYS))
    print(f"  rarest key {min(share, key=share.get)} at {min(share.values()):.1f}%,"
          f" commonest {max(share, key=share.get)} at {max(share.values()):.1f}%"
          f" (Pan Tadeusz: 4.6% and 14.0%)")
    print(f"  doubled presses: {repeats:,}, {100 * repeats / total:.1f}% of positions"
          f" (Pan Tadeusz: 5.8%)")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--language", default="pl")
    parser.add_argument("--words", type=int, default=2000, help="how many words to write")
    parser.add_argument("--dictionary", default=None)
    parser.add_argument("--out", default=None)
    parser.add_argument("--pool", type=int, default=12_000, help="how many words to choose from")
    parser.add_argument("--shortest", type=int, default=3)
    parser.add_argument("--longest", type=int, default=9)
    parser.add_argument(
        "--repeats",
        type=float,
        default=0.18,
        help="share of positions that should be a doubled press",
    )
    parser.add_argument("--sample", type=int, default=160, help="candidates weighed each step")
    parser.add_argument("--seed", type=int, default=326)
    arguments = parser.parse_args()

    dictionary = arguments.dictionary or os.path.join(
        DEFAULT_ASSETS, f"dictionary-{arguments.language}.bin"
    )
    candidates = pool(
        read_dictionary(dictionary), arguments.pool, arguments.shortest, arguments.longest
    )
    if not candidates:
        raise SystemExit(f"no usable words in {dictionary}")
    print(f"{len(candidates):,} words to choose from in {os.path.basename(dictionary)}",
          file=sys.stderr)

    words = generate(
        candidates, arguments.words, arguments.repeats, arguments.sample, arguments.seed
    )
    report(words)

    out = arguments.out or os.path.join(HERE, os.pardir, "bench", f"drill-{arguments.language}.txt")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8") as handle:
        for at in range(0, len(words), 8):
            handle.write(" ".join(words[at:at + 8]) + "\n")
    print(f"  written to {os.path.relpath(out)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

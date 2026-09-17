#!/usr/bin/env python3
"""Builds the T9 dictionary the keyboard ships.

    python3 build.py --language pl --words 120000 --title-weight 20

Reads everything in raw/ for the language and writes
app/src/main/assets/dictionary-<language>.bin.

Unlike the character tables in the sibling repositories this really is a finished structure
rather than counts: a front-coded, key-sequence-ordered word list cannot be rebuilt on the
device in the time a keyboard has to start. The encoder here and the reader in
`core/.../Dictionary.kt` are therefore two implementations of one format, and the round-trip
test in `DictionaryTest` is what keeps them honest.

Format, big-endian:

    magic        4 bytes  "T9D1"
    version      u8       1
    alphabetLen  u8       N, at most 63
    alphabet     N x u16  UTF-16 code units; byte value i+1 encodes alphabet[i]
    wordCount    u32
    indexStep    u16      words between checkpoints
    indexLen     u32
    index        indexLen x u32   offset of each checkpoint, from the start of entries
    entriesLen   u32
    entries      entriesLen bytes

One entry, words in (sequence ascending, count descending) order:

    b0           u8       high nibble shared prefix, low nibble suffix length
                          0xFF escapes to u8 shared, u8 suffixLen
    suffix       suffixLen bytes, each an alphabet index plus one
    score        u8       log-scaled count, 255 most frequent

Every checkpoint word stores a shared prefix of zero, so a search can start there.
"""

import argparse
import math
import os
import struct
import sys
from collections import Counter

from alphabet import ALPHABETS

HERE = os.path.dirname(__file__)
DEFAULT_RAW = os.path.join(HERE, "raw")
DEFAULT_ASSETS = os.path.join(HERE, os.pardir, "app", "src", "main", "assets")

MAGIC = b"T9D1"
VERSION = 1
INDEX_STEP = 32
ESCAPE = 0xFF

# ITU E.161, and it must agree with core/.../Keypad.kt character for character. The Polish
# letters fold onto their base key: that is what the dictionary is for.
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


def sequence_of(word: str) -> str | None:
    digits = []
    for character in word:
        digit = DIGIT.get(character)
        if digit is None:
            return None
        digits.append(digit)
    return "".join(digits)


def count_words(language: str, raw: str, title_weight: int) -> Counter:
    """Counts spellable word types across every raw file for the language.

    Words carrying anything the keypad cannot reach are dropped whole rather than repaired.
    Stripping the apostrophe out of `don't` would file it under the sequence for `dont` and
    then hand the user back a word with a mark they never typed and cannot delete in one
    press. English contractions are consequently missing from v1; the fix is an implicit
    apostrophe on key 1, the way Tegic did it, and it is a format change rather than a tweak.
    """
    sources = sorted(
        os.path.join(raw, name)
        for name in os.listdir(raw)
        if name.endswith(f"-{language}.txt")
    )
    if not sources:
        raise SystemExit(f"no raw text for {language}: run fetch.py first")

    counts = Counter()
    dropped = 0
    for path in sources:
        # Titles are a few percent of the text and the entire workload. startswith, not `in`:
        # "titles-" is a substring of "subtitles-", so a containment test weights everything
        # equally and silently changes no ratio at all.
        weight = title_weight if os.path.basename(path).startswith("titles-") else 1
        with open(path, encoding="utf-8") as handle:
            for line in handle:
                for token in line.split():
                    if sequence_of(token) is None:
                        dropped += 1
                        continue
                    counts[token] += weight

    print(
        f"{language}: {len(counts):,} spellable types from {len(sources)} files"
        f" ({dropped:,} tokens dropped as unspellable)",
        file=sys.stderr,
    )
    return counts


def count_pairs(language: str, raw: str, title_weight: int, vocabulary: set[str]) -> Counter:
    """Counts adjacent word pairs, over the words that made it into the dictionary.

    Word order is the whole point and the first version of this pipeline threw it away: a
    `Counter` over tokens says how common a word is and nothing about what follows what. But `z`
    and `w` are the same key, and so are `bo` and `co`, and `opisuje` and `opisuję` — for a given
    sequence the commoner one wins every time and for ever, whatever the rest of the sentence
    says. Only the word before can separate them.

    Pairs are counted only between words the dictionary holds. A pair whose second word cannot be
    offered is a pair the decoder can never use, and counting it would spend the table's budget on
    entries that are unreachable by construction.

    A line break ends a pair. Subtitle lines are speech turns as often as they are sentences, and
    joining across them would teach the model that the last word of one utterance predicts the
    first of the next.
    """
    sources = sorted(
        os.path.join(raw, name)
        for name in os.listdir(raw)
        if name.endswith(f"-{language}.txt")
    )

    pairs = Counter()
    for path in sources:
        weight = title_weight if os.path.basename(path).startswith("titles-") else 1
        with open(path, encoding="utf-8") as handle:
            for line in handle:
                previous = None
                for token in line.split():
                    if token not in vocabulary:
                        previous = None
                        continue
                    if previous is not None:
                        pairs[(previous, token)] += weight
                    previous = token

    print(f"{language}: {len(pairs):,} distinct pairs", file=sys.stderr)
    return pairs


def write_bigrams(language: str, pairs: Counter, keep: int, floor: int, out: str) -> None:
    """Writes the pairs worth keeping, as hashes rather than words.

    Two words cost several times what a hash of them costs, and the words are already in the
    dictionary: the table only has to answer "how likely is this pair", never "which pair is
    this". Sixty-four bits over a few hundred thousand entries collides a handful of times in the
    whole table, and the cost of one collision is that a word looks likelier after something than
    it is - a wrong ranking in a rare place, against a file several times smaller everywhere.

    Kept in hash order so the reader can binary search it without building anything at load.

    Format, big-endian:

        magic     4 bytes  "T9B1"
        version   u8       1
        span      u16      the log range the score byte was scaled into, times 100
        count     u32
        pairs     count x (u64 hash, u8 score)   ascending by hash
    """
    chosen = [pair for pair in pairs.most_common() if pair[1] >= floor][:keep]
    if not chosen:
        raise SystemExit("no pairs survived the floor: is the corpus large enough?")

    highest = chosen[0][1]
    lowest = chosen[-1][1]
    # The score byte is the log-probability of the pair, scaled so the rarest kept pair is 1 and
    # the commonest is 255. The span travels in the file because the reader needs it to get back
    # to nats, and it depends on the corpus rather than on the format.
    span = math.log(highest) - math.log(lowest) or 1.0

    table = []
    for (previous, word), count in chosen:
        step = 1 + int(254 * (math.log(count) - math.log(lowest)) / span)
        # Sorted as the reader compares them, which is signed: Kotlin has no unsigned long in its
        # arrays, so a hash with the top bit set is a negative number there and belongs first. Get
        # this wrong and the binary search quietly misses half the table.
        table.append((signed(fnv1a(f"{previous} {word}")), max(1, min(255, step))))
    table.sort()

    os.makedirs(out, exist_ok=True)
    target = os.path.join(out, f"bigrams-{language}.bin")
    with open(target, "wb") as handle:
        handle.write(b"T9B1")
        handle.write(struct.pack(">BHI", 1, min(65535, int(span * 100)), len(table)))
        for value, step in table:
            handle.write(struct.pack(">QB", value & 0xFFFFFFFFFFFFFFFF, step))

    size = os.path.getsize(target)
    print(
        f"  {os.path.basename(target)}  {size:,} bytes"
        f"  ({len(table):,} pairs, kept down to {lowest} occurrences)",
        file=sys.stderr,
    )


def signed(value: int) -> int:
    return value - (1 << 64) if value >= (1 << 63) else value


def fnv1a(text: str) -> int:
    """The same hash `Bigrams.hash` computes, and the two have to agree character for character."""
    value = 0xCBF29CE484222325
    for character in text:
        value = ((value ^ ord(character)) * 0x100000001B3) & 0xFFFFFFFFFFFFFFFF
    return value


def order(counts: Counter, limit: int) -> list[tuple[str, int]]:
    """Top words by count, then sorted by key sequence with the commonest first inside each.

    The sequence ordering is the format's whole trick: every word a sequence produces ends up
    contiguous, and so does every word it completes to, which turns both of T9's questions into
    a binary search. The count ordering inside a sequence is what makes the first candidate
    right most of the time without any runtime ranking at all.
    """
    chosen = counts.most_common(limit)
    return sorted(chosen, key=lambda item: (sequence_of(item[0]), -item[1], item[0]))


def encode(words: list[tuple[str, int]], alphabet: str) -> tuple[bytes, list[int]]:
    index_of = {letter: position + 1 for position, letter in enumerate(alphabet)}
    highest = max((count for _, count in words), default=1)
    scale = math.log1p(highest) or 1.0

    entries = bytearray()
    checkpoints = []
    previous = ""

    for position, (word, count) in enumerate(words):
        if position % INDEX_STEP == 0:
            checkpoints.append(len(entries))
            shared = 0
        else:
            shared = 0
            limit = min(len(previous), len(word))
            while shared < limit and previous[shared] == word[shared]:
                shared += 1

        suffix = word[shared:]
        if shared >= 15 or len(suffix) >= 15:
            entries.append(ESCAPE)
            entries.append(shared)
            entries.append(len(suffix))
        else:
            entries.append((shared << 4) | len(suffix))
        entries.extend(index_of[letter] for letter in suffix)

        # 0 is never written: a score of zero would be indistinguishable from a word the
        # encoder failed to score, and every word here occurred at least once.
        entries.append(max(1, min(255, 1 + int(254 * math.log1p(count) / scale))))
        previous = word

    return bytes(entries), checkpoints


def write(language: str, words: list[tuple[str, int]], out: str) -> str:
    alphabet = "".join(sorted({letter for word, _ in words for letter in word}))
    if len(alphabet) > 63:
        raise SystemExit(f"alphabet of {len(alphabet)} does not fit the format")

    entries, checkpoints = encode(words, alphabet)

    os.makedirs(out, exist_ok=True)
    target = os.path.join(out, f"dictionary-{language}.bin")
    with open(target, "wb") as handle:
        handle.write(MAGIC)
        handle.write(struct.pack(">BB", VERSION, len(alphabet)))
        for letter in alphabet:
            handle.write(struct.pack(">H", ord(letter)))
        handle.write(struct.pack(">IHI", len(words), INDEX_STEP, len(checkpoints)))
        for offset in checkpoints:
            handle.write(struct.pack(">I", offset))
        handle.write(struct.pack(">I", len(entries)))
        handle.write(entries)

    size = os.path.getsize(target)
    print(
        f"  {os.path.basename(target)}  {size:,} bytes"
        f"  ({len(words):,} words, {size / len(words):.2f} B/word,"
        f" alphabet {len(alphabet)})",
        file=sys.stderr,
    )
    return target


def report(words: list[tuple[str, int]]) -> None:
    """How ambiguous the dictionary is, weighted by how often each word actually occurs.

    Printed because it is the number that decides whether T9 is worth shipping, and it is
    cheap here and expensive to recover later.
    """
    buckets = {}
    for word, count in words:
        buckets.setdefault(sequence_of(word), []).append((word, count))

    total = sum(count for _, count in words)
    first = sum(max(entry[1] for entry in bucket) for bucket in buckets.values())
    collided = sum(len(bucket) for bucket in buckets.values() if len(bucket) > 1)

    print(
        f"  {len(buckets):,} distinct key sequences,"
        f" {collided:,} words share one with another"
        f" ({100 * first / total:.2f}% of weighted use is first choice)",
        file=sys.stderr,
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--language", choices=tuple(ALPHABETS), required=True)
    parser.add_argument("--raw", default=DEFAULT_RAW, help="directory of normalised text")
    parser.add_argument("--out", default=DEFAULT_ASSETS, help="where to write the dictionary")
    parser.add_argument(
        "--words",
        type=int,
        default=120_000,
        help="how many word types to keep, commonest first",
    )
    parser.add_argument(
        "--title-weight",
        type=int,
        default=20,
        help="how many times title text counts, to set the domain mix deliberately",
    )
    parser.add_argument(
        "--pairs",
        type=int,
        default=400_000,
        help="how many word pairs to keep, commonest first; 0 writes no table at all",
    )
    parser.add_argument(
        "--pair-floor",
        type=int,
        default=3,
        help="how often a pair must occur to be worth keeping",
    )
    arguments = parser.parse_args()

    counts = count_words(arguments.language, arguments.raw, arguments.title_weight)
    words = order(counts, arguments.words)
    if not words:
        raise SystemExit("no words survived: check the raw text")
    report(words)
    write(arguments.language, words, arguments.out)

    if arguments.pairs > 0:
        vocabulary = {word for word, _ in words}
        pairs = count_pairs(
            arguments.language, arguments.raw, arguments.title_weight, vocabulary
        )
        write_bigrams(
            arguments.language, pairs, arguments.pairs, arguments.pair_floor, arguments.out
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())

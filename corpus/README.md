# Corpus

Two scripts and no data. `fetch.py` downloads the text, `build.py` turns it into the dictionary
the app ships. `raw/` is gitignored: the corpora are large, and their licensing is a good deal
less clear than a word-frequency table derived from them.

```bash
python3 fetch.py --language pl --megabytes 250
python3 build.py --language pl --words 120000 --title-weight 20 --pairs 400000
```

`build.py` writes two files: the dictionary, and `bigrams-<language>.bin`, the word pairs the
decoder uses to tell `z` from `w`. Both are committed.

**Wikidata refuses more often than it answers.** Pages come back 500, 502, 504 and as truncated
JSON, and at ten thousand labels a page the musician query failed *every* time — the same
truncation at the same byte, four attempts apart, which is a response the endpoint will not finish
sending rather than a connection that dropped. Two thousand a page returns six thousand of them,
so that is the default now.

Run it twice. Failures come in bursts, the second run merges with what the first got, and the
count of pages that never arrived is printed at the end — that number is how much to disbelieve
the corpus by. A fifth of it looks exactly like all of it from the inside, and a dictionary built
on that is quietly worse in precisely the domain this keyboard is for.

`build.py` writes `../app/src/main/assets/dictionary-<language>.bin`, which **is** committed. The
dictionary is a finished structure rather than counts, unlike the character tables in the sibling
repositories — a front-coded, key-sequence-ordered word list is not something the device can
rebuild in the time a keyboard has to start.

## Sources, and why these two

**OpenSubtitles** supplies running speech. It teaches the dictionary ordinary vocabulary and,
more importantly, the frequencies that decide which word a key sequence offers first.

**Wikidata** supplies film, series, actor and musician labels — CC0, and the workload the
keyboard actually faces. Statistics from running text generalise worst exactly here, which is why
`--title-weight` exists: it sets the domain mix deliberately rather than accepting whatever the
download happened to contain. At weight 1 a few hundred thousand title tokens vanish under
several million tokens of speech and change no ranking at all.

Hunspell and sjp.pl would both give a larger Polish vocabulary and are **deliberately not used**:
GPL/LGPL/MPL against this project's MIT. A table of word frequencies derived from a corpus is a
set of facts; a vendored word list is somebody's work.

## Why the format is what it is

Words are sorted by **key sequence**, not alphabetically. Everything follows from that:

- Every word a sequence spells is contiguous, so exact matches are a binary search.
- Every word it completes to is contiguous with them, so predictive completion is the same
  search and a forward scan.
- Sorting by count *inside* a sequence means the first candidate is right most of the time with
  no ranking work at runtime at all.

Sorted alphabetically the same file would need a separate digit index roughly its own size again.

Letters are stored as alphabet indices rather than UTF-8, which is worth about 15% on Polish on
its own: `ą ć ę ł ń ó ś ź ż` are two bytes each in UTF-8 and one here. Front coding against the
previous word removes most of the rest. A checkpoint every 32 words stores a zero shared prefix,
which is what lets a binary search start in the middle of the file.

## What the sizes came out at

Measured on 250 MB of OpenSubtitles per language plus the Wikidata labels, 2026-08-25. The
`words` column is what `--words` was set to; the rest is the file on disk.

Run `build.py` and it prints the same numbers for whatever corpus you gave it, including the
share of weighted use that lands on the first candidate — which is the figure that decides
whether T9 is worth shipping, and it is cheap here and expensive to recover later.

## Two things to know before trusting a figure from this

**The corpus is the ceiling, not `--words`.** At 40 MB per language the vocabulary saturated well
below 150k types for Polish, so asking for 200k silently gave fewer. Check the "spellable types"
line `build.py` prints before believing a word count.

**Held-out coverage is not workload coverage.** Splitting subtitles and testing on the held-out
part measures how well the dictionary covers *more subtitles*. The keyboard's real input is
`../bench/queries-v1.tsv`, which is mostly proper nouns and deliberately contains
out-of-dictionary channel names. `./gradlew :core:bench` is the figure that counts.

## The typing record

`errors.py` reads what the harness wrote while somebody copied out a text at full speed and
reports how their thumb behaves. Not a corpus script like the other two — it turns a recording
into the error model the decoder is tuned against.

```bash
./gradlew :core:harness       # Record, copy out a text, stop whenever
python3 errors.py ../bench/typing.tsv
```

Nothing in it is trained. The phrase on screen is known, so the keys it should have produced are
derivable, and every row is a fully aligned pair: the counts fall straight out. The alternative
is inventing the numbers, and a decoder tuned against an invented noise model is tuned to
somebody's imagination rather than to the person holding the remote.

Two things the first real recording showed, neither of them guessable:

**A doubled press is swallowed one time in six.** On a keypad where `mnońó` share a key that is
not a rare event: `on`, `no`, `nn` are two presses of `6`, and 16.4% of such pairs arrived as one
press against 1.4% for a press standing on its own. An order of magnitude apart, so they are two
different events and the decoder needs them costed separately.

**Slips land next door.** Four in five wrong keys were a physically adjacent key, diagonals
included, which is what justifies an adjacency-weighted substitution cost. It is only knowable
because the record carries the `pad` column: the same digit sits in a different place depending
on whether the keypad is being read as itself or as a remote, and without knowing which, the
geometry is unrecoverable afterwards.

## The drill

`drill.py` writes a text to copy out that exercises the keypad evenly, instead of like poetry.

```bash
python3 drill.py --language pl --words 2000
```

Real words from the shipped dictionary, chosen greedily so that every key lands on 12.5% of the
presses and doubled presses reach 18% of positions. `../bench/drill-pl.txt` is committed, so a
recording can be compared against the same text later.

**It is stratified sampling, not cheating.** The error model is a set of conditional
probabilities — given key `8` was wanted, how often was it missed; given a doubled press, how
often did one go missing. Choosing what to type changes how often each condition arises, not what
happens once it does. What would be wrong is letting these frequencies into the decoder's own
scoring, and nothing here touches that.

**What it buys.** Key `8` is 4.6% of Pan Tadeusz and 12.5% here, and the rarest key is what
decides when the matrix is usable. One pass of the drill is 13,695 presses, 45 minutes at five a
second, and gives every key 1,461 presses; the same coverage of `8` from verse needs 31,780
letters, near two hours. Doubled presses, the largest error mode in the record, arrive three
times as often.

**Type both.** A drill of unrelated words has no rhythm and a sentence does, so the two may not
be typed the same way. `errors.py` reports them separately before pooling — if the drill turns
out to be the harder of the two, weights fitted to it are the wrong weights, and the `source`
column is what makes that visible.

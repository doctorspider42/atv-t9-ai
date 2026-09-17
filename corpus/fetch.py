#!/usr/bin/env python3
"""Fetches the training text. Nothing here is committed: only this script is.

Two sources, for two different jobs. OpenSubtitles supplies running speech, which is what
teaches the model ordinary letter sequences. Wikidata supplies film, series and person
names, which is the workload the keyboard actually faces and the place where statistics
from running text generalise worst.

The subtitle download is bounded by default, and here the bound could be far lower than it
is. This project needs single-character frequencies over some fifty symbols, which settle
after a few hundred thousand characters; the budget is generous only so the same raw text can
serve a higher-order model if one is ever wanted.

    python3 fetch.py --language pl --megabytes 40
"""

import argparse
import gzip
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

from alphabet import normalise

RAW = os.path.join(os.path.dirname(__file__), "raw")

SUBTITLES = "https://object.pouta.csc.fi/OPUS-OpenSubtitles/v2018/mono/{language}.txt.gz"

WIKIDATA = "https://query.wikidata.org/sparql"

# One entity kind per request, paged. Two things learned the hard way: a union of kinds
# times out, and so does walking the subclass tree with `wdt:P31/wdt:P279*` - the endpoint
# then returns a truncated body, which arrives as a JSON parse error rather than an HTTP
# error. Direct `P31` costs a few niche films and finishes.
TITLES_QUERY = """
SELECT ?label WHERE {{
  {selector}
  ?item rdfs:label ?label .
  FILTER(LANG(?label) = "{language}")
}}
LIMIT {limit}
OFFSET {offset}
"""

# People are selected by occupation rather than by being human: Q5 has millions of members
# and none of the filtering that makes the result relevant to a TV search box.
SELECTORS = {
    "film": "?item wdt:P31 wd:Q11424 .",
    "series": "?item wdt:P31 wd:Q5398426 .",
    "actor": "?item wdt:P106 wd:Q33999 .",
    "musician": "?item wdt:P106 wd:Q639669 .",
}

USER_AGENT = "atv-t9-corpus/0.1 (https://github.com/vagrant326/atv-t9)"


def fetch_subtitles(language: str, megabytes: int) -> str:
    """Streams the mono corpus and stops at the byte budget, normalising as it goes."""
    url = SUBTITLES.format(language=language)
    budget = megabytes * 1024 * 1024
    written = 0
    target = os.path.join(RAW, f"subtitles-{language}.txt")

    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request) as response:
        with gzip.GzipFile(fileobj=response) as stream:
            with open(target, "w", encoding="utf-8") as out:
                for raw in stream:
                    line = normalise(raw.decode("utf-8", "replace"), language)
                    if len(line) < 2:
                        continue
                    out.write(line + "\n")
                    written += len(line) + 1
                    if written >= budget:
                        break
    print(f"subtitles-{language}.txt  {written / 1024 / 1024:.1f} MB", file=sys.stderr)
    return target


def fetch_page(selector: str, language: str, limit: int, offset: int, tries: int = 4) -> list[str]:
    """One page, retried, because the endpoint fails far more often than it succeeds badly.

    A run of this returned twenty thousand labels of an expected hundred and twenty thousand: the
    film pages timed out, and every musician page came back 500, 502 or as truncated JSON. None of
    that is an error here in any visible sense — the pipeline simply ends up with a fifth of the
    data it thinks it has, and the dictionary and the word pairs built from it are quietly worse in
    exactly the domain this keyboard exists for.

    So a failed page is retried with a widening pause rather than skipped on the first refusal.
    What is still missing after that is reported as a count, which is the number to disbelieve the
    corpus by.
    """
    query = TITLES_QUERY.format(
        selector=selector, language=language, limit=limit, offset=offset
    )
    url = f"{WIKIDATA}?{urllib.parse.urlencode({'query': query, 'format': 'json'})}"
    request = urllib.request.Request(
        url,
        headers={"User-Agent": USER_AGENT, "Accept": "application/sparql-results+json"},
    )

    for attempt in range(tries):
        try:
            with urllib.request.urlopen(request, timeout=180) as response:
                payload = json.load(response)
            return [row["label"]["value"] for row in payload["results"]["bindings"]]
        except Exception as failure:  # noqa: BLE001 - every failure here is worth one more try
            if attempt == tries - 1:
                raise
            pause = 5 * (attempt + 1)
            print(
                f"  {selector.split()[2]} offset {offset}: {failure}; again in {pause}s",
                file=sys.stderr,
            )
            time.sleep(pause)
    return []


def fetch_titles(language: str, per_page: int, pages: int) -> str:
    """Film, series, actor and musician labels, merged with whatever is already on disk.

    Merged, and written once at the end. The endpoint fails in bursts and refuses some pages
    outright, so a second run with a smaller page size is the ordinary way to fill the gaps a
    first one left. Opening the file for writing at the start throws away the good half in order
    to try for the other — which is how a retry ends with less than it started with, and did:
    thirty-five thousand labels became six.

    A page that never arrives is counted rather than logged and forgotten. That count is the
    number by which to disbelieve the corpus, and without it a fifth of the data looks like all
    of it.
    """
    target = os.path.join(RAW, f"titles-{language}.txt")

    labels = set()
    if os.path.exists(target):
        with open(target, encoding="utf-8") as existing:
            labels = {line.strip() for line in existing if line.strip()}
        print(f"titles-{language}.txt: {len(labels):,} already here", file=sys.stderr)

    missing = 0
    for name, selector in SELECTORS.items():
        kept = 0
        for page in range(pages):
            try:
                found = fetch_page(selector, language, per_page, page * per_page)
            except Exception as failure:  # noqa: BLE001 - reported, then counted
                print(f"titles {name} {language} page {page} gave up: {failure}", file=sys.stderr)
                missing += 1
                continue
            if not found:
                break
            for label in found:
                line = normalise(label, language)
                if len(line) >= 2:
                    labels.add(line)
                    kept += 1
        print(f"titles {name} {language}: {kept}", file=sys.stderr)

    with open(target, "w", encoding="utf-8") as out:
        for line in sorted(labels):
            out.write(line + "\n")

    print(
        f"titles-{language}.txt  {len(labels):,} labels"
        + (f", {missing} pages never arrived" if missing else ""),
        file=sys.stderr,
    )
    return target


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--language", choices=("pl", "en"), required=True)
    parser.add_argument("--megabytes", type=int, default=40)
    parser.add_argument("--titles", type=int, default=10000, help="labels per page")
    parser.add_argument("--pages", type=int, default=3)
    parser.add_argument("--skip-subtitles", action="store_true")
    parser.add_argument("--skip-titles", action="store_true")
    arguments = parser.parse_args()

    os.makedirs(RAW, exist_ok=True)
    if not arguments.skip_subtitles:
        fetch_subtitles(arguments.language, arguments.megabytes)
    if not arguments.skip_titles:
        fetch_titles(arguments.language, arguments.titles, arguments.pages)
    return 0


if __name__ == "__main__":
    sys.exit(main())

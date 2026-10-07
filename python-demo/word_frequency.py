#!/usr/bin/env python3
"""Print a word frequency report for a text file or standard input.

A single-file demo that uses only the Python standard library. It reads UTF-8
text, splits it into lowercase words, and prints a deterministic frequency
table: most common word first, ties broken alphabetically.

Examples:
    python3 word_frequency.py sample.txt
    python3 word_frequency.py sample.txt --top 5
    cat sample.txt | python3 word_frequency.py --top 5
    python3 word_frequency.py sample.txt --top 0 --min-length 4
"""

from __future__ import annotations

import argparse
import re
import sys
from collections import Counter

WORD_RE = re.compile(r"[a-z0-9']+")
DEFAULT_TOP = 10
STDIN_PATH = "-"


def tokenize(text: str, min_length: int = 1) -> list[str]:
    """Return the lowercase words in ``text``, dropping short ones."""
    return [word for word in WORD_RE.findall(text.lower()) if len(word) >= min_length]


def rank_words(text: str, min_length: int = 1) -> list[tuple[str, int]]:
    """Rank ``(word, count)`` pairs by descending count, then alphabetically."""
    counts = Counter(tokenize(text, min_length))
    return sorted(counts.items(), key=lambda item: (-item[1], item[0]))


def build_report(text: str, top: int | None, min_length: int = 1) -> str:
    """Build the full report as a single string."""
    ranked = rank_words(text, min_length)
    token_total = sum(count for _, count in ranked)
    shown = ranked if top is None else ranked[:top]

    lines = [
        "Word frequency report",
        f"Tokens: {token_total}",
        f"Unique words: {len(ranked)}",
    ]
    if not shown:
        lines.append("No words found.")
        return "\n".join(lines)
    if len(shown) < len(ranked):
        lines.append(f"Showing top {len(shown)}")

    rank_width = max(4, len(str(len(shown))))
    word_width = max(4, max(len(word) for word, _ in shown))
    count_width = max(5, len(str(max(count for _, count in shown))))

    lines.append("")
    lines.append(f"{'RANK':>{rank_width}}  {'WORD':<{word_width}}  {'COUNT':>{count_width}}")
    for rank, (word, count) in enumerate(shown, start=1):
        lines.append(f"{rank:>{rank_width}}  {word:<{word_width}}  {count:>{count_width}}")
    return "\n".join(lines)


def parse_args(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Print a word frequency report for a text file or standard input.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "path",
        nargs="?",
        default=STDIN_PATH,
        help=f"UTF-8 text file to read ('{STDIN_PATH}' or omitted reads standard input)",
    )
    parser.add_argument(
        "--top",
        type=int,
        default=DEFAULT_TOP,
        help=f"show at most N words (default: {DEFAULT_TOP}; use 0 to show every word)",
    )
    parser.add_argument(
        "--min-length",
        type=int,
        default=1,
        help="ignore words shorter than N characters (default: 1)",
    )
    return parser.parse_args(argv)


def read_text(path: str) -> str:
    """Read UTF-8 text from ``path``, or from standard input when it is '-'."""
    if path == STDIN_PATH:
        return sys.stdin.read()
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    if args.top < 0:
        print("error: --top must be 0 or greater", file=sys.stderr)
        return 2
    if args.min_length < 1:
        print("error: --min-length must be 1 or greater", file=sys.stderr)
        return 2

    try:
        text = read_text(args.path)
    except OSError as exc:
        detail = exc.strerror or str(exc)
        print(f"error: cannot read {args.path}: {detail}", file=sys.stderr)
        return 2

    top = None if args.top == 0 else args.top
    print(build_report(text, top, args.min_length))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

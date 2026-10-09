#!/usr/bin/env python3
"""Analyze text and report useful statistics.

Given a block of text (from a file, stdin, or a string), this tool reports:

* word, sentence, paragraph, and character counts
* average word / sentence length
* estimated reading time
* the Flesch Reading Ease score (a readability measure)
* the most frequent words (excluding common stop-words)

It exposes a small set of pure functions that are easy to unit-test, plus a
command-line interface.
"""

from __future__ import annotations

import argparse
import re
import string
import sys
from collections import Counter

# A small built-in English stop-word list.
STOP_WORDS = {
    "a", "an", "the", "and", "or", "but", "if", "then", "else", "for", "nor",
    "on", "at", "to", "from", "by", "of", "in", "with", "without", "is", "are",
    "was", "were", "be", "been", "being", "am", "do", "does", "did", "have",
    "has", "had", "i", "you", "he", "she", "it", "we", "they", "them", "his",
    "her", "its", "our", "their", "this", "that", "these", "those", "as", "so",
    "not", "no", "can", "will", "just", "than", "too", "very", "there", "here",
}

_WORD_RE = re.compile(r"[A-Za-z0-9']+")
_SENTENCE_RE = re.compile(r"[^.!?]+[.!?]*")


def count_words(text: str) -> int:
    """Return the number of word-like tokens in ``text``."""
    return len(_WORD_RE.findall(text))


def count_sentences(text: str) -> int:
    """Return the number of sentences in ``text``.

    A sentence is a run of characters terminated by ``.``, ``!`` or ``?``.
    """
    sentences = [s for s in _SENTENCE_RE.findall(text) if s.strip()]
    return len(sentences)


def count_paragraphs(text: str) -> int:
    """Return the number of paragraphs (blocks separated by blank lines)."""
    blocks = [block for block in re.split(r"\n\s*\n", text.strip()) if block.strip()]
    return len(blocks)


def get_words(text: str) -> list[str]:
    """Return a list of normalized word tokens (lower-cased)."""
    return [w.lower() for w in _WORD_RE.findall(text)]



def count_syllables(word: str) -> int:
    """Estimate the number of syllables in a word using a simple heuristic."""
    word = word.lower().strip(string.punctuation)
    if not word:
        return 0
    # Strip a trailing silent 'e'.
    if word.endswith("e") and len(word) > 2:
        word = word[:-1]
    vowels = "aeiouy"
    count = 0
    prev_was_vowel = False
    for ch in word:
        is_vowel = ch in vowels
        if is_vowel and not prev_was_vowel:
            count += 1
        prev_was_vowel = is_vowel
    return max(count, 1)


def flesch_reading_ease(text: str) -> float:
    """Return the Flesch Reading Ease score for ``text``.

    Higher scores indicate easier reading (90-100 very easy, 0-30 very hard).
    Returns 0.0 when there is not enough text to score.
    """
    words = get_words(text)
    sentences = count_sentences(text)
    if not words or sentences == 0:
        return 0.0

    total_syllables = sum(count_syllables(w) for w in words)
    words_per_sentence = len(words) / sentences
    syllables_per_word = total_syllables / len(words)
    score = 206.835 - 1.015 * words_per_sentence - 84.6 * syllables_per_word
    return round(score, 1)


def reading_ease_label(score: float) -> str:
    """Map a Flesch Reading Ease score to a readability label."""
    if score >= 90:
        return "Very Easy"
    if score >= 80:
        return "Easy"
    if score >= 70:
        return "Fairly Easy"
    if score >= 60:
        return "Standard"
    if score >= 50:
        return "Fairly Difficult"
    if score >= 30:
        return "Difficult"
    return "Very Confusing"


def estimate_reading_time(word_count: int, wpm: int = 200) -> str:
    """Return a human-friendly reading-time estimate for ``word_count`` words."""
    if word_count <= 0:
        return "0 min"
    minutes = word_count / wpm
    if minutes < 1:
        seconds = round(minutes * 60)
        return f"{seconds} sec"
    return f"{minutes:.1f} min"


def top_words(text: str, limit: int = 5, remove_stop_words: bool = True) -> list[tuple[str, int]]:
    """Return the ``limit`` most common words as ``(word, count)`` tuples."""
    words = get_words(text)
    if remove_stop_words:
        words = [w for w in words if w not in STOP_WORDS]
    return Counter(words).most_common(limit)


def analyze(text: str) -> dict[str, object]:
    """Run all analyses and return a summary dictionary for ``text``."""
    words = count_words(text)
    sentences = count_sentences(text)
    paragraphs = count_paragraphs(text)
    score = flesch_reading_ease(text)
    word_list = get_words(text)
    avg_word_len = (sum(len(w) for w in word_list) / len(word_list)) if word_list else 0.0

    return {
        "characters": len(text),
        "characters_no_spaces": len(text.replace(" ", "").replace("\n", "")),
        "words": words,
        "sentences": sentences,
        "paragraphs": paragraphs,
        "avg_word_length": round(avg_word_len, 2),
        "avg_sentence_length": round(words / sentences, 2) if sentences else 0.0,
        "reading_time": estimate_reading_time(words),
        "flesch_reading_ease": score,
        "readability": reading_ease_label(score),
        "top_words": top_words(text),
    }


def format_report(stats: dict[str, object]) -> str:
    """Return a human-readable multi-line report from an ``analyze`` result."""
    lines = [
        "Text Analysis Report",
        "=" * 40,
        f"Characters:            {stats['characters']}",
        f"Characters (no spaces):{stats['characters_no_spaces']}",
        f"Words:                 {stats['words']}",
        f"Sentences:             {stats['sentences']}",
        f"Paragraphs:            {stats['paragraphs']}",
        f"Avg word length:       {stats['avg_word_length']}",
        f"Avg sentence length:   {stats['avg_sentence_length']}",
        f"Estimated read time:   {stats['reading_time']}",
        f"Reading ease (Flesch): {stats['flesch_reading_ease']} ({stats['readability']})",
        "",
        "Top words:",
    ]
    top = stats["top_words"] or []
    if top:
        for word, count in top:  # type: ignore[misc]
            lines.append(f"  {word:<15} {count}")
    else:
        lines.append("  (none)")
    return "\n".join(lines)


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Analyze text and report readability statistics."
    )
    parser.add_argument(
        "path",
        nargs="?",
        help="path to a text file to analyze (reads stdin if omitted or '-')",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)

    if args.path and args.path != "-":
        try:
            with open(args.path, "r", encoding="utf-8") as handle:
                text = handle.read()
        except OSError as exc:
            print(f"Error: {exc}", file=sys.stderr)
            return 1
    else:
        text = sys.stdin.read()

    stats = analyze(text)
    print(format_report(stats))
    return 0


if __name__ == "__main__":
    sys.exit(main())


"""Tests for text_analyzer."""

from __future__ import annotations

import io
import os
import sys
import unittest
from contextlib import redirect_stdout

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import text_analyzer as ta


SAMPLE = (
    "The quick brown fox jumps over the lazy dog. "
    "It was a bright cold day in April, and the clocks were striking thirteen. "
    "Programming is fun!\n\n"
    "Python is a wonderful language. Many people love programming."
)


class TestCounts(unittest.TestCase):
    def test_count_words(self):
        self.assertEqual(ta.count_words("one two three"), 3)
        self.assertEqual(ta.count_words(""), 0)
        self.assertEqual(ta.count_words("don't stop 123"), 3)

    def test_count_sentences(self):
        self.assertEqual(ta.count_sentences("Hello world. How are you? Fine!"), 3)
        self.assertEqual(ta.count_sentences(""), 0)

    def test_count_paragraphs(self):
        self.assertEqual(ta.count_paragraphs("Para one.\n\nPara two."), 2)
        self.assertEqual(ta.count_paragraphs("Single paragraph."), 1)
        self.assertEqual(ta.count_paragraphs(""), 0)

    def test_get_words_normalizes_case(self):
        self.assertEqual(ta.get_words("Hello WORLD hello"), ["hello", "world", "hello"])


class TestSyllables(unittest.TestCase):
    def test_basic_words(self):
        self.assertEqual(ta.count_syllables("the"), 1)
        self.assertEqual(ta.count_syllables("banana"), 3)
        self.assertEqual(ta.count_syllables("programming"), 3)

    def test_empty(self):
        self.assertEqual(ta.count_syllables(""), 0)

    def test_minimum_of_one(self):
        self.assertGreaterEqual(ta.count_syllables("a"), 1)


class TestReadingEase(unittest.TestCase):
    def test_simple_text_scores_high(self):
        score = ta.flesch_reading_ease("The cat sat. The dog ran. I am happy.")
        self.assertGreater(score, 70)

    def test_empty_text_scores_zero(self):
        self.assertEqual(ta.flesch_reading_ease(""), 0.0)

    def test_label_mapping(self):
        self.assertEqual(ta.reading_ease_label(95), "Very Easy")
        self.assertEqual(ta.reading_ease_label(75), "Fairly Easy")
        self.assertEqual(ta.reading_ease_label(20), "Very Confusing")


class TestReadingTime(unittest.TestCase):
    def test_zero_words(self):
        self.assertEqual(ta.estimate_reading_time(0), "0 min")

    def test_short_text_in_seconds(self):
        self.assertTrue(ta.estimate_reading_time(100).endswith("sec"))

    def test_longer_text_in_minutes(self):
        result = ta.estimate_reading_time(1000)
        self.assertTrue(result.endswith("min"))


class TestTopWords(unittest.TestCase):
    def test_returns_most_common(self):
        text = "apple banana apple apple banana cherry"
        result = ta.top_words(text, limit=2)
        self.assertEqual(result[0], ("apple", 3))
        self.assertEqual(len(result), 2)

    def test_stop_words_removed_by_default(self):
        result = ta.top_words("the the the cat", limit=5, remove_stop_words=True)
        words = [w for w, _ in result]
        self.assertNotIn("the", words)
        self.assertIn("cat", words)

    def test_stop_words_kept_when_requested(self):
        result = ta.top_words("the the cat", limit=5, remove_stop_words=False)
        self.assertEqual(result[0], ("the", 2))


class TestAnalyze(unittest.TestCase):
    def test_summary_keys(self):
        stats = ta.analyze(SAMPLE)
        for key in (
            "characters",
            "words",
            "sentences",
            "paragraphs",
            "reading_time",
            "flesch_reading_ease",
            "readability",
            "top_words",
        ):
            self.assertIn(key, stats)

    def test_reasonable_values(self):
        stats = ta.analyze(SAMPLE)
        self.assertGreater(stats["words"], 20)
        self.assertGreaterEqual(stats["sentences"], 4)
        self.assertEqual(stats["paragraphs"], 2)

    def test_format_report_runs(self):
        stats = ta.analyze(SAMPLE)
        report = ta.format_report(stats)
        self.assertIn("Text Analysis Report", report)
        self.assertIn("Words:", report)


class TestCli(unittest.TestCase):
    def test_main_reads_stdin(self):
        buffer = io.StringIO()
        with redirect_stdout(buffer):
            code = ta.main([])
            # No stdin provided in test => empty string analysis still works.
        self.assertEqual(code, 0)
        self.assertIn("Words:", buffer.getvalue())


if __name__ == "__main__":
    unittest.main()

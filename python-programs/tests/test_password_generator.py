"""Tests for password_generator."""

from __future__ import annotations

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import password_generator as pg


class TestBuildPools(unittest.TestCase):
    def test_all_pools_enabled_by_default(self):
        pools = pg.build_pools()
        self.assertEqual(
            set(pools), {"lower", "upper", "digits", "symbols"}
        )

    def test_disable_pools(self):
        pools = pg.build_pools(use_lower=False, use_upper=False)
        self.assertEqual(set(pools), {"digits", "symbols"})

    def test_no_pools_raises(self):
        with self.assertRaises(ValueError):
            pg.build_pools(
                use_lower=False,
                use_upper=False,
                use_digits=False,
                use_symbols=False,
            )

    def test_avoid_ambiguous_removes_confusables(self):
        pools = pg.build_pools(avoid_ambiguous=True)
        joined = "".join(pools.values())
        for ch in pg.AMBIGUOUS:
            self.assertNotIn(ch, joined)


class TestGeneratePassword(unittest.TestCase):
    def test_length_is_respected(self):
        for length in (4, 8, 16, 32):
            self.assertEqual(len(pg.generate_password(length=length)), length)

    def test_invalid_length_raises(self):
        with self.assertRaises(ValueError):
            pg.generate_password(length=0)

    def test_contains_each_enabled_pool(self):
        pwd = pg.generate_password(length=20)
        self.assertTrue(any(c.islower() for c in pwd))
        self.assertTrue(any(c.isupper() for c in pwd))
        self.assertTrue(any(c.isdigit() for c in pwd))
        self.assertTrue(any(c in pg.SYMBOLS for c in pwd))

    def test_digits_only_pool(self):
        pwd = pg.generate_password(
            length=10,
            use_lower=False,
            use_upper=False,
            use_symbols=False,
        )
        self.assertTrue(all(c.isdigit() for c in pwd))

    def test_avoid_ambiguous_output(self):
        pwd = pg.generate_password(length=16, avoid_ambiguous=True)
        for ch in pwd:
            self.assertNotIn(ch, pg.AMBIGUOUS)

    def test_short_password_does_not_crash(self):
        # Length smaller than the number of pools should still work.
        pwd = pg.generate_password(length=2)
        self.assertEqual(len(pwd), 2)

    def test_passwords_differ_between_runs(self):
        # Extremely unlikely to collide with a secure RNG.
        pws = {pg.generate_password(length=24) for _ in range(50)}
        self.assertGreater(len(pws), 45)


class TestEntropy(unittest.TestCase):
    def test_zero_entropy_for_degenerate_input(self):
        self.assertEqual(pg.estimate_entropy(0, 26), 0.0)
        self.assertEqual(pg.estimate_entropy(10, 1), 0.0)

    def test_entropy_scales_with_length(self):
        short = pg.estimate_entropy(8, 26)
        long = pg.estimate_entropy(16, 26)
        self.assertGreater(long, short)

    def test_entropy_known_value(self):
        # 8 chars over a 26-char alphabet => 8 * log2(26).
        import math

        self.assertAlmostEqual(
            pg.estimate_entropy(8, 26), 8 * math.log2(26), places=5
        )


class TestStrengthLabel(unittest.TestCase):
    def test_labels(self):
        self.assertEqual(pg.strength_label(10), "Very Weak")
        self.assertEqual(pg.strength_label(50), "Weak")
        self.assertEqual(pg.strength_label(70), "Reasonable")
        self.assertEqual(pg.strength_label(90), "Strong")
        self.assertEqual(pg.strength_label(120), "Very Strong")


class TestPasswordInfo(unittest.TestCase):
    def test_info_fields(self):
        info = pg.password_info("abcABC123", 62)
        self.assertEqual(info["length"], 9)
        self.assertIn("strength", info)
        self.assertIn("entropy_bits", info)


if __name__ == "__main__":
    unittest.main()

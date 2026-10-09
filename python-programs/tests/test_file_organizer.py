"""Tests for file_organizer."""

from __future__ import annotations

import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import file_organizer as fo


class TestCategorize(unittest.TestCase):
    def test_known_extensions(self):
        self.assertEqual(fo.categorize("photo.JPG"), "Images")
        self.assertEqual(fo.categorize("report.pdf"), "Documents")
        self.assertEqual(fo.categorize("backup.zip"), "Archives")
        self.assertEqual(fo.categorize("song.mp3"), "Audio")
        self.assertEqual(fo.categorize("movie.mp4"), "Video")
        self.assertEqual(fo.categorize("main.py"), "Code")
        self.assertEqual(fo.categorize("data.sqlite"), "Data")

    def test_unknown_extension(self):
        self.assertEqual(fo.categorize("mystery.xyz"), fo.OTHER_CATEGORY)

    def test_no_extension(self):
        self.assertEqual(fo.categorize("README"), fo.OTHER_CATEGORY)


class TestUniqueDestination(unittest.TestCase):
    def test_no_collision(self):
        with tempfile.TemporaryDirectory() as tmp:
            dest = fo.unique_destination(Path(tmp), "file.txt")
            self.assertEqual(dest.name, "file.txt")

    def test_collision_adds_suffix(self):
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "file.txt").write_text("existing")
            dest = fo.unique_destination(Path(tmp), "file.txt")
            self.assertEqual(dest.name, "file (1).txt")

    def test_multiple_collisions(self):
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "file.txt").write_text("a")
            (Path(tmp) / "file (1).txt").write_text("b")
            dest = fo.unique_destination(Path(tmp), "file.txt")
            self.assertEqual(dest.name, "file (2).txt")


class TestPlanOrganization(unittest.TestCase):
    def test_not_a_directory_raises(self):
        with self.assertRaises(NotADirectoryError):
            fo.plan_organization(Path("/definitely/not/a/real/dir"))

    def test_plan_maps_files_to_categories(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            (tmp_path / "a.png").write_text("x")
            (tmp_path / "b.pdf").write_text("y")
            moves = fo.plan_organization(tmp_path)
            by_name = {src.name: dest for src, dest in moves}
            self.assertEqual(by_name["a.png"].parent.name, "Images")
            self.assertEqual(by_name["b.pdf"].parent.name, "Documents")

    def test_plan_skips_directories_and_hidden(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            (tmp_path / "a.png").write_text("x")
            (tmp_path / ".hidden.png").write_text("h")
            (tmp_path / "subdir").mkdir()
            moves = fo.plan_organization(tmp_path)
            self.assertEqual(len(moves), 1)
            self.assertEqual(moves[0][0].name, "a.png")

    def test_plan_does_not_touch_filesystem(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            (tmp_path / "a.png").write_text("x")
            fo.plan_organization(tmp_path)
            # The file is still in place and no category folder was created.
            self.assertTrue((tmp_path / "a.png").exists())
            self.assertFalse((tmp_path / "Images").exists())


class TestExecuteMoves(unittest.TestCase):
    def test_dry_run_makes_no_changes(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            (tmp_path / "a.png").write_text("x")
            moves = fo.plan_organization(tmp_path)
            executed = fo.execute_moves(moves, dry_run=True)
            self.assertEqual(len(executed), 1)
            self.assertTrue((tmp_path / "a.png").exists())
            self.assertFalse((tmp_path / "Images").exists())

    def test_real_move(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            (tmp_path / "a.png").write_text("x")
            moves = fo.plan_organization(tmp_path)
            fo.execute_moves(moves, dry_run=False)
            self.assertFalse((tmp_path / "a.png").exists())
            self.assertTrue((tmp_path / "Images" / "a.png").exists())

    def test_organize_end_to_end(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            (tmp_path / "a.png").write_text("x")
            (tmp_path / "b.pdf").write_text("y")
            (tmp_path / "c.unknownext").write_text("z")
            moves = fo.organize(tmp_path)
            self.assertEqual(len(moves), 3)
            self.assertTrue((tmp_path / "Images" / "a.png").exists())
            self.assertTrue((tmp_path / "Documents" / "b.pdf").exists())
            self.assertTrue((tmp_path / "Other" / "c.unknownext").exists())


if __name__ == "__main__":
    unittest.main()

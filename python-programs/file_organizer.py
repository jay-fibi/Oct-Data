#!/usr/bin/env python3
"""Organize files in a directory into category subfolders.

Files are sorted into folders by their extension (Images, Documents,
Archives, Audio, Video, Code, ...). The operation is safe and reversible in
spirit: a ``--dry-run`` mode previews the moves, and files that would
collide with an existing name are renamed with a numeric suffix instead of
being overwritten.

Only files directly inside the target directory are moved (not recursively),
and existing category folders are skipped to avoid re-organizing.
"""

from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

# Mapping of category name -> set of lower-case extensions (including the dot).
CATEGORY_MAP: dict[str, set[str]] = {
    "Images": {".jpg", ".jpeg", ".png", ".gif", ".bmp", ".svg", ".webp", ".tiff", ".ico"},
    "Documents": {".pdf", ".doc", ".docx", ".txt", ".md", ".rtf", ".odt", ".xls", ".xlsx", ".ppt", ".pptx", ".csv"},
    "Archives": {".zip", ".tar", ".gz", ".bz2", ".xz", ".7z", ".rar"},
    "Audio": {".mp3", ".wav", ".flac", ".aac", ".ogg", ".m4a"},
    "Video": {".mp4", ".mkv", ".mov", ".avi", ".wmv", ".flv", ".webm"},
    "Code": {".py", ".js", ".ts", ".html", ".css", ".java", ".c", ".cpp", ".h", ".go", ".rs", ".rb", ".sh", ".json", ".xml", ".yml", ".yaml"},
    "Data": {".db", ".sqlite", ".sql", ".parquet"},
}

OTHER_CATEGORY = "Other"


def categorize(filename: str) -> str:
    """Return the category name for a given filename based on its extension."""
    ext = Path(filename).suffix.lower()
    for category, extensions in CATEGORY_MAP.items():
        if ext in extensions:
            return category
    return OTHER_CATEGORY


def unique_destination(dest_dir: Path, filename: str) -> Path:
    """Return a non-colliding destination path inside ``dest_dir``.

    If ``filename`` already exists in ``dest_dir``, a numeric suffix is
    inserted before the extension (e.g. ``file (1).txt``).
    """
    candidate = dest_dir / filename
    if not candidate.exists():
        return candidate

    stem = Path(filename).stem
    suffix = Path(filename).suffix
    counter = 1
    while True:
        candidate = dest_dir / f"{stem} ({counter}){suffix}"
        if not candidate.exists():
            return candidate
        counter += 1



def plan_organization(directory: Path) -> list[tuple[Path, Path]]:
    """Return a list of (source, destination) moves for ``directory``.

    Does not touch the filesystem. Skips subdirectories and hidden files.
    """
    directory = Path(directory)
    if not directory.is_dir():
        raise NotADirectoryError(f"Not a directory: {directory}")

    category_dirs = {directory / name for name in CATEGORY_MAP}
    category_dirs.add(directory / OTHER_CATEGORY)

    moves: list[tuple[Path, Path]] = []
    for entry in sorted(directory.iterdir()):
        if not entry.is_file():
            continue
        if entry.name.startswith("."):
            continue
        if entry in category_dirs:
            continue

        category = categorize(entry.name)
        dest_dir = directory / category
        destination = unique_destination(dest_dir, entry.name)
        moves.append((entry, destination))
    return moves


def execute_moves(
    moves: list[tuple[Path, Path]], dry_run: bool = False
) -> list[tuple[Path, Path]]:
    """Perform (or preview) the planned moves.

    Creates destination folders as needed. Returns the list of executed moves.
    """
    executed: list[tuple[Path, Path]] = []
    for source, destination in moves:
        if dry_run:
            print(f"[dry-run] {source.name}  ->  {destination}")
        else:
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(source), str(destination))
            print(f"{source.name}  ->  {destination}")
        executed.append((source, destination))
    return executed


def organize(directory: Path, dry_run: bool = False) -> list[tuple[Path, Path]]:
    """Plan and execute organization of ``directory``."""
    moves = plan_organization(directory)
    return execute_moves(moves, dry_run=dry_run)


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Organize files in a directory into category subfolders."
    )
    parser.add_argument("directory", help="directory to organize")
    parser.add_argument(
        "-n",
        "--dry-run",
        action="store_true",
        help="preview the moves without changing anything",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)

    try:
        moves = organize(args.directory, dry_run=args.dry_run)
    except (NotADirectoryError, FileNotFoundError) as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1

    verb = "would be moved" if args.dry_run else "moved"
    print(f"\n{len(moves)} file(s) {verb}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

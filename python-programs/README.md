# Python Programs

A small collection of self-contained, dependency-free Python utilities using
only the standard library. Each program works both as an importable module and
as a command-line tool, and is covered by unit tests (Python's built-in
`unittest` — no third-party packages required).

## Contents

| Program | Description |
| --- | --- |
| [`password_generator.py`](password_generator.py) | Cryptographically secure random password generator with entropy/strength estimation. |
| [`file_organizer.py`](file_organizer.py) | Sort files in a directory into category subfolders (Images, Documents, ...) with a safe dry-run mode. |
| [`text_analyzer.py`](text_analyzer.py) | Compute readability statistics (word/sentence counts, reading time, Flesch Reading Ease, top words). |

## Requirements

* Python 3.9+ (developed and tested on Python 3.11)
* No external dependencies.

## Usage

### Password Generator

```bash
# Generate one 16-character password
python password_generator.py

# Generate 5 passwords of length 20, digits + lowercase only
python password_generator.py -c 5 -l 20 --no-upper --no-symbols

# Avoid easily confused characters (O/0, l/1, ...)
python password_generator.py --avoid-ambiguous
```

### File Organizer

```bash
# Preview what would happen (no changes made)
python file_organizer.py /path/to/folder --dry-run

# Actually organize the folder
python file_organizer.py /path/to/folder
```

Files are moved into subfolders such as `Images/`, `Documents/`, `Archives/`,
`Audio/`, `Video/`, `Code/`, `Data/`, and `Other/`. Name collisions are
resolved by appending a numeric suffix, so nothing is ever overwritten.

### Text Analyzer

```bash
# Analyze a file
python text_analyzer.py article.txt

# Analyze text from stdin
echo "Hello world. This is a test." | python text_analyzer.py
```

## Running the Tests

From this directory:

```bash
python -m unittest discover -s tests -v
```

or using the helper script:

```bash
./run_tests.sh
```

## Programmatic Use

Each module exposes pure functions you can import and reuse, for example:

```python
from password_generator import generate_password
from text_analyzer import analyze

print(generate_password(length=20))
print(analyze("The cat sat on the mat. The dog ran fast."))
```

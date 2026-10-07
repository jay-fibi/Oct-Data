# Python Word Frequency Demo

One standalone console program that counts the most common words in a text file
or on standard input. It uses only the Python standard library and does not
depend on the Java or Android projects.

## Requirements

Python 3.8 or newer (developed and verified with CPython 3.11). No third-party
packages, no installation step.

## Run

From this directory:

```sh
python3 word_frequency.py sample.txt
```

Expected output:

```text
Word frequency report
Tokens: 24
Unique words: 15
Showing top 10

RANK  WORD   COUNT
   1  fox        4
   2  the        4
   3  a          2
   4  dog        2
   5  quick      2
   6  and        1
   7  away       1
   8  barks      1
   9  brown      1
  10  happy      1
```

## Options

| Argument | Meaning |
| --- | --- |
| `path` | UTF-8 text file to read. Use `-` or omit it to read standard input. |
| `--top N` | Show at most `N` words (default `10`). Use `0` to show every word. |
| `--min-length N` | Ignore words shorter than `N` characters (default `1`). |

## Examples

Read from standard input and show the five most common words:

```sh
cat sample.txt | python3 word_frequency.py --top 5
```

Ignore short words and list the complete ranking:

```sh
python3 word_frequency.py sample.txt --top 0 --min-length 4
```

```text
Word frequency report
Tokens: 10
Unique words: 9

RANK  WORD   COUNT
   1  quick      2
   2  away       1
   3  barks      1
   4  brown      1
   5  happy      1
   6  jumps      1
   7  lazy       1
   8  over       1
   9  runs       1
```

## How it works

1. The whole input is lowercased and split into words by the regular expression
   `[a-z0-9']+`, which keeps contractions such as `don't` intact.
2. `collections.Counter` tallies the words, then the result is sorted by
   descending count with ties broken alphabetically. The ordering is therefore
   fully deterministic for identical input.
3. Column widths are computed from the data, so the table stays aligned for any
   input.

Exit codes: `0` on success (including when the input contains no words, in
which case the report says `No words found.`), `2` for a usage error or an
unreadable file. Errors are written to standard error.

## Limitations

- Word characters are ASCII letters, digits, and apostrophes. Accented letters
  are treated as separators, so `café` is counted as `caf`.
- No stemming, no stop-word removal, and no language detection; `runs` and
  `running` are counted separately.
- The entire input is read into memory before counting, so the program is meant
  for demonstration-sized files rather than streaming multi-gigabyte logs.

# Java Console Demos

Six standalone console programs demonstrating core Java and the standard
library. Each file compiles on its own, uses only the Java standard library,
and requires no build tool, no external dependencies, and no Android project.

## Programs

| Program | Demonstrates |
| --- | --- |
| `ScoreStatisticsDemo.java` | Arrays, loops, methods, input validation, formatted output |
| `CollectionsDemo.java` | Lists, sets, sorting with comparators, streams, records |
| `StringManipulationDemo.java` | Splitting, trimming, joining, `StringBuilder` |
| `DateTimeDemo.java` | `java.time` dates, durations, `DateTimeFormatter` |
| `FileIoDemo.java` | NIO.2 buffered read/write, try-with-resources |
| `ExceptionHandlingDemo.java` | Custom checked exception, try/catch/finally |

## Compile and run

Requires JDK 17 or newer. From this directory, compile everything at once:

```sh
javac --release 17 -d out *.java
```

Then run any program by its class name:

```sh
java -cp out ScoreStatisticsDemo
java -cp out CollectionsDemo
java -cp out StringManipulationDemo
java -cp out DateTimeDemo
java -cp out FileIoDemo
java -cp out ExceptionHandlingDemo
```

To compile a single program instead:

```sh
javac --release 17 -d out CollectionsDemo.java
java -cp out CollectionsDemo
```

The `out/` directory and `*.class` files are ignored by git.

## Expected output

### ScoreStatisticsDemo

Change the `scores` array in `main` to try different data. Scores must be
integers from 0 to 100; the statistics method rejects null, empty, and
out-of-range inputs with an `IllegalArgumentException`.

```text
Java Score Statistics Demo
Number of scores: 5
Lowest score: 68
Highest score: 94
Average score: 82.00
```

### CollectionsDemo

Copies an immutable `List.of(...)` before sorting, de-duplicates names into a
`LinkedHashSet`, sorts by price with a `Comparator`, and totals with a stream.

```text
Java Collections Demo
Total products: 4
Unique names: [Keyboard, Mouse, Monitor]
Sorted by price (high to low):
  Monitor  $219.00
  Keyboard $49.99
  Mouse    $19.50
  Mouse    $19.50
Combined price: $307.99
```

### StringManipulationDemo

Splits a messy comma-separated string, trims and skips empty tokens,
title-cases each item, and joins them back together. Also checks a palindrome
using `StringBuilder.reverse()`.

```text
Java String Manipulation Demo
Raw input: "  java,  python ,,C++, go  "
Normalized items: Java | Python | C++ | Go
Item count: 4
Palindrome check (Racecar): true
```

### DateTimeDemo

Computes a day count with `ChronoUnit`, derives a quarter, and formats a
deadline. The "today" and duration lines depend on the current date.

```text
Java Date/Time Demo
Launch date: 2024-01-15
Day of week: MONDAY
Today: 2026-10-09
Days since launch: 998
Launch quarter: Q1
Deadline: 2026-12-31 23:59
Hours until deadline: 1998
```

### FileIoDemo

Writes three lines to a temp file with a `BufferedWriter`, reads them back
with a `BufferedReader`, then deletes the file in a `finally` block. The file
name is a randomly generated temp name, so it differs on every run.

```text
Java File I/O Demo
File name: java-file-io-demo8780803689522170335.txt
  1: alpha (5 chars)
  2: beta (4 chars)
  3: gamma (5 chars)
Line count: 3
Total characters: 14
```

### ExceptionHandlingDemo

A small `Account` class throws a custom checked `InsufficientFundsException`
for overdrafts and an unchecked `IllegalArgumentException` for invalid amounts;
`main` catches both and always reports the balance in a `finally` block.

```text
Java Exception Handling Demo
Withdrew $40.00
  balance now $60.00
Rejected $500.00, short by $440.00
  balance now $60.00
Rejected: Withdrawal must be positive.
  balance now $60.00
Final balance: $60.00
```

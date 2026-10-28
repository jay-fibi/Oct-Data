# Java Score Statistics Demo

One standalone console program demonstrating arrays, loops, methods, input
validation, and formatted output using only the Java standard library.
It does not require the Android project or any external dependencies.

## Compile and run

Requires JDK 17 or newer. From this directory:

```sh
javac --release 17 -d out ScoreStatisticsDemo.java
java -cp out ScoreStatisticsDemo
```

Expected output:

```text
Java Score Statistics Demo
Number of scores: 5
Lowest score: 68
Highest score: 94
Average score: 82.00
```

Change the `scores` array in `main` to try different data, then recompile.
Scores must be integers from 0 to 100. The statistics method rejects null,
empty, and out-of-range inputs with an `IllegalArgumentException`.

## Simple calculator

`SimpleCalculator.java` is a second standalone demo: four arithmetic methods
(`add`, `subtract`, `multiply`, `divide`) plus a `main` that prints a few sample
operations. `divide` throws an `ArithmeticException` when the divisor is zero.

```sh
javac --release 17 -d out SimpleCalculator.java
java -cp out SimpleCalculator
```

Expected output:

```text
Java Simple Calculator Demo
10 + 5 = 15
10 - 5 = 5
10 * 5 = 50
10 / 5 = 2
10 / 4 = 2.50
1 / 0 -> Error: Cannot divide by zero.
```

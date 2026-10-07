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
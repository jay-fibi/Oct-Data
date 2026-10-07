# Java Demos

A small collection of standalone Java programs used for demonstration and
learning. Each program lives in its own file and has its own `main` method, so
they can be compiled and run independently.

## Demos

| # | File | What it shows |
|---|------|---------------|
| 1 | `src/HelloWorld.java` | `main` method, console output, command-line arguments |
| 2 | `src/FizzBuzz.java` | Loops, the modulo operator, `if`/`else-if` branching |
| 3 | `src/Fibonacci.java` | Iterative vs. memoized vs. naive recursion |
| 4 | `src/PalindromeChecker.java` | String handling, two-pointer scanning, input normalisation |
| 5 | `src/BubbleSort.java` | Arrays, nested loops, swapping, early-exit optimisation |

## Requirements

A JDK 17 or newer (`javac` and `java` on your `PATH`).

## Running

Run everything with the helper script:

```bash
./run.sh
```

Run a single demo (extra arguments are forwarded to the program):

```bash
./run.sh HelloWorld Ada Lovelace
./run.sh FizzBuzz 30
./run.sh Fibonacci 25
./run.sh PalindromeChecker "A man, a plan, a canal: Panama"
./run.sh BubbleSort 5 3 8 1 9 2
```

Or compile and run manually:

```bash
javac -d out src/*.java
java -cp out HelloWorld
java -cp out FizzBuzz 30
java -cp out Fibonacci 25
java -cp out PalindromeChecker "racecar"
java -cp out BubbleSort 5 3 8 1 9 2
```

## Example output

```
$ java -cp out FizzBuzz 5
1
2
Fizz
4
Buzz

$ java -cp out PalindromeChecker "racecar"
"racecar" -> PALINDROME
```

The `out/` directory holds compiled `.class` files and is ignored by Git.

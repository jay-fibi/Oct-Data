import java.util.ArrayList;
import java.util.List;

/**
 * Demo 3 - Fibonacci
 *
 * Shows three different ways to compute the Fibonacci sequence:
 *   1. an iterative loop (fast, O(n))
 *   2. a memoized recursion (fast, demonstrates caching)
 *   3. a naive recursion (slow, demonstrates exponential growth)
 *
 * Run:
 *   java Fibonacci        (first 15 numbers)
 *   java Fibonacci 25     (first 25 numbers)
 */
public class Fibonacci {

    private static final int DEFAULT_COUNT = 15;

    public static void main(String[] args) {
        int count = DEFAULT_COUNT;
        if (args.length > 0) {
            try {
                count = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("'" + args[0] + "' is not an integer, using default " + DEFAULT_COUNT);
            }
        }

        System.out.println("First " + count + " Fibonacci numbers");
        System.out.println("Iterative : " + iterative(count));

        long start = System.nanoTime();
        long memoValue = memoized(count - 1);
        long memoMicros = (System.nanoTime() - start) / 1_000;
        System.out.println("Memoized  : fib(" + (count - 1) + ") = " + memoValue + "  (" + memoMicros + " us)");

        // Naive recursion gets expensive quickly, so cap it for the demo.
        int naiveN = Math.min(count - 1, 30);
        start = System.nanoTime();
        long naiveValue = naive(naiveN);
        long naiveMicros = (System.nanoTime() - start) / 1_000;
        System.out.println("Naive     : fib(" + naiveN + ") = " + naiveValue + "  (" + naiveMicros + " us)");
    }

    /** Builds the first {@code count} Fibonacci numbers with a simple loop. */
    static List<Long> iterative(int count) {
        List<Long> series = new ArrayList<>();
        long a = 0;
        long b = 1;
        for (int i = 0; i < count; i++) {
            series.add(a);
            long next = a + b;
            a = b;
            b = next;
        }
        return series;
    }

    /** Recursive Fibonacci with a cache so each n is computed only once. */
    static long memoized(int n) {
        long[] cache = new long[n + 1];
        return memoizedHelper(n, cache);
    }

    private static long memoizedHelper(int n, long[] cache) {
        if (n <= 1) {
            return n;
        }
        if (cache[n] != 0) {
            return cache[n];
        }
        long value = memoizedHelper(n - 1, cache) + memoizedHelper(n - 2, cache);
        cache[n] = value;
        return value;
    }

    /** Plain, uncached recursion - included to show why caching matters. */
    static long naive(int n) {
        if (n <= 1) {
            return n;
        }
        return naive(n - 1) + naive(n - 2);
    }
}

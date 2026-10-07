/**
 * Demo 2 - FizzBuzz
 *
 * A classic programming exercise that demonstrates loops, the modulo
 * operator and if/else-if branching.
 *
 *   - multiples of 3        -> "Fizz"
 *   - multiples of 5        -> "Buzz"
 *   - multiples of 3 and 5  -> "FizzBuzz"
 *   - everything else       -> the number itself
 *
 * Run:
 *   java FizzBuzz          (prints 1..20)
 *   java FizzBuzz 50       (prints 1..50)
 */
public class FizzBuzz {

    private static final int DEFAULT_LIMIT = 20;

    public static void main(String[] args) {
        int limit = DEFAULT_LIMIT;
        if (args.length > 0) {
            try {
                limit = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("'" + args[0] + "' is not an integer, using default " + DEFAULT_LIMIT);
            }
        }

        for (int i = 1; i <= limit; i++) {
            System.out.println(fizzBuzz(i));
        }
    }

    /** Returns the FizzBuzz representation for a single number. */
    static String fizzBuzz(int n) {
        if (n % 15 == 0) {
            return "FizzBuzz";
        } else if (n % 3 == 0) {
            return "Fizz";
        } else if (n % 5 == 0) {
            return "Buzz";
        }
        return Integer.toString(n);
    }
}

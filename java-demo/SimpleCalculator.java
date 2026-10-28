import java.util.Locale;

/** A minimal calculator built with plain methods and the Java standard library. */
public final class SimpleCalculator {
    private SimpleCalculator() {
    }

    public static void main(String[] args) {
        System.out.println("Java Simple Calculator Demo");
        printOperation("10 + 5", add(10, 5));
        printOperation("10 - 5", subtract(10, 5));
        printOperation("10 * 5", multiply(10, 5));
        printOperation("10 / 5", divide(10, 5));
        printOperation("10 / 4", divide(10, 4));

        try {
            divide(1, 0);
        } catch (ArithmeticException exception) {
            System.out.println("1 / 0 -> Error: " + exception.getMessage());
        }
    }

    public static double add(double left, double right) {
        return left + right;
    }

    public static double subtract(double left, double right) {
        return left - right;
    }

    public static double multiply(double left, double right) {
        return left * right;
    }

    public static double divide(double left, double right) {
        if (right == 0) {
            throw new ArithmeticException("Cannot divide by zero.");
        }
        return left / right;
    }

    private static void printOperation(String expression, double result) {
        System.out.printf(Locale.ROOT, "%s = %s%n", expression, format(result));
    }

    /** Prints whole numbers without a decimal part, everything else with two decimals. */
    private static String format(double value) {
        if (value == Math.rint(value) && Double.isFinite(value)) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }
}

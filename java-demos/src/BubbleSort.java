import java.util.Arrays;

/**
 * Demo 5 - BubbleSort
 *
 * Sorts an array of integers using the bubble sort algorithm and prints the
 * array after every pass so the "bubbling" behaviour is visible.
 *
 * Demonstrates arrays, nested loops, swapping, early-exit optimisation and
 * converting between strings and numbers.
 *
 * Run:
 *   java BubbleSort                      (sorts a built-in array)
 *   java BubbleSort 5 3 8 1 9 2          (sorts the supplied numbers)
 */
public class BubbleSort {

    private static final int[] DEFAULT_DATA = {5, 1, 4, 2, 8, 0, 9, 3, 7, 6};

    public static void main(String[] args) {
        int[] data = parse(args);
        System.out.println("Input : " + Arrays.toString(data));

        sort(data);

        System.out.println("Sorted: " + Arrays.toString(data));
    }

    /** Sorts {@code data} in place, printing the array after each pass. */
    static void sort(int[] data) {
        int n = data.length;
        for (int pass = 0; pass < n - 1; pass++) {
            boolean swapped = false;
            for (int i = 0; i < n - 1 - pass; i++) {
                if (data[i] > data[i + 1]) {
                    int temp = data[i];
                    data[i] = data[i + 1];
                    data[i + 1] = temp;
                    swapped = true;
                }
            }
            System.out.printf("Pass %d: %s%n", pass + 1, Arrays.toString(data));

            // If nothing moved this pass the array is already sorted.
            if (!swapped) {
                System.out.println("No swaps in this pass -> array is sorted, stopping early.");
                break;
            }
        }
    }

    /** Parses command-line arguments, falling back to the default data set. */
    private static int[] parse(String[] args) {
        if (args.length == 0) {
            return DEFAULT_DATA.clone();
        }
        int[] values = new int[args.length];
        for (int i = 0; i < args.length; i++) {
            try {
                values[i] = Integer.parseInt(args[i]);
            } catch (NumberFormatException e) {
                System.err.println("'" + args[i] + "' is not an integer, ignoring it.");
                values[i] = 0;
            }
        }
        return values;
    }
}

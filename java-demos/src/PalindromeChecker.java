import java.util.Locale;

/**
 * Demo 4 - PalindromeChecker
 *
 * Determines whether a piece of text reads the same forwards and backwards.
 * Demonstrates String manipulation, StringBuilder, two-pointer scanning
 * and basic input handling.
 *
 * The check ignores case, whitespace and punctuation, so
 * "A man, a plan, a canal: Panama" counts as a palindrome.
 *
 * Run:
 *   java PalindromeChecker                       (runs built-in examples)
 *   java PalindromeChecker "racecar"
 *   java PalindromeChecker "not a palindrome"
 */
public class PalindromeChecker {

    public static void main(String[] args) {
        if (args.length > 0) {
            String input = String.join(" ", args);
            report(input);
            return;
        }

        // Built-in examples when no argument is supplied.
        String[] samples = {
                "racecar",
                "A man, a plan, a canal: Panama",
                "Was it a car or a cat I saw?",
                "Java",
                "No 'x' in Nixon",
                ""
        };
        System.out.println("Running built-in examples:");
        for (String sample : samples) {
            report(sample);
        }
    }

    /** Prints a formatted result line for one input. */
    private static void report(String input) {
        boolean palindrome = isPalindrome(input);
        System.out.printf("\"%s\" -> %s%n", input, palindrome ? "PALINDROME" : "not a palindrome");
        if (!palindrome) {
            System.out.println("   reversed: \"" + new StringBuilder(input).reverse() + "\"");
        }
    }

    /**
     * Returns true when {@code text} is a palindrome once case, spaces and
     * non-alphanumeric characters are removed.
     */
    static boolean isPalindrome(String text) {
        String cleaned = normalize(text);
        int left = 0;
        int right = cleaned.length() - 1;
        while (left < right) {
            if (cleaned.charAt(left) != cleaned.charAt(right)) {
                return false;
            }
            left++;
            right--;
        }
        return true;
    }

    /** Lower-cases the text and keeps only letters and digits. */
    static String normalize(String text) {
        StringBuilder builder = new StringBuilder();
        for (char c : text.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                builder.append(c);
            }
        }
        return builder.toString();
    }
}

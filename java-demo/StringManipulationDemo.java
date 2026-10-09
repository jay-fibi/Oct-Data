import java.util.Locale;

/** Demonstrates splitting, trimming, joining, and StringBuilder operations. */
public final class StringManipulationDemo {
    private StringManipulationDemo() {
    }

    public static void main(String[] args) {
        String raw = "  java,  python ,,C++, go  ";

        System.out.println("Java String Manipulation Demo");
        System.out.println("Raw input: \"" + raw + "\"");

        StringBuilder joined = new StringBuilder();
        int count = 0;
        for (String token : raw.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (count > 0) {
                joined.append(" | ");
            }
            joined.append(Character.toUpperCase(trimmed.charAt(0)))
                    .append(trimmed.substring(1).toLowerCase(Locale.ROOT));
            count++;
        }
        System.out.println("Normalized items: " + joined);
        System.out.println("Item count: " + count);

        String word = "Racecar";
        String reversed = new StringBuilder(word).reverse().toString();
        System.out.println("Palindrome check (" + word + "): " + word.equalsIgnoreCase(reversed));
    }
}

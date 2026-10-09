import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Demonstrates lists, sets, sorting with comparators, and streams. */
public final class CollectionsDemo {
    private CollectionsDemo() {
    }

    /** A simple value object, using a record (available since Java 16). */
    public record Product(String name, double price) {
    }

    public static void main(String[] args) {
        // List.of() is immutable, so copy it before sorting in place.
        List<Product> products = new ArrayList<>(List.of(
                new Product("Keyboard", 49.99),
                new Product("Mouse", 19.50),
                new Product("Monitor", 219.00),
                new Product("Mouse", 19.50)));

        Set<String> uniqueNames = new LinkedHashSet<>();
        for (Product product : products) {
            uniqueNames.add(product.name());
        }

        products.sort(Comparator.comparingDouble(Product::price).reversed());

        System.out.println("Java Collections Demo");
        System.out.println("Total products: " + products.size());
        System.out.println("Unique names: " + uniqueNames);
        System.out.println("Sorted by price (high to low):");
        for (Product product : products) {
            System.out.printf(Locale.ROOT, "  %-8s $%.2f%n", product.name(), product.price());
        }

        double total = products.stream()
                .mapToDouble(Product::price)
                .sum();
        System.out.printf(Locale.ROOT, "Combined price: $%.2f%n", total);
    }
}

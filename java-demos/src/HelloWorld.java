/**
 * Demo 1 - HelloWorld
 *
 * The classic entry point. Demonstrates a main method, console output,
 * command-line arguments and basic String formatting.
 *
 * Run:
 *   java HelloWorld
 *   java HelloWorld Ada Lovelace
 */
public class HelloWorld {

    public static void main(String[] args) {
        String name = (args.length > 0) ? String.join(" ", args) : "World";

        System.out.println("Hello, " + name + "!");
        System.out.printf("This JVM is running Java %s on %s (%s).%n",
                System.getProperty("java.version"),
                System.getProperty("os.name"),
                System.getProperty("os.arch"));

        // A tiny loop so the demo produces more than one line of output.
        for (int i = 1; i <= 3; i++) {
            System.out.println("  greeting #" + i + " -> Hello, " + name + "!");
        }
    }
}

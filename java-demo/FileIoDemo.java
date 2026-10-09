import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Demonstrates buffered text file writing and reading with try-with-resources. */
public final class FileIoDemo {
    private FileIoDemo() {
    }

    public static void main(String[] args) throws IOException {
        Path file = Files.createTempFile("java-file-io-demo", ".txt");
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                writer.write("alpha");
                writer.newLine();
                writer.write("beta");
                writer.newLine();
                writer.write("gamma");
            }

            System.out.println("Java File I/O Demo");
            System.out.println("File name: " + file.getFileName());

            int lineNumber = 0;
            int totalCharacters = 0;
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    totalCharacters += line.length();
                    System.out.printf(Locale.ROOT, "  %d: %s (%d chars)%n",
                            lineNumber, line, line.length());
                }
            }

            System.out.println("Line count: " + lineNumber);
            System.out.println("Total characters: " + totalCharacters);
        } finally {
            Files.deleteIfExists(file);
        }
    }
}

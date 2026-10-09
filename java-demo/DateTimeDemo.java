import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/** Demonstrates the java.time API for dates, durations, and formatting. */
public final class DateTimeDemo {
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private DateTimeDemo() {
    }

    public static void main(String[] args) {
        LocalDate launch = LocalDate.of(2024, 1, 15);
        LocalDate today = LocalDate.now();

        System.out.println("Java Date/Time Demo");
        System.out.println("Launch date: " + launch);
        System.out.println("Day of week: " + launch.getDayOfWeek());
        System.out.println("Today: " + today);
        System.out.println("Days since launch: " + ChronoUnit.DAYS.between(launch, today));
        System.out.printf(Locale.ROOT, "Launch quarter: Q%d%n",
                ((launch.getMonthValue() - 1) / 3) + 1);

        LocalDateTime deadline = LocalDateTime.of(2026, 12, 31, 23, 59);
        Duration remaining = Duration.between(LocalDateTime.now(), deadline);
        System.out.println("Deadline: " + STAMP.format(deadline));
        System.out.println("Hours until deadline: " + remaining.toHours());
    }
}

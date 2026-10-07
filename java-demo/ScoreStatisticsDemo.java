import java.util.Locale;

/** Demonstrates arrays, loops, methods, and formatted console output. */
public final class ScoreStatisticsDemo {
    private ScoreStatisticsDemo() {
    }

    public static void main(String[] args) {
        int[] scores = {72, 85, 91, 68, 94};
        printStatistics(scores);
    }

    public static void printStatistics(int[] scores) {
        if (scores == null || scores.length == 0) {
            throw new IllegalArgumentException("Provide at least one score.");
        }

        long total = 0;
        int lowest = scores[0];
        int highest = scores[0];
        for (int score : scores) {
            if (score < 0 || score > 100) {
                throw new IllegalArgumentException("Scores must be between 0 and 100.");
            }
            total += score;
            lowest = Math.min(lowest, score);
            highest = Math.max(highest, score);
        }

        double average = (double) total / scores.length;
        System.out.println("Java Score Statistics Demo");
        System.out.println("Number of scores: " + scores.length);
        System.out.println("Lowest score: " + lowest);
        System.out.println("Highest score: " + highest);
        System.out.printf(Locale.ROOT, "Average score: %.2f%n", average);
    }
}
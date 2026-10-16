import java.util.Scanner;

/**
 * A simple console-based calculator that performs basic arithmetic operations.
 */
public class Calculator {

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        boolean running = true;

        System.out.println("=== Simple Java Calculator ===");
        System.out.println("Operations: +  -  *  /  (type 'quit' to exit)");

        while (running) {
            System.out.print("\nEnter first number: ");
            String input1 = scanner.nextLine().trim();
            if (input1.equalsIgnoreCase("quit")) {
                running = false;
                break;
            }

            double num1;
            try {
                num1 = Double.parseDouble(input1);
            } catch (NumberFormatException e) {
                System.out.println("Invalid number. Please try again.");
                continue;
            }

            System.out.print("Enter operator (+, -, *, /): ");
            String op = scanner.nextLine().trim();
            if (op.equalsIgnoreCase("quit")) {
                running = false;
                break;
            }

            System.out.print("Enter second number: ");
            String input2 = scanner.nextLine().trim();
            if (input2.equalsIgnoreCase("quit")) {
                running = false;
                break;
            }

            double num2;
            try {
                num2 = Double.parseDouble(input2);
            } catch (NumberFormatException e) {
                System.out.println("Invalid number. Please try again.");
                continue;
            }

            double result;
            switch (op) {
                case "+":
                    result = num1 + num2;
                    break;
                case "-":
                    result = num1 - num2;
                    break;
                case "*":
                    result = num1 * num2;
                    break;
                case "/":
                    if (num2 == 0) {
                        System.out.println("Error: Division by zero is not allowed.");
                        continue;
                    }
                    result = num1 / num2;
                    break;
                default:
                    System.out.println("Invalid operator. Please use +, -, *, or /.");
                    continue;
            }

            System.out.printf("Result: %.2f %s %.2f = %.2f%n", num1, op, num2, result);
        }

        System.out.println("Goodbye!");
        scanner.close();
    }
}
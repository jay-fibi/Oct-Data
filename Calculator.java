import java.util.Locale;
import java.util.Scanner;

public class Calculator {
    public static void main(String[] args) {
        try (Scanner scanner = new Scanner(System.in).useLocale(Locale.US)) {
            System.out.println("Simple Calculator (+, -, *, /)");
            System.out.print("Enter first number: ");
            if (!scanner.hasNextDouble()) {
                System.out.println("Error: Please enter a valid number.");
                return;
            }
            double firstNumber = scanner.nextDouble();

            System.out.print("Enter operator (+, -, *, /): ");
            if (!scanner.hasNext()) {
                System.out.println("Error: An operator is required.");
                return;
            }
            String operator = scanner.next();

            System.out.print("Enter second number: ");
            if (!scanner.hasNextDouble()) {
                System.out.println("Error: Please enter a valid number.");
                return;
            }
            double secondNumber = scanner.nextDouble();

            if (!Double.isFinite(firstNumber) || !Double.isFinite(secondNumber)) {
                System.out.println("Error: Numbers must be finite.");
                return;
            }

            double result;
            switch (operator) {
                case "+":
                    result = firstNumber + secondNumber;
                    break;
                case "-":
                    result = firstNumber - secondNumber;
                    break;
                case "*":
                    result = firstNumber * secondNumber;
                    break;
                case "/":
                    if (secondNumber == 0) {
                        System.out.println("Error: Cannot divide by zero.");
                        return;
                    }
                    result = firstNumber / secondNumber;
                    break;
                default:
                    System.out.println("Error: Unsupported operator. Use +, -, *, or /.");
                    return;
            }

            System.out.println("Result: " + result);
        }
    }
}
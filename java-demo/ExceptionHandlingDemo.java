import java.util.Locale;

/** Demonstrates a custom checked exception plus try/catch/finally handling. */
public final class ExceptionHandlingDemo {
    private ExceptionHandlingDemo() {
    }

    /** Thrown when a withdrawal exceeds the available balance. */
    static final class InsufficientFundsException extends Exception {
        private static final long serialVersionUID = 1L;
        private final double shortfall;

        InsufficientFundsException(double shortfall) {
            super("Short by " + shortfall);
            this.shortfall = shortfall;
        }

        double shortfall() {
            return shortfall;
        }
    }

    /** A minimal bank account that validates its own withdrawals. */
    static final class Account {
        private double balance;

        Account(double balance) {
            if (balance < 0) {
                throw new IllegalArgumentException("Opening balance cannot be negative.");
            }
            this.balance = balance;
        }

        double balance() {
            return balance;
        }

        void withdraw(double amount) throws InsufficientFundsException {
            if (amount <= 0) {
                throw new IllegalArgumentException("Withdrawal must be positive.");
            }
            if (amount > balance) {
                throw new InsufficientFundsException(amount - balance);
            }
            balance -= amount;
        }
    }

    public static void main(String[] args) {
        System.out.println("Java Exception Handling Demo");

        Account account = new Account(100.0);
        attemptWithdrawal(account, 40.0);
        attemptWithdrawal(account, 500.0);
        attemptWithdrawal(account, -5.0);
        System.out.printf(Locale.ROOT, "Final balance: $%.2f%n", account.balance());
    }

    private static void attemptWithdrawal(Account account, double amount) {
        try {
            account.withdraw(amount);
            System.out.printf(Locale.ROOT, "Withdrew $%.2f%n", amount);
        } catch (InsufficientFundsException ex) {
            System.out.printf(Locale.ROOT, "Rejected $%.2f, short by $%.2f%n",
                    amount, ex.shortfall());
        } catch (IllegalArgumentException ex) {
            System.out.println("Rejected: " + ex.getMessage());
        } finally {
            System.out.printf(Locale.ROOT, "  balance now $%.2f%n", account.balance());
        }
    }
}

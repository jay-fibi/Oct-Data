package com.jayfibi.calculator.core;

import java.math.BigDecimal;
import java.math.MathContext;

/** A bounded decimal calculator with no platform dependencies. */
public final class CalculatorEngine {
    private static final int MAX_EXPRESSION_LENGTH = 4096;
    private static final int MAX_NESTING = 128;
    private static final int MAX_RESULT_LENGTH = 4096;
    private static final MathContext PRECISION = MathContext.DECIMAL128;

    private CalculatorEngine() {
    }

    /**
     * Evaluates decimal +, -, *, /, parentheses, unary signs, and postfix %.
     * Percentage is always a literal division by 100: 200 + 10% is 200.1,
     * not 220. Repeated percentages divide by 100 each time. Multiplication
     * is explicit, and exponent notation is not accepted. Arithmetic uses
     * DECIMAL128 (34 significant digits, half-even rounding); literals remain
     * exact until used in arithmetic. The result has no exponent or trailing
     * fractional zeros. Expressions/results are limited to 4096 characters,
     * with at most 128 nested parentheses.
     *
     * @throws IllegalArgumentException for invalid/oversized expressions,
     *         division by zero, or results outside the supported range
     */
    public static String evaluate(String expression) throws IllegalArgumentException {
        if (expression == null || expression.length() == 0
                || expression.length() > MAX_EXPRESSION_LENGTH) {
            throw new IllegalArgumentException("Enter an expression of 1 to 4096 characters.");
        }
        String normalized = expression.replace('\u00d7', '*')
                .replace('\u00f7', '/').replace('\u2212', '-');
        try {
            Parser parser = new Parser(normalized);
            BigDecimal value = parser.expression();
            parser.skipSpace();
            if (!parser.atEnd()) {
                throw parser.invalid("Unexpected character");
            }
            if (value.signum() == 0) {
                return "0";
            }
            value = value.stripTrailingZeros();
            checkRange(value);
            String result = value.toPlainString();
            if (result.length() > MAX_RESULT_LENGTH) {
                throw new IllegalArgumentException("Result is too large.");
            }
            return result;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Arithmetic result is not supported.", exception);
        }
    }

    private static BigDecimal checkRange(BigDecimal value) {
        if (value.signum() != 0) {
            long integerDigits = (long) value.precision() - value.scale();
            if (integerDigits > MAX_RESULT_LENGTH
                    || value.scale() > MAX_RESULT_LENGTH
                    || value.scale() < -MAX_RESULT_LENGTH) {
                throw new IllegalArgumentException("Result is too large or too small.");
            }
        }
        return value;
    }

    private static final class Parser {
        private final String input;
        private int position;
        private int nesting;

        Parser(String input) {
            this.input = input;
        }

        BigDecimal expression() {
            BigDecimal value = term();
            while (true) {
                if (take('+')) {
                    value = checkRange(value.add(term(), PRECISION));
                } else if (take('-')) {
                    value = checkRange(value.subtract(term(), PRECISION));
                } else {
                    return value;
                }
            }
        }

        private BigDecimal term() {
            BigDecimal value = unary();
            while (true) {
                if (take('*')) {
                    value = checkRange(value.multiply(unary(), PRECISION));
                } else if (take('/')) {
                    BigDecimal divisor = unary();
                    if (divisor.signum() == 0) {
                        throw invalid("Cannot divide by zero");
                    }
                    value = checkRange(value.divide(divisor, PRECISION));
                } else {
                    return value;
                }
            }
        }

        private BigDecimal unary() {
            boolean negative = false;
            while (true) {
                if (take('-')) {
                    negative = !negative;
                } else if (!take('+')) {
                    break;
                }
            }
            BigDecimal value = primary();
            while (take('%')) {
                value = checkRange(value.movePointLeft(2));
            }
            return negative ? value.negate() : value;
        }

        private BigDecimal primary() {
            if (take('(')) {
                if (++nesting > MAX_NESTING) {
                    throw invalid("Too many nested parentheses");
                }
                BigDecimal value = expression();
                if (!take(')')) {
                    throw invalid("Missing closing parenthesis");
                }
                nesting--;
                return value;
            }
            skipSpace();
            int start = position;
            boolean decimalPoint = false;
            int digits = 0;
            while (!atEnd()) {
                char c = input.charAt(position);
                if (c >= '0' && c <= '9') {
                    digits++;
                    position++;
                } else if (c == '.' && !decimalPoint) {
                    decimalPoint = true;
                    position++;
                } else {
                    break;
                }
            }
            if (digits == 0) {
                throw invalid("Expected a decimal number");
            }
            return checkRange(new BigDecimal(input.substring(start, position)));
        }

        private boolean take(char expected) {
            skipSpace();
            if (!atEnd() && input.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        void skipSpace() {
            while (!atEnd() && Character.isWhitespace(input.charAt(position))) {
                position++;
            }
        }

        boolean atEnd() {
            return position == input.length();
        }

        IllegalArgumentException invalid(String message) {
            return new IllegalArgumentException(message + " at position " + (position + 1) + ".");
        }
    }
}
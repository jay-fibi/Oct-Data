#!/usr/bin/env python3
"""Secure random password generator.

Uses the :mod:`secrets` module (cryptographically strong) rather than
:mod:`random`, so the output is suitable for real passwords.

Features
--------
* Configurable length and character pools (lowercase, uppercase, digits, symbols).
* Guarantees at least one character from every selected pool.
* Avoids visually ambiguous characters (e.g. ``O``/``0``, ``l``/``1``).
* Estimates password entropy and strength.

Run ``python password_generator.py --help`` for CLI usage, or import the
functions for programmatic use.
"""

from __future__ import annotations

import argparse
import math
import secrets
import string
import sys

LOWER = string.ascii_lowercase
UPPER = string.ascii_uppercase
DIGITS = string.digits
SYMBOLS = "!@#$%^&*()-_=+[]{};:,.<>/?"

# Characters that are easy to confuse when read by a human.
AMBIGUOUS = "O0oIl1|`'\";:.,"


def build_pools(
    use_lower: bool = True,
    use_upper: bool = True,
    use_digits: bool = True,
    use_symbols: bool = True,
    avoid_ambiguous: bool = False,
) -> dict[str, str]:
    """Return a mapping of pool-name -> characters for the selected pools.

    Raises :class:`ValueError` if every pool is disabled.
    """
    pools: dict[str, str] = {}
    if use_lower:
        pools["lower"] = LOWER
    if use_upper:
        pools["upper"] = UPPER
    if use_digits:
        pools["digits"] = DIGITS
    if use_symbols:
        pools["symbols"] = SYMBOLS
    if not pools:
        raise ValueError("At least one character pool must be enabled.")

    if avoid_ambiguous:
        pools = {
            name: "".join(c for c in chars if c not in AMBIGUOUS)
            for name, chars in pools.items()
        }
        if not any(pools.values()):
            raise ValueError("All characters were filtered out as ambiguous.")
    return pools



def generate_password(
    length: int = 16,
    use_lower: bool = True,
    use_upper: bool = True,
    use_digits: bool = True,
    use_symbols: bool = True,
    avoid_ambiguous: bool = False,
) -> str:
    """Generate a single secure random password.

    The password is guaranteed to contain at least one character from each
    enabled pool (provided ``length`` is large enough), then shuffled so the
    guaranteed characters are not in predictable positions.
    """
    if length < 1:
        raise ValueError("Password length must be at least 1.")

    pools = build_pools(
        use_lower, use_upper, use_digits, use_symbols, avoid_ambiguous
    )
    all_chars = "".join(pools.values())

    # Start with one character from each enabled pool to satisfy requirements.
    password_chars = [secrets.choice(chars) for chars in pools.values()]

    # If length is smaller than the number of pools, trim the guarantees.
    if len(password_chars) > length:
        password_chars = password_chars[:length]
    else:
        remaining = length - len(password_chars)
        password_chars.extend(secrets.choice(all_chars) for _ in range(remaining))

    # Fisher-Yates shuffle using a secure RNG.
    for i in range(len(password_chars) - 1, 0, -1):
        j = secrets.randbelow(i + 1)
        password_chars[i], password_chars[j] = password_chars[j], password_chars[i]

    return "".join(password_chars)


def estimate_entropy(length: int, pool_size: int) -> float:
    """Return estimated entropy in bits for a password of ``length`` chars.

    Entropy is ``length * log2(pool_size)``.
    """
    if length <= 0 or pool_size <= 1:
        return 0.0
    return length * math.log2(pool_size)


def strength_label(entropy_bits: float) -> str:
    """Map an entropy value (in bits) to a coarse human label."""
    if entropy_bits < 40:
        return "Very Weak"
    if entropy_bits < 60:
        return "Weak"
    if entropy_bits < 80:
        return "Reasonable"
    if entropy_bits < 100:
        return "Strong"
    return "Very Strong"


def password_info(password: str, pool_size: int) -> dict[str, object]:
    """Return a summary dict describing a generated password."""
    entropy = estimate_entropy(len(password), pool_size)
    return {
        "password": password,
        "length": len(password),
        "entropy_bits": round(entropy, 1),
        "strength": strength_label(entropy),
    }


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Generate cryptographically secure random passwords."
    )
    parser.add_argument(
        "-l", "--length", type=int, default=16, help="password length (default: 16)"
    )
    parser.add_argument(
        "-c",
        "--count",
        type=int,
        default=1,
        help="how many passwords to generate (default: 1)",
    )
    parser.add_argument("--no-lower", action="store_true", help="exclude lowercase letters")
    parser.add_argument("--no-upper", action="store_true", help="exclude uppercase letters")
    parser.add_argument("--no-digits", action="store_true", help="exclude digits")
    parser.add_argument("--no-symbols", action="store_true", help="exclude symbols")
    parser.add_argument(
        "--avoid-ambiguous",
        action="store_true",
        help="avoid easily confused characters (O/0, l/1, etc.)",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)

    try:
        pools = build_pools(
            not args.no_lower,
            not args.no_upper,
            not args.no_digits,
            not args.no_symbols,
            args.avoid_ambiguous,
        )
    except ValueError as exc:
        parser.error(str(exc))
        return 2

    pool_size = len("".join(pools.values()))
    for _ in range(args.count):
        pwd = generate_password(
            length=args.length,
            use_lower=not args.no_lower,
            use_upper=not args.no_upper,
            use_digits=not args.no_digits,
            use_symbols=not args.no_symbols,
            avoid_ambiguous=args.avoid_ambiguous,
        )
        info = password_info(pwd, pool_size)
        print(
            f"{info['password']}  "
            f"[{info['strength']}, ~{info['entropy_bits']} bits]"
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())


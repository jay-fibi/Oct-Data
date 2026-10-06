#!/usr/bin/env python3
"""Black-box smoke test using only Python's standard library and Android SDK adb.

This resets the installed application's data, and requires explicit opt-in.
Run on a disposable emulator, never a device with history you want to keep.
"""

import argparse
import re
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = "com.jayfibi.calculator"


class Device:
    def __init__(self, adb, serial):
        self.command = [adb] + (["-s", serial] if serial else [])
        self.checks = 0

    def run(self, *args):
        result = subprocess.run(self.command + list(args), check=True, text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=45)
        return result.stdout

    def nodes(self):
        self.run("shell", "rm", "-f", "/data/local/tmp/calculator-smoke.xml")
        output = self.run("shell", "uiautomator", "dump", "/data/local/tmp/calculator-smoke.xml")
        if "dumped to" not in output:
            raise AssertionError(f"UIAutomator did not produce a fresh dump: {output}")
        document = self.run("shell", "cat", "/data/local/tmp/calculator-smoke.xml")
        return list(ET.fromstring(document).iter("node"))

    def wait(self, text=None, description=None, contains=False, timeout=20):
        deadline = time.monotonic() + timeout
        last = []
        while time.monotonic() < deadline:
            last = self.nodes()
            for node in last:
                value = node.get("text", "")
                # Native AlertDialog buttons are rendered uppercase on some OS themes.
                if ((text is not None and (text.casefold() in value.casefold() if contains
                                          else text.casefold() == value.casefold()))
                        or (description is not None and node.get("content-desc") == description)):
                    return node
            time.sleep(0.3)
        visible = [node.get("text") for node in last if node.get("text")]
        raise AssertionError(f"Missing {text or description!r}. Visible: {visible}")

    def tap_node(self, node):
        left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds")))
        self.run("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))

    def tap(self, text=None, description=None):
        self.tap_node(self.wait(text=text, description=description))

    def input(self, value):
        # Test data uses only numbers and calculator punctuation, never shell metacharacters.
        if not re.fullmatch(r"[0-9.+/-]+", value):
            raise ValueError("Use only simple numeric test expressions.")
        self.run("shell", "input", "text", value)

    def replace_editor(self, description, value):
        self.tap(description=description)
        self.run("shell", "input", "keycombination", "113", "29")  # Ctrl+A
        self.input(value)

    def check(self, description, text=None, contains=False):
        self.wait(text=text, contains=contains)
        self.checks += 1
        print(f"PASS {description}", flush=True)

    def check_locked(self, description):
        self.wait(text="Your history is locked")
        nodes = self.nodes()
        secrets = {"12/4", "42/2", "= 3", "= 21"}
        if any(node.get("text") in secrets or node.get("class") == "android.widget.EditText"
               for node in nodes):
            raise AssertionError("Locked history exposes an entry or editor")
        self.checks += 1
        print(f"PASS {description}", flush=True)

    def launch(self):
        self.run("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")
        self.wait(text="Pocket Calculator")

    def pin(self, value, setting=False):
        self.tap(description="Numeric PIN")
        self.input(value)
        if setting:
            self.tap(description="Confirm PIN")
            self.input(value)
        self.tap("Save PIN" if setting else "Unlock")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial")
    parser.add_argument("--allow-clear-data", action="store_true",
                        help="Explicitly allow deletion of ALL calculator data on the target device")
    args = parser.parse_args()
    if not args.allow_clear_data:
        parser.error("Use a disposable emulator and pass --allow-clear-data; this test deletes app data.")
    device = Device(args.adb, args.serial)
    if "Success" not in device.run("shell", "pm", "clear", PACKAGE):
        raise AssertionError("Could not clear the synthetic test fixture")
    device.launch()
    device.wait(text="History saved on this device")
    for key in ["2", "+", "3", "×", "4", "="]:
        device.tap(key)
    device.check("arithmetic and persistence notification", "Calculation saved.")
    device.check("operator precedence", "14")
    device.tap("History")
    device.check("history entry", "= 14")
    device.tap("Edit")
    device.replace_editor("Expression", "12/4")
    device.tap("Save")
    device.check("text edit persisted", "= 3")
    device.run("shell", "am", "force-stop", PACKAGE)
    device.launch()
    device.wait(text="History saved on this device")
    device.tap("History")
    device.check("history survives process restart", "12/4")
    device.tap("Calculator")
    device.replace_editor("Type an expression, e.g. (12 + 8) / 4", "9+1")
    device.run("shell", "input", "keyevent", "KEYCODE_BACK")
    device.tap("Settings")
    device.tap("Dark")
    device.check("theme selected", "Dark · selected")
    device.wait(text="History saved on this device")
    device.tap("Calculator")
    device.check("unprotected draft survives theme recreation", "9+1")
    device.tap("Settings")
    device.tap("Set history PIN")
    device.pin("123456", setting=True)
    device.wait(text="History unlocked", contains=True)
    device.tap("History")
    device.check("encrypted history available while unlocked", "= 3")
    device.tap("Lock now")
    device.check_locked("manual history lock hides entries")
    device.tap("Unlock history")
    device.pin("999999")
    device.check("wrong PIN rejected", "incorrect PIN", contains=True)
    device.tap("Unlock history")
    device.pin("123456")
    device.check("correct PIN restores entries", "= 3")
    device.tap("Edit")
    device.replace_editor("Expression", "42/2")
    device.tap("Save")
    device.check("encrypted text edit", "= 21")
    device.run("shell", "input", "keyevent", "KEYCODE_HOME")
    device.launch()
    device.check_locked("backgrounding locks and hides history")
    device.tap("Calculator")
    for key in ["7", "+", "8", "="]:
        device.tap(key)
    device.check("locked calculation is explicitly not saved", "Result not saved.", contains=True)
    device.tap("History")
    device.tap("Unlock history")
    device.pin("123456")
    device.check("locked calculation did not add an entry", "1 saved calculations", contains=True)
    device.check("encrypted edit survives lock", "= 21")
    device.tap("Settings")
    device.tap("Ocean")
    device.wait(text="Ocean · selected")
    device.wait(text="History locked", contains=True)
    device.tap("History")
    device.check_locked("theme recreation locks and hides history")
    device.tap("Unlock history")
    device.pin("123456")
    device.check("encrypted data survives theme recreation", "= 21")
    with tempfile.TemporaryDirectory(prefix="calculator-smoke-") as temporary:
        target = Path(temporary) / "history.bin"
        result = subprocess.run(device.command + ["exec-out", "run-as", PACKAGE,
                                "cat", "files/history.bin"], check=True,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15)
        target.write_bytes(result.stdout)
        if not result.stdout.startswith(b"JFCHIST\n") or len(result.stdout) <= 12:
            raise AssertionError("History file has an invalid header")
        if result.stdout[12] != 1:
            raise AssertionError("History file is not marked encrypted")
        if b"42/2" in result.stdout:
            raise AssertionError("History contains plaintext expression")
        device.checks += 1
        print("PASS on-device history file is encrypted", flush=True)
    device.run("shell", "rm", "-f", "/data/local/tmp/calculator-smoke.xml")
    print(f"PASS: {device.checks} Android smoke checks.", flush=True)


if __name__ == "__main__":
    main()
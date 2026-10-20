#!/usr/bin/env python3
"""Inspect/select/restore a TV dream without changing sleep or dock triggers."""
import argparse
import json
from pathlib import Path
import shlex
import subprocess

KEYS = (
    "screensaver_components", "screensaver_enabled",
    "screensaver_activate_on_sleep", "screensaver_activate_on_dock",
)
CHANGED = KEYS[:2]
COMPONENT = "org.ambientflow.tv/.AmbientDreamService"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb", help="adb executable path")
    parser.add_argument("--serial", required=True, help="explicit adb device serial")
    parser.add_argument("--user", type=int, help="Android user ID (default: current user)")
    parser.add_argument("--state", type=Path, help="backup JSON; inspect creates, select/restore read")
    parser.add_argument("action", choices=("inspect", "select", "restore", "test"))
    args = parser.parse_args()
    adb = [args.adb, "-s", args.serial]

    def run(*parts):
        return subprocess.run(adb + list(parts), check=True, capture_output=True,
                              text=True, timeout=30).stdout.rstrip("\r\n")

    def shell(*parts):
        # adb invokes a remote shell; quote values, including prior component lists.
        return run("shell", shlex.join(str(p) for p in parts))

    device = run("get-serialno")
    user = args.user if args.user is not None else int(shell("am", "get-current-user"))
    if user < 0:
        parser.error("--user must be a nonnegative ID")

    def settings(*parts):
        return shell("settings", "--user", user, *parts)

    def read():
        # 'get' cannot distinguish a missing entry from a literal value 'null'.
        entries = dict(line.split("=", 1) for line in settings("list", "secure").splitlines()
                       if "=" in line)
        return {key: entries.get(key) for key in KEYS}

    def write(values):
        for key in CHANGED:
            value = values[key]
            if value is None:
                settings("delete", "secure", key)
            else:
                settings("put", "secure", key, value)
        actual = read()
        if any(actual[key] != values[key] for key in CHANGED):
            raise RuntimeError("Settings read-back mismatch; retain backup and restore manually")

    current = read()
    snapshot = {"format": 1, "serial": device, "user": user, "values": current}
    if args.action == "inspect":
        print(json.dumps(snapshot, indent=2))
        if args.state:
            # Never silently replace the only record of the original settings.
            with args.state.open("x", encoding="utf-8") as output:
                json.dump(snapshot, output, indent=2)
                output.write("\n")
            print(f"Saved original values to {args.state}")
        return
    if args.action == "test":
        try:
            help_text = shell("cmd", "dreams", "help")
            print("Device cmd dreams help:\n" + help_text)
        except subprocess.CalledProcessError as error:
            print("cmd dreams help is unavailable: " + (error.stderr or error.stdout or str(error)).strip())
        print("Opening normal Screensaver settings; use its preview/Start now control if offered.")
        shell("am", "start", "--user", user, "-a", "android.settings.DREAM_SETTINGS")
        return
    if not args.state:
        parser.error("select/restore require --state; first run inspect --state FILE")
    saved = json.loads(args.state.read_text(encoding="utf-8"))
    if saved.get("format") != 1 or saved.get("serial") != device or saved.get("user") != user:
        parser.error("Backup format/device/user mismatch; refusing to change settings")
    original = saved.get("values")
    if not isinstance(original, dict) or set(original) != set(KEYS) or any(
            value is not None and not isinstance(value, str) for value in original.values()):
        parser.error("Invalid backup values")
    if args.action == "select":
        if current != original:
            parser.error("Settings changed since inspection; inspect again into a NEW backup file")
        intended = dict(current, screensaver_components=COMPONENT, screensaver_enabled="1")
        try:
            write(intended)
        except (subprocess.SubprocessError, RuntimeError):
            print("Selection failed; attempting rollback from the preserved backup")
            write(original)
            raise
    else:
        # Only restore the two fields this helper changes. Never overwrite later
        # user changes to dock/sleep triggers, which are preserved for inspection.
        intended = dict(current, screensaver_components=COMPONENT, screensaver_enabled="1")
        if any(current[k] not in (original[k], intended[k]) for k in CHANGED):
            parser.error("Selection fields were changed by another actor; restore manually from backup")
        write(original)
    print(json.dumps({"serial": device, "user": user, "values": read()}, indent=2))
    print("Sleep/dock triggers were not changed. Keep the original backup.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        raise SystemExit(f"Screensaver operation failed: {error}") from error

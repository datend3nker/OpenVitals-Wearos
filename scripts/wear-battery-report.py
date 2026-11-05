#!/usr/bin/env python3
"""Battery use of the watch, worked out from the debug files.

    scripts/wear-battery-report.py <folder> [--hours]

<folder> is a pull of scripts/wear-debug-pull.sh: trace-*.log, and diagnostics.csv if present.
Needs only the standard library.

Readings come from the `Power;battery` lines of the trace (written whenever the level or the
charging state changes while the trace runs), and from diagnostics.csv as a fallback for days
the trace has none. The state at each moment comes from the trace: screen on or off, the heart
rate plan (`HeartRate;plan`), the sleep hold, the wrist, the charger. The drop between two
readings is split over the states in proportion to the time spent in them, so a state's value
is an estimate that gets better with more readings. The files hold values; keep them to yourself.
"""
import argparse
import collections
import datetime
import glob
import os
import sys

# A gap with no trace line longer than this is shown as "no data" in the hourly table only.
HOUR = 3600.0


def fields(parts):
    out = {}
    for part in parts:
        key, _, value = part.partition("=")
        out[key] = value
    return out


def read_trace(folder):
    events = []
    for path in sorted(glob.glob(os.path.join(folder, "trace-*.log"))):
        with open(path, encoding="utf-8", errors="replace") as handle:
            for line in handle:
                parts = line.rstrip("\n").split(";")
                if len(parts) < 3 or not parts[0].isdigit():
                    continue
                events.append((int(parts[0]) / 1000.0, parts[1], parts[2], fields(parts[3:])))
    events.sort(key=lambda e: e[0])
    return events


def read_diagnostics(folder):
    path = os.path.join(folder, "diagnostics.csv")
    readings = []
    if not os.path.exists(path):
        return readings
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            parts = line.strip().split(";")
            if len(parts) == 6 and parts[0].isdigit() and parts[1].isdigit():
                readings.append((int(parts[0]) / 1000.0, int(parts[1]), parts[2] == "1"))
    return readings


def battery_readings(events, diagnostics):
    readings = [
        (t, int(f["level"]), f.get("charging") == "true")
        for t, source, name, f in events
        if source == "Power" and name == "battery" and f.get("level", "").isdigit()
    ]
    if readings:
        # The trace is exact; use diagnostics only for time before the trace starts.
        first = readings[0][0]
        readings = [r for r in diagnostics if r[0] < first] + readings
    else:
        readings = list(diagnostics)
    return sorted(readings)


class Timeline:
    """The state of the watch over time, replayed from the trace."""

    def __init__(self, events):
        self.changes = []  # (time, key, value)
        screen = False
        for t, source, name, f in events:
            if source == "Screen":
                self.changes.append((t, "screen", name == "on"))
            elif source == "HeartRate" and name == "plan":
                reason = f.get("reason", "")
                seconds = int(f.get("intervalSec", "0") or 0)
                self.changes.append((t, "hr", "off (%s)" % reason if seconds == 0 else "%ds (%s)" % (seconds, reason)))
            elif source == "Hold" and name == "sleep_hold":
                self.changes.append((t, "hold", f.get("holding") == "true"))
            elif source == "Power" and name in ("charger_on", "charger_off"):
                self.changes.append((t, "charger", name == "charger_on"))
        self.links = [t for t, source, name, _ in events if source == "Link" and name == "connection_open"]

    def segments(self, start, end):
        """(from, to, state) pieces in [start, end], state = (screen, hr label)."""
        state = {"screen": False, "hr": "unknown", "hold": False, "charger": False}
        for t, key, value in self.changes:
            if t <= start:
                state[key] = value
        cuts = [t for t, _, _ in self.changes if start < t < end]
        points = [start] + cuts + [end]
        index = 0
        # Re-walk to apply changes inside the interval in order.
        inside = [(t, k, v) for t, k, v in self.changes if start < t < end]
        pieces = []
        for left, right in zip(points, points[1:]):
            while index < len(inside) and inside[index][0] <= left:
                _, key, value = inside[index]
                state[key] = value
                index += 1
            pieces.append((left, right, (state["screen"], state["hr"])))
        return pieces


def report(folder, hourly):
    events = read_trace(folder)
    readings = battery_readings(events, read_diagnostics(folder))
    if len(readings) < 2:
        print("Not enough battery readings in %s (need the trace with the debug switch on)." % folder)
        return 1
    timeline = Timeline(events)
    hours = collections.defaultdict(float)
    drops = collections.defaultdict(float)
    charging_hours = 0.0
    per_hour = collections.OrderedDict()
    for (t0, l0, c0), (t1, l1, c1) in zip(readings, readings[1:]):
        dt = t1 - t0
        if dt <= 0:
            continue
        if c0 or c1:
            charging_hours += dt / HOUR
            continue
        drop = max(l0 - l1, 0)
        for left, right, state in timeline.segments(t0, t1):
            share = (right - left) / dt
            hours[state] += (right - left) / HOUR
            drops[state] += drop * share
        stamp = datetime.datetime.fromtimestamp(t0).strftime("%d.%m. %H:00")
        row = per_hour.setdefault(stamp, [0.0, 0.0])
        row[0] += dt / HOUR
        row[1] += drop
    total_hours = sum(hours.values())
    total_drop = sum(drops.values())
    first = datetime.datetime.fromtimestamp(readings[0][0]).strftime("%d.%m. %H:%M")
    last = datetime.datetime.fromtimestamp(readings[-1][0]).strftime("%d.%m. %H:%M")
    print("Battery use %s to %s: %.1f h on the wrist or table, %.1f h charging, %.0f %% used" % (
        first, last, total_hours, charging_hours, total_drop))
    if total_hours:
        print("Overall %.2f %%/h\n" % (total_drop / total_hours))
    print("%-34s %8s %8s %8s" % ("state (screen, heart rate plan)", "hours", "% used", "%/h"))
    for state in sorted(hours, key=lambda s: -drops[s]):
        screen, plan = state
        label = "%s, %s" % ("screen on" if screen else "screen off", plan)
        rate = drops[state] / hours[state] if hours[state] else 0
        print("%-34s %8.1f %8.1f %8.2f" % (label, hours[state], drops[state], rate))
    links = len(timeline.links)
    span = (readings[-1][0] - readings[0][0]) / HOUR
    if span:
        print("\nLink connections: %d (%.1f per hour)" % (links, links / span))
    if hourly:
        print("\n%-12s %6s %6s %6s" % ("hour", "hours", "% used", "%/h"))
        for stamp, (h, d) in per_hour.items():
            print("%-12s %6.2f %6.1f %6.2f" % (stamp, h, d, d / h if h else 0))
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("folder")
    parser.add_argument("--hours", action="store_true", help="also print one line per hour")
    args = parser.parse_args()
    sys.exit(report(args.folder, args.hours))


if __name__ == "__main__":
    main()

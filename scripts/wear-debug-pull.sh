#!/usr/bin/env bash
# Pull the debug files of the watch app (and optionally the phone's sync log)
# into a folder named with the time, and optionally run the offline check.
#
#   scripts/wear-debug-pull.sh [--serial <ip:port>] [options]
#
#   --out <dir>      parent folder for the pull (default: wear-debug-pull in the current folder)
#   --package <id>   the installed package id (default: tech.mmarca.openvitals)
#   --debug-build    shortcut for --package tech.mmarca.openvitals.debug
#   --phone <serial> also pull wear-sync-*.log from the phone with this adb serial
#   --replay         run the offline check (:healthcalc WearDebugReplayCheck) on the pull
#   --dry-run        print what would run; no adb, no device needed
#
# The watch writes the files only while "Debug data" is on (phone: Watches, or
# the watch: Settings, Tracking). They hold heart rate, movement and times.
# Keep them to yourself. Guide: docs/features/watches.md, section Debug data.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PACKAGE="tech.mmarca.openvitals"
PHONE_PACKAGE="tech.mmarca.openvitals"
OUT_PARENT="wear-debug-pull"
PHONE_SERIAL=""
REPLAY=no
DRY=no
SERIAL="${ANDROID_SERIAL:-}"

fail() { printf 'error: %s\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
    case "$1" in
        -s|--serial) SERIAL="$2"; shift 2 ;;
        --out) OUT_PARENT="$2"; shift 2 ;;
        --package) PACKAGE="$2"; shift 2 ;;
        --debug-build) PACKAGE="tech.mmarca.openvitals.debug"; shift ;;
        --phone) PHONE_SERIAL="$2"; shift 2 ;;
        --replay) REPLAY=yes; shift ;;
        --dry-run) DRY=yes; shift ;;
        -h|--help) sed -n '2,16p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) fail "unknown argument $1 (try --help)" ;;
    esac
done

STAMP="$(date +%Y%m%d-%H%M%S)"
DEST="$OUT_PARENT/$STAMP"
WATCH_DIR="/sdcard/Android/data/$PACKAGE/files"

if [ "$DRY" = yes ]; then
    say() { printf '%s\n' "$*"; }
    note() { printf '  %s\n' "$*"; }
    adbw() { printf '  would run: adb%s %s\n' "${SERIAL:+ -s $SERIAL}" "$*"; }
else
    # shellcheck source=lib/wear-adb.sh
    . "$REPO_ROOT/scripts/lib/wear-adb.sh"
    require_watch
fi

say "Pulling the watch's debug files into $DEST"
if [ "$DRY" = yes ]; then note "would run: mkdir -p $DEST"; else mkdir -p "$DEST"; fi
adbw pull "$WATCH_DIR/debug/." "$DEST/" || note "no debug folder on the watch: is Debug data switched on?"
adbw pull "$WATCH_DIR/diagnostics.csv" "$DEST/diagnostics.csv" || note "no diagnostics.csv"

if [ -n "$PHONE_SERIAL" ]; then
    say "Pulling the phone's sync log"
    if [ "$DRY" = yes ]; then
        note "would run: adb -s $PHONE_SERIAL pull /sdcard/Android/data/$PHONE_PACKAGE/files/debug/. $DEST/phone/"
    else
        mkdir -p "$DEST/phone"
        "$ADB" -s "$PHONE_SERIAL" pull "/sdcard/Android/data/$PHONE_PACKAGE/files/debug/." "$DEST/phone/" \
            || note "no debug folder on the phone: is Debug data switched on?"
    fi
fi

if [ "$DRY" = no ]; then
    say "Pulled"
    ls -l "$DEST" | sed 's/^/  /'
fi

if [ "$REPLAY" = yes ]; then
    say "Offline check on $DEST"
    if [ "$DRY" = yes ]; then
        note "would run: env OPENVITALS_WEAR_DEBUG_DIR=$DEST ./gradlew --offline :healthcalc:test --tests '*WearDebugReplayCheck*' --rerun -i"
    else
        [ -f "$DEST/minutes.csv" ] || fail "$DEST/minutes.csv is missing; nothing to replay."
        ABS_DEST="$(cd "$DEST" && pwd)"
        (cd "$REPO_ROOT" && env OPENVITALS_WEAR_DEBUG_DIR="$ABS_DEST" \
            ./gradlew :healthcalc:test --tests '*WearDebugReplayCheck*' --rerun -i)
    fi
fi

# Watch Tiles With Real Values

> **Status (2026-11-04):** Branch 1 is partly built: `:healthcalc` exists, and the watch computes sleep with stages, resting heart rate and night HRV from its own minutes. Distance and active calories are not computed yet, and branch 2 (the phone sends what the watch cannot know) is not started. Current behaviour: [Watches](../features/watches.md).

## Problem

The watch computes steps, heart rate, floors, elevation, sleep, resting heart rate and night HRV. Distance, active calories, weight and hydration stay empty: distance and calories are not computed yet, and weight and hydration are logged on the phone. No path carries a value from the phone to the watch.

## Plan

Two small branches, each its own PR.

### Branch 1: the watch computes what it measures

- A shared pure-Kotlin module, built like `:wearlink` and compiled into `:app` and `:wear`, so both compute with the same code and get the same result. Working name: `:healthcalc`.
- Move `domain/insights/WearSleepEstimator.kt` and `SleepStageEstimator.kt` there (about 600 lines). `:domain` stays as it is but uses the module. `:wear` cannot use `:domain`, because `:domain` brings the Health Connect client and zxing.
- Put resting heart rate, distance and active calories from steps there too.
- The watch runs it on its own data: sleep and stages once in the morning, resting heart rate once a day, distance and calories from the step minutes. The cost is milliseconds. The battery cost comes from the sensors and the CPU wake-ups, which run already.
- Distance and calories need height, weight, age and sex. The phone sends these with `SET_PREF`.
- Weight and hydration: the watch sums its own logged entries.

### Branch 2: the phone sends only what the watch cannot know

- A new `:wearlink` request, for example `PUT_DAY <metric> <date> <value>`, plus stage lines for sleep. It covers the last 7 days. The phone sends it at the end of every sync.
- Values come from Health Connect: sleep from Samsung Health or Sleep as Android, weight from a scale, entries made on the phone.
- The watch stores them. It never takes a phone value for a metric it measures itself, except sleep when the user prefers another source.
- Sleep source setting: `domain/model/SleepSessionMerging.kt` keeps the "richer" session of two that overlap at least 85 %. That ignores the Health Connect priority, so Samsung Health always wins. Health Connect does not let an app read the priority list. Add a "preferred sleep source" setting in OpenVitals and use it in the deduplication. Its choice also decides which sleep the watch shows.

## On the waiting list

- **HRV:** the math is cheap. The measurement needs 25 Hz raw PPG with the CPU awake. See `origin/research/wear-ppg-hrv` (`docs/research/wear-ppg-hrv.md`). It makes sense only at night, while the watch is still.
- **SpO2:** the Galaxy Watch (SM-L310) has no SpO2 sensor that an app may read. `hr_raw` shows only two green channels. Open test: log `hr_raw` while Samsung Health takes a manual SpO2 reading, and check whether more channels light up.

## Night of 2026-11-02 (test notes)

- Watch: in bed about 23:45, asleep about 00:15, up about 08:10.
- Heart rate 02:34–06:24 was about 100 bpm while lying still, then back to 47. Samsung Health shows the same, so this is likely real and not a sensor error. No filter is planned.
- The movement in Sleep as Android (phone accelerometer, from 01:18) matches the watch's movement minutes.
- Caveat: Sleep as Android ran with its watch companion, which very likely kept the processor awake, so this night's movement is denser than a normal night without bedtime mode. Do not tune thresholds to it.

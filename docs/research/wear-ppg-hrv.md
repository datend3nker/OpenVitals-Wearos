# Research spike: raw PPG and HRV on the watch without the Samsung Health SDK

**Status: research only, not for merge.** This note records what the
`research/wear-ppg-hrv` branch found so the team can decide how, or whether,
to integrate a heart-rate-variability feature that does not depend on the
Samsung Health Sensor SDK (which is Partner-Program / Play-only and cannot
ship in the public repo).

## Goal

Get the most out of the Galaxy Watch7 sensors using only AOSP APIs and the
permissions the app already holds, in particular to see whether
**heart-rate variability (RMSSD)** can be derived on-device from the raw PPG
stream instead of reading `HeartRateVariabilityRmssdRecord` written by
Samsung Health through Health Connect.

## Method

- Watch: Galaxy Watch7 `SM-L310`, debuggable build.
- Sensor: `com.samsung.sensor.hr_raw`, readable with the `READ_HEART_RATE`
  permission alone — no SDK, no signature permission. 16 float values per
  event; the floats are int-bit-packed (reinterpret the raw 32-bit pattern
  as `int32`). `v1` and `v5` are two PPG channels (DC ~2.1M). `v0` is a
  status flag (1/2).
- Logger: `PpgRawLogger` (debug-only, started/stopped by a long-press on the
  watch status screen) dumps every event to a CSV under the app's files.
- Captures taken at rest, watch on the wrist, off the charger.

## Experiment on this branch

`PpgRawLogger` was changed in two ways to test one hypothesis — that the
periodic ~0.7 s gaps seen in earlier captures were the dozing CPU dropping
the non-wakeup sensor stream:

1. Hold a `PARTIAL_WAKE_LOCK` while logging (as `SleepMinuteRecorder` does).
2. `MAX_REPORT_LATENCY_MICROS = 0` — no batching, each sample delivered at
   once for clean per-sample timestamps.

## Finding 1 — the wake lock fixes the sampling (confirmed)

| metric | before (no wake lock) | after (wake lock) |
|---|---|---|
| effective sample rate | ~18–21 Hz | **25.00 Hz** |
| gaps > 80 ms | ~37–66 per capture | **0** |
| largest inter-sample gap | 200–880 ms | **50 ms** |

Hypothesis confirmed: the dozing CPU was dropping the stream. With the
processor held awake the raw PPG arrives gap-free at the full 25 Hz, even
with the screen off. The wake lock is acquired on start and released on
stop; `WAKE_LOCK` is already declared in the wear manifest.

## Finding 2 — HRV is still not reliably extractable at 25 Hz

With clean timing the remaining blockers are signal-level, not sampling:

- **Channel:** `v5` is the real PPG channel (in-band cardiac SNR ~7.4 vs
  ~2.5 for `v1`); its pulsatile swing is ~10× larger.
- **Heart rate (windowed, ~10 s, autocorrelation):** obtainable. ~80 % of
  windows show cardiac periodicity; HR ~52 ±7 bpm at rest. Usable for a
  single HR number, but the app already gets reliable HR from the standard
  heart-rate sensor, so this is not the prize.
- **Beat-to-beat IBI / RMSSD:** **not reliable.** Across peak-detection (two
  variants) and autocorrelation, only ~40 % of inter-beat intervals land in
  a physiologic range, and RMSSD comes out 240–360 ms — physiologically
  impossible (resting is ~20–80 ms). The autocorrelation periodicity
  strength is ~0.45 (1.0 = perfectly periodic), i.e. the beat shape/timing
  is too noisy for millisecond-accurate intervals.
- **Sample rate:** the sensor's `minDelay` is 40000 µs, so 25 Hz is the
  hardware maximum for `hr_raw`. A first naive peak detector quantized the
  IBI to 40 ms steps and looked like a wall for a 20–80 ms RMSSD.

Root cause of this first failure: a naive detector on a weak pulsatile AC
riding on baseline wander, at a signal that also has genuinely poor stretches.

## Update (2026-11-02) — matching the SDK is an algorithm problem, not a rate wall

Two findings reframe the pessimism above:

- **The Samsung Health Sensor SDK uses the same 25 Hz PPG.** Its docs list
  PPG green as a 25 Hz tracker; its heart-rate tracker derives an `IBI_LIST`
  with per-interval `IBI_STATUS_LIST` quality flags from that same 25 Hz
  stream. So SDK-grade HRV at 25 Hz is possible — the SDK's advantage is
  processing (and perhaps a cleaner, AGC'd signal), not a higher rate.
- **A proper pipeline closes most of the gap.** On the gap-free 262 s
  capture, `v5`, with: bandpass 0.5–4 Hz → **FFT up-sampling to 200 Hz**
  (defeats the 40 ms quantization) → **matched-filter / template beat
  detection** with a correlation quality gate → **Kubios-style IBI artifact
  correction** (drop intervals >25 % off the local median; RMSSD over
  successive normal-normal pairs only), RMSSD fell from ~336 ms (naive) to
  ~110 ms (whole capture) to **~85 ms on the cleanest 60 s window** (20 %
  artifacts there). Borderline, not yet SDK-grade, but the trend proves the
  method.

**Remaining gap to SDK quality:** even the best window still rejects ~20 % of
beats — but the next capture showed this gap was capture quality, not the
method.

## Breakthrough (2026-11-02) — a clean capture gives SDK-grade RMSSD

With the accelerometer now logged alongside the PPG, a **snug, fully still**
294 s capture was taken (accelerometer confirms it: magnitude std 0.065,
147/148 two-second windows below the motion threshold). The *unchanged*
pipeline (bandpass → FFT up-sample to 200 Hz → matched-filter detection →
IBI artifact correction) then produced:

| | whole capture (294 s) | cleanest 60 s window |
|---|---|---|
| artifacts | **0 %** (321/321 beats) | 0 % |
| HR | 65.9 bpm | 67.3 bpm |
| SDNN | 45.0 ms | 28.2 ms |
| **RMSSD** | **44.8 ms** | **38.9 ms** |

All physiologic (resting target 20–80 ms). `v1` and `v5` processed
independently agree to ~1 ms (RMSSD 44.8 vs 46.0), which cross-validates the
result against a per-channel pipeline bug.

**Conclusion: SDK-grade HRV from raw `hr_raw` at 25 Hz is demonstrated,
fully FOSS.** The earlier "25 Hz ceiling / not reliable" pessimism was wrong;
the blocker was purely capture quality (loose fit / micro-motion), not the
sample rate and not the algorithm. 25 Hz + FFT up-sampling recovers
millisecond beat timing; a clean signal needs no artifact rejection at all.

Open items before shipping: validate absolute accuracy against a chest-strap
/ ECG reference (internal consistency is already strong); confirm it holds
across wearers, wrist positions, and skin tones; define a motion/quality gate
(the accelerometer stream is now captured for exactly this) so the on-watch
version refuses rather than reports a noisy window; then port the pipeline
from the numpy prototype to Kotlin on the watch.

## Update (2026-11-05) — the first full night on the watch, and what else the sensors can give

**Night HRV on the watch (Kotlin port, shipped in `review/wear-all`).** One night, 03:12 to 10:30, Galaxy Watch7 (`SM-L310`), 6-minute windows from `hr_raw` with the accelerometer gate:

- 9 windows stored (219 to 257 beats each), 1 dropped for movement.
- RMSSD fell from 106 ms in the first window to 54 ms in the last one, at wake-up. That is the shape a night should have (high in deep sleep, lower toward morning).
- No reference device was worn. The absolute values are still not validated against a chest strap or ECG.

**What the watch hardware offers.** `dumpsys sensorservice` lists a skin temperature sensor, a thermistor, ECG and BIA, but all four need `com.samsung.permission.SSENSOR`, so an ordinary app cannot read them. `hr_raw` gives two green-like channels (`v1`, `v5`). There is no SpO2 sensor type, and no red or infrared channel is visible.

**Samsung's newer measures.**

- The *AGEs index* (Samsung Health) is, according to Samsung, measured in sleep with the BioActive sensor and supported on Watch7 and later. Samsung has not published the signals or the algorithm, so it cannot be rebuilt from `hr_raw`. Treat any number we compute as a different quantity and do not give it that name.
- *Vascular load* (sleep, pulse wave analysis) and the *antioxidant index* (thumb, skin carotenoids) are Watch8 and Ultra features. The antioxidant index needs the multi-wavelength LEDs. Neither is derivable from two green channels.

Sources: [Samsung: how the sensor breaks new ground](https://news.samsung.com/ca/how-galaxy-watchs-innovative-sensor-breaks-new-ground-in-tracking-your-health), [Samsung support: Watch8 health features](https://www.samsung.com/uk/support/mobile-devices/health-features-on-the-galaxy-watch8-and-watch8-classic/), [Android Authority: AGEs APK teardown](https://www.androidauthority.com/samsung-galaxy-watch-ages-index-apk-teardown-3456073/).

**Respiratory rate (not built).** It can be derived from what the night windows already have:

1. From the beat intervals: breathing modulates the interval length (respiratory sinus arrhythmia). Resample the interval series to 4 Hz, take the spectrum, and read the peak between 0.1 and 0.5 Hz (6 to 30 breaths per minute).
2. From the PPG itself: the baseline wander and the pulse amplitude also move with breathing. The same band applies, and it works as a second estimate when the beats are too few.
3. Take the value only when both agree within 2 breaths per minute, and only in still windows (the accelerometer gate exists).

Needs: no new sensor, no extra wake time (the 6-minute windows already run). Health Connect has `RespiratoryRateRecord`. Open: compare against Samsung Health's own sleep respiratory rate (it writes to Health Connect) over several nights before showing it.

## What this means for integration

- The wake-lock change is a genuine, self-contained improvement to the raw
  capture and could be lifted into the debug logger independently.
- A FOSS, on-device **RMSSD** from `hr_raw` is now **demonstrated** on clean
  rest (RMSSD 44.8 ms, 0 % artifacts). Recommended path:
  1. Port the numpy pipeline to Kotlin on the watch, with a motion/quality
     gate (accelerometer + `v0` status) so a noisy window is refused, not
     reported, and validate against a chest-strap/ECG reference across a few
     wearers. Ship it FOSS — no Samsung SDK, works in the public repo.
  2. Fallback only if on-watch cost or cross-wearer robustness disappoints:
     keep HRV on the Samsung IBI path (SDK / Health Connect) as a Play-only,
     SDK-gated watch feature, with the phone app and public repo still
     SDK-free.

## Reproduce

1. Build + install the wear debug build; long-press the watch status screen
   to start the raw PPG log, long-press again to stop.
2. Pull the CSV: `adb shell run-as tech.mmarca.openvitals.debug cat
   files/ppg_raw/<file>.csv > capture.csv`.
3. Analysis scripts live in the session scratchpad (not committed); they are
   numpy-only and decode the int-bit-packed floats, measure timing/gaps, and
   run beat detection + autocorrelation HRV.

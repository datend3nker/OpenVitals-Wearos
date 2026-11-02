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
beats. To close it: (a) capture the **accelerometer** alongside PPG for
motion/quality gating (the logger does not yet log it); (b) take **cleaner
captures** (snug fit, fully still); (c) **validate** against the Samsung
`HeartRateVariabilityRmssdRecord` in Health Connect for the same window as a
read-only ground truth; (d) improve detection (fuse `v1`+`v5`, better
template, reject low-SNR windows up front).

## What this means for integration

- The wake-lock change is a genuine, self-contained improvement to the raw
  capture and could be lifted into the debug logger independently.
- A FOSS, on-device **RMSSD** from `hr_raw` is **not yet demonstrated** but no
  longer looks blocked by the 25 Hz rate. Options, roughly in order of promise:
  1. Finish the raw-PPG pipeline above (accelerometer gating + cleaner
     captures + ground-truth validation) and ship it FOSS if it reaches SDK
     parity on clean rest.
  2. Accept that HRV needs the Samsung IBI path (SDK / Health Connect) and
     keep HRV as a Play-only / SDK-gated feature on the watch, while the
     phone app and public repo stay SDK-free.
  3. Report only coarser, quantization-tolerant metrics (resting HR, HR
     trends, maybe SDNN over long windows) rather than RMSSD.

## Reproduce

1. Build + install the wear debug build; long-press the watch status screen
   to start the raw PPG log, long-press again to stop.
2. Pull the CSV: `adb shell run-as tech.mmarca.openvitals.debug cat
   files/ppg_raw/<file>.csv > capture.csv`.
3. Analysis scripts live in the session scratchpad (not committed); they are
   numpy-only and decode the int-bit-packed floats, measure timing/gaps, and
   run beat detection + autocorrelation HRV.

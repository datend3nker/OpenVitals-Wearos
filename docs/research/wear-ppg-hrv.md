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
- **Hard ceiling:** the sensor's `minDelay` is 40000 µs, so **25 Hz is the
  hardware maximum** for `hr_raw`. At 25 Hz the inter-beat interval is
  quantized to 40 ms steps, which is coarse relative to a 20–80 ms RMSSD
  even with sub-sample peak interpolation.

Root causes of the HRV failure are now (a) a weak pulsatile AC riding on
baseline wander in the raw (un-AGC'd) signal and (b) the 25 Hz / 40 ms
quantization — not the CPU-doze gaps that Finding 1 removed.

## What this means for integration

- The wake-lock change is a genuine, self-contained improvement to the raw
  capture and could be lifted into the debug logger independently.
- A FOSS, on-device **RMSSD** feature from `hr_raw` is **not demonstrated**
  and may be out of reach at 25 Hz. Options, roughly in order of promise:
  1. Accept that HRV needs the Samsung IBI path (SDK / Health Connect) and
     keep HRV as a Play-only / SDK-gated feature on the watch, while the
     phone app and public repo stay SDK-free.
  2. Push the raw-PPG pipeline harder before giving up: template-matching /
     matched-filter beat detection, baseline-wander removal, and motion /
     quality gating (using `v0` status and/or the accelerometer) to drop bad
     windows. This improves HR confidence but the 25 Hz quantization ceiling
     on RMSSD remains.
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

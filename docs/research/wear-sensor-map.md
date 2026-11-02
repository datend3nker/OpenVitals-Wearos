# Research: which Galaxy Watch7 sensors an app can read without the Samsung SDK

**Status: research only, not for merge.** Companion to
[wear-ppg-hrv.md](wear-ppg-hrv.md). Answers the question "what can the watch
give us with only AOSP APIs and permissions an ordinary app can hold?"

Source: `adb shell dumpsys sensorservice` on a Galaxy Watch7 (`SM-L310`),
2026-11-02. The `perm:` column of each sensor decides who may read it.

## Readable by an ordinary app (no SDK, no signature permission)

| Sensor | Type string | Permission |
|---|---|---|
| Samsung HR Raw Sensor (PPG) | `com.samsung.sensor.hr_raw` | `android.permission.health.READ_HEART_RATE` |
| Samsung HR Batch / None Wakeup | `android.sensor.heart_rate` | `READ_HEART_RATE` |
| Step counter / detector | `android.sensor.step_counter`, `step_detector` | `ACTIVITY_RECOGNITION` |
| Altitude (barometer) | `com.samsung.sensor.altitude` | none |
| Rotation vector, game rotation, linear acceleration | AOSP types | none |
| Off-body detector, low-power off-body | `low_latency_offbody_detect`, `low_power_offbody_detector` | none |

## Gated by `com.samsung.permission.SSENSOR` (Samsung-signed apps only)

| Sensor | Type string | What it would give |
|---|---|---|
| AFE4510 ECG | `com.samsung.sensor.ecg` | ECG |
| AFE4510 BIA | `com.samsung.sensor.bia` | body composition (bioimpedance) |
| Samsung Skin Temp Sensor | `com.samsung.sensor.skin_temp` | skin temperature |
| Samsung Thermistor Sensor | `com.samsung.sensor.thermistor` | temperature |
| HR Raw Fac / Fac2 | `hr_raw_fac`, `hr_raw_fac2` | factory-mode raw PPG, possibly more channels |
| LSM6DSO Accelerometer 32G | `accelerometer_32g` | high-range accelerometer |

Regular accelerometer data is available through the AOSP accelerometer, which
the research logger already records.

## What follows for each wanted metric

| Metric | Route without the SDK |
|---|---|
| Heart rate, sleep, steps, ascent | done |
| HRV (RMSSD) | demonstrated from `hr_raw`, see wear-ppg-hrv.md |
| Respiratory rate, stress | derivable from PPG, accelerometer and HRV; not yet built |
| Sleep stages, VO2max, calories, fall detection | derivable from heart rate and motion; not yet built |
| SpO2 | open. There is no SpO2 sensor. SpO2 needs red and IR light, and `hr_raw` under `READ_HEART_RATE` only exposes two green-like channels (`v1`, `v5`). Whether another mode of `hr_raw` exposes red/IR is unchecked. |
| ECG, body composition, skin temperature | only through `SSENSOR`, i.e. the Samsung Health Sensor SDK |
| Cycle tracking | needs skin temperature, so it is blocked with it |
| Blood pressure | needs calibration and the SDK; not planned |

## Future plan: reach the SSENSOR-gated sensors without the SDK

The goal is to get ECG, BIA and skin temperature without depending on the
Samsung Health Sensor SDK, which is Partner-Program gated and cannot live in
the public repo. This is a later item.

Scope limit, so a future session does not misread this: defeating the
`SSENSOR` signature permission or otherwise bypassing Android platform
security is out of scope. Routes that stay in scope:

- Samsung's Partner Program, if access can be obtained, for a Play-Store-only
  watch build that stays out of the public repo.
- Reading what Samsung Health already writes to Health Connect (for example
  the Samsung-written `HeartRateVariabilityRmssdRecord`), accepting that this
  keeps a Samsung Health dependency on the phone.
- Checking whether open or documented Health Services / Wear OS APIs expose
  any of these metrics on this hardware.

## Open questions

1. Does `hr_raw` ever expose red/IR channels (SpO2), for example at another
   sampling rate or sensor configuration?
2. Port the HRV pipeline to Kotlin on the watch with a motion gate.
3. Validate the HRV result against a chest strap or ECG reference.

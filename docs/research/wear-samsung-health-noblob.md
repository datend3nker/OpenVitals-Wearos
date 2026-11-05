# Research spike: Samsung Health Sensor Service without Samsung's library

**Status: research only, not for merge.** Companion to
[wear-ppg-hrv.md](wear-ppg-hrv.md). Answers: can an F-Droid-clean build talk
to the Samsung sensor service (SpO2, skin temperature, ECG, BIA — all behind
`com.samsung.permission.SSENSOR` for an ordinary app) without shipping
Samsung's proprietary Health Sensor SDK `.aar`?

## Why this is a different question from `wear-ppg-hrv`

That branch reads a sensor directly through AOSP `SensorManager`, a public
Android API, under a permission the app already holds. This one talks to a
Samsung system service (`com.samsung.android.service.health`) that is
otherwise reached only through Samsung's own compiled library. The access
control stays identical either way: Health Sensor Service's developer mode
and the app's own permissions, nothing here goes around either. What
changes is whether the APK contains Samsung's binary (`.aar`) or only our
own code that speaks the same (undocumented) Binder wire protocol — the
`.aar` is why this cannot go into an F-Droid build as things stand.

## Method

Facts below come from running `javap -p -c` on the classes inside
`samsung-health-sensor-api-1.4.1.aar` (downloaded from
[the SDK's own page](https://developer.samsung.com/health/sensor/overview.html))
and from a throwaway test app with developer mode on, on a Galaxy Watch7
(SM-L310). No text, comment, or identifier from Samsung's shipped `.aidl` or
`.java` source is copied here or anywhere in this branch: only the
behaviour a disassembler and a live device show, same as how an
interoperability clean-room traditionally works. The `.aar` itself, and
anything extracted verbatim from it, stays out of this repo.

## Finding 1 — the wire protocol for the easy call (confirmed)

- Bind: `Intent("com.samsung.android.service.health.wear.sts.ACTION_SERVICE")`
  targeted at `com.samsung.android.service.health`, `BIND_AUTO_CREATE`.
- Interface token (`Parcel.writeInterfaceToken`) on every call:
  `com.samsung.android.service.health.tracking.IClientEventListener`.
- Transaction codes, read off the Proxy class's `transact()` calls:
  `createHealthTracker=1`, `setEventListener=2`, `unsetEventListener=3`,
  `flush=4`, `getSupportedHealthTrackerTypes=5`.
- `getSupportedHealthTrackerTypes`: write only the interface token, no
  other field; the reply parcel's `createStringArrayList()` holds the
  supported tracker names as plain strings (e.g. `"SPO2_ON_DEMAND"`), not
  an enum ordinal. Matches the 19-entry list the official SDK gave on the
  same watch.

This one call needs no Parcelable layout — it is proven by the smoke test
below.

## Finding 2 — what is still open

- `createHealthTracker`'s four fields (two strings, a `TrackerUserProfile`
  Parcelable, a fourth string) are not yet identified by meaning (app id?
  a session token the caller makes up? the tracker type name?).
- The inbound `ServiceDataPoint` / `ServiceValue` Parcelable layout — how a
  reading's fields (e.g. SpO2's `STATUS`, `SPO2`, `HEART_RATE`,
  `ACCURACY_FLAG`) are packed — is not yet reverse engineered. This is the
  part that actually carries health values, so it is the part that most
  needs independent verification against the official SDK's own output
  before any result from it is trusted.
- `TrackerUserProfile`'s own fields are unknown.

## Plan

1. Prove the bind + the one simple call on-device (below). ✅
2. Work out `createHealthTracker` + `setEventListener` + the
   `IServiceEventListener` callback's inbound Parcel layout, one tracker
   type at a time, starting with `SPO2_ON_DEMAND` and
   `SKIN_TEMPERATURE_ON_DEMAND` — the two trackers that gave genuinely new
   data in the SDK smoke test and that work without a sustained gesture.
3. Validate every decoded field against the official SDK's reading of the
   same live tracker, side by side, before treating a value as real.
4. Only once a tracker's own wire format is solid: decide, in a separate
   step, whether and how it belongs in the shipped `:wear` app.

## Reproduce finding 1

A throwaway harness app (not in this repo) binds the service and calls
transaction 5 with just the interface token written; the reply's
`readException()` then `createStringArrayList()` gave the same 19 tracker
names the official SDK's `HealthTrackerCapability.getSupportHealthTrackerTypes()`
gave on the same watch, same session.

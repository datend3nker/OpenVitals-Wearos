# Watch: Automatic Activity Detection, More From The Sensors, Workout UI

> **Status (2026-11-04):** Research and proposal, nothing built. Current behaviour: [Watches](../features/watches.md#workouts-on-the-watch). Sections say what was checked and what is this project's own judgement.

## Goal

1. The watch notices an activity by itself, without GPS, and offers to keep it or records it.
2. Everything the sensors give and is useful is worked out from them: VO2 max, then walking analysis and the same for cycling.
3. The workout screens use what Wear OS offers, as Samsung Health and the others do.

## How the others detect an activity

Checked against vendor pages and manuals on 2026-11-04. Anything not found is marked "not found" instead of guessed.

| Vendor | What is detected | When it starts | Source |
| --- | --- | --- | --- |
| Samsung Galaxy Watch | Walking/running, cycling (not on Watch4/5), elliptical, rowing machine, dynamic workout, swimming | After 10 minutes of the activity; how it ends and whether the first 10 minutes are kept: not found in Samsung's page. A user manual says tracking stops after a pause of over a minute. | [Samsung support](https://www.samsung.com/us/support/answer/ANS10003306/) |
| Garmin Move IQ | Movement that matches familiar patterns, shown on the timeline; older devices start timed walks and runs | The minimum duration per activity type is a setting; the default was not confirmed | [Garmin manual](https://www8.garmin.com/manuals/webhelp/venu/EN-US/GUID-19399E88-6C66-434F-8587-0B75C70E34C7.html) |
| Fitbit SmartTrack (2015) | Continuous movement, the type guessed from intensity and pattern | 15 minutes by default, adjustable per type; no GPS, no live stats, no distance or pace in the history | [Fitbit](https://canarywww.fitbit.com/smarttrack), [Wareable](https://www.wareable.com/fitness-trackers/fitbit-updates-surge-and-charge-hr-with-automatic-exercise-detection-1978) |
| Apple Watch | A reminder to start, for running, walking, swimming, elliptical and rower; a reminder to end for all but "Other" | Not found | [MacRumors](https://www.macrumors.com/how-to/use-automatic-workout-detection-watchos-5/) |

What this says, and what it does not:

- Everyone waits roughly 10 to 15 minutes before calling it a workout. Short walks are the usual complaint (users report false and missed detections for Samsung and Fitbit, anecdotal).
- Apple asks ("reminder") and Garmin shows it on the timeline; Samsung and Fitbit record. Asking is the cautious choice and it is what is proposed first.
- No vendor says how it decides. Their pages describe the behaviour, not the rule. The rule below is therefore this project's own, to be tuned.

## What the watch already has

Per clock minute, for a week: steps, height climbed, heart rate, and the movement features that sleep detection uses (`AccelerationMinuteFeatures`, `docs/engineering/sleep-minute-features.md`). Nothing new has to be sensed to see a walk, a run or a ride; GPS is not needed.

## Proposed rule (judgement, to be tuned with data)

A detector over the last minutes of the watch's own minute rows, run when a minute row closes:

- **Walking/running/hiking**: step cadence above a floor for N consecutive minutes (cadence tells walk from run; a run is clearly higher). Heart rate above resting by a margin makes it stronger evidence, but is not required (a slow walk).
- **Cycling**: a steady heart rate raise **with a still wrist and few steps**; the steps stay low while the heart rate rises. Needs the barometer or later GPS to say outdoors from a spin bike.
- **Ending**: the cadence or heart rate drops below the floor for a few minutes.
- **First action**: a notification ("Looks like a walk, 12 min. Keep recording?") and the minutes already recorded are credited to the workout from the minute rows, as Fitbit and Samsung do. The numbers (N, the cadence floor, the margin) are not set here: they come from the data below, not from a paper.

The watch is the only place this can run: the phone is not always near.

## Data to collect

The rule is only as good as the data it is tuned on.

- **Our own**: an opt-in recorder on the watch next to `PpgRawLogger`: the minute rows plus the label the wearer gives (a started workout, or a "this was a walk" tap afterwards), exported by hand. Nothing leaves the device by itself.
- **Public**: CAPTURE-24 (Oxford, wrist accelerometer, 151 participants, 3883 hours of which 2562 annotated, CC BY 4.0; [Scientific Data](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC11484779/)) has free-living labels. It is a 100 Hz Axivity AX3 on the dominant hand, not a watch: useful for the walk/run/sit shape, not for the exact numbers. PAMAP2 was not checked.

## More from the sensors

Each item needs a verified source before it is implemented (`health-calculations-need-sources`). Listed, not started.

| Metric | Inputs the watch has | Source status |
| --- | --- | --- |
| VO2 max (resting estimate) | Resting heart rate, a measured or age-estimated maximum heart rate | Uth, Sørensen, Overgaard, Pedersen, "Estimation of VO2max from the ratio between HRmax and HRrest", Eur J Appl Physiol 91:111-115 (2004): VO2max about 15.3 x HRmax / HRrest. The authors say it was shown for well-trained men and awaits validation in other groups; with an age-estimated HRmax the error stays comparable to other indirect tests. A weak estimate for a general user: show it as such, or leave it |
| VO2 max (from a run) | Speed from GPS or cadence, heart rate | Not looked up yet |
| Walking analysis (step length, asymmetry, double support, steadiness) | Wrist accelerometer, cadence | Not looked up yet. A wrist sees the arm swing, not the feet: what a wrist can do is a research question first |
| Cycling (cadence, power estimate, climbs) | Barometer, GPS, heart rate; no cadence sensor on the wrist | Not looked up yet |
| Training load, recovery | Heart rate over a workout | Not looked up yet |

## Wear OS workout idioms

| Idiom | Status |
| --- | --- |
| Horizontal pager with the page indicator, controls one swipe to the left | Done (`RecordingScreen`) |
| Bottom action button (`EdgeButton`) | Used on the measurement screens; the workout controls are round buttons on their own page |
| Ongoing Activity: the pill on the watch face and in recents, with a stopwatch | Done (branch `wear/ongoing-workout`) |
| Ambient (always-on) mode of the workout screen | Open: the screen must dim and show only the elapsed time and heart rate; needs a watch to try |
| Haptic tick at each kilometre or mile, and at a heart rate zone change | Open |
| Auto-pause when standing still | Open: the tracker has pauses; the rule is not written |
| Watch face complications (steps, heart rate) | Open |
| Rotary crown on lists | The Compose lists scroll with it already |
| Always-on tile for a running workout | Open |

## Order of work

1. Collect labelled minute rows on the wearer's own watch for a few weeks (the opt-in recorder).
2. Write the detector as pure Kotlin in `:healthcalc`, tuned on that data and CAPTURE-24, and its tests.
3. The notification first; recording by itself only after the detector has run silently for a while.
4. The metrics above one at a time, each after its source is checked and listed.

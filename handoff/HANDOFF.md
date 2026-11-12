# Handoff, 2026-11-12

Branch `heart-rate-zones` (fork/heart-rate-zones, commits 133bdebb, 52302665) is pushed, no PR yet.
#357 (calories) and #359 (coffee recipes) are merged into main (2026-11-11 and 2026-11-12).

## Open
1. Open the PR for heart-rate-zones (draft or normal); text below.
2. Correct the OpenLink agent brief: a profile field for zones IS justified, because the user can set own limits (HeartZoneThresholds, five lower bpm). OpenLink #7 leaves OpenLinkProfile unchanged. Ask whether summary zoneLowerBoundsBpm means five ACSM zones, zone 1 first.
3. Strings activity_heart_rate_zones_manual: feature names in 16 languages are guessed; ar/el/iw are AI translations.
4. After #357/#359 (merged): flaky Instant.now() privacy test on main; existing installs miss corrected drink presets; unsourced: Americano 30 ml, Ice Java 15 ml, white coffee profile, Starbucks Tall 66 mg.
5. Differences from OpenWatch #36: no observed max in the detail screen; its basis doc says resting = profile else night, code uses the profile only; 60 s sample gap is an app choice. Body Energy zones (0 to 5, 75 percent split) differ from #36's five.
6. Untracked locally: .claude/skills/caveman-learn/ (not committed).

## PR text (heart-rate-zones)

Heart rate zones in the activity detail, on the ACSM's intensity classes

OpenWatch #36 has heart rate zones and calls them a fallback until OpenVitals shares its zones. OpenVitals only had zones inside Body Energy (private, with a 75 percent split that has no source, plus the user's own limits). This shows zones in the activity detail: the user's own limits when set, else OpenWatch's computation, so one heart rate lands in the same zone in both apps.

- Own limits: if the user set manual zones in the Body Energy calibration, those apply.
- `HeartRateZones` (`:domain`): the five ACSM intensity classes (Garber et al., Med Sci Sports Exerc 2011, table 4). Shares of the heart rate reserve (Karvonen) when a resting rate is known, shares of the maximum otherwise. Also the time in each zone.
- `HeartRateZoneBasis` (`:domain`): the maximum is the profile's, else a believable observed maximum, else Tanaka's formula (Gulati's for women). It reuses `ageMaxHeartRate` and `isObservedMaxHeartRateTrustworthy`, so there is no second copy of those.
- Activity detail: a card with the time in each zone and its bpm range, and the maximum it rests on. No card without a maximum or age.

Body Energy is unchanged and keeps its own zone estimate.

Nothing goes over OpenLink in this PR. The user's own limits are not shared yet; that would need a profile field. Resting and maximum rate already cross in `OpenLinkProfile`, and OpenWatch lets them win over its fallback, so an old or new OpenWatch needs nothing to match. No OpenWatch version is required.

Known differences from OpenWatch #36:
- The detail screen passes no observed maximum (the recovery read does the same). OpenWatch reads the year's highest heart rate from Health Connect. For a user with no entered maximum and a believable observed one, the two apps can pick different maximums. `HeartRateZoneBasis.of` takes it as a parameter, so a caller can add it.
- OpenWatch's `HeartRateZoneBasis` docs say the resting rate is the profile's, else the latest night's, but its code only uses the profile's (the night's only decides whether an observed maximum is believable). This copies the code. Worth fixing on one side.
- A gap of over 60 seconds between readings counts for no time. App choice, no source.

Strings are in all locales; ar/el/iw are AI translations, a native look would help.

Tests: zone bounds (with and without resting rate, rounding), time in zones, the basis choice, and the detail ViewModel.

🤖 Generated with [Claude Code](https://claude.com/claude-code)


---

# Doppelte Rechenklassen OpenVitals ↔ OpenWatch

Stand: OpenVitals `coffee-recipe-catalog` (auf main 2026-11-12), OpenWatch lokal `workout-recovery-from-stillness`
und `origin/heart-rate-zones` (PR #36). Methode: `domain/src/main` beider Repos nach Dateinamen
abgeglichen, Text nach Ersetzen von `openvitals`/`openwatch` verglichen ("identisch" = nur dieser Name
unterscheidet sich). Nicht geprüft: Verhalten, nur Quelltext.

## A. Gleicher Dateiname, in beiden `domain/`

| Name | Pfad OpenVitals (`domain/src/main/kotlin/tech/mmarca/openvitals/`) | Pfad OpenWatch (`domain/src/main/kotlin/tech/mmarca/openwatch/`) | Stand | Reines JVM |
|---|---|---|---|---|
| ActivityModels | domain/model/ | domain/model/ | identisch | ja |
| AppThemeMode | domain/preferences/ | domain/preferences/ | identisch | ja |
| BioimpedanceComposition | domain/insights/ | domain/insights/ | identisch | ja |
| BleSensorModels | domain/model/ | domain/model/ | **abweichend** (KDoc anders; OpenWatch hat zusätzlich `isGarminGfdi`, `isGarminWatch`, `isWearosWatch`) | ja |
| BodyProfile | domain/preferences/ | domain/preferences/ | identisch | ja |
| CoMapsNavigation | domain/model/ | domain/model/ | identisch | ja |
| CoMapsRouteSimplify | domain/model/ | domain/model/ | identisch | ja |
| FitCrc, FitDecoder, FitEncoder, FitFloat16 | core/fit/ | core/fit/ | identisch | ja |
| GeoDistance | core/geo/ | core/geo/ | identisch | ja |
| HealthConnectAvailability | domain/model/ | domain/model/ | identisch | ja |
| ImportedClientRecordIds | domain/model/ | domain/model/ | identisch | ja |
| RecordingMethod | domain/model/ | domain/model/ | identisch | ja |
| RouteElevation | domain/insights/ | domain/insights/ | identisch | ja |
| SleepLabels | domain/insights/ | domain/insights/ | identisch | ja |
| UnitQuantity, UnitSystem, UnitSystemPreference | domain/preferences/ | domain/preferences/ | identisch | ja |
| SystemUnitSystemProvider | domain/preferences/ | domain/preferences/ | identisch | **nein** (importiert `android.*`) |
| WalkingEnergy | domain/insights/ | domain/insights/ | identisch | ja |

21 von 22 Dateipaaren sind textgleich. Kopiert aus OpenVitals 2.16.0 (OpenWatch `AGENTS.md` Z. 11) und
seitdem nicht auseinandergelaufen, außer `BleSensorModels` (OpenWatch hat drei Zusatz-Properties).

## B. Gleiche Rechnung, andere Datei oder anderer Aufbau

| Rechnung | OpenVitals | OpenWatch | Stand | Reines JVM |
|---|---|---|---|---|
| Banister-TRIMP (Morton 1990) | `domain/insights/CardioLoad.kt` (Koeffizienten 0,64/1,92 und 0,86/1,67) | `wearlink/.../WorkoutMetrics.kt` `banisterTrimp` (selbe Koeffizienten) | gleiche Quelle und Konstanten, getrennte Umsetzung; OpenWatch nimmt bei unbekanntem Geschlecht den Mittelwert | ja / ja |
| Keytel-Kalorien (Keytel 2005) | `domain/insights/WorkoutEnergy.kt` (Sitzungsmittel der HF, Geschlecht nötig) | `WorkoutMetrics.keytelKcal` (je Herzschlag-Spanne, Boden = Mifflin, unbekanntes Geschlecht = Mittel) | **abweichend**: gleiche Koeffizienten, anderes Vorgehen. Gleiche Eingaben können andere kcal geben | ja / ja |
| Mifflin-St-Jeor | `domain/insights/BasalMetabolicRate.kt` | `WorkoutMetrics.mifflinKcalPerMinute` | gleiche Quelle, getrennt; nicht Zeile für Zeile verglichen | ja / ja |
| Gehenergie | `domain/insights/WalkingEnergy.kt` | dasselbe | identisch (Abschnitt A) | ja |
| HF-Erholung (Cole 1999) | `domain/insights/HeartRateRecovery.kt` (Fenster, Qualität, Spitze) | `WorkoutMetrics.recovery` (Mittel ±5 s, Wert nach 1 und 2 min) | **abweichend**, OpenVitals deutlich reicher | ja / ja |
| Max-HF (Tanaka/Gulati, beobachtetes Maximum ≥150 und ≥ Ruhe+60) | `domain/insights/MaxHeartRate.kt` | `domain/insights/HeartRateZoneBasis.kt` (PR #36; Kommentar nennt die Schwellen "OpenVitals' heuristic, kept so both apps agree") | gleiche Formeln und Schwellen, **zwei Kopien**, OpenWatch nennt die Schwellen ausdrücklich gespiegelt | ja / ja |
| Zonen (ACSM, Karvonen) | **fehlt** | `wearlink/.../HeartRateZones.kt` (PR #36; auf dem lokalen Branch noch in `WorkoutMetrics.kt`) | nur OpenWatch | – / ja |
| VO2max aus Gehen/Laufen (ACSM + Swain) | **fehlt** (nur die ACSM-Geh-Gleichung in `WorkoutEnergy.kt`) | `WorkoutMetrics.vo2Max` | nur OpenWatch | – / ja |
| Splits | `features/activity/ActivityDetailDisplay.kt` (`:app`, nicht `:domain`) | `WorkoutMetrics.splits` | getrennt; OpenVitals' liegt im App-Modul | nicht geprüft |
| FIT-Routen-Parser, Höhenkorrektur | `features/manualentry/activity/routeimport/{FitRouteParser,RouteElevationCorrection}.kt` | `activityimport/{FitRouteParser,RouteElevationCorrection}.kt` | **abweichend** (Parser nahe gleich, Höhenkorrektur nicht) | Parser ja, Korrektur nein |

## C. Nur in OpenWatch (kein Gegenstück in OpenVitals `:domain`)

CaloriesBurned, GarminSleepMinute, GarminWellnessMetric, GeoCoordinateParser, HeartRateZoneBasis,
Lids, NocturnalRestingHeartRate, ScaleWeighIn, SharedGeoPoint, SleepStageEstimator, SleepWakeScorer,
SptWindowDetector, VitalsUnits, WearMinuteGrid, WearNaps, WearNight, WearSleepEstimator,
WearSleepMinute, WearStateDetector.
Die Schlafschätzer (Stage, Wake, SPT, Naps) gibt es in OpenVitals nicht; sie sind OpenWatch-eigen.

## D. Gleiche Dateinamen in `:app` (Auswahl, nicht Rechenlogik)

Gleich im Text und ohne Android: `DispatcherProvider`, `DisplayValue`, `BodyProfilePreferences`.
Gleich, aber Android: `AppForegroundGate`, `AutoResizeText`, `CoMapsNavigation{Repository,Source}`,
`BlePermissions`, `Color`, `CurrentLocaleStrings`, `HealthConnectAvailabilityService`. Abweichend:
`BleSensorCoordinator`, `SyncRecordsReader`, `HealthConnectSyncGate`, `AppModule`, `AppNavigation`.
Das sind Gerüst und UI, keine Rechenklassen, und kämen für ein Rechen-Repo nicht in Frage.

## Einschätzung zu einem gemeinsamen Repo (nicht als Entscheidung)

Dafür spricht: Abschnitt A zeigt, dass Kopien in der Praxis stabil bleiben, solange niemand sie
anfasst; Abschnitt B zeigt, wo sie schon laufen: Keytel und HF-Erholung rechnen in beiden Apps
verschieden, die Max-HF-Schwellen stehen zweimal mit einem Hinweis "damit beide übereinstimmen". Wer
eine Formel korrigiert (zuletzt Keytel/MET in #357), muss es heute zweimal tun und merken, dass es
zwei Stellen gibt. Dagegen spricht: ein drittes Repo heißt eine dritte Release- und CI-Kette für
Änderungen, die bisher ein PR in einem Repo waren; beide Apps müssten bei jeder Formeländerung ein
Submodul heben, und eine geänderte Formel ändert gespeicherte Werte (Body Energy hat eine Zeitleiste
in Room) – das braucht Versionierung der Ergebnisse, nicht nur des Codes. In Frage kämen nur Klassen
aus Abschnitt A und die reinen Formeln aus B, also reines JVM ohne `android.*`: FIT, Geo, Einheiten,
Modelle, Max-HF, Zonen, TRIMP, Energie. Das Submodul-Muster von `openlink/` passt dafür, weil es
dasselbe Problem löst (ein Vertrag, zwei Apps, ein festgehaltener Stand) und OpenWatch es schon
einbindet; es heißt aber, dass `:domain` heute `tech.mmarca.openvitals.*` heißt und dort die Pakete
umbenannt werden müssten, und dass `AGENTS.md` von OpenWatch ("teilt zur Laufzeit keinen Code")
geändert würde. Billiger Zwischenschritt: ein Test, der die gespiegelten Dateien beider Repos
vergleicht (OpenWatch beschreibt ein früher so gehaltenes kopiertes Protokoll als "held in sync only by a test", architecture.md Z. 185), statt sofort ein Repo.

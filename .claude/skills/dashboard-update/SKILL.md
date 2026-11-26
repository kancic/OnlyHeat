---
name: dashboard-update
description: Use when the TTO Advance controller's local dashboard (http://192.168.42.1/) may be on a new firmware version — the version under the login form differs from the newest docs/dashboard-api-<version>.md, the user says the controller updated (it auto-updates at night), or local features (finding the controller, schedule, API PIN, restart) suddenly fail. Also use when the official TTO Advance app (com.thermotechnik.ttoadvanceapp) has a new version — its versionName differs from the newest docs/cloud-api-<version>.md, the user says the app updated — or cloud features (login, rooms, setting temperatures, history, smart heating) suddenly fail. Captures the new dashboard or cloud API, documents what changed in a new versioned doc, and fixes OnlyHeat's code if needed.
---

# Dashboard firmware and official app updates

Two things can change under OnlyHeat without warning. Each has its own track:

- **A. The local dashboard** (steps 1–6) changes when the controller installs a firmware update, which it
  does by itself at night.
- **B. The cloud API** (`https://api.smart-heating.co/api-remote`, steps B1–B5) is defined by the official TTO Advance
  app. When that app updates, the server may have new or changed endpoints. A firmware update can also change what
  the cloud returns (AccuWeather values arrived with 1.0.9.2.0), so after documenting a new firmware, run track B's
  capture as well and diff it.

## A. Dashboard firmware

Every firmware gets its own doc and raw snapshot in `docs/`:

- `docs/dashboard-api-1.0.7.0.8.md`: the full reference (the first capture).
- `docs/dashboard-api-<version>.md`: what changed since the previous version.
- `docs/dashboard-snapshot-<version>/`: the raw capture of that version.

The app code that depends on the dashboard is `shared/src/commonMain/kotlin/ancic/karim/onlyheat/data/Discover.kt`
(finding and recognising the controller) and `LocalApi.kt` (login, schedule, API PIN, reboot).

### 1. Detect

```sh
curl -s -m 5 http://192.168.42.1/ | grep -oE 'TTO Advance [0-9.]+'
ls docs/dashboard-api-*.md
```

If the version is already documented, stop and tell the user. If the controller doesn't answer, the computer is
probably not on its Wi-Fi ("TTO Advance-XXXXXX" or the renamed SSID). Ask the user to connect, or use its home LAN
address from the desktop app's saved `savedLanController` (`defaults read com.apple.java.util.prefs`).

### 2. Capture (read-only)

```sh
python3 -I .claude/skills/dashboard-update/capture.py <scratchpad>/dashboard-<version> [host] [pin]
```

The script logs in, saves every page, modal, `app.js` and `.htaccess`, lists every `.php` the pages reference
(`endpoints.txt`), and probes the read-only endpoints three ways: with session and `X-Requested-With`, with the
session only, and anonymously (`probe.txt`). It redacts the API PIN. The PIN defaults to the desktop app's saved
`localPin`. If the login fails, ask the user for the 4-digit dashboard PIN. Never guess it.

**Safety:** never call anything that writes, reboots, switches relays, rescans sensors or changes the API PIN or
Wi-Fi. That covers every `save-*`, `change-*`, `delete-*`, `enable-/disable-smart-heating`, `generate-api-pin`,
`device-restart`, `run-command-test-on/off`, `swap-t-sensors-*`, `create-wizard-*`, `run-sonar` and `set-device`.
A write is allowed only when it is a no-op, e.g. saving the schedule that is already there. Say so in the doc.
Note anything you couldn't test.

### 3. Diff

```sh
python3 -I .claude/skills/dashboard-update/diff.py docs/dashboard-snapshot-<previous> <scratchpad>/dashboard-<version>
```

Ignore live values: temperatures, clock, login history, weather and chart data. Look for changes in:

- page `<title>`s, the login form and `TTO Advance-XXXXXX (*)` (what `Discover.kt` recognises)
- `probe.txt`: endpoints that now reject requests, or whose reply format changed
- new or removed endpoints (`endpoints.txt`), modals and form fields
- `#formSchedule` markup and the `save-schedule.php` parameters
- `id="apiPin"` on `/setup`
- `.htaccess`, cookies (`curl -si http://192.168.42.1/ | grep -i set-cookie`), and anything in `app.js`

### 4. Check OnlyHeat against the device

Run the app's real code with a temporary desktop test, then delete the test file.

```kotlin
// shared/src/desktopTest/kotlin/ancic/karim/onlyheat/data/LiveFirmwareCheck.kt — temporary, delete after the run
class LiveFirmwareCheck {
    @Test fun live() = runBlocking {
        val node = java.util.prefs.Preferences.userRoot().node("onlyheat-livecheck")
        try {
            val api = LocalApi("192.168.42.1", "<pin>", com.russhwolf.settings.PreferencesSettings(node)) { null }
            val before = api.schedule(); println("LIVE schedule ${before.entries}")
            println("LIVE apiPin ${api.apiPin(DashboardTarget("192.168.42.1", "<TTO Advance-XXXXXX>")).apiPin.length} digits")
            println("LIVE save ${api.save(before.entries)?.entries}") // same schedule back: no change
        } finally { node.removeNode() }
    }
}
```

```sh
./gradlew :shared:desktopTest --tests 'ancic.karim.onlyheat.data.LiveFirmwareCheck' -i | grep -E 'LIVE|FAILED|Exception'
```

`LocalApi`'s signatures change over time. Read the file first and adapt the test. Never call `rebootAndWait()`
unless the user agrees to a reboot.

### 5. Fix the app (only if something broke)

Keep every older firmware working too. The controller can be on any version, and other units may never update.
Accept old and new, as `dashboardTitle` does (`TermoTehnik PI|ThermoTechnik ADVANCE`). Comment each fix with the
firmware it is for. Add or extend a unit test with the new markup (`DiscoverTest`, `ScheduleTest`), run
`./gradlew :shared:desktopTest`, then rerun the live check.

### 6. Document

1. Save the capture as `docs/dashboard-snapshot-<version>/`.
2. Write `docs/dashboard-api-<version>.md` in the style of `docs/dashboard-api-1.0.9.2.0.md`, listing only what
   changed since the previous version:
   - a header (version, date, a link to the previous doc and both snapshots)
   - changes that affect clients, each with an **OnlyHeat impact** line saying whether it is fixed
   - new features and endpoints, stating which were executed and which come from reading the scripts
   - weather, UI and cosmetic changes
   - what you re-verified as unchanged
   - a short client recipe (which cookies and headers each kind of request needs)
3. Add a one-line pointer to the new doc at the top of the previous version's doc.
4. Leave older docs and snapshots otherwise unchanged.

## B. Official app and cloud API

Every app version gets its own doc and raw snapshot in `docs/`:

- `docs/cloud-api-1.0.3.md`: the full reference (the first capture).
- `docs/cloud-api-<version>.md`: what changed since the previous version.
- `docs/cloud-snapshot-<version>/`: the redacted replies of every read endpoint, `probe.txt` (error cases and
  transport variations), `endpoints.txt` (the endpoints and params the app sends) and `version.txt`.

The app code that depends on the cloud is `shared/src/commonMain/kotlin/ancic/karim/onlyheat/data/Api.kt`.

### B1. Detect

With the phone connected over adb:

```sh
adb shell dumpsys package com.thermotechnik.ttoadvanceapp | grep -E 'versionName|versionCode|lastUpdateTime'
ls docs/cloud-api-*.md
```

If the version is already documented, and no firmware update prompted this run, stop and tell the user. Without a
phone, the user can read the version from the Play Store page or the app's settings.

### B2. Extract the endpoints from the app

The app is Expo / React Native, so its logic is in Hermes bytecode in `assets/index.android.bundle`. Pull and
decompile it into the scratchpad. Never put it in the repo.

```sh
mkdir -p <scratchpad>/tto && cd <scratchpad>/tto
for p in $(adb shell pm path com.thermotechnik.ttoadvanceapp | sed 's/package://'); do adb pull "$p"; done
unzip -o -q base.apk assets/index.android.bundle assets/app.config
uvx --python 3.12 --from hermes-dec hbc-decompiler assets/index.android.bundle dec.js
grep -oE "'/(get|set|del|add|update|remove|save|check|reset|change)[A-Za-z0-9]*'" dec.js | sort -u
grep -n "api-remote" dec.js        # base URL; check it hasn't moved
# params: the FormData keys appended just before each endpoint string
for e in $(grep -oE "'/[a-z][A-Za-z0-9]+'" dec.js | sort -u | tr -d "'/"); do
  n=$(grep -n "'/$e'" dec.js | head -1 | cut -d: -f1)
  echo "$e: $(sed -n "$((n-70)),${n}p" dec.js | grep -oE "'[A-Za-z0-9_]+'" | tr '\n' ' ')"
done
```

The params loop prints every string literal near the call. Pick the FormData keys out by hand, and read the
surrounding code for conditional ones (e.g. `smartTarget` only when `isSmart` is `'1'`). Compare against the
previous `endpoints.txt`. To see which reply fields the app reads, grep for `'field'` or `.field`.

### B3. Capture (read-only)

```sh
python3 -I .claude/skills/dashboard-update/cloud-capture.py <scratchpad>/cloud-<version> [authKey]
```

The auth key defaults to the desktop app's saved `authKey`. It can also come from OnlyHeat on the phone
(`adb shell run-as ancic.karim.onlyheat cat shared_prefs/ancic.karim.onlyheat_preferences.xml`, debug builds only).
Never print the key, and never commit it. The script redacts it.

**Safety:** never call `setRoomTemp`, `setRoomTemp4All` or `setRoomSmart`. They switch real heating. Never call
`getKey` with the real PIN, because it mints a key. If an app version adds endpoints, add only the read-only ones to
the script's `reads`. Document writes from the app code and say they weren't executed.

### B4. Diff

```sh
python3 -I .claude/skills/dashboard-update/diff.py docs/cloud-snapshot-<previous> <scratchpad>/cloud-<version>
```

Ignore live values: temperatures, `executionTime`, `dateCreated`, the forecast and login history. Look for new or
removed endpoints and params, renamed or retyped fields (string ↔ number ↔ `false`), and new error texts in
`probe.txt`, especially the auth errors `Api.kt` matches on.

### B5. Check, fix and document

- Check: run `./gradlew :shared:desktopTest`, and exercise the app against the live cloud (home, history, smart).
  If needed, use a temporary desktop test like the one in step 4 that calls `HeatApi` read methods only, then
  delete it.
- Fix: keep older replies parsing too, and comment each fix with the app version or firmware it is for. Extend
  `ApiTest` with the new reply.
- Document: save the capture as `docs/cloud-snapshot-<version>/` with `endpoints.txt` and `version.txt`. Write
  `docs/cloud-api-<version>.md` in the style of the dashboard change docs, listing only what changed since the
  previous version, with **OnlyHeat impact** lines and live vs. read-from-code marked. Then add a one-line pointer
  at the top of the previous cloud doc. If the change came from a firmware update, say so and link the matching
  `dashboard-api-<version>.md`.

## Wrap-up

Report the findings to the user, the fixes, and what couldn't be tested. Commit only when the user asks. Commits go
directly on `main`.

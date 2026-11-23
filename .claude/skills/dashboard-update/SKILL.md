---
name: dashboard-update
description: Use when the TTO Advance controller's local dashboard (http://192.168.42.1/) may be on a new firmware version — the version under the login form differs from the newest docs/dashboard-api-<version>.md, the user says the controller updated (it auto-updates at night), or local features (finding the controller, schedule, API PIN, restart) suddenly fail. Captures the new dashboard, documents what changed in a new versioned doc, and fixes OnlyHeat's local code if needed.
---

# Dashboard firmware update

The controller installs firmware updates by itself at night, so the dashboard can change without warning.
Every firmware gets its own doc and raw snapshot in `docs/`:

- `docs/dashboard-api-1.0.7.0.8.md`: the full reference (the first capture).
- `docs/dashboard-api-<version>.md`: what changed since the previous version.
- `docs/dashboard-snapshot-<version>/`: the raw capture of that version.

The app code that depends on the dashboard is `shared/src/commonMain/kotlin/ancic/karim/onlyheat/Discover.kt`
(finding and recognising the controller) and `LocalApi.kt` (login, schedule, API PIN, reboot).

## 1. Detect

```sh
curl -s -m 5 http://192.168.42.1/ | grep -oE 'TTO Advance [0-9.]+'
ls docs/dashboard-api-*.md
```

If the version is already documented, stop and tell the user. If the controller doesn't answer, the computer is
probably not on its Wi-Fi ("TTO Advance-XXXXXX" or the renamed SSID). Ask the user to connect, or use its home LAN
address from the desktop app's saved `savedLanController` (`defaults read com.apple.java.util.prefs`).

## 2. Capture (read-only)

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

## 3. Diff

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

## 4. Check OnlyHeat against the device

Run the app's real code with a temporary desktop test, then delete the test file.

```kotlin
// shared/src/desktopTest/kotlin/ancic/karim/onlyheat/LiveFirmwareCheck.kt — temporary, delete after the run
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
./gradlew :shared:desktopTest --tests 'ancic.karim.onlyheat.LiveFirmwareCheck' -i | grep -E 'LIVE|FAILED|Exception'
```

`LocalApi`'s signatures change over time. Read the file first and adapt the test. Never call `rebootAndWait()`
unless the user agrees to a reboot.

## 5. Fix the app (only if something broke)

Keep every older firmware working too. The controller can be on any version, and other units may never update.
Accept old and new, as `dashboardTitle` does (`TermoTehnik PI|ThermoTechnik ADVANCE`). Comment each fix with the
firmware it is for. Add or extend a unit test with the new markup (`DiscoverTest`, `ScheduleTest`), run
`./gradlew :shared:desktopTest`, then rerun the live check.

## 6. Document

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

Report the findings to the user, the fixes, and what couldn't be tested. Commit only when the user asks. Commits go
directly on `main`.

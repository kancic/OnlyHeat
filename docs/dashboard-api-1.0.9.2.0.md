# TTO Advance local dashboard: firmware 1.0.9.2.0

Captured on 2026-11-23, right after the update, from the same controller (`http://192.168.42.1/`, S/N
`TTO-0000000012345678`, `TTO Advance-A1B2C3`). Static assets are now `?v=1.0.79`.

This document lists **only what changed** since 1.0.7.0.8. Anything not mentioned here behaves as described in
[`dashboard-api-1.0.7.0.8.md`](dashboard-api-1.0.7.0.8.md), and that document is kept unchanged as the reference for the old firmware.
Raw pages, modals, `app.js` and `.htaccess` are in [`dashboard-snapshot-1.0.9.2.0/`](dashboard-snapshot-1.0.9.2.0/)
(API PIN redacted). The old capture is still in [`dashboard-snapshot-1.0.7.0.8/`](dashboard-snapshot-1.0.7.0.8/).

## 1. Changes that affect clients (and OnlyHeat)

### 1.1 New page title

Every page's `<title>` changed from `<Page> | TermoTehnik PI` to **`<Page> | ThermoTechnik ADVANCE`**, e.g.
`Dashboard | ThermoTechnik ADVANCE`. The login page markup a client recognises is unchanged:

- `<form id="loginForm">` with `loginInput1`…`loginInput4`
- the device dropdown option `TTO Advance-A1B2C3\n (*)`
- `TTO Advance 1.0.9.2.0<br>S/N: …` under the form (the old `<br>` in front is gone, and the text is no longer white)

**OnlyHeat impact (fixed):** the `dashboardTitle` regex in `Discover.kt` matched only the old title, so the
controller was never found. It now accepts both titles.

### 1.2 AJAX endpoints need a session and `X-Requested-With`

On 1.0.7.0.8 almost every AJAX endpoint answered anyone. Now every endpoint tested under `/include/ajax/`,
`/include/modals/`, `/include/templates/` and `/run-command-*.php` requires **both**:

1. a logged-in `PHPSESSID` session, and
2. the header **`X-Requested-With: XMLHttpRequest`**, which jQuery adds automatically.

If either is missing, the endpoint returns HTTP **200** with an error body:

| Kind of endpoint | Body when rejected |
|---|---|
| JSON and `run-command-*` endpoints (yes, including the plain-text ones) | `{"type":"error","message":"Login session expired, please login again."}` |
| Modals (`/include/modals/*.php`) and `/include/templates/*.php` | `<div class="modal-body">Login session expired, please login again.</div>` |
| `login-check.php` with `currentPin` and no session | `{"valid":false,"type":"error","message":"Login session expired, please login again."}` |

Tested and confirmed to need both: `get-system-time`, `get-system-temp`, `check-wifi-status`,
`load-paired-entity-ports`, `load-paired-entity-devices`, all `select-*`, `get-table-users`,
`get-device-live-log`, `accu-api-loc-search`, `update-tto-check`, `save-schedule`, `save-paired-entity-temp`,
`run-command-test-temp`, `run-command-test-hum`, `run-command-read-room-status`, `run-command-read-status`,
`run-command-read-fan-status`, `dashboard-added-devices`, and every modal.

Still open (no session, no header needed):

- **`login-check.php` with `loginInput1..4`.** It works without the header and even without loading `/dashboard`
  first. Replies are unchanged (`{"valid":true,"type":"success",…}` / `"Wrong PIN. Retry!"`).
- **`device-status.php`**, which still replies `status`. Reboot detection keeps working.

Not tested, because calling them would change something: `device-restart.php`, `run-sonar.php`, `set-device.php`,
`logout.php`. Assume they need both like the rest.

Pages (`/scheduler`, `/setup`, `/logs/N` …) still need only the session cookie. No header is needed, and without a
session they still return the login page with HTTP 200 and the requested page's title.

**OnlyHeat impact:**

- **Fixed:** `LocalApi`'s client now sends `X-Requested-With: XMLHttpRequest` on every request, so schedule saves
  and `device-restart.php` work again. Before the fix, the save got "Login session expired" and the reboot was most
  likely ignored.
- **Worked around:** the controller picker searches before anyone has logged in, so `check-wifi-status.php` now
  answers it with the error JSON. Nothing else gives the Wi-Fi name without the dashboard PIN: not the login page,
  and not the cloud `getRooms` (which only has `globalDeviceName`, the sonar name, and `globalIpAddress`). So after
  each successful login, `LocalApi` reads the Wi-Fi name and remembers it per controller name (setting
  `wifiName.<TTO Advance-XXXXXX>`), and the picker shows the remembered name. A controller the app has never logged
  in to shows only its `TTO Advance-XXXXXX` name.
- Reading the schedule (`GET /scheduler`) and the API PIN (`GET /setup`, `id="apiPin"`) still work unchanged.

### 1.2.1 Clearing the schedule is answered with a warning

Saving an empty schedule (the single blank row, or no fields at all) replies
`{"type":"warning","message":"No data sent, schedule disabled."}`. The schedule is still cleared. It isn't known
whether 1.0.7.0.8 answered the same way, because an empty save was never sent to it. `LocalApi.save()` now treats
`warning` as success when it sent no entries.

### 1.3 Session cookie

`Set-Cookie: PHPSESSID=…; path=/; SameSite=Lax; HttpOnly` (it used to be `path=/` only). `ttodevice` is unchanged.
How long a session lasts was not measured.

### 1.4 `ttodevice` proxy limited to private addresses

`.htaccess` (still downloadable at `GET /.htaccess`) now proxies only to private IPv4 addresses:

```apache
RewriteCond %{HTTP_COOKIE} ttodevice=((?:10\.[0-9]{1,3}|192\.168|172\.(?:1[6-9]|2[0-9]|3[01]))\.[0-9]{1,3}\.[0-9]{1,3})(?:;|$) [NC]
```

All other rules are identical.

## 2. New features and endpoints

| Feature | Endpoint | Params / reply | Where |
|---|---|---|---|
| Delete the API PIN | `POST /include/ajax/delete-api-pin.php` | no params → `{type, message, APIpin}`. The page shows `APIpin` afterwards. | Setup → API PIN → "Generate new PIN" now opens a confirmation dialog (`#apiPinModal`) with **Cancel / Delete / Confirm**. Confirm calls `generate-api-pin.php` as before. The dialog warns that deleting "disconnects this TTO Advance from the mobile application and the remote portal until a new PIN is generated". |
| Automatic updates | `POST /include/ajax/change-auto-update.php` | `autoUpdate` = `1` \| `0` → `{type, message}` | Setup → Update dialog: checkbox "Install new versions automatically at night" (`#autoUpdateSwitch`), **checked** on this unit. |
| Daily sensor reset | `change-info-settings.php` takes a new field `resetSens` = `0` NO \| `1` YES | | System settings dialog: "(SERVICE option) Reset Temperature Sensors every 24h (@03:26)". `NO` on this unit. The dialog's script updates `#infoResetSens`, but the Setup page has no element with that id, so the card never shows the value. |

None of these were executed. Parameters and replies come from the page scripts.

**Auto-update is on**, so the firmware, and with it everything in this document, can change overnight without
anyone installing anything. After any unexplained breakage, check the version under the login form first.

**API PIN deleted:** if someone deletes the PIN, `parseApiPin()` will probably find no PIN in `#apiPin` and OnlyHeat
throws `NoApiPin`. That case was not captured.

## 3. Weather and Thermal Intelligence

"Smart Heating" is renamed **"Thermal Intelligence"** everywhere: nav link, page `<h1>`, the dashboard button
`THERMAL INTELLIGENCE`, modal titles ("Enable thermal intelligence for room "X"?", "Set thermal intelligence
temperature"), the Setup card "Thermal Intelligence Weather Settings", the user permission label, and toasts. The URL
(`/weather`), element ids, and endpoints (`enable-/disable-smart-heating.php`, same params) are unchanged.

The chart series `Smart Target Temp.` is now **`Thermal Intelligence Target Temp.`**. Other series keys are
unchanged.

**AccuWeather works again:**

- `accu-api-loc-search.php?q=Zagreb` → `[{"id":"117910","text":"Zagreb; Zagreb City; Croatia"}]`
  (with session and header).
- Dashboard and `/weather` show "Current Conditions" with a real icon (`…/slate/icons/06.svg`), Temperature,
  Real Feel and a text (e.g. "Mostly cloudy"). A headline sentence follows the location line
  (e.g. "Rain from Wednesday afternoon until Thursday morning, when it will change to snow").
- New forecast cards. The dashboard shows "Forecast: Today" and "Forecast: Tomorrow". `/weather` adds three more days
  (e.g. "Wednesday 25."). Each card has an icon, Maximum, Minimum and a text.
- On `/weather`, `Outside Temperature`, `Predicted Outside Temp.` and `Predicted Room Temp.` now carry real values
  instead of 0. The stat tiles now show real numbers (e.g. Max. Outside 7.8, Min. Outside 2.2, Inside vs. outside
  DROP/h 17.6%) instead of `-20`/`60`/`inf%`. There's still no TS7 sensor, so the outside values come from
  AccuWeather. The chart's y-range follows the data (`forceY([-1.2,25])`).

## 4. Cosmetic and hardening changes

- **New branding:** logos `tto-logo-new.png`, `tto-logo-light-new.png`, the spinner
  `tto-thermotehnik-sekundirani-rotated.png`, favicons under `/images/favicon/`, decorative `header-stripes` in
  the navbar and footer, a white login card (no background photo) with a blue LOGIN button, and footer
  "Copyright TTO Thermotechnik".
- **Escaping:** `app.js` `pushNotification()` now escapes the message as text. The Wi-Fi dongle dialog escapes SSIDs
  and channels (`escHtml`), the Setup page writes the AP SSID with `.text()`, the Wi-Fi/LAN warnings write
  `&quot;r1m&quot;`, and room names in scripts are JSON-encoded (`"Dnevni boravak\/kuhinja"`).
- **"Add TTO device":** stops and re-enables the button when `check-api-local.php` doesn't reply
  `success: 'true'`.
- **Service page:** no longer calls `readFans()` on load.

## 5. Unchanged (re-verified)

- Login form fields, PIN `0000`, `login-check.php` replies, and the login history on Security.
- Reply formats of every read endpoint in §1.2 (when called with session and header) match the old document.
  Examples: `run-command-test-temp` → `24.3` / empty, `run-command-read-room-status` → `1`,
  `get-device-live-log` values are strings, `select-new-t-sensor` → `[{"id":0,"name":"- no new sensors detected -"}]`.
- The `#formSchedule` markup and the parallel-array `save-schedule.php` format (save accepted with session and
  header). The schedule is still empty (one hidden template row).
- `id="apiPin"` on `/setup`, the room/port/sensor ids, the `/logs/N` embedded chart data, holiday mode, and all
  other settings dialogs.
- The trash icon for deleting a room is still broken: the rendered class is `deleteDeviceButton`, but `app.js` binds
  `.deletePairedDevice`.

## 6. Client recipe for 1.0.9.2.0

```
GET  /dashboard                                   → PHPSESSID (HttpOnly) + ttodevice=127.0.1.1
POST /include/ajax/login-check.php                loginInput1..4          (header optional)
GET  /scheduler, /setup, /logs/N                  cookie only
POST /include/ajax/*.php, /run-command-*.php      cookie + X-Requested-With: XMLHttpRequest
GET  /include/modals/*.php                        cookie + X-Requested-With: XMLHttpRequest
POST /include/ajax/device-status.php              nothing (reboot probe)
```

Sending the header on every request is harmless, including page loads and login, so a client can just always send
it.

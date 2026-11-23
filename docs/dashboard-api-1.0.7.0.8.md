# TTO Advance local dashboard: firmware 1.0.7.0.8

Captured on 2026-11-23 from the controller at `http://192.168.42.1/` (its own Wi-Fi, renamed "r1m - Heat") and
`192.168.100.49` (the home LAN through its USB Wi-Fi dongle).

> **Update 2026-11-23:** the manufacturer released a new firmware for this unit. It was installed and rebooting
> the same day. Everything below, and `dashboard-snapshot-1.0.7.0.8/`, is the
> **pre-update baseline (1.0.7.0.8)**. Re-capture after the update and diff against it (§8 is the checklist).
> Until that's done, treat each detail here as unverified on the new firmware.
>
> **Done:** the new firmware is **1.0.9.2.0**. What changed is documented in
> [`dashboard-api-1.0.9.2.0.md`](dashboard-api-1.0.9.2.0.md), with a raw capture in
> [`dashboard-snapshot-1.0.9.2.0/`](dashboard-snapshot-1.0.9.2.0/). This document stays as the 1.0.7.0.8 reference.

Baseline firmware:

- Firmware **TTO Advance 1.0.7.0.8**, static assets `?v=1.0.73`
- Device name `TTO Advance-A1B2C3`, S/N `TTO-0000000012345678`
- Apache/2.4.25 (Raspbian), PHP (a `PHPSESSID` session), jQuery 3.2.1, Bootstrap 4 beta, nvd3/d3 charts, canvas-gauges

Raw HTML of every page and modal, plus `app.js` and the server's `.htaccess`, is in
[`dashboard-snapshot-1.0.7.0.8/`](dashboard-snapshot-1.0.7.0.8/). The API PIN is redacted there. When this document and the snapshot
disagree, the snapshot is right.

OnlyHeat uses this dashboard in `shared/src/commonMain/kotlin/ancic/karim/onlyheat/LocalApi.kt` (login, schedule,
API PIN, reboot) and `Discover.kt` (finding the controller, Wi-Fi name).

---

## 1. Network and addressing

| Interface | Address | Notes |
|---|---|---|
| Built-in access point ("ADVANCE base WiFi") | `192.168.42.1` (gateway) | Default SSID `TTO Advance-XXXXXX` (last 6 hex digits of the MAC). The user can rename it, e.g. `r1m - Heat`. Clients get `192.168.42.x`. |
| USB Wi-Fi dongle | DHCP from the home router (`192.168.100.49/24` here) | Connects as a client to the home Wi-Fi (WPA/WPA2-PSK only). A static IP can be set. |
| LAN (Ethernet) | DHCP, `automatic` | Configured in Setup → LAN. |

Plain HTTP on port 80 only. No TLS and no CORS headers. Every response sends `Cache-Control: no-store`.

### The `ttodevice` cookie and the built-in reverse proxy

The first visit to any page sets `ttodevice=127.0.1.1` (Max-Age 30 days), next to `PHPSESSID`. The server's
`.htaccess` (readable at `GET /.htaccess`, copy in `dashboard-snapshot-1.0.7.0.8/htaccess.txt`) routes on it:

```apache
RewriteCond %{HTTP_COOKIE} ttodevice=127 [NC]          # local device → front controller
RewriteRule ^.*$ ./index.php [QSA,L,END]               #   (only when the path isn't a real file or dir)
RewriteCond %{HTTP_COOKIE} !ttodevice=127? [NC]
RewriteCond %{HTTP_COOKIE} ttodevice=([^;]+) [NC]
RewriteRule ^ http://%1:80%{REQUEST_URI} [QSA,P,END,L] # any other value → reverse-proxied to that host
RewriteCond %{HTTP_COOKIE} !ttodevice? [NC]            # no cookie → front controller
RewriteRule ^.*$ ./index.php [QSA,L,END]
```

This is how one "Master" dashboard controls the "Slave" units found by the network sonar (§6.9). The login page's
device dropdown posts to `set-device.php`, which changes the cookie, and from then on every request goes to that
unit. **An API client should send `ttodevice=127.0.1.1`, or no cookie at all.** Any other value turns the controller
into a proxy to that host.

### Routing

Pretty URLs (`/dashboard`, `/setup`, `/logs/3` …) are served by `index.php`. Paths that don't exist render a themed
"403 Forbidden | TermoTehnik PI" page with HTTP **200**. Real directories (`/include/`, `/include/ajax/`) return a
real Apache 403. A real `.php` file runs directly, without the front controller.

---

## 2. Authentication

### Login

```
GET  /dashboard                                 → sets PHPSESSID + ttodevice (do this first)
POST /include/ajax/login-check.php
     Content-Type: application/x-www-form-urlencoded
     loginInput1=0&loginInput2=0&loginInput3=0&loginInput4=0     (one digit per field)
```

| Result | Body (`application/json`) |
|---|---|
| OK | `{"valid":true,"type":"success","message":"Login successfull. You'll be redirected in a few seconds."}` |
| Wrong PIN | `{"valid":false,"type":"error","message":"Wrong PIN. Retry!"}` |

- HTTP status is always 200. Check `type` (or `valid`).
- The PIN is **4 digits**. The factory default is `0000`, and this unit still uses it.
- The same endpoint also accepts `currentPin=NNNN` instead of `loginInput1..4`. The Security page uses this to check
  the current PIN before changing it, and the reply has the same shape.
- No rate limiting or lockout was seen.
- Every successful login is recorded (date, client IP) and shown on the Security page.
- Logout: `POST /include/ajax/logout.php` → `{"type":"success"}`.

### What the session actually protects (important)

- **Pages** (`/dashboard`, `/scheduler`, `/setup`, `/logs/N`, `/service`, `/wizard`, `/security`, `/weather`):
  without a session, the server returns the login page with **HTTP 200** and the requested page's `<title>`, e.g.
  `Schedule | TermoTehnik PI`. To tell them apart, look for `id="loginForm"` and the `loginInput1..4` fields. That is
  what `looksLikeDashboard()` in `Discover.kt` does. **The title alone doesn't tell you whether you're logged in.**
- **AJAX endpoints mostly don't check the session.** Every endpoint tested answered the same with and without a
  session, writes included (`save-paired-entity-temp.php` was confirmed). The only one seen to need a session is
  `GET /include/ajax/get-table-users.php`, which returns an empty body without one.
  OnlyHeat logs in anyway: it is cheap, checks the PIN, and stays correct if some endpoint does check.

### Users

The Security page can add extra users with their own 4-digit PIN and a set of allowed pages (`dashboard`, `logs`,
`service`, `setup`, `weather`, `scheduler`). Each user also gets an API PIN. No extra users exist on this unit.

---

## 3. Response conventions

- **Action endpoints** reply with JSON `{"type": "success"|"error"|"warning"|"information", "message": "..."}`.
  The dashboard shows `message` as a toast. `message` can be empty (`{"type":"success","message":""}`).
- **`run-command-*.php` endpoints** (hardware reads/writes) reply with **plain text** (`text/html`): a bare number,
  sometimes with a trailing `\n`, or an empty string for "no reading".
- Some JSON replies send numbers as strings (`"systemTemp":"47"`, live-log values `"24.187"`). Parse leniently.
- Parameters are read from **`$_POST`**. The same request as a GET with a query string returns an empty body for
  endpoints that take parameters. Endpoints without parameters (e.g. `check-wifi-status.php`) work with GET too.
- Errors are reported in the body, never through HTTP status. Network failures and timeouts are the only other way a
  call fails.

---

## 4. Data model

| Concept | Ids | Notes |
|---|---|---|
| **Actuator port** (relay) | `RK1`…`RK6` → `port_id` 1–6 | Drives the valve actuators (230 V or 24 V, set in System info). |
| **Temperature sensor** | `TS1`…`TS6` → `temp_id` 1–6, `TS7 (External)` → 7 | Room sensors on 1-wire. Raw readings come in 1/16 °C steps (`24.187`, `24.25`, `24.312`), which points to DS18B20. TS7 is the outdoor sensor, reported to the cloud as `s7Temp`/`s7Hum`. |
| **Room** ("paired entity") | `entity_id` (1, 2, 3 …) | Pairs one sensor with a main port, plus up to 5 extra "jumper" ports. On this unit: 1 = Kupaona (RK1/TS1), 2 = Dnevni boravak/kuhinja (RK2/TS2), 3 = Spavaca soba (RK3/TS3). The same id is the cloud API's `roomID`. |
| **Fan** | `fan_id` | Supported in the Service-page JS, but this unit shows no fan cards. |

The HTML uses `data-temp-id`, `data-paired-id` (= `entity_id`) and `data-port-id`. On this unit all three are equal
for each room, but that's only because each room was created with matching numbers. **Don't assume
`temp_id == entity_id` on another unit.** Read the pairing from `load-paired-entity-devices.php` or
`load-paired-entity-ports.php`.

**Temperatures:** °C, 0.5 °C steps in the UI (any decimal is accepted). The dashboard gauge runs −10…50.
**Observed:** the target that gets logged (Logs, Smart Heating charts) is the **setpoint + 0.2 °C** (set 23 → logged
23.2, 23.5 → 23.7, 22 → 22.2). The cloud's `getRoomLog` targets probably include the same offset.

**Relay logic is inverted:** `0` = relay energised (ON / heating), `1` = off. See §6.2.

---

## 5. Pages

All pages share a navbar (Dashboard, Smart Heating, Schedule, Logs, Security, Setup, logout) and a footer with
"System time snapshot" (`get-system-time.php`, read once), "System temperature" (the Pi's CPU temperature,
`get-system-temp.php` every 5 s) and the S/N.

| Path | Title | Content |
|---|---|---|
| `/` (no session) | `Dashboard \| TermoTehnik PI` | Login: device dropdown (`#device`, the sonar list; this unit is `value="127.0.1.1"` labelled `TTO Advance-A1B2C3 (*)`), a refresh button (`/run-sonar.php`), 4 PIN boxes (`#loginForm`), and the firmware version and S/N under the form. |
| `/dashboard` | Dashboard | Header shows the controller IP and `HEATING MODE`/`COOLING MODE`. AccuWeather "Current Conditions" card. One card per room (§5.1). "Add TTO device" button (cloud slave pairing, §6.9). |
| `/weather` | Smart Heating | Weather card plus one nvd3 bar chart per room (§5.3). |
| `/scheduler` | Schedule | Holiday mode form and the weekly schedule form (§6.4, §6.5). |
| `/logs` | Logs | A table linking to `/logs/{entity_id}` for each room. |
| `/logs/{entity_id}` | Logs | Charts with the data embedded in the page (§5.2). Unknown ids still render a page. |
| `/security` | Security | Change PIN, last 10 logins (date, IP, "Service" column), users table, Add User. |
| `/setup` | Setup | All settings cards (§6.6–6.10). Shows the **API PIN** in `<h5 id="apiPin">NNNNNN</h5>`. |
| `/service` | Service | "Diagnostics": RK1–RK6 on/off test buttons with status, TS1–TS7 live gauges. |
| `/wizard` | Setup Wizard | Empty unless the wizard was started (`create-wizard-files.php`). |

### 5.1 Dashboard room card

Values rendered on the server:

```html
<div class="card ap-room-temp-setup pi-temp" data-temp-id="3" data-paired-id="3" data-port-id="3">
  <span class="room-name">Spavaca soba</span>
  <input class="temp-input" value="23" disabled>            <!-- current setpoint, empty when OFF -->
  <strong class="temp-input-display">23 ̊C</strong>          <!-- "-" when off, "automatic" when smart -->
  ... buttons: temp-minus / temp-plus (±0.5), SET, SET FOR ALL ROOMS, SMART HEATING, CONFIGURE, OFF
  <a href="/logs/3"><span class="ports-jumped"></span></a>  <!-- filled from load-paired-entity-ports -->
</div>
<script> heating_cooling_rooms[3] = 'heating'; </script>    <!-- room's effective mode -->
```

`var heating_cooling_global = 'heating'|'cooling'` holds the system mode.

What the page does while open:

- On `window.load`: `POST enable-all-t-sensors.php` (≈10 s, wakes every sensor port).
- Right away and then every **23 s**, for each room: `run-command-test-temp.php {temp_id}` (gauge) and
  `run-command-read-room-status.php {entity_id}` (flame icon `heating-on.png`/`heating-off.png`,
  or `cooling-*`).
- Right away and then every **25 s**: `run-command-test-hum.php {temp_id}` for each room, one second apart.
  `0`/`0.0` shows "- not connected -".
- An empty temperature reading shows "- temperature sensor error -", and SET is refused on the client
  ("Temperature sensor for X not functional").
- "SET FOR ALL ROOMS" sends one `save-paired-entity-temp.php` per room. The server has no bulk endpoint.

### 5.2 `/logs/{entity_id}`: embedded chart data

nvd3 `lineChart` series are written into the page as JS literals, as `[epochMillis, value]` pairs. Times are real UTC
epoch ms (`1795437900000` = 2026-11-23 12:45:00Z, read at 12:45 UTC). The page has no JSON endpoint for the 24 h
data. You have to scrape it from the page.

| Chart `#id` | Series `key` | Span / step | Notes |
|---|---|---|---|
| `#chart` | `Actual Temperature`, `Target Temperature` | 24 h, **5 min** (≈298 points) | Missed samples just leave a gap (e.g. an 11-minute step). |
| `#chart1h` | same | 1 h, **1 min** (60 points) | Refreshed with `get-device-live-log.php` every 30 s (the label says 60 s). |
| `#chartHum` | `Humidity (%RH)` | 24 h, **10 min** | All 0 when the sensor has no humidity. |

Script excerpt to match on:

```js
"key": "Actual Temperature",
"values": [
[1795347840000, 24.25],[1795348140000, 24.187], ...
```

### 5.3 `/weather` (Smart Heating) per-room stats

For each room: `<h2>Room name</h2>`, an nvd3 `multiBarChart` in `#bar-graph-{entity_id}` with series
`Room Temperature`, `Target Temperature`, `Outside Temperature` (x = `"14h"…"12h","NOW"`, the last 24 hours) and
`Predicted Outside Temp.`, `Predicted Room Temp.`, `Smart Target Temp.` (x = `"p14h"…"p00h"`, from now until
midnight). Below that, 12 stat tiles: MAX./MIN. Room, Room Max. DROP/h, RISE/h, Max./Min. Outside, Outside DROP/h,
RISE/h, Diff. MAX./MIN., Inside vs. outside DROP/h, RISE/h.

On this unit, outside and predicted values are always 0, Max./Min. Outside show the sentinel values `-20`/`60`, and
the ratios show `inf%`. No TS7 sensor is fitted and the AccuWeather integration is dead (see §6.8). The cloud's
`getSmartRoomLogsLast24h` serves the same data as JSON, which is why OnlyHeat drops the flat "predicted" line.

---

## 6. Endpoint reference

All are `POST` with `application/x-www-form-urlencoded` unless noted. "Verified" means called during this capture,
and the response shown is real. Everything else comes from reading the dashboard's JS. **Writes were not executed**
except one same-value setpoint save.

### 6.1 Status and info (read-only, verified)

| Endpoint | Params | Response |
|---|---|---|
| `/include/ajax/get-system-time.php` | – | `{"systime":"13:45:00"}` (local time, HH:mm:ss) |
| `/include/ajax/get-system-temp.php` | – | `{"systemTemp":"47"}` (Pi CPU °C, string) |
| `/include/ajax/check-wifi-status.php` | – | `{"wifiStatus":"on","wifiSSID":"r1m - Heat","wifiGATEWAY":"192.168.42.1"}`: the **built-in AP**, not the dongle. Discover.kt uses `wifiSSID` to name controllers. |
| `/include/ajax/device-status.php` | – | `status` (text/html). Liveness probe used after a reboot. |
| `/include/ajax/update-tto-check.php` | – | `{"type":"warning","message":"There are no updates for your device"}`. With an update: `type:"success"`, `update:<version>`. |
| `/include/ajax/scan-wifi-networks.php` | – | ≈3.4 s. `[{"channel":"6","quality":"65/70","signal":"-45","encription":"on"\|"off","essid":"..."}, ...]`. Hidden networks: `essid` starts with `\x00`. An adapter failure: `essid` starts with `ERROR`. |
| `/include/ajax/accu-api-loc-search.php` (**GET**) | `q` | Returns `null` on this unit (dead AccuWeather key). select2 expects `[{id,text}]`. |
| `/include/ajax/get-table-users.php` (**GET**) | – | HTML `<table>` (Username, Permissions, PIN, API PIN, Last Login). **Needs a session**: empty body without one. |

### 6.2 Hardware: sensors and relays (`/run-command-*.php`, text/plain-ish)

| Endpoint | Params | Response (verified unless marked) |
|---|---|---|
| `/run-command-test-temp.php` | `temp_id` 1–7 | `24.3` (°C rounded to 0.1). **Empty string** = no sensor or read error. Takes **1–2 s** per sensor (1-wire read), 0.2 s when nothing is there. |
| `/run-command-test-hum.php` | `temp_id` | `0.0` = no humidity sensor (all sensors on this unit). Otherwise %RH. |
| `/run-command-read-room-status.php` | `entity_id` | `0` = room's valve **open / heating**, `1` = closed. |
| `/run-command-read-status.php` | `port_id` 1–6 | `1\n` = relay off, `0` = on, empty = unknown. |
| `/run-command-read-fan-status.php` | `fan_id` | Same encoding (`1\n`). |
| `/run-command-test-on.php` ⚠ | `port_id` | Forces relay ON (service test). Same 0/1 reply. *Not executed.* |
| `/run-command-test-off.php` ⚠ | `port_id` | Forces relay OFF. *Not executed.* |
| `/run-command-test-fan-on.php` / `-fan-off.php` ⚠ | `fan_id` | *Not executed.* |

### 6.3 Rooms: setpoint, off, smart heating

| Endpoint | Params | Notes |
|---|---|---|
| `/include/ajax/save-paired-entity-temp.php` | `entity_id`, `desiredTemp` (e.g. `23`, `23.5`) | **Verified:** `{"type":"success","message":""}`. The dashboard's SET. Same as the cloud's `setRoomTemp`. |
| `/include/ajax/delete-paired-entity-temp.php` | `entity_id` | Turns the room **OFF** (deletes the setpoint; the card then shows `-`). Cloud equivalent: `setRoomTemp` with an empty `tempTarget`. Reply `{type,message}`. |
| `/include/ajax/enable-smart-heating.php` | `entityId`, `entityName`, `smartTemp` | Note the **camelCase `entityId`**. The UI then shows "automatic" and disables manual controls. Text: "target temperature and the AccuWeather prediction … with a +2/-2 degree threshold". |
| `/include/ajax/disable-smart-heating.php` | `entityId`, `entityName` | The reply also carries `desiredTemp`, which becomes the manual setpoint again (shown as `"-tto"` when empty). |
| `/include/ajax/load-paired-entity-ports.php` | – | **Verified:** `{"3":"RK3-TS3 <i class=\"fa fa-area-chart\" ...></i>","2":"RK2-TS2 …","1":"RK1-TS1 …"}`. Keyed by `entity_id`, values are HTML. Strip tags to get the `RKn-TSm` pairing. |
| `/include/ajax/get-device-live-log.php` | `entity_id` | **Verified:** `[{"key":"Actual Temperature","color":"#da5555","area":"true","values":[[1795434360000,"24.187"],...]},{"key":"Target Temperature","color":"#c60000","values":[...]}]`. Last 60 min at 1-min steps, values are **strings**. |

The smart heating modal (`/include/modals/modal-set-smart-heating.php?room_id=N`) is titled
`Enable smart heating for room "X"?`, with the default value 22. When smart heating is already on, it renders
`#DisableAutoRoomButton` instead.

### 6.4 Schedule (`/scheduler`, form `#formSchedule`)

There's no JSON read endpoint. **Read it by parsing the page** (`parseSchedule()` in `LocalApi.kt`).

Each row is a `.schedule-repater` block containing:

```html
<select name="entity_id[]"> <option value="0">- Room -</option> <option value="3" selected>Spavaca soba</option> …
<select name="scheduleDay[]"> <option value="0">Every Day</option> <option value="Mon">Monday</option> … Sun
<input name="scheduleStart[]" value="06:00">     <!-- HH:mm, picked with DateTimePicker -->
<input name="scheduleEnd[]"   value="23:00">
<input name="scheduleTarget[]" value="23">       <!-- ±0.5 buttons -->
```

With no schedule saved (this unit right now), the form holds **one hidden template row** (`style="display: none"`,
room `0`, empty times, target `22`). A saved schedule renders one visible row per entry with `selected` options and
`value=` filled in. `ScheduleTest.kt` has a captured page with 3 entries.

**Save = replace the whole schedule:**

```
POST /include/ajax/save-schedule.php
entity_id[]=3&scheduleDay[]=0&scheduleStart[]=06:00&scheduleEnd[]=23:00&scheduleTarget[]=23
&entity_id[]=2&scheduleDay[]=Mon&...                         (parallel arrays, one index per row)
```

- Reply: `{"type":"success"|"error","message":"..."}`.
- To clear: send the single blank row the form itself sends (`entity_id[]=0`, empty day/start/end/target). OnlyHeat
  does exactly this.
- The UI text says a schedule *"will override the Smart Heating/Cooling and default heating/cooling functions"*.
  The cloud reports a room under schedule control as `isSchedule`, and the controller then ignores manual setpoints.

### 6.5 Holiday mode (`/scheduler`, form `#formHoliday`)

Overrides everything ("These settings will override every other setting set throughout the system").

```html
<input name="holidayStart[]"  value="10/12/2022 00:00">   <!-- dd/MM/yyyy HH:mm, local time -->
<input name="holidayEnd[]"    value="25/12/2022 00:37">
<input name="holidayTarget[]" value="12.5">
```

`POST /include/ajax/save-holiday.php` with the whole form serialised (parallel arrays, several periods allowed) →
`{type,message}`. The only stored period on this unit is in the past. Removing the last row hides it and clears its
dates. It is not deleted from the form. **Not executed.**

### 6.6 Room configuration (Setup → Paired rooms, Dashboard → CONFIGURE)

| Endpoint | Params | Notes |
|---|---|---|
| `/include/ajax/load-paired-entity-devices.php` | – | **Verified.** HTML cards: `<span class="entityName">Name</span>`, `data-paired-id="N"`, `<strong>Port:</strong> RK3`, `<strong>Sensor:</strong> TS3`. The best source for the room ↔ port ↔ sensor map. |
| `/include/ajax/change-paired-entity-devices.php` | `entity_id`, `entityName` (≤100 chars, required), `entityNotes`, `roomInverter` = `X` (system default) \| `1` heating \| `0` cooling, `entityPort[]` (extra "jumper" ports, up to 5) | Renames a room, sets a per-room heating/cooling override, adds extra ports. |
| `/include/ajax/save-paired-entity-devices.php` | `entityName`, `entityNotes`, `entityTemp` (sensor id), `entityPort` (main port id) | Create a room. The UI wizard lights one sensor's LED at a time (`enable-only-t-sensor-single.php {tsensor}`) and asks "Has the LED light on the sensor turned on?". |
| `/include/ajax/delete-paired-entity-devices.php` | `id` (= entity_id) | Delete a room. **UI bug:** `app.js` binds `.deletePairedDevice`, but the rendered trash icon has class `deleteDeviceButton`, so the trash icon does nothing in this firmware. |
| `/include/ajax/select-paired-ports.php` | – | **Verified:** free ports `{"4":{"id":4,"name":"RK4"},…}` |
| `/include/ajax/select-paired-ports-all.php` | – | **Verified:** `{"1":{"id":1,"name":"RK1 (main on Kupaona)"},…,"6":{"id":6,"name":"RK6"}}` |
| `/include/ajax/select-paired-temperature-sensors.php` | – | **Verified:** free sensors `{"4":{"id":4,"name":"TS4"},…,"7":{"id":7,"name":"TS7 (External)"}}` |
| `/include/ajax/select-faulty-t-sensor.php` | – | **Verified:** `{"4":{"id":4,"name":"TS4 [no sensor]"},…}` |
| `/include/ajax/select-new-t-sensor.php` | – | **Verified:** `[{"id":0,"name":"- no new sensors detected -"}]` |

### 6.7 Temperature sensor discovery

| Endpoint | Params | Notes |
|---|---|---|
| `/include/ajax/enable-all-t-sensors.php` | – | **Verified**, ≈10 s: `{"type":"success","message":"All temperature sensors enabled... process finished!"}`. Runs on every dashboard load. Safe. |
| `/include/ajax/enable-only-t-sensor-single.php` | `tsensor` | Powers only that sensor port (LED identification). Call `enable-all-t-sensors` afterwards. |
| `/include/ajax/swap-t-sensors-automatic.php` ⚠ | `tsensor` 1–7 | "Discover sensors": run sequentially for TS1…TS7, then `enable-all-t-sensors`. Each reply's `message` is a console line. Re-associates 1-wire sensors with ports. |

### 6.8 System settings (Setup page)

| Card | Endpoint | Params |
|---|---|---|
| Heating/Cooling mode (system-wide) | `/include/ajax/change-inverter-settings.php` | `inverterValue` = `1` heating \| `0` cooling |
| System information | `/include/ajax/change-info-settings.php` | `heatingSystem` 0 Floor \| 1 Radiator \| 2 None; `coolingSystem` 0 None \| 1 Ceiling \| 2 Wall; `valveVoltage` 0 = 230 V \| 1 = 24 V; `pumpInstalled` 0 connected to TTO Advance \| 1 not connected; `pumpType` 0 Pump \| 1 Water heater \| 2 None; `SYSTEM_LANG` `en_GB` \| `de_DE` \| `fr_FR` |
| AccuWeather location | `/include/ajax/save-accu-loc.php` | `loc_id`, `loc_text`. Current: "Zagreb; Zagreb City; Croatia". The weather card shows an empty icon `00.svg` and blank temperatures, because the AccuWeather API no longer answers. |
| Date/Time (offline only) | `/include/ajax/change-date-time.php` | `datetimeField` = `dd/MM/yyyy HH:mm` |
| API PIN | `/include/ajax/generate-api-pin.php` ⚠ | – → `{type,message,APIpin}`. **Invalidates the PIN the cloud app logs in with.** |
| Change login PIN | `/include/ajax/save-pin.php` | `currentPin`, `newPin`, `newPinRepeat` (4 digits) |
| Add user | `/include/ajax/new-user.php` | `username`, `permissionPage[]` (dashboard, logs, service, setup, weather, scheduler), `loginPinNewUser` |
| Update | `update-tto-check.php` → `update-tto-download.php {dl_ver}` → `device-restart.php` | Not relevant any more: no updates will come. |
| Setup wizard ⚠ | `create-wizard-files.php` → `create-login-file.php` → `admin-encrypt.php`, then redirect to `/` | Restarts first-time setup. **The login file is regenerated.** |
| Reboot ⚠ | `/include/ajax/device-restart.php` | No useful reply (the Pi may go down first). The UI shows "Saving data…", waits **10 s**, then polls `device-status.php` (timeout 1.5 s) every **2 s** until it answers. OnlyHeat's `rebootAndWait()` does the same, and also watches for an address change and for the cloud coming back. |

The API PIN shown on `/setup` (6 digits, `#apiPin`) is the PIN for the cloud API
`https://api.smart-heating.co/api-remote/getKey`. OnlyHeat reads it from there (`parseApiPin()`), so users don't
have to type it.

### 6.9 Network settings

| Card | Endpoint | Params / notes |
|---|---|---|
| ADVANCE base WiFi (built-in AP) | `/include/ajax/change-wifi-settings.php` then `/include/ajax/change-wifi-settings-restart.php` | `wifiExt=on`, `wifiSSID`, `wifiPASS`. **While the dongle is connected to a home network, the modal refuses**: "Please disconnect TTO Advance from the WiFi network: "r1m" …" and recommends connecting through the AP ("r1m - Heat"). |
| LAN (Ethernet) | `/include/modals/modal-change-ip.php` | Same refusal while the dongle is connected. The form only renders when the dongle is disconnected, so its fields weren't captured. |
| USB WiFi dongle | `scan-wifi-networks.php`, `change-dongle-wifi-settings.php`, `change-dongle-wifi-settings-disconnect.php` | Connect: `wifiEssidInput`, `wifiPassInput`, optional static `wifiIpInput`, `wifiSubnetInput` (prefix length 0–32, default 24), `wifiGatewayInput`. Setup shows the current SSID, IP and mask. |
| TTO Network Sonar | `/include/ajax/change-sonar-settings.php` | `sonarType` = `master` \| `slave` \| `standalone`, `sonarName` (this unit: Master, "My Home"). |
| Sonar scan | `/run-sonar.php` | Rescans the LAN for other TTO devices and refreshes the login-page dropdown. |
| Pick device | `/include/ajax/set-device.php` | `device` = IP (`127.0.1.1` = self). Sets the `ttodevice` proxy cookie (§1). |
| Add remote (cloud) device | browser → `https://api.smart-heating.co/api-remote/getKey {pin}` → `/include/ajax/check-api-local.php {authKey, serial}` → `/include/ajax/save-api-local.php {authKey, serial}` | Pairs a "TTO Advance Slave" by its **6-digit API PIN** through the cloud. `getKey` also returns a **`serial`** field, which OnlyHeat doesn't read. `check-api-local` replies `{"success":"true"}` when the key is new. Added devices show up in `/include/templates/dashboard-added-devices.php` (empty here). |

### 6.10 Modals (`GET /include/modals/*.php`)

The pages load modals into `.modal-content` (`data-remote=`). Each is an HTML fragment with its own `<script>`.
Loading one only renders a form. All 21 are in `dashboard-snapshot-1.0.7.0.8/modals/`:
`modal-accu-location`, `-add-device`, `-change-ip`, `-change-sonar`, `-change-wifi`, `-dongle-wifi`, `-info`,
`-inverter`, `-new-room`, `-new-user`, `-reboot`, `-room-config?room_id=N`, `-set-date-time`,
`-set-smart-heating?room_id=N`, `-start-wizard`, `-temp-swap-auto`, `-update`.
The current settings come pre-filled (`checked` radios, `value=`), so modals are also the place to **read** settings
that have no endpoint: the room's `roomInverter`, notes and jumper ports (`modal-room-config`), the info settings
(`modal-info`), and sonar type/name (`modal-change-sonar`).

---

## 7. How this maps to the cloud API (`Api.kt`)

The cloud (`https://api.smart-heating.co/api-remote/*`, multipart POST, `{"status":"OK"|"ERROR"}`) relays to the
same controller. Most features exist on both sides:

| Feature | Local dashboard | Cloud |
|---|---|---|
| Room list + current/target temps | scrape `/dashboard` + `run-command-test-temp` per room | `getRooms` |
| Set temperature | `save-paired-entity-temp.php` | `setRoomTemp` |
| All rooms | N × `save-paired-entity-temp.php` | `setRoomTemp4All` |
| Room off | `delete-paired-entity-temp.php` | `setRoomTemp` with an empty target |
| Valve open now | `run-command-read-room-status.php` (`0` = open) | `isWorking` |
| Smart heating | `enable-/disable-smart-heating.php` | `getRoomSmart` / `setRoomSmart` |
| 1 h / 24 h history | `get-device-live-log.php` / scrape `/logs/N` | `getRoomLog` / `getSmartRoomLogsLast24h` |
| Schedule | scrape `/scheduler`, `save-schedule.php` | **none** (local only) |
| Holiday mode, reboot, API PIN, Wi-Fi, sensor discovery, settings | dashboard only | **none** |

---

## 8. Checklist after a firmware update

After the firmware update, check these before trusting any of the code:

1. Login: is it still `GET /dashboard` then `login-check.php` with `loginInput1..4`, and is the reply still
   `{"type":"success"}`? Does the title still match the `Dashboard | TermoTehnik PI` regex in `Discover.kt`?
2. Does `check-wifi-status.php` still answer without a session, with `wifiSSID`? Does the login page still show the
   device name as `TTO Advance-XXXXXX (*)` (used by `dashboardName()`)?
3. Is the `#formSchedule` markup and the parallel-array POST to `save-schedule.php` unchanged? Run `ScheduleTest`
   against a fresh capture.
4. Is the API PIN still in `id="apiPin"` on `/setup`?
5. Reboot: `device-restart.php` + `device-status.php`?
6. Is it still `192.168.42.1` on its own AP?
7. Diff the new pages against `dashboard-snapshot-1.0.7.0.8/` (`curl` each page after logging in, then `diff`).

## 9. Security notes (for your own awareness)

- Plain HTTP, a 4-digit PIN (default `0000`), and no lockout seen.
- Almost every AJAX endpoint, writes and relay tests included, works **without logging in** for anyone on the
  controller's AP or the home LAN.
- `/.htaccess` can be downloaded, and the `ttodevice` cookie makes the controller reverse-proxy to **any** host
  (an open proxy/SSRF into whichever networks it's on).
- The built-in AP is protected by its WPA password. The home LAN is protected only by your home Wi-Fi.

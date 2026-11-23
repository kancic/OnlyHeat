# TTO Advance cloud API: official app 1.0.3

Captured on 2026-11-23 from `https://api.smart-heating.co/api-remote`, against the same controller as the dashboard
docs (firmware 1.0.9.2.0, `TTO Advance-A1B2C3`, device name "My Home").

The cloud API has no version of its own, so these docs are named after the official app that defines what
it is expected to do: **TTO Advance 1.0.3** (`com.thermotechnik.ttoadvanceapp`, versionCode 13, Expo SDK 54,
React Native with Hermes bytecode v96). The endpoint list and parameters come from the app's decompiled
`index.android.bundle`, and every read endpoint was then called live. Replies are in
[`cloud-snapshot-1.0.3/`](cloud-snapshot-1.0.3/), with auth keys, PINs, serials and public IPs redacted. When this
document and the snapshot disagree, the snapshot is right.

OnlyHeat uses this API in `shared/src/commonMain/kotlin/ancic/karim/onlyheat/Api.kt`. The local dashboard is
documented separately in [`dashboard-api-1.0.7.0.8.md`](dashboard-api-1.0.7.0.8.md) and
[`dashboard-api-1.0.9.2.0.md`](dashboard-api-1.0.9.2.0.md). §7 of the first one maps dashboard features to these
endpoints.

---

## 1. Transport

```
POST https://api.smart-heating.co/api-remote/<endpoint>
Content-Type: multipart/form-data            (the app: axios with baseURL + FormData)
authKey=<32 hex>&lang=en&<params>
```

- **POST only.** A GET with a query string → `{"status":"ERROR","message":"Invalid API parameter"}`, which is the
  same reply an unknown endpoint gets.
- `application/x-www-form-urlencoded` works too (verified on `getRoomSmart`). The app sends multipart.
- HTTP status is always **200**. Check the body: `{"status":"OK", ...}` or
  `{"status":"ERROR","message":"...","dateCreated":"YYYY-MM-DD HH:MM:SS"}`.
- OK replies also carry `executionTime` (`"2.81 seconds"`), and some carry `localExecutionTime`. Calls that reach the
  controller take about **3 s**. The server relays them to the controller live, so they are slow and can come back
  empty or garbled right after a write. OnlyHeat retries 3 times.
- `lang` (`en`, `hr` …) is sent on every call except `getKey`. It made no difference on the endpoints tested.
  Leaving it out works as well. OnlyHeat always sends `en` so error texts stay comparable.
- Numbers come as strings (`"23.625"`), as JSON numbers (`7.8`), or as `false`/`null` for "none". `dateTime` is a
  string in `getRoomLog` but an int in `getSmartRoomLogsLast24h`. Parse leniently.

## 2. Authentication

```
POST /getKey   pin=<6-digit API PIN>
→ {"status":"OK","authKey":"<32 hex>","serial":"TTO-…", ...}
```

The 6-digit **API PIN** is shown on the dashboard's Setup page. OnlyHeat reads it from there (`LocalApi.apiPin()`),
and on 1.0.9.2.0 the dashboard can delete it. Every other call sends the key as `authKey`. It isn't known
whether keys expire on their own.

`getKey` wasn't called with the real PIN during the capture, because that would mint a new key. The reply shape
comes from `Api.kt` and the dashboard doc.

| Case | `message` (always `status: ERROR`) |
|---|---|
| Wrong PIN | `No device found for requested PIN` |
| `authKey` missing or empty | `Invalid authentication key` |
| `authKey` well-formed but unknown (e.g. after the API PIN was regenerated or deleted) | `No device found for requested authentication key` |
| Unknown endpoint, or GET | `Invalid API parameter` |
| Endpoint switched off server-side | `command disabled` |

**OnlyHeat impact (not fixed):** `AuthExpired` triggers only on `Invalid authentication key`. A revoked key
returns `No device found for requested authentication key` instead. OnlyHeat then shows that text as an ordinary
error and doesn't send the user back to login.

## 3. Endpoints

The 14 endpoints the app calls (`cloud-snapshot-1.0.3/endpoints.txt`). "Live" means it was called during the
capture. The three writes and `getKey` were only read from the app code.

| Endpoint | Params (besides `authKey`, `lang`) | Live | OnlyHeat |
|---|---|---|---|
| `getKey` | `pin` (no `authKey`) | wrong PIN only | `login()` |
| `getRooms` | `detailed` (`true`/`false`, same reply here) | ✓ | `home()` |
| `setRoomTemp` | `roomID`, `tempTarget` (empty = room off) | – | `setTemp()` |
| `setRoomTemp4All` | `tempTarget` | – | `setAll()` |
| `getRoomLog` | `roomID` | ✓ | `roomLog()` (history sync) |
| `getSmartRoomLogsLast24h` | – | ✓ | `last24h()` |
| `getRoomSmart` | `roomID` | ✓ | `smart()` |
| `setRoomSmart` | `roomID`, `isSmart` (`1`/`0`), `smartTarget` (only sent when `isSmart=1`) | – | `setSmart()` |
| `getSmartForecast` | – | ✓ | not used |
| `getDashboardForecast` | – | ✓ (disabled) | not used |
| `getTempSensors` | – | ✓ | not used |
| `getTSensorTemp` | `tempSensorID` | ✓ | not used |
| `getHSensorTemp` | `tempSensorID` | ✓ | not used |
| `getLastLogins` | – | ✓ | not used |

### 3.1 `getRooms`

```json
{"status":"OK","globalHeatingCooling":"heating","globalIpAddress":"192.168.42.1","globalDeviceName":"My Home",
 "rooms":[{"id":"3","title":"Spavaca soba","description":"","isHeating":"heating","isSmart":true,
   "isSchedule":false,"isHoliday":false,"isWorking":false,"port":"3","portName":"RK3","tSensor":"3",
   "tSensorName":"TS3","hSensor":"3","hSensorName":"H3","currentTemp":"23.625","currentHum":null,"targetTemp":"23"}, …]}
```

- `id` is the dashboard's `entity_id` and the `roomID` everywhere else.
- `isWorking` means the valve is open right now. `isHeating` is the mode (`heating`/`cooling`), not activity.
- `targetTemp` is empty or null when the room is off.
- `s7Temp` / `s7Hum` (the TS7 outdoor sensor) only appear when a TS7 is fitted. The app reads them, and so does
  OnlyHeat (`Home.outdoorTemp`). This unit has none, so they are missing.
- `globalIpAddress` is the controller's address on its own Wi-Fi.

### 3.2 `getRoomLog`

About 24 h of one room at roughly 5-minute steps (300 s, with gaps of 6–14 min), oldest first. There were 294
points here:

```json
{"status":"OK","minVal":15,"maxVal":25,"minHum":-5,"maxHum":5,
 "singleLogs":[{"dateTime":"1795360860","dateTimeGraph":"1795360860000","currentTemp":"23.812","targetTemp":"22.2","hum":"0"}, …]}
```

There is **no outside temperature** here. `minVal`/`maxVal`/`minHum`/`maxHum` are fixed chart bounds.

### 3.3 `getSmartRoomLogsLast24h`

One entry per room: 24 hourly readings (`dateTime` on the hour, int), then the controller's hourly prediction for
about the next 11 h, **including outside temperature**. On firmware 1.0.9.2.0 the outside values are AccuWeather's,
since this unit has no TS7. On 1.0.7.0.8 they were 0 and the prediction was flat.

```json
{"roomID":"3","roomName":"Spavaca soba","minVal":-2.1,"maxVal":25,"unit":"C",
 "maxRoom":"24.1","minRoom":"23.2","roomMaxDrop":"0.3","roomMaxRise":"0.4",
 "maxOutside":7.8,"minOutside":2.2,"outsideMaxDrop":2.2,"outsideMaxRise":3.9,"diffMax":16.3,"diffMin":21,
 "insideVsOutsideDrop":"13.6%","insideVsOutsideRise":"10.3%",
 "logs":[{"dateTime":1795366800,"currentTemp":"23.562","targetTemp":"23.2","outsideTemp":"5"}, …,
         {"dateTime":1795453200,"predictedCurrentTemp":"23.325","predictedTargetTemp":"23.2","predictedOutsideTemp":"3.2"}, …]}
```

The stat fields are the same 12 tiles as the dashboard's `/weather` page. `outsideTemp` and `predictedOutsideTemp` are
the same for every room.

**OnlyHeat impact:** `parseDay()` splits readings from predictions by `predictedCurrentTemp`, but it ignores
`outsideTemp`, `predictedOutsideTemp` and the stats.

### 3.4 `getRoomSmart` / `setRoomSmart`

```json
{"status":"OK","isSmart":true,"smartTarget":"23"}
{"status":"OK","isSmart":false,"smartTarget":false}
```

A missing or unknown `roomID` still returns `OK` with `isSmart:false`, so it can't be used to check that a room exists.
`setRoomSmart` sends `smartTarget` only when enabling, and OnlyHeat does the same.

### 3.5 `getSmartForecast`

The AccuWeather data the dashboard shows, available from anywhere:

```json
{"status":"OK","location":"Zagreb; Zagreb City; Croatia",
 "headline":"Rain from Wednesday afternoon until Thursday morning, when it will change to snow",
 "dasboardForecast":[
   {"id":"daily","title":"Current Conditions","headline":"Mostly clear",
    "image":"http://vortex.accuweather.com/adc2010/images/slate/icons/34",
    "temperature":2.8,"temperatureUnit":"C","realFeelTemperature":2.2,"realFeelTemperatureUnit":"C"},
   {"id":"today","title":"Forecast: Today","headline":"Partly sunny","image":"…/icons/04",
    "temperatureMax":7.8,"temperatureMaxUnit":"C","temperatureMin":-2.6,"temperatureMinUnit":"C",
    "sunset":"16:18","sunrise":"07:06","wind":7.4,"windUnit":"km/h","windDirection":"SSE"},
   {"id":"tomorrow", …}, {"id":"Wednesday-25","title":"Wednesday 25.", …}, …]}
```

- `dasboardForecast` is the server's spelling, with no "h".
- There are 6 entries: current conditions, then today, tomorrow and three more days.
- `image` is an AccuWeather icon URL without an extension, over plain HTTP. The number is AccuWeather's icon code.
- Before firmware 1.0.9.2.0 AccuWeather didn't work on this unit, so this was empty or zero.

**OnlyHeat impact:** OnlyHeat scrapes the same "Current Conditions" from the local `/dashboard`
(`LocalApi.weather()`), which only works on the home network. This endpoint would work from anywhere.

### 3.6 `getDashboardForecast`

`{"status":"ERROR","message":"command disabled"}`. The app still calls it, so it has probably been replaced by
`getSmartForecast`.

### 3.7 Sensors

```
getTempSensors           → {"tSensors":[{"id":1,"name":"TS1"}, … {"id":7,"name":"TS7 (External)"}]}   (all 7, fitted or not)
getTSensorTemp  id=1     → {"temp":"24.25"}
getHSensorTemp  id=1     → {"hum":null}                (no humidity sensors on this unit)
```

### 3.8 `getLastLogins`

The dashboard's login history (Security page). The 10 most recent logins:

```json
{"lastLogins":[{"dateCreated":1795450768,"loginIp":"192.168.100.35","serviceLogin":0,"serviceLoginText":""}, …]}
```

## 4. Not in the cloud API

Schedule, holiday mode, reboot, API PIN, Wi-Fi, sensor discovery and settings are only available on the local
dashboard. See the dashboard doc §7.

## 5. Client recipe

```
POST /getKey      pin                                  → authKey (store it)
POST /<endpoint>  authKey, lang=en, params             multipart or urlencoded, never GET
check body.status == "OK"; HTTP status is always 200
retry empty or non-JSON bodies (the relay hiccups after writes); never retry an ERROR reply
treat "Invalid authentication key" and "No device found for requested authentication key" as logged out
```

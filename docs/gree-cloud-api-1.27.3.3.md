# Gree cloud API: Gree+ 1.27.3.3

Static analysis of Android Gree+ (`com.gree.greeplus`, version 1.27.3.3, ARM64, Dart 3.8.1), pulled
on 2026-11-27. Blutter at `4a60ac648bf448c5a7596437243bcd0b9376fdf0` recovered annotated Dart assembly
and the object pool for snapshot `830f4f59e7969c70b595182826435c19`. The Android side is packed. JADX
recovered the loader stubs, but the handlers for
Gree+'s platform calls were unavailable. OnlyHeat currently implements the separate UDP/7000 LAN protocol
in [Gree.kt](../shared/src/commonMain/kotlin/ancic/karim/onlyheat/ac/Gree.kt).

**Result.** A published client implements two remote paths. Both start with the account HTTP API. The MQTT
path uses the account UID and token directly. The legacy path discovers the device's relay with
`/App/QueryOnline`. The installed APK's Dart references to `/sign` belong to BLE commissioning. The
exact wire settings below come from the published client where the APK hands off to Android.

**Evidence.** “Dart” means read in the installed app's decompiled code. “Reference client” means read in
the linked implementation. “Reported live” identifies a third-party observation on their equipment.
“Inferred” marks a connection between those sources. This investigation made no live cloud calls.

---

## 1. Account HTTP API

**Reference client.** All four account calls below use HTTPS POST to `https://<prefix>grih.gree.com`.
Choose the account's region. Prefixes are `eu`, `hk`, `na`, `sa`, `in`, `me`, `au`, `ru`, and
empty for mainland China. The client also has a diagnostic GET to
`https://globalgrih.gree.com/App/GetRegion`. It expects a JSON list with `id`, `name`, `enName`,
`disHost`, `apiHost` and `region`. It uses its own region table for requests.
[Region table and directory client][http]

```http
Content-Type: application/x-www-form-urlencoded
Gaen1: 5ac2bdf935bcca70
Charset: utf-8

<standard base64 of AES-128-ECB(PKCS7(UTF-8 JSON), HTTP_AES_KEY)>
```

The body is a bare base64 string. The HTTP reply is JSON with an `enRes` string. Base64-decode it,
decrypt with the same `HTTP_AES_KEY`, remove padding and parse the UTF-8 JSON. The request JSON
combines the endpoint fields with `api` and `datVc`. Use one timestamp for the entire request.
`MD5` below means lowercase hexadecimal MD5 of UTF-8 bytes. The literal `APP_HASH` and
`HTTP_AES_KEY` are redacted here. [Account client constants and helpers][luc10]

| Field | Derivation in the reference client |
|---|---|
| `api.appId` | String `4920681951525131286` |
| `api.r` | Integer Unix epoch seconds |
| `api.t` | The same instant formatted as UTC `yyyy-MM-dd HH:mm:ss` |
| `api.vc` | `MD5(appId + "_" + APP_HASH + "_" + t + "_" + decimal(r))` |
| `datVc` | `MD5(APP_HASH + "_" + join("_", signed_values))`. Use the endpoint's field order below. Stringify scalar values and join the MAC array's entries with underscores. |
| Login `psw` | `MD5(MD5(MD5(password) + password) + t)` |

| POST path | Endpoint fields in `datVc` order | Decrypted reply consumed by the reference client |
|---|---|---|
| `/App/UserLoginV2` | `user` (account name), `psw`, `t` (same as `api.t`) | `uid` and `token` at top level or under `data`. Checks `r == 200` when `r` is present. |
| `/App/GetHomes` | `token`, `uid` | `home[]`, with `id` and `name` |
| `/App/GetDevsInRoomsOfHomeV2` | `token`, `uid`, `homeId` (selected `home[].id`) | `rooms[].devs[]`, with `mac`, `key`, `name`. The client also reads `pmac` (parent module), `ver` (protocol/build string), `mid` (type), `model` and `online` when present. |
| `/App/QueryOnline` | `token`, `uid`, `macs` (JSON array, entries signed in array order) | `online[]` entries carry `mac`, `svr` (host and port) and `isSSL`. Omission from the array means offline to this client. |

Sources for the endpoint and field derivations. [Account and online lookup implementations][http]

**Dart.** `PlatformApi.getHomes` in `modules/message/platform_api.dart` (address `0x5e624c`)
passes `/App/GetHomes`, `method: "POST"`, `timeout: 22000` and `signType: "GRIH"` to
`Network.request`. The `data` map is a native signing template
`{"token":1,"uid":1,"datVc":["token","uid"]}`. The numeric values and list are placeholders at the
channel boundary. The wire token, UID and digest come from the native side. In
`platform/apis/net/iot1/platform_userdata.dart`, `UserData.getHomeList` has an empty argument map
and `UserData.getDeviceList` takes a numeric `homeId`. `DeviceBean.fromJson` in
`modules/common/bean/device_bean.dart` reads `mac`, `key`, `name`, `pmac`, `mid` and `ver`.
The installed build's use of the reference client's app ID, app hash, AES key and `Gaen1` header, and
its exact login and device-list wire endpoints, remain unconfirmed.

## 2. The regional `/sign` URLs

**Dart.** `Constants.getServerHost` in `modules/common/constant/Constants.dart` maps the localized
`GR_Europe` and `GR_Russia` labels to `https://biot-eu.gree.com/sign` and
`https://biot-ru.gree.com/sign`. The handler started by `_BleCommissioningLoginPage.clickLogin`
looks up the chosen region and falls back to the Europe URL. It passes that URL as `serverHost` to
`BleCommissioning.bleLogin` (address `0x56249c`, `platform/apis/net/iot1/platform_bleCommissioning.dart`).
The password reset, verification and password change pages reference this mapping on their BLE account
paths as well.

The native bridge receives `loginType: "0"`, `username`, `password`, `checkType: "2"` and
`serverHost`. The Dart login callback reads `code` and `data.token`, calls
`BleCommissioning.bleAccessToken` (`BleCommission.getBleAccessToken` with the token), then reads
`data.access_token` from the second bridge reply. Those are native bridge fields. The HTTP method,
headers, request fields, hash or HMAC, timestamp and raw reply for `/sign` are inside the packed
side and remain unknown. There is no evidence in these call sites of broker provisioning.
[MQTT login flow in the reference client][discovery]

## 3. MQTT connection and topics

**Reference client.** `GreeMqttClient.connect` uses these parameters. Exact settings in the Android
handler remain unknown. [MQTT connection and topics][mqtt]

| Setting | Reference client value |
|---|---|
| Host and port | `mqtt-<region>.gree.com:1984` using the account prefix from §1. Mainland China uses `mqtt.gree.com:1984`. |
| Transport | TLS, MQTT 3.1.1 |
| CONNECT username | Decimal string of the account `uid` |
| CONNECT password | The account login's `token` |
| CONNECT client ID | `app_` followed by 16 lowercase hex digits from a random 64-bit value |
| Keepalive / clean session | 60 seconds / true |
| Publish | `request/<module_mac>`, QoS 1 |
| Subscribe | `response/<module_mac>/#` for replies, `status/<module_mac>/#` for status and `connect/<module_mac>` for presence, all QoS 1 |

The topic uses `pmac` for a unit behind a module and `mac` for a standalone unit. The reference
also has a heuristic for older single-head records with no `pmac`. Topic names above contain no
account UID or home ID. **Dart.** `TopicUtil.getTopic` (`modules/common/util/topic_util.dart`,
`0x639290`) indexes localized feedback categories via `FeedBackDetailBean.questions`. Its callers
are the feedback history and detail pages.

**TLS trust.** The base APK retains Eclipse Paho service metadata. Its `bttets/` entries named
`_._.gree.com.crt`, `_.as.gree.com.cer` and `_.gree.com.cer` are zero bytes. `res/raw/` had
audio and Android keep rules. No usable broker trust material or per-install client certificate was
identified in the inspected files. The reference client disables server certificate verification
and supplies no client certificate. That leaves the broker unauthenticated, so OnlyHeat needs a
verified trust path before sending a real account token. Android CA or pin selection is unresolved.
[Reference client's TLS context][mqtt]

## 4. Device messages

**Reference client.** Send JSON to the MQTT request topic in §3. For a standalone unit, `module_mac`
and `tcid` are both the device's `mac`. [Envelope builder][mqtt]

```text
{"t":"pack","i":0,"uid":<account uid>,"cid":"<random decimal string>","tcid":"<device mac>","pack":"<base64 ciphertext>"}
```

| Field | Meaning or derivation |
|---|---|
| `t`, `i` | Literal `"pack"`, integer `0` for a request using the device key |
| `uid` | UID returned by account login, carried through with its type. Only the CONNECT username is explicitly stringified. |
| `cid` | Random decimal string from the inclusive range 1000000000–9999999999, generated per publish. Separate from the MQTT client ID. |
| `tcid` | Addressed device. For a child unit the reference supports either the child here or the module here with the child in the encrypted payload's `mac`. |
| `pack` | Standard base64 of encrypted UTF-8 JSON, using `key` from the device record |
| `tag` | Present for GCM sends, base64 of the authentication tag |

Examples of inner messages, with synthetic values. Status maps the reply's `cols` names to the
same-index values in `dat`. The reference also handles unsolicited status and devices that omit a
command acknowledgement. On shared module topics, identify the addressed child before accepting
an update. The MQTT reader accepts `cid`, `i`, `pack`, `t`, `tcid`, `uid` and optional `tag`,
`ts` and `extras` on the outer message. The legacy status request also includes `mac` in the
inner JSON. [MQTT reader][mqtt] · [Device request and reply handlers][device]

```text
status  {"t":"status","cols":["Pow","Mod","SetTem","WdSpd"]}
reply   {"t":"dat","cols":["Pow","Mod","SetTem","WdSpd"],"dat":[0,1,24,0]}
cmd     {"t":"cmd","opt":["Pow","Mod","SetTem","WdSpd"],"p":[1,1,24,0]}
ack     {"t":"res","r":200,"opt":["Pow","Mod","SetTem","WdSpd"],"p":[1,1,24,0]}
```

The Home Assistant integration selects `CipherV1` (AES-128-ECB with PKCS7 padding). The library
also exposes `CipherV2` for MQTT (AES-128-GCM using the fixed 12-byte nonce
`54 40 78 44 49 67 5a 51 6c 5e 63 13` and ASCII AAD `qualcomm-test`, with a separate `tag`).
Its receive path parses the tag but the decrypt routine
does not authenticate it. The official app's MQTT cipher selection, reply tag semantics, exact
`cid` format and any request/reply correlation are open. The APK has `libGcmLib.so` with JNI
encryption/decryption exports, which establishes that GCM code ships in the package.
[Cipher implementations][cipher] · [Integration cipher selection][coordinator]

## 5. Capability check and older modules

**Dart.** `SceneUtil.isSupportMqtt` (`modules/schedule/util/scene_util.dart`, `0x66adb4`) returns
false for version strings shorter than two characters. It tests for `M`, takes the substring after the
last `V` and before the last dot, parses that as a double and checks `>= 3.2`. The
`M` test is a dynamic call, inferred to be `contains` from the same dispatch slot in
`StringUtils.getHideEmail`. Missing delimiters and parse failures return false.
`_DeviceTriggerConditionDialogState._isDeviceSupport` reads `DeviceBean.ver` and calls this
only for `mid` values `10001`, `10002`, `10007`, `10f02`, `11005` and `11f06`.
This verifies a scene-trigger check. The native transport-selection algorithm remains unknown.

**Reported live / reference client.** A V2.0.0 module first contacted the regional dispatcher on
1813, then kept a TLS connection to a device gateway on 16387. The reporter read device status
from that gateway using their account. For older devices the reference starts with the gateway,
using a parsed version tuple below `(3, 2)` as a heuristic. It can try the other transport
when the first fails. [Legacy status report][gateway-report] · [Gateway implementation][gateway]

1. POST `/App/QueryOnline` (§1) with the target MAC and read its `online[]` entry.
2. Open TCP to its `svr`, with TLS if `isSSL` is true. The observed `16387` is an example.
3. Send a single newline-terminated JSON envelope. It uses `t: "pack"`, `i: 0`, `cid: "app"`,
   `tcid` as the destination MAC, the account `uid`, and `pack` encrypted with the device
   key using AES-128-ECB/PKCS7. The reported unit used ECB on this path even though its LAN
   traffic used GCM.
4. Read one line, parse the outer JSON, decrypt `pack` if present, and close. Successful
   status returns inner `t: "dat"` with parallel `cols` and `dat` arrays. The reported reply
   also carried the device `mac` and `r: 200`.

**Module discovery.** On firmware V1.45, EWPE Keepalive reports a module opening TLS 1.2 to
`<region>dis.gree.com:1813` (for example `eu.dis.gree.com`) and sending
`{"t":"dis","mac":"<module mac>","p":1,"needMqtt":1}`. The packed `svr` reply uses
`i: 1` and base64 AES-128-ECB with the generic Gree key. The inner reply names the
next server, with an MQTT broker in `mqttInfo` when offered. The module rejected the
test broker's self-signed certificate. Older device-side implementations report TCP/1812.
The phone-side legacy client uses the per-device host and port from `QueryOnline`.
[EWPE Keepalive module observations][keepalive] · [1812 implementation][legacy-dis]

## 6. Android channel boundary

**Dart.** `PlatformBase.invokeMethodAsync` in `platform/platform_base.dart` calls
`MethodChannel("com.greeplus.flutter").invokeMethod("", jsonEncode(request))`. The channel
uses `StandardMethodCodec` (`pp.txt` at `0x8810`). The JSON uses
`{"clsName":"…","funName":"…","funData":{…}}`.

| Dart source under `package:plugin_flutter/` | `clsName.funName` | `funData` |
|---|---|---|
| `modules/message/platform_api.dart` · `PlatformApi.getHomes` | `Network.request` | `url`, `data`, `timeout`, `method`, `signType` (§1) |
| `platform/apis/net/iot1/platform_userdata.dart` · `UserData.getHomeList` / `getDeviceList` | `UserData.getHomeList` / `UserData.getDeviceList` | Empty map / `homeId` |
| `platform/apis/net/iot1/platform_bleCommissioning.dart` · `BleCommissioning.bleLogin` / `bleAccessToken` | `BleCommission.bleLogin` / `BleCommission.getBleAccessToken` | Login fields in §2 / `token` |
| `modules/message/platform_api.dart` · `PlatformApi.queryDevState` | `DeviceData.queryDevState` | `mac`, `pMac` |
| `platform/apis/net/iot1/platform_devicedata.dart` · `DeviceData.getPowerOffCmd` | `DeviceData.getPowerOffCmd` | `mid`, `pMac`, `mac` |
| `platform/apis/net/iot1/platform_system.dart` · `System.turnOnOrOffGroupSwitch` | `System.turnOnOrOffGroupSwitch` | `groupId`, `opt` |

`queryDevState` is a bridge call from the message UI. The data on the wire, the single-AC
control handler and the Paho options could not be recovered from the packed Android side.
ADB reported the shell user with no `su` on its path. No runtime dex dump was attempted.

## 7. Client recipe

```text
uid, token = account_post(region, "/App/UserLoginV2", login_fields)
homes = account_post(region, "/App/GetHomes", uid, token)
device = select(account_post(region, "/App/GetDevsInRoomsOfHomeV2", uid, token, homeId))
module = device.pmac or device.mac
inner = {"t":"status", "cols":["Pow","Mod","SetTem","WdSpd"]}

if module_uses_mqtt(device.ver):
    mqtt = connect_verified_tls(regional_broker(region), 1984, uid_as_string, token)
    mqtt.subscribe("response/" + module + "/#", "status/" + module + "/#")
    mqtt.publish("request/" + module, pack(inner, device.key, uid, device.mac))
    reply = decrypt(matching_reply(), device.key)
else:
    relay = account_post(region, "/App/QueryOnline", uid, token, macs=[device.mac])
    reply = exchange_one_line(relay, ecb_pack(inner + {"mac":device.mac}, device.key, uid))

return zip(reply.cols, reply.dat)
```

This is the published client's flow for a status read. A write uses the same path with the
`cmd` payload in §4. The `biot-…/sign` URL appears in the separate BLE flow in §2.

## 8. Open questions

- Which trust anchor or pin does Gree+ use for the broker and the legacy relay? Can OnlyHeat verify
  both with vendor server trust material?
- Which broker settings, cipher rules, topic filters and reply correlation does Android Gree+
  1.27.3.3 itself use? Which path and cipher work for the user's units?
- Does the installed build still use all the reference client's HTTP signing constants and device
  listing path? What method and payload does the BLE native handler use for `/sign`?

[luc10]: https://github.com/luc10/gree-api-client/blob/main/api_client.py
[http]: https://github.com/davo22/greeclimate/blob/91e6b951d248457ba3f019835951ed39093095aa/greeclimate_davo22/cloud_api.py
[discovery]: https://github.com/davo22/greeclimate/blob/91e6b951d248457ba3f019835951ed39093095aa/greeclimate_davo22/cloud_discovery.py#L91
[mqtt]: https://github.com/davo22/greeclimate/blob/91e6b951d248457ba3f019835951ed39093095aa/greeclimate_davo22/mqtt_client.py
[device]: https://github.com/davo22/greeclimate/blob/91e6b951d248457ba3f019835951ed39093095aa/greeclimate_davo22/cloud_device.py
[cipher]: https://github.com/davo22/greeclimate/blob/91e6b951d248457ba3f019835951ed39093095aa/greeclimate_davo22/cipher.py
[coordinator]: https://github.com/davo22/homeassistant-gree-cloud/blob/e290863c1dbaf2ae870e188c09a4c1ca4e93b753/custom_components/gree_cloud/coordinator.py#L896
[gateway-report]: https://github.com/davo22/homeassistant-gree-cloud/issues/21
[gateway]: https://github.com/davo22/greeclimate/blob/91e6b951d248457ba3f019835951ed39093095aa/greeclimate_davo22/cloud_gateway.py
[keepalive]: https://github.com/mysteriousdev777/ha-ewpe-keepalive#how-it-works
[legacy-dis]: https://github.com/rowra44/Fake-Gree-server

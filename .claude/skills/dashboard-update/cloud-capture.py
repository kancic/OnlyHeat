#!/usr/bin/env python3
"""Capture the TTO Advance cloud API (https://api.smart-heating.co/api-remote) into a snapshot folder. Read-only.

Usage: python3 -I cloud-capture.py OUT_DIR [AUTH_KEY]
  AUTH_KEY defaults to the desktop app's saved `authKey` (macOS Java prefs).

Writes OUT_DIR/<endpoint>.json (pretty-printed reply of every read endpoint), probe.txt (how the server answers
bad keys, missing params, other languages and GET instead of POST) and endpoints.txt (filled in by hand from the
official app; see the skill). Auth keys, PINs, serials and public IP addresses are redacted.
It never calls setRoomTemp, setRoomTemp4All, setRoomSmart or getKey.
"""
import json, os, re, subprocess, sys, urllib.parse, urllib.request, uuid

out = sys.argv[1]
key = sys.argv[2] if len(sys.argv) > 2 else None
if key is None:
    prefs = subprocess.run(["defaults", "read", "com.apple.java.util.prefs"], capture_output=True, text=True).stdout
    m = re.search(r'authKey = "?(\w+)"?;', prefs)
    key = m.group(1) if m else sys.exit("No auth key given and no saved authKey; pass it as the 2nd argument")

BASE = "https://api.smart-heating.co/api-remote"


def call(path, params, method="POST", multipart=True):
    if method == "GET":
        req = urllib.request.Request(BASE + path + "?" + urllib.parse.urlencode(params))
    elif multipart:  # what the official app (axios + FormData) sends
        b = uuid.uuid4().hex
        body = "".join(f'--{b}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n' for k, v in params.items())
        req = urllib.request.Request(BASE + path, (body + f"--{b}--\r\n").encode(),
                                     {"Content-Type": f"multipart/form-data; boundary={b}"})
    else:
        req = urllib.request.Request(BASE + path, urllib.parse.urlencode(params).encode())
    try:
        r = urllib.request.urlopen(req, timeout=30)
        return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:
        return "ERR", repr(e)


def redact(text):
    text = text.replace(key, "REDACTED_KEY")
    text = re.sub(r'("(?:authKey|serial|pin|apiPin)"\s*:\s*)"[^"]*"', r'\1"REDACTED"', text)
    # public IPv4 (keep 192.168/10./172.16-31 and 127.)
    return re.sub(r"\b(?!(?:10|127)\.)(?!192\.168\.)(?!172\.(?:1[6-9]|2\d|3[01])\.)(\d{1,3}\.){3}\d{1,3}\b", "x.x.x.x", text)


def save(name, text):
    os.makedirs(out, exist_ok=True)
    open(os.path.join(out, name), "w").write(redact(text))


def pretty(body):
    try:
        return json.dumps(json.loads(body), indent=2, ensure_ascii=False) + "\n"
    except ValueError:
        return body


auth = {"authKey": key, "lang": "en"}
status, rooms = call("/getRooms", {**auth, "detailed": "false"})
save("getRooms.json", pretty(rooms))
room = (json.loads(rooms).get("rooms") or [{}])[0].get("id", "1")

reads = {
    "getRooms.detailed=true": ("/getRooms", {**auth, "detailed": "true"}),
    "getRoomLog": ("/getRoomLog", {**auth, "roomID": room}),
    "getRoomSmart": ("/getRoomSmart", {**auth, "roomID": room}),
    "getSmartRoomLogsLast24h": ("/getSmartRoomLogsLast24h", auth),
    "getSmartForecast": ("/getSmartForecast", auth),
    "getDashboardForecast": ("/getDashboardForecast", auth),
    "getTempSensors": ("/getTempSensors", auth),
    "getTSensorTemp": ("/getTSensorTemp", {**auth, "tempSensorID": "1"}),
    "getHSensorTemp": ("/getHSensorTemp", {**auth, "tempSensorID": "1"}),
    "getLastLogins": ("/getLastLogins", auth),
}
for name, (path, params) in reads.items():
    save(f"{name}.json", pretty(call(path, params)[1]))

# How the server reacts to variations. Read endpoints only.
probes = [
    ("bad key", "/getRooms", {"authKey": "0" * 32, "lang": "en", "detailed": "false"}, "POST", True),
    ("no key", "/getRooms", {"lang": "en"}, "POST", True),
    ("no lang", "/getRoomSmart", {"authKey": key, "roomID": room}, "POST", True),
    ("lang=hr", "/getRoomSmart", {"authKey": key, "roomID": room, "lang": "hr"}, "POST", True),
    ("lang=hr bad key", "/getRooms", {"authKey": "0" * 32, "lang": "hr"}, "POST", True),
    ("no roomID", "/getRoomSmart", auth, "POST", True),
    ("unknown roomID", "/getRoomSmart", {**auth, "roomID": "999"}, "POST", True),
    ("urlencoded", "/getRoomSmart", {**auth, "roomID": room}, "POST", False),
    ("GET", "/getRoomSmart", {**auth, "roomID": room}, "GET", True),
    ("unknown endpoint", "/getNothing", auth, "POST", True),
    ("getKey wrong pin", "/getKey", {"pin": "000000"}, "POST", True),  # a wrong PIN mints nothing
]
lines = []
for label, path, params, method, multipart in probes:
    status, body = call(path, params, method, multipart)
    lines.append(f"=== {label}: {method} {path} {sorted(params)}\n  {status} {body.strip()[:300]}")
save("probe.txt", "\n".join(lines) + "\n")
print(f"saved {len(reads) + 1} replies and {len(probes)} probes to {out}")

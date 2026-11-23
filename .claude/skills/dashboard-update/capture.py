#!/usr/bin/env python3
"""Capture the TTO Advance local dashboard into a snapshot folder. Read-only.

Usage: python3 -I capture.py OUT_DIR [HOST] [PIN]
  HOST defaults to 192.168.42.1 (the controller's own Wi-Fi), PIN to the desktop app's saved `localPin`.

Writes OUT_DIR/{pages,modals}/*.html, app.js, htaccess.txt, version.txt and probe.txt (how each read-only
endpoint answers with session+XHR header, session only, and anonymously). The API PIN is redacted.
It only GETs pages/modals and POSTs to endpoints that read; it never saves, reboots or switches relays.
"""
import http.cookiejar, os, re, subprocess, sys, urllib.parse, urllib.request

out = sys.argv[1]
host = sys.argv[2] if len(sys.argv) > 2 else "192.168.42.1"
pin = sys.argv[3] if len(sys.argv) > 3 else None
if pin is None:  # the desktop app stores it in Java prefs on macOS
    prefs = subprocess.run(["defaults", "read", "com.apple.java.util.prefs"], capture_output=True, text=True).stdout
    m = re.search(r'localPin = "?(\d{4})"?;', prefs)
    pin = m.group(1) if m else sys.exit("No PIN given and no saved localPin; pass it as the 3rd argument")

B = f"http://{host}"
XHR = {"X-Requested-With": "XMLHttpRequest"}


def opener():
    return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))


def call(op, path, data=None, method="POST", headers=None):
    body = urllib.parse.urlencode(data or {}, doseq=True).encode() if method == "POST" else None
    url = B + path + ("?" + urllib.parse.urlencode(data) if method == "GET" and data else "")
    try:
        r = op.open(urllib.request.Request(url, data=body, method=method, headers=headers or {}), timeout=40)
        return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:
        return "ERR", repr(e)


def save(rel, text):
    path = os.path.join(out, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    text = re.sub(r'(id="apiPin"\s*>\s*)\w+', r"\1REDACTED", text)
    open(path, "w").write(text)


auth, anon = opener(), opener()
_, login_page = call(auth, "/", method="GET")
save("pages/login.html", login_page)
version = re.search(r"TTO Advance ([\d.]+)", login_page)
version = version.group(1) if version else "unknown"
save("version.txt", version + "\n")
print("firmware", version)

_, reply = call(auth, "/include/ajax/login-check.php", {f"loginInput{i + 1}": d for i, d in enumerate(pin)}, headers=XHR)
print("login", reply)
if '"success"' not in reply:
    sys.exit("login failed")

# Pages: the nav links plus /logs/N, /service and /wizard (not in the nav).
_, dash = call(auth, "/dashboard", method="GET", headers=XHR)
paths = sorted(set(re.findall(r'href="(/[a-z0-9/]+)"', dash)) | {"/dashboard", "/service", "/wizard", "/logs"})
pages = {}
for p in paths:
    if p == "/":
        continue
    _, html = call(auth, p, method="GET", headers=XHR)
    pages[p] = html
    save(f"pages/{p.strip('/').replace('/', '_')}.html", html)

for rel, src in [("app.js", "/js/app.js"), ("htaccess.txt", "/.htaccess")]:
    save(rel, call(auth, src, method="GET")[1])

modals = sorted(set(m for html in pages.values() for m in re.findall(r'data-remote="([^"]+)"', html)))
for m in modals:
    name = re.sub(r"\.php(\?room_id=(\d+))?$", lambda g: f".room_id={g.group(2)}" if g.group(2) else "", os.path.basename(m))
    save(f"modals/{name}.html", call(auth, m, method="GET", headers=XHR)[1])

# Every endpoint the pages/modals reference, so new ones show up in the diff.
everything = "".join(pages.values()) + "".join(open(os.path.join(out, "modals", f)).read() for f in os.listdir(os.path.join(out, "modals")))
endpoints = sorted(set(re.findall(r"[\w/.-]*\.php", everything)))
save("endpoints.txt", "\n".join(endpoints) + "\n")

# Read-only probes: (method, path, params). Never add anything that writes here.
room = re.search(r'data-paired-id="(\d+)"', dash)
room = room.group(1) if room else "1"
probes = [
    ("POST", "/include/ajax/get-system-time.php", {}),
    ("POST", "/include/ajax/get-system-temp.php", {}),
    ("POST", "/include/ajax/check-wifi-status.php", {}),
    ("POST", "/include/ajax/device-status.php", {}),
    ("POST", "/include/ajax/load-paired-entity-ports.php", {}),
    ("POST", "/include/ajax/load-paired-entity-devices.php", {}),
    ("POST", "/include/ajax/select-paired-ports.php", {}),
    ("POST", "/include/ajax/select-paired-ports-all.php", {}),
    ("POST", "/include/ajax/select-paired-temperature-sensors.php", {}),
    ("POST", "/include/ajax/select-faulty-t-sensor.php", {}),
    ("POST", "/include/ajax/select-new-t-sensor.php", {}),
    ("GET", "/include/ajax/get-table-users.php", {}),
    ("GET", "/include/ajax/accu-api-loc-search.php", {"q": "Zagreb"}),
    ("POST", "/include/ajax/update-tto-check.php", {}),
    ("POST", "/include/ajax/get-device-live-log.php", {"entity_id": room}),
    ("POST", "/run-command-test-temp.php", {"temp_id": room}),
    ("POST", "/run-command-test-hum.php", {"temp_id": room}),
    ("POST", "/run-command-read-room-status.php", {"entity_id": room}),
    ("POST", "/run-command-read-status.php", {"port_id": "1"}),
    ("POST", "/include/templates/dashboard-added-devices.php", {}),
]
lines = []
for method, path, params in probes:
    res = {
        "session+xhr": call(auth, path, params, method, XHR),
        "session": call(auth, path, params, method),
        "anon+xhr": call(anon, path, params, method, XHR),
    }
    lines.append(f"=== {method} {path} {params}")
    for k, (status, body) in res.items():
        body = body.strip().replace("\n", " ")
        lines.append(f"  {k:12} {status} {body[:300]}")
save("probe.txt", "\n".join(lines) + "\n")
print(f"saved {len(pages)} pages, {len(modals)} modals, {len(endpoints)} endpoint names, {len(probes)} probes to {out}")

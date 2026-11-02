#!/usr/bin/env python3
"""DevxyzClone emulator stress-bench driver.

Runs on the GitHub Actions host inside the android-emulator-runner step.
Drives the REAL flow with adb + uiautomator only (no test APK):

  install DevxyzClone -> install target app -> clone it inside
  DevxyzClone -> launch the clone -> sign in with the assistant Gmail
  -> relay the emailed 6-digit code (polled from emu/code.txt in the
  repo, pushed by the operator mid-run) -> capture the outcome.

Every step screenshots + dumps the UI hierarchy into OUT_DIR and logs a
verdict line into OUT_DIR/report.txt. The script always exits 0; the
artifacts carry the verdict.
"""
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET

OUT = os.environ.get("OUT_DIR", "emu-out")
CLONE_APK = os.environ.get("CLONE_APK", "")
TARGET_APK = os.environ.get("TARGET_APK", "")
TARGET = os.environ.get("TEST_TARGET", "muse")
EMAIL = os.environ.get("LOGIN_EMAIL", "jepongspideymuse@gmail.com")
GH_REPO = os.environ.get("GH_REPO", "")
GH_TOKEN = os.environ.get("GH_TOKEN", "")
HOST_PKG = "com.jepongdevxyz.appclone"
TARGET_PKG = {"muse": "com.facebook.aura", "ml": "com.mobile.legends"}[TARGET]
TARGET_LABEL = {"muse": "Muse", "ml": "Mobile Legends"}[TARGET]

os.makedirs(OUT, exist_ok=True)
T0 = time.time()


def log(msg):
    line = "[%6.1fs] %s" % (time.time() - T0, msg)
    print(line, flush=True)
    with open(os.path.join(OUT, "report.txt"), "a") as f:
        f.write(line + "\n")


def adb(*args, timeout=120, binary=False):
    r = subprocess.run(["adb"] + list(args), capture_output=True,
                       timeout=timeout)
    if binary:
        return r.stdout
    return (r.stdout + r.stderr).decode("utf-8", "replace")


def shot(name):
    try:
        data = adb("exec-out", "screencap", "-p", timeout=30, binary=True)
        with open(os.path.join(OUT, name + ".png"), "wb") as f:
            f.write(data)
    except Exception as e:
        log("shot %s failed: %s" % (name, e))


def anr_present():
    try:
        w = adb("shell", "dumpsys", "window", "windows", timeout=30)
        return ("Not Responding" in w) or ("isn't responding" in w) \
            or ("AppNotResponding" in w)
    except Exception:
        return False


def dismiss_anr():
    """Tap the ANR dialog's 'Wait' row (full-width, ~57% down the screen)."""
    try:
        size = adb("shell", "wm", "size", timeout=20)
        m = re.search(r"(\d+)x(\d+)", size)
        w, h = (int(m.group(1)), int(m.group(2))) if m else (1080, 2400)
        adb("shell", "input", "tap", str(w // 2), str(int(h * 0.57)),
            timeout=20)
        log("ANR dialog: tapped Wait at %d,%d" % (w // 2, int(h * 0.57)))
        time.sleep(4)
    except Exception as e:
        log("dismiss_anr failed: %s" % e)


def dump(name=None):
    """Return list of node dicts from uiautomator (ANR-recovering)."""
    for attempt in range(4):
        try:
            out = adb("shell", "uiautomator", "dump", "--compressed",
                      "/sdcard/dump.xml", timeout=45)
            raw = adb("shell", "cat", "/sdcard/dump.xml", timeout=30)
            if raw.strip().startswith("<"):
                break
            log("dump attempt %d not XML: %r / %r"
                % (attempt, out.strip()[:120], raw.strip()[:120]))
            if anr_present():
                dismiss_anr()
            else:
                time.sleep(5)
        except Exception as e:
            log("dump attempt %d failed: %s" % (attempt, e))
            if anr_present():
                dismiss_anr()
            time.sleep(3)
    try:
        if name:
            with open(os.path.join(OUT, name + ".xml"), "w") as f:
                f.write(raw)
        root = ET.fromstring(raw)
        nodes = []
        for n in root.iter("node"):
            a = n.attrib
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", a.get("bounds", ""))
            cx = cy = -1
            if m:
                x1, y1, x2, y2 = (int(v) for v in m.groups())
                cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
            nodes.append({
                "text": a.get("text", ""),
                "desc": a.get("content-desc", ""),
                "id": a.get("resource-id", ""),
                "cls": a.get("class", ""),
                "clickable": a.get("clickable") == "true",
                "x": cx, "y": cy,
            })
        return nodes
    except Exception as e:
        log("dump failed: %s" % e)
        return []


def texts(nodes):
    return " | ".join(n["text"] for n in nodes if n["text"])


def find(nodes, substr, rid=None, cls=None):
    s = substr.lower()
    for n in nodes:
        if rid and rid not in n["id"]:
            continue
        if cls and cls not in n["cls"]:
            continue
        if s in n["text"].lower() or s in n["desc"].lower():
            if n["x"] >= 0:
                return n
    return None


def tap_node(n):
    adb("shell", "input", "tap", str(n["x"]), str(n["y"]), timeout=30)
    time.sleep(1.0)


def handle_system_dialogs(nodes):
    """Dismiss system permission dialogs (log access, runtime perms).

    Only fires when a Button labelled Allow* exists together with a
    Don't-allow counterpart, so in-app buttons are never hit.
    """
    deny = any("don" in n["text"].lower() and "allow" in n["text"].lower()
               for n in nodes)
    if not deny:
        return False
    for label in ("Allow one-time access", "Allow all the time", "Allow"):
        n = find(nodes, label)
        if n is not None and "Button" in n["cls"]:
            tap_node(n)
            log("system dialog: tapped %r" % label)
            return True
    return False


def wait_text(substr, timeout=60, rid=None):
    end = time.time() + timeout
    last = []
    while time.time() < end:
        last = dump()
        if handle_system_dialogs(last):
            time.sleep(2)
            continue
        if find(last, substr, rid=rid):
            return last
        time.sleep(3)
    return last


def tap_text(substr, timeout=30, rid=None):
    nodes = wait_text(substr, timeout, rid=rid)
    n = find(nodes, substr, rid=rid)
    if n:
        tap_node(n)
        return True
    log("tap_text NOT FOUND: %s" % substr)
    return False


def type_text(text):
    # input text cannot handle some chars; email is safe after %s escape
    adb("shell", "input", "text", text.replace(" ", "%s"), timeout=30)
    time.sleep(0.8)


def finalize(verdict):
    log("VERDICT: " + verdict)
    try:
        lc = adb("logcat", "-d", "-t", "4000", timeout=60)
        with open(os.path.join(OUT, "logcat.txt"), "w") as f:
            f.write(lc)
    except Exception as e:
        log("logcat failed: %s" % e)
    try:
        dl = adb("shell", "run-as", HOST_PKG, "cat",
                 "files/devxyzclone-pmdiag.log", timeout=30)
        with open(os.path.join(OUT, "diaglog.txt"), "w") as f:
            f.write(dl)
    except Exception as e:
        log("diaglog failed: %s" % e)
    shot("final")
    dump("final")
    sys.exit(0)


def main():
    log("target=%s pkg=%s email=%s" % (TARGET, TARGET_PKG, EMAIL))
    log("clone_apk=%s target_apk=%s" % (CLONE_APK, TARGET_APK))

    # The ARM-translated target install is CPU-bound (dexopt). Switch the
    # install compiler filter to quicken so installs finish in minutes,
    # and give the target install a long leash.
    try:
        adb("root", timeout=30)
        adb("wait-for-device", timeout=60)
        for prop in ("pm.dexopt.install", "pm.dexopt.bg-dexopt"):
            adb("shell", "setprop", prop, "quicken", timeout=20)
        log("dexopt install filter set to quicken")
    except Exception as e:
        log("pre-install setup skipped: %s" % e)

    r = adb("install", "-r", "-g", CLONE_APK, timeout=600)
    log("install clone: " + r.strip().splitlines()[-1] if r.strip() else "install clone: ?")
    r = adb("install", "-r", "-g", TARGET_APK, timeout=1800)
    log("install target: " + r.strip().splitlines()[-1] if r.strip() else "install target: ?")
    if "Success" not in r:
        shot("install_fail")
        finalize("TARGET_INSTALL_FAILED (see report)")

    # Let dexopt/background work drain before touching the UI.
    time.sleep(60)
    for _ in range(5):
        if anr_present():
            dismiss_anr()
        else:
            break

    # Launch DevxyzClone's launcher activity.
    launcher = adb("shell", "cmd", "package", "resolve-activity",
                   "--brief", "-c", "android.intent.category.LAUNCHER",
                   HOST_PKG, timeout=30)
    log("launcher: " + launcher.strip().replace("\n", " "))
    adb("shell", "am", "start", "-n",
        HOST_PKG + "/com.onebitmonochrome.blacksbbox.view.main.WelcomeActivity",
        timeout=60)
    # The engine's first init is heavy; let the system settle and clear
    # any 'System UI isn't responding' dialog before interacting.
    time.sleep(45)
    for _ in range(5):
        if anr_present():
            dismiss_anr()
        else:
            break
    shot("launched")
    nodes = dump("home")
    if handle_system_dialogs(nodes):
        time.sleep(3)
        nodes = dump("home")
    shot("home")
    log("home texts: " + texts(nodes)[:300])

    # FAB -> app picker.
    fab = None
    for n in nodes:
        if n["id"].endswith("id/fab"):
            fab = n
            break
    if fab is None:
        # Fallback: bottom-right area of the screen.
        adb("shell", "input", "tap", "970", "2230", timeout=30)
    else:
        tap_node(fab)
    time.sleep(3)
    nodes = wait_text(TARGET_LABEL, timeout=90)
    shot("picker")
    log("picker texts: " + texts(nodes)[:300])
    row = find(nodes, TARGET_LABEL)
    if row is None:
        finalize("PICKER_TARGET_NOT_FOUND")
    tap_node(row)

    # Clone installs; wait for the clone row on the home list.
    nodes = wait_text(TARGET_LABEL, timeout=900)
    shot("after_clone")
    log("after clone texts: " + texts(nodes)[:300])
    row = find(nodes, TARGET_LABEL)
    if row is None:
        finalize("CLONE_ROW_NOT_FOUND")

    # Launch the clone.
    tap_node(row)
    time.sleep(8)
    # Dismiss system permission dialogs / hints while the clone boots.
    for _ in range(10):
        nodes = dump()
        if find(nodes, "Allow"):
            tap_text("Allow", timeout=5)
        if find(nodes, "welcome") or find(nodes, "log in") or find(nodes, "sign in"):
            break
        time.sleep(5)
    shot("clone_open")
    nodes = dump("clone_open")
    log("clone open texts: " + texts(nodes)[:400])

    if TARGET != "muse":
        finalize("TARGET_OPENED (ml interactive drive not scripted in v1)")

    # --- Muse native email login ---
    nodes = wait_text("Welcome", timeout=120)
    shot("welcome")
    log("welcome texts: " + texts(nodes)[:400])
    edit = None
    for n in nodes:
        if "EditText" in n["cls"]:
            edit = n
            break
    if edit is None:
        finalize("EMAIL_FIELD_NOT_FOUND")
    tap_node(edit)
    type_text(EMAIL)
    shot("email_typed")
    if not tap_text("Continue", timeout=30):
        finalize("CONTINUE_NOT_FOUND")
    log("email submitted; waiting for code screen or error")

    # Watch for the code screen, an error screen, or a checkpoint.
    end = time.time() + 120
    state = ""
    while time.time() < end:
        nodes = dump()
        t = texts(nodes).lower()
        if "something went wrong" in t:
            state = "LOGIN_ERROR_SCREEN"
            break
        if "code" in t and ("enter" in t or "sent" in t or "check" in t):
            state = "CODE_SCREEN"
            break
        if "getting ready" in t:
            state = "GETTING_READY"
            break
        time.sleep(4)
    shot("post_email")
    dump("post_email")
    log("post-email state: " + (state or "UNKNOWN"))
    if state == "LOGIN_ERROR_SCREEN":
        finalize("MUSE_LOGIN_ERROR_AFTER_EMAIL")
    if state not in ("CODE_SCREEN", "GETTING_READY"):
        finalize("MUSE_NO_CODE_SCREEN state=%s" % (state or "UNKNOWN"))

    if state == "CODE_SCREEN":
        code = poll_code()
        if not code:
            finalize("CODE_SCREEN_REACHED_NO_CODE_RELAYED")
        log("relayed code received (len %d)" % len(code))
        nodes = dump()
        edits = [n for n in nodes if "EditText" in n["cls"] and n["x"] >= 0]
        if not edits:
            finalize("CODE_FIELD_NOT_FOUND")
        if len(edits) == 1:
            tap_node(edits[0])
            type_text(code)
        else:
            for i, dch in enumerate(code[:len(edits)]):
                tap_node(edits[i])
                type_text(dch)
        shot("code_typed")
        tap_text("Continue", timeout=10) or tap_text("Verify", timeout=5) \
            or tap_text("Next", timeout=5) or tap_text("Confirm", timeout=5)

    # Wait for a logged-in surface.
    end = time.time() + 240
    verdict = "MUSE_POST_CODE_UNKNOWN"
    while time.time() < end:
        nodes = dump()
        t = texts(nodes).lower()
        if "something went wrong" in t:
            verdict = "MUSE_ERROR_AFTER_CODE"
            break
        if any(k in t for k in ("getting ready",)):
            time.sleep(6)
            continue
        if any(k in t for k in ("ask me anything", "message muse",
                               "how can i help", "new chat", "sunshine")):
            verdict = "MUSE_LOGIN_SUCCESS"
            break
        # Generic: welcome/login texts gone and an EditText composer exists.
        if "welcome" not in t and any("EditText" in n["cls"] for n in nodes):
            verdict = "MUSE_LOGIN_SUCCESS_probable"
            break
        time.sleep(6)
    shot("post_code")
    dump("post_code")
    finalize(verdict)


def poll_code():
    """Poll emu/code.txt in the repo until a fresh code appears."""
    if not GH_REPO or not GH_TOKEN:
        log("no GH relay configured")
        return None
    url = ("https://api.github.com/repos/%s/contents/emu/code.txt"
           % GH_REPO)
    end = time.time() + 12 * 60
    while time.time() < end:
        try:
            req = urllib.request.Request(url, headers={
                "Authorization": "Bearer " + GH_TOKEN,
                "Accept": "application/vnd.github.raw",
                "Cache-Control": "no-cache",
            })
            with urllib.request.urlopen(req, timeout=20) as resp:
                body = resp.read().decode("utf-8", "replace").strip()
            m = re.search(r"\b(\d{6})\b", body)
            if m and "PENDING" not in body:
                return m.group(1)
        except Exception as e:
            log("code poll error: %s" % e)
        time.sleep(15)
    return None


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        log("DRIVER EXCEPTION: %r" % e)
        try:
            shot("exception")
            dump("exception")
        finally:
            finalize("DRIVER_EXCEPTION %r" % e)

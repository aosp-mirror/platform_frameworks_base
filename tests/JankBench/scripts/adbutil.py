import subprocess
import re
import threading

ATRACE_PATH="/android/catapult/systrace/systrace/systrace.py"

class AdbError(RuntimeError):
    def __init__(self, arg):
        self.args = arg

def am(serial, cmd, args):
    if not isinstance(args, list):
        args = [args]
    full_args = ["am"] + [cmd] + args
    __call_adb(serial, full_args, False)

def pm(serial, cmd, args):
    if not isinstance(args, list):
        args = [args]
    full_args = ["pm"] + [cmd] + args
    __call_adb(serial, full_args, False)

def dumpsys(serial, topic):
    return __call_adb(serial, ["dumpsys"] + [topic], True)

def trace(serial,
        tags = ["gfx", "sched", "view", "freq", "am", "wm", "power", "load", "memreclaim"],
        time = "10"):
    args = [ATRACE_PATH, "-e", serial, "-t" + time, "-b32768"] + tags
    subprocess.call(args)

def wake(serial):
    output = dumpsys(serial, "power")
    wakefulness = re.search('mWakefulness=([a-zA-Z]+)', output)
    if wakefulness.group(1) != "Awake":
        __call_adb(serial, ["input", "keyevent", "KEYCODE_POWER"], False)

def root(serial):
    subprocess.call(["adb", "-s", serial, "root"])

def pull(serial, path, dest):
    subprocess.call(["adb", "-s", serial, "wait-for-device", "pull"] + [path] + [dest])

def shell(serial, cmd):
    __call_adb(serial, cmd, False)

def track_logcat(serial, awaited_string, callback):
    threading.Thread(target=__track_logcat, name=serial + "-waiter", args=(serial, awaited_string, callback)).start()

def __call_adb(serial, args, block):
    full_args = ["adb", "-s", serial, "wait-for-device", "shell"] + args
    print full_args
    output = None
    try:
        if block:
            output = subprocess.check_output(full_args)
        else:
            subprocess.call(full_args)
    except subprocess.CalledProcessError:
        raise AdbError("Error calling " + " ".join(args))

    return output

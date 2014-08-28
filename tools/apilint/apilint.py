#!/usr/bin/env python

# Copyright (C) 2014 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the 'License');
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an 'AS IS' BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""
Enforces common Android public API design patterns.  It ignores lint messages from
a previous API level, if provided.

Usage: apilint.py current.txt
Usage: apilint.py current.txt previous.txt
"""

import re, sys


BLACK, RED, GREEN, YELLOW, BLUE, MAGENTA, CYAN, WHITE = range(8)

def format(fg=None, bg=None, bright=False, bold=False, dim=False, reset=False):
    # manually derived from http://en.wikipedia.org/wiki/ANSI_escape_code#Codes
    codes = []
    if reset: codes.append("0")
    else:
        if not fg is None: codes.append("3%d" % (fg))
        if not bg is None:
            if not bright: codes.append("4%d" % (bg))
            else: codes.append("10%d" % (bg))
        if bold: codes.append("1")
        elif dim: codes.append("2")
        else: codes.append("22")
    return "\033[%sm" % (";".join(codes))


class Field():
    def __init__(self, clazz, raw):
        self.clazz = clazz
        self.raw = raw.strip(" {;")

        raw = raw.split()
        self.split = list(raw)

        for r in ["field", "volatile", "transient", "public", "protected", "static", "final", "deprecated"]:
            while r in raw: raw.remove(r)

        self.typ = raw[0]
        self.name = raw[1].strip(";")
        if len(raw) >= 4 and raw[2] == "=":
            self.value = raw[3].strip(';"')
        else:
            self.value = None

    def __repr__(self):
        return self.raw


class Method():
    def __init__(self, clazz, raw):
        self.clazz = clazz
        self.raw = raw.strip(" {;")

        raw = re.split("[\s(),;]+", raw)
        for r in ["", ";"]:
            while r in raw: raw.remove(r)
        self.split = list(raw)

        for r in ["method", "public", "protected", "static", "final", "deprecated", "abstract"]:
            while r in raw: raw.remove(r)

        self.typ = raw[0]
        self.name = raw[1]
        self.args = []
        for r in raw[2:]:
            if r == "throws": break
            self.args.append(r)

    def __repr__(self):
        return self.raw


class Class():
    def __init__(self, pkg, raw):
        self.pkg = pkg
        self.raw = raw.strip(" {;")
        self.ctors = []
        self.fields = []
        self.methods = []

        raw = raw.split()
        self.split = list(raw)
        if "class" in raw:
            self.fullname = raw[raw.index("class")+1]
        elif "interface" in raw:
            self.fullname = raw[raw.index("interface")+1]

        if "." in self.fullname:
            self.name = self.fullname[self.fullname.rindex(".")+1:]
        else:
            self.name = self.fullname

    def __repr__(self):
        return self.raw


class Package():
    def __init__(self, raw):
        self.raw = raw.strip(" {;")

        raw = raw.split()
        self.name = raw[raw.index("package")+1]

    def __repr__(self):
        return self.raw


def parse_api(fn):
    api = []
    pkg = None
    clazz = None

    with open(fn) as f:
        for raw in f.readlines():
            raw = raw.rstrip()

            if raw.startswith("package"):
                pkg = Package(raw)
            elif raw.startswith("  ") and raw.endswith("{"):
                clazz = Class(pkg, raw)
                api.append(clazz)
            elif raw.startswith("    ctor"):
                clazz.ctors.append(Method(clazz, raw))
            elif raw.startswith("    method"):
                clazz.methods.append(Method(clazz, raw))
            elif raw.startswith("    field"):
                clazz.fields.append(Field(clazz, raw))

    return api


failures = []

def _fail(clazz, detail, msg):
    """Records an API failure to be processed later."""
    global failures

    res = msg
    if detail is not None:
        res += "\n    in " + repr(detail)
    res += "\n    in " + repr(clazz)
    res += "\n    in " + repr(clazz.pkg)
    failures.append(res)

def warn(clazz, detail, msg):
    _fail(clazz, detail, "%sWarning:%s %s" % (format(fg=YELLOW, bg=BLACK), format(reset=True), msg))

def error(clazz, detail, msg):
    _fail(clazz, detail, "%sError:%s %s" % (format(fg=RED, bg=BLACK), format(reset=True), msg))


def verify_constants(clazz):
    """All static final constants must be FOO_NAME style."""
    if re.match("R\.[a-z]+", clazz.fullname): return

    for f in clazz.fields:
        if "static" in f.split and "final" in f.split:
            if re.match("[A-Z0-9_]+", f.name) is None:
                error(clazz, f, "Constant field names should be FOO_NAME")


def verify_enums(clazz):
    """Enums are bad, mmkay?"""
    if "extends java.lang.Enum" in clazz.raw:
        error(clazz, None, "Enums are not allowed")


def verify_class_names(clazz):
    """Try catching malformed class names like myMtp or MTPUser."""
    if re.search("[A-Z]{2,}", clazz.name) is not None:
        warn(clazz, None, "Class name style should be Mtp not MTP")
    if re.match("[^A-Z]", clazz.name):
        error(clazz, None, "Class must start with uppercase char")


def verify_method_names(clazz):
    """Try catching malformed method names, like Foo() or getMTU()."""
    if clazz.pkg.name == "android.opengl": return

    for m in clazz.methods:
        if re.search("[A-Z]{2,}", m.name) is not None:
            warn(clazz, m, "Method name style should be getMtu() instead of getMTU()")
        if re.match("[^a-z]", m.name):
            error(clazz, None, "Method name must start with lowercase char")


def verify_callbacks(clazz):
    """Verify Callback classes.
    All callback classes must be abstract.
    All methods must follow onFoo() naming style."""

    if clazz.name.endswith("Callbacks"):
        error(clazz, None, "Class must be named exactly Callback")
    if clazz.name.endswith("Observer"):
        warn(clazz, None, "Class should be named Callback")

    if clazz.name.endswith("Callback"):
        if "interface" in clazz.split:
            error(clazz, None, "Callback must be abstract class")

        for m in clazz.methods:
            if not re.match("on[A-Z][a-z]*", m.name):
                error(clazz, m, "Callback method names must be onFoo style")


def verify_listeners(clazz):
    """Verify Listener classes.
    All Listener classes must be interface.
    All methods must follow onFoo() naming style.
    If only a single method, it must match class name:
        interface OnFooListener { void onFoo() }"""

    if clazz.name.endswith("Listener"):
        if " abstract class " in clazz.raw:
            error(clazz, None, "Listener should be interface")

        for m in clazz.methods:
            if not re.match("on[A-Z][a-z]*", m.name):
                error(clazz, m, "Listener method names must be onFoo style")

        if len(clazz.methods) == 1 and clazz.name.startswith("On"):
            m = clazz.methods[0]
            if (m.name + "Listener").lower() != clazz.name.lower():
                error(clazz, m, "Single method name should match class name")


def verify_actions(clazz):
    """Verify intent actions.
    All action names must be named ACTION_FOO.
    All action values must be scoped by package and match name:
        package android.foo {
            String ACTION_BAR = "android.foo.action.BAR";
        }"""
    for f in clazz.fields:
        if f.value is None: continue
        if f.name.startswith("EXTRA_"): continue

        if "static" in f.split and "final" in f.split and f.typ == "java.lang.String":
            if "_ACTION" in f.name or "ACTION_" in f.name or ".action." in f.value.lower():
                if not f.name.startswith("ACTION_"):
                    error(clazz, f, "Intent action must be ACTION_FOO")
                else:
                    if clazz.name == "Intent":
                        prefix = "android.intent.action"
                    elif clazz.name == "Settings":
                        prefix = "android.settings"
                    else:
                        prefix = clazz.pkg.name + ".action"
                    expected = prefix + "." + f.name[7:]
                    if f.value != expected:
                        error(clazz, f, "Inconsistent action value")


def verify_extras(clazz):
    """Verify intent extras.
    All extra names must be named EXTRA_FOO.
    All extra values must be scoped by package and match name:
        package android.foo {
            String EXTRA_BAR = "android.foo.extra.BAR";
        }"""
    for f in clazz.fields:
        if f.value is None: continue
        if f.name.startswith("ACTION_"): continue

        if "static" in f.split and "final" in f.split and f.typ == "java.lang.String":
            if "_EXTRA" in f.name or "EXTRA_" in f.name or ".extra" in f.value.lower():
                if not f.name.startswith("EXTRA_"):
                    error(clazz, f, "Intent extra must be EXTRA_FOO")
                else:
                    if clazz.name == "Intent":
                        prefix = "android.intent.extra"
                    else:
                        prefix = clazz.pkg.name + ".extra"
                    expected = prefix + "." + f.name[6:]
                    if f.value != expected:
                        error(clazz, f, "Inconsistent extra value")


def verify_equals(clazz):
    """Verify that equals() and hashCode() must be overridden together."""
    methods = [ m.name for m in clazz.methods ]
    eq = "equals" in methods
    hc = "hashCode" in methods
    if eq != hc:
        error(clazz, None, "Must override both equals and hashCode")


def verify_parcelable(clazz):
    """Verify that Parcelable objects aren't hiding required bits."""
    if "implements android.os.Parcelable" in clazz.raw:
        creator = [ i for i in clazz.fields if i.name == "CREATOR" ]
        write = [ i for i in clazz.methods if i.name == "writeToParcel" ]
        describe = [ i for i in clazz.methods if i.name == "describeContents" ]

        if len(creator) == 0 or len(write) == 0 or len(describe) == 0:
            error(clazz, None, "Parcelable requires CREATOR, writeToParcel, and describeContents")


def verify_protected(clazz):
    """Verify that no protected methods are allowed."""
    for m in clazz.methods:
        if "protected" in m.split:
            error(clazz, m, "Protected method")
    for f in clazz.fields:
        if "protected" in f.split:
            error(clazz, f, "Protected field")


def verify_fields(clazz):
    """Verify that all exposed fields are final.
    Exposed fields must follow myName style.
    Catch internal mFoo objects being exposed."""
    for f in clazz.fields:
        if not "final" in f.split:
            error(clazz, f, "Bare fields must be final; consider adding accessors")

        if not "static" in f.split:
            if not re.match("[a-z]([a-zA-Z]+)?", f.name):
                error(clazz, f, "Non-static fields must be myName")

        if re.match("[m][A-Z]", f.name):
            error(clazz, f, "Don't expose your internal objects")


def verify_register(clazz):
    """Verify parity of registration methods.
    Callback objects use register/unregister methods.
    Listener objects use add/remove methods."""
    methods = [ m.name for m in clazz.methods ]
    for m in clazz.methods:
        if "Callback" in m.raw:
            if m.name.startswith("register"):
                other = "unregister" + m.name[8:]
                if other not in methods:
                    error(clazz, m, "Missing unregister")
            if m.name.startswith("unregister"):
                other = "register" + m.name[10:]
                if other not in methods:
                    error(clazz, m, "Missing register")

            if m.name.startswith("add") or m.name.startswith("remove"):
                error(clazz, m, "Callback should be register/unregister")

        if "Listener" in m.raw:
            if m.name.startswith("add"):
                other = "remove" + m.name[3:]
                if other not in methods:
                    error(clazz, m, "Missing remove")
            if m.name.startswith("remove") and not m.name.startswith("removeAll"):
                other = "add" + m.name[6:]
                if other not in methods:
                    error(clazz, m, "Missing add")

            if m.name.startswith("register") or m.name.startswith("unregister"):
                error(clazz, m, "Listener should be add/remove")


def verify_sync(clazz):
    """Verify synchronized methods aren't exposed."""
    for m in clazz.methods:
        if "synchronized" in m.split:
            error(clazz, m, "Lock exposed")


def verify_intent_builder(clazz):
    """Verify that Intent builders are createFooIntent() style."""
    if clazz.name == "Intent": return

    for m in clazz.methods:
        if m.typ == "android.content.Intent":
            if m.name.startswith("create") and m.name.endswith("Intent"):
                pass
            else:
                warn(clazz, m, "Should be createFooIntent()")


def verify_helper_classes(clazz):
    """Verify that helper classes are named consistently with what they extend."""
    if "extends android.app.Service" in clazz.raw:
        if not clazz.name.endswith("Service"):
            error(clazz, None, "Inconsistent class name")
    if "extends android.content.ContentProvider" in clazz.raw:
        if not clazz.name.endswith("Provider"):
            error(clazz, None, "Inconsistent class name")
    if "extends android.content.BroadcastReceiver" in clazz.raw:
        if not clazz.name.endswith("Receiver"):
            error(clazz, None, "Inconsistent class name")


def verify_builder(clazz):
    """Verify builder classes.
    Methods should return the builder to enable chaining."""
    if " extends " in clazz.raw: return
    if not clazz.name.endswith("Builder"): return

    if clazz.name != "Builder":
        warn(clazz, None, "Should be standalone Builder class")

    has_build = False
    for m in clazz.methods:
        if m.name == "build":
            has_build = True
            continue

        if m.name.startswith("get"): continue
        if m.name.startswith("clear"): continue

        if not m.typ.endswith(clazz.fullname):
            warn(clazz, m, "Should return the builder")

    if not has_build:
        warn(clazz, None, "Missing build() method")


def verify_aidl(clazz):
    """Catch people exposing raw AIDL."""
    if "extends android.os.Binder" in clazz.raw:
        error(clazz, None, "Exposing raw AIDL interface")


def verify_all(api):
    global failures

    failures = []
    for clazz in api:
        if clazz.pkg.name.startswith("java"): continue
        if clazz.pkg.name.startswith("junit"): continue
        if clazz.pkg.name.startswith("org.apache"): continue
        if clazz.pkg.name.startswith("org.xml"): continue
        if clazz.pkg.name.startswith("org.json"): continue
        if clazz.pkg.name.startswith("org.w3c"): continue

        verify_constants(clazz)
        verify_enums(clazz)
        verify_class_names(clazz)
        verify_method_names(clazz)
        verify_callbacks(clazz)
        verify_listeners(clazz)
        verify_actions(clazz)
        verify_extras(clazz)
        verify_equals(clazz)
        verify_parcelable(clazz)
        verify_protected(clazz)
        verify_fields(clazz)
        verify_register(clazz)
        verify_sync(clazz)
        verify_intent_builder(clazz)
        verify_helper_classes(clazz)
        verify_builder(clazz)
        verify_aidl(clazz)

    return failures


cur = parse_api(sys.argv[1])
cur_fail = verify_all(cur)

if len(sys.argv) > 2:
    prev = parse_api(sys.argv[2])
    prev_fail = verify_all(prev)

    # ignore errors from previous API level
    for p in prev_fail:
        if p in cur_fail:
            cur_fail.remove(p)


for f in cur_fail:
    print f
    print

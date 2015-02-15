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

You can also splice in blame details like this:
$ git blame api/current.txt -t -e > /tmp/currentblame.txt
$ apilint.py /tmp/currentblame.txt previous.txt --no-color
"""

import re, sys, collections, traceback


BLACK, RED, GREEN, YELLOW, BLUE, MAGENTA, CYAN, WHITE = range(8)

def format(fg=None, bg=None, bright=False, bold=False, dim=False, reset=False):
    # manually derived from http://en.wikipedia.org/wiki/ANSI_escape_code#Codes
    if "--no-color" in sys.argv: return ""
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
    def __init__(self, clazz, line, raw, blame):
        self.clazz = clazz
        self.line = line
        self.raw = raw.strip(" {;")
        self.blame = blame

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

        self.ident = self.raw.replace(" deprecated ", " ")

    def __repr__(self):
        return self.raw


class Method():
    def __init__(self, clazz, line, raw, blame):
        self.clazz = clazz
        self.line = line
        self.raw = raw.strip(" {;")
        self.blame = blame

        # drop generics for now
        raw = re.sub("<.+?>", "", raw)

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

        # identity for compat purposes
        ident = self.raw
        ident = ident.replace(" deprecated ", " ")
        ident = ident.replace(" synchronized ", " ")
        ident = re.sub("<.+?>", "", ident)
        if " throws " in ident:
            ident = ident[:ident.index(" throws ")]
        self.ident = ident

    def __repr__(self):
        return self.raw


class Class():
    def __init__(self, pkg, line, raw, blame):
        self.pkg = pkg
        self.line = line
        self.raw = raw.strip(" {;")
        self.blame = blame
        self.ctors = []
        self.fields = []
        self.methods = []

        raw = raw.split()
        self.split = list(raw)
        if "class" in raw:
            self.fullname = raw[raw.index("class")+1]
        elif "interface" in raw:
            self.fullname = raw[raw.index("interface")+1]
        else:
            raise ValueError("Funky class type %s" % (self.raw))

        if "extends" in raw:
            self.extends = raw[raw.index("extends")+1]
        else:
            self.extends = None

        self.fullname = self.pkg.name + "." + self.fullname
        self.name = self.fullname[self.fullname.rindex(".")+1:]

    def __repr__(self):
        return self.raw


class Package():
    def __init__(self, line, raw, blame):
        self.line = line
        self.raw = raw.strip(" {;")
        self.blame = blame

        raw = raw.split()
        self.name = raw[raw.index("package")+1]

    def __repr__(self):
        return self.raw


def parse_api(f):
    line = 0
    api = {}
    pkg = None
    clazz = None
    blame = None

    re_blame = re.compile("^([a-z0-9]{7,}) \(<([^>]+)>.+?\) (.+?)$")
    for raw in f.readlines():
        line += 1
        raw = raw.rstrip()
        match = re_blame.match(raw)
        if match is not None:
            blame = match.groups()[0:2]
            raw = match.groups()[2]
        else:
            blame = None

        if raw.startswith("package"):
            pkg = Package(line, raw, blame)
        elif raw.startswith("  ") and raw.endswith("{"):
            clazz = Class(pkg, line, raw, blame)
            api[clazz.fullname] = clazz
        elif raw.startswith("    ctor"):
            clazz.ctors.append(Method(clazz, line, raw, blame))
        elif raw.startswith("    method"):
            clazz.methods.append(Method(clazz, line, raw, blame))
        elif raw.startswith("    field"):
            clazz.fields.append(Field(clazz, line, raw, blame))

    return api


def parse_api_file(fn):
    with open(fn) as f:
        return parse_api(f)


class Failure():
    def __init__(self, sig, clazz, detail, error, rule, msg):
        self.sig = sig
        self.clazz = clazz
        self.detail = detail
        self.error = error
        self.rule = rule
        self.msg = msg

        if error:
            self.head = "Error %s" % (rule) if rule else "Error"
            dump = "%s%s:%s %s" % (format(fg=RED, bg=BLACK, bold=True), self.head, format(reset=True), msg)
        else:
            self.head = "Warning %s" % (rule) if rule else "Warning"
            dump = "%s%s:%s %s" % (format(fg=YELLOW, bg=BLACK, bold=True), self.head, format(reset=True), msg)

        self.line = clazz.line
        blame = clazz.blame
        if detail is not None:
            dump += "\n    in " + repr(detail)
            self.line = detail.line
            blame = detail.blame
        dump += "\n    in " + repr(clazz)
        dump += "\n    in " + repr(clazz.pkg)
        dump += "\n    at line " + repr(self.line)
        if blame is not None:
            dump += "\n    last modified by %s in %s" % (blame[1], blame[0])

        self.dump = dump

    def __repr__(self):
        return self.dump


failures = {}

def _fail(clazz, detail, error, rule, msg):
    """Records an API failure to be processed later."""
    global failures

    sig = "%s-%s-%s" % (clazz.fullname, repr(detail), msg)
    sig = sig.replace(" deprecated ", " ")

    failures[sig] = Failure(sig, clazz, detail, error, rule, msg)


def warn(clazz, detail, rule, msg):
    _fail(clazz, detail, False, rule, msg)

def error(clazz, detail, rule, msg):
    _fail(clazz, detail, True, rule, msg)


def verify_constants(clazz):
    """All static final constants must be FOO_NAME style."""
    if re.match("android\.R\.[a-z]+", clazz.fullname): return

    for f in clazz.fields:
        if "static" in f.split and "final" in f.split:
            if re.match("[A-Z0-9_]+", f.name) is None:
                error(clazz, f, "C2", "Constant field names should be FOO_NAME")


def verify_enums(clazz):
    """Enums are bad, mmkay?"""
    if "extends java.lang.Enum" in clazz.raw:
        error(clazz, None, "F5", "Enums are not allowed")


def verify_class_names(clazz):
    """Try catching malformed class names like myMtp or MTPUser."""
    if clazz.fullname.startswith("android.opengl"): return
    if clazz.fullname.startswith("android.renderscript"): return
    if re.match("android\.R\.[a-z]+", clazz.fullname): return

    if re.search("[A-Z]{2,}", clazz.name) is not None:
        warn(clazz, None, "S1", "Class name style should be Mtp not MTP")
    if re.match("[^A-Z]", clazz.name):
        error(clazz, None, "S1", "Class must start with uppercase char")


def verify_method_names(clazz):
    """Try catching malformed method names, like Foo() or getMTU()."""
    if clazz.fullname.startswith("android.opengl"): return
    if clazz.fullname.startswith("android.renderscript"): return
    if clazz.fullname == "android.system.OsConstants": return

    for m in clazz.methods:
        if re.search("[A-Z]{2,}", m.name) is not None:
            warn(clazz, m, "S1", "Method name style should be getMtu() instead of getMTU()")
        if re.match("[^a-z]", m.name):
            error(clazz, m, "S1", "Method name must start with lowercase char")


def verify_callbacks(clazz):
    """Verify Callback classes.
    All callback classes must be abstract.
    All methods must follow onFoo() naming style."""
    if clazz.fullname == "android.speech.tts.SynthesisCallback": return

    if clazz.name.endswith("Callbacks"):
        error(clazz, None, "L1", "Class name must not be plural")
    if clazz.name.endswith("Observer"):
        warn(clazz, None, "L1", "Class should be named FooCallback")

    if clazz.name.endswith("Callback"):
        if "interface" in clazz.split:
            error(clazz, None, "CL3", "Callback must be abstract class to enable extension in future API levels")

        for m in clazz.methods:
            if not re.match("on[A-Z][a-z]*", m.name):
                error(clazz, m, "L1", "Callback method names must be onFoo() style")


def verify_listeners(clazz):
    """Verify Listener classes.
    All Listener classes must be interface.
    All methods must follow onFoo() naming style.
    If only a single method, it must match class name:
        interface OnFooListener { void onFoo() }"""

    if clazz.name.endswith("Listener"):
        if " abstract class " in clazz.raw:
            error(clazz, None, "L1", "Listener should be an interface, otherwise renamed Callback")

        for m in clazz.methods:
            if not re.match("on[A-Z][a-z]*", m.name):
                error(clazz, m, "L1", "Listener method names must be onFoo() style")

        if len(clazz.methods) == 1 and clazz.name.startswith("On"):
            m = clazz.methods[0]
            if (m.name + "Listener").lower() != clazz.name.lower():
                error(clazz, m, "L1", "Single listener method name should match class name")


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
        if f.name == "SERVICE_INTERFACE" or f.name == "PROVIDER_INTERFACE": continue

        if "static" in f.split and "final" in f.split and f.typ == "java.lang.String":
            if "_ACTION" in f.name or "ACTION_" in f.name or ".action." in f.value.lower():
                if not f.name.startswith("ACTION_"):
                    error(clazz, f, "C3", "Intent action constant name must be ACTION_FOO")
                else:
                    if clazz.fullname == "android.content.Intent":
                        prefix = "android.intent.action"
                    elif clazz.fullname == "android.provider.Settings":
                        prefix = "android.settings"
                    elif clazz.fullname == "android.app.admin.DevicePolicyManager" or clazz.fullname == "android.app.admin.DeviceAdminReceiver":
                        prefix = "android.app.action"
                    else:
                        prefix = clazz.pkg.name + ".action"
                    expected = prefix + "." + f.name[7:]
                    if f.value != expected:
                        error(clazz, f, "C4", "Inconsistent action value; expected %s" % (expected))


def verify_extras(clazz):
    """Verify intent extras.
    All extra names must be named EXTRA_FOO.
    All extra values must be scoped by package and match name:
        package android.foo {
            String EXTRA_BAR = "android.foo.extra.BAR";
        }"""
    if clazz.fullname == "android.app.Notification": return
    if clazz.fullname == "android.appwidget.AppWidgetManager": return

    for f in clazz.fields:
        if f.value is None: continue
        if f.name.startswith("ACTION_"): continue

        if "static" in f.split and "final" in f.split and f.typ == "java.lang.String":
            if "_EXTRA" in f.name or "EXTRA_" in f.name or ".extra" in f.value.lower():
                if not f.name.startswith("EXTRA_"):
                    error(clazz, f, "C3", "Intent extra must be EXTRA_FOO")
                else:
                    if clazz.pkg.name == "android.content" and clazz.name == "Intent":
                        prefix = "android.intent.extra"
                    elif clazz.pkg.name == "android.app.admin":
                        prefix = "android.app.extra"
                    else:
                        prefix = clazz.pkg.name + ".extra"
                    expected = prefix + "." + f.name[6:]
                    if f.value != expected:
                        error(clazz, f, "C4", "Inconsistent extra value; expected %s" % (expected))


def verify_equals(clazz):
    """Verify that equals() and hashCode() must be overridden together."""
    methods = [ m.name for m in clazz.methods ]
    eq = "equals" in methods
    hc = "hashCode" in methods
    if eq != hc:
        error(clazz, None, "M8", "Must override both equals and hashCode; missing one")


def verify_parcelable(clazz):
    """Verify that Parcelable objects aren't hiding required bits."""
    if "implements android.os.Parcelable" in clazz.raw:
        creator = [ i for i in clazz.fields if i.name == "CREATOR" ]
        write = [ i for i in clazz.methods if i.name == "writeToParcel" ]
        describe = [ i for i in clazz.methods if i.name == "describeContents" ]

        if len(creator) == 0 or len(write) == 0 or len(describe) == 0:
            error(clazz, None, "FW3", "Parcelable requires CREATOR, writeToParcel, and describeContents; missing one")


def verify_protected(clazz):
    """Verify that no protected methods are allowed."""
    for m in clazz.methods:
        if "protected" in m.split:
            error(clazz, m, "M7", "No protected methods; must be public")
    for f in clazz.fields:
        if "protected" in f.split:
            error(clazz, f, "M7", "No protected fields; must be public")


def verify_fields(clazz):
    """Verify that all exposed fields are final.
    Exposed fields must follow myName style.
    Catch internal mFoo objects being exposed."""

    IGNORE_BARE_FIELDS = [
        "android.app.ActivityManager.RecentTaskInfo",
        "android.app.Notification",
        "android.content.pm.ActivityInfo",
        "android.content.pm.ApplicationInfo",
        "android.content.pm.FeatureGroupInfo",
        "android.content.pm.InstrumentationInfo",
        "android.content.pm.PackageInfo",
        "android.content.pm.PackageItemInfo",
        "android.os.Message",
        "android.system.StructPollfd",
    ]

    for f in clazz.fields:
        if not "final" in f.split:
            if clazz.fullname in IGNORE_BARE_FIELDS:
                pass
            elif clazz.fullname.endswith("LayoutParams"):
                pass
            elif clazz.fullname.startswith("android.util.Mutable"):
                pass
            else:
                error(clazz, f, "F2", "Bare fields must be marked final, or add accessors if mutable")

        if not "static" in f.split:
            if not re.match("[a-z]([a-zA-Z]+)?", f.name):
                error(clazz, f, "S1", "Non-static fields must be named with myField style")

        if re.match("[ms][A-Z]", f.name):
            error(clazz, f, "F1", "Don't expose your internal objects")

        if re.match("[A-Z_]+", f.name):
            if "static" not in f.split or "final" not in f.split:
                error(clazz, f, "C2", "Constants must be marked static final")


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
                    error(clazz, m, "L2", "Missing unregister method")
            if m.name.startswith("unregister"):
                other = "register" + m.name[10:]
                if other not in methods:
                    error(clazz, m, "L2", "Missing register method")

            if m.name.startswith("add") or m.name.startswith("remove"):
                error(clazz, m, "L3", "Callback methods should be named register/unregister")

        if "Listener" in m.raw:
            if m.name.startswith("add"):
                other = "remove" + m.name[3:]
                if other not in methods:
                    error(clazz, m, "L2", "Missing remove method")
            if m.name.startswith("remove") and not m.name.startswith("removeAll"):
                other = "add" + m.name[6:]
                if other not in methods:
                    error(clazz, m, "L2", "Missing add method")

            if m.name.startswith("register") or m.name.startswith("unregister"):
                error(clazz, m, "L3", "Listener methods should be named add/remove")


def verify_sync(clazz):
    """Verify synchronized methods aren't exposed."""
    for m in clazz.methods:
        if "synchronized" in m.split:
            error(clazz, m, "M5", "Internal lock exposed")


def verify_intent_builder(clazz):
    """Verify that Intent builders are createFooIntent() style."""
    if clazz.name == "Intent": return

    for m in clazz.methods:
        if m.typ == "android.content.Intent":
            if m.name.startswith("create") and m.name.endswith("Intent"):
                pass
            else:
                error(clazz, m, "FW1", "Methods creating an Intent should be named createFooIntent()")


def verify_helper_classes(clazz):
    """Verify that helper classes are named consistently with what they extend.
    All developer extendable methods should be named onFoo()."""
    test_methods = False
    if "extends android.app.Service" in clazz.raw:
        test_methods = True
        if not clazz.name.endswith("Service"):
            error(clazz, None, "CL4", "Inconsistent class name; should be FooService")

        found = False
        for f in clazz.fields:
            if f.name == "SERVICE_INTERFACE":
                found = True
                if f.value != clazz.fullname:
                    error(clazz, f, "C4", "Inconsistent interface constant; expected %s" % (clazz.fullname))

    if "extends android.content.ContentProvider" in clazz.raw:
        test_methods = True
        if not clazz.name.endswith("Provider"):
            error(clazz, None, "CL4", "Inconsistent class name; should be FooProvider")

        found = False
        for f in clazz.fields:
            if f.name == "PROVIDER_INTERFACE":
                found = True
                if f.value != clazz.fullname:
                    error(clazz, f, "C4", "Inconsistent interface name; expected %s" % (clazz.fullname))

    if "extends android.content.BroadcastReceiver" in clazz.raw:
        test_methods = True
        if not clazz.name.endswith("Receiver"):
            error(clazz, None, "CL4", "Inconsistent class name; should be FooReceiver")

    if "extends android.app.Activity" in clazz.raw:
        test_methods = True
        if not clazz.name.endswith("Activity"):
            error(clazz, None, "CL4", "Inconsistent class name; should be FooActivity")

    if test_methods:
        for m in clazz.methods:
            if "final" in m.split: continue
            if not re.match("on[A-Z]", m.name):
                if "abstract" in m.split:
                    error(clazz, m, None, "Methods implemented by developers must be named onFoo()")
                else:
                    warn(clazz, m, None, "If implemented by developer, should be named onFoo(); otherwise consider marking final")


def verify_builder(clazz):
    """Verify builder classes.
    Methods should return the builder to enable chaining."""
    if " extends " in clazz.raw: return
    if not clazz.name.endswith("Builder"): return

    if clazz.name != "Builder":
        warn(clazz, None, None, "Builder should be defined as inner class")

    has_build = False
    for m in clazz.methods:
        if m.name == "build":
            has_build = True
            continue

        if m.name.startswith("get"): continue
        if m.name.startswith("clear"): continue

        if m.name.startswith("with"):
            error(clazz, m, None, "Builder methods names must follow setFoo() style")

        if m.name.startswith("set"):
            if not m.typ.endswith(clazz.fullname):
                warn(clazz, m, "M4", "Methods should return the builder")

    if not has_build:
        warn(clazz, None, None, "Missing build() method")


def verify_aidl(clazz):
    """Catch people exposing raw AIDL."""
    if "extends android.os.Binder" in clazz.raw or "implements android.os.IInterface" in clazz.raw:
        error(clazz, None, None, "Exposing raw AIDL interface")


def verify_internal(clazz):
    """Catch people exposing internal classes."""
    if clazz.pkg.name.startswith("com.android"):
        error(clazz, None, None, "Exposing internal class")


def verify_layering(clazz):
    """Catch package layering violations.
    For example, something in android.os depending on android.app."""
    ranking = [
        ["android.service","android.accessibilityservice","android.inputmethodservice","android.printservice","android.appwidget","android.webkit","android.preference","android.gesture","android.print"],
        "android.app",
        "android.widget",
        "android.view",
        "android.animation",
        "android.provider",
        ["android.content","android.graphics.drawable"],
        "android.database",
        "android.graphics",
        "android.text",
        "android.os",
        "android.util"
    ]

    def rank(p):
        for i in range(len(ranking)):
            if isinstance(ranking[i], list):
                for j in ranking[i]:
                    if p.startswith(j): return i
            else:
                if p.startswith(ranking[i]): return i

    cr = rank(clazz.pkg.name)
    if cr is None: return

    for f in clazz.fields:
        ir = rank(f.typ)
        if ir and ir < cr:
            warn(clazz, f, "FW6", "Field type violates package layering")

    for m in clazz.methods:
        ir = rank(m.typ)
        if ir and ir < cr:
            warn(clazz, m, "FW6", "Method return type violates package layering")
        for arg in m.args:
            ir = rank(arg)
            if ir and ir < cr:
                warn(clazz, m, "FW6", "Method argument type violates package layering")


def verify_boolean(clazz, api):
    """Catches people returning boolean from getFoo() style methods.
    Ignores when matching setFoo() is present."""

    methods = [ m.name for m in clazz.methods ]

    builder = clazz.fullname + ".Builder"
    builder_methods = []
    if builder in api:
        builder_methods = [ m.name for m in api[builder].methods ]

    for m in clazz.methods:
        if m.typ == "boolean" and m.name.startswith("get") and m.name != "get" and len(m.args) == 0:
            setter = "set" + m.name[3:]
            if setter in methods:
                pass
            elif builder is not None and setter in builder_methods:
                pass
            else:
                warn(clazz, m, None, "Methods returning boolean should be named isFoo, hasFoo, areFoo")


def verify_collections(clazz):
    """Verifies that collection types are interfaces."""
    if clazz.fullname == "android.os.Bundle": return

    bad = ["java.util.Vector", "java.util.LinkedList", "java.util.ArrayList", "java.util.Stack",
           "java.util.HashMap", "java.util.HashSet", "android.util.ArraySet", "android.util.ArrayMap"]
    for m in clazz.methods:
        if m.typ in bad:
            error(clazz, m, "CL2", "Return type is concrete collection; should be interface")
        for arg in m.args:
            if arg in bad:
                error(clazz, m, "CL2", "Argument is concrete collection; should be interface")


def verify_flags(clazz):
    """Verifies that flags are non-overlapping."""
    known = collections.defaultdict(int)
    for f in clazz.fields:
        if "FLAG_" in f.name:
            try:
                val = int(f.value)
            except:
                continue

            scope = f.name[0:f.name.index("FLAG_")]
            if val & known[scope]:
                error(clazz, f, "C1", "Found overlapping flag constant value")
            known[scope] |= val


def verify_exception(clazz):
    """Verifies that methods don't throw generic exceptions."""
    for m in clazz.methods:
        if "throws java.lang.Exception" in m.raw or "throws java.lang.Throwable" in m.raw or "throws java.lang.Error" in m.raw:
            error(clazz, m, "S1", "Methods must not throw generic exceptions")


def verify_google(clazz):
    """Verifies that APIs never reference Google."""

    if re.search("google", clazz.raw, re.IGNORECASE):
        error(clazz, None, None, "Must never reference Google")

    test = []
    test.extend(clazz.ctors)
    test.extend(clazz.fields)
    test.extend(clazz.methods)

    for t in test:
        if re.search("google", t.raw, re.IGNORECASE):
            error(clazz, t, None, "Must never reference Google")


def verify_bitset(clazz):
    """Verifies that we avoid using heavy BitSet."""

    for f in clazz.fields:
        if f.typ == "java.util.BitSet":
            error(clazz, f, None, "Field type must not be heavy BitSet")

    for m in clazz.methods:
        if m.typ == "java.util.BitSet":
            error(clazz, m, None, "Return type must not be heavy BitSet")
        for arg in m.args:
            if arg == "java.util.BitSet":
                error(clazz, m, None, "Argument type must not be heavy BitSet")


def verify_manager(clazz):
    """Verifies that FooManager is only obtained from Context."""

    if not clazz.name.endswith("Manager"): return

    for c in clazz.ctors:
        error(clazz, c, None, "Managers must always be obtained from Context")


def verify_boxed(clazz):
    """Verifies that methods avoid boxed primitives."""

    boxed = ["java.lang.Number", "java.lang.Byte","java.lang.Double","java.lang.Float","java.lang.Integer","java.lang.Long","java.lang.Short"]

    for c in clazz.ctors:
        for arg in c.args:
            if arg in boxed:
                error(clazz, c, None, "Must avoid boxed primitives")

    for f in clazz.fields:
        if f.typ in boxed:
            error(clazz, f, None, "Must avoid boxed primitives")

    for m in clazz.methods:
        if m.typ in boxed:
            error(clazz, m, None, "Must avoid boxed primitives")
        for arg in m.args:
            if arg in boxed:
                error(clazz, m, None, "Must avoid boxed primitives")


def verify_static_utils(clazz):
    """Verifies that helper classes can't be constructed."""
    if clazz.fullname.startswith("android.opengl"): return
    if re.match("android\.R\.[a-z]+", clazz.fullname): return

    if len(clazz.fields) > 0: return
    if len(clazz.methods) == 0: return

    for m in clazz.methods:
        if "static" not in m.split:
            return

    # At this point, we have no fields, and all methods are static
    if len(clazz.ctors) > 0:
        error(clazz, None, None, "Fully-static utility classes must not have constructor")


def verify_style(api):
    """Find all style issues in the given API level."""
    global failures

    failures = {}
    for key in sorted(api.keys()):
        clazz = api[key]

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
        verify_internal(clazz)
        verify_layering(clazz)
        verify_boolean(clazz, api)
        verify_collections(clazz)
        verify_flags(clazz)
        verify_exception(clazz)
        verify_google(clazz)
        verify_bitset(clazz)
        verify_manager(clazz)
        verify_boxed(clazz)
        verify_static_utils(clazz)

    return failures


def verify_compat(cur, prev):
    """Find any incompatible API changes between two levels."""
    global failures

    def class_exists(api, test):
        return test.fullname in api

    def ctor_exists(api, clazz, test):
        for m in clazz.ctors:
            if m.ident == test.ident: return True
        return False

    def all_methods(api, clazz):
        methods = list(clazz.methods)
        if clazz.extends is not None:
            methods.extend(all_methods(api, api[clazz.extends]))
        return methods

    def method_exists(api, clazz, test):
        methods = all_methods(api, clazz)
        for m in methods:
            if m.ident == test.ident: return True
        return False

    def field_exists(api, clazz, test):
        for f in clazz.fields:
            if f.ident == test.ident: return True
        return False

    failures = {}
    for key in sorted(prev.keys()):
        prev_clazz = prev[key]

        if not class_exists(cur, prev_clazz):
            error(prev_clazz, None, None, "Class removed or incompatible change")
            continue

        cur_clazz = cur[key]

        for test in prev_clazz.ctors:
            if not ctor_exists(cur, cur_clazz, test):
                error(prev_clazz, prev_ctor, None, "Constructor removed or incompatible change")

        methods = all_methods(prev, prev_clazz)
        for test in methods:
            if not method_exists(cur, cur_clazz, test):
                error(prev_clazz, test, None, "Method removed or incompatible change")

        for test in prev_clazz.fields:
            if not field_exists(cur, cur_clazz, test):
                error(prev_clazz, test, None, "Field removed or incompatible change")

    return failures


if __name__ == "__main__":
    cur = parse_api_file(sys.argv[1])
    cur_fail = verify_style(cur)

    if len(sys.argv) > 2:
        prev = parse_api_file(sys.argv[2])
        prev_fail = verify_style(prev)

        # ignore errors from previous API level
        for p in prev_fail:
            if p in cur_fail:
                del cur_fail[p]

        # look for compatibility issues
        compat_fail = verify_compat(cur, prev)

        print "%s API compatibility issues %s\n" % ((format(fg=WHITE, bg=BLUE, bold=True), format(reset=True)))
        for f in sorted(compat_fail):
            print compat_fail[f]
            print


    print "%s API style issues %s\n" % ((format(fg=WHITE, bg=BLUE, bold=True), format(reset=True)))
    for f in sorted(cur_fail):
        print cur_fail[f]
        print

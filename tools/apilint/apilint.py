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
    def __init__(self, clazz, raw, blame):
        self.clazz = clazz
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
    def __init__(self, clazz, raw, blame):
        self.clazz = clazz
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
    def __init__(self, pkg, raw, blame):
        self.pkg = pkg
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
    def __init__(self, raw, blame):
        self.raw = raw.strip(" {;")
        self.blame = blame

        raw = raw.split()
        self.name = raw[raw.index("package")+1]

    def __repr__(self):
        return self.raw


def parse_api(fn):
    api = {}
    pkg = None
    clazz = None
    blame = None

    re_blame = re.compile("^([a-z0-9]{7,}) \(<([^>]+)>.+?\) (.+?)$")

    with open(fn) as f:
        for raw in f.readlines():
            raw = raw.rstrip()
            match = re_blame.match(raw)
            if match is not None:
                blame = match.groups()[0:2]
                raw = match.groups()[2]
            else:
                blame = None

            if raw.startswith("package"):
                pkg = Package(raw, blame)
            elif raw.startswith("  ") and raw.endswith("{"):
                clazz = Class(pkg, raw, blame)
                api[clazz.fullname] = clazz
            elif raw.startswith("    ctor"):
                clazz.ctors.append(Method(clazz, raw, blame))
            elif raw.startswith("    method"):
                clazz.methods.append(Method(clazz, raw, blame))
            elif raw.startswith("    field"):
                clazz.fields.append(Field(clazz, raw, blame))

    return api


failures = {}

def _fail(clazz, detail, msg):
    """Records an API failure to be processed later."""
    global failures

    sig = "%s-%s-%s" % (clazz.fullname, repr(detail), msg)
    sig = sig.replace(" deprecated ", " ")

    res = msg
    blame = clazz.blame
    if detail is not None:
        res += "\n    in " + repr(detail)
        blame = detail.blame
    res += "\n    in " + repr(clazz)
    res += "\n    in " + repr(clazz.pkg)
    if blame is not None:
        res += "\n    last modified by %s in %s" % (blame[1], blame[0])
    failures[sig] = res

def warn(clazz, detail, msg):
    _fail(clazz, detail, "%sWarning:%s %s" % (format(fg=YELLOW, bg=BLACK, bold=True), format(reset=True), msg))

def error(clazz, detail, msg):
    _fail(clazz, detail, "%sError:%s %s" % (format(fg=RED, bg=BLACK, bold=True), format(reset=True), msg))


def verify_constants(clazz):
    """All static final constants must be FOO_NAME style."""
    if re.match("android\.R\.[a-z]+", clazz.fullname): return

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
    if clazz.fullname.startswith("android.opengl"): return
    if clazz.fullname.startswith("android.renderscript"): return
    if re.match("android\.R\.[a-z]+", clazz.fullname): return

    if re.search("[A-Z]{2,}", clazz.name) is not None:
        warn(clazz, None, "Class name style should be Mtp not MTP")
    if re.match("[^A-Z]", clazz.name):
        error(clazz, None, "Class must start with uppercase char")


def verify_method_names(clazz):
    """Try catching malformed method names, like Foo() or getMTU()."""
    if clazz.fullname.startswith("android.opengl"): return
    if clazz.fullname.startswith("android.renderscript"): return
    if clazz.fullname == "android.system.OsConstants": return

    for m in clazz.methods:
        if re.search("[A-Z]{2,}", m.name) is not None:
            warn(clazz, m, "Method name style should be getMtu() instead of getMTU()")
        if re.match("[^a-z]", m.name):
            error(clazz, m, "Method name must start with lowercase char")


def verify_callbacks(clazz):
    """Verify Callback classes.
    All callback classes must be abstract.
    All methods must follow onFoo() naming style."""
    if clazz.fullname == "android.speech.tts.SynthesisCallback": return

    if clazz.name.endswith("Callbacks"):
        error(clazz, None, "Class name must not be plural")
    if clazz.name.endswith("Observer"):
        warn(clazz, None, "Class should be named FooCallback")

    if clazz.name.endswith("Callback"):
        if "interface" in clazz.split:
            error(clazz, None, "Callback must be abstract class to enable extension in future API levels")

        for m in clazz.methods:
            if not re.match("on[A-Z][a-z]*", m.name):
                error(clazz, m, "Callback method names must be onFoo() style")


def verify_listeners(clazz):
    """Verify Listener classes.
    All Listener classes must be interface.
    All methods must follow onFoo() naming style.
    If only a single method, it must match class name:
        interface OnFooListener { void onFoo() }"""

    if clazz.name.endswith("Listener"):
        if " abstract class " in clazz.raw:
            error(clazz, None, "Listener should be an interface, otherwise renamed Callback")

        for m in clazz.methods:
            if not re.match("on[A-Z][a-z]*", m.name):
                error(clazz, m, "Listener method names must be onFoo() style")

        if len(clazz.methods) == 1 and clazz.name.startswith("On"):
            m = clazz.methods[0]
            if (m.name + "Listener").lower() != clazz.name.lower():
                error(clazz, m, "Single listener method name should match class name")


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
                    error(clazz, f, "Intent action constant name must be ACTION_FOO")
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
                        error(clazz, f, "Inconsistent action value; expected %s" % (expected))


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
                    error(clazz, f, "Intent extra must be EXTRA_FOO")
                else:
                    if clazz.pkg.name == "android.content" and clazz.name == "Intent":
                        prefix = "android.intent.extra"
                    elif clazz.pkg.name == "android.app.admin":
                        prefix = "android.app.extra"
                    else:
                        prefix = clazz.pkg.name + ".extra"
                    expected = prefix + "." + f.name[6:]
                    if f.value != expected:
                        error(clazz, f, "Inconsistent extra value; expected %s" % (expected))


def verify_equals(clazz):
    """Verify that equals() and hashCode() must be overridden together."""
    methods = [ m.name for m in clazz.methods ]
    eq = "equals" in methods
    hc = "hashCode" in methods
    if eq != hc:
        error(clazz, None, "Must override both equals and hashCode; missing one")


def verify_parcelable(clazz):
    """Verify that Parcelable objects aren't hiding required bits."""
    if "implements android.os.Parcelable" in clazz.raw:
        creator = [ i for i in clazz.fields if i.name == "CREATOR" ]
        write = [ i for i in clazz.methods if i.name == "writeToParcel" ]
        describe = [ i for i in clazz.methods if i.name == "describeContents" ]

        if len(creator) == 0 or len(write) == 0 or len(describe) == 0:
            error(clazz, None, "Parcelable requires CREATOR, writeToParcel, and describeContents; missing one")


def verify_protected(clazz):
    """Verify that no protected methods are allowed."""
    for m in clazz.methods:
        if "protected" in m.split:
            error(clazz, m, "No protected methods; must be public")
    for f in clazz.fields:
        if "protected" in f.split:
            error(clazz, f, "No protected fields; must be public")


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
                error(clazz, f, "Bare fields must be marked final; consider adding accessors")

        if not "static" in f.split:
            if not re.match("[a-z]([a-zA-Z]+)?", f.name):
                error(clazz, f, "Non-static fields must be named with myField style")

        if re.match("[ms][A-Z]", f.name):
            error(clazz, f, "Don't expose your internal objects")

        if re.match("[A-Z_]+", f.name):
            if "static" not in f.split or "final" not in f.split:
                error(clazz, f, "Constants must be marked static final")


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
                    error(clazz, m, "Missing unregister method")
            if m.name.startswith("unregister"):
                other = "register" + m.name[10:]
                if other not in methods:
                    error(clazz, m, "Missing register method")

            if m.name.startswith("add") or m.name.startswith("remove"):
                error(clazz, m, "Callback methods should be named register/unregister")

        if "Listener" in m.raw:
            if m.name.startswith("add"):
                other = "remove" + m.name[3:]
                if other not in methods:
                    error(clazz, m, "Missing remove method")
            if m.name.startswith("remove") and not m.name.startswith("removeAll"):
                other = "add" + m.name[6:]
                if other not in methods:
                    error(clazz, m, "Missing add method")

            if m.name.startswith("register") or m.name.startswith("unregister"):
                error(clazz, m, "Listener methods should be named add/remove")


def verify_sync(clazz):
    """Verify synchronized methods aren't exposed."""
    for m in clazz.methods:
        if "synchronized" in m.split:
            error(clazz, m, "Internal lock exposed")


def verify_intent_builder(clazz):
    """Verify that Intent builders are createFooIntent() style."""
    if clazz.name == "Intent": return

    for m in clazz.methods:
        if m.typ == "android.content.Intent":
            if m.name.startswith("create") and m.name.endswith("Intent"):
                pass
            else:
                error(clazz, m, "Methods creating an Intent should be named createFooIntent()")


def verify_helper_classes(clazz):
    """Verify that helper classes are named consistently with what they extend.
    All developer extendable methods should be named onFoo()."""
    test_methods = False
    if "extends android.app.Service" in clazz.raw:
        test_methods = True
        if not clazz.name.endswith("Service"):
            error(clazz, None, "Inconsistent class name; should be FooService")

        found = False
        for f in clazz.fields:
            if f.name == "SERVICE_INTERFACE":
                found = True
                if f.value != clazz.fullname:
                    error(clazz, f, "Inconsistent interface constant; expected %s" % (clazz.fullname))

        if not found:
            warn(clazz, None, "Missing SERVICE_INTERFACE constant")

        if "abstract" in clazz.split and not clazz.fullname.startswith("android.service."):
            warn(clazz, None, "Services extended by developers should be under android.service")

    if "extends android.content.ContentProvider" in clazz.raw:
        test_methods = True
        if not clazz.name.endswith("Provider"):
            error(clazz, None, "Inconsistent class name; should be FooProvider")

        found = False
        for f in clazz.fields:
            if f.name == "PROVIDER_INTERFACE":
                found = True
                if f.value != clazz.fullname:
                    error(clazz, f, "Inconsistent interface name; expected %s" % (clazz.fullname))

        if not found:
            warn(clazz, None, "Missing PROVIDER_INTERFACE constant")

        if "abstract" in clazz.split and not clazz.fullname.startswith("android.provider."):
            warn(clazz, None, "Providers extended by developers should be under android.provider")

    if "extends android.content.BroadcastReceiver" in clazz.raw:
        test_methods = True
        if not clazz.name.endswith("Receiver"):
            error(clazz, None, "Inconsistent class name; should be FooReceiver")

    if "extends android.app.Activity" in clazz.raw:
        test_methods = True
        if not clazz.name.endswith("Activity"):
            error(clazz, None, "Inconsistent class name; should be FooActivity")

    if test_methods:
        for m in clazz.methods:
            if "final" in m.split: continue
            if not re.match("on[A-Z]", m.name):
                if "abstract" in m.split:
                    error(clazz, m, "Methods implemented by developers must be named onFoo()")
                else:
                    warn(clazz, m, "If implemented by developer, should be named onFoo(); otherwise consider marking final")


def verify_builder(clazz):
    """Verify builder classes.
    Methods should return the builder to enable chaining."""
    if " extends " in clazz.raw: return
    if not clazz.name.endswith("Builder"): return

    if clazz.name != "Builder":
        warn(clazz, None, "Builder should be defined as inner class")

    has_build = False
    for m in clazz.methods:
        if m.name == "build":
            has_build = True
            continue

        if m.name.startswith("get"): continue
        if m.name.startswith("clear"): continue

        if m.name.startswith("with"):
            error(clazz, m, "Builder methods names must follow setFoo() style")

        if m.name.startswith("set"):
            if not m.typ.endswith(clazz.fullname):
                warn(clazz, m, "Methods should return the builder")

    if not has_build:
        warn(clazz, None, "Missing build() method")


def verify_aidl(clazz):
    """Catch people exposing raw AIDL."""
    if "extends android.os.Binder" in clazz.raw or "implements android.os.IInterface" in clazz.raw:
        error(clazz, None, "Exposing raw AIDL interface")


def verify_internal(clazz):
    """Catch people exposing internal classes."""
    if clazz.pkg.name.startswith("com.android"):
        error(clazz, None, "Exposing internal class")


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
            warn(clazz, f, "Field type violates package layering")

    for m in clazz.methods:
        ir = rank(m.typ)
        if ir and ir < cr:
            warn(clazz, m, "Method return type violates package layering")
        for arg in m.args:
            ir = rank(arg)
            if ir and ir < cr:
                warn(clazz, m, "Method argument type violates package layering")


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
                warn(clazz, m, "Methods returning boolean should be named isFoo, hasFoo, areFoo")


def verify_collections(clazz):
    """Verifies that collection types are interfaces."""
    if clazz.fullname == "android.os.Bundle": return

    bad = ["java.util.Vector", "java.util.LinkedList", "java.util.ArrayList", "java.util.Stack",
           "java.util.HashMap", "java.util.HashSet", "android.util.ArraySet", "android.util.ArrayMap"]
    for m in clazz.methods:
        if m.typ in bad:
            error(clazz, m, "Return type is concrete collection; should be interface")
        for arg in m.args:
            if arg in bad:
                error(clazz, m, "Argument is concrete collection; should be interface")


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
                warn(clazz, f, "Found overlapping flag constant value")
            known[scope] |= val


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
            error(prev_clazz, None, "Class removed or incompatible change")
            continue

        cur_clazz = cur[key]

        for test in prev_clazz.ctors:
            if not ctor_exists(cur, cur_clazz, test):
                error(prev_clazz, prev_ctor, "Constructor removed or incompatible change")

        methods = all_methods(prev, prev_clazz)
        for test in methods:
            if not method_exists(cur, cur_clazz, test):
                error(prev_clazz, test, "Method removed or incompatible change")

        for test in prev_clazz.fields:
            if not field_exists(cur, cur_clazz, test):
                error(prev_clazz, test, "Field removed or incompatible change")

    return failures


cur = parse_api(sys.argv[1])
cur_fail = verify_style(cur)

if len(sys.argv) > 2:
    prev = parse_api(sys.argv[2])
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

#!/usr/bin/env python

# Copyright (C) 2021 The Android Open Source Project
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
Usage: deprecated_at_birth.py path/to/next/ path/to/previous/
Usage: deprecated_at_birth.py prebuilts/sdk/31/public/api/ prebuilts/sdk/30/public/api/
"""

import re, sys, os, collections, traceback, argparse


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


def ident(raw):
    """Strips superficial signature changes, giving us a strong key that
    can be used to identify members across API levels."""
    raw = raw.replace(" deprecated ", " ")
    raw = raw.replace(" synchronized ", " ")
    raw = raw.replace(" final ", " ")
    raw = re.sub("<.+?>", "", raw)
    raw = re.sub("@[A-Za-z]+ ", "", raw)
    raw = re.sub("@[A-Za-z]+\(.+?\) ", "", raw)
    if " throws " in raw:
        raw = raw[:raw.index(" throws ")]
    return raw


class Field():
    def __init__(self, clazz, line, raw, blame):
        self.clazz = clazz
        self.line = line
        self.raw = raw.strip(" {;")
        self.blame = blame

        raw = raw.split()
        self.split = list(raw)

        raw = [ r for r in raw if not r.startswith("@") ]
        for r in ["method", "field", "public", "protected", "static", "final", "abstract", "default", "volatile", "transient"]:
            while r in raw: raw.remove(r)

        self.typ = raw[0]
        self.name = raw[1].strip(";")
        if len(raw) >= 4 and raw[2] == "=":
            self.value = raw[3].strip(';"')
        else:
            self.value = None
        self.ident = ident(self.raw)

    def __hash__(self):
        return hash(self.raw)

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

        raw = [ r for r in raw if not r.startswith("@") ]
        for r in ["method", "field", "public", "protected", "static", "final", "abstract", "default", "volatile", "transient"]:
            while r in raw: raw.remove(r)

        self.typ = raw[0]
        self.name = raw[1]
        self.args = []
        self.throws = []
        target = self.args
        for r in raw[2:]:
            if r == "throws": target = self.throws
            else: target.append(r)
        self.ident = ident(self.raw)

    def __hash__(self):
        return hash(self.raw)

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
        elif "enum" in raw:
            self.fullname = raw[raw.index("enum")+1]
        elif "interface" in raw:
            self.fullname = raw[raw.index("interface")+1]
        elif "@interface" in raw:
            self.fullname = raw[raw.index("@interface")+1]
        else:
            raise ValueError("Funky class type %s" % (self.raw))

        if "extends" in raw:
            self.extends = raw[raw.index("extends")+1]
            self.extends_path = self.extends.split(".")
        else:
            self.extends = None
            self.extends_path = []

        self.fullname = self.pkg.name + "." + self.fullname
        self.fullname_path = self.fullname.split(".")

        self.name = self.fullname[self.fullname.rindex(".")+1:]

    def __hash__(self):
        return hash((self.raw, tuple(self.ctors), tuple(self.fields), tuple(self.methods)))

    def __repr__(self):
        return self.raw


class Package():
    def __init__(self, line, raw, blame):
        self.line = line
        self.raw = raw.strip(" {;")
        self.blame = blame

        raw = raw.split()
        self.name = raw[raw.index("package")+1]
        self.name_path = self.name.split(".")

    def __repr__(self):
        return self.raw


def _parse_stream(f, api={}):
    line = 0
    pkg = None
    clazz = None
    blame = None

    re_blame = re.compile("^([a-z0-9]{7,}) \(<([^>]+)>.+?\) (.+?)$")
    for raw in f:
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


def _parse_stream_path(path):
    api = {}
    print "Parsing", path
    for f in os.listdir(path):
        f = os.path.join(path, f)
        if not os.path.isfile(f): continue
        if not f.endswith(".txt"): continue
        if f.endswith("removed.txt"): continue
        print "\t", f
        with open(f) as s:
            api = _parse_stream(s, api)
    print "Parsed", len(api), "APIs"
    print
    return api


class Failure():
    def __init__(self, sig, clazz, detail, error, rule, msg):
        self.sig = sig
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


if __name__ == "__main__":
    next_path = sys.argv[1]
    prev_path = sys.argv[2]

    next_api = _parse_stream_path(next_path)
    prev_api = _parse_stream_path(prev_path)

    # Remove all existing things so we're left with new
    for prev_clazz in prev_api.values():
        if prev_clazz.fullname not in next_api: continue
        cur_clazz = next_api[prev_clazz.fullname]

        sigs = { i.ident: i for i in prev_clazz.ctors }
        cur_clazz.ctors = [ i for i in cur_clazz.ctors if i.ident not in sigs ]
        sigs = { i.ident: i for i in prev_clazz.methods }
        cur_clazz.methods = [ i for i in cur_clazz.methods if i.ident not in sigs ]
        sigs = { i.ident: i for i in prev_clazz.fields }
        cur_clazz.fields = [ i for i in cur_clazz.fields if i.ident not in sigs ]

        # Forget about class entirely when nothing new
        if len(cur_clazz.ctors) == 0 and len(cur_clazz.methods) == 0 and len(cur_clazz.fields) == 0:
            del next_api[prev_clazz.fullname]

    for clazz in next_api.values():
        if "@Deprecated " in clazz.raw and not clazz.fullname in prev_api:
            error(clazz, None, None, "Found API deprecation at birth")

        if "@Deprecated " in clazz.raw: continue

        for i in clazz.ctors + clazz.methods + clazz.fields:
            if "@Deprecated " in i.raw:
                error(clazz, i, None, "Found API deprecation at birth " + i.ident)

    print "%s Deprecated at birth %s\n" % ((format(fg=WHITE, bg=BLUE, bold=True),
                                            format(reset=True)))
    for f in sorted(failures):
        print failures[f]
        print

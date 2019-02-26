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

import re, sys, collections, traceback, argparse, itertools


BLACK, RED, GREEN, YELLOW, BLUE, MAGENTA, CYAN, WHITE = range(8)

ALLOW_GOOGLE = False
USE_COLOR = True

def format(fg=None, bg=None, bright=False, bold=False, dim=False, reset=False):
    # manually derived from http://en.wikipedia.org/wiki/ANSI_escape_code#Codes
    if not USE_COLOR: return ""
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
    def __init__(self, clazz, line, raw, blame, sig_format = 1):
        self.clazz = clazz
        self.line = line
        self.raw = raw.strip(" {;")
        self.blame = blame

        if sig_format == 2:
            V2LineParser(raw).parse_into_field(self)
        elif sig_format == 1:
            # drop generics for now; may need multiple passes
            raw = re.sub("<[^<]+?>", "", raw)
            raw = re.sub("<[^<]+?>", "", raw)

            raw = raw.split()
            self.split = list(raw)

            for r in ["field", "volatile", "transient", "public", "protected", "static", "final", "deprecated"]:
                while r in raw: raw.remove(r)

            # ignore annotations for now
            raw = [ r for r in raw if not r.startswith("@") ]

            self.typ = raw[0]
            self.name = raw[1].strip(";")
            if len(raw) >= 4 and raw[2] == "=":
                self.value = raw[3].strip(';"')
            else:
                self.value = None

        self.ident = "-".join((self.typ, self.name, self.value or ""))

    def __hash__(self):
        return hash(self.raw)

    def __repr__(self):
        return self.raw

class Method():
    def __init__(self, clazz, line, raw, blame, sig_format = 1):
        self.clazz = clazz
        self.line = line
        self.raw = raw.strip(" {;")
        self.blame = blame

        if sig_format == 2:
            V2LineParser(raw).parse_into_method(self)
        elif sig_format == 1:
            # drop generics for now; may need multiple passes
            raw = re.sub("<[^<]+?>", "", raw)
            raw = re.sub("<[^<]+?>", "", raw)

            # handle each clause differently
            raw_prefix, raw_args, _, raw_throws = re.match(r"(.*?)\((.*?)\)( throws )?(.*?);$", raw).groups()

            # parse prefixes
            raw = re.split("[\s]+", raw_prefix)
            for r in ["", ";"]:
                while r in raw: raw.remove(r)
            self.split = list(raw)

            for r in ["method", "public", "protected", "static", "final", "deprecated", "abstract", "default", "operator", "synchronized"]:
                while r in raw: raw.remove(r)

            self.typ = raw[0]
            self.name = raw[1]

            # parse args
            self.args = []
            for arg in re.split(",\s*", raw_args):
                arg = re.split("\s", arg)
                # ignore annotations for now
                arg = [ a for a in arg if not a.startswith("@") ]
                if len(arg[0]) > 0:
                    self.args.append(arg[0])

            # parse throws
            self.throws = []
            for throw in re.split(",\s*", raw_throws):
                self.throws.append(throw)
        else:
            raise ValueError("Unknown signature format: " + sig_format)

        self.ident = "-".join((self.typ, self.name, "-".join(self.args)))

    def sig_matches(self, typ, name, args):
        return typ == self.typ and name == self.name and args == self.args

    def __hash__(self):
        return hash(self.raw)

    def __repr__(self):
        return self.raw


class Class():
    def __init__(self, pkg, line, raw, blame, sig_format = 1):
        self.pkg = pkg
        self.line = line
        self.raw = raw.strip(" {;")
        self.blame = blame
        self.ctors = []
        self.fields = []
        self.methods = []

        if sig_format == 2:
            V2LineParser(raw).parse_into_class(self)
        elif sig_format == 1:
            # drop generics for now; may need multiple passes
            raw = re.sub("<[^<]+?>", "", raw)
            raw = re.sub("<[^<]+?>", "", raw)

            raw = raw.split()
            self.split = list(raw)
            if "class" in raw:
                self.fullname = raw[raw.index("class")+1]
            elif "interface" in raw:
                self.fullname = raw[raw.index("interface")+1]
            elif "@interface" in raw:
                self.fullname = raw[raw.index("@interface")+1]
            else:
                raise ValueError("Funky class type %s" % (self.raw))

            if "extends" in raw:
                self.extends = raw[raw.index("extends")+1]
            else:
                self.extends = None

            if "implements" in raw:
                self.implements = raw[raw.index("implements")+1]
            else:
                self.implements = None
        else:
            raise ValueError("Unknown signature format: " + sig_format)

        self.fullname = self.pkg.name + "." + self.fullname
        self.fullname_path = self.fullname.split(".")

        if self.extends is not None:
            self.extends_path = self.extends.split(".")
        else:
            self.extends_path = []

        self.name = self.fullname[self.fullname.rindex(".")+1:]

    def merge_from(self, other):
        self.ctors.extend(other.ctors)
        self.fields.extend(other.fields)
        self.methods.extend(other.methods)

    def __hash__(self):
        return hash((self.raw, tuple(self.ctors), tuple(self.fields), tuple(self.methods)))

    def __repr__(self):
        return self.raw


class Package():
    NAME = re.compile("package(?: .*)? ([A-Za-z.]+)")

    def __init__(self, line, raw, blame):
        self.line = line
        self.raw = raw.strip(" {;")
        self.blame = blame

        self.name = Package.NAME.match(raw).group(1)
        self.name_path = self.name.split(".")

    def __repr__(self):
        return self.raw

class V2Tokenizer(object):
    __slots__ = ["raw"]

    DELIMITER = re.compile(r'\s+|[()@<>;,={}/"!?]|\[\]|\.\.\.')
    STRING_SPECIAL = re.compile(r'["\\]')

    def __init__(self, raw):
        self.raw = raw

    def tokenize(self):
        tokens = []
        current = 0
        raw = self.raw
        length = len(raw)

        while current < length:
            while current < length:
                start = current
                match = V2Tokenizer.DELIMITER.search(raw, start)
                if match is not None:
                    match_start = match.start()
                    if match_start == current:
                        end = match.end()
                    else:
                        end = match_start
                else:
                    end = length

                token = raw[start:end]
                current = end

                if token == "" or token[0] == " ":
                    continue
                else:
                    break

            if token == "@":
                if raw[start:start+11] == "@interface ":
                    current = start + 11
                    tokens.append("@interface")
                    continue
            elif token == '/':
                if raw[start:start+2] == "//":
                    current = length
                    continue
            elif token == '"':
                current, string_token = self.tokenize_string(raw, length, current)
                tokens.append(token + string_token)
                continue

            tokens.append(token)

        return tokens

    def tokenize_string(self, raw, length, current):
        start = current
        end = length
        while start < end:
            match = V2Tokenizer.STRING_SPECIAL.search(raw, start)
            if match:
                if match.group() == '"':
                    end = match.end()
                    break
                elif match.group() == '\\':
                    # ignore whatever is after the slash
                    start += 2
                else:
                    raise ValueError("Unexpected match: `%s`" % (match.group()))
            else:
                raise ValueError("Unexpected EOF tokenizing string: `%s`" % (raw[current - 1:],))

        token = raw[current:end]
        return end, token

class V2LineParser(object):
    __slots__ = ["tokenized", "current", "len"]

    FIELD_KINDS = ("field", "property", "enum_constant")
    MODIFIERS = set("public protected internal private abstract default static final transient volatile synchronized native operator sealed strictfp infix inline suspend vararg".split())
    JAVA_LANG_TYPES = set("AbstractMethodError AbstractStringBuilder Appendable ArithmeticException ArrayIndexOutOfBoundsException ArrayStoreException AssertionError AutoCloseable Boolean BootstrapMethodError Byte Character CharSequence Class ClassCastException ClassCircularityError ClassFormatError ClassLoader ClassNotFoundException Cloneable CloneNotSupportedException Comparable Compiler Deprecated Double Enum EnumConstantNotPresentException Error Exception ExceptionInInitializerError Float FunctionalInterface IllegalAccessError IllegalAccessException IllegalArgumentException IllegalMonitorStateException IllegalStateException IllegalThreadStateException IncompatibleClassChangeError IndexOutOfBoundsException InheritableThreadLocal InstantiationError InstantiationException Integer InternalError InterruptedException Iterable LinkageError Long Math NegativeArraySizeException NoClassDefFoundError NoSuchFieldError NoSuchFieldException NoSuchMethodError NoSuchMethodException NullPointerException Number NumberFormatException Object OutOfMemoryError Override Package package-info.java Process ProcessBuilder ProcessEnvironment ProcessImpl Readable ReflectiveOperationException Runnable Runtime RuntimeException RuntimePermission SafeVarargs SecurityException SecurityManager Short StackOverflowError StackTraceElement StrictMath String StringBuffer StringBuilder StringIndexOutOfBoundsException SuppressWarnings System Thread ThreadDeath ThreadGroup ThreadLocal Throwable TypeNotPresentException UNIXProcess UnknownError UnsatisfiedLinkError UnsupportedClassVersionError UnsupportedOperationException VerifyError VirtualMachineError Void".split())

    def __init__(self, raw):
        self.tokenized = V2Tokenizer(raw).tokenize()
        self.current = 0
        self.len = len(self.tokenized)

    def parse_into_method(self, method):
        method.split = []
        kind = self.parse_one_of("ctor", "method")
        method.split.append(kind)
        annotations = self.parse_annotations()
        method.split.extend(self.parse_modifiers())
        self.parse_matching_paren("<", ">")
        if "@Deprecated" in annotations:
            method.split.append("deprecated")
        if kind == "ctor":
            method.typ = "ctor"
        else:
            method.typ = self.parse_type()
            method.split.append(method.typ)
        method.name = self.parse_name()
        method.split.append(method.name)
        self.parse_token("(")
        method.args = self.parse_args()
        self.parse_token(")")
        method.throws = self.parse_throws()
        if "@interface" in method.clazz.split:
            self.parse_annotation_default()
        self.parse_token(";")
        self.parse_eof()

    def parse_into_class(self, clazz):
        clazz.split = []
        annotations = self.parse_annotations()
        if "@Deprecated" in annotations:
            clazz.split.append("deprecated")
        clazz.split.extend(self.parse_modifiers())
        kind = self.parse_one_of("class", "interface", "@interface", "enum")
        if kind == "enum":
            # enums are implicitly final
            clazz.split.append("final")
        clazz.split.append(kind)
        clazz.fullname = self.parse_name()
        self.parse_matching_paren("<", ">")
        extends = self.parse_extends()
        clazz.extends = extends[0] if extends else None
        implements = self.parse_implements()
        clazz.implements = implements[0] if implements else None
        # The checks assume that interfaces are always found in implements, which isn't true for
        # subinterfaces.
        if not implements and "interface" in clazz.split:
            clazz.implements = clazz.extends
        self.parse_token("{")
        self.parse_eof()

    def parse_into_field(self, field):
        kind = self.parse_one_of(*V2LineParser.FIELD_KINDS)
        field.split = [kind]
        annotations = self.parse_annotations()
        if "@Deprecated" in annotations:
            field.split.append("deprecated")
        field.split.extend(self.parse_modifiers())
        field.typ = self.parse_type()
        field.split.append(field.typ)
        field.name = self.parse_name()
        field.split.append(field.name)
        if self.parse_if("="):
            field.value = self.parse_value_stripped()
        else:
            field.value = None

        self.parse_token(";")
        self.parse_eof()

    def lookahead(self):
        return self.tokenized[self.current]

    def parse_one_of(self, *options):
        found = self.lookahead()
        if found not in options:
            raise ValueError("Parsing failed, expected one of `%s` but found `%s` in %s" % (options, found, repr(self.tokenized)))
        return self.parse_token()

    def parse_token(self, tok = None):
        found = self.lookahead()
        if tok is not None and found != tok:
            raise ValueError("Parsing failed, expected `%s` but found `%s` in %s" % (tok, found, repr(self.tokenized)))
        self.current += 1
        return found

    def eof(self):
        return self.current == self.len

    def parse_eof(self):
        if not self.eof():
            raise ValueError("Parsing failed, expected EOF, but %s has not been parsed in %s" % (self.tokenized[self.current:], self.tokenized))

    def parse_if(self, tok):
        if not self.eof() and self.lookahead() == tok:
            self.parse_token()
            return True
        return False

    def parse_annotations(self):
        ret = []
        while self.lookahead() == "@":
            ret.append(self.parse_annotation())
        return ret

    def parse_annotation(self):
        ret = self.parse_token("@") + self.parse_token()
        self.parse_matching_paren("(", ")")
        return ret

    def parse_matching_paren(self, open, close):
        start = self.current
        if not self.parse_if(open):
            return
        length = len(self.tokenized)
        count = 1
        while count > 0:
            if self.current == length:
                raise ValueError("Unexpected EOF looking for closing paren: `%s`" % (self.tokenized[start:],))
            t = self.parse_token()
            if t == open:
                count += 1
            elif t == close:
                count -= 1
        return self.tokenized[start:self.current]

    def parse_modifiers(self):
        ret = []
        while self.lookahead() in V2LineParser.MODIFIERS:
            ret.append(self.parse_token())
        return ret

    def parse_kotlin_nullability(self):
        t = self.lookahead()
        if t == "?" or t == "!":
            return self.parse_token()
        return None

    def parse_type(self):
        self.parse_annotations()
        type = self.parse_token()
        if type[-1] == '.':
            self.parse_annotations()
            type += self.parse_token()
        if type in V2LineParser.JAVA_LANG_TYPES:
            type = "java.lang." + type
        self.parse_matching_paren("<", ">")
        while True:
            t = self.lookahead()
            if t == "@":
                self.parse_annotation()
            elif t == "[]":
                type += self.parse_token()
            elif self.parse_kotlin_nullability() is not None:
                pass  # discard nullability for now
            else:
                break
        return type

    def parse_arg_type(self):
        type = self.parse_type()
        if self.parse_if("..."):
            type += "..."
        self.parse_kotlin_nullability() # discard nullability for now
        return type

    def parse_name(self):
        return self.parse_token()

    def parse_args(self):
        args = []
        if self.lookahead() == ")":
            return args

        while True:
            args.append(self.parse_arg())
            if self.lookahead() == ")":
                return args
            self.parse_token(",")

    def parse_arg(self):
        self.parse_if("vararg")  # kotlin vararg
        self.parse_annotations()
        type = self.parse_arg_type()
        l = self.lookahead()
        if l != "," and l != ")":
            if self.lookahead() != '=':
                self.parse_token()  # kotlin argument name
            if self.parse_if('='): # kotlin default value
                self.parse_expression()
        return type

    def parse_expression(self):
        while not self.lookahead() in [')', ',', ';']:
            (self.parse_matching_paren('(', ')') or
            self.parse_matching_paren('{', '}') or
            self.parse_token())

    def parse_throws(self):
        ret = []
        if self.parse_if("throws"):
            ret.append(self.parse_type())
            while self.parse_if(","):
                ret.append(self.parse_type())
        return ret

    def parse_extends(self):
        if self.parse_if("extends"):
            return self.parse_space_delimited_type_list()
        return []

    def parse_implements(self):
        if self.parse_if("implements"):
            return self.parse_space_delimited_type_list()
        return []

    def parse_space_delimited_type_list(self, terminals = ["implements", "{"]):
        types = []
        while True:
            types.append(self.parse_type())
            if self.lookahead() in terminals:
                return types

    def parse_annotation_default(self):
        if self.parse_if("default"):
            self.parse_expression()

    def parse_value(self):
        if self.lookahead() == "{":
            return " ".join(self.parse_matching_paren("{", "}"))
        elif self.lookahead() == "(":
            return " ".join(self.parse_matching_paren("(", ")"))
        else:
            return self.parse_token()

    def parse_value_stripped(self):
        value = self.parse_value()
        if value[0] == '"':
            return value[1:-1]
        return value


def _parse_stream(f, clazz_cb=None, base_f=None, out_classes_with_base=None,
                  in_classes_with_base=[]):
    api = {}
    in_classes_with_base = _retry_iterator(in_classes_with_base)

    if base_f:
        base_classes = _retry_iterator(_parse_stream_to_generator(base_f))
    else:
        base_classes = []

    def handle_class(clazz):
        if clazz_cb:
            clazz_cb(clazz)
        else: # In callback mode, don't keep track of the full API
            api[clazz.fullname] = clazz

    def handle_missed_classes_with_base(clazz):
        for c in _yield_until_matching_class(in_classes_with_base, clazz):
            base_class = _skip_to_matching_class(base_classes, c)
            if base_class:
                handle_class(base_class)

    for clazz in _parse_stream_to_generator(f):
        # Before looking at clazz, let's see if there's some classes that were not present, but
        # may have an entry in the base stream.
        handle_missed_classes_with_base(clazz)

        base_class = _skip_to_matching_class(base_classes, clazz)
        if base_class:
            clazz.merge_from(base_class)
            if out_classes_with_base is not None:
                out_classes_with_base.append(clazz)
        handle_class(clazz)

    handle_missed_classes_with_base(None)

    return api

def _parse_stream_to_generator(f):
    line = 0
    pkg = None
    clazz = None
    blame = None
    sig_format = 1

    re_blame = re.compile("^([a-z0-9]{7,}) \(<([^>]+)>.+?\) (.+?)$")

    field_prefixes = map(lambda kind: "    %s" % (kind,), V2LineParser.FIELD_KINDS)
    def startsWithFieldPrefix(raw):
        for prefix in field_prefixes:
            if raw.startswith(prefix):
                return True
        return False

    for raw in f:
        line += 1
        raw = raw.rstrip()
        match = re_blame.match(raw)
        if match is not None:
            blame = match.groups()[0:2]
            raw = match.groups()[2]
        else:
            blame = None

        if line == 1 and raw == "// Signature format: 2.0":
            sig_format = 2
        elif raw.startswith("package"):
            pkg = Package(line, raw, blame)
        elif raw.startswith("  ") and raw.endswith("{"):
            clazz = Class(pkg, line, raw, blame, sig_format=sig_format)
        elif raw.startswith("    ctor"):
            clazz.ctors.append(Method(clazz, line, raw, blame, sig_format=sig_format))
        elif raw.startswith("    method"):
            clazz.methods.append(Method(clazz, line, raw, blame, sig_format=sig_format))
        elif startsWithFieldPrefix(raw):
            clazz.fields.append(Field(clazz, line, raw, blame, sig_format=sig_format))
        elif raw.startswith("  }") and clazz:
            yield clazz

def _retry_iterator(it):
    """Wraps an iterator, such that calling send(True) on it will redeliver the same element"""
    for e in it:
        while True:
            retry = yield e
            if not retry:
                break
            # send() was called, asking us to redeliver clazz on next(). Still need to yield
            # a dummy value to the send() first though.
            if (yield "Returning clazz on next()"):
                raise TypeError("send() must be followed by next(), not send()")

def _skip_to_matching_class(classes, needle):
    """Takes a classes iterator and consumes entries until it returns the class we're looking for

    This relies on classes being sorted by package and class name."""

    for clazz in classes:
        if clazz.pkg.name < needle.pkg.name:
            # We haven't reached the right package yet
            continue
        if clazz.pkg.name == needle.pkg.name and clazz.fullname < needle.fullname:
            # We're in the right package, but not the right class yet
            continue
        if clazz.fullname == needle.fullname:
            return clazz
        # We ran past the right class. Send it back into the generator, then report failure.
        classes.send(clazz)
        return None

def _yield_until_matching_class(classes, needle):
    """Takes a class iterator and yields entries it until it reaches the class we're looking for.

    This relies on classes being sorted by package and class name."""

    for clazz in classes:
        if needle is None:
            yield clazz
        elif clazz.pkg.name < needle.pkg.name:
            # We haven't reached the right package yet
            yield clazz
        elif clazz.pkg.name == needle.pkg.name and clazz.fullname < needle.fullname:
            # We're in the right package, but not the right class yet
            yield clazz
        elif clazz.fullname == needle.fullname:
            # Class found, abort.
            return
        else:
            # We ran past the right class. Send it back into the iterator, then abort.
            classes.send(clazz)
            return

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

    sig = "%s-%s-%s" % (clazz.fullname, detail.ident if detail else None, msg)
    sig = sig.replace(" deprecated ", " ")

    failures[sig] = Failure(sig, clazz, detail, error, rule, msg)


def warn(clazz, detail, rule, msg):
    _fail(clazz, detail, False, rule, msg)

def error(clazz, detail, rule, msg):
    _fail(clazz, detail, True, rule, msg)


noticed = {}

def notice(clazz):
    global noticed

    noticed[clazz.fullname] = hash(clazz)


def verify_constants(clazz):
    """All static final constants must be FOO_NAME style."""
    if re.match("android\.R\.[a-z]+", clazz.fullname): return
    if clazz.fullname.startswith("android.os.Build"): return
    if clazz.fullname == "android.system.OsConstants": return

    req = ["java.lang.String","byte","short","int","long","float","double","boolean","char"]
    for f in clazz.fields:
        if "static" in f.split and "final" in f.split:
            if re.match("[A-Z0-9_]+", f.name) is None:
                error(clazz, f, "C2", "Constant field names must be FOO_NAME")
            if f.typ != "java.lang.String":
                if f.name.startswith("MIN_") or f.name.startswith("MAX_"):
                    warn(clazz, f, "C8", "If min/max could change in future, make them dynamic methods")
            if f.typ in req and f.value is None:
                error(clazz, f, None, "All constants must be defined at compile time")


def verify_enums(clazz):
    """Enums are bad, mmkay?"""
    if clazz.extends == "java.lang.Enum" or "enum" in clazz.split:
        error(clazz, None, "F5", "Enums are not allowed")


def verify_class_names(clazz):
    """Try catching malformed class names like myMtp or MTPUser."""
    if clazz.fullname.startswith("android.opengl"): return
    if clazz.fullname.startswith("android.renderscript"): return
    if re.match("android\.R\.[a-z]+", clazz.fullname): return

    if re.search("[A-Z]{2,}", clazz.name) is not None:
        warn(clazz, None, "S1", "Class names with acronyms should be Mtp not MTP")
    if re.match("[^A-Z]", clazz.name):
        error(clazz, None, "S1", "Class must start with uppercase char")
    if clazz.name.endswith("Impl"):
        error(clazz, None, None, "Don't expose your implementation details")


def verify_method_names(clazz):
    """Try catching malformed method names, like Foo() or getMTU()."""
    if clazz.fullname.startswith("android.opengl"): return
    if clazz.fullname.startswith("android.renderscript"): return
    if clazz.fullname == "android.system.OsConstants": return

    for m in clazz.methods:
        if re.search("[A-Z]{2,}", m.name) is not None:
            warn(clazz, m, "S1", "Method names with acronyms should be getMtu() instead of getMTU()")
        if re.match("[^a-z]", m.name):
            error(clazz, m, "S1", "Method name must start with lowercase char")


def verify_callbacks(clazz):
    """Verify Callback classes.
    All callback classes must be abstract.
    All methods must follow onFoo() naming style."""
    if clazz.fullname == "android.speech.tts.SynthesisCallback": return

    if clazz.name.endswith("Callbacks"):
        error(clazz, None, "L1", "Callback class names should be singular")
    if clazz.name.endswith("Observer"):
        warn(clazz, None, "L1", "Class should be named FooCallback")

    if clazz.name.endswith("Callback"):
        if "interface" in clazz.split:
            error(clazz, None, "CL3", "Callbacks must be abstract class to enable extension in future API levels")

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
        if "abstract" in clazz.split and "class" in clazz.split:
            error(clazz, None, "L1", "Listeners should be an interface, or otherwise renamed Callback")

        for m in clazz.methods:
            if not re.match("on[A-Z][a-z]*", m.name):
                error(clazz, m, "L1", "Listener method names must be onFoo() style")

        if len(clazz.methods) == 1 and clazz.name.startswith("On"):
            m = clazz.methods[0]
            if (m.name + "Listener").lower() != clazz.name.lower():
                error(clazz, m, "L1", "Single listener method name must match class name")


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
        if "INTERACTION" in f.name: continue

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
                        error(clazz, f, "C4", "Inconsistent action value; expected '%s'" % (expected))


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
                        error(clazz, f, "C4", "Inconsistent extra value; expected '%s'" % (expected))


def verify_equals(clazz):
    """Verify that equals() and hashCode() must be overridden together."""
    eq = False
    hc = False
    for m in clazz.methods:
        if "static" in m.split: continue
        if m.sig_matches("boolean", "equals", ["java.lang.Object"]): eq = True
        if m.sig_matches("int", "hashCode", []): hc = True
    if eq != hc:
        error(clazz, None, "M8", "Must override both equals and hashCode; missing one")


def verify_parcelable(clazz):
    """Verify that Parcelable objects aren't hiding required bits."""
    if clazz.implements == "android.os.Parcelable":
        creator = [ i for i in clazz.fields if i.name == "CREATOR" ]
        write = [ i for i in clazz.methods if i.name == "writeToParcel" ]
        describe = [ i for i in clazz.methods if i.name == "describeContents" ]

        if len(creator) == 0 or len(write) == 0 or len(describe) == 0:
            error(clazz, None, "FW3", "Parcelable requires CREATOR, writeToParcel, and describeContents; missing one")

        if "final" not in clazz.split:
            error(clazz, None, "FW8", "Parcelable classes must be final")

        for c in clazz.ctors:
            if c.args == ["android.os.Parcel"]:
                error(clazz, c, "FW3", "Parcelable inflation is exposed through CREATOR, not raw constructors")


def verify_protected(clazz):
    """Verify that no protected methods or fields are allowed."""
    for m in clazz.methods:
        if m.name == "finalize": continue
        if "protected" in m.split:
            error(clazz, m, "M7", "Protected methods not allowed; must be public")
    for f in clazz.fields:
        if "protected" in f.split:
            error(clazz, f, "M7", "Protected fields not allowed; must be public")


def verify_fields(clazz):
    """Verify that all exposed fields are final.
    Exposed fields must follow myName style.
    Catch internal mFoo objects being exposed."""

    IGNORE_BARE_FIELDS = [
        "android.app.ActivityManager.RecentTaskInfo",
        "android.app.Notification",
        "android.content.pm.ActivityInfo",
        "android.content.pm.ApplicationInfo",
        "android.content.pm.ComponentInfo",
        "android.content.pm.ResolveInfo",
        "android.content.pm.FeatureGroupInfo",
        "android.content.pm.InstrumentationInfo",
        "android.content.pm.PackageInfo",
        "android.content.pm.PackageItemInfo",
        "android.content.res.Configuration",
        "android.graphics.BitmapFactory.Options",
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

        if "static" not in f.split and "property" not in f.split:
            if not re.match("[a-z]([a-zA-Z]+)?", f.name):
                error(clazz, f, "S1", "Non-static fields must be named using myField style")

        if re.match("[ms][A-Z]", f.name):
            error(clazz, f, "F1", "Internal objects must not be exposed")

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
            error(clazz, m, "M5", "Internal locks must not be exposed")


def verify_intent_builder(clazz):
    """Verify that Intent builders are createFooIntent() style."""
    if clazz.name == "Intent": return

    for m in clazz.methods:
        if m.typ == "android.content.Intent":
            if m.name.startswith("create") and m.name.endswith("Intent"):
                pass
            else:
                warn(clazz, m, "FW1", "Methods creating an Intent should be named createFooIntent()")


def verify_helper_classes(clazz):
    """Verify that helper classes are named consistently with what they extend.
    All developer extendable methods should be named onFoo()."""
    test_methods = False
    if clazz.extends == "android.app.Service":
        test_methods = True
        if not clazz.name.endswith("Service"):
            error(clazz, None, "CL4", "Inconsistent class name; should be FooService")

        found = False
        for f in clazz.fields:
            if f.name == "SERVICE_INTERFACE":
                found = True
                if f.value != clazz.fullname:
                    error(clazz, f, "C4", "Inconsistent interface constant; expected '%s'" % (clazz.fullname))

    if clazz.extends == "android.content.ContentProvider":
        test_methods = True
        if not clazz.name.endswith("Provider"):
            error(clazz, None, "CL4", "Inconsistent class name; should be FooProvider")

        found = False
        for f in clazz.fields:
            if f.name == "PROVIDER_INTERFACE":
                found = True
                if f.value != clazz.fullname:
                    error(clazz, f, "C4", "Inconsistent interface constant; expected '%s'" % (clazz.fullname))

    if clazz.extends == "android.content.BroadcastReceiver":
        test_methods = True
        if not clazz.name.endswith("Receiver"):
            error(clazz, None, "CL4", "Inconsistent class name; should be FooReceiver")

    if clazz.extends == "android.app.Activity":
        test_methods = True
        if not clazz.name.endswith("Activity"):
            error(clazz, None, "CL4", "Inconsistent class name; should be FooActivity")

    if test_methods:
        for m in clazz.methods:
            if "final" in m.split: continue
            if not re.match("on[A-Z]", m.name):
                if "abstract" in m.split:
                    warn(clazz, m, None, "Methods implemented by developers should be named onFoo()")
                else:
                    warn(clazz, m, None, "If implemented by developer, should be named onFoo(); otherwise consider marking final")


def verify_builder(clazz):
    """Verify builder classes.
    Methods should return the builder to enable chaining."""
    if clazz.extends: return
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
            warn(clazz, m, None, "Builder methods names should use setFoo() style")

        if m.name.startswith("set"):
            if not m.typ.endswith(clazz.fullname):
                warn(clazz, m, "M4", "Methods must return the builder object")

    if not has_build:
        warn(clazz, None, None, "Missing build() method")


def verify_aidl(clazz):
    """Catch people exposing raw AIDL."""
    if clazz.extends == "android.os.Binder" or clazz.implements == "android.os.IInterface":
        error(clazz, None, None, "Raw AIDL interfaces must not be exposed")


def verify_internal(clazz):
    """Catch people exposing internal classes."""
    if clazz.pkg.name.startswith("com.android"):
        error(clazz, None, None, "Internal classes must not be exposed")

def layering_build_ranking(ranking_list):
    r = {}
    for rank, ps in enumerate(ranking_list):
        if not isinstance(ps, list):
            ps = [ps]
        for p in ps:
            rs = r
            for n in p.split('.'):
                if n not in rs:
                    rs[n] = {}
                rs = rs[n]
            rs['-rank'] = rank
    return r

LAYERING_PACKAGE_RANKING = layering_build_ranking([
    ["android.service","android.accessibilityservice","android.inputmethodservice","android.printservice","android.appwidget","android.webkit","android.preference","android.gesture","android.print"],
    "android.app",
    "android.widget",
    "android.view",
    "android.animation",
    "android.provider",
    ["android.content","android.graphics.drawable"],
    "android.database",
    "android.text",
    "android.graphics",
    "android.os",
    "android.util"
])

def verify_layering(clazz):
    """Catch package layering violations.
    For example, something in android.os depending on android.app."""

    def rank(p):
        r = None
        l = LAYERING_PACKAGE_RANKING
        for n in p.split('.'):
            if n in l:
                l = l[n]
                if '-rank' in l:
                    r = l['-rank']
            else:
                break
        return r

    cr = rank(clazz.pkg.name)
    if cr is None: return

    for f in clazz.fields:
        ir = rank(f.typ)
        if ir is not None and ir < cr:
            warn(clazz, f, "FW6", "Field type violates package layering")

    for m in itertools.chain(clazz.methods, clazz.ctors):
        ir = rank(m.typ)
        if ir is not None and ir < cr:
            warn(clazz, m, "FW6", "Method return type violates package layering")
        for arg in m.args:
            ir = rank(arg)
            if ir is not None and ir < cr:
                warn(clazz, m, "FW6", "Method argument type violates package layering")


def verify_boolean(clazz):
    """Verifies that boolean accessors are named correctly.
    For example, hasFoo() and setHasFoo()."""

    def is_get(m): return len(m.args) == 0 and m.typ == "boolean"
    def is_set(m): return len(m.args) == 1 and m.args[0] == "boolean"

    gets = [ m for m in clazz.methods if is_get(m) ]
    sets = [ m for m in clazz.methods if is_set(m) ]

    def error_if_exists(methods, trigger, expected, actual):
        for m in methods:
            if m.name == actual:
                error(clazz, m, "M6", "Symmetric method for %s must be named %s" % (trigger, expected))

    for m in clazz.methods:
        if is_get(m):
            if re.match("is[A-Z]", m.name):
                target = m.name[2:]
                expected = "setIs" + target
                error_if_exists(sets, m.name, expected, "setHas" + target)
            elif re.match("has[A-Z]", m.name):
                target = m.name[3:]
                expected = "setHas" + target
                error_if_exists(sets, m.name, expected, "setIs" + target)
                error_if_exists(sets, m.name, expected, "set" + target)
            elif re.match("get[A-Z]", m.name):
                target = m.name[3:]
                expected = "set" + target
                error_if_exists(sets, m.name, expected, "setIs" + target)
                error_if_exists(sets, m.name, expected, "setHas" + target)

        if is_set(m):
            if re.match("set[A-Z]", m.name):
                target = m.name[3:]
                expected = "get" + target
                error_if_exists(sets, m.name, expected, "is" + target)
                error_if_exists(sets, m.name, expected, "has" + target)


def verify_collections(clazz):
    """Verifies that collection types are interfaces."""
    if clazz.fullname == "android.os.Bundle": return

    bad = ["java.util.Vector", "java.util.LinkedList", "java.util.ArrayList", "java.util.Stack",
           "java.util.HashMap", "java.util.HashSet", "android.util.ArraySet", "android.util.ArrayMap"]
    for m in clazz.methods:
        if m.typ in bad:
            error(clazz, m, "CL2", "Return type is concrete collection; must be higher-level interface")
        for arg in m.args:
            if arg in bad:
                error(clazz, m, "CL2", "Argument is concrete collection; must be higher-level interface")


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
                warn(clazz, f, "C1", "Found overlapping flag constant value")
            known[scope] |= val


def verify_exception(clazz):
    """Verifies that methods don't throw generic exceptions."""
    for m in clazz.methods:
        for t in m.throws:
            if t in ["java.lang.Exception", "java.lang.Throwable", "java.lang.Error"]:
                error(clazz, m, "S1", "Methods must not throw generic exceptions")

            if t in ["android.os.RemoteException"]:
                if clazz.name == "android.content.ContentProviderClient": continue
                if clazz.name == "android.os.Binder": continue
                if clazz.name == "android.os.IBinder": continue

                error(clazz, m, "FW9", "Methods calling into system server should rethrow RemoteException as RuntimeException")

            if len(m.args) == 0 and t in ["java.lang.IllegalArgumentException", "java.lang.NullPointerException"]:
                warn(clazz, m, "S1", "Methods taking no arguments should throw IllegalStateException")

GOOGLE_IGNORECASE = re.compile("google", re.IGNORECASE)

def verify_google(clazz):
    """Verifies that APIs never reference Google."""

    if GOOGLE_IGNORECASE.search(clazz.raw) is not None:
        error(clazz, None, None, "Must never reference Google")

    for test in clazz.ctors, clazz.fields, clazz.methods:
        for t in test:
            if GOOGLE_IGNORECASE.search(t.raw) is not None:
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
        error(clazz, c, None, "Managers must always be obtained from Context; no direct constructors")

    for m in clazz.methods:
        if m.typ == clazz.fullname:
            error(clazz, m, None, "Managers must always be obtained from Context")


def verify_boxed(clazz):
    """Verifies that methods avoid boxed primitives."""

    boxed = ["java.lang.Number","java.lang.Byte","java.lang.Double","java.lang.Float","java.lang.Integer","java.lang.Long","java.lang.Short"]

    for c in clazz.ctors:
        for arg in c.args:
            if arg in boxed:
                error(clazz, c, "M11", "Must avoid boxed primitives")

    for f in clazz.fields:
        if f.typ in boxed:
            error(clazz, f, "M11", "Must avoid boxed primitives")

    for m in clazz.methods:
        if m.typ in boxed:
            error(clazz, m, "M11", "Must avoid boxed primitives")
        for arg in m.args:
            if arg in boxed:
                error(clazz, m, "M11", "Must avoid boxed primitives")


def verify_static_utils(clazz):
    """Verifies that helper classes can't be constructed."""
    if clazz.fullname.startswith("android.opengl"): return
    if clazz.fullname.startswith("android.R"): return

    # Only care about classes with default constructors
    if len(clazz.ctors) == 1 and len(clazz.ctors[0].args) == 0:
        test = []
        test.extend(clazz.fields)
        test.extend(clazz.methods)

        if len(test) == 0: return
        for t in test:
            if "static" not in t.split:
                return

        error(clazz, None, None, "Fully-static utility classes must not have constructor")


def verify_overload_args(clazz):
    """Verifies that method overloads add new arguments at the end."""
    if clazz.fullname.startswith("android.opengl"): return

    overloads = collections.defaultdict(list)
    for m in clazz.methods:
        if "deprecated" in m.split: continue
        overloads[m.name].append(m)

    for name, methods in overloads.items():
        if len(methods) <= 1: continue

        # Look for arguments common across all overloads
        def cluster(args):
            count = collections.defaultdict(int)
            res = set()
            for i in range(len(args)):
                a = args[i]
                res.add("%s#%d" % (a, count[a]))
                count[a] += 1
            return res

        common_args = cluster(methods[0].args)
        for m in methods:
            common_args = common_args & cluster(m.args)

        if len(common_args) == 0: continue

        # Require that all common arguments are present at start of signature
        locked_sig = None
        for m in methods:
            sig = m.args[0:len(common_args)]
            if not common_args.issubset(cluster(sig)):
                warn(clazz, m, "M2", "Expected common arguments [%s] at beginning of overloaded method" % (", ".join(common_args)))
            elif not locked_sig:
                locked_sig = sig
            elif locked_sig != sig:
                error(clazz, m, "M2", "Expected consistent argument ordering between overloads: %s..." % (", ".join(locked_sig)))


def verify_callback_handlers(clazz):
    """Verifies that methods adding listener/callback have overload
    for specifying delivery thread."""

    # Ignore UI packages which assume main thread
    skip = [
        "animation",
        "view",
        "graphics",
        "transition",
        "widget",
        "webkit",
    ]
    for s in skip:
        if s in clazz.pkg.name_path: return
        if s in clazz.extends_path: return

    # Ignore UI classes which assume main thread
    if "app" in clazz.pkg.name_path or "app" in clazz.extends_path:
        for s in ["ActionBar","Dialog","Application","Activity","Fragment","Loader"]:
            if s in clazz.fullname: return
    if "content" in clazz.pkg.name_path or "content" in clazz.extends_path:
        for s in ["Loader"]:
            if s in clazz.fullname: return

    found = {}
    by_name = collections.defaultdict(list)
    examine = clazz.ctors + clazz.methods
    for m in examine:
        if m.name.startswith("unregister"): continue
        if m.name.startswith("remove"): continue
        if re.match("on[A-Z]+", m.name): continue

        by_name[m.name].append(m)

        for a in m.args:
            if a.endswith("Listener") or a.endswith("Callback") or a.endswith("Callbacks"):
                found[m.name] = m

    for f in found.values():
        takes_handler = False
        takes_exec = False
        for m in by_name[f.name]:
            if "android.os.Handler" in m.args:
                takes_handler = True
            if "java.util.concurrent.Executor" in m.args:
                takes_exec = True
        if not takes_exec:
            warn(clazz, f, "L1", "Registration methods should have overload that accepts delivery Executor")


def verify_context_first(clazz):
    """Verifies that methods accepting a Context keep it the first argument."""
    examine = clazz.ctors + clazz.methods
    for m in examine:
        if len(m.args) > 1 and m.args[0] != "android.content.Context":
            if "android.content.Context" in m.args[1:]:
                error(clazz, m, "M3", "Context is distinct, so it must be the first argument")
        if len(m.args) > 1 and m.args[0] != "android.content.ContentResolver":
            if "android.content.ContentResolver" in m.args[1:]:
                error(clazz, m, "M3", "ContentResolver is distinct, so it must be the first argument")


def verify_listener_last(clazz):
    """Verifies that methods accepting a Listener or Callback keep them as last arguments."""
    examine = clazz.ctors + clazz.methods
    for m in examine:
        if "Listener" in m.name or "Callback" in m.name: continue
        found = False
        for a in m.args:
            if a.endswith("Callback") or a.endswith("Callbacks") or a.endswith("Listener"):
                found = True
            elif found:
                warn(clazz, m, "M3", "Listeners should always be at end of argument list")


def verify_resource_names(clazz):
    """Verifies that resource names have consistent case."""
    if not re.match("android\.R\.[a-z]+", clazz.fullname): return

    # Resources defined by files are foo_bar_baz
    if clazz.name in ["anim","animator","color","dimen","drawable","interpolator","layout","transition","menu","mipmap","string","plurals","raw","xml"]:
        for f in clazz.fields:
            if re.match("config_[a-z][a-zA-Z1-9]*$", f.name): continue
            if f.name.startswith("config_"):
                error(clazz, f, None, "Expected config name to be config_fooBarBaz style")

            if re.match("[a-z1-9_]+$", f.name): continue
            error(clazz, f, None, "Expected resource name in this class to be foo_bar_baz style")

    # Resources defined inside files are fooBarBaz
    if clazz.name in ["array","attr","id","bool","fraction","integer"]:
        for f in clazz.fields:
            if re.match("config_[a-z][a-zA-Z1-9]*$", f.name): continue
            if re.match("layout_[a-z][a-zA-Z1-9]*$", f.name): continue
            if re.match("state_[a-z_]*$", f.name): continue

            if re.match("[a-z][a-zA-Z1-9]*$", f.name): continue
            error(clazz, f, "C7", "Expected resource name in this class to be fooBarBaz style")

    # Styles are FooBar_Baz
    if clazz.name in ["style"]:
        for f in clazz.fields:
            if re.match("[A-Z][A-Za-z1-9]+(_[A-Z][A-Za-z1-9]+?)*$", f.name): continue
            error(clazz, f, "C7", "Expected resource name in this class to be FooBar_Baz style")


def verify_files(clazz):
    """Verifies that methods accepting File also accept streams."""

    has_file = set()
    has_stream = set()

    test = []
    test.extend(clazz.ctors)
    test.extend(clazz.methods)

    for m in test:
        if "java.io.File" in m.args:
            has_file.add(m)
        if "java.io.FileDescriptor" in m.args or "android.os.ParcelFileDescriptor" in m.args or "java.io.InputStream" in m.args or "java.io.OutputStream" in m.args:
            has_stream.add(m.name)

    for m in has_file:
        if m.name not in has_stream:
            warn(clazz, m, "M10", "Methods accepting File should also accept FileDescriptor or streams")


def verify_manager_list(clazz):
    """Verifies that managers return List<? extends Parcelable> instead of arrays."""

    if not clazz.name.endswith("Manager"): return

    for m in clazz.methods:
        if m.typ.startswith("android.") and m.typ.endswith("[]"):
            warn(clazz, m, None, "Methods should return List<? extends Parcelable> instead of Parcelable[] to support ParceledListSlice under the hood")


def verify_abstract_inner(clazz):
    """Verifies that abstract inner classes are static."""

    if re.match(".+?\.[A-Z][^\.]+\.[A-Z]", clazz.fullname):
        if "abstract" in clazz.split and "static" not in clazz.split:
            warn(clazz, None, None, "Abstract inner classes should be static to improve testability")


def verify_runtime_exceptions(clazz):
    """Verifies that runtime exceptions aren't listed in throws."""

    banned = [
        "java.lang.NullPointerException",
        "java.lang.ClassCastException",
        "java.lang.IndexOutOfBoundsException",
        "java.lang.reflect.UndeclaredThrowableException",
        "java.lang.reflect.MalformedParametersException",
        "java.lang.reflect.MalformedParameterizedTypeException",
        "java.lang.invoke.WrongMethodTypeException",
        "java.lang.EnumConstantNotPresentException",
        "java.lang.IllegalMonitorStateException",
        "java.lang.SecurityException",
        "java.lang.UnsupportedOperationException",
        "java.lang.annotation.AnnotationTypeMismatchException",
        "java.lang.annotation.IncompleteAnnotationException",
        "java.lang.TypeNotPresentException",
        "java.lang.IllegalStateException",
        "java.lang.ArithmeticException",
        "java.lang.IllegalArgumentException",
        "java.lang.ArrayStoreException",
        "java.lang.NegativeArraySizeException",
        "java.util.MissingResourceException",
        "java.util.EmptyStackException",
        "java.util.concurrent.CompletionException",
        "java.util.concurrent.RejectedExecutionException",
        "java.util.IllformedLocaleException",
        "java.util.ConcurrentModificationException",
        "java.util.NoSuchElementException",
        "java.io.UncheckedIOException",
        "java.time.DateTimeException",
        "java.security.ProviderException",
        "java.nio.BufferUnderflowException",
        "java.nio.BufferOverflowException",
    ]

    examine = clazz.ctors + clazz.methods
    for m in examine:
        for t in m.throws:
            if t in banned:
                error(clazz, m, None, "Methods must not mention RuntimeException subclasses in throws clauses")


def verify_error(clazz):
    """Verifies that we always use Exception instead of Error."""
    if not clazz.extends: return
    if clazz.extends.endswith("Error"):
        error(clazz, None, None, "Trouble must be reported through an Exception, not Error")
    if clazz.extends.endswith("Exception") and not clazz.name.endswith("Exception"):
        error(clazz, None, None, "Exceptions must be named FooException")


def verify_units(clazz):
    """Verifies that we use consistent naming for units."""

    # If we find K, recommend replacing with V
    bad = {
        "Ns": "Nanos",
        "Ms": "Millis or Micros",
        "Sec": "Seconds", "Secs": "Seconds",
        "Hr": "Hours", "Hrs": "Hours",
        "Mo": "Months", "Mos": "Months",
        "Yr": "Years", "Yrs": "Years",
        "Byte": "Bytes", "Space": "Bytes",
    }

    for m in clazz.methods:
        if m.typ not in ["short","int","long"]: continue
        for k, v in bad.iteritems():
            if m.name.endswith(k):
                error(clazz, m, None, "Expected method name units to be " + v)
        if m.name.endswith("Nanos") or m.name.endswith("Micros"):
            warn(clazz, m, None, "Returned time values are strongly encouraged to be in milliseconds unless you need the extra precision")
        if m.name.endswith("Seconds"):
            error(clazz, m, None, "Returned time values must be in milliseconds")

    for m in clazz.methods:
        typ = m.typ
        if typ == "void":
            if len(m.args) != 1: continue
            typ = m.args[0]

        if m.name.endswith("Fraction") and typ != "float":
            error(clazz, m, None, "Fractions must use floats")
        if m.name.endswith("Percentage") and typ != "int":
            error(clazz, m, None, "Percentage must use ints")


def verify_closable(clazz):
    """Verifies that classes are AutoClosable."""
    if clazz.implements == "java.lang.AutoCloseable": return
    if clazz.implements == "java.io.Closeable": return

    for m in clazz.methods:
        if len(m.args) > 0: continue
        if m.name in ["close","release","destroy","finish","finalize","disconnect","shutdown","stop","free","quit"]:
            warn(clazz, m, None, "Classes that release resources should implement AutoClosable and CloseGuard")
            return


def verify_member_name_not_kotlin_keyword(clazz):
    """Prevent method names which are keywords in Kotlin."""

    # https://kotlinlang.org/docs/reference/keyword-reference.html#hard-keywords
    # This list does not include Java keywords as those are already impossible to use.
    keywords = [
        'as',
        'fun',
        'in',
        'is',
        'object',
        'typealias',
        'val',
        'var',
        'when',
    ]

    for m in clazz.methods:
        if m.name in keywords:
            error(clazz, m, None, "Method name must not be a Kotlin keyword")
    for f in clazz.fields:
        if f.name in keywords:
            error(clazz, f, None, "Field name must not be a Kotlin keyword")


def verify_method_name_not_kotlin_operator(clazz):
    """Warn about method names which become operators in Kotlin."""

    binary = set()

    def unique_binary_op(m, op):
        if op in binary:
            error(clazz, m, None, "Only one of '{0}' and '{0}Assign' methods should be present for Kotlin".format(op))
        binary.add(op)

    for m in clazz.methods:
        if 'static' in m.split or 'operator' in m.split:
            continue

        # https://kotlinlang.org/docs/reference/operator-overloading.html#unary-prefix-operators
        if m.name in ['unaryPlus', 'unaryMinus', 'not'] and len(m.args) == 0:
            warn(clazz, m, None, "Method can be invoked as a unary operator from Kotlin")

        # https://kotlinlang.org/docs/reference/operator-overloading.html#increments-and-decrements
        if m.name in ['inc', 'dec'] and len(m.args) == 0 and m.typ != 'void':
            # This only applies if the return type is the same or a subtype of the enclosing class, but we have no
            # practical way of checking that relationship here.
            warn(clazz, m, None, "Method can be invoked as a pre/postfix inc/decrement operator from Kotlin")

        # https://kotlinlang.org/docs/reference/operator-overloading.html#arithmetic
        if m.name in ['plus', 'minus', 'times', 'div', 'rem', 'mod', 'rangeTo'] and len(m.args) == 1:
            warn(clazz, m, None, "Method can be invoked as a binary operator from Kotlin")
            unique_binary_op(m, m.name)

        # https://kotlinlang.org/docs/reference/operator-overloading.html#in
        if m.name == 'contains' and len(m.args) == 1 and m.typ == 'boolean':
            warn(clazz, m, None, "Method can be invoked as a 'in' operator from Kotlin")

        # https://kotlinlang.org/docs/reference/operator-overloading.html#indexed
        if (m.name == 'get' and len(m.args) > 0) or (m.name == 'set' and len(m.args) > 1):
            warn(clazz, m, None, "Method can be invoked with an indexing operator from Kotlin")

        # https://kotlinlang.org/docs/reference/operator-overloading.html#invoke
        if m.name == 'invoke':
            warn(clazz, m, None, "Method can be invoked with function call syntax from Kotlin")

        # https://kotlinlang.org/docs/reference/operator-overloading.html#assignments
        if m.name in ['plusAssign', 'minusAssign', 'timesAssign', 'divAssign', 'remAssign', 'modAssign'] \
                and len(m.args) == 1 \
                and m.typ == 'void':
            warn(clazz, m, None, "Method can be invoked as a compound assignment operator from Kotlin")
            unique_binary_op(m, m.name[:-6])  # Remove 'Assign' suffix


def verify_collections_over_arrays(clazz):
    """Warn that [] should be Collections."""

    if "@interface" in clazz.split:
        return

    safe = ["java.lang.String[]","byte[]","short[]","int[]","long[]","float[]","double[]","boolean[]","char[]"]
    for m in clazz.methods:
        if m.typ.endswith("[]") and m.typ not in safe:
            warn(clazz, m, None, "Method should return Collection<> (or subclass) instead of raw array")
        for arg in m.args:
            if arg.endswith("[]") and arg not in safe:
                warn(clazz, m, None, "Method argument should be Collection<> (or subclass) instead of raw array")


def verify_user_handle(clazz):
    """Methods taking UserHandle should be ForUser or AsUser."""
    if clazz.name.endswith("Listener") or clazz.name.endswith("Callback") or clazz.name.endswith("Callbacks"): return
    if clazz.fullname == "android.app.admin.DeviceAdminReceiver": return
    if clazz.fullname == "android.content.pm.LauncherApps": return
    if clazz.fullname == "android.os.UserHandle": return
    if clazz.fullname == "android.os.UserManager": return

    for m in clazz.methods:
        if re.match("on[A-Z]+", m.name): continue

        has_arg = "android.os.UserHandle" in m.args
        has_name = m.name.endswith("AsUser") or m.name.endswith("ForUser")

        if clazz.fullname.endswith("Manager") and has_arg:
            warn(clazz, m, None, "When a method overload is needed to target a specific "
                 "UserHandle, callers should be directed to use "
                 "Context.createPackageContextAsUser() and re-obtain the relevant "
                 "Manager, and no new API should be added")
        elif has_arg and not has_name:
            warn(clazz, m, None, "Method taking UserHandle should be named 'doFooAsUser' "
                 "or 'queryFooForUser'")


def verify_params(clazz):
    """Parameter classes should be 'Params'."""
    if clazz.name.endswith("Params"): return
    if clazz.fullname == "android.app.ActivityOptions": return
    if clazz.fullname == "android.app.BroadcastOptions": return
    if clazz.fullname == "android.os.Bundle": return
    if clazz.fullname == "android.os.BaseBundle": return
    if clazz.fullname == "android.os.PersistableBundle": return

    bad = ["Param","Parameter","Parameters","Args","Arg","Argument","Arguments","Options","Bundle"]
    for b in bad:
        if clazz.name.endswith(b):
            error(clazz, None, None, "Classes holding a set of parameters should be called 'FooParams'")


def verify_services(clazz):
    """Service name should be FOO_BAR_SERVICE = 'foo_bar'."""
    if clazz.fullname != "android.content.Context": return

    for f in clazz.fields:
        if f.typ != "java.lang.String": continue
        found = re.match(r"([A-Z_]+)_SERVICE", f.name)
        if found:
            expected = found.group(1).lower()
            if f.value != expected:
                error(clazz, f, "C4", "Inconsistent service value; expected '%s'" % (expected))


def verify_tense(clazz):
    """Verify tenses of method names."""
    if clazz.fullname.startswith("android.opengl"): return

    for m in clazz.methods:
        if m.name.endswith("Enable"):
            warn(clazz, m, None, "Unexpected tense; probably meant 'enabled'")


def verify_icu(clazz):
    """Verifies that richer ICU replacements are used."""
    better = {
        "java.util.TimeZone": "android.icu.util.TimeZone",
        "java.util.Calendar": "android.icu.util.Calendar",
        "java.util.Locale": "android.icu.util.ULocale",
        "java.util.ResourceBundle": "android.icu.util.UResourceBundle",
        "java.util.SimpleTimeZone": "android.icu.util.SimpleTimeZone",
        "java.util.StringTokenizer": "android.icu.util.StringTokenizer",
        "java.util.GregorianCalendar": "android.icu.util.GregorianCalendar",
        "java.lang.Character": "android.icu.lang.UCharacter",
        "java.text.BreakIterator": "android.icu.text.BreakIterator",
        "java.text.Collator": "android.icu.text.Collator",
        "java.text.DecimalFormatSymbols": "android.icu.text.DecimalFormatSymbols",
        "java.text.NumberFormat": "android.icu.text.NumberFormat",
        "java.text.DateFormatSymbols": "android.icu.text.DateFormatSymbols",
        "java.text.DateFormat": "android.icu.text.DateFormat",
        "java.text.SimpleDateFormat": "android.icu.text.SimpleDateFormat",
        "java.text.MessageFormat": "android.icu.text.MessageFormat",
        "java.text.DecimalFormat": "android.icu.text.DecimalFormat",
    }

    for m in clazz.ctors + clazz.methods:
        types = []
        types.extend(m.typ)
        types.extend(m.args)
        for arg in types:
            if arg in better:
                warn(clazz, m, None, "Type %s should be replaced with richer ICU type %s" % (arg, better[arg]))


def verify_clone(clazz):
    """Verify that clone() isn't implemented; see EJ page 61."""
    for m in clazz.methods:
        if m.name == "clone":
            error(clazz, m, None, "Provide an explicit copy constructor instead of implementing clone()")


def verify_pfd(clazz):
    """Verify that android APIs use PFD over FD."""
    examine = clazz.ctors + clazz.methods
    for m in examine:
        if m.typ == "java.io.FileDescriptor":
            error(clazz, m, "FW11", "Must use ParcelFileDescriptor")
        if m.typ == "int":
            if "Fd" in m.name or "FD" in m.name or "FileDescriptor" in m.name:
                error(clazz, m, "FW11", "Must use ParcelFileDescriptor")
        for arg in m.args:
            if arg == "java.io.FileDescriptor":
                error(clazz, m, "FW11", "Must use ParcelFileDescriptor")

    for f in clazz.fields:
        if f.typ == "java.io.FileDescriptor":
            error(clazz, f, "FW11", "Must use ParcelFileDescriptor")


def verify_numbers(clazz):
    """Discourage small numbers types like short and byte."""

    discouraged = ["short","byte"]

    for c in clazz.ctors:
        for arg in c.args:
            if arg in discouraged:
                warn(clazz, c, "FW12", "Should avoid odd sized primitives; use int instead")

    for f in clazz.fields:
        if f.typ in discouraged:
            warn(clazz, f, "FW12", "Should avoid odd sized primitives; use int instead")

    for m in clazz.methods:
        if m.typ in discouraged:
            warn(clazz, m, "FW12", "Should avoid odd sized primitives; use int instead")
        for arg in m.args:
            if arg in discouraged:
                warn(clazz, m, "FW12", "Should avoid odd sized primitives; use int instead")


def verify_singleton(clazz):
    """Catch singleton objects with constructors."""

    singleton = False
    for m in clazz.methods:
        if m.name.startswith("get") and m.name.endswith("Instance") and " static " in m.raw:
            singleton = True

    if singleton:
        for c in clazz.ctors:
            error(clazz, c, None, "Singleton classes should use getInstance() methods")



def is_interesting(clazz):
    """Test if given class is interesting from an Android PoV."""

    if clazz.pkg.name.startswith("java"): return False
    if clazz.pkg.name.startswith("junit"): return False
    if clazz.pkg.name.startswith("org.apache"): return False
    if clazz.pkg.name.startswith("org.xml"): return False
    if clazz.pkg.name.startswith("org.json"): return False
    if clazz.pkg.name.startswith("org.w3c"): return False
    if clazz.pkg.name.startswith("android.icu."): return False
    return True


def examine_clazz(clazz):
    """Find all style issues in the given class."""

    notice(clazz)

    if not is_interesting(clazz): return

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
    verify_boolean(clazz)
    verify_collections(clazz)
    verify_flags(clazz)
    verify_exception(clazz)
    if not ALLOW_GOOGLE: verify_google(clazz)
    verify_bitset(clazz)
    verify_manager(clazz)
    verify_boxed(clazz)
    verify_static_utils(clazz)
    # verify_overload_args(clazz)
    verify_callback_handlers(clazz)
    verify_context_first(clazz)
    verify_listener_last(clazz)
    verify_resource_names(clazz)
    verify_files(clazz)
    verify_manager_list(clazz)
    verify_abstract_inner(clazz)
    verify_runtime_exceptions(clazz)
    verify_error(clazz)
    verify_units(clazz)
    verify_closable(clazz)
    verify_member_name_not_kotlin_keyword(clazz)
    verify_method_name_not_kotlin_operator(clazz)
    verify_collections_over_arrays(clazz)
    verify_user_handle(clazz)
    verify_params(clazz)
    verify_services(clazz)
    verify_tense(clazz)
    verify_icu(clazz)
    verify_clone(clazz)
    verify_pfd(clazz)
    verify_numbers(clazz)
    verify_singleton(clazz)


def examine_stream(stream, base_stream=None, in_classes_with_base=[], out_classes_with_base=None):
    """Find all style issues in the given API stream."""
    global failures, noticed
    failures = {}
    noticed = {}
    _parse_stream(stream, examine_clazz, base_f=base_stream,
                  in_classes_with_base=in_classes_with_base,
                  out_classes_with_base=out_classes_with_base)
    return (failures, noticed)


def examine_api(api):
    """Find all style issues in the given parsed API."""
    global failures
    failures = {}
    for key in sorted(api.keys()):
        examine_clazz(api[key])
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


def show_deprecations_at_birth(cur, prev):
    """Show API deprecations at birth."""
    global failures

    # Remove all existing things so we're left with new
    for prev_clazz in prev.values():
        cur_clazz = cur[prev_clazz.fullname]
        if not is_interesting(cur_clazz): continue

        sigs = { i.ident: i for i in prev_clazz.ctors }
        cur_clazz.ctors = [ i for i in cur_clazz.ctors if i.ident not in sigs ]
        sigs = { i.ident: i for i in prev_clazz.methods }
        cur_clazz.methods = [ i for i in cur_clazz.methods if i.ident not in sigs ]
        sigs = { i.ident: i for i in prev_clazz.fields }
        cur_clazz.fields = [ i for i in cur_clazz.fields if i.ident not in sigs ]

        # Forget about class entirely when nothing new
        if len(cur_clazz.ctors) == 0 and len(cur_clazz.methods) == 0 and len(cur_clazz.fields) == 0:
            del cur[prev_clazz.fullname]

    for clazz in cur.values():
        if "deprecated" in clazz.split and not clazz.fullname in prev:
            error(clazz, None, None, "Found API deprecation at birth")

        for i in clazz.ctors + clazz.methods + clazz.fields:
            if "deprecated" in i.split:
                error(clazz, i, None, "Found API deprecation at birth")

    print "%s Deprecated at birth %s\n" % ((format(fg=WHITE, bg=BLUE, bold=True),
                                            format(reset=True)))
    for f in sorted(failures):
        print failures[f]
        print


def show_stats(cur, prev):
    """Show API stats."""

    stats = collections.defaultdict(int)
    for cur_clazz in cur.values():
        if not is_interesting(cur_clazz): continue

        if cur_clazz.fullname not in prev:
            stats['new_classes'] += 1
            stats['new_ctors'] += len(cur_clazz.ctors)
            stats['new_methods'] += len(cur_clazz.methods)
            stats['new_fields'] += len(cur_clazz.fields)
        else:
            prev_clazz = prev[cur_clazz.fullname]

            sigs = { i.ident: i for i in prev_clazz.ctors }
            ctors = len([ i for i in cur_clazz.ctors if i.ident not in sigs ])
            sigs = { i.ident: i for i in prev_clazz.methods }
            methods = len([ i for i in cur_clazz.methods if i.ident not in sigs ])
            sigs = { i.ident: i for i in prev_clazz.fields }
            fields = len([ i for i in cur_clazz.fields if i.ident not in sigs ])

            if ctors + methods + fields > 0:
                stats['extend_classes'] += 1
                stats['extend_ctors'] += ctors
                stats['extend_methods'] += methods
                stats['extend_fields'] += fields

    print "#", "".join([ k.ljust(20) for k in sorted(stats.keys()) ])
    print " ", "".join([ str(stats[k]).ljust(20) for k in sorted(stats.keys()) ])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Enforces common Android public API design \
            patterns. It ignores lint messages from a previous API level, if provided.")
    parser.add_argument("current.txt", type=argparse.FileType('r'), help="current.txt")
    parser.add_argument("previous.txt", nargs='?', type=argparse.FileType('r'), default=None,
            help="previous.txt")
    parser.add_argument("--base-current", nargs='?', type=argparse.FileType('r'), default=None,
            help="The base current.txt to use when examining system-current.txt or"
                 " test-current.txt")
    parser.add_argument("--base-previous", nargs='?', type=argparse.FileType('r'), default=None,
            help="The base previous.txt to use when examining system-previous.txt or"
                 " test-previous.txt")
    parser.add_argument("--no-color", action='store_const', const=True,
            help="Disable terminal colors")
    parser.add_argument("--allow-google", action='store_const', const=True,
            help="Allow references to Google")
    parser.add_argument("--show-noticed", action='store_const', const=True,
            help="Show API changes noticed")
    parser.add_argument("--show-deprecations-at-birth", action='store_const', const=True,
            help="Show API deprecations at birth")
    parser.add_argument("--show-stats", action='store_const', const=True,
            help="Show API stats")
    args = vars(parser.parse_args())

    if args['no_color']:
        USE_COLOR = False

    if args['allow_google']:
        ALLOW_GOOGLE = True

    current_file = args['current.txt']
    base_current_file = args['base_current']
    previous_file = args['previous.txt']
    base_previous_file = args['base_previous']

    if args['show_deprecations_at_birth']:
        with current_file as f:
            cur = _parse_stream(f)
        with previous_file as f:
            prev = _parse_stream(f)
        show_deprecations_at_birth(cur, prev)
        sys.exit()

    if args['show_stats']:
        with current_file as f:
            cur = _parse_stream(f)
        with previous_file as f:
            prev = _parse_stream(f)
        show_stats(cur, prev)
        sys.exit()

    classes_with_base = []

    with current_file as f:
        if base_current_file:
            with base_current_file as base_f:
                cur_fail, cur_noticed = examine_stream(f, base_f,
                                                       out_classes_with_base=classes_with_base)
        else:
            cur_fail, cur_noticed = examine_stream(f, out_classes_with_base=classes_with_base)

    if not previous_file is None:
        with previous_file as f:
            if base_previous_file:
                with base_previous_file as base_f:
                    prev_fail, prev_noticed = examine_stream(f, base_f,
                                                             in_classes_with_base=classes_with_base)
            else:
                prev_fail, prev_noticed = examine_stream(f, in_classes_with_base=classes_with_base)

        # ignore errors from previous API level
        for p in prev_fail:
            if p in cur_fail:
                del cur_fail[p]

        # ignore classes unchanged from previous API level
        for k, v in prev_noticed.iteritems():
            if k in cur_noticed and v == cur_noticed[k]:
                del cur_noticed[k]

        """
        # NOTE: disabled because of memory pressure
        # look for compatibility issues
        compat_fail = verify_compat(cur, prev)

        print "%s API compatibility issues %s\n" % ((format(fg=WHITE, bg=BLUE, bold=True), format(reset=True)))
        for f in sorted(compat_fail):
            print compat_fail[f]
            print
        """

    if args['show_noticed'] and len(cur_noticed) != 0:
        print "%s API changes noticed %s\n" % ((format(fg=WHITE, bg=BLUE, bold=True), format(reset=True)))
        for f in sorted(cur_noticed.keys()):
            print f
        print

    if len(cur_fail) != 0:
        print "%s API style issues %s\n" % ((format(fg=WHITE, bg=BLUE, bold=True), format(reset=True)))
        for f in sorted(cur_fail):
            print cur_fail[f]
            print
        sys.exit(77)

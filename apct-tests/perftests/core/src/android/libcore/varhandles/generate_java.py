#!/usr/bin/python3
#
# Copyright (C) 2022 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""
Generate java benchmarks for varhandles.
Adapted to use CrystalBall from art/test/2239-varhandle-perf/util-src/generate_java.py.

To run use: python generate_java.py <destination_directory>

And then to correct lint errors (from frameworks/base):
../../tools/repohooks/tools/google-java-format.py --fix --google-java-format-diff ../../external/google-java-format/scripts/google-java-format-diff.py
"""


from enum import Enum
from pathlib import Path

import io
import sys


class MemLoc(Enum):
    FIELD = 0
    ARRAY = 1
    BYTE_ARRAY_VIEW = 2


def to_camel_case(word):
    return ''.join(c for c in word.title() if not c == '_')


LOOP ="BenchmarkState state = mPerfStatusReporter.getBenchmarkState();\n        while (state.keepRunning())"

class Benchmark:
    def __init__(self, code, static, vartype, flavour, klass, method, memloc,
        byteorder="LITTLE_ENDIAN"):
        self.code = code
        self.static = static
        self.vartype = vartype
        self.flavour = flavour
        self.klass = klass
        self.method = method
        self.byteorder = byteorder
        self.memloc = memloc

    def fullname(self):
        return "{klass}{method}{flavour}{static_name}{memloc}{byteorder}{vartype}PerfTest".format(
            klass = self.klass,
            method = to_camel_case(self.method),
            flavour = self.flavour,
            static_name = "Static" if self.static else "",
            memloc = to_camel_case(self.memloc.name),
            byteorder = to_camel_case(self.byteorder),
            vartype = to_camel_case(self.vartype))

    def gencode(self):
        if self.klass == "Reflect":
            method_suffix = "" if self.vartype == "String" else self.vartype.title()
            static_first_arg = "null"
        elif self.klass == "Unsafe":
            method_suffix = "Object" if self.vartype == "String" else self.vartype.title()
            static_first_arg = "this.getClass()"
        else:
            method_suffix = ""
            static_first_arg = ""

        first_arg = static_first_arg if self.static else "this"

        return self.code.format(
            name = self.fullname(),
            method = self.method + method_suffix,
            flavour = self.flavour,
            static_name = "Static" if self.static else "",
            static_kwd = "static " if self.static else "",
            static_prefix = "s" if self.static else "m",
            this = first_arg,
            this_comma = "" if not first_arg else first_arg + ", ",
            vartype = self.vartype,
            byteorder = self.byteorder,
            value1 = VALUES[self.vartype][0],
            value2 = VALUES[self.vartype][1],
            value1_byte_array = VALUES["byte[]"][self.byteorder][0],
            value2_byte_array = VALUES["byte[]"][self.byteorder][1],
            loop = LOOP)


def BenchVHField(code, static, vartype, flavour, method):
    return Benchmark(code, static, vartype, flavour, "VarHandle", method, MemLoc.FIELD)


def BenchVHArray(code, vartype, flavour, method):
    return Benchmark(code, False, vartype, flavour, "VarHandle", method, MemLoc.ARRAY)


def BenchVHByteArrayView(code, byteorder, vartype, flavour, method):
    return Benchmark(code, False, vartype, flavour, "VarHandle", method, MemLoc.BYTE_ARRAY_VIEW, byteorder)


def BenchReflect(code, static, vartype, method):
    return Benchmark(code, static, vartype, "", "Reflect", method, MemLoc.FIELD)


def BenchUnsafe(code, static, vartype, method):
    return Benchmark(code, static, vartype, "", "Unsafe", method, MemLoc.FIELD)


VALUES = {
    "int": ["42", "~42"],
    "float": ["3.14f", "2.17f"],
    "String": ["\"qwerty\"", "null"],
    "byte[]": {
        "LITTLE_ENDIAN": [
            "{ (byte) VALUE, (byte) (VALUE >> 8), (byte) (VALUE >> 16), (byte) (VALUE >> 24) }",
            "{ (byte) VALUE, (byte) (-1 >> 8), (byte) (-1 >> 16), (byte) (-1 >> 24) }",
        ],
        "BIG_ENDIAN": [
            "{ (byte) (VALUE >> 24), (byte) (VALUE >> 16), (byte) (VALUE >> 8), (byte) VALUE }",
            "{ (byte) (-1 >> 24), (byte) (-1 >> 16), (byte) (-1 >> 8), (byte) VALUE }",
        ],
    },
}

REPEAT = 2
REPEAT_HALF = (int) (REPEAT / 2)


BANNER = """/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
 // This file is generated by generate_java.py do not directly modify!"""


VH_IMPORTS = """
package android.libcore.varhandles;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.test.suitebuilder.annotation.LargeTest;

import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
"""


VH_START = BANNER + VH_IMPORTS + """
@RunWith(AndroidJUnit4.class)
@LargeTest
public class {name} {{
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();
    static final {vartype} FIELD_VALUE = {value1};
    {static_kwd}{vartype} {static_prefix}Field = FIELD_VALUE;
    VarHandle mVh;

    public {name}() throws Throwable {{
        mVh = MethodHandles.lookup().find{static_name}VarHandle(this.getClass(), "{static_prefix}Field", {vartype}.class);
    }}
"""


END = """
        }}
    }}
}}"""


VH_GET = VH_START + """
    @Before
    public void setup() {{
        {vartype} v = ({vartype}) mVh.{method}{flavour}({this});
        if (v != FIELD_VALUE) {{
            throw new RuntimeException("field has unexpected value " + v);
        }}
    }}

    @Test
    public void run() {{
        {vartype} x;
        {loop} {{""" + """
            x = ({vartype}) mVh.{method}{flavour}({this});""" * REPEAT + END


VH_SET = VH_START + """
    @After
    public void teardown() {{
        if ({static_prefix}Field != FIELD_VALUE) {{
            throw new RuntimeException("{static_prefix}Field has unexpected value " + {static_prefix}Field);
        }}
    }}

    @Test
    public void run() {{
        {vartype} x;
        {loop} {{""" + """
            mVh.{method}{flavour}({this_comma}FIELD_VALUE);""" * REPEAT + END


VH_CAS = VH_START + """
    @Test
    public void run() {{
        boolean success;
        {loop} {{""" + """
            success = mVh.{method}{flavour}({this_comma}{static_prefix}Field, {value2});
            success = mVh.{method}{flavour}({this_comma}{static_prefix}Field, {value1});""" * REPEAT_HALF + END


VH_CAE = VH_START + """
    @Test
    public void run() {{
        {vartype} x;
        {loop} {{""" + """
            x = ({vartype}) mVh.{method}{flavour}({this_comma}{static_prefix}Field, {value2});
            x = ({vartype}) mVh.{method}{flavour}({this_comma}{static_prefix}Field, {value1});""" * REPEAT_HALF + END


VH_GAS = VH_START + """
    @Test
    public void run() {{
        {vartype} x;
        {loop} {{""" + """
            x = ({vartype}) mVh.{method}{flavour}({this_comma}{value2});""" * REPEAT + END


VH_GAA = VH_START + """
    @Test
    public void run() {{
        {vartype} x;
        {loop} {{""" + """
            x = ({vartype}) mVh.{method}{flavour}({this_comma}{value2});""" * REPEAT + END


VH_GAB = VH_START + """
    @Test
    public void run() {{
        int x;
        {loop} {{""" + """
            x = ({vartype}) mVh.{method}{flavour}({this_comma}{value2});""" * REPEAT + END


VH_START_ARRAY = BANNER + VH_IMPORTS + """
@RunWith(AndroidJUnit4.class)
@LargeTest
public class {name} {{
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();
    static final {vartype} ELEMENT_VALUE = {value1};
    {vartype}[] mArray = {{ ELEMENT_VALUE }};
    VarHandle mVh;

    public {name}() throws Throwable {{
        mVh = MethodHandles.arrayElementVarHandle({vartype}[].class);
    }}
"""


VH_GET_A = VH_START_ARRAY + """
    @Before
    public void setup() {{
        {vartype} v = ({vartype}) mVh.{method}{flavour}(mArray, 0);
        if (v != ELEMENT_VALUE) {{
            throw new RuntimeException("array element has unexpected value: " + v);
        }}
    }}

    @Test
    public void run() {{
        {vartype}[] a = mArray;
        {vartype} x;
        {loop} {{""" + """
            x = ({vartype}) mVh.{method}{flavour}(a, 0);""" * REPEAT + END


VH_SET_A = VH_START_ARRAY + """
    @After
    public void teardown() {{
        if (mArray[0] != {value2}) {{
            throw new RuntimeException("array element has unexpected value: " + mArray[0]);
        }}
    }}

    @Test
    public void run() {{
        {vartype}[] a = mArray;
        {vartype} x;
        {loop} {{""" + """
            mVh.{method}{flavour}(a, 0, {value2});""" * REPEAT + END


VH_START_BYTE_ARRAY_VIEW = BANNER + VH_IMPORTS + """
import java.util.Arrays;
import java.nio.ByteOrder;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class {name} {{
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();
    static final {vartype} VALUE = {value1};
    byte[] mArray1 = {value1_byte_array};
    byte[] mArray2 = {value2_byte_array};
    VarHandle mVh;

    public {name}() throws Throwable {{
        mVh = MethodHandles.byteArrayViewVarHandle({vartype}[].class, ByteOrder.{byteorder});
  }}
"""


VH_GET_BAV = VH_START_BYTE_ARRAY_VIEW + """
    @Before
    public void setup() {{
        {vartype} v = ({vartype}) mVh.{method}{flavour}(mArray1, 0);
        if (v != VALUE) {{
            throw new RuntimeException("array has unexpected value: " + v);
        }}
    }}

    @Test
    public void run() {{
        byte[] a = mArray1;
        {vartype} x;
        {loop} {{""" + """
            x = ({vartype}) mVh.{method}{flavour}(a, 0);""" * REPEAT + END


VH_SET_BAV = VH_START_BYTE_ARRAY_VIEW + """
    @After
    public void teardown() {{
        if (!Arrays.equals(mArray2, mArray1)) {{
            throw new RuntimeException("array has unexpected values: " +
                mArray2[0] + " " + mArray2[1] + " " + mArray2[2] + " " + mArray2[3]);
        }}
    }}

    @Test
    public void run() {{
        byte[] a = mArray2;
        {loop} {{""" + """
            mVh.{method}{flavour}(a, 0, VALUE);""" * REPEAT + END


REFLECT_START = BANNER + VH_IMPORTS + """
import java.lang.reflect.Field;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class {name} {{
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();
    Field mField;
    {static_kwd}{vartype} {static_prefix}Value;

    public {name}() throws Throwable {{
        mField = this.getClass().getDeclaredField("{static_prefix}Value");
    }}
"""


REFLECT_GET = REFLECT_START + """
    @Test
    public void run() throws Throwable {{
        {vartype} x;
        {loop} {{""" + """
            x = ({vartype}) mField.{method}({this});""" * REPEAT + END


REFLECT_SET = REFLECT_START + """
    @Test
    public void run() throws Throwable {{
        {loop} {{""" + """
            mField.{method}({this_comma}{value1});""" * REPEAT + END


UNSAFE_START = BANNER + VH_IMPORTS + """
import java.lang.reflect.Field;
import jdk.internal.misc.Unsafe;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class {name} {{
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();
    long mOffset;
    public {static_kwd}{vartype} {static_prefix}Value = {value1};

    {name}() throws Throwable {{
        Field field = this.getClass().getDeclaredField("{static_prefix}Value");
        mOffset = get{static_name}FieldOffset(field);
    }}
"""


UNSAFE_GET = UNSAFE_START + """
    @Test
    public void run() throws Throwable {{
        {vartype} x;
        {loop} {{""" + """
            x = ({vartype}) theUnsafe.{method}({this_comma}mOffset);""" * REPEAT + END


UNSAFE_PUT = UNSAFE_START + """
    @Test
    public void run() throws Throwable {{
        {loop} {{""" + """
             theUnsafe.{method}({this_comma}mOffset, {value1});""" * REPEAT + END


UNSAFE_CAS = UNSAFE_START + """
    @Test
    public void run() throws Throwable {{
        {loop} {{""" + """
           theUnsafe.{method}({this_comma}mOffset, {value1}, {value2});
           theUnsafe.{method}({this_comma}mOffset, {value2}, {value1});""" * REPEAT_HALF + END


ALL_BENCHMARKS = (
    [BenchVHField(VH_GET, static, vartype, flavour, "get")
        for flavour in ["", "Acquire", "Opaque", "Volatile"]
        for static in [True, False]
        for vartype in ["int", "String"]] +
    [BenchVHField(VH_SET, static, vartype, flavour, "set")
        for flavour in ["", "Volatile", "Opaque", "Release"]
        for static in [True, False]
        for vartype in ["int", "String"]] +
    [BenchVHField(VH_CAS, static, vartype, flavour, "compareAndSet")
        for flavour in [""]
        for static in [True, False]
        for vartype in ["int", "String"]] +
    [BenchVHField(VH_CAS, static, vartype, flavour, "weakCompareAndSet")
        for flavour in ["", "Plain", "Acquire", "Release"]
        for static in [True, False]
        for vartype in ["int", "String"]] +
    [BenchVHField(VH_CAE, static, vartype, flavour, "compareAndExchange")
        for flavour in ["", "Acquire", "Release"]
        for static in [True, False]
        for vartype in ["int", "String"]] +
    [BenchVHField(VH_GAS, static, vartype, flavour, "getAndSet")
        for flavour in ["", "Acquire", "Release"]
        for static in [True, False]
        for vartype in ["int", "String"]] +
    [BenchVHField(VH_GAA, static, vartype, flavour, "getAndAdd")
        for flavour in ["", "Acquire", "Release"]
        for static in [True, False]
        for vartype in ["int", "float"]] +
    [BenchVHField(VH_GAB, static, vartype, flavour, "getAndBitwise")
        for flavour in [oper + mode
            for oper in ["Or", "Xor", "And"]
            for mode in ["", "Acquire", "Release"]]
        for static in [True, False]
        for vartype in ["int"]] +
    [BenchVHArray(VH_GET_A, vartype, flavour, "get")
        for flavour in [""]
        for vartype in ["int", "String"]] +
    [BenchVHArray(VH_SET_A, vartype, flavour, "set")
        for flavour in [""]
        for vartype in ["int", "String"]] +
    [BenchVHByteArrayView(VH_GET_BAV, byteorder, vartype, flavour, "get")
        for flavour in [""]
        for byteorder in ["BIG_ENDIAN", "LITTLE_ENDIAN"]
        for vartype in ["int"]] +
    [BenchVHByteArrayView(VH_SET_BAV, byteorder, vartype, flavour, "set")
        for flavour in [""]
        for byteorder in ["BIG_ENDIAN", "LITTLE_ENDIAN"]
        for vartype in ["int"]] +
    [BenchReflect(REFLECT_GET, static, vartype, "get")
        for static in [True, False]
        for vartype in ["int", "String"]] +
    [BenchReflect(REFLECT_SET, static, vartype, "set")
        for static in [True, False]
        for vartype in ["int", "String"]])



def main(argv):
    final_java_dir = Path(argv[1])
    if not final_java_dir.exists() or not final_java_dir.is_dir():
        print("{} is not a valid java dir".format(final_java_dir), file=sys.stderr)
        sys.exit(1)

    for bench in ALL_BENCHMARKS:
        file_path = final_java_dir / "{}.java".format(bench.fullname())
        with file_path.open("w") as f:
            print(bench.gencode(), file=f)


if __name__ == '__main__':
    main(sys.argv)

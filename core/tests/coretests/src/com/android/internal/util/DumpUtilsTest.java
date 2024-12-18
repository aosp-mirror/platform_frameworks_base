/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.internal.util;

import static com.android.internal.util.DumpUtils.CRITICAL_SECTION_COMPONENTS;
import static com.android.internal.util.DumpUtils.filterRecord;
import static com.android.internal.util.DumpUtils.isNonPlatformPackage;
import static com.android.internal.util.DumpUtils.isPlatformCriticalPackage;
import static com.android.internal.util.DumpUtils.isPlatformNonCriticalPackage;
import static com.android.internal.util.DumpUtils.isPlatformPackage;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.util.SparseArray;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Run with:
 atest FrameworksCoreTests:DumpUtilsTest
 */
@RunWith(AndroidJUnit4.class)
public class DumpUtilsTest {

    private final StringWriter mStringWriter = new StringWriter();
    private final PrintWriter mPrintWriter = new PrintWriter(mStringWriter);

    private static ComponentName cn(String componentName) {
        if (componentName == null) {
            return null;
        }
        return ComponentName.unflattenFromString(componentName);
    }

    private static ComponentName.WithComponentName wcn(String componentName) {
        if (componentName == null) {
            return null;
        }
        return () -> cn(componentName);
    }

    @Test
    public void testIsPlatformPackage() {
        assertTrue(isPlatformPackage("android"));
        assertTrue(isPlatformPackage("android.abc"));
        assertTrue(isPlatformPackage("com.android.abc"));

        assertFalse(isPlatformPackage((String) null));
        assertFalse(isPlatformPackage("com.google"));

        assertTrue(isPlatformPackage(cn("android/abc")));
        assertTrue(isPlatformPackage(cn("android.abc/abc")));
        assertTrue(isPlatformPackage(cn("com.android.def/abc")));

        assertFalse(isPlatformPackage(cn(null)));
        assertFalse(isPlatformPackage(cn("com.google.def/abc")));

        assertTrue(isPlatformPackage(wcn("android/abc")));
        assertTrue(isPlatformPackage(wcn("android.abc/abc")));
        assertTrue(isPlatformPackage(wcn("com.android.def/abc")));

        assertFalse(isPlatformPackage(wcn(null)));
        assertFalse(isPlatformPackage(wcn("com.google.def/abc")));
    }

    @Test
    public void testIsNonPlatformPackage() {
        assertFalse(isNonPlatformPackage("android"));
        assertFalse(isNonPlatformPackage("android.abc"));
        assertFalse(isNonPlatformPackage("com.android.abc"));

        assertFalse(isNonPlatformPackage((String) null));
        assertTrue(isNonPlatformPackage("com.google"));

        assertFalse(isNonPlatformPackage(cn("android/abc")));
        assertFalse(isNonPlatformPackage(cn("android.abc/abc")));
        assertFalse(isNonPlatformPackage(cn("com.android.def/abc")));

        assertFalse(isNonPlatformPackage(cn(null)));
        assertTrue(isNonPlatformPackage(cn("com.google.def/abc")));

        assertFalse(isNonPlatformPackage(wcn("android/abc")));
        assertFalse(isNonPlatformPackage(wcn("android.abc/abc")));
        assertFalse(isNonPlatformPackage(wcn("com.android.def/abc")));

        assertFalse(isNonPlatformPackage(wcn(null)));
        assertTrue(isNonPlatformPackage(wcn("com.google.def/abc")));
    }

    @Test
    public void testIsPlatformCriticalPackage() {
        for (final ComponentName componentName : CRITICAL_SECTION_COMPONENTS) {
            assertTrue(isPlatformCriticalPackage(() -> componentName));
            assertTrue(isPlatformPackage(componentName));
        }
        assertFalse(isPlatformCriticalPackage(wcn("com.google.p/abc")));
        assertFalse(isPlatformCriticalPackage(wcn("com.android.def/abc")));
        assertFalse(isPlatformCriticalPackage(wcn("com.android.abc")));
        assertFalse(isPlatformCriticalPackage(wcn("com.android")));
        assertFalse(isPlatformCriticalPackage(wcn(null)));
        assertFalse(isPlatformCriticalPackage(null));
    }

    @Test
    public void testIsPlatformNonCriticalPackage() {
        for (final ComponentName componentName : CRITICAL_SECTION_COMPONENTS) {
            assertFalse(isPlatformNonCriticalPackage(() -> componentName));
        }
        assertTrue(isPlatformNonCriticalPackage(wcn("android/abc")));
        assertTrue(isPlatformNonCriticalPackage(wcn("android.abc/abc")));
        assertTrue(isPlatformNonCriticalPackage(wcn("com.android.def/abc")));

        assertFalse(isPlatformNonCriticalPackage(wcn("com.google.def/abc")));
        assertFalse(isPlatformNonCriticalPackage(wcn(null)));
        assertFalse(isPlatformNonCriticalPackage(null));
    }

    @Test
    public void testFilterRecord() {
        assertFalse(filterRecord(null).test(wcn("com.google.p/abc")));
        assertFalse(filterRecord(null).test(wcn("com.android.p/abc")));

        assertTrue(filterRecord("all").test(wcn("com.google.p/abc")));
        assertTrue(filterRecord("all").test(wcn("com.android.p/abc")));
        assertFalse(filterRecord("all").test(wcn(null)));

        assertFalse(filterRecord("all-platform").test(wcn("com.google.p/abc")));
        assertTrue(filterRecord("all-platform").test(wcn("com.android.p/abc")));
        assertFalse(filterRecord("all-platform").test(wcn(null)));

        assertTrue(filterRecord("all-non-platform").test(wcn("com.google.p/abc")));
        assertFalse(filterRecord("all-non-platform").test(wcn("com.android.p/abc")));
        assertFalse(filterRecord("all-non-platform").test(wcn(null)));

        for (final ComponentName componentName : CRITICAL_SECTION_COMPONENTS) {
            assertTrue(filterRecord("all-platform-critical").test((() -> componentName)));
            assertFalse(filterRecord("all-platform-non-critical").test((() -> componentName)));
            assertTrue(filterRecord("all-platform").test((() -> componentName)));
        }
        assertFalse(filterRecord("all-platform-critical").test(wcn("com.google.p/abc")));
        assertFalse(filterRecord("all-platform-critical").test(wcn("com.android.p/abc")));
        assertFalse(filterRecord("all-platform-critical").test(wcn(null)));

        assertTrue(filterRecord("all-platform-non-critical").test(wcn("com.android.p/abc")));
        assertFalse(filterRecord("all-platform-non-critical").test(wcn("com.google.p/abc")));
        assertFalse(filterRecord("all-platform-non-critical").test(wcn(null)));

        // Partial string match.
        assertTrue(filterRecord("abc").test(wcn("com.google.p/.abc")));
        assertFalse(filterRecord("abc").test(wcn("com.google.p/.def")));
        assertTrue(filterRecord("com").test(wcn("com.google.p/.xyz")));

        // Full component name match.
        assertTrue(filterRecord("com.google/com.google.abc").test(wcn("com.google/.abc")));
        assertFalse(filterRecord("com.google/com.google.abc").test(wcn("com.google/.abc.def")));


        // Hex ID match
        ComponentName.WithComponentName component = wcn("com.google/.abc");

        assertTrue(filterRecord(
                Integer.toHexString(System.identityHashCode(component))).test(component));
        // Same component name, but different ID, no match.
        assertFalse(filterRecord(
                Integer.toHexString(System.identityHashCode(component))).test(
                        wcn("com.google/.abc")));
    }

    @Test
    public void testDumpSparseArray_empty() {
        SparseArray<String> array = new SparseArray<>();

        DumpUtils.dumpSparseArray(mPrintWriter, /* prefix= */ "...", array, "whatever");

        String output = flushPrintWriter();

        assertWithMessage("empty array dump").that(output).isEqualTo("...No whatevers\n");
    }

    @Test
    public void testDumpSparseArray_oneElement() {
        SparseArray<String> array = new SparseArray<>();
        array.put(1, "uno");

        DumpUtils.dumpSparseArray(mPrintWriter, /* prefix= */ ".", array, "number");

        String output = flushPrintWriter();

        assertWithMessage("dump of %s", array).that(output).isEqualTo(""
                + ".1 number(s):\n"
                + "..0: 1->uno\n");
    }

    @Test
    public void testDumpSparseArray_oneNullElement() {
        SparseArray<String> array = new SparseArray<>();
        array.put(1, null);

        DumpUtils.dumpSparseArray(mPrintWriter, /* prefix= */ ".", array, "NULL");

        String output = flushPrintWriter();

        assertWithMessage("dump of %s", array).that(output).isEqualTo(""
                + ".1 NULL(s):\n"
                + "..0: 1->(null)\n");
    }

    @Test
    public void testDumpSparseArray_multipleElements() {
        SparseArray<String> array = new SparseArray<>();
        array.put(1, "uno");
        array.put(2, "duo");
        array.put(42, null);

        DumpUtils.dumpSparseArray(mPrintWriter, /* prefix= */ ".", array, "number");

        String output = flushPrintWriter();

        assertWithMessage("dump of %s", array).that(output).isEqualTo(""
                + ".3 number(s):\n"
                + "..0: 1->uno\n"
                + "..1: 2->duo\n"
                + "..2: 42->(null)\n");
    }

    @Test
    public void testDumpSparseArray_keyDumperOnly() {
        SparseArray<String> array = new SparseArray<>();
        array.put(1, "uno");
        array.put(2, "duo");
        array.put(42, null);

        DumpUtils.dumpSparseArray(mPrintWriter, /* prefix= */ ".", array, "number",
                (i, k) -> {
                    mPrintWriter.printf("_%d=%d_", i, k);
                }, /* valueDumper= */ null);

        String output = flushPrintWriter();

        assertWithMessage("dump of %s", array).that(output).isEqualTo(""
                + ".3 number(s):\n"
                + "_0=1_uno\n"
                + "_1=2_duo\n"
                + "_2=42_(null)\n");
    }

    @Test
    public void testDumpSparseArray_valueDumperOnly() {
        SparseArray<String> array = new SparseArray<>();
        array.put(1, "uno");
        array.put(2, "duo");
        array.put(42, null);

        DumpUtils.dumpSparseArray(mPrintWriter, /* prefix= */ ".", array, "number",
                /* keyDumper= */ null,
                s -> {
                    mPrintWriter.print(s.toUpperCase());
                });

        String output = flushPrintWriter();

        assertWithMessage("dump of %s", array).that(output).isEqualTo(""
                + ".3 number(s):\n"
                + "..0: 1->UNO\n"
                + "..1: 2->DUO\n"
                + "..2: 42->(null)\n");
    }

    @Test
    public void testDumpSparseArray_keyAndValueDumpers() {
        SparseArray<String> array = new SparseArray<>();
        array.put(1, "uno");
        array.put(2, "duo");
        array.put(42, null);

        DumpUtils.dumpSparseArray(mPrintWriter, /* prefix= */ ".", array, "number",
                (i, k) -> {
                    mPrintWriter.printf("_%d=%d_", i, k);
                },
                s -> {
                    mPrintWriter.print(s.toUpperCase());
                });

        String output = flushPrintWriter();

        assertWithMessage("dump of %s", array).that(output).isEqualTo(""
                + ".3 number(s):\n"
                + "_0=1_UNO\n"
                + "_1=2_DUO\n"
                + "_2=42_(null)\n");
    }

    @Test
    public void testDumpSparseArrayValues() {
        SparseArray<String> array = new SparseArray<>();
        array.put(1, "uno");
        array.put(2, "duo");
        array.put(42, null);

        DumpUtils.dumpSparseArrayValues(mPrintWriter, /* prefix= */ ".", array, "number");

        String output = flushPrintWriter();

        assertWithMessage("dump of %s", array).that(output).isEqualTo(""
                + ".3 number(s):\n"
                + "..uno\n"
                + "..duo\n"
                + "..(null)\n");
    }

    private String flushPrintWriter() {
        mPrintWriter.flush();

        return mStringWriter.toString();
    }
}

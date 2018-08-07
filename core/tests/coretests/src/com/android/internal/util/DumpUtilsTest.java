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

import static com.android.internal.util.DumpUtils.filterRecord;
import static com.android.internal.util.DumpUtils.isNonPlatformPackage;
import static com.android.internal.util.DumpUtils.isPlatformPackage;

import android.content.ComponentName;

import junit.framework.TestCase;

/**
 * Run with:
 atest /android/pi-dev/frameworks/base/core/tests/coretests/src/com/android/internal/util/DumpTest.java
 */
public class DumpUtilsTest extends TestCase {

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
}

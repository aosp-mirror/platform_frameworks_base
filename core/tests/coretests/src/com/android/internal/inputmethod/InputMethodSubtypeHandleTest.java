/*
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

package com.android.internal.inputmethod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import android.annotation.NonNull;
import android.content.ComponentName;
import android.os.Parcel;
import android.platform.test.annotations.Presubmit;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.security.InvalidParameterException;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class InputMethodSubtypeHandleTest {

    @Test
    public void testCreateFromRawHandle() {
        {
            final InputMethodSubtypeHandle handle =
                    InputMethodSubtypeHandle.of("com.android.test/.Ime1:subtype:1");
            assertNotNull(handle);
            assertEquals("com.android.test/.Ime1:subtype:1", handle.toStringHandle());
            assertEquals("com.android.test/.Ime1", handle.getImeId());
            assertEquals(ComponentName.unflattenFromString("com.android.test/.Ime1"),
                    handle.getComponentName());
        }

        assertThrows(NullPointerException.class, () -> InputMethodSubtypeHandle.of(null));
        assertThrows(InvalidParameterException.class, () -> InputMethodSubtypeHandle.of(""));

        // The IME ID must use ComponentName#flattenToShortString(), not #flattenToString().
        assertThrows(InvalidParameterException.class, () -> InputMethodSubtypeHandle.of(
                "com.android.test/com.android.test.Ime1:subtype:1"));

        assertThrows(InvalidParameterException.class, () -> InputMethodSubtypeHandle.of(
                "com.android.test/.Ime1:subtype:0001"));
        assertThrows(InvalidParameterException.class, () -> InputMethodSubtypeHandle.of(
                "com.android.test/.Ime1:subtype:1!"));
        assertThrows(InvalidParameterException.class, () -> InputMethodSubtypeHandle.of(
                "com.android.test/.Ime1:subtype:1:"));
        assertThrows(InvalidParameterException.class, () -> InputMethodSubtypeHandle.of(
                "com.android.test/.Ime1:subtype:1:2"));
        assertThrows(InvalidParameterException.class, () -> InputMethodSubtypeHandle.of(
                "com.android.test/.Ime1:subtype:a"));
        assertThrows(InvalidParameterException.class, () -> InputMethodSubtypeHandle.of(
                "com.android.test/.Ime1:subtype:0x01"));
        assertThrows(InvalidParameterException.class, () -> InputMethodSubtypeHandle.of(
                "com.android.test/.Ime1:Subtype:a"));
        assertThrows(InvalidParameterException.class, () -> InputMethodSubtypeHandle.of(
                "ime1:subtype:1"));
    }

    @Test
    public void testCreateFromInputMethodInfo() {
        final InputMethodInfo imi = new InputMethodInfo(
                "com.android.test", "com.android.test.Ime1", "TestIME", null);
        {
            final InputMethodSubtypeHandle handle = InputMethodSubtypeHandle.of(imi, null);
            assertNotNull(handle);
            assertEquals("com.android.test/.Ime1:subtype:0", handle.toStringHandle());
            assertEquals("com.android.test/.Ime1", handle.getImeId());
            assertEquals(ComponentName.unflattenFromString("com.android.test/.Ime1"),
                    handle.getComponentName());
        }

        final InputMethodSubtype subtype =
                new InputMethodSubtype.InputMethodSubtypeBuilder().setSubtypeId(1).build();
        {
            final InputMethodSubtypeHandle handle = InputMethodSubtypeHandle.of(imi, subtype);
            assertNotNull(handle);
            assertEquals("com.android.test/.Ime1:subtype:1", handle.toStringHandle());
            assertEquals("com.android.test/.Ime1", handle.getImeId());
            assertEquals(ComponentName.unflattenFromString("com.android.test/.Ime1"),
                    handle.getComponentName());
        }

        assertThrows(NullPointerException.class, () -> InputMethodSubtypeHandle.of(null, null));
        assertThrows(NullPointerException.class, () -> InputMethodSubtypeHandle.of(null, subtype));
    }

    @Test
    public void testEquality() {
        assertEquals(InputMethodSubtypeHandle.of("com.android.test/.Ime1:subtype:1"),
                InputMethodSubtypeHandle.of("com.android.test/.Ime1:subtype:1"));
        assertEquals(InputMethodSubtypeHandle.of("com.android.test/.Ime1:subtype:1").hashCode(),
                InputMethodSubtypeHandle.of("com.android.test/.Ime1:subtype:1").hashCode());

        assertNotEquals(InputMethodSubtypeHandle.of("com.android.test/.Ime1:subtype:1"),
                InputMethodSubtypeHandle.of("com.android.test/.Ime1:subtype:2"));
        assertNotEquals(InputMethodSubtypeHandle.of("com.android.test/.Ime1:subtype:1"),
                InputMethodSubtypeHandle.of("com.android.test/.Ime2:subtype:1"));
    }

    @Test
    public void testParcelablility() {
        final InputMethodSubtypeHandle original =
                InputMethodSubtypeHandle.of("com.android.test/.Ime1:subtype:1");
        final InputMethodSubtypeHandle cloned = cloneHandle(original);
        assertEquals(original, cloned);
        assertEquals(original.hashCode(), cloned.hashCode());
        assertEquals(original.getComponentName(), cloned.getComponentName());
        assertEquals(original.getImeId(), cloned.getImeId());
        assertEquals(original.toStringHandle(), cloned.toStringHandle());
    }

    @Test
    public void testNoUnnecessaryStringInstantiationInToStringHandle() {
        final String validHandleStr = "com.android.test/.Ime1:subtype:1";
        // Verify that toStringHandle() returns the same String object if the input is valid for
        // an efficient memory usage.
        assertSame(validHandleStr, InputMethodSubtypeHandle.of(validHandleStr).toStringHandle());
    }

    @NonNull
    private static InputMethodSubtypeHandle cloneHandle(
            @NonNull InputMethodSubtypeHandle original) {
        Parcel parcel = null;
        try {
            parcel = Parcel.obtain();
            original.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return InputMethodSubtypeHandle.CREATOR.createFromParcel(parcel);
        } finally {
            if (parcel != null) {
                parcel.recycle();
            }
        }
    }
}

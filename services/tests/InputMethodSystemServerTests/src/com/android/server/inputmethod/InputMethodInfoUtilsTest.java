/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.inputmethod;

import static com.android.server.inputmethod.TestUtils.TEST_IME_ID1;
import static com.android.server.inputmethod.TestUtils.TEST_IME_ID2;
import static com.android.server.inputmethod.TestUtils.createFakeInputMethodInfo;
import static com.android.server.inputmethod.TestUtils.createFakeSubtypes;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.os.Parcel;
import android.os.Parcelable;
import android.view.inputmethod.InputMethodInfo;

import androidx.annotation.NonNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.Objects;

public final class InputMethodInfoUtilsTest {

    @Test
    public void testMarshalSameObject() {
        final var imi = createFakeInputMethodInfo(TEST_IME_ID1, createFakeSubtypes(3));
        final byte[] buf = InputMethodInfoUtils.marshal(imi);

        assertArrayEquals("The same value must be returned when called multiple times",
                buf, InputMethodInfoUtils.marshal(imi));
        assertArrayEquals("The same value must be returned when called multiple times",
                buf, InputMethodInfoUtils.marshal(imi));
    }

    @Test
    public void testMarshalDifferentObjects() {
        final var imi1 = createFakeInputMethodInfo(TEST_IME_ID1, createFakeSubtypes(3));
        final var imi2 = createFakeInputMethodInfo(TEST_IME_ID2, createFakeSubtypes(0));

        assertFalse("Different inputs must yield different byte patterns", Arrays.equals(
                InputMethodInfoUtils.marshal(imi1), InputMethodInfoUtils.marshal(imi2)));
    }

    @NonNull
    private static <T> T readTypedObject(byte[] data, @NonNull Parcelable.Creator<T> creator) {
        Parcel parcel = null;
        try {
            parcel = Parcel.obtain();
            parcel.unmarshall(data, 0, data.length);
            parcel.setDataPosition(0);
            return Objects.requireNonNull(parcel.readTypedObject(creator));
        } finally {
            if (parcel != null) {
                parcel.recycle();
            }
        }
    }

    @Test
    public void testUnmarshalSameObject() {
        final var imi = createFakeInputMethodInfo(TEST_IME_ID1, createFakeSubtypes(3));
        final var cloned = readTypedObject(InputMethodInfoUtils.marshal(imi),
                InputMethodInfo.CREATOR);
        assertEquals(imi.getPackageName(), cloned.getPackageName());
        assertEquals(imi.getSubtypeCount(), cloned.getSubtypeCount());
    }
}

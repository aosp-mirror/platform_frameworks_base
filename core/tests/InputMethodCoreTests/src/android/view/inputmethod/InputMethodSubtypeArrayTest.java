/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.view.inputmethod;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertEquals;

import android.os.BadParcelableException;
import android.os.Parcel;
import android.platform.test.annotations.Presubmit;
import android.view.inputmethod.InputMethodSubtype.InputMethodSubtypeBuilder;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

@SmallTest
@RunWith(AndroidJUnit4.class)
@Presubmit
public class InputMethodSubtypeArrayTest {

    @Test
    public void testInstantiate() throws Exception {
        final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
        subtypes.add(createDummySubtype(0, "en_US"));
        subtypes.add(createDummySubtype(1, "en_US"));
        subtypes.add(createDummySubtype(2, "ja_JP"));

        final InputMethodSubtypeArray array = new InputMethodSubtypeArray(subtypes);
        assertEquals(subtypes.size(), array.getCount());
        assertEquals(subtypes.get(0), array.get(0));
        assertEquals(subtypes.get(1), array.get(1));
        assertEquals(subtypes.get(2), array.get(2));

        final InputMethodSubtypeArray clonedArray = cloneViaParcel(array);
        assertEquals(subtypes.size(), clonedArray.getCount());
        assertEquals(subtypes.get(0), clonedArray.get(0));
        assertEquals(subtypes.get(1), clonedArray.get(1));
        assertEquals(subtypes.get(2), clonedArray.get(2));

        final InputMethodSubtypeArray clonedClonedArray = cloneViaParcel(clonedArray);
        assertEquals(clonedArray.getCount(), clonedClonedArray.getCount());
        assertEquals(clonedArray.get(0), clonedClonedArray.get(0));
        assertEquals(clonedArray.get(1), clonedClonedArray.get(1));
        assertEquals(clonedArray.get(2), clonedClonedArray.get(2));
    }

    @Test
    public void testNegativeCount() throws Exception {
        InputMethodSubtypeArray negativeCountArray;
        try {
            // Construct a InputMethodSubtypeArray with: mCount = -1
            var p = Parcel.obtain();
            p.writeInt(-1);
            p.setDataPosition(0);
            negativeCountArray = new InputMethodSubtypeArray(p);
        } catch (BadParcelableException e) {
            // Expected with fix: Prevent negative mCount
            assertThat(e).hasMessageThat().contains("mCount");
            return;
        }
        assertWithMessage("Test set-up failed")
                .that(negativeCountArray.getCount()).isEqualTo(-1);

        var p = Parcel.obtain();
        // Writes: int (mCount), int (mDecompressedSize), byte[] (mCompressedData)
        negativeCountArray.writeToParcel(p);
        p.setDataPosition(0);
        // Reads: int (mCount)
        // Leaves: int (mDecompressedSize), byte[] (mCompressedData)
        new InputMethodSubtypeArray(p);

        assertWithMessage("Didn't read all data that was previously written")
                .that(p.dataPosition())
                .isEqualTo(p.dataSize());
    }

    InputMethodSubtypeArray cloneViaParcel(final InputMethodSubtypeArray original) {
        Parcel parcel = null;
        try {
            parcel = Parcel.obtain();
            original.writeToParcel(parcel);
            parcel.setDataPosition(0);
            return new InputMethodSubtypeArray(parcel);
        } finally {
            if (parcel != null) {
                parcel.recycle();
            }
        }
    }

    private static InputMethodSubtype createDummySubtype(final int id, final String locale) {
        final InputMethodSubtypeBuilder builder = new InputMethodSubtypeBuilder();
        return builder.setSubtypeNameResId(0)
                .setSubtypeIconResId(0)
                .setSubtypeId(id)
                .setSubtypeLocale(locale)
                .setIsAsciiCapable(true)
                .build();
    }
}

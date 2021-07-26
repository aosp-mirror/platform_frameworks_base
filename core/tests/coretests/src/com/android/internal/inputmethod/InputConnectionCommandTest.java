/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static org.junit.Assert.assertThrows;

import android.os.Parcel;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class InputConnectionCommandTest {
    @Test
    public void testCreateFromParcelDoesNotSwallowExceptions() {
        final Parcel parcel = Parcel.obtain();
        try {
            parcel.writeInt(InputConnectionCommandType.FIRST_COMMAND);
            parcel.writeInt(InputConnectionCommand.FieldMask.PARCELABLE);
            parcel.writeInt(InputConnectionCommand.ParcelableType.NULL);  // invalid
            assertThrows(RuntimeException.class,
                    () -> InputConnectionCommand.CREATOR.createFromParcel(parcel));
        } finally {
            parcel.recycle();
        }
    }
}

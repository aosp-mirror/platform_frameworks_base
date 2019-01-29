/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.os.Parcel;
import android.os.UserHandle;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Supplemental tests that cannot be covered by CTS (e.g. due to hidden API dependencies).
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class EditorInfoTest {
    private static final int TEST_USER_ID = 42;

    /**
     * Makes sure that {@code null} {@link EditorInfo#targetInputMethodUser} can be copied via
     * {@link Parcel}.
     */
    @Test
    public void testNullTargetInputMethodUserParcelable() throws Exception {
        final EditorInfo editorInfo = new EditorInfo();
        editorInfo.targetInputMethodUser = null;
        assertNull(cloneViaParcel(editorInfo).targetInputMethodUser);
    }

    /**
     * Makes sure that non-{@code null} {@link EditorInfo#targetInputMethodUser} can be copied via
     * {@link Parcel}.
     */
    @Test
    public void testNonNullTargetInputMethodUserParcelable() throws Exception {
        final EditorInfo editorInfo = new EditorInfo();
        editorInfo.targetInputMethodUser = UserHandle.of(TEST_USER_ID);
        assertEquals(UserHandle.of(TEST_USER_ID), cloneViaParcel(editorInfo).targetInputMethodUser);
    }

    private static EditorInfo cloneViaParcel(EditorInfo original) {
        Parcel parcel = null;
        try {
            parcel = Parcel.obtain();
            original.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return EditorInfo.CREATOR.createFromParcel(parcel);
        } finally {
            if (parcel != null) {
                parcel.recycle();
            }
        }
    }
}

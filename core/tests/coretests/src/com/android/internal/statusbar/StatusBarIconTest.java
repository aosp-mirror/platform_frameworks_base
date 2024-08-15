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

package com.android.internal.statusbar;

import static com.google.common.truth.Truth.assertThat;

import android.os.Parcel;
import android.os.UserHandle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;


@RunWith(AndroidJUnit4.class)
@SmallTest
public class StatusBarIconTest {

    /**
     * Test {@link StatusBarIcon} can be stored then restored with {@link Parcel}.
     */
    @Test
    public void testParcelable() {
        final UserHandle dummyUserHandle = UserHandle.of(100);
        final String dummyIconPackageName = "com.android.internal.statusbar.test";
        final int dummyIconId = 123;
        final int dummyIconLevel = 1;
        final int dummyIconNumber = 2;
        final CharSequence dummyIconContentDescription = "dummyIcon";
        final StatusBarIcon original = new StatusBarIcon(dummyIconPackageName, dummyUserHandle,
                dummyIconId, dummyIconLevel, dummyIconNumber, dummyIconContentDescription,
                StatusBarIcon.Type.SystemIcon);

        final StatusBarIcon copy = clone(original);

        assertThat(copy.user).isEqualTo(original.user);
        assertThat(copy.pkg).isEqualTo(original.pkg);
        assertThat(copy.icon.sameAs(original.icon)).isTrue();
        assertThat(copy.iconLevel).isEqualTo(original.iconLevel);
        assertThat(copy.visible).isEqualTo(original.visible);
        assertThat(copy.number).isEqualTo(original.number);
        assertThat(copy.contentDescription).isEqualTo(original.contentDescription);
    }

    private StatusBarIcon clone(StatusBarIcon original) {
        Parcel parcel = null;
        try {
            parcel = Parcel.obtain();
            original.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return StatusBarIcon.CREATOR.createFromParcel(parcel);
        } finally {
            if (parcel != null) {
                parcel.recycle();
            }
        }
    }
}

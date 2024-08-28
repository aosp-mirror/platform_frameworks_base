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

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Icon;
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
        final StatusBarIcon original = newStatusBarIcon();

        final StatusBarIcon copy = parcelAndUnparcel(original);

        assertSerializableFieldsEqual(copy, original);
    }

    @Test
    public void testClone_withPreloaded() {
        final StatusBarIcon original = newStatusBarIcon();
        original.preloadedIcon = new ColorDrawable(Color.RED);

        final StatusBarIcon copy = original.clone();

        assertSerializableFieldsEqual(copy, original);
        assertThat(copy.preloadedIcon).isNotNull();
        assertThat(copy.preloadedIcon).isInstanceOf(ColorDrawable.class);
        assertThat(((ColorDrawable) copy.preloadedIcon).getColor()).isEqualTo(Color.RED);
    }

    @Test
    public void testClone_noPreloaded() {
        final StatusBarIcon original = newStatusBarIcon();

        final StatusBarIcon copy = original.clone();

        assertSerializableFieldsEqual(copy, original);
        assertThat(copy.preloadedIcon).isEqualTo(original.preloadedIcon);
    }

    private static StatusBarIcon newStatusBarIcon() {
        final UserHandle dummyUserHandle = UserHandle.of(100);
        final String dummyIconPackageName = "com.android.internal.statusbar.test";
        final Icon dummyIcon = Icon.createWithResource(dummyIconPackageName, 123);
        final int dummyIconLevel = 1;
        final int dummyIconNumber = 2;
        final CharSequence dummyIconContentDescription = "dummyIcon";
        return new StatusBarIcon(
                dummyUserHandle,
                dummyIconPackageName,
                dummyIcon,
                dummyIconLevel,
                dummyIconNumber,
                dummyIconContentDescription,
                StatusBarIcon.Type.SystemIcon,
                StatusBarIcon.Shape.FIXED_SPACE);
    }

    private static void assertSerializableFieldsEqual(StatusBarIcon copy, StatusBarIcon original) {
        assertThat(copy.user).isEqualTo(original.user);
        assertThat(copy.pkg).isEqualTo(original.pkg);
        assertThat(copy.icon.sameAs(original.icon)).isTrue();
        assertThat(copy.iconLevel).isEqualTo(original.iconLevel);
        assertThat(copy.visible).isEqualTo(original.visible);
        assertThat(copy.number).isEqualTo(original.number);
        assertThat(copy.contentDescription).isEqualTo(original.contentDescription);
        assertThat(copy.type).isEqualTo(original.type);
        assertThat(copy.shape).isEqualTo(original.shape);
    }

    private static StatusBarIcon parcelAndUnparcel(StatusBarIcon original) {
        Parcel parcel = Parcel.obtain();
        try {
            original.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return StatusBarIcon.CREATOR.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }
}

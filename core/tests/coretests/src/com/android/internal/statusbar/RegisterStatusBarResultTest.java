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

import android.graphics.Rect;
import android.os.Binder;
import android.os.Parcel;
import android.os.UserHandle;
import android.util.ArrayMap;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;


@RunWith(AndroidJUnit4.class)
@SmallTest
public class RegisterStatusBarResultTest {

    /**
     * Test {@link RegisterStatusBarResult} can be stored then restored with {@link Parcel}.
     */
    @Test
    public void testParcelable() {
        final String dumyIconKey = "dummyIcon1";
        final ArrayMap<String, StatusBarIcon> iconMap = new ArrayMap<>();
        iconMap.put(dumyIconKey, new StatusBarIcon("com.android.internal.statusbar.test",
                UserHandle.of(100), 123, 1, 2, "dummyIconDescription"));

        final RegisterStatusBarResult original = new RegisterStatusBarResult(iconMap,
                0x2 /* disabledFlags1 */,
                0x4 /* systemUiVisibility */,
                true /* menuVisible */,
                0x8 /* imeWindowVis */,
                0x10 /* imeBackDisposition */,
                false /* showImeSwitcher */,
                0x20 /* disabledFlags2 */,
                0x40 /* fullscreenStackSysUiVisibility */,
                0x80 /* dockedStackSysUiVisibility */,
                new Binder() /* imeToken */,
                new Rect(0x100, 0x200, 0x400, 0x800) /* fullscreenStackBounds */,
                new Rect(0x1000, 0x2000, 0x4000, 0x8000) /* dockedStackBounds */,
                true /* navbarColorManagedByIme */);

        final RegisterStatusBarResult copy = clone(original);

        assertThat(copy.mIcons).hasSize(original.mIcons.size());
        // We already test that StatusBarIcon is Parcelable.  Only check StatusBarIcon.user here.
        assertThat(copy.mIcons.get(dumyIconKey).user)
                .isEqualTo(original.mIcons.get(dumyIconKey).user);

        assertThat(copy.mDisabledFlags1).isEqualTo(original.mDisabledFlags1);
        assertThat(copy.mSystemUiVisibility).isEqualTo(original.mSystemUiVisibility);
        assertThat(copy.mMenuVisible).isEqualTo(original.mMenuVisible);
        assertThat(copy.mImeWindowVis).isEqualTo(original.mImeWindowVis);
        assertThat(copy.mImeBackDisposition).isEqualTo(original.mImeBackDisposition);
        assertThat(copy.mShowImeSwitcher).isEqualTo(original.mShowImeSwitcher);
        assertThat(copy.mDisabledFlags2).isEqualTo(original.mDisabledFlags2);
        assertThat(copy.mFullscreenStackSysUiVisibility)
                .isEqualTo(original.mFullscreenStackSysUiVisibility);
        assertThat(copy.mDockedStackSysUiVisibility)
                .isEqualTo(original.mDockedStackSysUiVisibility);
        assertThat(copy.mImeToken).isSameAs(original.mImeToken);
        assertThat(copy.mFullscreenStackBounds).isEqualTo(original.mFullscreenStackBounds);
        assertThat(copy.mDockedStackBounds).isEqualTo(original.mDockedStackBounds);
        assertThat(copy.mNavbarColorManagedByIme).isEqualTo(original.mNavbarColorManagedByIme);
    }

    private RegisterStatusBarResult clone(RegisterStatusBarResult original) {
        Parcel parcel = null;
        try {
            parcel = Parcel.obtain();
            original.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return RegisterStatusBarResult.CREATOR.createFromParcel(parcel);
        } finally {
            if (parcel != null) {
                parcel.recycle();
            }
        }
    }
}

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

import static android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;

import static com.google.common.truth.Truth.assertThat;

import android.graphics.Rect;
import android.os.Binder;
import android.os.Parcel;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.view.WindowInsets;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.view.AppearanceRegion;

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
                UserHandle.of(100), 123, 1, 2, "dummyIconDescription",
                StatusBarIcon.Type.SystemIcon));
        final LetterboxDetails letterboxDetails = new LetterboxDetails(
                /* letterboxInnerBounds= */ new Rect(1, 2, 3, 4),
                /* letterboxFullBounds= */ new Rect(5, 6, 7, 8),
                /* appAppearance= */ 321
        );
        final RegisterStatusBarResult original = new RegisterStatusBarResult(iconMap,
                0x2 /* disabledFlags1 */,
                0x4 /* appearance */,
                new AppearanceRegion[0] /* appearanceRegions */,
                0x8 /* imeWindowVis */,
                0x10 /* imeBackDisposition */,
                false /* showImeSwitcher */,
                0x20 /* disabledFlags2 */,
                new Binder() /* imeToken */,
                true /* navbarColorManagedByIme */,
                BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE,
                WindowInsets.Type.defaultVisible(),
                "test" /* packageName */,
                0 /* transientBarTypes */,
                new LetterboxDetails[] {letterboxDetails});

        final RegisterStatusBarResult copy = clone(original);

        assertThat(copy.mIcons).hasSize(original.mIcons.size());
        // We already test that StatusBarIcon is Parcelable.  Only check StatusBarIcon.user here.
        assertThat(copy.mIcons.get(dumyIconKey).user)
                .isEqualTo(original.mIcons.get(dumyIconKey).user);

        assertThat(copy.mDisabledFlags1).isEqualTo(original.mDisabledFlags1);
        assertThat(copy.mAppearance).isEqualTo(original.mAppearance);
        assertThat(copy.mAppearanceRegions).isEqualTo(original.mAppearanceRegions);
        assertThat(copy.mImeWindowVis).isEqualTo(original.mImeWindowVis);
        assertThat(copy.mImeBackDisposition).isEqualTo(original.mImeBackDisposition);
        assertThat(copy.mShowImeSwitcher).isEqualTo(original.mShowImeSwitcher);
        assertThat(copy.mDisabledFlags2).isEqualTo(original.mDisabledFlags2);
        assertThat(copy.mImeToken).isSameInstanceAs(original.mImeToken);
        assertThat(copy.mNavbarColorManagedByIme).isEqualTo(original.mNavbarColorManagedByIme);
        assertThat(copy.mBehavior).isEqualTo(original.mBehavior);
        assertThat(copy.mRequestedVisibleTypes).isEqualTo(original.mRequestedVisibleTypes);
        assertThat(copy.mPackageName).isEqualTo(original.mPackageName);
        assertThat(copy.mTransientBarTypes).isEqualTo(original.mTransientBarTypes);
        assertThat(copy.mLetterboxDetails).isEqualTo(original.mLetterboxDetails);
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

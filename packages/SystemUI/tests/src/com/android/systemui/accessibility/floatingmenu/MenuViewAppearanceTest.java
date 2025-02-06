/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.accessibility.floatingmenu;

import static com.google.common.truth.Truth.assertThat;

import android.graphics.Rect;
import android.testing.TestableLooper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link MenuViewAppearanceTest}. */
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
public class MenuViewAppearanceTest extends SysuiTestCase {
    static final Rect DRAGGABLE_BOUNDS = new Rect(0, 0, 10, 10);
    static final int MENU_HEIGHT = 1;

    @Test
    public void avoidVerticalDisplayCutout_roomAbove_placesAbove() {
        final int y = 2;
        final Rect cutout = new Rect(0, 3, 0, 10);

        final float end_y = MenuViewAppearance.avoidVerticalDisplayCutout(
                y, MENU_HEIGHT, DRAGGABLE_BOUNDS, cutout);

        assertThat(end_y + MENU_HEIGHT).isAtMost(cutout.top);
    }

    @Test
    public void avoidVerticalDisplayCutout_roomBelow_placesBelow() {
        final int y = 2;
        final Rect cutout = new Rect(0, 0, 0, 5);

        final float end_y = MenuViewAppearance.avoidVerticalDisplayCutout(
                y, MENU_HEIGHT, DRAGGABLE_BOUNDS, cutout);

        assertThat(end_y).isAtLeast(cutout.bottom);
    }

    @Test
    public void avoidVerticalDisplayCutout_noRoom_noChange() {
        final int y = 2;
        final Rect cutout = new Rect(0, 0, 0, 10);

        final float end_y = MenuViewAppearance.avoidVerticalDisplayCutout(
                y, MENU_HEIGHT, DRAGGABLE_BOUNDS, cutout);

        assertThat(end_y).isEqualTo(end_y);
    }
}

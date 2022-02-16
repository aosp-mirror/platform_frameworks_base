/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wm.utils;

import static android.view.DisplayCutout.BOUNDS_POSITION_BOTTOM;
import static android.view.DisplayCutout.BOUNDS_POSITION_LEFT;
import static android.view.DisplayCutout.BOUNDS_POSITION_RIGHT;
import static android.view.DisplayCutout.BOUNDS_POSITION_TOP;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_180;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;

import static com.android.server.wm.utils.DisplayRotationUtil.getBoundIndexFromRotation;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;

import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Test;

/**
 * Tests for {@link DisplayRotationUtil}
 *
 * Build/Install/Run:
 *  atest WmTests:DisplayRotationUtilTest
 */
@SmallTest
@Presubmit
public class DisplayRotationUtilTest {
    private static final Rect ZERO_RECT = new Rect();

    @Test
    public void testGetBoundIndexFromRotation_rot0() {
        assertThat(getBoundIndexFromRotation(BOUNDS_POSITION_LEFT, ROTATION_0),
                equalTo(BOUNDS_POSITION_LEFT));
        assertThat(getBoundIndexFromRotation(BOUNDS_POSITION_TOP, ROTATION_0),
                equalTo(BOUNDS_POSITION_TOP));
        assertThat(getBoundIndexFromRotation(BOUNDS_POSITION_RIGHT, ROTATION_0),
                equalTo(BOUNDS_POSITION_RIGHT));
        assertThat(getBoundIndexFromRotation(BOUNDS_POSITION_BOTTOM, ROTATION_0),
                equalTo(BOUNDS_POSITION_BOTTOM));
    }

    @Test
    public void testGetBoundIndexFromRotation_rot90() {
        assertThat(getBoundIndexFromRotation(BOUNDS_POSITION_LEFT, ROTATION_90),
                equalTo(BOUNDS_POSITION_BOTTOM));
        assertThat(getBoundIndexFromRotation(BOUNDS_POSITION_TOP, ROTATION_90),
                equalTo(BOUNDS_POSITION_LEFT));
        assertThat(getBoundIndexFromRotation(BOUNDS_POSITION_RIGHT, ROTATION_90),
                equalTo(BOUNDS_POSITION_TOP));
        assertThat(getBoundIndexFromRotation(BOUNDS_POSITION_BOTTOM, ROTATION_90),
                equalTo(BOUNDS_POSITION_RIGHT));
    }

    @Test
    public void testGetBoundIndexFromRotation_rot180() {
        assertThat(getBoundIndexFromRotation(BOUNDS_POSITION_LEFT, ROTATION_180),
                equalTo(BOUNDS_POSITION_RIGHT));
        assertThat(getBoundIndexFromRotation(BOUNDS_POSITION_TOP, ROTATION_180),
                equalTo(BOUNDS_POSITION_BOTTOM));
        assertThat(getBoundIndexFromRotation(BOUNDS_POSITION_RIGHT, ROTATION_180),
                equalTo(BOUNDS_POSITION_LEFT));
        assertThat(getBoundIndexFromRotation(BOUNDS_POSITION_BOTTOM, ROTATION_180),
                equalTo(BOUNDS_POSITION_TOP));
    }

    @Test
    public void testGetBoundIndexFromRotation_rot270() {
        assertThat(getBoundIndexFromRotation(BOUNDS_POSITION_LEFT, ROTATION_270),
                equalTo(BOUNDS_POSITION_TOP));
        assertThat(getBoundIndexFromRotation(BOUNDS_POSITION_TOP, ROTATION_270),
                equalTo(BOUNDS_POSITION_RIGHT));
        assertThat(getBoundIndexFromRotation(BOUNDS_POSITION_RIGHT, ROTATION_270),
                equalTo(BOUNDS_POSITION_BOTTOM));
        assertThat(getBoundIndexFromRotation(BOUNDS_POSITION_BOTTOM, ROTATION_270),
                equalTo(BOUNDS_POSITION_LEFT));

    }

    @Test
    public void testGetRotatedBounds_top_rot0() {
        DisplayRotationUtil util = new DisplayRotationUtil();
        Rect[] bounds = new Rect[] {ZERO_RECT, new Rect(50, 0, 150, 10), ZERO_RECT, ZERO_RECT};
        assertThat(util.getRotatedBounds(bounds, ROTATION_0, 200, 300),
                equalTo(bounds));
    }

    @Test
    public void testGetRotatedBounds_top_rot90() {
        DisplayRotationUtil util = new DisplayRotationUtil();
        Rect[] bounds = new Rect[] {ZERO_RECT, new Rect(50, 0, 150, 10), ZERO_RECT, ZERO_RECT};
        assertThat(util.getRotatedBounds(bounds, ROTATION_90, 200, 300),
                equalTo(new Rect[] {new Rect(0, 50, 10, 150), ZERO_RECT, ZERO_RECT, ZERO_RECT}));
    }

    @Test
    public void testGetRotatedBounds_top_rot180() {
        DisplayRotationUtil util = new DisplayRotationUtil();
        Rect[] bounds = new Rect[] {ZERO_RECT, new Rect(50, 0, 150, 10), ZERO_RECT, ZERO_RECT};
        assertThat(util.getRotatedBounds(bounds, ROTATION_180, 200, 300),
                equalTo(new Rect[] {ZERO_RECT, ZERO_RECT, ZERO_RECT, new Rect(50, 290, 150, 300)}));
    }

    @Test
    public void testGetRotatedBounds_top_rot270() {
        DisplayRotationUtil util = new DisplayRotationUtil();
        Rect[] bounds = new Rect[] {ZERO_RECT, new Rect(50, 0, 150, 10), ZERO_RECT, ZERO_RECT};
        assertThat(util.getRotatedBounds(bounds, ROTATION_270, 200, 300),
                equalTo(new Rect[] {ZERO_RECT, ZERO_RECT, new Rect(290, 50, 300, 150), ZERO_RECT}));
    }

    @Test
    public void testGetRotatedBounds_left_rot0() {
        DisplayRotationUtil util = new DisplayRotationUtil();
        Rect[] bounds = new Rect[] {new Rect(0, 50, 10, 150), ZERO_RECT, ZERO_RECT, ZERO_RECT};
        assertThat(util.getRotatedBounds(bounds, ROTATION_0, 300, 200),
                equalTo(bounds));
    }

    @Test
    public void testGetRotatedBounds_left_rot90() {
        DisplayRotationUtil util = new DisplayRotationUtil();
        Rect[] bounds = new Rect[] {new Rect(0, 50, 10, 150), ZERO_RECT, ZERO_RECT, ZERO_RECT};
        assertThat(util.getRotatedBounds(bounds, ROTATION_90, 300, 200),
                equalTo(new Rect[] {ZERO_RECT, ZERO_RECT, ZERO_RECT, new Rect(50, 290, 150, 300)}));
    }

    @Test
    public void testGetRotatedBounds_left_rot180() {
        DisplayRotationUtil util = new DisplayRotationUtil();
        Rect[] bounds = new Rect[] {new Rect(0, 50, 10, 150), ZERO_RECT, ZERO_RECT, ZERO_RECT};
        assertThat(util.getRotatedBounds(bounds, ROTATION_180, 300, 200),
                equalTo(new Rect[] {ZERO_RECT, ZERO_RECT, new Rect(290, 50, 300, 150), ZERO_RECT}));
    }

    @Test
    public void testGetRotatedBounds_left_rot270() {
        DisplayRotationUtil util = new DisplayRotationUtil();
        Rect[] bounds = new Rect[] {new Rect(0, 50, 10, 150), ZERO_RECT, ZERO_RECT, ZERO_RECT};
        assertThat(util.getRotatedBounds(bounds, ROTATION_270, 300, 200),
                equalTo(new Rect[] {ZERO_RECT, new Rect(50, 0, 150, 10), ZERO_RECT, ZERO_RECT}));
    }
}

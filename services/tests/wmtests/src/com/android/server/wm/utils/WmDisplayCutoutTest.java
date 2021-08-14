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
import static android.view.DisplayCutout.NO_CUTOUT;
import static android.view.DisplayCutout.fromBoundingRect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.graphics.Insets;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.util.Size;
import android.view.DisplayCutout;

import androidx.test.filters.SmallTest;

import org.junit.Test;

/**
 * Tests for {@link WmDisplayCutout}
 *
 * Build/Install/Run:
 *  atest WmTests:WmDisplayCutoutTest
 */
@SmallTest
@Presubmit
public class WmDisplayCutoutTest {
    private static final Rect ZERO_RECT = new Rect();
    private final DisplayCutout mCutoutTop = new DisplayCutout(
            Insets.of(0, 100, 0, 0),
            null /* boundLeft */, new Rect(50, 0, 75, 100) /* boundTop */,
            null /* boundRight */, null /* boundBottom */);

    @Test
    public void computeSafeInsets_cutoutTop() {
        WmDisplayCutout cutout = WmDisplayCutout.computeSafeInsets(
                fromBoundingRect(80, 0, 120, 20, BOUNDS_POSITION_TOP), 200, 400);

        assertEquals(new Rect(0, 20, 0, 0), cutout.getDisplayCutout().getSafeInsets());
    }

    @Test
    public void computeSafeInsets_cutoutLeft() {
        WmDisplayCutout cutout = WmDisplayCutout.computeSafeInsets(
                fromBoundingRect(0, 180, 20, 220, BOUNDS_POSITION_LEFT), 200, 400);

        assertEquals(new Rect(20, 0, 0, 0), cutout.getDisplayCutout().getSafeInsets());
    }

    @Test
    public void computeSafeInsets_cutoutBottom() {
        WmDisplayCutout cutout = WmDisplayCutout.computeSafeInsets(
                fromBoundingRect(80, 380, 120, 400, BOUNDS_POSITION_BOTTOM), 200, 400);

        assertEquals(new Rect(0, 0, 0, 20), cutout.getDisplayCutout().getSafeInsets());
    }

    @Test
    public void computeSafeInsets_cutoutRight() {
        WmDisplayCutout cutout = WmDisplayCutout.computeSafeInsets(
                fromBoundingRect(180, 180, 200, 220, BOUNDS_POSITION_RIGHT), 200, 400);

        assertEquals(new Rect(0, 0, 20, 0), cutout.getDisplayCutout().getSafeInsets());
    }

    @Test
    public void computeSafeInsets_topLeftCornerCutout_portrait() {
        WmDisplayCutout cutout = WmDisplayCutout.computeSafeInsets(
                fromBoundingRect(0, 0, 20, 20, BOUNDS_POSITION_TOP), 200, 400);

        assertEquals(new Rect(0, 20, 0, 0), cutout.getDisplayCutout().getSafeInsets());
    }

    @Test
    public void computeSafeInsets_topRightCornerCutout_portrait() {
        WmDisplayCutout cutout = WmDisplayCutout.computeSafeInsets(
                fromBoundingRect(180, 0, 200, 20, BOUNDS_POSITION_TOP), 200, 400);

        assertEquals(new Rect(0, 20, 0, 0), cutout.getDisplayCutout().getSafeInsets());
    }

    @Test
    public void computeSafeInsets_bottomLeftCornerCutout_portrait() {
        WmDisplayCutout cutout = WmDisplayCutout.computeSafeInsets(
                fromBoundingRect(0, 380, 20, 400, BOUNDS_POSITION_BOTTOM), 200, 400);

        assertEquals(new Rect(0, 0, 0, 20), cutout.getDisplayCutout().getSafeInsets());
    }

    @Test
    public void computeSafeInsets_bottomRightCornerCutout_portrait() {
        WmDisplayCutout cutout = WmDisplayCutout.computeSafeInsets(
                fromBoundingRect(180, 380, 200, 400, BOUNDS_POSITION_BOTTOM), 200, 400);

        assertEquals(new Rect(0, 0, 0, 20), cutout.getDisplayCutout().getSafeInsets());
    }

    @Test
    public void computeSafeInsets_topLeftCornerCutout_landscape() {
        WmDisplayCutout cutout = WmDisplayCutout.computeSafeInsets(
                fromBoundingRect(0, 0, 20, 20, BOUNDS_POSITION_LEFT), 400, 200);

        assertEquals(new Rect(20, 0, 0, 0), cutout.getDisplayCutout().getSafeInsets());
    }

    @Test
    public void computeSafeInsets_topRightCornerCutout_landscape() {
        WmDisplayCutout cutout = WmDisplayCutout.computeSafeInsets(
                fromBoundingRect(380, 0, 400, 20, BOUNDS_POSITION_RIGHT), 400, 200);

        assertEquals(new Rect(0, 0, 20, 0), cutout.getDisplayCutout().getSafeInsets());
    }

    @Test
    public void computeSafeInsets_bottomLeftCornerCutout_landscape() {
        WmDisplayCutout cutout = WmDisplayCutout.computeSafeInsets(
                fromBoundingRect(0, 180, 20, 200, BOUNDS_POSITION_LEFT), 400, 200);

        assertEquals(new Rect(20, 0, 0, 0), cutout.getDisplayCutout().getSafeInsets());
    }

    @Test
    public void computeSafeInsets_bottomRightCornerCutout_landscape() {
        WmDisplayCutout cutout = WmDisplayCutout.computeSafeInsets(
                fromBoundingRect(380, 180, 400, 200, BOUNDS_POSITION_RIGHT), 400, 200);

        assertEquals(new Rect(0, 0, 20, 0), cutout.getDisplayCutout().getSafeInsets());
    }

    @Test
    public void computeSafeInsets_waterfall() {
        WmDisplayCutout cutout = WmDisplayCutout.computeSafeInsets(
                DisplayCutout.constructDisplayCutout(
                        new Rect[] {ZERO_RECT, ZERO_RECT, ZERO_RECT, ZERO_RECT},
                        Insets.of(1, 2, 3, 4), null),
                200, 400);

        assertEquals(new Rect(1, 2, 3, 4), cutout.getDisplayCutout().getSafeInsets());
    }

    @Test
    public void computeSafeInsets_cutoutTop_greaterThan_waterfallTop() {
        WmDisplayCutout cutout = WmDisplayCutout.computeSafeInsets(
                DisplayCutout.constructDisplayCutout(
                        new Rect[] {ZERO_RECT, new Rect(80, 0, 120, 30), ZERO_RECT, ZERO_RECT},
                        Insets.of(0, 20, 0, 0), null),
                200, 400);

        assertEquals(new Rect(0, 30, 0, 0), cutout.getDisplayCutout().getSafeInsets());
    }

    @Test
    public void computeSafeInsets_cutoutTop_lessThan_waterfallTop() {
        WmDisplayCutout cutout = WmDisplayCutout.computeSafeInsets(
                DisplayCutout.constructDisplayCutout(
                        new Rect[] {ZERO_RECT, new Rect(80, 0, 120, 30), ZERO_RECT, ZERO_RECT},
                        Insets.of(0, 40, 0, 0), null),
                200, 400);

        assertEquals(new Rect(0, 40, 0, 0), cutout.getDisplayCutout().getSafeInsets());
    }

    @Test
    public void computeSafeInsets_cutoutLeft_greaterThan_waterfallLeft() {
        WmDisplayCutout cutout = WmDisplayCutout.computeSafeInsets(
                DisplayCutout.constructDisplayCutout(
                        new Rect[] {new Rect(0, 180, 30, 220), ZERO_RECT, ZERO_RECT, ZERO_RECT},
                        Insets.of(20, 0, 0, 0), null),
                200, 400);

        assertEquals(new Rect(30, 0, 0, 0), cutout.getDisplayCutout().getSafeInsets());
    }

    @Test
    public void computeSafeInsets_cutoutLeft_lessThan_waterfallLeft() {
        WmDisplayCutout cutout = WmDisplayCutout.computeSafeInsets(
                DisplayCutout.constructDisplayCutout(
                        new Rect[] {new Rect(0, 180, 30, 220), ZERO_RECT, ZERO_RECT, ZERO_RECT},
                        Insets.of(40, 0, 0, 0), null),
                200, 400);

        assertEquals(new Rect(40, 0, 0, 0), cutout.getDisplayCutout().getSafeInsets());
    }

    @Test
    public void computeSafeInsets_cutoutBottom_greaterThan_waterfallBottom() {
        WmDisplayCutout cutout = WmDisplayCutout.computeSafeInsets(
                DisplayCutout.constructDisplayCutout(
                        new Rect[] {ZERO_RECT, ZERO_RECT, ZERO_RECT, new Rect(80, 370, 120, 400)},
                        Insets.of(0, 0, 0, 20), null),
                200, 400);

        assertEquals(new Rect(0, 0, 0, 30), cutout.getDisplayCutout().getSafeInsets());
    }

    @Test
    public void computeSafeInsets_cutoutBottom_lessThan_waterfallBottom() {
        WmDisplayCutout cutout = WmDisplayCutout.computeSafeInsets(
                DisplayCutout.constructDisplayCutout(
                        new Rect[] {ZERO_RECT, ZERO_RECT, ZERO_RECT, new Rect(80, 370, 120, 400)},
                        Insets.of(0, 0, 0, 40), null),
                200, 400);

        assertEquals(new Rect(0, 0, 0, 40), cutout.getDisplayCutout().getSafeInsets());
    }

    @Test
    public void computeSafeInsets_cutoutRight_greaterThan_waterfallRight() {
        WmDisplayCutout cutout = WmDisplayCutout.computeSafeInsets(
                DisplayCutout.constructDisplayCutout(
                        new Rect[] {ZERO_RECT, ZERO_RECT, new Rect(170, 180, 200, 220), ZERO_RECT},
                        Insets.of(0, 0, 20, 0), null),
                200, 400);

        assertEquals(new Rect(0, 0, 30, 0), cutout.getDisplayCutout().getSafeInsets());
    }

    @Test
    public void computeSafeInsets_cutoutRight_lessThan_waterfallRight() {
        WmDisplayCutout cutout = WmDisplayCutout.computeSafeInsets(
                DisplayCutout.constructDisplayCutout(
                        new Rect[] {ZERO_RECT, ZERO_RECT, new Rect(170, 180, 200, 220), ZERO_RECT},
                        Insets.of(0, 0, 40, 0), null),
                200, 400);

        assertEquals(new Rect(0, 0, 40, 0), cutout.getDisplayCutout().getSafeInsets());
    }

    @Test
    public void computeSafeInsets_bounds() {
        DisplayCutout cutout =
                WmDisplayCutout.computeSafeInsets(mCutoutTop, 1000, 2000).getDisplayCutout();

        assertEquals(mCutoutTop.getBoundingRects(), cutout.getBoundingRects());
    }

    @Test
    public void test_equals() {
        assertEquals(new WmDisplayCutout(NO_CUTOUT, null), new WmDisplayCutout(NO_CUTOUT, null));
        assertEquals(new WmDisplayCutout(mCutoutTop, new Size(1, 2)),
                new WmDisplayCutout(mCutoutTop, new Size(1, 2)));

        assertNotEquals(new WmDisplayCutout(mCutoutTop, new Size(1, 2)),
                new WmDisplayCutout(mCutoutTop, new Size(5, 6)));
        assertNotEquals(new WmDisplayCutout(mCutoutTop, new Size(1, 2)),
                new WmDisplayCutout(NO_CUTOUT, new Size(1, 2)));
    }

    @Test
    public void test_hashCode() {
        assertEquals(new WmDisplayCutout(NO_CUTOUT, null).hashCode(),
                new WmDisplayCutout(NO_CUTOUT, null).hashCode());
        assertEquals(new WmDisplayCutout(mCutoutTop, new Size(1, 2)).hashCode(),
                new WmDisplayCutout(mCutoutTop, new Size(1, 2)).hashCode());
    }
}

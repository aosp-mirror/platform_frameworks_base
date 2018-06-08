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


import static android.view.DisplayCutout.NO_CUTOUT;
import static android.view.DisplayCutout.fromBoundingRect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.util.Size;
import android.view.DisplayCutout;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

/**
 * Tests for {@link WmDisplayCutout}
 *
 * Run with: atest WmDisplayCutoutTest
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class WmDisplayCutoutTest {

    private final DisplayCutout mCutoutTop = new DisplayCutout(
            new Rect(0, 100, 0, 0),
            Arrays.asList(new Rect(50, 0, 75, 100)));

    @Test
    public void calculateRelativeTo_top() {
        WmDisplayCutout cutout = WmDisplayCutout.computeSafeInsets(
                fromBoundingRect(0, 0, 100, 20), 200, 400)
                .calculateRelativeTo(new Rect(5, 5, 95, 195));

        assertEquals(new Rect(0, 15, 0, 0), cutout.getDisplayCutout().getSafeInsets());
    }

    @Test
    public void calculateRelativeTo_left() {
        WmDisplayCutout cutout = WmDisplayCutout.computeSafeInsets(
                fromBoundingRect(0, 0, 20, 100), 400, 200)
                .calculateRelativeTo(new Rect(5, 5, 195, 95));

        assertEquals(new Rect(15, 0, 0, 0), cutout.getDisplayCutout().getSafeInsets());
    }

    @Test
    public void calculateRelativeTo_bottom() {
        WmDisplayCutout cutout = WmDisplayCutout.computeSafeInsets(
                fromBoundingRect(0, 180, 100, 200), 100, 200)
                .calculateRelativeTo(new Rect(5, 5, 95, 195));

        assertEquals(new Rect(0, 0, 0, 15), cutout.getDisplayCutout().getSafeInsets());
    }

    @Test
    public void calculateRelativeTo_right() {
        WmDisplayCutout cutout = WmDisplayCutout.computeSafeInsets(
                fromBoundingRect(180, 0, 200, 100), 200, 100)
                .calculateRelativeTo(new Rect(5, 5, 195, 95));

        assertEquals(new Rect(0, 0, 15, 0), cutout.getDisplayCutout().getSafeInsets());
    }

    @Test
    public void calculateRelativeTo_bounds() {
        WmDisplayCutout cutout = WmDisplayCutout.computeSafeInsets(
                fromBoundingRect(0, 0, 100, 20), 200, 400)
                .calculateRelativeTo(new Rect(5, 10, 95, 180));

        assertEquals(new Rect(-5, -10, 95, 10), cutout.getDisplayCutout().getBounds().getBounds());
    }

    @Test
    public void computeSafeInsets_top() {
        WmDisplayCutout cutout = WmDisplayCutout.computeSafeInsets(
                fromBoundingRect(0, 0, 100, 20), 200, 400);

        assertEquals(new Rect(0, 20, 0, 0), cutout.getDisplayCutout().getSafeInsets());
    }

    @Test
    public void computeSafeInsets_left() {
        WmDisplayCutout cutout = WmDisplayCutout.computeSafeInsets(
                fromBoundingRect(0, 0, 20, 100), 400, 200);

        assertEquals(new Rect(20, 0, 0, 0), cutout.getDisplayCutout().getSafeInsets());
    }

    @Test
    public void computeSafeInsets_bottom() {
        WmDisplayCutout cutout = WmDisplayCutout.computeSafeInsets(
                fromBoundingRect(0, 180, 100, 200), 100, 200);

        assertEquals(new Rect(0, 0, 0, 20), cutout.getDisplayCutout().getSafeInsets());
    }

    @Test
    public void computeSafeInsets_right() {
        WmDisplayCutout cutout = WmDisplayCutout.computeSafeInsets(
                fromBoundingRect(180, 0, 200, 100), 200, 100);

        assertEquals(new Rect(0, 0, 20, 0), cutout.getDisplayCutout().getSafeInsets());
    }

    @Test
    public void computeSafeInsets_bounds() {
        DisplayCutout cutout = WmDisplayCutout.computeSafeInsets(mCutoutTop, 1000,
                2000).getDisplayCutout();

        assertEquals(mCutoutTop.getBounds().getBounds(),
                cutout.getBounds().getBounds());
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
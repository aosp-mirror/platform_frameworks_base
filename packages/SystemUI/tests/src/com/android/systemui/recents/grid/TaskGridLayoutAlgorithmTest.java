/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.systemui.recents.grid;

import android.graphics.Rect;
import android.test.suitebuilder.annotation.SmallTest;
import com.android.systemui.SysuiTestCase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;

import org.junit.Test;

@SmallTest
public class TaskGridLayoutAlgorithmTest extends SysuiTestCase {

    private static final List<Integer> ZERO_MARGIN = TaskGridLayoutAlgorithm.ZERO_MARGIN;

    @Test
    public void testOneTile() {
        List<Rect> rects = TaskGridLayoutAlgorithm.getRectsForTaskCount(
                1, 1000, 1000, 1 /* screenRatio */, 0 /* padding */, ZERO_MARGIN, 0);
        assertEquals(1, rects.size());
        Rect singleRect = rects.get(0);
        assertEquals(1000, singleRect.width());
    }

    @Test
    public void testTwoTilesLandscape() {
        List<Rect> rects = TaskGridLayoutAlgorithm.getRectsForTaskCount(
                2, 1200, 500, 1.2f /* screenRatio */, 0 /* padding */, ZERO_MARGIN, 0);
        assertEquals(2, rects.size());
        for (Rect rect : rects) {
            assertEquals(600, rect.width());
            assertEquals(499, rect.height());
        }
    }

    @Test
    public void testTwoTilesLandscapeWithPadding() {
        List<Rect> rects = TaskGridLayoutAlgorithm.getRectsForTaskCount(
                2, 1200, 500, 1.19f /* screenRatio */, 10 /* padding */, ZERO_MARGIN, 0);
        assertEquals(2, rects.size());
        Rect rectA = rects.get(0);
        Rect rectB = rects.get(1);
        assertEquals(595, rectA.width());
        assertEquals(595, rectB.width());
        assertEquals(605, rectB.left);
    }

    @Test
    public void testTwoTilesPortrait() {
        List<Rect> rects = TaskGridLayoutAlgorithm.getRectsForTaskCount(
                2, 500, 1200, 1 /* screenRatio */, 0 /* padding */, ZERO_MARGIN, 0);
        assertEquals(2, rects.size());
        for (Rect rect : rects) {
            assertEquals(250, rect.width());
            assertEquals(250, rect.height());
        }
    }

    @Test
    public void testThreeTiles() {
        List<Rect> rects = TaskGridLayoutAlgorithm.getRectsForTaskCount(
                3, 1200, 500, 2 /* screenRatio */, 0 /* padding */, ZERO_MARGIN, 0);
        assertEquals(3, rects.size());
        for (Rect rect : rects) {
            assertEquals(400, rect.width());
            assertEquals(200, rect.height());
        }
    }

    @Test
    public void testFourTiles() {
        List<Rect> rects = TaskGridLayoutAlgorithm.getRectsForTaskCount(
                4, 1200, 500, 2.4f /* screenRatio */, 0 /* padding */, ZERO_MARGIN, 0);
        assertEquals(4, rects.size());
        for (Rect rect : rects) {
            assertEquals(600, rect.width());
            assertEquals(249, rect.height());
        }
        Rect rectD = rects.get(3);
        assertEquals(600, rectD.left);
        assertEquals(250, rectD.top);
    }

    @Test
    public void testNineTiles() {
        List<Rect> rects = TaskGridLayoutAlgorithm.getRectsForTaskCount(
                9, 1200, 600, 2 /* screenRatio */, 0 /* padding */, ZERO_MARGIN, 0);
        assertEquals(9, rects.size());
        for (Rect rect : rects) {
            assertEquals(400, rect.width());
            assertEquals(200, rect.height());
        }
        Rect rectE = rects.get(4);
        assertEquals(400, rectE.left);
        assertEquals(200, rectE.top);
        Rect rectI = rects.get(8);
        assertEquals(800, rectI.left);
        assertEquals(400, rectI.top);
    }}


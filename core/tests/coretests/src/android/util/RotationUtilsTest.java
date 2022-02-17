/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.util;

import static android.util.RotationUtils.rotateBounds;
import static android.util.RotationUtils.rotatePoint;
import static android.view.Surface.ROTATION_180;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;

import static org.junit.Assert.assertEquals;

import android.graphics.Point;
import android.graphics.Rect;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link RotationUtils}.
 *
 * Build/Install/Run:
 *  atest FrameworksCoreTests:RotationUtilsTest
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class RotationUtilsTest {

    @Test
    public void testRotateBounds() {
        Rect testParent = new Rect(0, 0, 1000, 600);
        Rect testInner = new Rect(40, 20, 120, 80);

        Rect testResult = new Rect(testInner);
        rotateBounds(testResult, testParent, ROTATION_90);
        assertEquals(new Rect(20, 880, 80, 960), testResult);

        testResult.set(testInner);
        rotateBounds(testResult, testParent, ROTATION_180);
        assertEquals(new Rect(880, 520, 960, 580), testResult);

        testResult.set(testInner);
        rotateBounds(testResult, testParent, ROTATION_270);
        assertEquals(new Rect(520, 40, 580, 120), testResult);
    }

    @Test
    public void testRotatePoint() {
        int parentW = 1000;
        int parentH = 600;
        Point testPt = new Point(60, 40);

        Point testResult = new Point(testPt);
        rotatePoint(testResult, ROTATION_90, parentW, parentH);
        assertEquals(new Point(40, 940), testResult);

        testResult.set(testPt.x, testPt.y);
        rotatePoint(testResult, ROTATION_180, parentW, parentH);
        assertEquals(new Point(940, 560), testResult);

        testResult.set(testPt.x, testPt.y);
        rotatePoint(testResult, ROTATION_270, parentW, parentH);
        assertEquals(new Point(560, 60), testResult);
    }
}

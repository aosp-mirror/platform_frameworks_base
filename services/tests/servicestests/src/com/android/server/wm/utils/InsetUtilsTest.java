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
 * limitations under the License
 */

package com.android.server.wm.utils;

import static android.hardware.camera2.params.OutputConfiguration.ROTATION_90;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_180;
import static android.view.Surface.ROTATION_270;

import static junit.framework.Assert.assertEquals;

import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class InsetUtilsTest {

    @Test
    public void testAdd() throws Exception {
        final Rect rect1 = new Rect(10, 20, 30, 40);
        final Rect rect2 = new Rect(50, 60, 70, 80);
        InsetUtils.addInsets(rect1, rect2);
        assertEquals(new Rect(60, 80, 100, 120), rect1);
    }

    @Test
    public void rotate() {
        final Rect original = new Rect(1, 2, 3, 4);

        assertEquals("rot0", original, rotateCopy(original, ROTATION_0));

        final Rect rot90 = rotateCopy(original, ROTATION_90);
        assertEquals("rot90", new Rect(2, 3, 4, 1), rot90);

        final Rect rot180 = rotateCopy(original, ROTATION_180);
        assertEquals("rot180", new Rect(3, 4, 1, 2), rot180);
        assertEquals("rot90(rot90)=rot180", rotateCopy(rot90, ROTATION_90), rot180);

        final Rect rot270 = rotateCopy(original, ROTATION_270);
        assertEquals("rot270", new Rect(4, 1, 2, 3), rot270);
        assertEquals("rot90(rot180)=rot270", rotateCopy(rot180, ROTATION_90), rot270);
    }

    private static Rect rotateCopy(Rect insets, int rotationDelta) {
        final Rect copy = new Rect(insets);
        InsetUtils.rotateInsets(copy, rotationDelta);
        return copy;
    }
}


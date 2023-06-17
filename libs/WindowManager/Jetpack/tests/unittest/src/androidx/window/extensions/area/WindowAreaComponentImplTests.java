/*
 * Copyright (C) 2023 The Android Open Source Project
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

package androidx.window.extensions.area;

import static org.junit.Assert.assertEquals;

import android.platform.test.annotations.Presubmit;
import android.util.DisplayMetrics;
import android.view.Surface;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class WindowAreaComponentImplTests {

    private final DisplayMetrics mTestDisplayMetrics = new DisplayMetrics();

    @Before
    public void setup() {
        mTestDisplayMetrics.widthPixels = 1;
        mTestDisplayMetrics.heightPixels = 2;
        mTestDisplayMetrics.noncompatWidthPixels = 3;
        mTestDisplayMetrics.noncompatHeightPixels = 4;
    }

    /**
     * Cases where the rear display metrics does not need to be transformed.
     */
    @Test
    public void testRotateRearDisplayMetrics_noTransformNeeded() {
        final DisplayMetrics originalMetrics = new DisplayMetrics();
        originalMetrics.setTo(mTestDisplayMetrics);

        WindowAreaComponentImpl.rotateRearDisplayMetricsIfNeeded(
                Surface.ROTATION_0, Surface.ROTATION_0, mTestDisplayMetrics);
        assertEquals(originalMetrics, mTestDisplayMetrics);

        WindowAreaComponentImpl.rotateRearDisplayMetricsIfNeeded(
                Surface.ROTATION_180, Surface.ROTATION_180, mTestDisplayMetrics);
        assertEquals(originalMetrics, mTestDisplayMetrics);

        WindowAreaComponentImpl.rotateRearDisplayMetricsIfNeeded(
                Surface.ROTATION_0, Surface.ROTATION_180, mTestDisplayMetrics);
        assertEquals(originalMetrics, mTestDisplayMetrics);

        WindowAreaComponentImpl.rotateRearDisplayMetricsIfNeeded(
                Surface.ROTATION_180, Surface.ROTATION_0, mTestDisplayMetrics);
        assertEquals(originalMetrics, mTestDisplayMetrics);
    }

    /**
     * Cases where the rear display metrics need to be transformed.
     */
    @Test
    public void testRotateRearDisplayMetrics_transformNeeded() {
        DisplayMetrics originalMetrics = new DisplayMetrics();
        originalMetrics.setTo(mTestDisplayMetrics);

        DisplayMetrics expectedMetrics = new DisplayMetrics();
        expectedMetrics.setTo(mTestDisplayMetrics);
        expectedMetrics.widthPixels = mTestDisplayMetrics.heightPixels;
        expectedMetrics.heightPixels = mTestDisplayMetrics.widthPixels;
        expectedMetrics.noncompatWidthPixels = mTestDisplayMetrics.noncompatHeightPixels;
        expectedMetrics.noncompatHeightPixels = mTestDisplayMetrics.noncompatWidthPixels;

        WindowAreaComponentImpl.rotateRearDisplayMetricsIfNeeded(
                Surface.ROTATION_90, Surface.ROTATION_0, mTestDisplayMetrics);
        assertEquals(expectedMetrics, mTestDisplayMetrics);

        mTestDisplayMetrics.setTo(originalMetrics);
        WindowAreaComponentImpl.rotateRearDisplayMetricsIfNeeded(
                Surface.ROTATION_270, Surface.ROTATION_0, mTestDisplayMetrics);
        assertEquals(expectedMetrics, mTestDisplayMetrics);
    }
}

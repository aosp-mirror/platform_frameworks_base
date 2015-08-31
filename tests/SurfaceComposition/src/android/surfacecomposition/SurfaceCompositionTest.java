/*
 * Copyright (C) 2015 The Android Open Source Project
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
package android.surfacecomposition;

import android.graphics.PixelFormat;
import android.surfacecomposition.SurfaceCompositionMeasuringActivity.AllocationScore;
import android.surfacecomposition.SurfaceCompositionMeasuringActivity.CompositorScore;
import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

public class SurfaceCompositionTest extends
        ActivityInstrumentationTestCase2<SurfaceCompositionMeasuringActivity> {
    private final static String TAG = "SurfaceCompositionTest";

    // Pass threshold for major pixel formats.
    private final static int[] TEST_PIXEL_FORMATS = new int[] {
        PixelFormat.TRANSLUCENT,
        PixelFormat.OPAQUE,
    };

    // Based on Nexus 9 performance which is usually < 9.0.
    private final static double[] MIN_ACCEPTED_COMPOSITION_SCORE = new double[] {
        8.0,
        8.0,
    };

    // Based on Nexus 6 performance which is usually < 28.0.
    private final static double[] MIN_ACCEPTED_ALLOCATION_SCORE = new double[] {
        20.0,
        20.0,
    };

    public SurfaceCompositionTest() {
        super(SurfaceCompositionMeasuringActivity.class);
    }

    private void testRestoreContexts() {
    }

    @SmallTest
    public void testSurfaceCompositionPerformance() {
        for (int i = 0; i < TEST_PIXEL_FORMATS.length; ++i) {
            int pixelFormat = TEST_PIXEL_FORMATS[i];
            String formatName = SurfaceCompositionMeasuringActivity.getPixelFormatInfo(pixelFormat);
            CompositorScore score = getActivity().measureCompositionScore(pixelFormat);
            Log.i(TAG, "testSurfaceCompositionPerformance(" + formatName + ") = " + score);
            assertTrue("Device does not support surface(" + formatName + ") composition " +
                    "performance score. " + score.mSurfaces + " < " +
                    MIN_ACCEPTED_COMPOSITION_SCORE[i] + ".",
                    score.mSurfaces >= MIN_ACCEPTED_COMPOSITION_SCORE[i]);
        }
    }

    @SmallTest
    public void testSurfaceAllocationPerformance() {
        for (int i = 0; i < TEST_PIXEL_FORMATS.length; ++i) {
            int pixelFormat = TEST_PIXEL_FORMATS[i];
            String formatName = SurfaceCompositionMeasuringActivity.getPixelFormatInfo(pixelFormat);
            AllocationScore score = getActivity().measureAllocationScore(pixelFormat);
            Log.i(TAG, "testSurfaceAllocationPerformance(" + formatName + ") = " + score);
            assertTrue("Device does not support surface(" + formatName + ") allocation " +
                    "performance score. " + score.mMedian + " < " +
                    MIN_ACCEPTED_ALLOCATION_SCORE[i] + ".",
                    score.mMedian >= MIN_ACCEPTED_ALLOCATION_SCORE[i]);
        }
    }
}

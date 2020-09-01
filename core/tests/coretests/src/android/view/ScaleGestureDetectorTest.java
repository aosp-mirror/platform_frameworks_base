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
* limitations under the License.
*/

package android.view;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.matcher.ViewMatchers.withId;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;
import android.widget.TextView;
import android.window.WindowMetricsHelper;

import androidx.test.filters.LargeTest;
import androidx.test.rule.ActivityTestRule;

import com.android.frameworks.coretests.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

@LargeTest
public class ScaleGestureDetectorTest {

    @Rule
    public final ActivityTestRule<ScaleGesture> mActivityRule =
            new ActivityTestRule<>(ScaleGesture.class);
    private ScaleGesture mScaleGestureActivity;

    @Before
    public void setUp() throws Exception {
        mScaleGestureActivity = mActivityRule.getActivity();
    }

    @Test
    public void testScaleGestureDetector() {
        // No scaling should have occurred prior to performing pinch zoom action.
        final float initialScaleFactor = 1.0f;
        assertEquals(initialScaleFactor, mScaleGestureActivity.getScaleFactor(), 0f);

        // Specify start and end coordinates with respect to the window size.
        final WindowManager wm = mScaleGestureActivity.getSystemService(WindowManager.class);
        final Rect windowBounds = WindowMetricsHelper.getBoundsExcludingNavigationBarAndCutout(
                wm.getCurrentWindowMetrics());
        final int windowWidth = windowBounds.width();
        final int windowHeight = windowBounds.height();

        // Obtain coordinates to perform pinch and zoom from the center, to 75% of the display.
        final int centerX = windowWidth / 2;
        final int centerY = windowHeight / 2;

        // Offset center coordinates by one, so that the two starting points are different.
        final float[] firstFingerStartCoords = new float[] {centerX + 1.0f, centerY - 1.0f};
        final float[] firstFingerEndCoords =
        new float[] {0.75f * windowWidth, 0.25f * windowHeight};
        final float[] secondFingerStartCoords = new float[] {centerX - 1.0f, centerY + 1.0f};
        final float[] secondFingerEndCoords =
        new float[] {0.25f * windowWidth, 0.75f * windowHeight};

        onView(withId(R.id.article)).perform(new PinchZoomAction(firstFingerStartCoords,
                firstFingerEndCoords, secondFingerStartCoords, secondFingerEndCoords,
                TextView.class));

        // Text should have been 'zoomed', meaning scale factor increased.
        assertTrue(mScaleGestureActivity.getScaleFactor() > initialScaleFactor);
    }
}
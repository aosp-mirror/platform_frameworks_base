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

import android.content.Context;
import android.support.test.filters.LargeTest;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.test.ActivityInstrumentationTestCase2;
import android.util.DisplayMetrics;
import android.view.PinchZoomAction;
import android.view.ScaleGesture;
import android.view.WindowManager;
import android.widget.TextView;

import com.android.frameworks.coretests.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static android.support.test.espresso.matcher.ViewMatchers.withId;
import static android.support.test.espresso.Espresso.onView;

@LargeTest
public class ScaleGestureDetectorTest extends ActivityInstrumentationTestCase2<ScaleGesture> {
    private ScaleGesture mScaleGestureActivity;

    public ScaleGestureDetectorTest() {
        super("com.android.frameworks.coretests", ScaleGesture.class);
    }

    @Before
    public void setUp() throws Exception {
        super.setUp();
        injectInstrumentation(InstrumentationRegistry.getInstrumentation());
        mScaleGestureActivity = getActivity();
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @Test
    public void testScaleGestureDetector() {
        // No scaling should have occurred prior to performing pinch zoom action.
        final float initialScaleFactor = 1.0f;
        assertEquals(initialScaleFactor, mScaleGestureActivity.getScaleFactor());

        // Specify start and end coordinates, irrespective of device display size.
        final DisplayMetrics dm = new DisplayMetrics();
        final WindowManager wm = (WindowManager) (mScaleGestureActivity.getApplicationContext())
                .getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getMetrics(dm);
        final int displayWidth = dm.widthPixels;
        final int displayHeight = dm.heightPixels;

        // Obtain coordinates to perform pinch and zoom from the center, to 75% of the display.
        final int centerX = displayWidth / 2;
        final int centerY = displayHeight / 2;

        // Offset center coordinates by one, so that the two starting points are different.
        final float[] firstFingerStartCoords = new float[] {centerX + 1.0f, centerY - 1.0f};
        final float[] firstFingerEndCoords =
        new float[] {0.75f * displayWidth, 0.25f * displayHeight};
        final float[] secondFingerStartCoords = new float[] {centerX - 1.0f, centerY + 1.0f};
        final float[] secondFingerEndCoords =
        new float[] {0.25f * displayWidth, 0.75f * displayHeight};

        onView(withId(R.id.article)).perform(new PinchZoomAction(firstFingerStartCoords,
                firstFingerEndCoords, secondFingerStartCoords, secondFingerEndCoords,
                TextView.class));

        // Text should have been 'zoomed', meaning scale factor increased.
        assertTrue(mScaleGestureActivity.getScaleFactor() > initialScaleFactor);
    }
}
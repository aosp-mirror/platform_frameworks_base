/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.FlakyTest;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link WindowManager#getCurrentWindowMetrics()} and
 * {@link WindowManager#getMaximumWindowMetrics()}.
 *
 * <p>Build/Install/Run:
 *  atest FrameworksCoreTests:WindowMetricsTest
 *
 * <p>This test class is a part of Window Manager Service tests and specified in
 * {@link com.android.server.wm.test.filters.FrameworksTestsFilter}.
 */
@FlakyTest(bugId = 148789183, detail = "Remove after confirmed it's stable.")
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class WindowMetricsTest {
    private Context mWindowContext;
    private WindowManager mWm;

    @Before
    public void setUp() {
        final Context instContext = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        final Display display = instContext.getSystemService(DisplayManager.class)
                .getDisplay(DEFAULT_DISPLAY);
        mWindowContext = instContext.createDisplayContext(display)
                .createWindowContext(TYPE_APPLICATION_OVERLAY, null /* options */);
        mWm = mWindowContext.getSystemService(WindowManager.class);
    }

    @Test
    public void testAddViewAndRemoveView_GetMetrics_DoNotCrash() {
        final View view = new View(mWindowContext);
        final WindowManager.LayoutParams params =
                new WindowManager.LayoutParams(TYPE_APPLICATION_OVERLAY);
        Handler.getMain().runWithScissors(() -> {
            mWm.addView(view, params);
            // Check get metrics do not crash.
            WindowMetrics currentMetrics = mWm.getCurrentWindowMetrics();
            WindowMetrics maxMetrics = mWm.getMaximumWindowMetrics();
            verifyMetricsSanity(currentMetrics, maxMetrics);

            mWm.removeViewImmediate(view);
            // Check get metrics do not crash.
            currentMetrics = mWm.getCurrentWindowMetrics();
            maxMetrics = mWm.getMaximumWindowMetrics();
            verifyMetricsSanity(currentMetrics, maxMetrics);
        }, 0);
    }

    private static void verifyMetricsSanity(WindowMetrics currentMetrics,
            WindowMetrics maxMetrics) {
        Rect currentBounds = currentMetrics.getBounds();
        Rect maxBounds = maxMetrics.getBounds();

        assertTrue(maxBounds.width() >= currentBounds.width());
        assertTrue(maxBounds.height() >= currentBounds.height());
        assertTrue(maxBounds.left >= 0);
        assertTrue(maxBounds.top >= 0);
    }
}

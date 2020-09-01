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

package android.window;

import static org.junit.Assert.assertEquals;

import android.app.Activity;
import android.graphics.Point;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.view.WindowMetrics;

import androidx.test.filters.SmallTest;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link WindowMetricsHelper}
 *
 * <p>Build/Install/Run:
 *  atest FrameworksCoreTests:WindowMetricsHelperTest
 *
 * <p>This test class is a part of Window Manager Service tests and specified in
 * {@link com.android.server.wm.test.filters.FrameworksTestsFilter}.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class WindowMetricsHelperTest {

    @Rule
    public ActivityTestRule<TestActivity> mActivityRule =
            new ActivityTestRule<>(TestActivity.class);

    @Test
    public void testGetLegacySizeMatchesDisplayGetSize() throws Throwable {
        mActivityRule.runOnUiThread(() -> {
            Activity activity = mActivityRule.getActivity();
            final WindowMetrics metrics = activity.getWindowManager().getCurrentWindowMetrics();
            final Rect legacyBounds = WindowMetricsHelper
                    .getBoundsExcludingNavigationBarAndCutout(metrics);

            final Point expectedSize = new Point();
            activity.getDisplay().getSize(expectedSize);

            assertEquals(expectedSize.x, legacyBounds.width());
            assertEquals(expectedSize.y, legacyBounds.height());
        });
    }

    public static class TestActivity extends Activity { }
}

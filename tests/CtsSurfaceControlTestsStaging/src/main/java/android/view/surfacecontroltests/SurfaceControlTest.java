/*
 * Copyright 2023 The Android Open Source Project
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

package android.view.surfacecontroltests;

import android.Manifest;
import android.hardware.display.DisplayManager;
import android.support.test.uiautomator.UiDevice;
import android.view.Surface;
import android.view.SurfaceControl;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;

import com.android.compatibility.common.util.DisplayUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@Ignore // b/330376055: Write tests for functionality for both dVRR and MRR devices.
@RunWith(AndroidJUnit4.class)
public class SurfaceControlTest {
    private static final String TAG = "SurfaceControlTest";

    @Rule
    public ActivityTestRule<GraphicsActivity> mActivityRule =
            new ActivityTestRule<>(GraphicsActivity.class);

    private int mInitialRefreshRateSwitchingType;
    private DisplayManager mDisplayManager;

    @Before
    public void setUp() throws Exception {
        GraphicsActivity activity = mActivityRule.getActivity();

        UiDevice uiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

        uiDevice.wakeUp();
        uiDevice.executeShellCommand("wm dismiss-keyguard");

        InstrumentationRegistry.getInstrumentation().getUiAutomation().adoptShellPermissionIdentity(
                Manifest.permission.MODIFY_REFRESH_RATE_SWITCHING_TYPE,
                Manifest.permission.OVERRIDE_DISPLAY_MODE_REQUESTS);

        // Prevent DisplayManager from limiting the allowed refresh rate range based on
        // non-app policies (e.g. low battery, user settings, etc).
        mDisplayManager = activity.getSystemService(DisplayManager.class);
        mDisplayManager.setShouldAlwaysRespectAppRequestedMode(true);

        mInitialRefreshRateSwitchingType = DisplayUtil.getRefreshRateSwitchingType(mDisplayManager);
        mDisplayManager.setRefreshRateSwitchingType(
                DisplayManager.SWITCHING_TYPE_ACROSS_AND_WITHIN_GROUPS);
    }

    @After
    public void tearDown() {
        if (mDisplayManager != null) {
            mDisplayManager.setRefreshRateSwitchingType(mInitialRefreshRateSwitchingType);
            mDisplayManager.setShouldAlwaysRespectAppRequestedMode(false);
        }

        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .dropShellPermissionIdentity();
    }

    @Test
    public void testSurfaceControlFrameRateCompatibilityGte() throws InterruptedException {
        GraphicsActivity activity = mActivityRule.getActivity();
        activity.testSurfaceControlFrameRateCompatibility(Surface.FRAME_RATE_COMPATIBILITY_GTE);
    }

    @Test
    public void testSurfaceControlFrameRateCategoryHigh() throws InterruptedException {
        GraphicsActivity activity = mActivityRule.getActivity();
        activity.testSurfaceControlFrameRateCategory(Surface.FRAME_RATE_CATEGORY_HIGH);
    }

    @Test
    public void testSurfaceControlFrameRateCategoryHighHint() throws InterruptedException {
        GraphicsActivity activity = mActivityRule.getActivity();
        activity.testSurfaceControlFrameRateCategory(Surface.FRAME_RATE_CATEGORY_HIGH_HINT);
    }

    @Test
    public void testSurfaceControlFrameRateCategoryNormal() throws InterruptedException {
        GraphicsActivity activity = mActivityRule.getActivity();
        activity.testSurfaceControlFrameRateCategory(Surface.FRAME_RATE_CATEGORY_NORMAL);
    }

    @Test
    public void testSurfaceControlFrameRateCategoryLow() throws InterruptedException {
        GraphicsActivity activity = mActivityRule.getActivity();
        activity.testSurfaceControlFrameRateCategory(Surface.FRAME_RATE_CATEGORY_LOW);
    }

    @Test
    public void testSurfaceControlFrameRateCategoryNoPreference() throws InterruptedException {
        GraphicsActivity activity = mActivityRule.getActivity();
        activity.testSurfaceControlFrameRateCategory(Surface.FRAME_RATE_CATEGORY_NO_PREFERENCE);
    }

    @Test
    public void testSurfaceControlFrameRateCategoryDefault() throws InterruptedException {
        GraphicsActivity activity = mActivityRule.getActivity();
        activity.testSurfaceControlFrameRateCategory(Surface.FRAME_RATE_CATEGORY_DEFAULT);
    }

    @Test
    public void testSurfaceControlFrameRateSelectionStrategyPropagate()
            throws InterruptedException {
        GraphicsActivity activity = mActivityRule.getActivity();
        activity.testSurfaceControlFrameRateSelectionStrategy(
                SurfaceControl.FRAME_RATE_SELECTION_STRATEGY_PROPAGATE);
    }

    @Test
    public void testSurfaceControlFrameRateSelectionStrategyOverrideChildren()
            throws InterruptedException {
        GraphicsActivity activity = mActivityRule.getActivity();
        activity.testSurfaceControlFrameRateSelectionStrategy(
                SurfaceControl.FRAME_RATE_SELECTION_STRATEGY_OVERRIDE_CHILDREN);
    }

    @Test
    public void testSurfaceControlFrameRateSelectionStrategySelf()
            throws InterruptedException {
        GraphicsActivity activity = mActivityRule.getActivity();
        activity.testSurfaceControlFrameRateSelectionStrategy(
                SurfaceControl.FRAME_RATE_SELECTION_STRATEGY_SELF);
    }
}

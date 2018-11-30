/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.wm;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOW_CONFIG_ALWAYS_ON_TOP;
import static android.app.WindowConfiguration.WINDOW_CONFIG_APP_BOUNDS;
import static android.app.WindowConfiguration.WINDOW_CONFIG_ROTATION;
import static android.app.WindowConfiguration.WINDOW_CONFIG_WINDOWING_MODE;
import static android.content.pm.ActivityInfo.CONFIG_WINDOW_CONFIGURATION;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.app.WindowConfiguration;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.view.DisplayInfo;
import android.view.Surface;

import androidx.test.filters.FlakyTest;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;

/**
 * Test class to for {@link android.app.WindowConfiguration}.
 *
 * Build/Install/Run:
 *  atest FrameworksServicesTests:WindowConfigurationTests
 */
@FlakyTest(bugId = 74078662)
@SmallTest
@Presubmit
public class WindowConfigurationTests extends WindowTestsBase {
    private Rect mParentBounds;

    @Before
    public void setUp() throws Exception {
        mParentBounds = new Rect(10 /*left*/, 30 /*top*/, 80 /*right*/, 60 /*bottom*/);
    }

    /** Tests {@link android.app.WindowConfiguration#diff(WindowConfiguration, boolean)}. */
    @Test
    public void testDiff() {
        final Configuration config1 = new Configuration();
        final WindowConfiguration winConfig1 = config1.windowConfiguration;
        final Configuration config2 = new Configuration();
        final WindowConfiguration winConfig2 = config2.windowConfiguration;
        final Configuration config3 = new Configuration();
        final WindowConfiguration winConfig3 = config3.windowConfiguration;
        final Configuration config4 = new Configuration();
        final WindowConfiguration winConfig4 = config4.windowConfiguration;

        winConfig1.setAppBounds(0, 1, 1, 0);
        winConfig2.setAppBounds(1, 2, 2, 1);
        winConfig3.setAppBounds(winConfig1.getAppBounds());
        winConfig4.setRotation(Surface.ROTATION_90);

        assertEquals(CONFIG_WINDOW_CONFIGURATION, config1.diff(config2));
        assertEquals(0, config1.diffPublicOnly(config2));
        assertEquals(WINDOW_CONFIG_APP_BOUNDS,
                winConfig1.diff(winConfig2, false /* compareUndefined */));

        winConfig2.setWindowingMode(WINDOWING_MODE_FREEFORM);
        assertEquals(WINDOW_CONFIG_APP_BOUNDS | WINDOW_CONFIG_WINDOWING_MODE,
                winConfig1.diff(winConfig2, false /* compareUndefined */));

        winConfig2.setAlwaysOnTop(true);
        assertEquals(WINDOW_CONFIG_APP_BOUNDS | WINDOW_CONFIG_WINDOWING_MODE
                | WINDOW_CONFIG_ALWAYS_ON_TOP,
                winConfig1.diff(winConfig2, false /* compareUndefined */));

        assertEquals(WINDOW_CONFIG_ROTATION,
                winConfig1.diff(winConfig4, false /* compareUndefined */));

        assertEquals(0, config1.diff(config3));
        assertEquals(0, config1.diffPublicOnly(config3));
        assertEquals(0, winConfig1.diff(winConfig3, false /* compareUndefined */));
    }

    /** Tests {@link android.app.WindowConfiguration#compareTo(WindowConfiguration)}. */
    @Test
    public void testConfigurationCompareTo() {
        final Configuration blankConfig = new Configuration();
        final WindowConfiguration blankWinConfig = new WindowConfiguration();

        final Configuration config1 = new Configuration();
        final WindowConfiguration winConfig1 = config1.windowConfiguration;
        winConfig1.setAppBounds(1, 2, 3, 4);

        final Configuration config2 = new Configuration(config1);
        final WindowConfiguration winConfig2 = config2.windowConfiguration;

        assertEquals(config1.compareTo(config2), 0);
        assertEquals(winConfig1.compareTo(winConfig2), 0);

        // Different windowing mode
        winConfig2.setWindowingMode(WINDOWING_MODE_FREEFORM);
        assertNotEquals(config1.compareTo(config2), 0);
        assertNotEquals(winConfig1.compareTo(winConfig2), 0);
        winConfig2.setWindowingMode(winConfig1.getWindowingMode());

        // Different always on top state
        winConfig2.setAlwaysOnTop(true);
        assertNotEquals(config1.compareTo(config2), 0);
        assertNotEquals(winConfig1.compareTo(winConfig2), 0);
        winConfig2.setAlwaysOnTop(winConfig1.isAlwaysOnTop());

        // Different bounds
        winConfig2.setAppBounds(0, 2, 3, 4);
        assertNotEquals(config1.compareTo(config2), 0);
        assertNotEquals(winConfig1.compareTo(winConfig2), 0);
        winConfig2.setAppBounds(winConfig1.getAppBounds());

        // No bounds
        assertEquals(config1.compareTo(blankConfig), -1);
        assertEquals(winConfig1.compareTo(blankWinConfig), -1);

        // Different rotation
        winConfig2.setRotation(Surface.ROTATION_180);
        assertNotEquals(config1.compareTo(config2), 0);
        assertNotEquals(winConfig1.compareTo(winConfig2), 0);
        winConfig2.setRotation(winConfig1.getRotation());

        assertEquals(blankConfig.compareTo(config1), 1);
        assertEquals(blankWinConfig.compareTo(winConfig1), 1);
    }

    @Test
    public void testSetActivityType() {
        final WindowConfiguration config = new WindowConfiguration();
        config.setActivityType(ACTIVITY_TYPE_HOME);
        assertEquals(ACTIVITY_TYPE_HOME, config.getActivityType());

        // Allowed to change from app process.
        config.setActivityType(ACTIVITY_TYPE_STANDARD);
        assertEquals(ACTIVITY_TYPE_STANDARD, config.getActivityType());
    }

    /** Ensures the configuration app bounds at the root level match the app dimensions. */
    @Test
    public void testAppBounds_RootConfigurationBounds() {
        final DisplayInfo info = mDisplayContent.getDisplayInfo();
        info.appWidth = 1024;
        info.appHeight = 768;

        final Rect appBounds = mWm.computeNewConfiguration(
                mDisplayContent.getDisplayId()).windowConfiguration.getAppBounds();
        // The bounds should always be positioned in the top left.
        assertEquals(appBounds.left, 0);
        assertEquals(appBounds.top, 0);

        // The bounds should equal the defined app width and height
        assertEquals(appBounds.width(), info.appWidth);
        assertEquals(appBounds.height(), info.appHeight);
    }

    /** Ensures that bounds are clipped to their parent. */
    @Test
    public void testAppBounds_BoundsClipping() {
        final Rect shiftedBounds = new Rect(mParentBounds);
        shiftedBounds.offset(10, 10);
        final Rect expectedBounds = new Rect(mParentBounds);
        expectedBounds.intersect(shiftedBounds);
        testStackBoundsConfiguration(WINDOWING_MODE_FULLSCREEN, mParentBounds, shiftedBounds,
                expectedBounds);
    }

    /** Ensures that empty bounds are not propagated to the configuration. */
    @Test
    public void testAppBounds_EmptyBounds() {
        final Rect emptyBounds = new Rect();
        testStackBoundsConfiguration(WINDOWING_MODE_FULLSCREEN, mParentBounds, emptyBounds,
                null /*ExpectedBounds*/);
    }

    /** Ensures that bounds on freeform stacks are not clipped. */
    @Test
    public void testAppBounds_FreeFormBounds() {
        final Rect freeFormBounds = new Rect(mParentBounds);
        freeFormBounds.offset(10, 10);
        testStackBoundsConfiguration(WINDOWING_MODE_FREEFORM, mParentBounds, freeFormBounds,
                freeFormBounds);
    }

    /** Ensures that fully contained bounds are not clipped. */
    @Test
    public void testAppBounds_ContainedBounds() {
        final Rect insetBounds = new Rect(mParentBounds);
        insetBounds.inset(5, 5, 5, 5);
        testStackBoundsConfiguration(
                WINDOWING_MODE_FULLSCREEN, mParentBounds, insetBounds, insetBounds);
    }

    /** Ensures that full screen free form bounds are clipped */
    @Test
    public void testAppBounds_FullScreenFreeFormBounds() {
        final Rect fullScreenBounds = new Rect(0, 0, mDisplayInfo.logicalWidth,
                mDisplayInfo.logicalHeight);
        testStackBoundsConfiguration(WINDOWING_MODE_FULLSCREEN, mParentBounds, fullScreenBounds,
                mParentBounds);
    }

    private void testStackBoundsConfiguration(int windowingMode, Rect parentBounds, Rect bounds,
            Rect expectedConfigBounds) {
        final StackWindowController stackController = createStackControllerOnStackOnDisplay(
                        windowingMode, ACTIVITY_TYPE_STANDARD, mDisplayContent);

        final Configuration parentConfig = mDisplayContent.getConfiguration();
        parentConfig.windowConfiguration.setAppBounds(parentBounds);

        final Configuration config = new Configuration();
        final WindowConfiguration winConfig = config.windowConfiguration;
        stackController.adjustConfigurationForBounds(bounds, null /*insetBounds*/,
                new Rect() /*nonDecorBounds*/, new Rect() /*stableBounds*/, false /*overrideWidth*/,
                false /*overrideHeight*/, mDisplayInfo.logicalDensityDpi, config, parentConfig,
                windowingMode);
        // Assert that both expected and actual are null or are equal to each other

        assertEquals(expectedConfigBounds, winConfig.getAppBounds());
    }

}

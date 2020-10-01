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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_ASSISTANT;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ROTATION_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOW_CONFIG_ALWAYS_ON_TOP;
import static android.app.WindowConfiguration.WINDOW_CONFIG_APP_BOUNDS;
import static android.app.WindowConfiguration.WINDOW_CONFIG_BOUNDS;
import static android.app.WindowConfiguration.WINDOW_CONFIG_ROTATION;
import static android.app.WindowConfiguration.WINDOW_CONFIG_WINDOWING_MODE;
import static android.content.pm.ActivityInfo.CONFIG_WINDOW_CONFIGURATION;
import static android.view.Surface.ROTATION_270;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.app.WindowConfiguration;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.view.DisplayInfo;
import android.view.Surface;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test class to for {@link android.app.WindowConfiguration}.
 *
 * Build/Install/Run:
 *  atest WmTests:WindowConfigurationTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
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

        assertEquals(0, config1.compareTo(config2));
        assertEquals(0, winConfig1.compareTo(winConfig2));

        // Different windowing mode
        winConfig2.setWindowingMode(WINDOWING_MODE_FREEFORM);
        assertNotEquals(0, config1.compareTo(config2));
        assertNotEquals(0, winConfig1.compareTo(winConfig2));
        winConfig2.setWindowingMode(winConfig1.getWindowingMode());

        // Different always on top state
        winConfig2.setAlwaysOnTop(true);
        assertNotEquals(0, config1.compareTo(config2));
        assertNotEquals(0, winConfig1.compareTo(winConfig2));
        winConfig2.setAlwaysOnTop(winConfig1.isAlwaysOnTop());

        // Different bounds
        winConfig2.setAppBounds(0, 2, 3, 4);
        assertNotEquals(0, config1.compareTo(config2));
        assertNotEquals(0, winConfig1.compareTo(winConfig2));
        winConfig2.setAppBounds(winConfig1.getAppBounds());

        // No bounds
        assertEquals(-1, config1.compareTo(blankConfig));
        assertEquals(-1, winConfig1.compareTo(blankWinConfig));

        // Different rotation
        winConfig2.setRotation(Surface.ROTATION_180);
        assertNotEquals(0, config1.compareTo(config2));
        assertNotEquals(0, winConfig1.compareTo(winConfig2));
        winConfig2.setRotation(winConfig1.getRotation());

        assertEquals(1, blankConfig.compareTo(config1));
        assertEquals(1, blankWinConfig.compareTo(winConfig1));
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
        // The bounds should always be positioned in the top left besides cutout.
        final int expectedLeft = info.displayCutout != null
                ? info.displayCutout.getSafeInsetLeft() : 0;
        final int expectedTop = info.displayCutout != null
                ? info.displayCutout.getSafeInsetTop() : 0;
        assertEquals(expectedLeft, appBounds.left);
        assertEquals(expectedTop, appBounds.top);

        // The bounds should equal the defined app width and height
        assertEquals(info.appWidth, appBounds.width());
        assertEquals(info.appHeight, appBounds.height());
    }

    /** Ensure the window always has a caption in Freeform window mode or display mode. */
    @Test
    public void testCaptionShownForFreeformWindowingMode() {
        final WindowConfiguration config = new WindowConfiguration();
        config.setActivityType(ACTIVITY_TYPE_STANDARD);
        config.setWindowingMode(WINDOWING_MODE_FREEFORM);
        config.setDisplayWindowingMode(WINDOWING_MODE_FULLSCREEN);
        assertTrue(config.hasWindowDecorCaption());

        config.setDisplayWindowingMode(WINDOWING_MODE_FREEFORM);
        assertTrue(config.hasWindowDecorCaption());

        config.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        assertTrue(config.hasWindowDecorCaption());

        config.setDisplayWindowingMode(WINDOWING_MODE_FULLSCREEN);
        assertFalse(config.hasWindowDecorCaption());
    }

    /** Caption should not show for non-standard activity window. */
    @Test
    public void testCaptionNotShownForNonStandardActivityType() {
        final WindowConfiguration config = new WindowConfiguration();
        config.setActivityType(ACTIVITY_TYPE_HOME);
        config.setWindowingMode(WINDOWING_MODE_FREEFORM);
        config.setDisplayWindowingMode(WINDOWING_MODE_FREEFORM);
        assertFalse(config.hasWindowDecorCaption());

        config.setActivityType(ACTIVITY_TYPE_ASSISTANT);
        assertFalse(config.hasWindowDecorCaption());

        config.setActivityType(ACTIVITY_TYPE_RECENTS);
        assertFalse(config.hasWindowDecorCaption());

        config.setActivityType(ACTIVITY_TYPE_STANDARD);
        assertTrue(config.hasWindowDecorCaption());
    }

    @Test
    public void testMaskedSetTo() {
        final WindowConfiguration config = new WindowConfiguration();
        final WindowConfiguration other = new WindowConfiguration();
        other.setBounds(new Rect(10, 10, 100, 100));
        other.setRotation(ROTATION_270);
        config.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        config.setBounds(null);

        // no change
        config.setTo(other, 0);
        assertTrue(config.getBounds().isEmpty());
        assertEquals(ROTATION_UNDEFINED, config.getRotation());
        assertEquals(WINDOWING_MODE_FULLSCREEN, config.getWindowingMode());

        final int justBoundsAndRotation = WINDOW_CONFIG_BOUNDS | WINDOW_CONFIG_ROTATION;
        config.setTo(other, justBoundsAndRotation);
        assertEquals(other.getBounds(), config.getBounds());
        assertEquals(other.getRotation(), config.getRotation());
        assertEquals(WINDOWING_MODE_FULLSCREEN, config.getWindowingMode());

        // unsets as well
        final int justWindowingMode = WINDOW_CONFIG_WINDOWING_MODE;
        config.setTo(other, justWindowingMode);
        assertEquals(WINDOWING_MODE_UNDEFINED, config.getWindowingMode());
    }
}

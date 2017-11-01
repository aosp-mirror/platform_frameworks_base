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
 * limitations under the License
 */

package com.android.server.wm;

import android.app.ActivityManager;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.DisplayInfo;
import org.junit.Test;

import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Test class to exercise logic related to {@link android.content.res.Configuration#appBounds}.
 *
 * Build/Install/Run:
 *  bit FrameworksServicesTests:com.android.server.wm.AppBoundsTests
 */
@SmallTest
@Presubmit
@org.junit.runner.RunWith(AndroidJUnit4.class)
public class AppBoundsTests extends WindowTestsBase {
    private Rect mParentBounds;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mParentBounds = new Rect(10 /*left*/, 30 /*top*/, 80 /*right*/, 60 /*bottom*/);
    }

    /**
     * Ensures that appBounds causes {@link android.content.pm.ActivityInfo.CONFIG_APP_BOUNDS} diff.
     */
    @Test
    public void testAppBoundsConfigurationDiff() {
        final Configuration config = new Configuration();
        final Configuration config2 = new Configuration();
        config.appBounds = new Rect(0, 1, 1, 0);
        config2.appBounds = new Rect(1, 2, 2, 1);

        assertEquals(ActivityInfo.CONFIG_SCREEN_SIZE, config.diff(config2));
        assertEquals(0, config.diffPublicOnly(config2));
    }

    /**
     * Ensures the configuration app bounds at the root level match the app dimensions.
     */
    @Test
    public void testRootConfigurationBounds() throws Exception {
        final DisplayInfo info = mDisplayContent.getDisplayInfo();
        info.appWidth = 1024;
        info.appHeight = 768;

        final Configuration config = sWm.computeNewConfiguration(mDisplayContent.getDisplayId());
        // The bounds should always be positioned in the top left.
        assertEquals(config.appBounds.left, 0);
        assertEquals(config.appBounds.top, 0);

        // The bounds should equal the defined app width and height
        assertEquals(config.appBounds.width(), info.appWidth);
        assertEquals(config.appBounds.height(), info.appHeight);
    }

    /**
     * Ensures that bounds are clipped to their parent.
     */
    @Test
    public void testBoundsClipping() throws Exception {
        final Rect shiftedBounds = new Rect(mParentBounds);
        shiftedBounds.offset(10, 10);
        final Rect expectedBounds = new Rect(mParentBounds);
        expectedBounds.intersect(shiftedBounds);
        testStackBoundsConfiguration(null /*stackId*/, mParentBounds, shiftedBounds,
                expectedBounds);
    }

    /**
     * Ensures that empty bounds are not propagated to the configuration.
     */
    @Test
    public void testEmptyBounds() throws Exception {
        final Rect emptyBounds = new Rect();
        testStackBoundsConfiguration(null /*stackId*/, mParentBounds, emptyBounds,
                null /*ExpectedBounds*/);
    }

    /**
     * Ensures that bounds on freeform stacks are not clipped.
     */
    @Test
    public void testFreeFormBounds() throws Exception {
        final Rect freeFormBounds = new Rect(mParentBounds);
        freeFormBounds.offset(10, 10);
        testStackBoundsConfiguration(ActivityManager.StackId.FREEFORM_WORKSPACE_STACK_ID,
                mParentBounds, freeFormBounds, freeFormBounds);
    }

    /**
     * Ensures that fully contained bounds are not clipped.
     */
    @Test
    public void testContainedBounds() throws Exception {
        final Rect insetBounds = new Rect(mParentBounds);
        insetBounds.inset(5, 5, 5, 5);
        testStackBoundsConfiguration(null /*stackId*/, mParentBounds, insetBounds, insetBounds);
    }

    /**
     * Ensures that full screen free form bounds are clipped
     */
    @Test
    public void testFullScreenFreeFormBounds() throws Exception {
        final Rect fullScreenBounds = new Rect(0, 0, mDisplayInfo.logicalWidth,
                mDisplayInfo.logicalHeight);
        testStackBoundsConfiguration(null /*stackId*/, mParentBounds, fullScreenBounds,
                mParentBounds);
    }

    private void testStackBoundsConfiguration(Integer stackId, Rect parentBounds, Rect bounds,
            Rect expectedConfigBounds) {
        final StackWindowController stackController = stackId != null ?
                createStackControllerOnStackOnDisplay(stackId, mDisplayContent)
                : createStackControllerOnDisplay(mDisplayContent);

        final Configuration parentConfig = mDisplayContent.getConfiguration();
        parentConfig.setAppBounds(parentBounds);

        final Configuration config = new Configuration();
        stackController.adjustConfigurationForBounds(bounds, null /*insetBounds*/,
                new Rect() /*nonDecorBounds*/, new Rect() /*stableBounds*/, false /*overrideWidth*/,
                false /*overrideHeight*/, mDisplayInfo.logicalDensityDpi, config, parentConfig);
        // Assert that both expected and actual are null or are equal to each other

        assertTrue((expectedConfigBounds == null && config.appBounds == null)
                || expectedConfigBounds.equals(config.appBounds));
    }

    /**
     * Ensures appBounds are considered in {@link Configuration#compareTo(Configuration)}.
     */
    @Test
    public void testConfigurationCompareTo() throws Exception {
        final Configuration blankConfig = new Configuration();

        final Configuration config1 = new Configuration();
        config1.appBounds = new Rect(1, 2, 3, 4);

        final Configuration config2 = new Configuration(config1);

        assertEquals(config1.compareTo(config2), 0);

        config2.appBounds.left = 0;

        // Different bounds
        assertNotEquals(config1.compareTo(config2), 0);

        // No bounds
        assertEquals(config1.compareTo(blankConfig), -1);
        assertEquals(blankConfig.compareTo(config1), 1);

    }
}

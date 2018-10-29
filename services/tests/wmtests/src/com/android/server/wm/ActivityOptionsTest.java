/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.app.ActivityTaskManager.SPLIT_SCREEN_CREATE_MODE_BOTTOM_OR_RIGHT;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.view.WindowManager.LayoutParams.ROTATION_ANIMATION_ROTATE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.ActivityOptions;
import android.os.Bundle;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.MediumTest;

import org.junit.Test;

/**
 * Build/Install/Run:
 *  atest WmTests:ActivityOptionsTest
 */
@MediumTest
@Presubmit
public class ActivityOptionsTest {

    @Test
    public void testMerge_NoClobber() {
        // Construct some options with set values
        ActivityOptions opts = ActivityOptions.makeBasic();
        opts.setLaunchDisplayId(Integer.MAX_VALUE);
        opts.setLaunchActivityType(ACTIVITY_TYPE_STANDARD);
        opts.setLaunchWindowingMode(WINDOWING_MODE_FULLSCREEN);
        opts.setAvoidMoveToFront();
        opts.setLaunchTaskId(Integer.MAX_VALUE);
        opts.setLockTaskEnabled(true);
        opts.setRotationAnimationHint(ROTATION_ANIMATION_ROTATE);
        opts.setTaskOverlay(true, true);
        opts.setSplitScreenCreateMode(SPLIT_SCREEN_CREATE_MODE_BOTTOM_OR_RIGHT);
        Bundle optsBundle = opts.toBundle();

        // Try and merge the constructed options with a new set of options
        optsBundle.putAll(ActivityOptions.makeBasic().toBundle());

        // Ensure the set values are not clobbered
        ActivityOptions restoredOpts = ActivityOptions.fromBundle(optsBundle);
        assertEquals(Integer.MAX_VALUE, restoredOpts.getLaunchDisplayId());
        assertEquals(ACTIVITY_TYPE_STANDARD, restoredOpts.getLaunchActivityType());
        assertEquals(WINDOWING_MODE_FULLSCREEN, restoredOpts.getLaunchWindowingMode());
        assertTrue(restoredOpts.getAvoidMoveToFront());
        assertEquals(Integer.MAX_VALUE, restoredOpts.getLaunchTaskId());
        assertTrue(restoredOpts.getLockTaskMode());
        assertEquals(ROTATION_ANIMATION_ROTATE, restoredOpts.getRotationAnimationHint());
        assertTrue(restoredOpts.getTaskOverlay());
        assertTrue(restoredOpts.canTaskOverlayResume());
        assertEquals(SPLIT_SCREEN_CREATE_MODE_BOTTOM_OR_RIGHT,
                restoredOpts.getSplitScreenCreateMode());
    }
}

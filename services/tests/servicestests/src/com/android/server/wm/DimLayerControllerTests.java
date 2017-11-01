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

import static android.view.DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.hardware.display.DisplayManagerGlobal;
import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.view.Display;
import android.view.DisplayInfo;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for the {@link DimLayerController} class.
 *
 * Build/Install/Run:
 *  bit FrameworksServicesTests:com.android.server.wm.DimLayerControllerTests
 */
@SmallTest
@Presubmit
@org.junit.runner.RunWith(AndroidJUnit4.class)
public class DimLayerControllerTests extends WindowTestsBase {

    /**
     * This tests if shared fullscreen dim layer is added when stack is added to display
     * and is removed when the only stack on the display is removed.
     */
    @Test
    public void testSharedFullScreenDimLayer() throws Exception {
        // Create a display.
        final DisplayContent dc = createNewDisplay();
        assertFalse(dc.mDimLayerController.hasSharedFullScreenDimLayer());

        // Add stack with activity.
        final TaskStack stack = createTaskStackOnDisplay(dc);
        assertTrue(dc.mDimLayerController.hasDimLayerUser(stack));
        assertTrue(dc.mDimLayerController.hasSharedFullScreenDimLayer());

        // Remove the only stack on the display and check if the shared dim layer clears.
        stack.removeImmediately();
        assertFalse(dc.mDimLayerController.hasDimLayerUser(stack));
        assertFalse(dc.mDimLayerController.hasSharedFullScreenDimLayer());
    }
}

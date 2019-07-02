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
 * limitations under the License.
 */

package com.android.server.wm;

import static android.view.WindowManager.DOCKED_BOTTOM;
import static android.view.WindowManager.DOCKED_LEFT;
import static android.view.WindowManager.DOCKED_RIGHT;
import static android.view.WindowManager.DOCKED_TOP;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_BOTTOM;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_LEFT;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_RIGHT;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Test;

@SmallTest
@Presubmit
public class DockedStackDividerControllerTests {

    @Test
    public void testIsDockSideAllowedDockTop() {
        // Docked top is always allowed
        assertTrue(DockedStackDividerController.isDockSideAllowed(DOCKED_TOP, DOCKED_LEFT,
                NAV_BAR_BOTTOM, true /* navigationBarCanMove */));
        assertTrue(DockedStackDividerController.isDockSideAllowed(DOCKED_TOP, DOCKED_LEFT,
                NAV_BAR_BOTTOM, false /* navigationBarCanMove */));
    }

    @Test
    public void testIsDockSideAllowedDockBottom() {
        // Cannot dock bottom
        assertFalse(DockedStackDividerController.isDockSideAllowed(DOCKED_BOTTOM, DOCKED_LEFT,
                NAV_BAR_BOTTOM, true /* navigationBarCanMove */));
    }

    @Test
    public void testIsDockSideAllowedNavigationBarMovable() {
        assertFalse(DockedStackDividerController.isDockSideAllowed(DOCKED_LEFT, DOCKED_LEFT,
                NAV_BAR_BOTTOM, true /* navigationBarCanMove */));
        assertFalse(DockedStackDividerController.isDockSideAllowed(DOCKED_LEFT, DOCKED_LEFT,
                NAV_BAR_LEFT, true /* navigationBarCanMove */));
        assertTrue(DockedStackDividerController.isDockSideAllowed(DOCKED_LEFT, DOCKED_LEFT,
                NAV_BAR_RIGHT, true /* navigationBarCanMove */));
        assertFalse(DockedStackDividerController.isDockSideAllowed(DOCKED_RIGHT, DOCKED_LEFT,
                NAV_BAR_BOTTOM, true /* navigationBarCanMove */));
        assertFalse(DockedStackDividerController.isDockSideAllowed(DOCKED_RIGHT, DOCKED_LEFT,
                NAV_BAR_RIGHT, true /* navigationBarCanMove */));
        assertTrue(DockedStackDividerController.isDockSideAllowed(DOCKED_RIGHT, DOCKED_LEFT,
                NAV_BAR_LEFT, true /* navigationBarCanMove */));
    }

    @Test
    public void testIsDockSideAllowedNavigationBarNotMovable() {
        // Navigation bar is not movable such as tablets
        assertTrue(DockedStackDividerController.isDockSideAllowed(DOCKED_LEFT, DOCKED_LEFT,
                NAV_BAR_BOTTOM, false /* navigationBarCanMove */));
        assertTrue(DockedStackDividerController.isDockSideAllowed(DOCKED_LEFT, DOCKED_TOP,
                NAV_BAR_BOTTOM, false /* navigationBarCanMove */));
        assertFalse(DockedStackDividerController.isDockSideAllowed(DOCKED_LEFT, DOCKED_RIGHT,
                NAV_BAR_BOTTOM, false /* navigationBarCanMove */));
        assertFalse(DockedStackDividerController.isDockSideAllowed(DOCKED_RIGHT, DOCKED_LEFT,
                NAV_BAR_BOTTOM, false /* navigationBarCanMove */));
        assertFalse(DockedStackDividerController.isDockSideAllowed(DOCKED_RIGHT, DOCKED_TOP,
                NAV_BAR_BOTTOM, false /* navigationBarCanMove */));
        assertTrue(DockedStackDividerController.isDockSideAllowed(DOCKED_RIGHT, DOCKED_RIGHT,
                NAV_BAR_BOTTOM, false /* navigationBarCanMove */));
    }
}

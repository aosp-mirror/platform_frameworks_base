/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.view.Display.INVALID_DISPLAY;

import static com.android.server.wm.ActivityDisplay.POSITION_TOP;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import android.content.pm.ApplicationInfo;
import android.platform.test.annotations.Presubmit;

import org.junit.Test;

/**
 * Tests for the {@link WindowProcessController} class.
 *
 * Build/Install/Run:
 *  atest WmTests:WindowProcessControllerTests
 */
@Presubmit
public class WindowProcessControllerTests extends ActivityTestsBase {

    @Test
    public void testDisplayConfigurationListener() {
        final WindowProcessController wpc = new WindowProcessController(
                        mService, mock(ApplicationInfo.class), null, 0, -1, null, null);
        //By default, the process should not listen to any display.
        assertEquals(INVALID_DISPLAY, wpc.getDisplayId());

        // Register to display 1 as a listener.
        TestActivityDisplay testActivityDisplay1 = createTestActivityDisplayInContainer();
        wpc.registerDisplayConfigurationListenerLocked(testActivityDisplay1);
        assertTrue(testActivityDisplay1.containsListener(wpc));
        assertEquals(testActivityDisplay1.mDisplayId, wpc.getDisplayId());

        // Move to display 2.
        TestActivityDisplay testActivityDisplay2 = createTestActivityDisplayInContainer();
        wpc.registerDisplayConfigurationListenerLocked(testActivityDisplay2);
        assertFalse(testActivityDisplay1.containsListener(wpc));
        assertTrue(testActivityDisplay2.containsListener(wpc));
        assertEquals(testActivityDisplay2.mDisplayId, wpc.getDisplayId());

        // Null ActivityDisplay will not change anything.
        wpc.registerDisplayConfigurationListenerLocked(null);
        assertTrue(testActivityDisplay2.containsListener(wpc));
        assertEquals(testActivityDisplay2.mDisplayId, wpc.getDisplayId());

        // Unregister listener will remove the wpc from registered displays.
        wpc.unregisterDisplayConfigurationListenerLocked();
        assertFalse(testActivityDisplay1.containsListener(wpc));
        assertFalse(testActivityDisplay2.containsListener(wpc));
        assertEquals(INVALID_DISPLAY, wpc.getDisplayId());

        // Unregistration still work even if the display was removed.
        wpc.registerDisplayConfigurationListenerLocked(testActivityDisplay1);
        assertEquals(testActivityDisplay1.mDisplayId, wpc.getDisplayId());
        mRootActivityContainer.removeChild(testActivityDisplay1);
        wpc.unregisterDisplayConfigurationListenerLocked();
        assertEquals(INVALID_DISPLAY, wpc.getDisplayId());
    }

    private TestActivityDisplay createTestActivityDisplayInContainer() {
        final TestActivityDisplay testActivityDisplay = createNewActivityDisplay();
        mRootActivityContainer.addChild(testActivityDisplay, POSITION_TOP);
        return testActivityDisplay;
    }
}

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

import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.platform.test.annotations.Presubmit;
import android.view.InputChannel;

import androidx.test.filters.FlakyTest;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for the {@link TaskPositioningController} class.
 *
 * atest com.android.server.wm.TaskPositioningControllerTests
 */
@SmallTest
@FlakyTest(bugId = 113616538)
@RunWith(AndroidJUnit4.class)
@Presubmit
public class TaskPositioningControllerTests extends WindowTestsBase {
    private static final int TIMEOUT_MS = 1000;
    private TaskPositioningController mTarget;
    private WindowState mWindow;

    @Before
    public void setUp() throws Exception {
        super.setUp();

        assertNotNull(sWm.mTaskPositioningController);
        mTarget = sWm.mTaskPositioningController;

        when(sWm.mInputManager.transferTouchFocus(
                any(InputChannel.class),
                any(InputChannel.class))).thenReturn(true);

        mWindow = createWindow(null, TYPE_BASE_APPLICATION, "window");
        mWindow.mInputChannel = new InputChannel();
        synchronized (sWm.mWindowMap) {
            sWm.mWindowMap.put(mWindow.mClient.asBinder(), mWindow);
        }
    }

    @Test
    public void testStartAndFinishPositioning() throws Exception {
        synchronized (sWm.mWindowMap) {
            assertFalse(mTarget.isPositioningLocked());
            assertNull(mTarget.getDragWindowHandleLocked());
        }

        assertTrue(mTarget.startMovingTask(mWindow.mClient, 0, 0));

        synchronized (sWm.mWindowMap) {
            assertTrue(mTarget.isPositioningLocked());
            assertNotNull(mTarget.getDragWindowHandleLocked());
        }

        mTarget.finishTaskPositioning();
        // Wait until the looper processes finishTaskPositioning.
        assertTrue(sWm.mH.runWithScissors(() -> {}, TIMEOUT_MS));

        assertFalse(mTarget.isPositioningLocked());
        assertNull(mTarget.getDragWindowHandleLocked());
    }

    @Test
    public void testHandleTapOutsideTask() throws Exception {
        synchronized (sWm.mWindowMap) {

            assertFalse(mTarget.isPositioningLocked());
            assertNull(mTarget.getDragWindowHandleLocked());
        }

        final DisplayContent content = mock(DisplayContent.class);
        when(content.findTaskForResizePoint(anyInt(), anyInt())).thenReturn(mWindow.getTask());
        assertNotNull(mWindow.getTask().getTopVisibleAppMainWindow());

        mTarget.handleTapOutsideTask(content, 0, 0);
        // Wait until the looper processes finishTaskPositioning.
        assertTrue(sWm.mH.runWithScissors(() -> {}, TIMEOUT_MS));

        synchronized (sWm.mWindowMap) {
            assertTrue(mTarget.isPositioningLocked());
            assertNotNull(mTarget.getDragWindowHandleLocked());
        }

        mTarget.finishTaskPositioning();
        // Wait until the looper processes finishTaskPositioning.
        assertTrue(sWm.mH.runWithScissors(() -> {}, TIMEOUT_MS));

        assertFalse(mTarget.isPositioningLocked());
        assertNull(mTarget.getDragWindowHandleLocked());
    }
}

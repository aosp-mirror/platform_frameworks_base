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

import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyInt;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.platform.test.annotations.Presubmit;
import android.view.InputChannel;

import androidx.test.filters.FlakyTest;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the {@link TaskPositioningController} class.
 *
 * Build/Install/Run:
 *  atest WmTests:TaskPositioningControllerTests
 */
@SmallTest
@Presubmit
public class TaskPositioningControllerTests extends WindowTestsBase {
    private static final int TIMEOUT_MS = 1000;

    private TaskPositioningController mTarget;
    private WindowState mWindow;

    @Before
    public void setUp() throws Exception {
        assertNotNull(mWm.mTaskPositioningController);
        mTarget = mWm.mTaskPositioningController;

        mWindow = createWindow(null, TYPE_BASE_APPLICATION, "window");
        mWindow.mInputChannel = new InputChannel();
        synchronized (mWm.mGlobalLock) {
            mWm.mWindowMap.put(mWindow.mClient.asBinder(), mWindow);
            spyOn(mDisplayContent);
            doReturn(mock(InputMonitor.class)).when(mDisplayContent).getInputMonitor();
        }
    }

    @Test
    public void testStartAndFinishPositioning() {
        synchronized (mWm.mGlobalLock) {
            assertFalse(mTarget.isPositioningLocked());
            assertNull(mTarget.getDragWindowHandleLocked());
        }

        assertTrue(mTarget.startMovingTask(mWindow.mClient, 0, 0));

        synchronized (mWm.mGlobalLock) {
            assertTrue(mTarget.isPositioningLocked());
            assertNotNull(mTarget.getDragWindowHandleLocked());
        }

        mTarget.finishTaskPositioning();
        // Wait until the looper processes finishTaskPositioning.
        assertTrue(mWm.mH.runWithScissors(() -> { }, TIMEOUT_MS));

        assertFalse(mTarget.isPositioningLocked());
        assertNull(mTarget.getDragWindowHandleLocked());
    }

    @FlakyTest(bugId = 129507487)
    @Test
    public void testFinishPositioningWhenAppRequested() {
        synchronized (mWm.mGlobalLock) {
            assertFalse(mTarget.isPositioningLocked());
            assertNull(mTarget.getDragWindowHandleLocked());
        }

        assertTrue(mTarget.startMovingTask(mWindow.mClient, 0, 0));

        synchronized (mWm.mGlobalLock) {
            assertTrue(mTarget.isPositioningLocked());
            assertNotNull(mTarget.getDragWindowHandleLocked());
        }

        mTarget.finishTaskPositioning(mWindow.mClient);
        // Wait until the looper processes finishTaskPositioning.
        assertTrue(mWm.mH.runWithScissors(() -> { }, TIMEOUT_MS));

        assertFalse(mTarget.isPositioningLocked());
        assertNull(mTarget.getDragWindowHandleLocked());
    }

    @Test
    public void testHandleTapOutsideTask() {
        synchronized (mWm.mGlobalLock) {
            assertFalse(mTarget.isPositioningLocked());
            assertNull(mTarget.getDragWindowHandleLocked());
        }

        final DisplayContent content = mock(DisplayContent.class);
        when(content.findTaskForResizePoint(anyInt(), anyInt())).thenReturn(mWindow.getTask());
        assertNotNull(mWindow.getTask().getTopVisibleAppMainWindow());

        mTarget.handleTapOutsideTask(content, 0, 0);
        // Wait until the looper processes finishTaskPositioning.
        assertTrue(mWm.mH.runWithScissors(() -> { }, TIMEOUT_MS));

        synchronized (mWm.mGlobalLock) {
            assertTrue(mTarget.isPositioningLocked());
            assertNotNull(mTarget.getDragWindowHandleLocked());
        }

        mTarget.finishTaskPositioning();
        // Wait until the looper processes finishTaskPositioning.
        assertTrue(mWm.mH.runWithScissors(() -> { }, TIMEOUT_MS));

        assertFalse(mTarget.isPositioningLocked());
        assertNull(mTarget.getDragWindowHandleLocked());
    }
}

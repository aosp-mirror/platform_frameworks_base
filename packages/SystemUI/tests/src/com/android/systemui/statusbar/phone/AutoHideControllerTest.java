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

package com.android.systemui.statusbar.phone;

import static android.view.Display.DEFAULT_DISPLAY;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.graphics.Rect;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.view.View;

import androidx.test.filters.SmallTest;

import com.android.systemui.Dependency;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.CommandQueue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** atest AutoHideControllerTest */
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class AutoHideControllerTest extends SysuiTestCase {

    private AutoHideController mAutoHideController;

    private static final int FULL_MASK = 0xffffffff;

    @Before
    public void setUp() {
        mContext.putComponent(CommandQueue.class, mock(CommandQueue.class));
        mAutoHideController =
                spy(new AutoHideController(mContext, Dependency.get(Dependency.MAIN_HANDLER)));
        mAutoHideController.mDisplayId = DEFAULT_DISPLAY;
        mAutoHideController.mSystemUiVisibility = View.VISIBLE;
    }

    @After
    public void tearDown() {
        mAutoHideController = null;
    }

    @Test
    public void testSetSystemUiVisibilityEarlyReturnWithDifferentDisplay() {
        mAutoHideController.setSystemUiVisibility(1, 1, 2, 3, 4, null, new Rect(), false);

        verify(mAutoHideController, never()).notifySystemUiVisibilityChanged(anyInt());
    }

    @Test
    public void testSetSystemUiVisibilityEarlyReturnWithSameVisibility() {
        mAutoHideController
                .setSystemUiVisibility(
                        DEFAULT_DISPLAY, View.VISIBLE, 2, 3, 4, null, new Rect(), false);

        verify(mAutoHideController, never()).notifySystemUiVisibilityChanged(anyInt());
    }

    // Test if status bar unhide status doesn't change without status bar.
    @Test
    public void testSetSystemUiVisibilityWithoutStatusBar() {
        doReturn(false).when(mAutoHideController).hasStatusBar();
        int expectedStatus = View.STATUS_BAR_UNHIDE;
        mAutoHideController.mSystemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.STATUS_BAR_UNHIDE;

        mAutoHideController.setSystemUiVisibility(
                DEFAULT_DISPLAY, expectedStatus, 2, 3, FULL_MASK, null, new Rect(), false);

        assertEquals("System UI visibility should not be changed",
                expectedStatus, mAutoHideController.mSystemUiVisibility);
        verify(mAutoHideController, times(1)).notifySystemUiVisibilityChanged(eq(expectedStatus));
    }

    @Test
    public void testSetSystemUiVisibilityWithVisChanged() {
        doReturn(true).when(mAutoHideController).hasStatusBar();
        doReturn(true).when(mAutoHideController).hasNavigationBar();
        mAutoHideController.mSystemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.STATUS_BAR_UNHIDE
                | View.NAVIGATION_BAR_UNHIDE;

        mAutoHideController.setSystemUiVisibility(
                DEFAULT_DISPLAY, View.STATUS_BAR_UNHIDE | View.NAVIGATION_BAR_UNHIDE,
                2, 3, FULL_MASK, null, new Rect(), false);

        int expectedStatus = View.VISIBLE;
        assertEquals(expectedStatus, mAutoHideController.mSystemUiVisibility);
        verify(mAutoHideController).notifySystemUiVisibilityChanged(eq(expectedStatus));
    }
}

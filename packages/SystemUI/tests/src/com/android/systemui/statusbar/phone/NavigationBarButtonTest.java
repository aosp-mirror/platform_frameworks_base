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

package com.android.systemui.statusbar.phone;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.os.SystemClock;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.util.Log;
import android.view.Display;
import android.view.DisplayInfo;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.SysuiTestableContext;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.function.Predicate;

/** atest NavigationBarButtonTest */
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class NavigationBarButtonTest extends SysuiTestCase {

    private static final String TAG = "NavigationBarButtonTest";
    private ImageReader mReader;
    private NavigationBarView mNavBar;
    private VirtualDisplay mVirtualDisplay;

    @Before
    public void setup() {
        final Display display = createVirtualDisplay();
        final SysuiTestableContext context =
                (SysuiTestableContext) mContext.createDisplayContext(display);

        mDependency.injectMockDependency(AssistManager.class);
        mDependency.injectMockDependency(OverviewProxyService.class);
        mDependency.injectMockDependency(KeyguardStateController.class);
        mNavBar = new NavigationBarView(context, null);
    }

    private Display createVirtualDisplay() {
        final String displayName = "NavVirtualDisplay";
        final DisplayInfo displayInfo = new DisplayInfo();
        mContext.getDisplay().getDisplayInfo(displayInfo);

        final DisplayManager displayManager = mContext.getSystemService(DisplayManager.class);

        mReader = ImageReader.newInstance(displayInfo.logicalWidth,
                displayInfo.logicalHeight, PixelFormat.RGBA_8888, 2);

        assertNotNull("ImageReader must not be null", mReader);

        mVirtualDisplay = displayManager.createVirtualDisplay(displayName, displayInfo.logicalWidth,
                displayInfo.logicalHeight, displayInfo.logicalDensityDpi, mReader.getSurface(),
                0 /*flags*/);

        assertNotNull("virtual display must not be null", mVirtualDisplay);

        waitForDisplayReady(mVirtualDisplay.getDisplay().getDisplayId());

        return mVirtualDisplay.getDisplay();
    }

    private void waitForDisplayReady(int displayId) {
        waitForDisplayCondition(displayId, state -> state);
    }

    private void waitForDisplayCondition(int displayId, Predicate<Boolean> condition) {
        for (int retry = 1; retry <= 10; retry++) {
            if (condition.test(isDisplayOn(displayId))) {
                return;
            }
            Log.i(TAG, "Waiting for virtual display ready ... retry = " + retry);
            SystemClock.sleep(500);
        }
    }

    private boolean isDisplayOn(int displayId) {
        DisplayManager displayManager = mContext.getSystemService(DisplayManager.class);
        Display display = displayManager.getDisplay(displayId);
        return display != null && display.getState() == Display.STATE_ON;
    }

    @After
    public void tearDown() {
        releaseDisplay();
    }

    private void releaseDisplay() {
        mVirtualDisplay.release();
        mReader.close();
    }

    @Test
    public void testRecentsButtonDisabledOnSecondaryDisplay() {
        assertTrue("The recents button must be disabled",
                mNavBar.isRecentsButtonDisabled());
    }
}




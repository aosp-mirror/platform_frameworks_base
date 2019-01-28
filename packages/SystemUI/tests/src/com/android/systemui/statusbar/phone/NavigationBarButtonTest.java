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
import static org.mockito.Mockito.mock;

import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.view.Display;
import android.view.DisplayInfo;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.SysuiTestableContext;
import com.android.systemui.statusbar.CommandQueue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** atest NavigationBarButtonTest */
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class NavigationBarButtonTest extends SysuiTestCase {

    private ImageReader mReader;
    private NavigationBarView mNavBar;
    private VirtualDisplay mVirtualDisplay;

    @Before
    public void setup() {
        mContext.putComponent(CommandQueue.class, mock(CommandQueue.class));
        final Display display = createVirtualDisplay();
        final SysuiTestableContext context =
                (SysuiTestableContext) mContext.createDisplayContext(display);
        context.putComponent(CommandQueue.class, mock(CommandQueue.class));

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

        return mVirtualDisplay.getDisplay();
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




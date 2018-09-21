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
 *
 */

package com.android.server.wm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.app.WindowConfiguration;
import android.platform.test.annotations.Presubmit;
import android.view.Display;
import android.view.DisplayInfo;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

/**
 * Tests for {@link DisplaySettings}.
 *
 * Build/Install/Run:
 *  atest WmTests:DisplaySettingsTests
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class DisplaySettingsTests extends WindowTestsBase {

    private File mTestFolder;
    private DisplaySettings mTarget;

    private DisplayContent mPrimaryDisplay;
    private DisplayContent mSecondaryDisplay;

    @Before
    public void setUp() throws Exception {
        super.setUp();

        mTestFolder = InstrumentationRegistry.getContext().getCacheDir();
        deleteRecursively(mTestFolder);

        sWm.setSupportsFreeformWindowManagement(false);
        sWm.setIsPc(false);

        mTarget = new DisplaySettings(sWm, mTestFolder);
        mTarget.readSettingsLocked();

        mPrimaryDisplay = sWm.getDefaultDisplayContentLocked();
        mSecondaryDisplay = createNewDisplay();
        assertNotEquals(Display.DEFAULT_DISPLAY, mSecondaryDisplay.getDisplayId());
    }

    @Test
    public void testPrimaryDisplayDefaultToFullscreenWithoutFreeformSupport() {
        mTarget.applySettingsToDisplayLocked(mPrimaryDisplay);

        assertEquals(WindowConfiguration.WINDOWING_MODE_FULLSCREEN,
                mPrimaryDisplay.getWindowingMode());
    }

    @Test
    public void testPrimaryDisplayDefaultToFullscreenWithFreeformSupportNonPc() {
        sWm.setSupportsFreeformWindowManagement(true);

        mTarget.applySettingsToDisplayLocked(mPrimaryDisplay);

        assertEquals(WindowConfiguration.WINDOWING_MODE_FULLSCREEN,
                mPrimaryDisplay.getWindowingMode());
    }

    @Test
    public void testPrimaryDisplayDefaultToFreeformWithFreeformIsPc() {
        sWm.setSupportsFreeformWindowManagement(true);
        sWm.setIsPc(true);

        mTarget.applySettingsToDisplayLocked(mPrimaryDisplay);

        assertEquals(WindowConfiguration.WINDOWING_MODE_FREEFORM,
                mPrimaryDisplay.getWindowingMode());
    }

    @Test
    public void testSecondaryDisplayDefaultToFullscreenWithoutFreeformSupport() {
        mTarget.applySettingsToDisplayLocked(mSecondaryDisplay);

        assertEquals(WindowConfiguration.WINDOWING_MODE_FULLSCREEN,
                mSecondaryDisplay.getWindowingMode());
    }

    @Test
    public void testSecondaryDisplayDefaultToFreeformWithFreeformSupportNonPc() {
        sWm.setSupportsFreeformWindowManagement(true);

        mTarget.applySettingsToDisplayLocked(mSecondaryDisplay);

        assertEquals(WindowConfiguration.WINDOWING_MODE_FREEFORM,
                mSecondaryDisplay.getWindowingMode());
    }

    @Test
    public void testSecondaryDisplayDefaultToFreeformWithFreeformSupportIsPc() {
        sWm.setSupportsFreeformWindowManagement(true);
        sWm.setIsPc(true);

        mTarget.applySettingsToDisplayLocked(mSecondaryDisplay);

        assertEquals(WindowConfiguration.WINDOWING_MODE_FREEFORM,
                mSecondaryDisplay.getWindowingMode());
    }

    @Test
    public void testDefaultToZeroOverscan() {
        mTarget.applySettingsToDisplayLocked(mPrimaryDisplay);

        assertOverscan(mPrimaryDisplay, 0 /* left */, 0 /* top */, 0 /* right */, 0 /* bottom */);
    }

    @Test
    public void testPersistOverscanInSameInstance() {
        final DisplayInfo info = mPrimaryDisplay.getDisplayInfo();
        mTarget.setOverscanLocked(info.uniqueId, info.name, 1 /* left */, 2 /* top */,
                3 /* right */, 4 /* bottom */);

        mTarget.applySettingsToDisplayLocked(mPrimaryDisplay);

        assertOverscan(mPrimaryDisplay, 1 /* left */, 2 /* top */, 3 /* right */, 4 /* bottom */);
    }

    @Test
    public void testPersistOverscanAcrossInstances() {
        final DisplayInfo info = mPrimaryDisplay.getDisplayInfo();
        mTarget.setOverscanLocked(info.uniqueId, info.name, 1 /* left */, 2 /* top */,
                3 /* right */, 4 /* bottom */);
        mTarget.writeSettingsLocked();

        DisplaySettings target = new DisplaySettings(sWm, mTestFolder);
        target.readSettingsLocked();

        target.applySettingsToDisplayLocked(mPrimaryDisplay);

        assertOverscan(mPrimaryDisplay, 1 /* left */, 2 /* top */, 3 /* right */, 4 /* bottom */);
    }

    private static void assertOverscan(DisplayContent display, int left, int top, int right,
            int bottom) {
        final DisplayInfo info = display.getDisplayInfo();

        assertEquals(left, info.overscanLeft);
        assertEquals(top, info.overscanTop);
        assertEquals(right, info.overscanRight);
        assertEquals(bottom, info.overscanBottom);
    }

    private static boolean deleteRecursively(File file) {
        if (file.isFile()) {
            return file.delete();
        }

        boolean fullyDeleted = true;
        final File[] files = file.listFiles();
        for (File child : files) {
            fullyDeleted &= deleteRecursively(child);
        }
        fullyDeleted &= file.delete();
        return fullyDeleted;
    }
}

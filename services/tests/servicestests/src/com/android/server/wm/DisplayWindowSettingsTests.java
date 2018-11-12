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

import static android.view.WindowManager.REMOVE_CONTENT_MODE_DESTROY;
import static android.view.WindowManager.REMOVE_CONTENT_MODE_MOVE_TO_PRIMARY;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;

import android.app.WindowConfiguration;
import android.platform.test.annotations.Presubmit;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.Surface;

import androidx.test.filters.SmallTest;

import com.android.server.policy.WindowManagerPolicy;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

/**
 * Tests for the {@link DisplayWindowSettings} class.
 *
 * Build/Install/Run:
 *  atest FrameworksServicesTests:DisplayWindowSettingsTests
 */
@SmallTest
@Presubmit
public class DisplayWindowSettingsTests extends WindowTestsBase {

    private static final File TEST_FOLDER = getInstrumentation().getTargetContext().getCacheDir();
    private DisplayWindowSettings mTarget;

    DisplayInfo mPrivateDisplayInfo;

    private DisplayContent mPrimaryDisplay;
    private DisplayContent mSecondaryDisplay;
    private DisplayContent mPrivateDisplay;

    @Before
    public void setUp() throws Exception {
        deleteRecursively(TEST_FOLDER);

        mWm.setSupportsFreeformWindowManagement(false);
        mWm.setIsPc(false);
        mWm.setForceDesktopModeOnExternalDisplays(false);

        mTarget = new DisplayWindowSettings(mWm, TEST_FOLDER);

        mPrimaryDisplay = mWm.getDefaultDisplayContentLocked();
        mSecondaryDisplay = mDisplayContent;
        assertNotEquals(Display.DEFAULT_DISPLAY, mSecondaryDisplay.getDisplayId());

        mPrivateDisplayInfo = new DisplayInfo(mDisplayInfo);
        mPrivateDisplayInfo.flags |= Display.FLAG_PRIVATE;
        mPrivateDisplay = createNewDisplay(mPrivateDisplayInfo);
        assertNotEquals(Display.DEFAULT_DISPLAY, mPrivateDisplay.getDisplayId());
        assertNotEquals(mSecondaryDisplay.getDisplayId(), mPrivateDisplay.getDisplayId());
    }

    @After
    public void tearDown() {
        deleteRecursively(TEST_FOLDER);
    }

    @Test
    public void testPrimaryDisplayDefaultToFullscreen_NoFreeformSupport() {
        mTarget.applySettingsToDisplayLocked(mPrimaryDisplay);

        assertEquals(WindowConfiguration.WINDOWING_MODE_FULLSCREEN,
                mPrimaryDisplay.getWindowingMode());
    }

    @Test
    public void testPrimaryDisplayDefaultToFullscreen_HasFreeformSupport_NonPc_NoDesktopMode() {
        mWm.setSupportsFreeformWindowManagement(true);

        mTarget.applySettingsToDisplayLocked(mPrimaryDisplay);

        assertEquals(WindowConfiguration.WINDOWING_MODE_FULLSCREEN,
                mPrimaryDisplay.getWindowingMode());
    }

    @Test
    public void testPrimaryDisplayDefaultToFullscreen_HasFreeformSupport_NonPc_HasDesktopMode() {
        mWm.setSupportsFreeformWindowManagement(true);
        mWm.setForceDesktopModeOnExternalDisplays(true);

        mTarget.applySettingsToDisplayLocked(mPrimaryDisplay);

        assertEquals(WindowConfiguration.WINDOWING_MODE_FULLSCREEN,
                mPrimaryDisplay.getWindowingMode());
    }

    @Test
    public void testPrimaryDisplayDefaultToFreeform_HasFreeformSupport_IsPc() {
        mWm.setSupportsFreeformWindowManagement(true);
        mWm.setIsPc(true);

        mTarget.applySettingsToDisplayLocked(mPrimaryDisplay);

        assertEquals(WindowConfiguration.WINDOWING_MODE_FREEFORM,
                mPrimaryDisplay.getWindowingMode());
    }

    @Test
    public void testSecondaryDisplayDefaultToFullscreen_NoFreeformSupport() {
        mTarget.applySettingsToDisplayLocked(mSecondaryDisplay);

        assertEquals(WindowConfiguration.WINDOWING_MODE_FULLSCREEN,
                mSecondaryDisplay.getWindowingMode());
    }

    @Test
    public void testSecondaryDisplayDefaultToFreeform_HasFreeformSupport_NonPc_NoDesktopMode() {
        mWm.setSupportsFreeformWindowManagement(true);

        mTarget.applySettingsToDisplayLocked(mSecondaryDisplay);

        assertEquals(WindowConfiguration.WINDOWING_MODE_FULLSCREEN,
                mSecondaryDisplay.getWindowingMode());
    }

    @Test
    public void testSecondaryDisplayDefaultToFreeform_HasFreeformSupport_NonPc_HasDesktopMode() {
        mWm.setSupportsFreeformWindowManagement(true);
        mWm.setForceDesktopModeOnExternalDisplays(true);

        mTarget.applySettingsToDisplayLocked(mSecondaryDisplay);

        assertEquals(WindowConfiguration.WINDOWING_MODE_FREEFORM,
                mSecondaryDisplay.getWindowingMode());
    }

    @Test
    public void testSecondaryDisplayDefaultToFreeform_HasFreeformSupport_IsPc() {
        mWm.setSupportsFreeformWindowManagement(true);
        mWm.setIsPc(true);

        mTarget.applySettingsToDisplayLocked(mSecondaryDisplay);

        assertEquals(WindowConfiguration.WINDOWING_MODE_FREEFORM,
                mSecondaryDisplay.getWindowingMode());
    }

    @Test
    public void testDefaultToOriginalMetrics() {
        final int originalWidth = mSecondaryDisplay.mBaseDisplayWidth;
        final int originalHeight = mSecondaryDisplay.mBaseDisplayHeight;
        final int originalDensity = mSecondaryDisplay.mBaseDisplayDensity;
        final boolean originalScalingDisabled = mSecondaryDisplay.mDisplayScalingDisabled;

        mTarget.applySettingsToDisplayLocked(mSecondaryDisplay);

        assertEquals(originalWidth, mSecondaryDisplay.mBaseDisplayWidth);
        assertEquals(originalHeight, mSecondaryDisplay.mBaseDisplayHeight);
        assertEquals(originalDensity, mSecondaryDisplay.mBaseDisplayDensity);
        assertEquals(originalScalingDisabled, mSecondaryDisplay.mDisplayScalingDisabled);
    }

    @Test
    public void testSetForcedSize() {
        final DisplayInfo originalInfo = new DisplayInfo(mSecondaryDisplay.getDisplayInfo());
        // Provides the orginal display info to avoid changing initial display size.
        doAnswer(invocation -> {
            ((DisplayInfo) invocation.getArguments()[1]).copyFrom(originalInfo);
            return null;
        }).when(mWm.mDisplayManagerInternal).getNonOverrideDisplayInfo(anyInt(), any());

        mTarget.setForcedSize(mSecondaryDisplay, 1000 /* width */, 2000 /* height */);
        applySettingsToDisplayByNewInstance(mSecondaryDisplay);

        assertEquals(1000 /* width */, mSecondaryDisplay.mBaseDisplayWidth);
        assertEquals(2000 /* height */, mSecondaryDisplay.mBaseDisplayHeight);

        mWm.clearForcedDisplaySize(mSecondaryDisplay.getDisplayId());
        assertEquals(mSecondaryDisplay.mInitialDisplayWidth, mSecondaryDisplay.mBaseDisplayWidth);
        assertEquals(mSecondaryDisplay.mInitialDisplayHeight, mSecondaryDisplay.mBaseDisplayHeight);
    }

    @Test
    public void testSetForcedDensity() {
        mTarget.setForcedDensity(mSecondaryDisplay, 600 /* density */, 0 /* userId */);
        applySettingsToDisplayByNewInstance(mSecondaryDisplay);

        assertEquals(600 /* density */, mSecondaryDisplay.mBaseDisplayDensity);

        mWm.clearForcedDisplayDensityForUser(mSecondaryDisplay.getDisplayId(), 0 /* userId */);
        assertEquals(mSecondaryDisplay.mInitialDisplayDensity,
                mSecondaryDisplay.mBaseDisplayDensity);
    }

    @Test
    public void testSetForcedScalingMode() {
        mTarget.setForcedScalingMode(mSecondaryDisplay, DisplayContent.FORCE_SCALING_MODE_DISABLED);
        applySettingsToDisplayByNewInstance(mSecondaryDisplay);

        assertTrue(mSecondaryDisplay.mDisplayScalingDisabled);

        mWm.setForcedDisplayScalingMode(mSecondaryDisplay.getDisplayId(),
                DisplayContent.FORCE_SCALING_MODE_AUTO);
        assertFalse(mSecondaryDisplay.mDisplayScalingDisabled);
    }

    @Test
    public void testDefaultToZeroOverscan() {
        mTarget.applySettingsToDisplayLocked(mPrimaryDisplay);

        assertOverscan(mPrimaryDisplay, 0 /* left */, 0 /* top */, 0 /* right */, 0 /* bottom */);
    }

    @Test
    public void testPersistOverscanInSameInstance() {
        final DisplayInfo info = mPrimaryDisplay.getDisplayInfo();
        mTarget.setOverscanLocked(info, 1 /* left */, 2 /* top */, 3 /* right */, 4 /* bottom */);

        mTarget.applySettingsToDisplayLocked(mPrimaryDisplay);

        assertOverscan(mPrimaryDisplay, 1 /* left */, 2 /* top */, 3 /* right */, 4 /* bottom */);
    }

    @Test
    public void testPersistOverscanAcrossInstances() {
        final DisplayInfo info = mPrimaryDisplay.getDisplayInfo();
        mTarget.setOverscanLocked(info, 1 /* left */, 2 /* top */, 3 /* right */, 4 /* bottom */);

        applySettingsToDisplayByNewInstance(mPrimaryDisplay);

        assertOverscan(mPrimaryDisplay, 1 /* left */, 2 /* top */, 3 /* right */, 4 /* bottom */);
    }

    @Test
    public void testDefaultToFreeUserRotation() {
        mTarget.applySettingsToDisplayLocked(mSecondaryDisplay);

        final DisplayRotation rotation = mSecondaryDisplay.getDisplayRotation();
        assertEquals(WindowManagerPolicy.USER_ROTATION_FREE, rotation.getUserRotationMode());
        assertFalse(rotation.isRotationFrozen());
    }

    @Test
    public void testDefaultTo0DegRotation() {
        mTarget.applySettingsToDisplayLocked(mSecondaryDisplay);

        assertEquals(Surface.ROTATION_0, mSecondaryDisplay.getDisplayRotation().getUserRotation());
    }

    @Test
    public void testPrivateDisplayDefaultToDestroyContent() {
        assertEquals(REMOVE_CONTENT_MODE_DESTROY,
                mTarget.getRemoveContentModeLocked(mPrivateDisplay));
    }

    @Test
    public void testPublicDisplayDefaultToMoveToPrimary() {
        assertEquals(REMOVE_CONTENT_MODE_MOVE_TO_PRIMARY,
                mTarget.getRemoveContentModeLocked(mSecondaryDisplay));
    }

    @Test
    public void testDefaultToNotShowWithInsecureKeyguard() {
        assertFalse(mTarget.shouldShowWithInsecureKeyguardLocked(mPrivateDisplay));
        assertFalse(mTarget.shouldShowWithInsecureKeyguardLocked(mSecondaryDisplay));
    }

    @Test
    public void testPublicDisplayNotAllowSetShouldShowWithInsecureKeyguard() {
        mTarget.setShouldShowWithInsecureKeyguardLocked(mSecondaryDisplay, true);

        assertFalse(mTarget.shouldShowWithInsecureKeyguardLocked(mSecondaryDisplay));
    }

    @Test
    public void testPrivateDisplayAllowSetShouldShowWithInsecureKeyguard() {
        mTarget.setShouldShowWithInsecureKeyguardLocked(mPrivateDisplay, true);

        assertTrue(mTarget.shouldShowWithInsecureKeyguardLocked(mPrivateDisplay));
    }

    @Test
    public void testPrimaryDisplayShouldShowSystemDecors() {
        assertTrue(mTarget.shouldShowSystemDecorsLocked(mPrimaryDisplay));

        mTarget.setShouldShowSystemDecorsLocked(mPrimaryDisplay, false);

        // Default display should show system decors
        assertTrue(mTarget.shouldShowSystemDecorsLocked(mPrimaryDisplay));
    }

    @Test
    public void testSecondaryDisplayDefaultToNotShowSystemDecors() {
        assertFalse(mTarget.shouldShowSystemDecorsLocked(mSecondaryDisplay));
    }

    @Test
    public void testPrimaryDisplayShouldShowIme() {
        assertTrue(mTarget.shouldShowImeLocked(mPrimaryDisplay));

        mTarget.setShouldShowImeLocked(mPrimaryDisplay, false);

        assertTrue(mTarget.shouldShowImeLocked(mPrimaryDisplay));
    }

    @Test
    public void testSecondaryDisplayDefaultToNotShowIme() {
        assertFalse(mTarget.shouldShowImeLocked(mSecondaryDisplay));
    }

    @Test
    public void testPersistUserRotationModeInSameInstance() {
        mTarget.setUserRotation(mSecondaryDisplay, WindowManagerPolicy.USER_ROTATION_LOCKED,
                Surface.ROTATION_90);

        mTarget.applySettingsToDisplayLocked(mSecondaryDisplay);

        final DisplayRotation rotation = mSecondaryDisplay.getDisplayRotation();
        assertEquals(WindowManagerPolicy.USER_ROTATION_LOCKED, rotation.getUserRotationMode());
        assertTrue(rotation.isRotationFrozen());
    }

    @Test
    public void testPersistUserRotationInSameInstance() {
        mTarget.setUserRotation(mSecondaryDisplay, WindowManagerPolicy.USER_ROTATION_LOCKED,
                Surface.ROTATION_90);

        mTarget.applySettingsToDisplayLocked(mSecondaryDisplay);

        assertEquals(Surface.ROTATION_90, mSecondaryDisplay.getDisplayRotation().getUserRotation());
    }

    @Test
    public void testPersistUserRotationModeAcrossInstances() {
        mTarget.setUserRotation(mSecondaryDisplay, WindowManagerPolicy.USER_ROTATION_LOCKED,
                Surface.ROTATION_270);

        applySettingsToDisplayByNewInstance(mSecondaryDisplay);

        final DisplayRotation rotation = mSecondaryDisplay.getDisplayRotation();
        assertEquals(WindowManagerPolicy.USER_ROTATION_LOCKED, rotation.getUserRotationMode());
        assertTrue(rotation.isRotationFrozen());
    }

    @Test
    public void testPersistUserRotationAcrossInstances() {
        mTarget.setUserRotation(mSecondaryDisplay, WindowManagerPolicy.USER_ROTATION_LOCKED,
                Surface.ROTATION_270);

        applySettingsToDisplayByNewInstance(mSecondaryDisplay);

        assertEquals(Surface.ROTATION_270,
                mSecondaryDisplay.getDisplayRotation().getUserRotation());
    }

    private static void assertOverscan(DisplayContent display, int left, int top, int right,
            int bottom) {
        final DisplayInfo info = display.getDisplayInfo();

        assertEquals(left, info.overscanLeft);
        assertEquals(top, info.overscanTop);
        assertEquals(right, info.overscanRight);
        assertEquals(bottom, info.overscanBottom);
    }

    /**
     * This method helps to ensure read and write persistent settings successfully because the
     * constructor of {@link DisplayWindowSettings} should read the persistent file from the given
     * path that also means the previous state must be written correctly.
     */
    private void applySettingsToDisplayByNewInstance(DisplayContent display) {
        new DisplayWindowSettings(mWm, TEST_FOLDER).applySettingsToDisplayLocked(display);
    }

    private static boolean deleteRecursively(File file) {
        boolean fullyDeleted = true;
        if (file.isFile()) {
            return file.delete();
        } else if (file.isDirectory()) {
            final File[] files = file.listFiles();
            for (File child : files) {
                fullyDeleted &= deleteRecursively(child);
            }
            fullyDeleted &= file.delete();
        }
        return fullyDeleted;
    }
}

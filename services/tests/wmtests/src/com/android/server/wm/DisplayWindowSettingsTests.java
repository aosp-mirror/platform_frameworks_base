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

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.view.IWindowManager.FIXED_TO_USER_ROTATION_DEFAULT;
import static android.view.IWindowManager.FIXED_TO_USER_ROTATION_DISABLED;
import static android.view.IWindowManager.FIXED_TO_USER_ROTATION_ENABLED;
import static android.view.WindowManager.DISPLAY_IME_POLICY_FALLBACK_DISPLAY;
import static android.view.WindowManager.DISPLAY_IME_POLICY_LOCAL;
import static android.view.WindowManager.REMOVE_CONTENT_MODE_DESTROY;
import static android.view.WindowManager.REMOVE_CONTENT_MODE_MOVE_TO_PRIMARY;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Matchers.eq;

import android.annotation.NonNull;
import android.app.WindowConfiguration;
import android.content.ContentResolver;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.Surface;

import androidx.test.filters.SmallTest;

import com.android.server.LocalServices;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.wm.DisplayWindowSettings.SettingsProvider.SettingsEntry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.Map;

/**
 * Tests for the {@link DisplayWindowSettings} class.
 *
 * Build/Install/Run:
 *  atest WmTests:DisplayWindowSettingsTests
 */
@SmallTest
@Presubmit
@WindowTestsBase.UseTestDisplay
@RunWith(WindowTestRunner.class)
public class DisplayWindowSettingsTests extends WindowTestsBase {
    private TestSettingsProvider mSettingsProvider;
    private DisplayWindowSettings mDisplayWindowSettings;

    private DisplayInfo mPrivateDisplayInfo;

    private DisplayContent mPrimaryDisplay;
    private DisplayContent mSecondaryDisplay;
    private DisplayContent mPrivateDisplay;

    @Before
    public void setUp() throws Exception {
        // TODO(b/121296525): We may want to restore other display settings (not only overscans in
        // testPersistOverscan*test) on mPrimaryDisplay and mSecondaryDisplay back to default
        // values after each test finishes, since we are going to reuse a singleton
        // WindowManagerService instance among all tests that extend {@link WindowTestsBase} class
        // (b/113239988).
        mWm.mAtmService.mSupportsFreeformWindowManagement = false;
        mWm.setIsPc(false);
        mWm.setForceDesktopModeOnExternalDisplays(false);

        mSettingsProvider = new TestSettingsProvider();
        mDisplayWindowSettings = new DisplayWindowSettings(mWm, mSettingsProvider);

        mPrimaryDisplay = mWm.getDefaultDisplayContentLocked();
        mSecondaryDisplay = mDisplayContent;
        assertNotEquals(Display.DEFAULT_DISPLAY, mSecondaryDisplay.getDisplayId());

        mPrivateDisplayInfo = new DisplayInfo(mDisplayInfo);
        mPrivateDisplayInfo.flags |= Display.FLAG_PRIVATE;
        mPrivateDisplay = createNewDisplay(mPrivateDisplayInfo);
        assertNotEquals(Display.DEFAULT_DISPLAY, mPrivateDisplay.getDisplayId());
        assertNotEquals(mSecondaryDisplay.getDisplayId(), mPrivateDisplay.getDisplayId());
    }

    @Test
    public void testPrimaryDisplayDefaultToFullscreen_NoFreeformSupport() {
        mDisplayWindowSettings.applySettingsToDisplayLocked(mPrimaryDisplay);

        assertEquals(WindowConfiguration.WINDOWING_MODE_FULLSCREEN,
                mPrimaryDisplay.getDefaultTaskDisplayArea().getWindowingMode());
    }

    @Test
    public void testPrimaryDisplayDefaultToFullscreen_HasFreeformSupport_NonPc_NoDesktopMode() {
        mWm.mAtmService.mSupportsFreeformWindowManagement = true;

        mDisplayWindowSettings.applySettingsToDisplayLocked(mPrimaryDisplay);

        assertEquals(WindowConfiguration.WINDOWING_MODE_FULLSCREEN,
                mPrimaryDisplay.getDefaultTaskDisplayArea().getWindowingMode());
    }

    @Test
    public void testPrimaryDisplayDefaultToFullscreen_HasFreeformSupport_NonPc_HasDesktopMode() {
        mWm.mAtmService.mSupportsFreeformWindowManagement = true;
        mWm.setForceDesktopModeOnExternalDisplays(true);

        mDisplayWindowSettings.applySettingsToDisplayLocked(mPrimaryDisplay);

        assertEquals(WindowConfiguration.WINDOWING_MODE_FULLSCREEN,
                mPrimaryDisplay.getDefaultTaskDisplayArea().getWindowingMode());
    }

    @Test
    public void testPrimaryDisplayDefaultToFreeform_HasFreeformSupport_IsPc() {
        mWm.mAtmService.mSupportsFreeformWindowManagement = true;
        mWm.setIsPc(true);

        mDisplayWindowSettings.applySettingsToDisplayLocked(mPrimaryDisplay);

        assertEquals(WINDOWING_MODE_FREEFORM,
                mPrimaryDisplay.getDefaultTaskDisplayArea().getWindowingMode());
    }

    @Test
    public void testPrimaryDisplayUpdateToFreeform_HasFreeformSupport_IsPc() {
        mDisplayWindowSettings.applySettingsToDisplayLocked(mPrimaryDisplay);

        mWm.mAtmService.mSupportsFreeformWindowManagement = true;
        mWm.setIsPc(true);

        mDisplayWindowSettings.updateSettingsForDisplay(mPrimaryDisplay);

        assertEquals(WindowConfiguration.WINDOWING_MODE_FREEFORM,
                mPrimaryDisplay.getDefaultTaskDisplayArea().getWindowingMode());
    }

    @Test
    public void testSecondaryDisplayDefaultToFullscreen_NoFreeformSupport() {
        mDisplayWindowSettings.applySettingsToDisplayLocked(mSecondaryDisplay);

        assertEquals(WindowConfiguration.WINDOWING_MODE_FULLSCREEN,
                mSecondaryDisplay.getDefaultTaskDisplayArea().getWindowingMode());
    }

    @Test
    public void testSecondaryDisplayDefaultToFreeform_HasFreeformSupport_NonPc_NoDesktopMode() {
        mWm.mAtmService.mSupportsFreeformWindowManagement = true;

        mDisplayWindowSettings.applySettingsToDisplayLocked(mSecondaryDisplay);

        assertEquals(WindowConfiguration.WINDOWING_MODE_FULLSCREEN,
                mSecondaryDisplay.getDefaultTaskDisplayArea().getWindowingMode());
    }

    @Test
    public void testSecondaryDisplayDefaultToFreeform_HasFreeformSupport_NonPc_HasDesktopMode() {
        mWm.mAtmService.mSupportsFreeformWindowManagement = true;
        mWm.setForceDesktopModeOnExternalDisplays(true);

        mDisplayWindowSettings.applySettingsToDisplayLocked(mSecondaryDisplay);

        assertEquals(WINDOWING_MODE_FREEFORM,
                mSecondaryDisplay.getDefaultTaskDisplayArea().getWindowingMode());
    }

    @Test
    public void testSecondaryDisplayDefaultToFreeform_HasFreeformSupport_IsPc() {
        mWm.mAtmService.mSupportsFreeformWindowManagement = true;
        mWm.setIsPc(true);

        mDisplayWindowSettings.applySettingsToDisplayLocked(mSecondaryDisplay);

        assertEquals(WINDOWING_MODE_FREEFORM,
                mSecondaryDisplay.getDefaultTaskDisplayArea().getWindowingMode());
    }

    @Test
    public void testDefaultToOriginalMetrics() {
        final int originalWidth = mSecondaryDisplay.mBaseDisplayWidth;
        final int originalHeight = mSecondaryDisplay.mBaseDisplayHeight;
        final int originalDensity = mSecondaryDisplay.mBaseDisplayDensity;
        final boolean originalScalingDisabled = mSecondaryDisplay.mDisplayScalingDisabled;

        mDisplayWindowSettings.applySettingsToDisplayLocked(mSecondaryDisplay);

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

        mDisplayWindowSettings.setForcedSize(mSecondaryDisplay, 1000 /* width */,
                2000 /* height */);
        mDisplayWindowSettings.applySettingsToDisplayLocked(mSecondaryDisplay);

        assertEquals(1000 /* width */, mSecondaryDisplay.mBaseDisplayWidth);
        assertEquals(2000 /* height */, mSecondaryDisplay.mBaseDisplayHeight);

        mWm.clearForcedDisplaySize(mSecondaryDisplay.getDisplayId());
        assertEquals(mSecondaryDisplay.mInitialDisplayWidth, mSecondaryDisplay.mBaseDisplayWidth);
        assertEquals(mSecondaryDisplay.mInitialDisplayHeight, mSecondaryDisplay.mBaseDisplayHeight);
    }

    @Test
    public void testSetForcedDensity() {
        mDisplayWindowSettings.setForcedDensity(mSecondaryDisplay.getDisplayInfo(),
                600 /* density */, 0 /* userId */);
        mDisplayWindowSettings.applySettingsToDisplayLocked(mSecondaryDisplay);

        assertEquals(600 /* density */, mSecondaryDisplay.mBaseDisplayDensity);

        mWm.clearForcedDisplayDensityForUser(mSecondaryDisplay.getDisplayId(), 0 /* userId */);
        assertEquals(mSecondaryDisplay.mInitialDisplayDensity,
                mSecondaryDisplay.mBaseDisplayDensity);
    }

    @Test
    public void testSetForcedScalingMode() {
        mDisplayWindowSettings.setForcedScalingMode(mSecondaryDisplay,
                DisplayContent.FORCE_SCALING_MODE_DISABLED);
        mDisplayWindowSettings.applySettingsToDisplayLocked(mSecondaryDisplay);

        assertTrue(mSecondaryDisplay.mDisplayScalingDisabled);

        mWm.setForcedDisplayScalingMode(mSecondaryDisplay.getDisplayId(),
                DisplayContent.FORCE_SCALING_MODE_AUTO);
        assertFalse(mSecondaryDisplay.mDisplayScalingDisabled);
    }

    @Test
    public void testResetAllowAllRotations() {
        final DisplayRotation displayRotation = mock(DisplayRotation.class);
        spyOn(mPrimaryDisplay);
        doReturn(displayRotation).when(mPrimaryDisplay).getDisplayRotation();

        mDisplayWindowSettings.applyRotationSettingsToDisplayLocked(mPrimaryDisplay);

        verify(displayRotation).resetAllowAllRotations();
    }

    @Test
    public void testDefaultToFreeUserRotation() {
        mDisplayWindowSettings.applySettingsToDisplayLocked(mSecondaryDisplay);

        final DisplayRotation rotation = mSecondaryDisplay.getDisplayRotation();
        assertEquals(WindowManagerPolicy.USER_ROTATION_FREE, rotation.getUserRotationMode());
        assertFalse(rotation.isRotationFrozen());
    }

    @Test
    public void testDefaultTo0DegRotation() {
        mDisplayWindowSettings.applySettingsToDisplayLocked(mSecondaryDisplay);

        assertEquals(Surface.ROTATION_0, mSecondaryDisplay.getDisplayRotation().getUserRotation());
    }

    @Test
    public void testPrivateDisplayDefaultToDestroyContent() {
        assertEquals(REMOVE_CONTENT_MODE_DESTROY,
                mDisplayWindowSettings.getRemoveContentModeLocked(mPrivateDisplay));
    }

    @Test
    public void testPublicDisplayDefaultToMoveToPrimary() {
        assertEquals(REMOVE_CONTENT_MODE_MOVE_TO_PRIMARY,
                mDisplayWindowSettings.getRemoveContentModeLocked(mSecondaryDisplay));
    }

    @Test
    public void testDefaultToNotShowWithInsecureKeyguard() {
        assertFalse(mDisplayWindowSettings.shouldShowWithInsecureKeyguardLocked(mPrivateDisplay));
        assertFalse(mDisplayWindowSettings.shouldShowWithInsecureKeyguardLocked(mSecondaryDisplay));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPublicDisplayNotAllowSetShouldShowWithInsecureKeyguard() {
        mDisplayWindowSettings.setShouldShowWithInsecureKeyguardLocked(mSecondaryDisplay, true);
    }

    @Test
    public void testPrivateDisplayAllowSetShouldShowWithInsecureKeyguard() {
        mDisplayWindowSettings.setShouldShowWithInsecureKeyguardLocked(mPrivateDisplay, true);

        assertTrue(mDisplayWindowSettings.shouldShowWithInsecureKeyguardLocked(mPrivateDisplay));
    }

    @Test
    public void testPrimaryDisplayShouldShowSystemDecors() {
        assertTrue(mDisplayWindowSettings.shouldShowSystemDecorsLocked(mPrimaryDisplay));

        mDisplayWindowSettings.setShouldShowSystemDecorsLocked(mPrimaryDisplay, false);

        // Default display should show system decors
        assertTrue(mDisplayWindowSettings.shouldShowSystemDecorsLocked(mPrimaryDisplay));
    }

    @Test
    public void testSecondaryDisplayDefaultToNotShowSystemDecors() {
        assertFalse(mDisplayWindowSettings.shouldShowSystemDecorsLocked(mSecondaryDisplay));
    }

    @Test
    public void testPrimaryDisplayImePolicy() {
        assertEquals(DISPLAY_IME_POLICY_LOCAL,
                mDisplayWindowSettings.getImePolicyLocked(mPrimaryDisplay));

        mDisplayWindowSettings.setDisplayImePolicy(mPrimaryDisplay,
                DISPLAY_IME_POLICY_FALLBACK_DISPLAY);

        assertEquals(DISPLAY_IME_POLICY_LOCAL,
                mDisplayWindowSettings.getImePolicyLocked(mPrimaryDisplay));
    }

    @Test
    public void testSecondaryDisplayDefaultToShowImeOnFallbackDisplay() {
        assertEquals(DISPLAY_IME_POLICY_FALLBACK_DISPLAY,
                mDisplayWindowSettings.getImePolicyLocked(mSecondaryDisplay));
    }

    @Test
    public void testSetUserRotationMode() {
        mDisplayWindowSettings.setUserRotation(mSecondaryDisplay,
                WindowManagerPolicy.USER_ROTATION_LOCKED, Surface.ROTATION_90);

        mDisplayWindowSettings.applySettingsToDisplayLocked(mSecondaryDisplay);

        final DisplayRotation rotation = mSecondaryDisplay.getDisplayRotation();
        assertEquals(WindowManagerPolicy.USER_ROTATION_LOCKED, rotation.getUserRotationMode());
        assertTrue(rotation.isRotationFrozen());
    }

    @Test
    public void testSetUserRotation() {
        mDisplayWindowSettings.setUserRotation(mSecondaryDisplay,
                WindowManagerPolicy.USER_ROTATION_LOCKED, Surface.ROTATION_90);

        mDisplayWindowSettings.applySettingsToDisplayLocked(mSecondaryDisplay);

        assertEquals(Surface.ROTATION_90, mSecondaryDisplay.getDisplayRotation().getUserRotation());
    }

    @Test
    public void testFixedToUserRotationDefault() {
        mDisplayWindowSettings.setUserRotation(mPrimaryDisplay,
                WindowManagerPolicy.USER_ROTATION_LOCKED, Surface.ROTATION_0);

        final DisplayRotation displayRotation = mock(DisplayRotation.class);
        spyOn(mPrimaryDisplay);
        doReturn(displayRotation).when(mPrimaryDisplay).getDisplayRotation();

        mDisplayWindowSettings.applySettingsToDisplayLocked(mPrimaryDisplay);

        verify(displayRotation).restoreSettings(anyInt(), anyInt(),
                eq(FIXED_TO_USER_ROTATION_DEFAULT));
    }

    @Test
    public void testSetFixedToUserRotationDisabled() {
        mDisplayWindowSettings.setFixedToUserRotation(mPrimaryDisplay,
                FIXED_TO_USER_ROTATION_DISABLED);

        final DisplayRotation displayRotation = mock(DisplayRotation.class);
        spyOn(mPrimaryDisplay);
        doReturn(displayRotation).when(mPrimaryDisplay).getDisplayRotation();

        mDisplayWindowSettings.applySettingsToDisplayLocked(mPrimaryDisplay);

        verify(displayRotation).restoreSettings(anyInt(), anyInt(),
                eq(FIXED_TO_USER_ROTATION_DISABLED));
    }

    @Test
    public void testSetFixedToUserRotationEnabled() {
        mDisplayWindowSettings.setFixedToUserRotation(mPrimaryDisplay,
                FIXED_TO_USER_ROTATION_ENABLED);

        final DisplayRotation displayRotation = mock(DisplayRotation.class);
        spyOn(mPrimaryDisplay);
        doReturn(displayRotation).when(mPrimaryDisplay).getDisplayRotation();

        mDisplayWindowSettings.applySettingsToDisplayLocked(mPrimaryDisplay);

        verify(displayRotation).restoreSettings(anyInt(), anyInt(),
                eq(FIXED_TO_USER_ROTATION_ENABLED));
    }

    @Test
    public void testShouldShowImeOnDisplayWithinForceDesktopMode() {
        try {
            // Presume display enabled force desktop mode from developer options.
            final DisplayContent dc = createMockSimulatedDisplay();
            mWm.setForceDesktopModeOnExternalDisplays(true);
            final WindowManagerInternal wmInternal = LocalServices.getService(
                    WindowManagerInternal.class);
            // Make sure WindowManagerInter#getDisplayImePolicy is SHOW_IME_ON_DISPLAY is due to
            // mForceDesktopModeOnExternalDisplays being SHOW_IME_ON_DISPLAY.
            assertEquals(DISPLAY_IME_POLICY_FALLBACK_DISPLAY,
                    mWm.mDisplayWindowSettings.getImePolicyLocked(dc));
            assertEquals(DISPLAY_IME_POLICY_LOCAL, wmInternal.getDisplayImePolicy(dc.getDisplayId()));
        } finally {
            mWm.setForceDesktopModeOnExternalDisplays(false);
        }
    }

    @Test
    public void testDisplayWindowSettingsAppliedOnDisplayReady() {
        // Set forced densities for two displays in DisplayWindowSettings
        final DisplayContent dc = createMockSimulatedDisplay();
        final ContentResolver contentResolver = useFakeSettingsProvider();
        mDisplayWindowSettings.setForcedDensity(mPrimaryDisplay.getDisplayInfo(), 123,
                0 /* userId */);
        mDisplayWindowSettings.setForcedDensity(dc.getDisplayInfo(), 456, 0 /* userId */);

        // Apply settings to displays - the settings will be stored, but config will not be
        // recalculated immediately.
        mDisplayWindowSettings.applySettingsToDisplayLocked(mPrimaryDisplay);
        mDisplayWindowSettings.applySettingsToDisplayLocked(dc);
        assertFalse(mPrimaryDisplay.mWaitingForConfig);
        assertFalse(dc.mWaitingForConfig);

        final int invalidW = Integer.MAX_VALUE;
        final int invalidH = Integer.MAX_VALUE;
        // Verify that applyForcedPropertiesForDefaultDisplay() handles invalid size request.
        Settings.Global.putString(contentResolver, Settings.Global.DISPLAY_SIZE_FORCED,
                invalidW + "," + invalidH);
        // Notify WM that the displays are ready and check that they are reconfigured.
        mWm.displayReady();
        waitUntilHandlersIdle();

        // Density is set successfully.
        assertEquals(123, mPrimaryDisplay.getConfiguration().densityDpi);
        assertEquals(456, dc.getConfiguration().densityDpi);
        // Invalid size won't be applied.
        assertNotEquals(invalidW, mPrimaryDisplay.mBaseDisplayWidth);
        assertNotEquals(invalidH, mPrimaryDisplay.mBaseDisplayHeight);
    }

    @Test
    public void testDisplayRotationSettingsAppliedOnCreation() {
        // Create new displays with different rotation settings
        final SettingsEntry settingsEntry1 = new SettingsEntry();
        settingsEntry1.mIgnoreOrientationRequest = false;
        final DisplayContent dcDontIgnoreOrientation = createMockSimulatedDisplay(settingsEntry1);
        final SettingsEntry settingsEntry2 = new SettingsEntry();
        settingsEntry2.mIgnoreOrientationRequest = true;
        final DisplayContent dcIgnoreOrientation = createMockSimulatedDisplay(settingsEntry2);

        // Verify that newly created displays are created with correct rotation settings
        assertFalse(dcDontIgnoreOrientation.getIgnoreOrientationRequest());
        assertTrue(dcIgnoreOrientation.getIgnoreOrientationRequest());
    }

    @Test
    public void testDisplayRemoval() {
        spyOn(mWm.mDisplayWindowSettings);
        spyOn(mWm.mDisplayWindowSettingsProvider);

        mPrivateDisplay.removeImmediately();

        verify(mWm.mDisplayWindowSettings).onDisplayRemoved(mPrivateDisplay);
        verify(mWm.mDisplayWindowSettingsProvider).onDisplayRemoved(
                mPrivateDisplay.getDisplayInfo());
    }

    @Test
    public void testClearDisplaySettings() {
        spyOn(mWm.mDisplayWindowSettings);
        spyOn(mWm.mDisplayWindowSettingsProvider);

        WindowManagerInternal wmInternal = LocalServices.getService(WindowManagerInternal.class);
        DisplayInfo info = mPrivateDisplay.getDisplayInfo();
        wmInternal.clearDisplaySettings(info.uniqueId, info.type);

        verify(mWm.mDisplayWindowSettings).clearDisplaySettings(info.uniqueId, info.type);
        verify(mWm.mDisplayWindowSettingsProvider).clearDisplaySettings(info);
    }

    public final class TestSettingsProvider implements DisplayWindowSettings.SettingsProvider {
        Map<DisplayInfo, SettingsEntry> mOverrideSettingsCache = new HashMap<>();

        @Override
        public SettingsEntry getSettings(@NonNull DisplayInfo info) {
            return getOverrideSettings(info);
        }

        @Override
        public SettingsEntry getOverrideSettings(@NonNull DisplayInfo info) {
            SettingsEntry result = new SettingsEntry();
            SettingsEntry overrideSettings = mOverrideSettingsCache.get(info);
            if (overrideSettings != null) {
                result.setTo(overrideSettings);
            }
            return result;
        }

        @Override
        public void updateOverrideSettings(@NonNull DisplayInfo info,
                @NonNull SettingsEntry settings) {
            SettingsEntry overrideSettings = mOverrideSettingsCache.get(info);
            if (overrideSettings == null) {
                overrideSettings = new SettingsEntry();
                mOverrideSettingsCache.put(info, overrideSettings);
            }

            overrideSettings.setTo(settings);
        }

        @Override
        public void onDisplayRemoved(@NonNull DisplayInfo info) {
            mOverrideSettingsCache.remove(info);
        }

        @Override
        public void clearDisplaySettings(@NonNull DisplayInfo info) {
            mOverrideSettingsCache.remove(info);
        }
    }
}

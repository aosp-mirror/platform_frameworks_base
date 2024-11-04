/*
 * Copyright (C) 2022 The Android Open Source Project
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
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.content.res.Configuration.ORIENTATION_UNDEFINED;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.platform.test.annotations.Presubmit;
import android.view.Surface;
import android.view.WindowInsets.Type;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test class for {@link DisplayRotationImmersiveAppCompatPolicy}.
 *
 * Build/Install/Run:
 *  atest WmTests:DisplayRotationImmersiveAppCompatPolicyTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class DisplayRotationImmersiveAppCompatPolicyTests extends WindowTestsBase {

    private DisplayRotationImmersiveAppCompatPolicy mPolicy;

    private AppCompatConfiguration mMockAppCompatConfiguration;
    private ActivityRecord mMockActivityRecord;
    private Task mMockTask;
    private WindowState mMockWindowState;

    @Before
    public void setUp() throws Exception {
        mMockActivityRecord = mock(ActivityRecord.class);
        mMockTask = mock(Task.class);
        when(mMockTask.getWindowingMode()).thenReturn(WINDOWING_MODE_FULLSCREEN);
        when(mMockActivityRecord.getTask()).thenReturn(mMockTask);
        when(mMockActivityRecord.areBoundsLetterboxed()).thenReturn(false);
        when(mMockActivityRecord.getRequestedConfigurationOrientation()).thenReturn(
                ORIENTATION_LANDSCAPE);
        mMockWindowState = mock(WindowState.class);
        when(mMockWindowState.getRequestedVisibleTypes()).thenReturn(0);
        when(mMockActivityRecord.findMainWindow()).thenReturn(mMockWindowState);

        doReturn(mMockActivityRecord).when(mDisplayContent).topRunningActivity();
        when(mDisplayContent.getIgnoreOrientationRequest()).thenReturn(true);

        mMockAppCompatConfiguration = mock(AppCompatConfiguration.class);
        when(mMockAppCompatConfiguration.isDisplayRotationImmersiveAppCompatPolicyEnabled())
                .thenReturn(true);
        when(mMockAppCompatConfiguration
                .isDisplayRotationImmersiveAppCompatPolicyEnabledAtBuildTime())
                    .thenReturn(true);

        mPolicy = DisplayRotationImmersiveAppCompatPolicy.createIfNeeded(
                mMockAppCompatConfiguration, createDisplayRotationMock(),
                mDisplayContent);
    }

    private DisplayRotation createDisplayRotationMock() {
        DisplayRotation mockDisplayRotation = mock(DisplayRotation.class);

        when(mockDisplayRotation.isAnyPortrait(Surface.ROTATION_0)).thenReturn(true);
        when(mockDisplayRotation.isAnyPortrait(Surface.ROTATION_90)).thenReturn(false);
        when(mockDisplayRotation.isAnyPortrait(Surface.ROTATION_180)).thenReturn(true);
        when(mockDisplayRotation.isAnyPortrait(Surface.ROTATION_270)).thenReturn(false);
        when(mockDisplayRotation.isLandscapeOrSeascape(Surface.ROTATION_0)).thenReturn(false);
        when(mockDisplayRotation.isLandscapeOrSeascape(Surface.ROTATION_90)).thenReturn(true);
        when(mockDisplayRotation.isLandscapeOrSeascape(Surface.ROTATION_180)).thenReturn(false);
        when(mockDisplayRotation.isLandscapeOrSeascape(Surface.ROTATION_270)).thenReturn(true);

        return mockDisplayRotation;
    }

    @Test
    public void testIsRotationLockEnforced_landscapeActivity_lockedWhenRotatingToPortrait() {
        // Base case: App is optimal in Landscape.

        // ROTATION_* is the target display orientation counted from the natural display
        // orientation. Outside of test environment, ROTATION_0 means that proposed display
        // rotation is the natural device orientation.
        // DisplayRotationImmersiveAppCompatPolicy assesses whether the proposed target
        // orientation ROTATION_* is optimal for the top fullscreen activity or not.
        // For instance, ROTATION_0 means portrait screen orientation (see
        // createDisplayRotationMock) which isn't optimal for a landscape-only activity so
        // we should show a rotation suggestion button instead of rotating directly.

        // Rotation to portrait
        assertTrue(mPolicy.isRotationLockEnforced(Surface.ROTATION_0));
        // Rotation to landscape
        assertFalse(mPolicy.isRotationLockEnforced(Surface.ROTATION_90));
        // Rotation to portrait
        assertTrue(mPolicy.isRotationLockEnforced(Surface.ROTATION_180));
        // Rotation to landscape
        assertFalse(mPolicy.isRotationLockEnforced(Surface.ROTATION_270));
    }

    @Test
    public void testIsRotationLockEnforced_portraitActivity_lockedWhenRotatingToLandscape() {
        when(mMockActivityRecord.getRequestedConfigurationOrientation()).thenReturn(
                ORIENTATION_PORTRAIT);

        // Rotation to portrait
        assertFalse(mPolicy.isRotationLockEnforced(Surface.ROTATION_0));
        // Rotation to landscape
        assertTrue(mPolicy.isRotationLockEnforced(Surface.ROTATION_90));
        // Rotation to portrait
        assertFalse(mPolicy.isRotationLockEnforced(Surface.ROTATION_180));
        // Rotation to landscape
        assertTrue(mPolicy.isRotationLockEnforced(Surface.ROTATION_270));
    }

    @Test
    public void testIsRotationLockEnforced_responsiveActivity_lockNotEnforced() {
        // Do not fix screen orientation
        when(mMockActivityRecord.getRequestedConfigurationOrientation()).thenReturn(
                ORIENTATION_UNDEFINED);

        assertIsRotationLockEnforcedReturnsFalseForAllRotations();
    }

    @Test
    public void testIsRotationLockEnforced_statusBarVisible_lockNotEnforced() {
        // Some system bars are visible
        when(mMockWindowState.getRequestedVisibleTypes()).thenReturn(Type.statusBars());

        assertIsRotationLockEnforcedReturnsFalseForAllRotations();
    }

    @Test
    public void testIsRotationLockEnforced_navBarVisible_lockNotEnforced() {
        // Some system bars are visible
        when(mMockWindowState.getRequestedVisibleTypes()).thenReturn(Type.navigationBars());

        assertIsRotationLockEnforcedReturnsFalseForAllRotations();
    }

    @Test
    public void testIsRotationLockEnforced_activityIsLetterboxed_lockNotEnforced() {
        // Activity is letterboxed
        when(mMockActivityRecord.areBoundsLetterboxed()).thenReturn(true);

        assertIsRotationLockEnforcedReturnsFalseForAllRotations();
    }

    @Test
    public void testIsRotationLockEnforced_notFullscreen_lockNotEnforced() {
        when(mMockTask.getWindowingMode()).thenReturn(WINDOWING_MODE_MULTI_WINDOW);

        assertIsRotationLockEnforcedReturnsFalseForAllRotations();

        when(mMockTask.getWindowingMode()).thenReturn(WINDOWING_MODE_PINNED);

        assertIsRotationLockEnforcedReturnsFalseForAllRotations();

        when(mMockTask.getWindowingMode()).thenReturn(WINDOWING_MODE_FREEFORM);

        assertIsRotationLockEnforcedReturnsFalseForAllRotations();
    }

    @Test
    public void testIsRotationLockEnforced_ignoreOrientationRequestDisabled_lockNotEnforced() {
        when(mDisplayContent.getIgnoreOrientationRequest()).thenReturn(false);

        assertIsRotationLockEnforcedReturnsFalseForAllRotations();
    }

    @Test
    public void testRotationChoiceEnforcedOnly_nullTopRunningActivity_lockNotEnforced() {
        when(mDisplayContent.topRunningActivity()).thenReturn(null);

        assertIsRotationLockEnforcedReturnsFalseForAllRotations();
    }

    @Test
    public void testRotationChoiceEnforcedOnly_featureFlagDisabled_lockNotEnforced() {
        when(mMockAppCompatConfiguration.isDisplayRotationImmersiveAppCompatPolicyEnabled())
                .thenReturn(false);

        assertIsRotationLockEnforcedReturnsFalseForAllRotations();
    }

    private void assertIsRotationLockEnforcedReturnsFalseForAllRotations() {
        assertFalse(mPolicy.isRotationLockEnforced(Surface.ROTATION_0));
        assertFalse(mPolicy.isRotationLockEnforced(Surface.ROTATION_90));
        assertFalse(mPolicy.isRotationLockEnforced(Surface.ROTATION_180));
        assertFalse(mPolicy.isRotationLockEnforced(Surface.ROTATION_270));
    }
}

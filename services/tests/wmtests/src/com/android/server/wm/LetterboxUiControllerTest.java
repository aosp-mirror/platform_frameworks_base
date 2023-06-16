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

import static android.content.pm.ActivityInfo.FORCE_NON_RESIZE_APP;
import static android.content.pm.ActivityInfo.FORCE_RESIZE_APP;
import static android.content.pm.ActivityInfo.OVERRIDE_ANY_ORIENTATION;
import static android.content.pm.ActivityInfo.OVERRIDE_CAMERA_COMPAT_DISABLE_FORCE_ROTATION;
import static android.content.pm.ActivityInfo.OVERRIDE_CAMERA_COMPAT_DISABLE_REFRESH;
import static android.content.pm.ActivityInfo.OVERRIDE_CAMERA_COMPAT_ENABLE_REFRESH_VIA_PAUSE;
import static android.content.pm.ActivityInfo.OVERRIDE_ENABLE_COMPAT_FAKE_FOCUS;
import static android.content.pm.ActivityInfo.OVERRIDE_ENABLE_COMPAT_IGNORE_ORIENTATION_REQUEST_WHEN_LOOP_DETECTED;
import static android.content.pm.ActivityInfo.OVERRIDE_ENABLE_COMPAT_IGNORE_REQUESTED_ORIENTATION;
import static android.content.pm.ActivityInfo.OVERRIDE_LANDSCAPE_ORIENTATION_TO_REVERSE_LANDSCAPE;
import static android.content.pm.ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO;
import static android.content.pm.ActivityInfo.OVERRIDE_ORIENTATION_ONLY_FOR_CAMERA;
import static android.content.pm.ActivityInfo.OVERRIDE_UNDEFINED_ORIENTATION_TO_NOSENSOR;
import static android.content.pm.ActivityInfo.OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT;
import static android.content.pm.ActivityInfo.OVERRIDE_USE_DISPLAY_LANDSCAPE_NATURAL_ORIENTATION;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_NOSENSOR;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.view.InsetsSource.FLAG_INSETS_ROUNDED_CORNER;
import static android.view.WindowManager.PROPERTY_CAMERA_COMPAT_ALLOW_FORCE_ROTATION;
import static android.view.WindowManager.PROPERTY_CAMERA_COMPAT_ALLOW_REFRESH;
import static android.view.WindowManager.PROPERTY_CAMERA_COMPAT_ENABLE_REFRESH_VIA_PAUSE;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_DISPLAY_ORIENTATION_OVERRIDE;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_IGNORING_ORIENTATION_REQUEST_WHEN_LOOP_DETECTED;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_MIN_ASPECT_RATIO_OVERRIDE;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_ORIENTATION_OVERRIDE;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_RESIZEABLE_ACTIVITY_OVERRIDES;
import static android.view.WindowManager.PROPERTY_COMPAT_ENABLE_FAKE_FOCUS;
import static android.view.WindowManager.PROPERTY_COMPAT_IGNORE_REQUESTED_ORIENTATION;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.eq;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.server.wm.LetterboxUiController.MIN_COUNT_TO_IGNORE_REQUEST_IN_LOOP;
import static com.android.server.wm.LetterboxUiController.SET_ORIENTATION_REQUEST_COUNTER_TIMEOUT_MS;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;

import android.annotation.Nullable;
import android.compat.testing.PlatformCompatChangeRule;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.Property;
import android.content.res.Resources;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.view.InsetsSource;
import android.view.InsetsState;
import android.view.RoundedCorner;
import android.view.RoundedCorners;
import android.view.WindowInsets;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;

import com.android.internal.R;

import libcore.junit.util.compat.CoreCompatChangeRule.DisableCompatChanges;
import libcore.junit.util.compat.CoreCompatChangeRule.EnableCompatChanges;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;

 /**
 * Test class for {@link LetterboxUiControllerTest}.
 *
 * Build/Install/Run:
 *  atest WmTests:LetterboxUiControllerTest
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class LetterboxUiControllerTest extends WindowTestsBase {
    private static final int TASKBAR_COLLAPSED_HEIGHT = 10;
    private static final int TASKBAR_EXPANDED_HEIGHT = 20;
    private static final int SCREEN_WIDTH = 200;
    private static final int SCREEN_HEIGHT = 100;
    private static final Rect TASKBAR_COLLAPSED_BOUNDS = new Rect(0,
            SCREEN_HEIGHT - TASKBAR_COLLAPSED_HEIGHT, SCREEN_WIDTH, SCREEN_HEIGHT);
    private static final Rect TASKBAR_EXPANDED_BOUNDS = new Rect(0,
            SCREEN_HEIGHT - TASKBAR_EXPANDED_HEIGHT, SCREEN_WIDTH, SCREEN_HEIGHT);

    @Rule
    public TestRule compatChangeRule = new PlatformCompatChangeRule();

    private ActivityRecord mActivity;
    private Task mTask;
    private DisplayContent mDisplayContent;
    private LetterboxUiController mController;
    private LetterboxConfiguration mLetterboxConfiguration;
    private final Rect mLetterboxedPortraitTaskBounds = new Rect();

    @Before
    public void setUp() throws Exception {
        mActivity = setUpActivityWithComponent();

        mLetterboxConfiguration = mWm.mLetterboxConfiguration;
        spyOn(mLetterboxConfiguration);

        mController = new LetterboxUiController(mWm, mActivity);
    }

    // shouldIgnoreRequestedOrientation

    @Test
    @EnableCompatChanges({OVERRIDE_ENABLE_COMPAT_IGNORE_REQUESTED_ORIENTATION})
    public void testShouldIgnoreRequestedOrientation_activityRelaunching_returnsTrue() {
        prepareActivityThatShouldIgnoreRequestedOrientationDuringRelaunch();

        assertTrue(mController.shouldIgnoreRequestedOrientation(SCREEN_ORIENTATION_UNSPECIFIED));
    }

    @Test
    @EnableCompatChanges({OVERRIDE_ENABLE_COMPAT_IGNORE_REQUESTED_ORIENTATION})
    public void testShouldIgnoreRequestedOrientation_cameraCompatTreatment_returnsTrue() {
        doReturn(true).when(mLetterboxConfiguration).isCameraCompatTreatmentEnabled();
        doReturn(true).when(mLetterboxConfiguration)
                .isCameraCompatTreatmentEnabledAtBuildTime();

        // Recreate DisplayContent with DisplayRotationCompatPolicy
        mActivity = setUpActivityWithComponent();
        mController = new LetterboxUiController(mWm, mActivity);
        prepareActivityThatShouldIgnoreRequestedOrientationDuringRelaunch();
        mController.setRelaunchingAfterRequestedOrientationChanged(false);

        spyOn(mDisplayContent.mDisplayRotationCompatPolicy);
        doReturn(true).when(mDisplayContent.mDisplayRotationCompatPolicy)
                .isTreatmentEnabledForActivity(eq(mActivity));

        assertTrue(mController.shouldIgnoreRequestedOrientation(SCREEN_ORIENTATION_UNSPECIFIED));
    }

    @Test
    public void testShouldIgnoreRequestedOrientation_overrideDisabled_returnsFalse() {
        prepareActivityThatShouldIgnoreRequestedOrientationDuringRelaunch();

        assertFalse(mController.shouldIgnoreRequestedOrientation(SCREEN_ORIENTATION_UNSPECIFIED));
    }

    @Test
    public void testShouldIgnoreRequestedOrientation_propertyIsTrue_returnsTrue()
            throws Exception {
        doReturn(true).when(mLetterboxConfiguration)
                .isPolicyForIgnoringRequestedOrientationEnabled();
        mockThatProperty(PROPERTY_COMPAT_IGNORE_REQUESTED_ORIENTATION, /* value */ true);
        mController = new LetterboxUiController(mWm, mActivity);
        prepareActivityThatShouldIgnoreRequestedOrientationDuringRelaunch();

        assertTrue(mController.shouldIgnoreRequestedOrientation(SCREEN_ORIENTATION_UNSPECIFIED));
    }

    @Test
    @EnableCompatChanges({OVERRIDE_ENABLE_COMPAT_IGNORE_REQUESTED_ORIENTATION})
    public void testShouldIgnoreRequestedOrientation_propertyIsFalseAndOverride_returnsFalse()
            throws Exception {
        doReturn(true).when(mLetterboxConfiguration)
                .isPolicyForIgnoringRequestedOrientationEnabled();
        mockThatProperty(PROPERTY_COMPAT_IGNORE_REQUESTED_ORIENTATION, /* value */ false);

        mController = new LetterboxUiController(mWm, mActivity);
        prepareActivityThatShouldIgnoreRequestedOrientationDuringRelaunch();

        assertFalse(mController.shouldIgnoreRequestedOrientation(SCREEN_ORIENTATION_UNSPECIFIED));
    }

    @Test
    public void testShouldIgnoreOrientationRequestLoop_overrideDisabled_returnsFalse() {
        doReturn(true).when(mLetterboxConfiguration)
                .isPolicyForIgnoringRequestedOrientationEnabled();
        doReturn(false).when(mActivity).isLetterboxedForFixedOrientationAndAspectRatio();
        // Request 3 times to simulate orientation request loop
        for (int i = 0; i <= MIN_COUNT_TO_IGNORE_REQUEST_IN_LOOP; i++) {
            assertShouldIgnoreOrientationRequestLoop(/* shouldIgnore */ false,
                    /* expectedCount */ 0);
        }
    }

    @Test
    @EnableCompatChanges({OVERRIDE_ENABLE_COMPAT_IGNORE_ORIENTATION_REQUEST_WHEN_LOOP_DETECTED})
    public void testShouldIgnoreOrientationRequestLoop_propertyIsFalseAndOverride_returnsFalse()
            throws Exception {
        doReturn(true).when(mLetterboxConfiguration)
                .isPolicyForIgnoringRequestedOrientationEnabled();
        mockThatProperty(PROPERTY_COMPAT_ALLOW_IGNORING_ORIENTATION_REQUEST_WHEN_LOOP_DETECTED,
                /* value */ false);
        doReturn(false).when(mActivity).isLetterboxedForFixedOrientationAndAspectRatio();

        mController = new LetterboxUiController(mWm, mActivity);

        // Request 3 times to simulate orientation request loop
        for (int i = 0; i <= MIN_COUNT_TO_IGNORE_REQUEST_IN_LOOP; i++) {
            assertShouldIgnoreOrientationRequestLoop(/* shouldIgnore */ false,
                    /* expectedCount */ 0);
        }
    }

    @Test
    @EnableCompatChanges({OVERRIDE_ENABLE_COMPAT_IGNORE_ORIENTATION_REQUEST_WHEN_LOOP_DETECTED})
    public void testShouldIgnoreOrientationRequestLoop_isLetterboxed_returnsFalse() {
        doReturn(true).when(mLetterboxConfiguration)
                .isPolicyForIgnoringRequestedOrientationEnabled();
        doReturn(true).when(mActivity).isLetterboxedForFixedOrientationAndAspectRatio();

        // Request 3 times to simulate orientation request loop
        for (int i = 0; i <= MIN_COUNT_TO_IGNORE_REQUEST_IN_LOOP; i++) {
            assertShouldIgnoreOrientationRequestLoop(/* shouldIgnore */ false,
                    /* expectedCount */ i);
        }
    }

    @Test
    @EnableCompatChanges({OVERRIDE_ENABLE_COMPAT_IGNORE_ORIENTATION_REQUEST_WHEN_LOOP_DETECTED})
    public void testShouldIgnoreOrientationRequestLoop_noLoop_returnsFalse() {
        doReturn(true).when(mLetterboxConfiguration)
                .isPolicyForIgnoringRequestedOrientationEnabled();
        doReturn(false).when(mActivity).isLetterboxedForFixedOrientationAndAspectRatio();

        // No orientation request loop
        assertShouldIgnoreOrientationRequestLoop(/* shouldIgnore */ false,
                /* expectedCount */ 0);
    }

    @Test
    @EnableCompatChanges({OVERRIDE_ENABLE_COMPAT_IGNORE_ORIENTATION_REQUEST_WHEN_LOOP_DETECTED})
    public void testShouldIgnoreOrientationRequestLoop_timeout_returnsFalse()
            throws InterruptedException {
        doReturn(true).when(mLetterboxConfiguration)
                .isPolicyForIgnoringRequestedOrientationEnabled();
        doReturn(false).when(mActivity).isLetterboxedForFixedOrientationAndAspectRatio();

        for (int i = MIN_COUNT_TO_IGNORE_REQUEST_IN_LOOP; i > 0; i--) {
            assertShouldIgnoreOrientationRequestLoop(/* shouldIgnore */ false,
                    /* expectedCount */ 0);
            Thread.sleep(SET_ORIENTATION_REQUEST_COUNTER_TIMEOUT_MS);
        }
    }

    @Test
    @EnableCompatChanges({OVERRIDE_ENABLE_COMPAT_IGNORE_ORIENTATION_REQUEST_WHEN_LOOP_DETECTED})
    public void testShouldIgnoreOrientationRequestLoop_returnsTrue() {
        doReturn(true).when(mLetterboxConfiguration)
                .isPolicyForIgnoringRequestedOrientationEnabled();
        doReturn(false).when(mActivity).isLetterboxedForFixedOrientationAndAspectRatio();

        for (int i = 0; i < MIN_COUNT_TO_IGNORE_REQUEST_IN_LOOP; i++) {
            assertShouldIgnoreOrientationRequestLoop(/* shouldIgnore */ false,
                    /* expectedCount */ i);
        }
        assertShouldIgnoreOrientationRequestLoop(/* shouldIgnore */ true,
                /* expectedCount */ MIN_COUNT_TO_IGNORE_REQUEST_IN_LOOP);
    }

    private void assertShouldIgnoreOrientationRequestLoop(boolean shouldIgnore, int expectedCount) {
        if (shouldIgnore) {
            assertTrue(mController.shouldIgnoreOrientationRequestLoop());
        } else {
            assertFalse(mController.shouldIgnoreOrientationRequestLoop());
        }
        assertEquals(expectedCount, mController.getSetOrientationRequestCounter());
    }

    @Test
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_DISABLE_REFRESH})
    public void testShouldIgnoreRequestedOrientation_flagIsDisabled_returnsFalse() {
        prepareActivityThatShouldIgnoreRequestedOrientationDuringRelaunch();
        doReturn(false).when(mLetterboxConfiguration)
                .isPolicyForIgnoringRequestedOrientationEnabled();

        assertFalse(mController.shouldIgnoreRequestedOrientation(SCREEN_ORIENTATION_UNSPECIFIED));
    }

    // shouldRefreshActivityForCameraCompat

    @Test
    public void testShouldRefreshActivityForCameraCompat_flagIsDisabled_returnsFalse() {
        doReturn(false).when(mLetterboxConfiguration)
                .isCameraCompatTreatmentEnabled();

        assertFalse(mController.shouldRefreshActivityForCameraCompat());
    }

    @Test
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_DISABLE_REFRESH})
    public void testShouldRefreshActivityForCameraCompat_overrideEnabled_returnsFalse() {
        doReturn(true).when(mLetterboxConfiguration)
                .isCameraCompatTreatmentEnabled();

        assertFalse(mController.shouldRefreshActivityForCameraCompat());
    }

    @Test
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_DISABLE_REFRESH})
    public void testShouldRefreshActivityForCameraCompat_propertyIsTrueAndOverride_returnsFalse()
            throws Exception {
        doReturn(true).when(mLetterboxConfiguration)
                .isCameraCompatTreatmentEnabled();
        mockThatProperty(PROPERTY_CAMERA_COMPAT_ALLOW_REFRESH, /* value */ true);

        mController = new LetterboxUiController(mWm, mActivity);

        assertFalse(mController.shouldRefreshActivityForCameraCompat());
    }

    @Test
    public void testShouldRefreshActivityForCameraCompat_propertyIsFalse_returnsFalse()
            throws Exception {
        doReturn(true).when(mLetterboxConfiguration)
                .isCameraCompatTreatmentEnabled();
        mockThatProperty(PROPERTY_CAMERA_COMPAT_ALLOW_REFRESH, /* value */ false);

        mController = new LetterboxUiController(mWm, mActivity);

        assertFalse(mController.shouldRefreshActivityForCameraCompat());
    }

    @Test
    public void testShouldRefreshActivityForCameraCompat_propertyIsTrue_returnsTrue()
            throws Exception {
        doReturn(true).when(mLetterboxConfiguration)
                .isCameraCompatTreatmentEnabled();
        mockThatProperty(PROPERTY_CAMERA_COMPAT_ALLOW_REFRESH, /* value */ true);

        mController = new LetterboxUiController(mWm, mActivity);

        assertTrue(mController.shouldRefreshActivityForCameraCompat());
    }

    // shouldRefreshActivityViaPauseForCameraCompat

    @Test
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_ENABLE_REFRESH_VIA_PAUSE})
    public void testShouldRefreshActivityViaPauseForCameraCompat_flagIsDisabled_returnsFalse() {
        doReturn(false).when(mLetterboxConfiguration)
                .isCameraCompatTreatmentEnabled();

        assertFalse(mController.shouldRefreshActivityViaPauseForCameraCompat());
    }

    @Test
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_ENABLE_REFRESH_VIA_PAUSE})
    public void testShouldRefreshActivityViaPauseForCameraCompat_overrideEnabled_returnsTrue() {
        doReturn(true).when(mLetterboxConfiguration)
                .isCameraCompatTreatmentEnabled();

        assertTrue(mController.shouldRefreshActivityViaPauseForCameraCompat());
    }

    @Test
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_ENABLE_REFRESH_VIA_PAUSE})
    public void testShouldRefreshActivityViaPauseForCameraCompat_propertyIsFalseAndOverride_returnFalse()
            throws Exception {
        doReturn(true).when(mLetterboxConfiguration)
                .isCameraCompatTreatmentEnabled();
        mockThatProperty(PROPERTY_CAMERA_COMPAT_ENABLE_REFRESH_VIA_PAUSE, /* value */ false);

        mController = new LetterboxUiController(mWm, mActivity);

        assertFalse(mController.shouldRefreshActivityViaPauseForCameraCompat());
    }

    @Test
    public void testShouldRefreshActivityViaPauseForCameraCompat_propertyIsTrue_returnsTrue()
            throws Exception {
        doReturn(true).when(mLetterboxConfiguration)
                .isCameraCompatTreatmentEnabled();
        mockThatProperty(PROPERTY_CAMERA_COMPAT_ENABLE_REFRESH_VIA_PAUSE, /* value */ true);

        mController = new LetterboxUiController(mWm, mActivity);

        assertTrue(mController.shouldRefreshActivityViaPauseForCameraCompat());
    }

    // shouldForceRotateForCameraCompat

    @Test
    public void testShouldForceRotateForCameraCompat_flagIsDisabled_returnsFalse() {
        doReturn(false).when(mLetterboxConfiguration)
                .isCameraCompatTreatmentEnabled();

        assertFalse(mController.shouldForceRotateForCameraCompat());
    }

    @Test
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_DISABLE_FORCE_ROTATION})
    public void testShouldForceRotateForCameraCompat_overrideEnabled_returnsFalse() {
        doReturn(true).when(mLetterboxConfiguration)
                .isCameraCompatTreatmentEnabled();

        assertFalse(mController.shouldForceRotateForCameraCompat());
    }

    @Test
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_DISABLE_FORCE_ROTATION})
    public void testShouldForceRotateForCameraCompat_propertyIsTrueAndOverride_returnsFalse()
            throws Exception {
        doReturn(true).when(mLetterboxConfiguration)
                .isCameraCompatTreatmentEnabled();
        mockThatProperty(PROPERTY_CAMERA_COMPAT_ALLOW_FORCE_ROTATION, /* value */ true);

        mController = new LetterboxUiController(mWm, mActivity);

        assertFalse(mController.shouldForceRotateForCameraCompat());
    }

    @Test
    public void testShouldForceRotateForCameraCompat_propertyIsFalse_returnsFalse()
            throws Exception {
        doReturn(true).when(mLetterboxConfiguration)
                .isCameraCompatTreatmentEnabled();
        mockThatProperty(PROPERTY_CAMERA_COMPAT_ALLOW_FORCE_ROTATION, /* value */ false);

        mController = new LetterboxUiController(mWm, mActivity);

        assertFalse(mController.shouldForceRotateForCameraCompat());
    }

    @Test
    public void testShouldForceRotateForCameraCompat_propertyIsTrue_returnsTrue()
            throws Exception {
        doReturn(true).when(mLetterboxConfiguration)
                .isCameraCompatTreatmentEnabled();
        mockThatProperty(PROPERTY_CAMERA_COMPAT_ALLOW_FORCE_ROTATION, /* value */ true);

        mController = new LetterboxUiController(mWm, mActivity);

        assertTrue(mController.shouldForceRotateForCameraCompat());
    }

    @Test
    public void testGetCropBoundsIfNeeded_handleCropForTransparentActivityBasedOnOpaqueBounds() {
        final InsetsSource taskbar = new InsetsSource(/*id=*/ 0,
                 WindowInsets.Type.navigationBars());
        taskbar.setFlags(FLAG_INSETS_ROUNDED_CORNER, FLAG_INSETS_ROUNDED_CORNER);
        final WindowState mainWindow = mockForGetCropBoundsAndRoundedCorners(taskbar);
        final Rect opaqueBounds = new Rect(0, 0, 500, 300);
        doReturn(opaqueBounds).when(mActivity).getBounds();
        // Activity is translucent
        spyOn(mActivity.mLetterboxUiController);
        doReturn(true).when(mActivity.mLetterboxUiController).hasInheritedLetterboxBehavior();

        // Makes requested sizes different
        mainWindow.mRequestedWidth = opaqueBounds.width() - 1;
        mainWindow.mRequestedHeight = opaqueBounds.height() - 1;
        assertNull(mActivity.mLetterboxUiController.getCropBoundsIfNeeded(mainWindow));

        // Makes requested sizes equals
        mainWindow.mRequestedWidth = opaqueBounds.width();
        mainWindow.mRequestedHeight = opaqueBounds.height();
        assertNotNull(mActivity.mLetterboxUiController.getCropBoundsIfNeeded(mainWindow));
    }

    @Test
    public void testGetCropBoundsIfNeeded_noCrop() {
        final InsetsSource taskbar = new InsetsSource(/*id=*/ 0,
                WindowInsets.Type.navigationBars());
        final WindowState mainWindow = mockForGetCropBoundsAndRoundedCorners(taskbar);

        // Do not apply crop if taskbar is collapsed
        taskbar.setFrame(TASKBAR_COLLAPSED_BOUNDS);
        assertNull(mController.getExpandedTaskbarOrNull(mainWindow));

        mLetterboxedPortraitTaskBounds.set(SCREEN_WIDTH / 4, SCREEN_HEIGHT / 4,
                SCREEN_WIDTH - SCREEN_WIDTH / 4, SCREEN_HEIGHT - SCREEN_HEIGHT / 4);

        final Rect noCrop = mController.getCropBoundsIfNeeded(mainWindow);
        assertNotEquals(null, noCrop);
        assertEquals(0, noCrop.left);
        assertEquals(0, noCrop.top);
        assertEquals(mLetterboxedPortraitTaskBounds.width(), noCrop.right);
        assertEquals(mLetterboxedPortraitTaskBounds.height(), noCrop.bottom);
    }

    @Test
    public void testGetCropBoundsIfNeeded_appliesCrop() {
        final InsetsSource taskbar = new InsetsSource(/*id=*/ 0,
                WindowInsets.Type.navigationBars());
        taskbar.setFlags(FLAG_INSETS_ROUNDED_CORNER, FLAG_INSETS_ROUNDED_CORNER);
        final WindowState mainWindow = mockForGetCropBoundsAndRoundedCorners(taskbar);

        // Apply crop if taskbar is expanded
        taskbar.setFrame(TASKBAR_EXPANDED_BOUNDS);
        assertNotNull(mController.getExpandedTaskbarOrNull(mainWindow));

        mLetterboxedPortraitTaskBounds.set(SCREEN_WIDTH / 4, 0, SCREEN_WIDTH - SCREEN_WIDTH / 4,
                SCREEN_HEIGHT);

        final Rect crop = mController.getCropBoundsIfNeeded(mainWindow);
        assertNotEquals(null, crop);
        assertEquals(0, crop.left);
        assertEquals(0, crop.top);
        assertEquals(mLetterboxedPortraitTaskBounds.width(), crop.right);
        assertEquals(mLetterboxedPortraitTaskBounds.height() - TASKBAR_EXPANDED_HEIGHT,
                crop.bottom);
    }

    @Test
    public void testGetCropBoundsIfNeeded_appliesCropWithSizeCompatScaling() {
        final InsetsSource taskbar = new InsetsSource(/*id=*/ 0,
                WindowInsets.Type.navigationBars());
        taskbar.setFlags(FLAG_INSETS_ROUNDED_CORNER, FLAG_INSETS_ROUNDED_CORNER);
        final WindowState mainWindow = mockForGetCropBoundsAndRoundedCorners(taskbar);
        final float scaling = 2.0f;

        // Apply crop if taskbar is expanded
        taskbar.setFrame(TASKBAR_EXPANDED_BOUNDS);
        assertNotNull(mController.getExpandedTaskbarOrNull(mainWindow));
        // With SizeCompat scaling
        doReturn(true).when(mActivity).inSizeCompatMode();
        mainWindow.mInvGlobalScale = scaling;

        mLetterboxedPortraitTaskBounds.set(SCREEN_WIDTH / 4, 0, SCREEN_WIDTH - SCREEN_WIDTH / 4,
                SCREEN_HEIGHT);

        final int appWidth = mLetterboxedPortraitTaskBounds.width();
        final int appHeight = mLetterboxedPortraitTaskBounds.height();

        final Rect crop = mController.getCropBoundsIfNeeded(mainWindow);
        assertNotEquals(null, crop);
        assertEquals(0, crop.left);
        assertEquals(0, crop.top);
        assertEquals((int) (appWidth * scaling), crop.right);
        assertEquals((int) ((appHeight - TASKBAR_EXPANDED_HEIGHT) * scaling), crop.bottom);
    }

    @Test
    public void testGetRoundedCornersRadius_withRoundedCornersFromInsets() {
        final float invGlobalScale = 0.5f;
        final int expectedRadius = 7;
        final int configurationRadius = 15;

        final WindowState mainWindow = mockForGetCropBoundsAndRoundedCorners(/*taskbar=*/ null);
        mainWindow.mInvGlobalScale = invGlobalScale;
        final InsetsState insets = mainWindow.getInsetsState();

        RoundedCorners roundedCorners = new RoundedCorners(
                /*topLeft=*/ null,
                /*topRight=*/ null,
                /*bottomRight=*/ new RoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT,
                    configurationRadius, /*centerX=*/ 1, /*centerY=*/ 1),
                /*bottomLeft=*/ new RoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT,
                    configurationRadius * 2 /*2 is to test selection of the min radius*/,
                    /*centerX=*/ 1, /*centerY=*/ 1)
        );
        insets.setRoundedCorners(roundedCorners);
        mLetterboxConfiguration.setLetterboxActivityCornersRadius(-1);

        assertEquals(expectedRadius, mController.getRoundedCornersRadius(mainWindow));
    }

    @Test
    public void testGetRoundedCornersRadius_withLetterboxActivityCornersRadius() {
        final float invGlobalScale = 0.5f;
        final int expectedRadius = 7;
        final int configurationRadius = 15;

        final WindowState mainWindow = mockForGetCropBoundsAndRoundedCorners(/*taskbar=*/ null);
        mainWindow.mInvGlobalScale = invGlobalScale;
        mLetterboxConfiguration.setLetterboxActivityCornersRadius(configurationRadius);

        doReturn(true).when(mActivity).isInLetterboxAnimation();
        assertEquals(expectedRadius, mController.getRoundedCornersRadius(mainWindow));

        doReturn(false).when(mActivity).isInLetterboxAnimation();
        assertEquals(expectedRadius, mController.getRoundedCornersRadius(mainWindow));

        doReturn(false).when(mainWindow).isOnScreen();
        assertEquals(0, mController.getRoundedCornersRadius(mainWindow));

        doReturn(true).when(mActivity).isInLetterboxAnimation();
        assertEquals(expectedRadius, mController.getRoundedCornersRadius(mainWindow));
    }

    @Test
    public void testGetRoundedCornersRadius_noScalingApplied() {
        final int configurationRadius = 15;

        final WindowState mainWindow = mockForGetCropBoundsAndRoundedCorners(/*taskbar=*/ null);
        mLetterboxConfiguration.setLetterboxActivityCornersRadius(configurationRadius);

        mainWindow.mInvGlobalScale = -1f;
        assertEquals(configurationRadius, mController.getRoundedCornersRadius(mainWindow));

        mainWindow.mInvGlobalScale = 0f;
        assertEquals(configurationRadius, mController.getRoundedCornersRadius(mainWindow));

        mainWindow.mInvGlobalScale = 1f;
        assertEquals(configurationRadius, mController.getRoundedCornersRadius(mainWindow));
    }

    private WindowState mockForGetCropBoundsAndRoundedCorners(@Nullable InsetsSource taskbar) {
        final WindowState mainWindow = mock(WindowState.class);
        final InsetsState insets = new InsetsState();
        final Resources resources = mWm.mContext.getResources();
        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams();

        mainWindow.mInvGlobalScale = 1f;
        spyOn(resources);
        spyOn(mActivity);

        if (taskbar != null) {
            taskbar.setVisible(true);
            insets.addSource(taskbar);
        }
        doReturn(mLetterboxedPortraitTaskBounds).when(mActivity).getBounds();
        doReturn(false).when(mActivity).isInLetterboxAnimation();
        doReturn(true).when(mActivity).isVisible();
        doReturn(true).when(mActivity).isLetterboxedForFixedOrientationAndAspectRatio();
        doReturn(insets).when(mainWindow).getInsetsState();
        doReturn(attrs).when(mainWindow).getAttrs();
        doReturn(true).when(mainWindow).isDrawn();
        doReturn(true).when(mainWindow).isOnScreen();
        doReturn(false).when(mainWindow).isLetterboxedForDisplayCutout();
        doReturn(true).when(mainWindow).areAppWindowBoundsLetterboxed();
        doReturn(true).when(mLetterboxConfiguration).isLetterboxActivityCornersRounded();
        doReturn(TASKBAR_EXPANDED_HEIGHT).when(resources).getDimensionPixelSize(
                R.dimen.taskbar_frame_height);

        // Need to reinitialise due to the change in resources getDimensionPixelSize output.
        mController = new LetterboxUiController(mWm, mActivity);

        return mainWindow;
    }

    // overrideOrientationIfNeeded

    @Test
    public void testOverrideOrientationIfNeeded_mapInvokedOnRequest() throws Exception {
        mController = new LetterboxUiController(mWm, mActivity);
        spyOn(mWm);

        mController.overrideOrientationIfNeeded(SCREEN_ORIENTATION_PORTRAIT);

        verify(mWm).mapOrientationRequest(SCREEN_ORIENTATION_PORTRAIT);
    }

    @Test
    @EnableCompatChanges({OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT})
    public void testOverrideOrientationIfNeeded_portraitOverrideEnabled_returnsPortrait()
            throws Exception {
        assertEquals(mController.overrideOrientationIfNeeded(
                /* candidate */ SCREEN_ORIENTATION_UNSPECIFIED), SCREEN_ORIENTATION_PORTRAIT);
    }

    @Test
    @EnableCompatChanges({OVERRIDE_UNDEFINED_ORIENTATION_TO_NOSENSOR})
    public void testOverrideOrientationIfNeeded_portraitOverrideEnabled_returnsNosensor() {
        assertEquals(mController.overrideOrientationIfNeeded(
                /* candidate */ SCREEN_ORIENTATION_UNSPECIFIED), SCREEN_ORIENTATION_NOSENSOR);
    }

    @Test
    @EnableCompatChanges({OVERRIDE_UNDEFINED_ORIENTATION_TO_NOSENSOR})
    public void testOverrideOrientationIfNeeded_nosensorOverride_orientationFixed_returnsUnchanged() {
        assertEquals(mController.overrideOrientationIfNeeded(
                /* candidate */ SCREEN_ORIENTATION_PORTRAIT), SCREEN_ORIENTATION_PORTRAIT);
    }

    @Test
    @EnableCompatChanges({OVERRIDE_LANDSCAPE_ORIENTATION_TO_REVERSE_LANDSCAPE})
    public void testOverrideOrientationIfNeeded_reverseLandscapeOverride_orientationPortraitOrUndefined_returnsUnchanged() {
        assertEquals(mController.overrideOrientationIfNeeded(
                /* candidate */ SCREEN_ORIENTATION_PORTRAIT), SCREEN_ORIENTATION_PORTRAIT);
        assertEquals(mController.overrideOrientationIfNeeded(
                /* candidate */ SCREEN_ORIENTATION_UNSPECIFIED), SCREEN_ORIENTATION_UNSPECIFIED);
    }

    @Test
    @EnableCompatChanges({OVERRIDE_LANDSCAPE_ORIENTATION_TO_REVERSE_LANDSCAPE})
    public void testOverrideOrientationIfNeeded_reverseLandscapeOverride_orientationLandscape_returnsReverseLandscape() {
        assertEquals(mController.overrideOrientationIfNeeded(
                /* candidate */ SCREEN_ORIENTATION_LANDSCAPE),
                SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
    }

    @Test
    @EnableCompatChanges({OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT})
    public void testOverrideOrientationIfNeeded_portraitOverride_orientationFixed_returnsUnchanged() {
        assertEquals(mController.overrideOrientationIfNeeded(
                /* candidate */ SCREEN_ORIENTATION_NOSENSOR), SCREEN_ORIENTATION_NOSENSOR);
    }

    @Test
    @EnableCompatChanges({OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT, OVERRIDE_ANY_ORIENTATION})
    public void testOverrideOrientationIfNeeded_portraitAndIgnoreFixedOverrides_returnsPortrait() {
        assertEquals(mController.overrideOrientationIfNeeded(
                /* candidate */ SCREEN_ORIENTATION_NOSENSOR), SCREEN_ORIENTATION_PORTRAIT);
    }

    @Test
    @EnableCompatChanges({OVERRIDE_UNDEFINED_ORIENTATION_TO_NOSENSOR, OVERRIDE_ANY_ORIENTATION})
    public void testOverrideOrientationIfNeeded_noSensorAndIgnoreFixedOverrides_returnsNosensor() {
        assertEquals(mController.overrideOrientationIfNeeded(
                /* candidate */ SCREEN_ORIENTATION_PORTRAIT), SCREEN_ORIENTATION_NOSENSOR);
    }

    @Test
    @EnableCompatChanges({OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT})
    public void testOverrideOrientationIfNeeded_propertyIsFalse_returnsUnchanged()
            throws Exception {
        mockThatProperty(PROPERTY_COMPAT_ALLOW_ORIENTATION_OVERRIDE, /* value */ false);

        mController = new LetterboxUiController(mWm, mActivity);

        assertEquals(mController.overrideOrientationIfNeeded(
                /* candidate */ SCREEN_ORIENTATION_UNSPECIFIED), SCREEN_ORIENTATION_UNSPECIFIED);
    }

    @Test
    @EnableCompatChanges({OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT,
            OVERRIDE_ORIENTATION_ONLY_FOR_CAMERA})
    public void testOverrideOrientationIfNeeded_whenCameraNotActive_returnsUnchanged() {
        doReturn(true).when(mLetterboxConfiguration).isCameraCompatTreatmentEnabled();
        doReturn(true).when(mLetterboxConfiguration)
                .isCameraCompatTreatmentEnabledAtBuildTime();

        // Recreate DisplayContent with DisplayRotationCompatPolicy
        mActivity = setUpActivityWithComponent();
        mController = new LetterboxUiController(mWm, mActivity);

        spyOn(mDisplayContent.mDisplayRotationCompatPolicy);
        doReturn(false).when(mDisplayContent.mDisplayRotationCompatPolicy)
                .isActivityEligibleForOrientationOverride(eq(mActivity));

        assertEquals(mController.overrideOrientationIfNeeded(
                /* candidate */ SCREEN_ORIENTATION_UNSPECIFIED), SCREEN_ORIENTATION_UNSPECIFIED);
    }

    @Test
    @EnableCompatChanges({OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT,
            OVERRIDE_ORIENTATION_ONLY_FOR_CAMERA})
    public void testOverrideOrientationIfNeeded_whenCameraActive_returnsPortrait() {
        doReturn(true).when(mLetterboxConfiguration).isCameraCompatTreatmentEnabled();
        doReturn(true).when(mLetterboxConfiguration)
                .isCameraCompatTreatmentEnabledAtBuildTime();

        // Recreate DisplayContent with DisplayRotationCompatPolicy
        mActivity = setUpActivityWithComponent();
        mController = new LetterboxUiController(mWm, mActivity);

        spyOn(mDisplayContent.mDisplayRotationCompatPolicy);
        doReturn(true).when(mDisplayContent.mDisplayRotationCompatPolicy)
                .isActivityEligibleForOrientationOverride(eq(mActivity));

        assertEquals(mController.overrideOrientationIfNeeded(
                /* candidate */ SCREEN_ORIENTATION_UNSPECIFIED), SCREEN_ORIENTATION_PORTRAIT);
    }

    // shouldUseDisplayLandscapeNaturalOrientation

    @Test
    @EnableCompatChanges({OVERRIDE_USE_DISPLAY_LANDSCAPE_NATURAL_ORIENTATION})
    public void testShouldUseDisplayLandscapeNaturalOrientation_override_returnsTrue() {
        prepareActivityThatShouldUseDisplayLandscapeNaturalOrientation();
        assertTrue(mController.shouldUseDisplayLandscapeNaturalOrientation());
    }

    @Test
    @EnableCompatChanges({OVERRIDE_USE_DISPLAY_LANDSCAPE_NATURAL_ORIENTATION})
    public void testShouldUseDisplayLandscapeNaturalOrientation_overrideAndFalseProperty_returnsFalse()
            throws Exception {
        mockThatProperty(PROPERTY_COMPAT_ALLOW_DISPLAY_ORIENTATION_OVERRIDE, /* value */ false);

        mController = new LetterboxUiController(mWm, mActivity);

        prepareActivityThatShouldUseDisplayLandscapeNaturalOrientation();
        assertFalse(mController.shouldUseDisplayLandscapeNaturalOrientation());
    }

    @Test
    @EnableCompatChanges({OVERRIDE_USE_DISPLAY_LANDSCAPE_NATURAL_ORIENTATION})
    public void testShouldUseDisplayLandscapeNaturalOrientation_portraitNaturalOrientation_returnsFalse() {
        prepareActivityThatShouldUseDisplayLandscapeNaturalOrientation();
        doReturn(ORIENTATION_PORTRAIT).when(mDisplayContent).getNaturalOrientation();

        assertFalse(mController.shouldUseDisplayLandscapeNaturalOrientation());
    }

    @Test
    @EnableCompatChanges({OVERRIDE_USE_DISPLAY_LANDSCAPE_NATURAL_ORIENTATION})
    public void testShouldUseDisplayLandscapeNaturalOrientation_disabledIgnoreOrientationRequest_returnsFalse() {
        prepareActivityThatShouldUseDisplayLandscapeNaturalOrientation();
        mDisplayContent.setIgnoreOrientationRequest(false);

        assertFalse(mController.shouldUseDisplayLandscapeNaturalOrientation());
    }

    @Test
    @EnableCompatChanges({OVERRIDE_USE_DISPLAY_LANDSCAPE_NATURAL_ORIENTATION})
    public void testShouldUseDisplayLandscapeNaturalOrientation_inMultiWindowMode_returnsFalse() {
        prepareActivityThatShouldUseDisplayLandscapeNaturalOrientation();

        spyOn(mTask);
        doReturn(true).when(mTask).inMultiWindowMode();

        assertFalse(mController.shouldUseDisplayLandscapeNaturalOrientation());
    }

    @Test
    @EnableCompatChanges({OVERRIDE_ENABLE_COMPAT_FAKE_FOCUS})
    public void testShouldSendFakeFocus_overrideEnabled_returnsTrue() {
        doReturn(true).when(mLetterboxConfiguration).isCompatFakeFocusEnabled();

        mController = new LetterboxUiController(mWm, mActivity);

        assertTrue(mController.shouldSendFakeFocus());
    }

    @Test
    @DisableCompatChanges({OVERRIDE_ENABLE_COMPAT_FAKE_FOCUS})
    public void testShouldSendFakeFocus_overrideDisabled_returnsFalse() {
        doReturn(true).when(mLetterboxConfiguration).isCompatFakeFocusEnabled();

        mController = new LetterboxUiController(mWm, mActivity);

        assertFalse(mController.shouldSendFakeFocus());
    }

    @Test
    @EnableCompatChanges({OVERRIDE_ENABLE_COMPAT_FAKE_FOCUS})
    public void testIsCompatFakeFocusEnabled_propertyDisabledAndOverrideEnabled_fakeFocusDisabled()
            throws Exception {
        doReturn(true).when(mLetterboxConfiguration).isCompatFakeFocusEnabled();
        mockThatProperty(PROPERTY_COMPAT_ENABLE_FAKE_FOCUS, /* value */ false);

        mController = new LetterboxUiController(mWm, mActivity);

        assertFalse(mController.shouldSendFakeFocus());
    }

    @Test
    @DisableCompatChanges({OVERRIDE_ENABLE_COMPAT_FAKE_FOCUS})
    public void testIsCompatFakeFocusEnabled_propertyEnabled_noOverride_fakeFocusEnabled()
            throws Exception {
        doReturn(true).when(mLetterboxConfiguration).isCompatFakeFocusEnabled();
        mockThatProperty(PROPERTY_COMPAT_ENABLE_FAKE_FOCUS, /* value */ true);

        mController = new LetterboxUiController(mWm, mActivity);

        assertTrue(mController.shouldSendFakeFocus());
    }

    @Test
    public void testIsCompatFakeFocusEnabled_propertyDisabled_fakeFocusDisabled()
            throws Exception {
        doReturn(true).when(mLetterboxConfiguration).isCompatFakeFocusEnabled();
        mockThatProperty(PROPERTY_COMPAT_ENABLE_FAKE_FOCUS, /* value */ false);

        mController = new LetterboxUiController(mWm, mActivity);

        assertFalse(mController.shouldSendFakeFocus());
    }

    @Test
    public void testIsCompatFakeFocusEnabled_propertyEnabled_fakeFocusEnabled()
            throws Exception {
        doReturn(true).when(mLetterboxConfiguration).isCompatFakeFocusEnabled();
        mockThatProperty(PROPERTY_COMPAT_ENABLE_FAKE_FOCUS, /* value */ true);

        mController = new LetterboxUiController(mWm, mActivity);

        assertTrue(mController.shouldSendFakeFocus());
    }

    @Test
    @EnableCompatChanges({OVERRIDE_MIN_ASPECT_RATIO})
    public void testshouldOverrideMinAspectRatio_overrideEnabled_returnsTrue() {
        mController = new LetterboxUiController(mWm, mActivity);

        assertTrue(mController.shouldOverrideMinAspectRatio());
    }

    @Test
    @EnableCompatChanges({OVERRIDE_MIN_ASPECT_RATIO})
    public void testshouldOverrideMinAspectRatio_propertyTrue_overrideEnabled_returnsTrue()
            throws Exception {
        mockThatProperty(PROPERTY_COMPAT_ALLOW_MIN_ASPECT_RATIO_OVERRIDE, /* value */ true);
        mController = new LetterboxUiController(mWm, mActivity);

        assertTrue(mController.shouldOverrideMinAspectRatio());
    }

    @Test
    @DisableCompatChanges({OVERRIDE_MIN_ASPECT_RATIO})
    public void testshouldOverrideMinAspectRatio_propertyTrue_overrideDisabled_returnsFalse()
            throws Exception {
        mockThatProperty(PROPERTY_COMPAT_ALLOW_MIN_ASPECT_RATIO_OVERRIDE, /* value */ true);
        mController = new LetterboxUiController(mWm, mActivity);

        assertFalse(mController.shouldOverrideMinAspectRatio());
    }

    @Test
    @DisableCompatChanges({OVERRIDE_MIN_ASPECT_RATIO})
    public void testshouldOverrideMinAspectRatio_overrideDisabled_returnsFalse() {
        mController = new LetterboxUiController(mWm, mActivity);

        assertFalse(mController.shouldOverrideMinAspectRatio());
    }

    @Test
    @EnableCompatChanges({OVERRIDE_MIN_ASPECT_RATIO})
    public void testshouldOverrideMinAspectRatio_propertyFalse_overrideEnabled_returnsFalse()
            throws Exception {
        mockThatProperty(PROPERTY_COMPAT_ALLOW_MIN_ASPECT_RATIO_OVERRIDE, /* value */ false);
        mController = new LetterboxUiController(mWm, mActivity);

        assertFalse(mController.shouldOverrideMinAspectRatio());
    }

    @Test
    @DisableCompatChanges({OVERRIDE_MIN_ASPECT_RATIO})
    public void testshouldOverrideMinAspectRatio_propertyFalse_noOverride_returnsFalse()
            throws Exception {
        mockThatProperty(PROPERTY_COMPAT_ALLOW_MIN_ASPECT_RATIO_OVERRIDE, /* value */ false);
        mController = new LetterboxUiController(mWm, mActivity);

        assertFalse(mController.shouldOverrideMinAspectRatio());
    }

    @Test
    @EnableCompatChanges({FORCE_RESIZE_APP})
    public void testshouldOverrideForceResizeApp_overrideEnabled_returnsTrue() {
        mController = new LetterboxUiController(mWm, mActivity);

        assertTrue(mController.shouldOverrideForceResizeApp());
    }

    @Test
    @EnableCompatChanges({FORCE_RESIZE_APP})
    public void testshouldOverrideForceResizeApp_propertyTrue_overrideEnabled_returnsTrue()
            throws Exception {
        mockThatProperty(PROPERTY_COMPAT_ALLOW_RESIZEABLE_ACTIVITY_OVERRIDES, /* value */ true);
        mController = new LetterboxUiController(mWm, mActivity);

        assertTrue(mController.shouldOverrideForceResizeApp());
    }

    @Test
    @DisableCompatChanges({FORCE_RESIZE_APP})
    public void testshouldOverrideForceResizeApp_propertyTrue_overrideDisabled_returnsFalse()
            throws Exception {
        mockThatProperty(PROPERTY_COMPAT_ALLOW_RESIZEABLE_ACTIVITY_OVERRIDES, /* value */ true);
        mController = new LetterboxUiController(mWm, mActivity);

        assertFalse(mController.shouldOverrideForceResizeApp());
    }

    @Test
    @DisableCompatChanges({FORCE_RESIZE_APP})
    public void testshouldOverrideForceResizeApp_overrideDisabled_returnsFalse() {
        mController = new LetterboxUiController(mWm, mActivity);

        assertFalse(mController.shouldOverrideForceResizeApp());
    }

    @Test
    @EnableCompatChanges({FORCE_RESIZE_APP})
    public void testshouldOverrideForceResizeApp_propertyFalse_overrideEnabled_returnsFalse()
            throws Exception {
        mockThatProperty(PROPERTY_COMPAT_ALLOW_RESIZEABLE_ACTIVITY_OVERRIDES, /* value */ false);
        mController = new LetterboxUiController(mWm, mActivity);

        assertFalse(mController.shouldOverrideForceResizeApp());
    }

    @Test
    @DisableCompatChanges({FORCE_RESIZE_APP})
    public void testshouldOverrideForceResizeApp_propertyFalse_noOverride_returnsFalse()
            throws Exception {
        mockThatProperty(PROPERTY_COMPAT_ALLOW_RESIZEABLE_ACTIVITY_OVERRIDES, /* value */ false);
        mController = new LetterboxUiController(mWm, mActivity);

        assertFalse(mController.shouldOverrideForceResizeApp());
    }

    @Test
    @EnableCompatChanges({FORCE_NON_RESIZE_APP})
    public void testshouldOverrideForceNonResizeApp_overrideEnabled_returnsTrue() {
        mController = new LetterboxUiController(mWm, mActivity);

        assertTrue(mController.shouldOverrideForceNonResizeApp());
    }

    @Test
    @EnableCompatChanges({FORCE_NON_RESIZE_APP})
    public void testshouldOverrideForceNonResizeApp_propertyTrue_overrideEnabled_returnsTrue()
            throws Exception {
        mockThatProperty(PROPERTY_COMPAT_ALLOW_RESIZEABLE_ACTIVITY_OVERRIDES, /* value */ true);
        mController = new LetterboxUiController(mWm, mActivity);

        assertTrue(mController.shouldOverrideForceNonResizeApp());
    }

    @Test
    @DisableCompatChanges({FORCE_NON_RESIZE_APP})
    public void testshouldOverrideForceNonResizeApp_propertyTrue_overrideDisabled_returnsFalse()
            throws Exception {
        mockThatProperty(PROPERTY_COMPAT_ALLOW_RESIZEABLE_ACTIVITY_OVERRIDES, /* value */ true);
        mController = new LetterboxUiController(mWm, mActivity);

        assertFalse(mController.shouldOverrideForceNonResizeApp());
    }

    @Test
    @DisableCompatChanges({FORCE_NON_RESIZE_APP})
    public void testshouldOverrideForceNonResizeApp_overrideDisabled_returnsFalse() {
        mController = new LetterboxUiController(mWm, mActivity);

        assertFalse(mController.shouldOverrideForceNonResizeApp());
    }

    @Test
    @EnableCompatChanges({FORCE_NON_RESIZE_APP})
    public void testshouldOverrideForceNonResizeApp_propertyFalse_overrideEnabled_returnsFalse()
            throws Exception {
        mockThatProperty(PROPERTY_COMPAT_ALLOW_RESIZEABLE_ACTIVITY_OVERRIDES, /* value */ false);
        mController = new LetterboxUiController(mWm, mActivity);

        assertFalse(mController.shouldOverrideForceNonResizeApp());
    }

    @Test
    @DisableCompatChanges({FORCE_NON_RESIZE_APP})
    public void testshouldOverrideForceNonResizeApp_propertyFalse_noOverride_returnsFalse()
            throws Exception {
        mockThatProperty(PROPERTY_COMPAT_ALLOW_RESIZEABLE_ACTIVITY_OVERRIDES, /* value */ false);
        mController = new LetterboxUiController(mWm, mActivity);

        assertFalse(mController.shouldOverrideForceNonResizeApp());
    }

    @Test
    public void testgetFixedOrientationLetterboxAspectRatio_splitScreenAspectEnabled() {
        doReturn(true).when(mActivity.mWmService.mLetterboxConfiguration)
                .isCameraCompatTreatmentEnabled();
        doReturn(true).when(mActivity.mWmService.mLetterboxConfiguration)
                .isCameraCompatTreatmentEnabledAtBuildTime();
        doReturn(true).when(mActivity.mWmService.mLetterboxConfiguration)
                .isCameraCompatSplitScreenAspectRatioEnabled();
        doReturn(false).when(mActivity.mWmService.mLetterboxConfiguration)
                .getIsDisplayAspectRatioEnabledForFixedOrientationLetterbox();
        doReturn(1.5f).when(mActivity.mWmService.mLetterboxConfiguration)
                .getFixedOrientationLetterboxAspectRatio();

        // Recreate DisplayContent with DisplayRotationCompatPolicy
        mActivity = setUpActivityWithComponent();
        mController = new LetterboxUiController(mWm, mActivity);

        assertEquals(mController.getFixedOrientationLetterboxAspectRatio(
                mActivity.getParent().getConfiguration()), 1.5f, /* delta */ 0.01);

        spyOn(mDisplayContent.mDisplayRotationCompatPolicy);
        doReturn(true).when(mDisplayContent.mDisplayRotationCompatPolicy)
                .isTreatmentEnabledForActivity(eq(mActivity));

        assertEquals(mController.getFixedOrientationLetterboxAspectRatio(
                mActivity.getParent().getConfiguration()), mController.getSplitScreenAspectRatio(),
                /* delta */  0.01);
    }

    private void mockThatProperty(String propertyName, boolean value) throws Exception {
        Property property = new Property(propertyName, /* value */ value, /* packageName */ "",
                 /* className */ "");
        PackageManager pm = mWm.mContext.getPackageManager();
        spyOn(pm);
        doReturn(property).when(pm).getProperty(eq(propertyName), anyString());
    }

    private void prepareActivityThatShouldUseDisplayLandscapeNaturalOrientation() {
        spyOn(mDisplayContent);
        doReturn(ORIENTATION_LANDSCAPE).when(mDisplayContent).getNaturalOrientation();
        mDisplayContent.setIgnoreOrientationRequest(true);
    }

    private void prepareActivityThatShouldIgnoreRequestedOrientationDuringRelaunch() {
        doReturn(true).when(mLetterboxConfiguration)
                .isPolicyForIgnoringRequestedOrientationEnabled();
        mController.setRelaunchingAfterRequestedOrientationChanged(true);
    }

    private ActivityRecord setUpActivityWithComponent() {
        mDisplayContent = new TestDisplayContent
                .Builder(mAtm, /* dw */ 1000, /* dh */ 2000).build();
        mTask = new TaskBuilder(mSupervisor).setDisplay(mDisplayContent).build();
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setOnTop(true)
                .setTask(mTask)
                // Set the component to be that of the test class in order to enable compat changes
                .setComponent(ComponentName.createRelative(mContext,
                        com.android.server.wm.LetterboxUiControllerTest.class.getName()))
                .build();
        return activity;
    }
}

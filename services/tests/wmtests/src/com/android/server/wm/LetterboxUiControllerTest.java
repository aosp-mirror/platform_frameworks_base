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

import static android.content.pm.ActivityInfo.OVERRIDE_CAMERA_COMPAT_DISABLE_FORCE_ROTATION;
import static android.content.pm.ActivityInfo.OVERRIDE_CAMERA_COMPAT_DISABLE_REFRESH;
import static android.content.pm.ActivityInfo.OVERRIDE_CAMERA_COMPAT_ENABLE_REFRESH_VIA_PAUSE;
import static android.content.pm.ActivityInfo.OVERRIDE_ENABLE_COMPAT_IGNORE_REQUESTED_ORIENTATION;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.view.WindowManager.PROPERTY_CAMERA_COMPAT_ALLOW_FORCE_ROTATION;
import static android.view.WindowManager.PROPERTY_CAMERA_COMPAT_ALLOW_REFRESH;
import static android.view.WindowManager.PROPERTY_CAMERA_COMPAT_ENABLE_REFRESH_VIA_PAUSE;
import static android.view.WindowManager.PROPERTY_COMPAT_IGNORE_REQUESTED_ORIENTATION;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.eq;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;

import android.compat.testing.PlatformCompatChangeRule;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.Property;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

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

    @Rule
    public TestRule compatChangeRule = new PlatformCompatChangeRule();

    private ActivityRecord mActivity;
    private DisplayContent mDisplayContent;
    private LetterboxUiController mController;
    private LetterboxConfiguration mLetterboxConfiguration;

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
        doReturn(true).when(mLetterboxConfiguration).isCameraCompatTreatmentEnabled(anyBoolean());

        // Recreate DisplayContent with DisplayRotationCompatPolicy
        mActivity = setUpActivityWithComponent();
        mController = new LetterboxUiController(mWm, mActivity);
        prepareActivityThatShouldIgnoreRequestedOrientationDuringRelaunch();
        mController.setRelauchingAfterRequestedOrientationChanged(false);

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
                .isCameraCompatTreatmentEnabled(/* checkDeviceConfig */ true);

        assertFalse(mController.shouldRefreshActivityForCameraCompat());
    }

    @Test
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_DISABLE_REFRESH})
    public void testShouldRefreshActivityForCameraCompat_overrideEnabled_returnsFalse() {
        doReturn(true).when(mLetterboxConfiguration)
                .isCameraCompatTreatmentEnabled(/* checkDeviceConfig */ true);

        assertFalse(mController.shouldRefreshActivityForCameraCompat());
    }

    @Test
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_DISABLE_REFRESH})
    public void testShouldRefreshActivityForCameraCompat_propertyIsTrueAndOverride_returnsFalse()
            throws Exception {
        doReturn(true).when(mLetterboxConfiguration)
                .isCameraCompatTreatmentEnabled(/* checkDeviceConfig */ true);
        mockThatProperty(PROPERTY_CAMERA_COMPAT_ALLOW_REFRESH, /* value */ true);

        mController = new LetterboxUiController(mWm, mActivity);

        assertFalse(mController.shouldRefreshActivityForCameraCompat());
    }

    @Test
    public void testShouldRefreshActivityForCameraCompat_propertyIsFalse_returnsFalse()
            throws Exception {
        doReturn(true).when(mLetterboxConfiguration)
                .isCameraCompatTreatmentEnabled(/* checkDeviceConfig */ true);
        mockThatProperty(PROPERTY_CAMERA_COMPAT_ALLOW_REFRESH, /* value */ false);

        mController = new LetterboxUiController(mWm, mActivity);

        assertFalse(mController.shouldRefreshActivityForCameraCompat());
    }

    @Test
    public void testShouldRefreshActivityForCameraCompat_propertyIsTrue_returnsTrue()
            throws Exception {
        doReturn(true).when(mLetterboxConfiguration)
                .isCameraCompatTreatmentEnabled(/* checkDeviceConfig */ true);
        mockThatProperty(PROPERTY_CAMERA_COMPAT_ALLOW_REFRESH, /* value */ true);

        mController = new LetterboxUiController(mWm, mActivity);

        assertTrue(mController.shouldRefreshActivityForCameraCompat());
    }

    // shouldRefreshActivityViaPauseForCameraCompat

    @Test
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_ENABLE_REFRESH_VIA_PAUSE})
    public void testShouldRefreshActivityViaPauseForCameraCompat_flagIsDisabled_returnsFalse() {
        doReturn(false).when(mLetterboxConfiguration)
                .isCameraCompatTreatmentEnabled(/* checkDeviceConfig */ true);

        assertFalse(mController.shouldRefreshActivityViaPauseForCameraCompat());
    }

    @Test
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_ENABLE_REFRESH_VIA_PAUSE})
    public void testShouldRefreshActivityViaPauseForCameraCompat_overrideEnabled_returnsTrue() {
        doReturn(true).when(mLetterboxConfiguration)
                .isCameraCompatTreatmentEnabled(/* checkDeviceConfig */ true);

        assertTrue(mController.shouldRefreshActivityViaPauseForCameraCompat());
    }

    @Test
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_ENABLE_REFRESH_VIA_PAUSE})
    public void testShouldRefreshActivityViaPauseForCameraCompat_propertyIsFalseAndOverride_returnFalse()
            throws Exception {
        doReturn(true).when(mLetterboxConfiguration)
                .isCameraCompatTreatmentEnabled(/* checkDeviceConfig */ true);
        mockThatProperty(PROPERTY_CAMERA_COMPAT_ENABLE_REFRESH_VIA_PAUSE, /* value */ false);

        mController = new LetterboxUiController(mWm, mActivity);

        assertFalse(mController.shouldRefreshActivityViaPauseForCameraCompat());
    }

    @Test
    public void testShouldRefreshActivityViaPauseForCameraCompat_propertyIsTrue_returnsTrue()
            throws Exception {
        doReturn(true).when(mLetterboxConfiguration)
                .isCameraCompatTreatmentEnabled(/* checkDeviceConfig */ true);
        mockThatProperty(PROPERTY_CAMERA_COMPAT_ENABLE_REFRESH_VIA_PAUSE, /* value */ true);

        mController = new LetterboxUiController(mWm, mActivity);

        assertTrue(mController.shouldRefreshActivityViaPauseForCameraCompat());
    }

    // shouldForceRotateForCameraCompat

    @Test
    public void testShouldForceRotateForCameraCompat_flagIsDisabled_returnsFalse() {
        doReturn(false).when(mLetterboxConfiguration)
                .isCameraCompatTreatmentEnabled(/* checkDeviceConfig */ true);

        assertFalse(mController.shouldForceRotateForCameraCompat());
    }

    @Test
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_DISABLE_FORCE_ROTATION})
    public void testShouldForceRotateForCameraCompat_overrideEnabled_returnsFalse() {
        doReturn(true).when(mLetterboxConfiguration)
                .isCameraCompatTreatmentEnabled(/* checkDeviceConfig */ true);

        assertFalse(mController.shouldForceRotateForCameraCompat());
    }

    @Test
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_DISABLE_FORCE_ROTATION})
    public void testShouldForceRotateForCameraCompat_propertyIsTrueAndOverride_returnsFalse()
            throws Exception {
        doReturn(true).when(mLetterboxConfiguration)
                .isCameraCompatTreatmentEnabled(/* checkDeviceConfig */ true);
        mockThatProperty(PROPERTY_CAMERA_COMPAT_ALLOW_FORCE_ROTATION, /* value */ true);

        mController = new LetterboxUiController(mWm, mActivity);

        assertFalse(mController.shouldForceRotateForCameraCompat());
    }

    @Test
    public void testShouldForceRotateForCameraCompat_propertyIsFalse_returnsFalse()
            throws Exception {
        doReturn(true).when(mLetterboxConfiguration)
                .isCameraCompatTreatmentEnabled(/* checkDeviceConfig */ true);
        mockThatProperty(PROPERTY_CAMERA_COMPAT_ALLOW_FORCE_ROTATION, /* value */ false);

        mController = new LetterboxUiController(mWm, mActivity);

        assertFalse(mController.shouldForceRotateForCameraCompat());
    }

    @Test
    public void testShouldForceRotateForCameraCompat_propertyIsTrue_returnsTrue()
            throws Exception {
        doReturn(true).when(mLetterboxConfiguration)
                .isCameraCompatTreatmentEnabled(/* checkDeviceConfig */ true);
        mockThatProperty(PROPERTY_CAMERA_COMPAT_ALLOW_FORCE_ROTATION, /* value */ true);

        mController = new LetterboxUiController(mWm, mActivity);

        assertTrue(mController.shouldForceRotateForCameraCompat());
    }

    private void mockThatProperty(String propertyName, boolean value) throws Exception {
        Property property = new Property(propertyName, /* value */ value, /* packageName */ "",
                 /* className */ "");
        PackageManager pm = mWm.mContext.getPackageManager();
        spyOn(pm);
        doReturn(property).when(pm).getProperty(eq(propertyName), anyString());
    }

    private void prepareActivityThatShouldIgnoreRequestedOrientationDuringRelaunch() {
        doReturn(true).when(mLetterboxConfiguration)
                .isPolicyForIgnoringRequestedOrientationEnabled();
        mController.setRelauchingAfterRequestedOrientationChanged(true);
    }

    private ActivityRecord setUpActivityWithComponent() {
        mDisplayContent = new TestDisplayContent
                .Builder(mAtm, /* dw */ 1000, /* dh */ 2000).build();
        Task task = new TaskBuilder(mSupervisor).setDisplay(mDisplayContent).build();
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setOnTop(true)
                .setTask(task)
                // Set the component to be that of the test class in order to enable compat changes
                .setComponent(ComponentName.createRelative(mContext,
                        com.android.server.wm.LetterboxUiControllerTest.class.getName()))
                .build();
        return activity;
    }
}

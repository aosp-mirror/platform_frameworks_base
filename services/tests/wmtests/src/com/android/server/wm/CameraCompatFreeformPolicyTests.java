/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static android.app.CameraCompatTaskInfo.CAMERA_COMPAT_FREEFORM_LANDSCAPE_DEVICE_IN_LANDSCAPE;
import static android.app.CameraCompatTaskInfo.CAMERA_COMPAT_FREEFORM_LANDSCAPE_DEVICE_IN_PORTRAIT;
import static android.app.CameraCompatTaskInfo.CAMERA_COMPAT_FREEFORM_NONE;
import static android.app.CameraCompatTaskInfo.CAMERA_COMPAT_FREEFORM_PORTRAIT_DEVICE_IN_LANDSCAPE;
import static android.app.CameraCompatTaskInfo.CAMERA_COMPAT_FREEFORM_PORTRAIT_DEVICE_IN_PORTRAIT;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.servertransaction.ActivityLifecycleItem.ON_PAUSE;
import static android.app.servertransaction.ActivityLifecycleItem.ON_STOP;
import static android.content.pm.ActivityInfo.OVERRIDE_CAMERA_COMPAT_ENABLE_FREEFORM_WINDOWING_TREATMENT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;
import static com.android.server.wm.AppCompatConfiguration.MIN_FIXED_ORIENTATION_LETTERBOX_ASPECT_RATIO;
import static com.android.window.flags.Flags.FLAG_ENABLE_CAMERA_COMPAT_FOR_DESKTOP_WINDOWING;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.CameraCompatTaskInfo;
import android.app.IApplicationThread;
import android.app.WindowConfiguration.WindowingMode;
import android.app.servertransaction.RefreshCallbackItem;
import android.app.servertransaction.ResumeActivityItem;
import android.compat.testing.PlatformCompatChangeRule;
import android.content.ComponentName;
import android.content.pm.ActivityInfo.ScreenOrientation;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.content.res.Configuration.Orientation;
import android.graphics.Rect;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.Presubmit;
import android.view.DisplayInfo;
import android.view.Surface;

import androidx.test.filters.SmallTest;

import libcore.junit.util.compat.CoreCompatChangeRule.EnableCompatChanges;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.Executor;

/**
 * Tests for {@link CameraCompatFreeformPolicy}.
 *
 * Build/Install/Run:
 *  atest WmTests:CameraCompatFreeformPolicyTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class CameraCompatFreeformPolicyTests extends WindowTestsBase {
    @Rule
    public TestRule compatChangeRule = new PlatformCompatChangeRule();

    // Main activity package name needs to be the same as the process to test overrides.
    private static final String TEST_PACKAGE_1 = "com.android.frameworks.wmtests";
    private static final String TEST_PACKAGE_2 = "com.test.package.two";
    private static final String CAMERA_ID_1 = "camera-1";
    private AppCompatConfiguration mAppCompatConfiguration;

    private CameraManager.AvailabilityCallback mCameraAvailabilityCallback;
    private CameraCompatFreeformPolicy mCameraCompatFreeformPolicy;
    private ActivityRecord mActivity;
    private ActivityRefresher mActivityRefresher;

    @Before
    public void setUp() throws Exception {
        mAppCompatConfiguration = mDisplayContent.mWmService.mAppCompatConfiguration;
        spyOn(mAppCompatConfiguration);
        when(mAppCompatConfiguration.isCameraCompatTreatmentEnabled()).thenReturn(true);
        when(mAppCompatConfiguration.isCameraCompatRefreshEnabled()).thenReturn(true);
        when(mAppCompatConfiguration.isCameraCompatRefreshCycleThroughStopEnabled())
                .thenReturn(true);

        final CameraManager mockCameraManager = mock(CameraManager.class);
        doAnswer(invocation -> {
            mCameraAvailabilityCallback = invocation.getArgument(1);
            return null;
        }).when(mockCameraManager).registerAvailabilityCallback(
                any(Executor.class), any(CameraManager.AvailabilityCallback.class));

        when(mContext.getSystemService(CameraManager.class)).thenReturn(mockCameraManager);

        mDisplayContent.setIgnoreOrientationRequest(true);

        final Handler mockHandler = mock(Handler.class);

        when(mockHandler.postDelayed(any(Runnable.class), anyLong())).thenAnswer(
                invocation -> {
                    ((Runnable) invocation.getArgument(0)).run();
                    return null;
                });

        mActivityRefresher = new ActivityRefresher(mDisplayContent.mWmService, mockHandler);
        final CameraStateMonitor cameraStateMonitor = new CameraStateMonitor(mDisplayContent,
                mockHandler);
        mCameraCompatFreeformPolicy = new CameraCompatFreeformPolicy(mDisplayContent,
                cameraStateMonitor, mActivityRefresher);

        setDisplayRotation(ROTATION_90);
        mCameraCompatFreeformPolicy.start();
        cameraStateMonitor.startListeningToCameraState();
    }

    @Test
    @DisableFlags(FLAG_ENABLE_CAMERA_COMPAT_FOR_DESKTOP_WINDOWING)
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_ENABLE_FREEFORM_WINDOWING_TREATMENT})
    public void testIsCameraRunningAndWindowingModeEligible_featureDisabled_returnsFalse() {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);

        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);

        assertFalse(mCameraCompatFreeformPolicy.isCameraRunningAndWindowingModeEligible(mActivity));
    }

    @Test
    @EnableFlags(FLAG_ENABLE_CAMERA_COMPAT_FOR_DESKTOP_WINDOWING)
    public void testIsCameraRunningAndWindowingModeEligible_overrideDisabled_returnsFalse() {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);

        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);

        assertFalse(mCameraCompatFreeformPolicy.isCameraRunningAndWindowingModeEligible(mActivity));
    }

    @Test
    @EnableFlags(FLAG_ENABLE_CAMERA_COMPAT_FOR_DESKTOP_WINDOWING)
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_ENABLE_FREEFORM_WINDOWING_TREATMENT})
    public void testIsCameraRunningAndWindowingModeEligible_cameraNotRunning_returnsFalse() {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);

        assertFalse(mCameraCompatFreeformPolicy.isCameraRunningAndWindowingModeEligible(mActivity));
    }

    @Test
    @EnableFlags(FLAG_ENABLE_CAMERA_COMPAT_FOR_DESKTOP_WINDOWING)
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_ENABLE_FREEFORM_WINDOWING_TREATMENT})
    public void testIsCameraRunningAndWindowingModeEligible_notFreeformWindowing_returnsFalse() {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT, WINDOWING_MODE_FULLSCREEN);

        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);

        assertFalse(mCameraCompatFreeformPolicy.isCameraRunningAndWindowingModeEligible(mActivity));
    }

    @Test
    @EnableFlags(FLAG_ENABLE_CAMERA_COMPAT_FOR_DESKTOP_WINDOWING)
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_ENABLE_FREEFORM_WINDOWING_TREATMENT})
    public void testIsCameraRunningAndWindowingModeEligible_optInFreeformCameraRunning_true() {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);

        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);

        assertTrue(mCameraCompatFreeformPolicy.isCameraRunningAndWindowingModeEligible(mActivity));
    }

    @Test
    @EnableFlags(FLAG_ENABLE_CAMERA_COMPAT_FOR_DESKTOP_WINDOWING)
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_ENABLE_FREEFORM_WINDOWING_TREATMENT})
    public void testFullscreen_doesNotActivateCameraCompatMode() {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT, WINDOWING_MODE_FULLSCREEN);
        doReturn(false).when(mActivity).inFreeformWindowingMode();

        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);

        assertNotInCameraCompatMode();
    }

    @Test
    @EnableFlags(FLAG_ENABLE_CAMERA_COMPAT_FOR_DESKTOP_WINDOWING)
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_ENABLE_FREEFORM_WINDOWING_TREATMENT})
    public void testOrientationUnspecified_doesNotActivateCameraCompatMode() {
        configureActivity(SCREEN_ORIENTATION_UNSPECIFIED);

        assertNotInCameraCompatMode();
    }

    @Test
    @EnableFlags(FLAG_ENABLE_CAMERA_COMPAT_FOR_DESKTOP_WINDOWING)
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_ENABLE_FREEFORM_WINDOWING_TREATMENT})
    public void testNoCameraConnection_doesNotActivateCameraCompatMode() {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);
        assertNotInCameraCompatMode();
    }

    @Test
    @EnableFlags(FLAG_ENABLE_CAMERA_COMPAT_FOR_DESKTOP_WINDOWING)
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_ENABLE_FREEFORM_WINDOWING_TREATMENT})
    public void testCameraConnected_deviceInPortrait_portraitCameraCompatMode() throws Exception {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);
        setDisplayRotation(ROTATION_0);
        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);

        assertInCameraCompatMode(CAMERA_COMPAT_FREEFORM_PORTRAIT_DEVICE_IN_PORTRAIT);
        assertActivityRefreshRequested(/* refreshRequested */ false);
    }

    @Test
    @EnableFlags(FLAG_ENABLE_CAMERA_COMPAT_FOR_DESKTOP_WINDOWING)
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_ENABLE_FREEFORM_WINDOWING_TREATMENT})
    public void testCameraConnected_deviceInLandscape_portraitCameraCompatMode() throws Exception {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);
        setDisplayRotation(ROTATION_270);
        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);

        assertInCameraCompatMode(CAMERA_COMPAT_FREEFORM_PORTRAIT_DEVICE_IN_LANDSCAPE);
        assertActivityRefreshRequested(/* refreshRequested */ false);
    }

    @Test
    @EnableFlags(FLAG_ENABLE_CAMERA_COMPAT_FOR_DESKTOP_WINDOWING)
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_ENABLE_FREEFORM_WINDOWING_TREATMENT})
    public void testCameraConnected_deviceInPortrait_landscapeCameraCompatMode() throws Exception {
        configureActivity(SCREEN_ORIENTATION_LANDSCAPE);
        setDisplayRotation(ROTATION_0);
        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);

        assertInCameraCompatMode(CAMERA_COMPAT_FREEFORM_LANDSCAPE_DEVICE_IN_PORTRAIT);
        assertActivityRefreshRequested(/* refreshRequested */ false);
    }

    @Test
    @EnableFlags(FLAG_ENABLE_CAMERA_COMPAT_FOR_DESKTOP_WINDOWING)
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_ENABLE_FREEFORM_WINDOWING_TREATMENT})
    public void testCameraConnected_deviceInLandscape_landscapeCameraCompatMode() throws Exception {
        configureActivity(SCREEN_ORIENTATION_LANDSCAPE);
        setDisplayRotation(ROTATION_270);
        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);

        assertInCameraCompatMode(CAMERA_COMPAT_FREEFORM_LANDSCAPE_DEVICE_IN_LANDSCAPE);
        assertActivityRefreshRequested(/* refreshRequested */ false);
    }

    @Test
    @EnableFlags(FLAG_ENABLE_CAMERA_COMPAT_FOR_DESKTOP_WINDOWING)
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_ENABLE_FREEFORM_WINDOWING_TREATMENT})
    public void testCameraReconnected_cameraCompatModeAndRefresh() throws Exception {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);
        setDisplayRotation(ROTATION_270);

        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);
        callOnActivityConfigurationChanging(mActivity, /* letterboxNew= */ true,
                /* lastLetterbox= */ false);
        mCameraAvailabilityCallback.onCameraClosed(CAMERA_ID_1);
        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);
        // Activity is letterboxed from the previous configuration change.
        callOnActivityConfigurationChanging(mActivity, /* letterboxNew= */ true,
                /* lastLetterbox= */ true);

        assertInCameraCompatMode(CAMERA_COMPAT_FREEFORM_PORTRAIT_DEVICE_IN_LANDSCAPE);
        assertActivityRefreshRequested(/* refreshRequested */ true);
    }

    @Test
    @EnableFlags(FLAG_ENABLE_CAMERA_COMPAT_FOR_DESKTOP_WINDOWING)
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_ENABLE_FREEFORM_WINDOWING_TREATMENT})
    public void testCameraOpenedForDifferentPackage_notInCameraCompatMode() {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);

        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_2);

        assertNotInCameraCompatMode();
    }

    @Test
    @EnableFlags(FLAG_ENABLE_CAMERA_COMPAT_FOR_DESKTOP_WINDOWING)
    public void testShouldApplyCameraCompatFreeformTreatment_overrideNotEnabled_returnsFalse() {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);

        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);

        assertFalse(mCameraCompatFreeformPolicy.isTreatmentEnabledForActivity(mActivity,
                /* checkOrientation */ true));
    }

    @Test
    @EnableFlags(FLAG_ENABLE_CAMERA_COMPAT_FOR_DESKTOP_WINDOWING)
    @EnableCompatChanges(OVERRIDE_CAMERA_COMPAT_ENABLE_FREEFORM_WINDOWING_TREATMENT)
    public void testShouldApplyCameraCompatFreeformTreatment_enabledByOverride_returnsTrue() {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);

        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);

        assertTrue(mActivity.info
                .isChangeEnabled(OVERRIDE_CAMERA_COMPAT_ENABLE_FREEFORM_WINDOWING_TREATMENT));
        assertTrue(mCameraCompatFreeformPolicy.isTreatmentEnabledForActivity(mActivity,
                /* checkOrientation */ true));
    }

    @Test
    @EnableFlags(FLAG_ENABLE_CAMERA_COMPAT_FOR_DESKTOP_WINDOWING)
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_ENABLE_FREEFORM_WINDOWING_TREATMENT})
    public void testShouldRefreshActivity_appBoundsChanged_returnsTrue() {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);
        Configuration oldConfiguration = createConfiguration(/* letterbox= */ false);
        Configuration newConfiguration = createConfiguration(/* letterbox= */ true);
        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);

        assertTrue(mCameraCompatFreeformPolicy.shouldRefreshActivity(mActivity, newConfiguration,
                oldConfiguration));
    }

    @Test
    @EnableFlags(FLAG_ENABLE_CAMERA_COMPAT_FOR_DESKTOP_WINDOWING)
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_ENABLE_FREEFORM_WINDOWING_TREATMENT})
    public void testShouldRefreshActivity_displayRotationChanged_returnsTrue() {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);
        Configuration oldConfiguration = createConfiguration(/* letterbox= */ true);
        Configuration newConfiguration = createConfiguration(/* letterbox= */ true);

        oldConfiguration.windowConfiguration.setDisplayRotation(0);
        newConfiguration.windowConfiguration.setDisplayRotation(90);
        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);

        assertTrue(mCameraCompatFreeformPolicy.shouldRefreshActivity(mActivity, newConfiguration,
                oldConfiguration));
    }

    @Test
    @EnableFlags(FLAG_ENABLE_CAMERA_COMPAT_FOR_DESKTOP_WINDOWING)
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_ENABLE_FREEFORM_WINDOWING_TREATMENT})
    public void testShouldRefreshActivity_appBoundsNorDisplayChanged_returnsFalse() {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);
        Configuration oldConfiguration = createConfiguration(/* letterbox= */ true);
        Configuration newConfiguration = createConfiguration(/* letterbox= */ true);

        oldConfiguration.windowConfiguration.setDisplayRotation(0);
        newConfiguration.windowConfiguration.setDisplayRotation(0);
        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);

        assertFalse(mCameraCompatFreeformPolicy.shouldRefreshActivity(mActivity, newConfiguration,
                oldConfiguration));
    }

    @Test
    @EnableFlags(FLAG_ENABLE_CAMERA_COMPAT_FOR_DESKTOP_WINDOWING)
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_ENABLE_FREEFORM_WINDOWING_TREATMENT})
    public void testOnActivityConfigurationChanging_refreshDisabledViaFlag_noRefresh()
            throws Exception {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);

        doReturn(false).when(mActivity.mAppCompatController.getAppCompatCameraOverrides())
                .shouldRefreshActivityForCameraCompat();

        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);
        callOnActivityConfigurationChanging(mActivity);

        assertActivityRefreshRequested(/* refreshRequested */ false);
    }

    @Test
    @EnableFlags(FLAG_ENABLE_CAMERA_COMPAT_FOR_DESKTOP_WINDOWING)
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_ENABLE_FREEFORM_WINDOWING_TREATMENT})
    public void testOnActivityConfigurationChanging_cycleThroughStopDisabled() throws Exception {
        when(mAppCompatConfiguration.isCameraCompatRefreshCycleThroughStopEnabled())
                .thenReturn(false);

        configureActivity(SCREEN_ORIENTATION_PORTRAIT);

        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);
        callOnActivityConfigurationChanging(mActivity);

        assertActivityRefreshRequested(/* refreshRequested */ true, /* cycleThroughStop */ false);
    }

    @Test
    @EnableFlags(FLAG_ENABLE_CAMERA_COMPAT_FOR_DESKTOP_WINDOWING)
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_ENABLE_FREEFORM_WINDOWING_TREATMENT})
    public void testOnActivityConfigurationChanging_cycleThroughStopDisabledForApp()
            throws Exception {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);
        doReturn(true).when(mActivity.mAppCompatController.getAppCompatCameraOverrides())
                .shouldRefreshActivityViaPauseForCameraCompat();

        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);
        callOnActivityConfigurationChanging(mActivity);

        assertActivityRefreshRequested(/* refreshRequested */ true, /* cycleThroughStop */ false);
    }

    @Test
    @EnableFlags(FLAG_ENABLE_CAMERA_COMPAT_FOR_DESKTOP_WINDOWING)
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_ENABLE_FREEFORM_WINDOWING_TREATMENT})
    public void testGetCameraCompatAspectRatio_activityNotInCameraCompat_returnsDefaultAspRatio() {
        configureActivity(SCREEN_ORIENTATION_FULL_USER);

        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);
        callOnActivityConfigurationChanging(mActivity);

        assertEquals(MIN_FIXED_ORIENTATION_LETTERBOX_ASPECT_RATIO,
                mCameraCompatFreeformPolicy.getCameraCompatAspectRatio(mActivity),
                /* delta= */ 0.001);
    }

    @Test
    @EnableFlags(FLAG_ENABLE_CAMERA_COMPAT_FOR_DESKTOP_WINDOWING)
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_ENABLE_FREEFORM_WINDOWING_TREATMENT})
    public void testGetCameraCompatAspectRatio_activityInCameraCompat_returnsConfigAspectRatio() {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);
        final float configAspectRatio = 1.5f;
        mWm.mAppCompatConfiguration.setCameraCompatAspectRatio(configAspectRatio);

        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);
        callOnActivityConfigurationChanging(mActivity);

        assertEquals(configAspectRatio,
                mCameraCompatFreeformPolicy.getCameraCompatAspectRatio(mActivity),
                /* delta= */ 0.001);
    }

    @Test
    @EnableFlags(FLAG_ENABLE_CAMERA_COMPAT_FOR_DESKTOP_WINDOWING)
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_ENABLE_FREEFORM_WINDOWING_TREATMENT})
    public void testGetCameraCompatAspectRatio_inCameraCompatPerAppOverride_returnDefAspectRatio() {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);
        final float configAspectRatio = 1.5f;
        mWm.mAppCompatConfiguration.setCameraCompatAspectRatio(configAspectRatio);
        doReturn(true).when(mActivity.mAppCompatController.getAppCompatCameraOverrides())
                .isOverrideMinAspectRatioForCameraEnabled();

        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);
        callOnActivityConfigurationChanging(mActivity);

        assertEquals(MIN_FIXED_ORIENTATION_LETTERBOX_ASPECT_RATIO,
                mCameraCompatFreeformPolicy.getCameraCompatAspectRatio(mActivity),
                /* delta= */ 0.001);
    }

    @Test
    @EnableFlags(FLAG_ENABLE_CAMERA_COMPAT_FOR_DESKTOP_WINDOWING)
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_ENABLE_FREEFORM_WINDOWING_TREATMENT})
    public void testOnCameraOpened_portraitActivity_sandboxesDisplayRotationAndUpdatesApp() throws
            Exception {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);
        setDisplayRotation(ROTATION_270);

        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);

        // This is a portrait rotation for a device with portrait natural orientation (most common,
        // currently the only one supported).
        assertCompatibilityInfoSentWithDisplayRotation(ROTATION_0);
    }

    @Test
    @EnableFlags(FLAG_ENABLE_CAMERA_COMPAT_FOR_DESKTOP_WINDOWING)
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_ENABLE_FREEFORM_WINDOWING_TREATMENT})
    public void testOnCameraOpened_landscapeActivity_sandboxesDisplayRotationAndUpdatesApp() throws
            Exception {
        configureActivity(SCREEN_ORIENTATION_LANDSCAPE);
        setDisplayRotation(ROTATION_0);

        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);

        // This is a landscape rotation for a device with portrait natural orientation (most common,
        // currently the only one supported).
        assertCompatibilityInfoSentWithDisplayRotation(ROTATION_90);
    }

    private void configureActivity(@ScreenOrientation int activityOrientation) {
        configureActivity(activityOrientation, WINDOWING_MODE_FREEFORM);
    }

    private void configureActivity(@ScreenOrientation int activityOrientation,
            @WindowingMode int windowingMode) {
        configureActivityAndDisplay(activityOrientation, ORIENTATION_PORTRAIT, windowingMode);
    }

    private void configureActivityAndDisplay(@ScreenOrientation int activityOrientation,
            @Orientation int naturalOrientation, @WindowingMode int windowingMode) {
        final Task task = new TaskBuilder(mSupervisor)
                .setDisplay(mDisplayContent)
                .setWindowingMode(windowingMode)
                .build();

        mActivity = new ActivityBuilder(mAtm)
                // Set the component to be that of the test class in order to enable compat changes
                .setComponent(ComponentName.createRelative(mContext,
                        com.android.server.wm.CameraCompatFreeformPolicyTests.class.getName()))
                .setScreenOrientation(activityOrientation)
                .setTask(task)
                .build();

        spyOn(mActivity.mAppCompatController.getAppCompatCameraOverrides());
        spyOn(mActivity.info);

        doReturn(mActivity).when(mDisplayContent).topRunningActivity(anyBoolean());
        doReturn(naturalOrientation).when(mDisplayContent).getNaturalOrientation();

        doReturn(windowingMode == WINDOWING_MODE_FREEFORM).when(mActivity)
                .inFreeformWindowingMode();
        setupMockApplicationThread();
    }

    private void assertInCameraCompatMode(@CameraCompatTaskInfo.FreeformCameraCompatMode int mode) {
        assertEquals(mode, mCameraCompatFreeformPolicy.getCameraCompatMode(mActivity));
    }

    private void assertNotInCameraCompatMode() {
        assertInCameraCompatMode(CAMERA_COMPAT_FREEFORM_NONE);
    }

    private void assertActivityRefreshRequested(boolean refreshRequested) throws Exception {
        assertActivityRefreshRequested(refreshRequested, /* cycleThroughStop*/ true);
    }

    private void assertActivityRefreshRequested(boolean refreshRequested,
            boolean cycleThroughStop) throws Exception {
        verify(mActivity.mAppCompatController.getAppCompatCameraOverrides(),
                times(refreshRequested ? 1 : 0)).setIsRefreshRequested(true);

        final RefreshCallbackItem refreshCallbackItem =
                new RefreshCallbackItem(mActivity.token, cycleThroughStop ? ON_STOP : ON_PAUSE);
        final ResumeActivityItem resumeActivityItem = new ResumeActivityItem(mActivity.token,
                /* isForward */ false, /* shouldSendCompatFakeFocus */ false);

        verify(mActivity.mAtmService.getLifecycleManager(), times(refreshRequested ? 1 : 0))
                .scheduleTransactionItems(mActivity.app.getThread(),
                        refreshCallbackItem, resumeActivityItem);
    }

    private void callOnActivityConfigurationChanging(ActivityRecord activity) {
        callOnActivityConfigurationChanging(activity, /* letterboxNew= */ true,
                /* lastLetterbox= */false);
    }

    private void callOnActivityConfigurationChanging(ActivityRecord activity, boolean letterboxNew,
            boolean lastLetterbox) {
        mActivityRefresher.onActivityConfigurationChanging(activity,
                /* newConfig */ createConfiguration(letterboxNew),
                /* lastReportedConfig */ createConfiguration(lastLetterbox));
    }

    private Configuration createConfiguration(boolean letterbox) {
        final Configuration configuration = new Configuration();
        Rect bounds = letterbox ? new Rect(/*left*/ 300, /*top*/ 0, /*right*/ 700, /*bottom*/ 600)
                : new Rect(/*left*/ 0, /*top*/ 0, /*right*/ 1000, /*bottom*/ 600);
        configuration.windowConfiguration.setAppBounds(bounds);
        return configuration;
    }

    private void setDisplayRotation(@Surface.Rotation int displayRotation) {
        doAnswer(invocation -> {
            DisplayInfo displayInfo = new DisplayInfo();
            mDisplayContent.getDisplay().getDisplayInfo(displayInfo);
            displayInfo.rotation = displayRotation;
            // Set height so that the natural orientation (rotation is 0) is portrait. This is the
            // case for most standard phones and tablets.
            // TODO(b/365725400): handle landscape natural orientation.
            displayInfo.logicalHeight = displayRotation % 180 == 0 ? 800 : 600;
            displayInfo.logicalWidth = displayRotation % 180 == 0 ? 600 : 800;
            return displayInfo;
        }).when(mDisplayContent.mWmService.mDisplayManagerInternal)
                .getDisplayInfo(anyInt());
    }

    private void setupMockApplicationThread() {
        IApplicationThread mockApplicationThread = mock(IApplicationThread.class);
        spyOn(mActivity.app);
        doReturn(mockApplicationThread).when(mActivity.app).getThread();
    }

    private void assertCompatibilityInfoSentWithDisplayRotation(@Surface.Rotation int
            expectedRotation) throws Exception {
        final ArgumentCaptor<CompatibilityInfo> compatibilityInfoArgumentCaptor =
                ArgumentCaptor.forClass(CompatibilityInfo.class);
        verify(mActivity.app.getThread()).updatePackageCompatibilityInfo(eq(mActivity.packageName),
                compatibilityInfoArgumentCaptor.capture());

        final CompatibilityInfo compatInfo = compatibilityInfoArgumentCaptor.getValue();
        assertTrue(compatInfo.isOverrideDisplayRotationRequired());
        assertEquals(expectedRotation, compatInfo.applicationDisplayRotation);
    }
}

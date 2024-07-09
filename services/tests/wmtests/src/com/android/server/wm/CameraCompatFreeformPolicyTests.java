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

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.servertransaction.ActivityLifecycleItem.ON_PAUSE;
import static android.app.servertransaction.ActivityLifecycleItem.ON_STOP;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.content.pm.ActivityInfo.OVERRIDE_CAMERA_COMPAT_DISABLE_FREEFORM_WINDOWING_TREATMENT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;
import static com.android.window.flags.Flags.FLAG_CAMERA_COMPAT_FOR_FREEFORM;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.CameraCompatTaskInfo;
import android.app.WindowConfiguration.WindowingMode;
import android.app.servertransaction.RefreshCallbackItem;
import android.app.servertransaction.ResumeActivityItem;
import android.compat.testing.PlatformCompatChangeRule;
import android.content.ComponentName;
import android.content.pm.ActivityInfo.ScreenOrientation;
import android.content.res.Configuration;
import android.content.res.Configuration.Orientation;
import android.graphics.Rect;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import libcore.junit.util.compat.CoreCompatChangeRule.EnableCompatChanges;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;

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
    private static final String CAMERA_ID_2 = "camera-2";
    private CameraManager mMockCameraManager;
    private Handler mMockHandler;
    private LetterboxConfiguration mLetterboxConfiguration;

    private CameraManager.AvailabilityCallback mCameraAvailabilityCallback;
    private CameraCompatFreeformPolicy mCameraCompatFreeformPolicy;
    private ActivityRecord mActivity;
    private Task mTask;
    private ActivityRefresher mActivityRefresher;

    @Before
    public void setUp() throws Exception {
        mLetterboxConfiguration = mDisplayContent.mWmService.mLetterboxConfiguration;
        spyOn(mLetterboxConfiguration);
        when(mLetterboxConfiguration.isCameraCompatTreatmentEnabled())
                .thenReturn(true);
        when(mLetterboxConfiguration.isCameraCompatRefreshEnabled())
                .thenReturn(true);
        when(mLetterboxConfiguration.isCameraCompatRefreshCycleThroughStopEnabled())
                .thenReturn(true);

        mMockCameraManager = mock(CameraManager.class);
        doAnswer(invocation -> {
            mCameraAvailabilityCallback = invocation.getArgument(1);
            return null;
        }).when(mMockCameraManager).registerAvailabilityCallback(
                any(Executor.class), any(CameraManager.AvailabilityCallback.class));

        when(mContext.getSystemService(CameraManager.class)).thenReturn(mMockCameraManager);

        mDisplayContent.setIgnoreOrientationRequest(true);

        mMockHandler = mock(Handler.class);

        when(mMockHandler.postDelayed(any(Runnable.class), anyLong())).thenAnswer(
                invocation -> {
                    ((Runnable) invocation.getArgument(0)).run();
                    return null;
                });

        mActivityRefresher = new ActivityRefresher(mDisplayContent.mWmService, mMockHandler);
        mSetFlagsRule.enableFlags(FLAG_CAMERA_COMPAT_FOR_FREEFORM);
        CameraStateMonitor cameraStateMonitor =
                new CameraStateMonitor(mDisplayContent, mMockHandler);
        mCameraCompatFreeformPolicy =
                new CameraCompatFreeformPolicy(mDisplayContent, cameraStateMonitor,
                        mActivityRefresher);

        mCameraCompatFreeformPolicy.start();
        cameraStateMonitor.startListeningToCameraState();
    }

    @Test
    public void testFullscreen_doesNotActivateCameraCompatMode() {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT, WINDOWING_MODE_FULLSCREEN);
        doReturn(false).when(mActivity).inFreeformWindowingMode();

        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);

        assertNotInCameraCompatMode();
    }

    @Test
    public void testOrientationUnspecified_doesNotActivateCameraCompatMode() {
        configureActivity(SCREEN_ORIENTATION_UNSPECIFIED);

        assertNotInCameraCompatMode();
    }

    @Test
    public void testNoCameraConnection_doesNotActivateCameraCompatMode() {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);
        assertNotInCameraCompatMode();
    }

    @Test
    public void testCameraConnected_activatesCameraCompatMode() throws Exception {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);
        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);

        assertInCameraCompatMode();
        assertActivityRefreshRequested(/* refreshRequested */ false);
    }

    @Test
    public void testCameraReconnected_cameraCompatModeAndRefresh() throws Exception {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);

        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);
        mCameraAvailabilityCallback.onCameraClosed(CAMERA_ID_1);
        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);
        callOnActivityConfigurationChanging(mActivity);

        assertInCameraCompatMode();
        assertActivityRefreshRequested(/* refreshRequested */ true);
    }

    @Test
    public void testReconnectedToDifferentCamera_activatesCameraCompatModeAndRefresh()
            throws Exception {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);

        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);
        mCameraAvailabilityCallback.onCameraClosed(CAMERA_ID_1);
        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_2, TEST_PACKAGE_1);
        callOnActivityConfigurationChanging(mActivity);

        assertInCameraCompatMode();
        assertActivityRefreshRequested(/* refreshRequested */ true);
    }

    @Test
    public void testCameraDisconnected_deactivatesCameraCompatMode() {
        configureActivityAndDisplay(SCREEN_ORIENTATION_PORTRAIT, ORIENTATION_LANDSCAPE,
                WINDOWING_MODE_FREEFORM);
        // Open camera and test for compat treatment
        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);
        assertInCameraCompatMode();

        // Close camera and test for revert
        mCameraAvailabilityCallback.onCameraClosed(CAMERA_ID_1);

        assertNotInCameraCompatMode();
    }

    @Test
    public void testCameraOpenedForDifferentPackage_notInCameraCompatMode() {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);

        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_2);

        assertNotInCameraCompatMode();
    }

    @Test
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_DISABLE_FREEFORM_WINDOWING_TREATMENT})
    public void testShouldApplyCameraCompatFreeformTreatment_overrideEnabled_returnsFalse() {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);

        assertTrue(mActivity.info
                .isChangeEnabled(OVERRIDE_CAMERA_COMPAT_DISABLE_FREEFORM_WINDOWING_TREATMENT));
        assertFalse(mCameraCompatFreeformPolicy
                .shouldApplyFreeformTreatmentForCameraCompat(mActivity));
    }

    @Test
    public void testShouldApplyCameraCompatFreeformTreatment_notDisabledByOverride_returnsTrue() {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);

        assertTrue(mCameraCompatFreeformPolicy
                .shouldApplyFreeformTreatmentForCameraCompat(mActivity));
    }

    @Test
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
    public void testOnActivityConfigurationChanging_cycleThroughStopDisabled() throws Exception {
        when(mLetterboxConfiguration.isCameraCompatRefreshCycleThroughStopEnabled())
                .thenReturn(false);

        configureActivity(SCREEN_ORIENTATION_PORTRAIT);

        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);
        callOnActivityConfigurationChanging(mActivity);

        assertActivityRefreshRequested(/* refreshRequested */ true, /* cycleThroughStop */ false);
    }

    @Test
    public void testOnActivityConfigurationChanging_cycleThroughStopDisabledForApp()
            throws Exception {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);
        doReturn(true).when(mActivity.mAppCompatController.getAppCompatCameraOverrides())
                .shouldRefreshActivityViaPauseForCameraCompat();

        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);
        callOnActivityConfigurationChanging(mActivity);

        assertActivityRefreshRequested(/* refreshRequested */ true, /* cycleThroughStop */ false);
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
        mTask = new TaskBuilder(mSupervisor)
                .setDisplay(mDisplayContent)
                .setWindowingMode(windowingMode)
                .build();

        mActivity = new ActivityBuilder(mAtm)
                // Set the component to be that of the test class in order to enable compat changes
                .setComponent(ComponentName.createRelative(mContext,
                        com.android.server.wm.CameraCompatFreeformPolicyTests.class.getName()))
                .setScreenOrientation(activityOrientation)
                .setTask(mTask)
                .build();

        spyOn(mActivity.mLetterboxUiController);
        spyOn(mActivity.mAppCompatController.getAppCompatCameraOverrides());
        spyOn(mActivity.info);

        doReturn(mActivity).when(mDisplayContent).topRunningActivity(anyBoolean());
        doReturn(naturalOrientation).when(mDisplayContent).getNaturalOrientation();

        doReturn(true).when(mActivity).inFreeformWindowingMode();
    }

    private void assertInCameraCompatMode() {
        assertNotEquals(CameraCompatTaskInfo.CAMERA_COMPAT_FREEFORM_NONE,
                mActivity.mAppCompatController.getAppCompatCameraOverrides()
                        .getFreeformCameraCompatMode());
    }

    private void assertNotInCameraCompatMode() {
        assertEquals(CameraCompatTaskInfo.CAMERA_COMPAT_FREEFORM_NONE,
                mActivity.mAppCompatController.getAppCompatCameraOverrides()
                        .getFreeformCameraCompatMode());
    }

    private void assertActivityRefreshRequested(boolean refreshRequested) throws Exception {
        assertActivityRefreshRequested(refreshRequested, /* cycleThroughStop*/ true);
    }

    private void assertActivityRefreshRequested(boolean refreshRequested,
            boolean cycleThroughStop) throws Exception {
        verify(mActivity.mAppCompatController.getAppCompatCameraOverrides(),
                times(refreshRequested ? 1 : 0)).setIsRefreshRequested(true);

        final RefreshCallbackItem refreshCallbackItem = RefreshCallbackItem.obtain(mActivity.token,
                cycleThroughStop ? ON_STOP : ON_PAUSE);
        final ResumeActivityItem resumeActivityItem = ResumeActivityItem.obtain(mActivity.token,
                /* isForward */ false, /* shouldSendCompatFakeFocus */ false);

        verify(mActivity.mAtmService.getLifecycleManager(), times(refreshRequested ? 1 : 0))
                .scheduleTransactionAndLifecycleItems(mActivity.app.getThread(),
                        refreshCallbackItem, resumeActivityItem);
    }

    private void callOnActivityConfigurationChanging(ActivityRecord activity) {
        mActivityRefresher.onActivityConfigurationChanging(activity,
                /* newConfig */ createConfiguration(/*letterbox=*/ true),
                /* lastReportedConfig */ createConfiguration(/*letterbox=*/ false));
    }

    private Configuration createConfiguration(boolean letterbox) {
        final Configuration configuration = new Configuration();
        Rect bounds = letterbox ? new Rect(300, 0, 700, 600) : new Rect(0, 0, 1000, 600);
        configuration.windowConfiguration.setAppBounds(bounds);
        return configuration;
    }
}

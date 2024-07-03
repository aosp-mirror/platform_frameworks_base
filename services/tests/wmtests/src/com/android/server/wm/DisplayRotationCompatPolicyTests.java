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

import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.servertransaction.ActivityLifecycleItem.ON_PAUSE;
import static android.app.servertransaction.ActivityLifecycleItem.ON_STOP;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_NOSENSOR;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_90;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

import android.app.servertransaction.RefreshCallbackItem;
import android.app.servertransaction.ResumeActivityItem;
import android.content.ComponentName;
import android.content.pm.ActivityInfo.ScreenOrientation;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Configuration.Orientation;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.platform.test.annotations.Presubmit;
import android.view.Display;
import android.view.Surface.Rotation;

import androidx.test.filters.SmallTest;

import com.android.internal.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.Executor;

/**
 * Tests for {@link DisplayRotationCompatPolicy}.
 *
 * Build/Install/Run:
 *  atest WmTests:DisplayRotationCompatPolicyTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public final class DisplayRotationCompatPolicyTests extends WindowTestsBase {

    private static final String TEST_PACKAGE_1 = "com.test.package.one";
    private static final String TEST_PACKAGE_2 = "com.test.package.two";
    private static final String CAMERA_ID_1 = "camera-1";
    private static final String CAMERA_ID_2 = "camera-2";
    private static final String TEST_PACKAGE_1_LABEL = "testPackage1";
    private CameraManager mMockCameraManager;
    private Handler mMockHandler;
    private LetterboxConfiguration mLetterboxConfiguration;
    private ActivityRefresher mActivityRefresher;

    private DisplayRotationCompatPolicy mDisplayRotationCompatPolicy;
    private CameraManager.AvailabilityCallback mCameraAvailabilityCallback;

    private ActivityRecord mActivity;
    private Task mTask;

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

        spyOn(mContext);
        when(mContext.getSystemService(CameraManager.class)).thenReturn(mMockCameraManager);

        spyOn(mDisplayContent);

        mDisplayContent.setIgnoreOrientationRequest(true);

        mMockHandler = mock(Handler.class);

        when(mMockHandler.postDelayed(any(Runnable.class), anyLong())).thenAnswer(
                invocation -> {
                    ((Runnable) invocation.getArgument(0)).run();
                    return null;
                });
        CameraStateMonitor cameraStateMonitor =
                new CameraStateMonitor(mDisplayContent, mMockHandler);
        mActivityRefresher = new ActivityRefresher(mDisplayContent.mWmService, mMockHandler);
        mDisplayRotationCompatPolicy = new DisplayRotationCompatPolicy(mDisplayContent,
                cameraStateMonitor, mActivityRefresher);

        // Do not show the real toast.
        spyOn(mDisplayRotationCompatPolicy);
        doNothing().when(mDisplayRotationCompatPolicy).showToast(anyInt());
        doNothing().when(mDisplayRotationCompatPolicy).showToast(anyInt(), anyString());

        mDisplayRotationCompatPolicy.start();
        cameraStateMonitor.startListeningToCameraState();
    }

    @Test
    public void testOpenedCameraInSplitScreen_showToast() throws Exception {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);
        spyOn(mTask);
        doReturn(WINDOWING_MODE_MULTI_WINDOW).when(mActivity).getWindowingMode();
        doReturn(WINDOWING_MODE_MULTI_WINDOW).when(mTask).getWindowingMode();

        final PackageManager mockPackageManager = mock(PackageManager.class);
        final ApplicationInfo mockApplicationInfo = mock(ApplicationInfo.class);
        when(mContext.getPackageManager()).thenReturn(mockPackageManager);
        when(mockPackageManager.getApplicationInfo(anyString(), anyInt()))
                .thenReturn(mockApplicationInfo);

        doReturn(TEST_PACKAGE_1_LABEL).when(mockPackageManager)
                .getApplicationLabel(mockApplicationInfo);

        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);

        verify(mDisplayRotationCompatPolicy).showToast(
                R.string.display_rotation_camera_compat_toast_in_multi_window,
                TEST_PACKAGE_1_LABEL);
    }

    @Test
    public void testOpenedCameraInSplitScreen_orientationNotFixed_doNotShowToast() {
        configureActivity(SCREEN_ORIENTATION_UNSPECIFIED);
        spyOn(mTask);
        doReturn(WINDOWING_MODE_MULTI_WINDOW).when(mActivity).getWindowingMode();
        doReturn(WINDOWING_MODE_MULTI_WINDOW).when(mTask).getWindowingMode();

        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);

        verify(mDisplayRotationCompatPolicy, never()).showToast(
                R.string.display_rotation_camera_compat_toast_in_multi_window,
                TEST_PACKAGE_1_LABEL);
    }

    @Test
    public void testOnScreenRotationAnimationFinished_treatmentNotEnabled_doNotShowToast() {
        when(mLetterboxConfiguration.isCameraCompatTreatmentEnabled())
                .thenReturn(false);

        mDisplayRotationCompatPolicy.onScreenRotationAnimationFinished();

        verify(mDisplayRotationCompatPolicy, never()).showToast(
                R.string.display_rotation_camera_compat_toast_after_rotation);
    }

    @Test
    public void testOnScreenRotationAnimationFinished_noOpenCamera_doNotShowToast() {
        mDisplayRotationCompatPolicy.onScreenRotationAnimationFinished();

        verify(mDisplayRotationCompatPolicy, never()).showToast(
                R.string.display_rotation_camera_compat_toast_after_rotation);
    }

    @Test
    public void testOnScreenRotationAnimationFinished_notFullscreen_doNotShowToast() {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);
        doReturn(true).when(mActivity).inMultiWindowMode();

        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);

        mDisplayRotationCompatPolicy.onScreenRotationAnimationFinished();

        verify(mDisplayRotationCompatPolicy, never()).showToast(
                R.string.display_rotation_camera_compat_toast_after_rotation);
    }

    @Test
    public void testOnScreenRotationAnimationFinished_orientationNotFixed_doNotShowToast() {
        configureActivity(SCREEN_ORIENTATION_UNSPECIFIED);
        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);

        mDisplayRotationCompatPolicy.onScreenRotationAnimationFinished();

        verify(mDisplayRotationCompatPolicy, never()).showToast(
                R.string.display_rotation_camera_compat_toast_after_rotation);
    }

    @Test
    public void testOnScreenRotationAnimationFinished_showToast() {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);
        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);

        mDisplayRotationCompatPolicy.onScreenRotationAnimationFinished();

        verify(mDisplayRotationCompatPolicy).showToast(
                R.string.display_rotation_camera_compat_toast_after_rotation);
    }

    @Test
    public void testTreatmentNotEnabled_noForceRotationOrRefresh() throws Exception {
        when(mLetterboxConfiguration.isCameraCompatTreatmentEnabled())
                .thenReturn(false);

        configureActivity(SCREEN_ORIENTATION_PORTRAIT);
        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);

        assertEquals(mDisplayRotationCompatPolicy.getOrientation(),
                SCREEN_ORIENTATION_UNSPECIFIED);

        assertNoForceRotationOrRefresh();
    }

    @Test
    public void testTreatmentDisabledViaDeviceConfig_noForceRotationOrRefresh() throws Exception {
        when(mLetterboxConfiguration.isCameraCompatTreatmentEnabled())
                .thenReturn(false);

        configureActivity(SCREEN_ORIENTATION_PORTRAIT);
        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);

        assertNoForceRotationOrRefresh();
    }

    @Test
    public void testTreatmentDisabledPerApp_noForceRotationOrRefresh()
            throws Exception {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);
        doReturn(false).when(mActivity.mAppCompatController.getAppCompatCameraOverrides())
                .shouldForceRotateForCameraCompat();

        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);

        assertNoForceRotationOrRefresh();
    }

    @Test
    public void testMultiWindowMode_returnUnspecified_noForceRotationOrRefresh() throws Exception {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);
        final TestSplitOrganizer organizer = new TestSplitOrganizer(mAtm, mDisplayContent);
        mActivity.getTask().reparent(organizer.mPrimary, WindowContainer.POSITION_TOP,
                false /* moveParents */, "test" /* reason */);

        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);

        assertTrue(mActivity.inMultiWindowMode());
        assertNoForceRotationOrRefresh();
    }

    @Test
    public void testOrientationUnspecified_noForceRotationOrRefresh() throws Exception {
        configureActivity(SCREEN_ORIENTATION_UNSPECIFIED);

        assertNoForceRotationOrRefresh();
    }

    @Test
    public void testOrientationLocked_noForceRotationOrRefresh() throws Exception {
        configureActivity(SCREEN_ORIENTATION_LOCKED);

        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);

        assertNoForceRotationOrRefresh();
    }

    @Test
    public void testOrientationNoSensor_noForceRotationOrRefresh() throws Exception {
        configureActivity(SCREEN_ORIENTATION_NOSENSOR);

        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);

        assertNoForceRotationOrRefresh();
    }

    @Test
    public void testIgnoreOrientationRequestIsFalse_noForceRotationOrRefresh() throws Exception {
        mDisplayContent.setIgnoreOrientationRequest(false);

        configureActivity(SCREEN_ORIENTATION_PORTRAIT);
        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);

        assertNoForceRotationOrRefresh();
    }

    @Test
    public void testDisplayNotInternal_noForceRotationOrRefresh() throws Exception {
        Display display = mDisplayContent.getDisplay();
        spyOn(display);

        configureActivity(SCREEN_ORIENTATION_PORTRAIT);
        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);

        when(display.getType()).thenReturn(Display.TYPE_EXTERNAL);
        assertNoForceRotationOrRefresh();

        when(display.getType()).thenReturn(Display.TYPE_WIFI);
        assertNoForceRotationOrRefresh();

        when(display.getType()).thenReturn(Display.TYPE_OVERLAY);
        assertNoForceRotationOrRefresh();

        when(display.getType()).thenReturn(Display.TYPE_VIRTUAL);
        assertNoForceRotationOrRefresh();
    }

    @Test
    public void testNoCameraConnection_noForceRotationOrRefresh() throws Exception {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);

        assertNoForceRotationOrRefresh();
    }

    @Test
    public void testCameraReconnected_forceRotationAndRefresh() throws Exception {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);

        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);
        mCameraAvailabilityCallback.onCameraClosed(CAMERA_ID_1);
        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);
        callOnActivityConfigurationChanging(mActivity, /* isDisplayRotationChanging */ true);

        assertEquals(mDisplayRotationCompatPolicy.getOrientation(),
                SCREEN_ORIENTATION_PORTRAIT);
        assertActivityRefreshRequested(/* refreshRequested */ true);
    }

    @Test
    public void testReconnectedToDifferentCamera_forceRotationAndRefresh() throws Exception {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);

        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);
        mCameraAvailabilityCallback.onCameraClosed(CAMERA_ID_1);
        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_2, TEST_PACKAGE_1);
        callOnActivityConfigurationChanging(mActivity, /* isDisplayRotationChanging */ true);

        assertEquals(mDisplayRotationCompatPolicy.getOrientation(),
                SCREEN_ORIENTATION_PORTRAIT);
        assertActivityRefreshRequested(/* refreshRequested */ true);
    }

    @Test
    public void testCameraDisconnected_revertRotationAndRefresh() throws Exception {
        configureActivityAndDisplay(SCREEN_ORIENTATION_PORTRAIT, ORIENTATION_LANDSCAPE);
        // Open camera and test for compat treatment
        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);
        callOnActivityConfigurationChanging(mActivity, /* isDisplayRotationChanging */ true);
        assertEquals(mDisplayRotationCompatPolicy.getOrientation(),
                SCREEN_ORIENTATION_LANDSCAPE);
        assertActivityRefreshRequested(/* refreshRequested */ true);
        // Close camera and test for revert
        mCameraAvailabilityCallback.onCameraClosed(CAMERA_ID_1);
        callOnActivityConfigurationChanging(mActivity, /* isDisplayRotationChanging */ true);
        assertEquals(mDisplayRotationCompatPolicy.getOrientation(),
                SCREEN_ORIENTATION_UNSPECIFIED);
        assertActivityRefreshRequested(/* refreshRequested */ true);
    }

    @Test
    public void testGetOrientation_cameraConnectionClosed_returnUnspecified() {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);

        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);

        assertEquals(mDisplayRotationCompatPolicy.getOrientation(),
                SCREEN_ORIENTATION_PORTRAIT);

        mCameraAvailabilityCallback.onCameraClosed(CAMERA_ID_1);

        assertEquals(mDisplayRotationCompatPolicy.getOrientation(),
                SCREEN_ORIENTATION_UNSPECIFIED);
    }

    @Test
    public void testCameraOpenedForDifferentPackage_noForceRotationOrRefresh() throws Exception {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);

        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_2);

        assertNoForceRotationOrRefresh();
    }

    @Test
    public void testGetOrientation_portraitActivity_portraitNaturalOrientation_returnPortrait() {
        testGetOrientationForActivityAndNaturalOrientations(
                /* activityOrientation */ SCREEN_ORIENTATION_PORTRAIT,
                /* naturalOrientation */ ORIENTATION_PORTRAIT,
                /* expectedOrientation */ SCREEN_ORIENTATION_PORTRAIT);
    }

    @Test
    public void testGetOrientation_portraitActivity_landscapeNaturalOrientation_returnLandscape() {
        testGetOrientationForActivityAndNaturalOrientations(
                /* activityOrientation */ SCREEN_ORIENTATION_PORTRAIT,
                /* naturalOrientation */ ORIENTATION_LANDSCAPE,
                /* expectedOrientation */ SCREEN_ORIENTATION_LANDSCAPE);
    }

    @Test
    public void testGetOrientation_landscapeActivity_portraitNaturalOrientation_returnLandscape() {
        testGetOrientationForActivityAndNaturalOrientations(
                /* activityOrientation */ SCREEN_ORIENTATION_LANDSCAPE,
                /* naturalOrientation */ ORIENTATION_PORTRAIT,
                /* expectedOrientation */ SCREEN_ORIENTATION_LANDSCAPE);
    }

    @Test
    public void testGetOrientation_landscapeActivity_landscapeNaturalOrientation_returnPortrait() {
        testGetOrientationForActivityAndNaturalOrientations(
                /* activityOrientation */ SCREEN_ORIENTATION_LANDSCAPE,
                /* naturalOrientation */ ORIENTATION_LANDSCAPE,
                /* expectedOrientation */ SCREEN_ORIENTATION_PORTRAIT);
    }

    private void testGetOrientationForActivityAndNaturalOrientations(
            @ScreenOrientation int activityOrientation,
            @Orientation int naturalOrientation,
            @ScreenOrientation int expectedOrientation) {
        configureActivityAndDisplay(activityOrientation, naturalOrientation);

        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);

        assertEquals(mDisplayRotationCompatPolicy.getOrientation(),
                expectedOrientation);
    }

    @Test
    public void testOnActivityConfigurationChanging_refreshDisabledViaFlag_noRefresh()
            throws Exception {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);

        doReturn(false).when(mActivity.mAppCompatController.getAppCompatCameraOverrides())
                .shouldRefreshActivityForCameraCompat();

        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);
        callOnActivityConfigurationChanging(mActivity, /* isDisplayRotationChanging */ true);

        assertActivityRefreshRequested(/* refreshRequested */ false);
    }

    @Test
    public void testOnActivityConfigurationChanging_refreshDisabledPerApp_noRefresh()
            throws Exception {
        when(mLetterboxConfiguration.isCameraCompatRefreshEnabled()).thenReturn(false);

        configureActivity(SCREEN_ORIENTATION_PORTRAIT);

        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);
        callOnActivityConfigurationChanging(mActivity, /* isDisplayRotationChanging */ true);

        assertActivityRefreshRequested(/* refreshRequested */ false);
    }

    @Test
    public void testOnActivityConfigurationChanging_displayRotationNotChanging_noRefresh()
            throws Exception {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);
        doReturn(false).when(mActivity
                        .mAppCompatController.getAppCompatCameraOverrides())
                            .isCameraCompatSplitScreenAspectRatioAllowed();

        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);
        callOnActivityConfigurationChanging(mActivity, /* isDisplayRotationChanging */ false);

        assertActivityRefreshRequested(/* refreshRequested */ false);
    }

    @Test
    public void testOnActivityConfigurationChanging_splitScreenAspectRatioAllowed_refresh()
            throws Exception {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);
        doReturn(true).when(mActivity.mAppCompatController.getAppCompatCameraOverrides())
                .isCameraCompatSplitScreenAspectRatioAllowed();

        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);
        callOnActivityConfigurationChanging(mActivity, /* isDisplayRotationChanging */ false);

        assertActivityRefreshRequested(/* refreshRequested */ true);
    }

    @Test
    public void testOnActivityConfigurationChanging_cycleThroughStopDisabled() throws Exception {
        when(mLetterboxConfiguration.isCameraCompatRefreshCycleThroughStopEnabled())
                .thenReturn(false);

        configureActivity(SCREEN_ORIENTATION_PORTRAIT);

        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);
        callOnActivityConfigurationChanging(mActivity, /* isDisplayRotationChanging */ true);

        assertActivityRefreshRequested(/* refreshRequested */ true, /* cycleThroughStop */ false);
    }

    @Test
    public void testOnActivityConfigurationChanging_cycleThroughStopDisabledForApp()
            throws Exception {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);
        doReturn(true).when(mActivity.mAppCompatController.getAppCompatCameraOverrides())
                .shouldRefreshActivityViaPauseForCameraCompat();

        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);
        callOnActivityConfigurationChanging(mActivity, /* isDisplayRotationChanging */ true);

        assertActivityRefreshRequested(/* refreshRequested */ true, /* cycleThroughStop */ false);
    }

    @Test
    public void testIsCameraActiveWhenCallbackInvokedNoMultiWindow_returnTrue() {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);
        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);

        assertTrue(
                mDisplayRotationCompatPolicy.isCameraActive(mActivity, /* mustBeFullscreen*/ true));
    }

    @Test
    public void testIsCameraActiveWhenCallbackNotInvokedNoMultiWindow_returnFalse() {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);

        assertFalse(
                mDisplayRotationCompatPolicy.isCameraActive(mActivity, /* mustBeFullscreen*/ true));
    }

    @Test
    public void testIsCameraActiveWhenCallbackNotInvokedMultiWindow_returnFalse() {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);
        when(mActivity.inMultiWindowMode()).thenReturn(true);

        assertFalse(
                mDisplayRotationCompatPolicy.isCameraActive(mActivity, /* mustBeFullscreen*/ true));
    }

    @Test
    public void testIsCameraActiveWhenCallbackInvokedMultiWindow_returnFalse() {
        configureActivity(SCREEN_ORIENTATION_PORTRAIT);
        when(mActivity.inMultiWindowMode()).thenReturn(true);
        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);

        assertFalse(
                mDisplayRotationCompatPolicy.isCameraActive(mActivity, /* mustBeFullscreen*/ true));
    }

    private void configureActivity(@ScreenOrientation int activityOrientation) {
        configureActivityAndDisplay(activityOrientation, ORIENTATION_PORTRAIT);
    }

    private void configureActivityAndDisplay(@ScreenOrientation int activityOrientation,
            @Orientation int naturalOrientation) {

        mTask = new TaskBuilder(mSupervisor)
                .setDisplay(mDisplayContent)
                .build();

        mActivity = new ActivityBuilder(mAtm)
                .setComponent(new ComponentName(TEST_PACKAGE_1, ".TestActivity"))
                .setScreenOrientation(activityOrientation)
                .setTask(mTask)
                .build();

        spyOn(mActivity.mAtmService.getLifecycleManager());
        spyOn(mActivity.mLetterboxUiController);
        spyOn(mActivity.mAppCompatController.getAppCompatCameraOverrides());

        doReturn(mActivity).when(mDisplayContent).topRunningActivity(anyBoolean());
        doReturn(naturalOrientation).when(mDisplayContent).getNaturalOrientation();
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

    private void assertNoForceRotationOrRefresh() throws Exception {
        callOnActivityConfigurationChanging(mActivity, /* isDisplayRotationChanging */ true);

        assertEquals(mDisplayRotationCompatPolicy.getOrientation(),
                SCREEN_ORIENTATION_UNSPECIFIED);
        assertActivityRefreshRequested(/* refreshRequested */ false);
    }

    private void callOnActivityConfigurationChanging(
            ActivityRecord activity, boolean isDisplayRotationChanging) {
        mActivityRefresher.onActivityConfigurationChanging(activity,
                /* newConfig */ createConfigurationWithDisplayRotation(ROTATION_0),
                /* newConfig */ createConfigurationWithDisplayRotation(
                        isDisplayRotationChanging ? ROTATION_90 : ROTATION_0));
    }

    private static Configuration createConfigurationWithDisplayRotation(@Rotation int rotation) {
        final Configuration config = new Configuration();
        config.windowConfiguration.setDisplayRotation(rotation);
        return config;
    }
}

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

package com.android.server.companion.virtual;

import static android.hardware.camera2.CameraInjectionSession.InjectionStatusCallback.ERROR_INJECTION_UNSUPPORTED;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.UserInfo;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraInjectionSession;
import android.hardware.camera2.CameraManager;
import android.os.Process;
import android.os.UserManager;
import android.platform.test.annotations.Presubmit;
import android.testing.TestableContext;
import android.util.ArraySet;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.server.LocalServices;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class CameraAccessControllerTest {
    private static final String FRONT_CAMERA = "0";
    private static final String REAR_CAMERA = "1";
    private static final String TEST_APP_PACKAGE = "some.package";
    private static final String OTHER_APP_PACKAGE = "other.package";
    private static final int PERSONAL_PROFILE_USER_ID = 0;
    private static final int WORK_PROFILE_USER_ID = 10;

    private CameraAccessController mController;

    @Rule
    public final TestableContext mContext = new TestableContext(
            InstrumentationRegistry.getContext());

    @Mock
    private CameraManager mCameraManager;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private UserManager mUserManager;
    @Mock
    private VirtualDeviceManagerInternal mDeviceManagerInternal;
    @Mock
    private CameraAccessController.CameraAccessBlockedCallback mBlockedCallback;

    private ApplicationInfo mTestAppInfo = new ApplicationInfo();
    private ApplicationInfo mOtherAppInfo = new ApplicationInfo();
    private ArraySet<Integer> mRunningUids = new ArraySet<>();
    private List<UserInfo> mAliveUsers = new ArrayList<>();

    @Captor
    ArgumentCaptor<CameraInjectionSession.InjectionStatusCallback> mInjectionCallbackCaptor;

    @Before
    public void setUp() throws PackageManager.NameNotFoundException {
        MockitoAnnotations.initMocks(this);
        mContext.addMockSystemService(CameraManager.class, mCameraManager);
        mContext.addMockSystemService(UserManager.class, mUserManager);
        mContext.setMockPackageManager(mPackageManager);
        LocalServices.removeServiceForTest(VirtualDeviceManagerInternal.class);
        LocalServices.addService(VirtualDeviceManagerInternal.class, mDeviceManagerInternal);
        mController = new CameraAccessController(mContext, mDeviceManagerInternal,
                mBlockedCallback);
        mTestAppInfo.uid = Process.FIRST_APPLICATION_UID;
        mOtherAppInfo.uid = Process.FIRST_APPLICATION_UID + 1;
        mRunningUids.add(Process.FIRST_APPLICATION_UID);
        mAliveUsers.add(new UserInfo(PERSONAL_PROFILE_USER_ID, "", 0));
        when(mPackageManager.getApplicationInfoAsUser(
                eq(TEST_APP_PACKAGE), eq(PackageManager.GET_ACTIVITIES),
                anyInt())).thenReturn(mTestAppInfo);
        when(mPackageManager.getApplicationInfoAsUser(
                eq(OTHER_APP_PACKAGE), eq(PackageManager.GET_ACTIVITIES),
                anyInt())).thenReturn(mOtherAppInfo);
        when(mUserManager.getAliveUsers()).thenReturn(mAliveUsers);
        mController.startObservingIfNeeded();
    }

    @Test
    public void getUserId_returnsCorrectId() {
        assertThat(mController.getUserId()).isEqualTo(mContext.getUserId());
    }

    @Test
    public void onCameraOpened_uidNotRunning_noCameraBlocking() throws CameraAccessException {
        when(mDeviceManagerInternal.isAppRunningOnAnyVirtualDevice(
                eq(mTestAppInfo.uid))).thenReturn(false);
        mController.onCameraOpened(FRONT_CAMERA, TEST_APP_PACKAGE);
        verify(mCameraManager, never()).injectCamera(any(), any(), any(), any(), any());
    }

    @Test
    public void onCameraOpened_uidRunning_cameraBlocked() throws CameraAccessException {
        when(mDeviceManagerInternal.isAppRunningOnAnyVirtualDevice(
                eq(mTestAppInfo.uid))).thenReturn(true);
        mController.onCameraOpened(FRONT_CAMERA, TEST_APP_PACKAGE);
        verify(mCameraManager).injectCamera(eq(TEST_APP_PACKAGE), eq(FRONT_CAMERA), anyString(),
                any(), any());
    }

    @Test
    public void onCameraClosed_injectionWasActive_cameraUnblocked() throws CameraAccessException {
        when(mDeviceManagerInternal.isAppRunningOnAnyVirtualDevice(
                eq(mTestAppInfo.uid))).thenReturn(true);
        mController.onCameraOpened(FRONT_CAMERA, TEST_APP_PACKAGE);
        verify(mCameraManager).injectCamera(eq(TEST_APP_PACKAGE), eq(FRONT_CAMERA), anyString(),
                any(), mInjectionCallbackCaptor.capture());
        CameraInjectionSession session = mock(CameraInjectionSession.class);
        mInjectionCallbackCaptor.getValue().onInjectionSucceeded(session);

        mController.onCameraClosed(FRONT_CAMERA);
        verify(session).close();
    }

    @Test
    public void onCameraClosed_otherCameraClosed_cameraNotUnblocked() throws CameraAccessException {
        when(mDeviceManagerInternal.isAppRunningOnAnyVirtualDevice(
                eq(mTestAppInfo.uid))).thenReturn(true);
        mController.onCameraOpened(FRONT_CAMERA, TEST_APP_PACKAGE);
        verify(mCameraManager).injectCamera(eq(TEST_APP_PACKAGE), eq(FRONT_CAMERA), anyString(),
                any(), mInjectionCallbackCaptor.capture());
        CameraInjectionSession session = mock(CameraInjectionSession.class);
        mInjectionCallbackCaptor.getValue().onInjectionSucceeded(session);

        mController.onCameraClosed(REAR_CAMERA);
        verify(session, never()).close();
    }

    @Test
    public void onCameraClosed_twoCamerasBlocked_correctCameraUnblocked()
            throws CameraAccessException {
        when(mDeviceManagerInternal.isAppRunningOnAnyVirtualDevice(
                eq(mTestAppInfo.uid))).thenReturn(true);
        when(mDeviceManagerInternal.isAppRunningOnAnyVirtualDevice(
                eq(mOtherAppInfo.uid))).thenReturn(true);

        mController.onCameraOpened(FRONT_CAMERA, TEST_APP_PACKAGE);
        mController.onCameraOpened(REAR_CAMERA, OTHER_APP_PACKAGE);

        verify(mCameraManager).injectCamera(eq(TEST_APP_PACKAGE), eq(FRONT_CAMERA), anyString(),
                any(), mInjectionCallbackCaptor.capture());
        CameraInjectionSession frontCameraSession = mock(CameraInjectionSession.class);
        mInjectionCallbackCaptor.getValue().onInjectionSucceeded(frontCameraSession);

        verify(mCameraManager).injectCamera(eq(OTHER_APP_PACKAGE), eq(REAR_CAMERA), anyString(),
                any(), mInjectionCallbackCaptor.capture());
        CameraInjectionSession rearCameraSession = mock(CameraInjectionSession.class);
        mInjectionCallbackCaptor.getValue().onInjectionSucceeded(rearCameraSession);

        mController.onCameraClosed(REAR_CAMERA);
        verify(frontCameraSession, never()).close();
        verify(rearCameraSession).close();
    }

    @Test
    public void onInjectionError_callbackFires() throws CameraAccessException {
        when(mDeviceManagerInternal.isAppRunningOnAnyVirtualDevice(
                eq(mTestAppInfo.uid))).thenReturn(true);
        mController.onCameraOpened(FRONT_CAMERA, TEST_APP_PACKAGE);
        verify(mCameraManager).injectCamera(eq(TEST_APP_PACKAGE), eq(FRONT_CAMERA), anyString(),
                any(), mInjectionCallbackCaptor.capture());
        CameraInjectionSession session = mock(CameraInjectionSession.class);
        mInjectionCallbackCaptor.getValue().onInjectionSucceeded(session);
        mInjectionCallbackCaptor.getValue().onInjectionError(ERROR_INJECTION_UNSUPPORTED);
        verify(mBlockedCallback).onCameraAccessBlocked(eq(mTestAppInfo.uid));
    }

    @Test
    public void twoCameraAccesses_onlyOneOnVirtualDisplay_callbackFiresForCorrectUid()
            throws CameraAccessException {
        when(mDeviceManagerInternal.isAppRunningOnAnyVirtualDevice(
                eq(mTestAppInfo.uid))).thenReturn(true);
        mController.onCameraOpened(FRONT_CAMERA, TEST_APP_PACKAGE);
        mController.onCameraOpened(REAR_CAMERA, OTHER_APP_PACKAGE);

        verify(mCameraManager).injectCamera(eq(TEST_APP_PACKAGE), eq(FRONT_CAMERA), anyString(),
                any(), mInjectionCallbackCaptor.capture());
        CameraInjectionSession session = mock(CameraInjectionSession.class);
        mInjectionCallbackCaptor.getValue().onInjectionSucceeded(session);
        mInjectionCallbackCaptor.getValue().onInjectionError(ERROR_INJECTION_UNSUPPORTED);
        verify(mBlockedCallback).onCameraAccessBlocked(eq(mTestAppInfo.uid));
    }

    @Test
    public void twoCameraAccessesBySameUid_secondOnVirtualDisplay_noCallbackButCameraCanBlocked()
            throws CameraAccessException {
        when(mDeviceManagerInternal.isAppRunningOnAnyVirtualDevice(
                eq(mTestAppInfo.uid))).thenReturn(false);
        mController.onCameraOpened(FRONT_CAMERA, TEST_APP_PACKAGE);
        mController.blockCameraAccessIfNeeded(mRunningUids);

        verify(mCameraManager).injectCamera(eq(TEST_APP_PACKAGE), eq(FRONT_CAMERA), anyString(),
                any(), mInjectionCallbackCaptor.capture());
        CameraInjectionSession session = mock(CameraInjectionSession.class);
        mInjectionCallbackCaptor.getValue().onInjectionSucceeded(session);
        mInjectionCallbackCaptor.getValue().onInjectionError(ERROR_INJECTION_UNSUPPORTED);
        verify(mBlockedCallback).onCameraAccessBlocked(eq(mTestAppInfo.uid));
    }

    @Test
    public void twoCameraAccessesBySameUid_secondOnVirtualDisplay_firstCloseThenOpenCameraUnblock()
            throws CameraAccessException {
        when(mDeviceManagerInternal.isAppRunningOnAnyVirtualDevice(
                eq(mTestAppInfo.uid))).thenReturn(false);
        mController.onCameraOpened(FRONT_CAMERA, TEST_APP_PACKAGE);
        mController.blockCameraAccessIfNeeded(mRunningUids);
        mController.onCameraClosed(FRONT_CAMERA);
        mController.onCameraOpened(FRONT_CAMERA, TEST_APP_PACKAGE);

        verify(mCameraManager, times(1)).injectCamera(any(), any(), any(), any(), any());
    }

    @Test
    public void multipleUsers_getPersonalProfileAppUid_cameraBlocked()
            throws CameraAccessException, NameNotFoundException {
        mAliveUsers.add(new UserInfo(WORK_PROFILE_USER_ID, "", 0));
        when(mPackageManager.getApplicationInfoAsUser(
                eq(TEST_APP_PACKAGE), eq(PackageManager.GET_ACTIVITIES),
                eq(PERSONAL_PROFILE_USER_ID))).thenReturn(mTestAppInfo);
        when(mPackageManager.getApplicationInfoAsUser(
                eq(TEST_APP_PACKAGE), eq(PackageManager.GET_ACTIVITIES),
                eq(WORK_PROFILE_USER_ID))).thenThrow(NameNotFoundException.class);
        when(mDeviceManagerInternal.isAppRunningOnAnyVirtualDevice(
                eq(mTestAppInfo.uid))).thenReturn(true);
        mController.onCameraOpened(FRONT_CAMERA, TEST_APP_PACKAGE);

        verify(mCameraManager).injectCamera(eq(TEST_APP_PACKAGE), eq(FRONT_CAMERA), anyString(),
                any(), any());
    }

    @Test
    public void multipleUsers_getPersonalProfileAppUid_noCameraBlocking()
            throws CameraAccessException, NameNotFoundException {
        mAliveUsers.add(new UserInfo(WORK_PROFILE_USER_ID, "", 0));
        when(mPackageManager.getApplicationInfoAsUser(
                eq(TEST_APP_PACKAGE), eq(PackageManager.GET_ACTIVITIES),
                eq(PERSONAL_PROFILE_USER_ID))).thenReturn(mTestAppInfo);
        when(mPackageManager.getApplicationInfoAsUser(
                eq(TEST_APP_PACKAGE), eq(PackageManager.GET_ACTIVITIES),
                eq(WORK_PROFILE_USER_ID))).thenThrow(NameNotFoundException.class);
        when(mDeviceManagerInternal.isAppRunningOnAnyVirtualDevice(
                eq(mTestAppInfo.uid))).thenReturn(false);
        mController.onCameraOpened(FRONT_CAMERA, TEST_APP_PACKAGE);

        verify(mCameraManager, never()).injectCamera(any(), any(), any(), any(), any());
    }

    @Test
    public void multipleUsers_getWorkProfileAppUid_cameraBlocked()
            throws CameraAccessException, NameNotFoundException {
        mAliveUsers.add(new UserInfo(WORK_PROFILE_USER_ID, "", 0));
        when(mPackageManager.getApplicationInfoAsUser(
                eq(TEST_APP_PACKAGE), eq(PackageManager.GET_ACTIVITIES),
                eq(PERSONAL_PROFILE_USER_ID))).thenThrow(NameNotFoundException.class);
        when(mPackageManager.getApplicationInfoAsUser(
                eq(TEST_APP_PACKAGE), eq(PackageManager.GET_ACTIVITIES),
                eq(WORK_PROFILE_USER_ID))).thenReturn(mTestAppInfo);
        when(mDeviceManagerInternal.isAppRunningOnAnyVirtualDevice(
                eq(mTestAppInfo.uid))).thenReturn(true);
        mController.onCameraOpened(FRONT_CAMERA, TEST_APP_PACKAGE);

        verify(mCameraManager).injectCamera(eq(TEST_APP_PACKAGE), eq(FRONT_CAMERA), anyString(),
                any(), any());
    }

    @Test
    public void multipleUsers_getWorkProfileAppUid_noCameraBlocking()
            throws CameraAccessException, NameNotFoundException {
        mAliveUsers.add(new UserInfo(WORK_PROFILE_USER_ID, "", 0));
        when(mPackageManager.getApplicationInfoAsUser(
                eq(TEST_APP_PACKAGE), eq(PackageManager.GET_ACTIVITIES),
                eq(PERSONAL_PROFILE_USER_ID))).thenThrow(NameNotFoundException.class);
        when(mPackageManager.getApplicationInfoAsUser(
                eq(TEST_APP_PACKAGE), eq(PackageManager.GET_ACTIVITIES),
                eq(WORK_PROFILE_USER_ID))).thenReturn(mTestAppInfo);
        when(mDeviceManagerInternal.isAppRunningOnAnyVirtualDevice(
                eq(mTestAppInfo.uid))).thenReturn(false);
        mController.onCameraOpened(FRONT_CAMERA, TEST_APP_PACKAGE);

        verify(mCameraManager, never()).injectCamera(any(), any(), any(), any(), any());
    }
}

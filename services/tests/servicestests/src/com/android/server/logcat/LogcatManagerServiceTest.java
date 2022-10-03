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

package com.android.server.logcat;

import static android.os.Process.INVALID_UID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.ILogd;
import android.os.Looper;
import android.os.UserHandle;
import android.os.test.TestLooper;

import androidx.test.core.app.ApplicationProvider;

import com.android.server.LocalServices;
import com.android.server.logcat.LogcatManagerService.Injector;
import com.android.server.testutils.OffsettableClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.function.Supplier;

/**
 * Tests for {@link com.android.server.logcat.LogcatManagerService}.
 *
 * Build/Install/Run:
 * atest FrameworksServicesTests:LogcatManagerServiceTest
 */
@SuppressWarnings("GuardedBy")
public class LogcatManagerServiceTest {
    private static final String APP1_PACKAGE_NAME = "app1";
    private static final int APP1_UID = 10001;
    private static final int APP1_GID = 10001;
    private static final int APP1_PID = 10001;
    private static final String APP2_PACKAGE_NAME = "app2";
    private static final int APP2_UID = 10002;
    private static final int APP2_GID = 10002;
    private static final int APP2_PID = 10002;
    private static final int FD1 = 10;
    private static final int FD2 = 11;

    @Mock
    private ActivityManagerInternal mActivityManagerInternalMock;
    @Mock
    private PackageManager mPackageManagerMock;
    @Mock
    private ILogd mLogdMock;

    private LogcatManagerService mService;
    private LogcatManagerService.LogAccessDialogCallback mDialogCallback;
    private ContextWrapper mContextSpy;
    private OffsettableClock mClock;
    private TestLooper mTestLooper;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        addLocalServiceMock(ActivityManagerInternal.class, mActivityManagerInternalMock);
        when(mActivityManagerInternalMock.getInstrumentationSourceUid(anyInt()))
                .thenReturn(INVALID_UID);

        mContextSpy = spy(new ContextWrapper(ApplicationProvider.getApplicationContext()));
        mClock = new OffsettableClock.Stopped();
        mTestLooper = new TestLooper(mClock::now);
        when(mContextSpy.getPackageManager()).thenReturn(mPackageManagerMock);
        when(mPackageManagerMock.getPackagesForUid(APP1_UID)).thenReturn(
                new String[]{APP1_PACKAGE_NAME});
        when(mPackageManagerMock.getPackagesForUid(APP2_UID)).thenReturn(
                new String[]{APP2_PACKAGE_NAME});
        when(mActivityManagerInternalMock.getPackageNameByPid(APP1_PID)).thenReturn(
                APP1_PACKAGE_NAME);
        when(mActivityManagerInternalMock.getPackageNameByPid(APP2_PID)).thenReturn(
                APP2_PACKAGE_NAME);

        mService = new LogcatManagerService(mContextSpy, new Injector() {
            @Override
            protected Supplier<Long> createClock() {
                return mClock::now;
            }

            @Override
            protected Looper getLooper() {
                return mTestLooper.getLooper();
            }

            @Override
            protected ILogd getLogdService() {
                return mLogdMock;
            }
        });
        mDialogCallback = mService.getDialogCallback();
        mService.onStart();
    }

    @After
    public void tearDown() throws Exception {
        LocalServices.removeServiceForTest(ActivityManagerInternal.class);
    }

    /**
     * Creates a mock and registers it to {@link LocalServices}.
     */
    private static <T> void addLocalServiceMock(Class<T> clazz, T mock) {
        LocalServices.removeServiceForTest(clazz);
        LocalServices.addService(clazz, mock);
    }

    @Test
    public void test_RequestFromBackground_DeclinedWithoutPrompt() throws Exception {
        when(mActivityManagerInternalMock.getUidProcessState(APP1_UID)).thenReturn(
                ActivityManager.PROCESS_STATE_RECEIVER);
        mService.getBinderService().startThread(APP1_UID, APP1_GID, APP1_PID, FD1);
        mTestLooper.dispatchAll();

        verify(mLogdMock).decline(APP1_UID, APP1_GID, APP1_PID, FD1);
        verify(mLogdMock, never()).approve(APP1_UID, APP1_GID, APP1_PID, FD1);
        verify(mContextSpy, never()).startActivityAsUser(any(), any());
    }

    @Test
    public void test_RequestFromBackground_ApprovedIfInstrumented() throws Exception {
        when(mActivityManagerInternalMock.getInstrumentationSourceUid(APP1_UID))
                .thenReturn(APP1_UID);
        when(mActivityManagerInternalMock.getUidProcessState(APP1_UID)).thenReturn(
                ActivityManager.PROCESS_STATE_RECEIVER);
        mService.getBinderService().startThread(APP1_UID, APP1_GID, APP1_PID, FD1);
        mTestLooper.dispatchAll();

        verify(mLogdMock).approve(APP1_UID, APP1_GID, APP1_PID, FD1);
        verify(mLogdMock, never()).decline(APP1_UID, APP1_GID, APP1_PID, FD1);
        verify(mContextSpy, never()).startActivityAsUser(any(), any());
    }

    @Test
    public void test_RequestFromForegroundService_DeclinedWithoutPrompt() throws Exception {
        when(mActivityManagerInternalMock.getUidProcessState(APP1_UID)).thenReturn(
                ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE);
        mService.getBinderService().startThread(APP1_UID, APP1_GID, APP1_PID, FD1);
        mTestLooper.dispatchAll();

        verify(mLogdMock).decline(APP1_UID, APP1_GID, APP1_PID, FD1);
        verify(mLogdMock, never()).approve(APP1_UID, APP1_GID, APP1_PID, FD1);
        verify(mContextSpy, never()).startActivityAsUser(any(), any());
    }

    @Test
    public void test_RequestFromTop_ShowsPrompt() throws Exception {
        when(mActivityManagerInternalMock.getUidProcessState(APP1_UID)).thenReturn(
                ActivityManager.PROCESS_STATE_TOP);
        mService.getBinderService().startThread(APP1_UID, APP1_GID, APP1_PID, FD1);
        mTestLooper.dispatchAll();

        verify(mLogdMock, never()).approve(APP1_UID, APP1_GID, APP1_PID, FD1);
        verify(mLogdMock, never()).decline(APP1_UID, APP1_GID, APP1_PID, FD1);
        verify(mContextSpy, times(1)).startActivityAsUser(any(), eq(UserHandle.SYSTEM));
    }

    @Test
    public void test_RequestFromTop_NoInteractionWithPrompt_DeclinesAfterTimeout()
            throws Exception {
        when(mActivityManagerInternalMock.getUidProcessState(APP1_UID)).thenReturn(
                ActivityManager.PROCESS_STATE_TOP);
        mService.getBinderService().startThread(APP1_UID, APP1_GID, APP1_PID, FD1);
        mTestLooper.dispatchAll();

        advanceTime(LogcatManagerService.PENDING_CONFIRMATION_TIMEOUT_MILLIS);

        verify(mLogdMock, never()).approve(APP1_UID, APP1_GID, APP1_PID, FD1);
        verify(mLogdMock).decline(APP1_UID, APP1_GID, APP1_PID, FD1);
    }

    @Test
    public void test_RequestFromTop_Approved() throws Exception {
        when(mActivityManagerInternalMock.getUidProcessState(APP1_UID)).thenReturn(
                ActivityManager.PROCESS_STATE_TOP);
        mService.getBinderService().startThread(APP1_UID, APP1_GID, APP1_PID, FD1);
        mTestLooper.dispatchAll();
        verify(mContextSpy, times(1)).startActivityAsUser(any(), eq(UserHandle.SYSTEM));

        mDialogCallback.approveAccessForClient(APP1_UID, APP1_PACKAGE_NAME);
        mTestLooper.dispatchAll();

        verify(mLogdMock, times(1)).approve(APP1_UID, APP1_GID, APP1_PID, FD1);
        verify(mLogdMock, never()).decline(APP1_UID, APP1_GID, APP1_PID, FD1);
    }

    @Test
    public void test_RequestFromTop_Declined() throws Exception {
        when(mActivityManagerInternalMock.getUidProcessState(APP1_UID)).thenReturn(
                ActivityManager.PROCESS_STATE_TOP);
        mService.getBinderService().startThread(APP1_UID, APP1_GID, APP1_PID, FD1);
        mTestLooper.dispatchAll();
        verify(mContextSpy, times(1)).startActivityAsUser(any(), eq(UserHandle.SYSTEM));

        mDialogCallback.declineAccessForClient(APP1_UID, APP1_PACKAGE_NAME);
        mTestLooper.dispatchAll();

        verify(mLogdMock, never()).approve(APP1_UID, APP1_GID, APP1_PID, FD1);
        verify(mLogdMock, times(1)).decline(APP1_UID, APP1_GID, APP1_PID, FD1);
    }

    @Test
    public void test_RequestFromTop_MultipleRequestsApprovedTogether() throws Exception {
        when(mActivityManagerInternalMock.getUidProcessState(APP1_UID)).thenReturn(
                ActivityManager.PROCESS_STATE_TOP);
        mService.getBinderService().startThread(APP1_UID, APP1_GID, APP1_PID, FD1);
        mService.getBinderService().startThread(APP1_UID, APP1_GID, APP1_PID, FD2);
        mTestLooper.dispatchAll();
        verify(mContextSpy, times(1)).startActivityAsUser(any(), eq(UserHandle.SYSTEM));
        verify(mLogdMock, never()).approve(eq(APP1_UID), eq(APP1_GID), eq(APP1_PID), anyInt());
        verify(mLogdMock, never()).decline(eq(APP1_UID), eq(APP1_GID), eq(APP1_PID), anyInt());

        mDialogCallback.approveAccessForClient(APP1_UID, APP1_PACKAGE_NAME);
        mTestLooper.dispatchAll();

        verify(mLogdMock, times(1)).approve(APP1_UID, APP1_GID, APP1_PID, FD1);
        verify(mLogdMock, times(1)).approve(APP1_UID, APP1_GID, APP1_PID, FD2);
        verify(mLogdMock, never()).decline(APP1_UID, APP1_GID, APP1_PID, FD1);
        verify(mLogdMock, never()).decline(APP1_UID, APP1_GID, APP1_PID, FD2);
    }

    @Test
    public void test_RequestFromTop_MultipleRequestsDeclinedTogether() throws Exception {
        when(mActivityManagerInternalMock.getUidProcessState(APP1_UID)).thenReturn(
                ActivityManager.PROCESS_STATE_TOP);
        mService.getBinderService().startThread(APP1_UID, APP1_GID, APP1_PID, FD1);
        mService.getBinderService().startThread(APP1_UID, APP1_GID, APP1_PID, FD2);
        mTestLooper.dispatchAll();
        verify(mContextSpy, times(1)).startActivityAsUser(any(), eq(UserHandle.SYSTEM));
        verify(mLogdMock, never()).approve(eq(APP1_UID), eq(APP1_GID), eq(APP1_PID), anyInt());
        verify(mLogdMock, never()).decline(eq(APP1_UID), eq(APP1_GID), eq(APP1_PID), anyInt());

        mDialogCallback.declineAccessForClient(APP1_UID, APP1_PACKAGE_NAME);
        mTestLooper.dispatchAll();

        verify(mLogdMock, times(1)).decline(APP1_UID, APP1_GID, APP1_PID, FD1);
        verify(mLogdMock, times(1)).decline(APP1_UID, APP1_GID, APP1_PID, FD2);
        verify(mLogdMock, never()).approve(APP1_UID, APP1_GID, APP1_PID, FD1);
        verify(mLogdMock, never()).approve(APP1_UID, APP1_GID, APP1_PID, FD2);
    }

    @Test
    public void test_RequestFromTop_Approved_DoesNotShowPromptAgain() throws Exception {
        when(mActivityManagerInternalMock.getUidProcessState(APP1_UID)).thenReturn(
                ActivityManager.PROCESS_STATE_TOP);
        mService.getBinderService().startThread(APP1_UID, APP1_GID, APP1_PID, FD1);
        mTestLooper.dispatchAll();
        mDialogCallback.approveAccessForClient(APP1_UID, APP1_PACKAGE_NAME);
        mTestLooper.dispatchAll();

        mService.getBinderService().startThread(APP1_UID, APP1_GID, APP1_PID, FD2);
        mTestLooper.dispatchAll();

        verify(mContextSpy, times(1)).startActivityAsUser(any(), eq(UserHandle.SYSTEM));
        verify(mLogdMock, times(1)).approve(APP1_UID, APP1_GID, APP1_PID, FD1);
        verify(mLogdMock, times(1)).approve(APP1_UID, APP1_GID, APP1_PID, FD2);
        verify(mLogdMock, never()).decline(APP1_UID, APP1_GID, APP1_PID, FD2);
    }

    @Test
    public void test_RequestFromTop_Declined_DoesNotShowPromptAgain() throws Exception {
        when(mActivityManagerInternalMock.getUidProcessState(APP1_UID)).thenReturn(
                ActivityManager.PROCESS_STATE_TOP);
        mService.getBinderService().startThread(APP1_UID, APP1_GID, APP1_PID, FD1);
        mTestLooper.dispatchAll();
        mDialogCallback.declineAccessForClient(APP1_UID, APP1_PACKAGE_NAME);
        mTestLooper.dispatchAll();

        mService.getBinderService().startThread(APP1_UID, APP1_GID, APP1_PID, FD2);
        mTestLooper.dispatchAll();

        verify(mContextSpy, times(1)).startActivityAsUser(any(), eq(UserHandle.SYSTEM));
        verify(mLogdMock, times(1)).decline(APP1_UID, APP1_GID, APP1_PID, FD1);
        verify(mLogdMock, times(1)).decline(APP1_UID, APP1_GID, APP1_PID, FD2);
        verify(mLogdMock, never()).approve(APP1_UID, APP1_GID, APP1_PID, FD2);
    }

    @Test
    public void test_RequestFromTop_Approved_ShowsPromptForDifferentClient() throws Exception {
        when(mActivityManagerInternalMock.getUidProcessState(APP1_UID)).thenReturn(
                ActivityManager.PROCESS_STATE_TOP);
        when(mActivityManagerInternalMock.getUidProcessState(APP2_UID)).thenReturn(
                ActivityManager.PROCESS_STATE_TOP);
        mService.getBinderService().startThread(APP1_UID, APP1_GID, APP1_PID, FD1);
        mTestLooper.dispatchAll();
        mDialogCallback.approveAccessForClient(APP1_UID, APP1_PACKAGE_NAME);
        mTestLooper.dispatchAll();

        mService.getBinderService().startThread(APP2_UID, APP2_GID, APP2_PID, FD2);
        mTestLooper.dispatchAll();

        verify(mContextSpy, times(2)).startActivityAsUser(any(), eq(UserHandle.SYSTEM));
        verify(mLogdMock, never()).decline(APP2_UID, APP2_GID, APP2_PID, FD2);
        verify(mLogdMock, never()).approve(APP2_UID, APP2_GID, APP2_PID, FD2);
    }

    @Test
    public void test_RequestFromTop_Approved_ShowPromptAgainAfterTimeout() throws Exception {
        when(mActivityManagerInternalMock.getUidProcessState(APP1_UID)).thenReturn(
                ActivityManager.PROCESS_STATE_TOP);
        mService.getBinderService().startThread(APP1_UID, APP1_GID, APP1_PID, FD1);
        mTestLooper.dispatchAll();
        mDialogCallback.declineAccessForClient(APP1_UID, APP1_PACKAGE_NAME);
        mTestLooper.dispatchAll();

        advanceTime(LogcatManagerService.STATUS_EXPIRATION_TIMEOUT_MILLIS);

        mService.getBinderService().startThread(APP1_UID, APP1_GID, APP1_PID, FD1);
        mTestLooper.dispatchAll();

        verify(mContextSpy, times(2)).startActivityAsUser(any(), eq(UserHandle.SYSTEM));
    }

    private void advanceTime(long timeMs) {
        mClock.fastForward(timeMs);
        mTestLooper.dispatchAll();
    }
}

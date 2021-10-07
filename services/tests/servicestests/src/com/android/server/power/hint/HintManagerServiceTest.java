/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.power.hint;


import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.IHintSession;
import android.os.Process;

import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.power.hint.HintManagerService.AppHintSession;
import com.android.server.power.hint.HintManagerService.Injector;
import com.android.server.power.hint.HintManagerService.NativeWrapper;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link com.android.server.power.hint.HintManagerService}.
 *
 * Build/Install/Run:
 *  atest FrameworksServicesTests:HintManagerServiceTest
 */
public class HintManagerServiceTest {
    private static final long DEFAULT_HINT_PREFERRED_RATE = 16666666L;
    private static final long DEFAULT_TARGET_DURATION = 16666666L;
    private static final int UID = Process.myUid();
    private static final int TID = Process.myPid();
    private static final int TGID = Process.getThreadGroupLeader(TID);
    private static final int[] SESSION_TIDS_A = new int[] {TID};
    private static final int[] SESSION_TIDS_B = new int[] {TID};
    private static final long[] DURATIONS_THREE = new long[] {1L, 100L, 1000L};
    private static final long[] TIMESTAMPS_THREE = new long[] {1L, 2L, 3L};
    private static final long[] DURATIONS_ZERO = new long[] {};
    private static final long[] TIMESTAMPS_ZERO = new long[] {};
    private static final long[] TIMESTAMPS_TWO = new long[] {1L, 2L};

    @Mock private Context mContext;
    @Mock private HintManagerService.NativeWrapper mNativeWrapperMock;
    @Mock private ActivityManagerInternal mAmInternalMock;

    private HintManagerService mService;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mNativeWrapperMock.halGetHintSessionPreferredRate())
                .thenReturn(DEFAULT_HINT_PREFERRED_RATE);
        when(mNativeWrapperMock.halCreateHintSession(eq(TGID), eq(UID), eq(SESSION_TIDS_A),
              eq(DEFAULT_TARGET_DURATION))).thenReturn(1L);
        when(mNativeWrapperMock.halCreateHintSession(eq(TGID), eq(UID), eq(SESSION_TIDS_B),
              eq(DEFAULT_TARGET_DURATION))).thenReturn(2L);
        when(mAmInternalMock.getIsolatedProcesses(anyInt())).thenReturn(null);
        LocalServices.removeServiceForTest(ActivityManagerInternal.class);
        LocalServices.addService(ActivityManagerInternal.class, mAmInternalMock);
    }

    private HintManagerService createService() {
        mService = new HintManagerService(mContext, new Injector() {
            NativeWrapper createNativeWrapper() {
                return mNativeWrapperMock;
            }
        });
        return mService;
    }

    @Test
    public void testInitializeService() {
        HintManagerService service = createService();
        verify(mNativeWrapperMock).halInit();
        assertThat(service.mHintSessionPreferredRate).isEqualTo(DEFAULT_HINT_PREFERRED_RATE);
    }

    @Test
    public void testCreateHintSessionInvalidPid() throws Exception {
        HintManagerService service = createService();
        IBinder token = new Binder();
        // Make sure we throw exception when adding a TID doesn't belong to the processes
        // In this case, we add `init` PID into the list.
        assertThrows(SecurityException.class,
                () -> service.getBinderServiceInstance().createHintSession(token,
                        new int[]{TID, 1}, DEFAULT_TARGET_DURATION));
    }

    @Test
    public void testCreateHintSession() throws Exception {
        HintManagerService service = createService();
        IBinder token = new Binder();

        IHintSession a = service.getBinderServiceInstance().createHintSession(token,
                SESSION_TIDS_A, DEFAULT_TARGET_DURATION);
        assertNotNull(a);

        IHintSession b = service.getBinderServiceInstance().createHintSession(token,
                SESSION_TIDS_B, DEFAULT_TARGET_DURATION);
        assertNotEquals(a, b);
    }

    @Test
    public void testPauseResumeHintSession() throws Exception {
        HintManagerService service = createService();
        IBinder token = new Binder();

        AppHintSession a = (AppHintSession) service.getBinderServiceInstance()
                .createHintSession(token, SESSION_TIDS_A, DEFAULT_TARGET_DURATION);

        // Set session to background and calling updateHintAllowed() would invoke pause();
        service.mUidObserver.onUidStateChanged(
                a.mUid, ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND, 0, 0);
        final Object sync = new Object();
        FgThread.getHandler().post(() -> {
            synchronized (sync) {
                sync.notify();
            }
        });
        synchronized (sync) {
            sync.wait();
        }
        assumeFalse(a.updateHintAllowed());
        verify(mNativeWrapperMock, times(1)).halPauseHintSession(anyLong());

        // Set session to foreground and calling updateHintAllowed() would invoke resume();
        service.mUidObserver.onUidStateChanged(
                a.mUid, ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND, 0, 0);
        FgThread.getHandler().post(() -> {
            synchronized (sync) {
                sync.notify();
            }
        });
        synchronized (sync) {
            sync.wait();
        }
        assumeTrue(a.updateHintAllowed());
        verify(mNativeWrapperMock, times(1)).halResumeHintSession(anyLong());
    }

    @Test
    public void testCloseHintSession() throws Exception {
        HintManagerService service = createService();
        IBinder token = new Binder();

        IHintSession a = service.getBinderServiceInstance().createHintSession(token,
                SESSION_TIDS_A, DEFAULT_TARGET_DURATION);

        a.close();
        verify(mNativeWrapperMock, times(1)).halCloseHintSession(anyLong());
    }

    @Test
    public void testUpdateTargetWorkDuration() throws Exception {
        HintManagerService service = createService();
        IBinder token = new Binder();

        IHintSession a = service.getBinderServiceInstance().createHintSession(token,
                SESSION_TIDS_A, DEFAULT_TARGET_DURATION);

        assertThrows(IllegalArgumentException.class, () -> {
            a.updateTargetWorkDuration(-1L);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            a.updateTargetWorkDuration(0L);
        });

        a.updateTargetWorkDuration(100L);
        verify(mNativeWrapperMock, times(1)).halUpdateTargetWorkDuration(anyLong(), eq(100L));
    }

    @Test
    public void testReportActualWorkDuration() throws Exception {
        HintManagerService service = createService();
        IBinder token = new Binder();

        AppHintSession a = (AppHintSession) service.getBinderServiceInstance()
                .createHintSession(token, SESSION_TIDS_A, DEFAULT_TARGET_DURATION);

        a.updateTargetWorkDuration(100L);
        a.reportActualWorkDuration(DURATIONS_THREE, TIMESTAMPS_THREE);
        verify(mNativeWrapperMock, times(1)).halReportActualWorkDuration(anyLong(),
                eq(DURATIONS_THREE), eq(TIMESTAMPS_THREE));

        assertThrows(IllegalArgumentException.class, () -> {
            a.reportActualWorkDuration(DURATIONS_ZERO, TIMESTAMPS_THREE);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            a.reportActualWorkDuration(DURATIONS_THREE, TIMESTAMPS_ZERO);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            a.reportActualWorkDuration(DURATIONS_THREE, TIMESTAMPS_TWO);
        });

        reset(mNativeWrapperMock);
        // Set session to background, then the duration would not be updated.
        service.mUidObserver.onUidStateChanged(
                a.mUid, ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND, 0, 0);
        final Object sync = new Object();
        FgThread.getHandler().post(() -> {
            synchronized (sync) {
                sync.notify();
            }
        });
        synchronized (sync) {
            sync.wait();
        }
        assumeFalse(a.updateHintAllowed());
        a.reportActualWorkDuration(DURATIONS_THREE, TIMESTAMPS_THREE);
        verify(mNativeWrapperMock, never()).halReportActualWorkDuration(anyLong(), any(), any());
    }

    @Test
    public void testDoHintInBackground() throws Exception {
        HintManagerService service = createService();
        IBinder token = new Binder();

        AppHintSession a = (AppHintSession) service.getBinderServiceInstance()
                .createHintSession(token, SESSION_TIDS_A, DEFAULT_TARGET_DURATION);

        service.mUidObserver.onUidStateChanged(
                a.mUid, ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND, 0, 0);
        final Object sync = new Object();
        FgThread.getHandler().post(() -> {
            synchronized (sync) {
                sync.notify();
            }
        });
        synchronized (sync) {
            sync.wait();
        }
        assertFalse(a.updateHintAllowed());
    }

    @Test
    public void testDoHintInForeground() throws Exception {
        HintManagerService service = createService();
        IBinder token = new Binder();

        AppHintSession a = (AppHintSession) service.getBinderServiceInstance()
                .createHintSession(token, SESSION_TIDS_A, DEFAULT_TARGET_DURATION);

        service.mUidObserver.onUidStateChanged(
                a.mUid, ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND, 0, 0);
        assertTrue(a.updateHintAllowed());
    }
}

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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.eq;
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
import android.os.PerformanceHintManager;
import android.os.Process;
import android.os.WorkDuration;
import android.util.Log;

import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.power.hint.HintManagerService.AppHintSession;
import com.android.server.power.hint.HintManagerService.Injector;
import com.android.server.power.hint.HintManagerService.NativeWrapper;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Tests for {@link com.android.server.power.hint.HintManagerService}.
 *
 * Build/Install/Run:
 *  atest FrameworksServicesTests:HintManagerServiceTest
 */
public class HintManagerServiceTest {
    private static final String TAG = "HintManagerServiceTest";

    private static final long DEFAULT_HINT_PREFERRED_RATE = 16666666L;
    private static final long DEFAULT_TARGET_DURATION = 16666666L;
    private static final long CONCURRENCY_TEST_DURATION_SEC = 10;
    private static final int UID = Process.myUid();
    private static final int TID = Process.myPid();
    private static final int TGID = Process.getThreadGroupLeader(TID);
    private static final int[] SESSION_TIDS_A = new int[] {TID};
    private static final int[] SESSION_TIDS_B = new int[] {TID};
    private static final int[] SESSION_TIDS_C = new int[] {TID};
    private static final long[] DURATIONS_THREE = new long[] {1L, 100L, 1000L};
    private static final long[] TIMESTAMPS_THREE = new long[] {1L, 2L, 3L};
    private static final long[] DURATIONS_ZERO = new long[] {};
    private static final long[] TIMESTAMPS_ZERO = new long[] {};
    private static final long[] TIMESTAMPS_TWO = new long[] {1L, 2L};
    private static final WorkDuration[] WORK_DURATIONS_FIVE = new WorkDuration[] {
        new WorkDuration(1L, 11L, 8L, 4L, 1L),
        new WorkDuration(2L, 13L, 8L, 6L, 2L),
        new WorkDuration(3L, 333333333L, 8L, 333333333L, 3L),
        new WorkDuration(2L, 13L, 0L, 6L, 2L),
        new WorkDuration(2L, 13L, 8L, 0L, 2L),
    };

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
        when(mNativeWrapperMock.halCreateHintSession(eq(TGID), eq(UID), eq(SESSION_TIDS_C),
              eq(0L))).thenReturn(1L);
        when(mAmInternalMock.getIsolatedProcesses(anyInt())).thenReturn(null);
        LocalServices.removeServiceForTest(ActivityManagerInternal.class);
        LocalServices.addService(ActivityManagerInternal.class, mAmInternalMock);
    }

    static class NativeWrapperFake extends NativeWrapper {
        @Override
        public void halInit() {
        }

        @Override
        public long halGetHintSessionPreferredRate() {
            return 1;
        }

        @Override
        public long halCreateHintSession(int tgid, int uid, int[] tids, long durationNanos) {
            return 1;
        }

        @Override
        public void halPauseHintSession(long halPtr) {
        }

        @Override
        public void halResumeHintSession(long halPtr) {
        }

        @Override
        public void halCloseHintSession(long halPtr) {
        }

        @Override
        public void halUpdateTargetWorkDuration(long halPtr, long targetDurationNanos) {
        }

        @Override
        public void halReportActualWorkDuration(
                long halPtr, long[] actualDurationNanos, long[] timeStampNanos) {
        }

        @Override
        public void halSendHint(long halPtr, int hint) {
        }

        @Override
        public void halSetThreads(long halPtr, int[] tids) {
        }

        @Override
        public void halSetMode(long halPtr, int mode, boolean enabled) {
        }
    }

    private HintManagerService createService() {
        mService = new HintManagerService(mContext, new Injector() {
            NativeWrapper createNativeWrapper() {
                return mNativeWrapperMock;
            }
        });
        return mService;
    }

    private HintManagerService createServiceWithFakeWrapper() {
        mService = new HintManagerService(mContext, new Injector() {
            NativeWrapper createNativeWrapper() {
                return new NativeWrapperFake();
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

        IHintSession c = service.getBinderServiceInstance().createHintSession(token,
                SESSION_TIDS_C, 0L);
        assertNotNull(c);
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

        // Using CountDownLatch to ensure above onUidStateChanged() job was digested.
        final CountDownLatch latch = new CountDownLatch(1);
        FgThread.getHandler().post(() -> {
            latch.countDown();
        });
        latch.await();
        assertFalse(service.mUidObserver.isUidForeground(a.mUid));
        verify(mNativeWrapperMock, times(1)).halPauseHintSession(anyLong());

        // Set session to foreground and calling updateHintAllowed() would invoke resume();
        service.mUidObserver.onUidStateChanged(
                a.mUid, ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND, 0, 0);

        // Using CountDownLatch to ensure above onUidStateChanged() job was digested.
        final CountDownLatch latch2 = new CountDownLatch(1);
        FgThread.getHandler().post(() -> {
            latch2.countDown();
        });
        latch2.await();

        assertTrue(service.mUidObserver.isUidForeground(a.mUid));
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

        // Using CountDownLatch to ensure above onUidStateChanged() job was digested.
        final CountDownLatch latch = new CountDownLatch(1);
        FgThread.getHandler().post(() -> {
            latch.countDown();
        });
        latch.await();

        assertFalse(service.mUidObserver.isUidForeground(a.mUid));
        a.reportActualWorkDuration(DURATIONS_THREE, TIMESTAMPS_THREE);
        verify(mNativeWrapperMock, never()).halReportActualWorkDuration(anyLong(), any(), any());
    }

    @Test
    public void testSendHint() throws Exception {
        HintManagerService service = createService();
        IBinder token = new Binder();

        AppHintSession a = (AppHintSession) service.getBinderServiceInstance()
                .createHintSession(token, SESSION_TIDS_A, DEFAULT_TARGET_DURATION);

        a.sendHint(PerformanceHintManager.Session.CPU_LOAD_RESET);
        verify(mNativeWrapperMock, times(1)).halSendHint(anyLong(),
                eq(PerformanceHintManager.Session.CPU_LOAD_RESET));

        assertThrows(IllegalArgumentException.class, () -> {
            a.sendHint(-1);
        });

        reset(mNativeWrapperMock);
        // Set session to background, then the duration would not be updated.
        service.mUidObserver.onUidStateChanged(
                a.mUid, ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND, 0, 0);
        FgThread.getHandler().runWithScissors(() -> { }, 500);
        assertFalse(service.mUidObserver.isUidForeground(a.mUid));
        a.sendHint(PerformanceHintManager.Session.CPU_LOAD_RESET);
        verify(mNativeWrapperMock, never()).halSendHint(anyLong(), anyInt());
    }

    @Test
    public void testDoHintInBackground() throws Exception {
        HintManagerService service = createService();
        IBinder token = new Binder();

        AppHintSession a = (AppHintSession) service.getBinderServiceInstance()
                .createHintSession(token, SESSION_TIDS_A, DEFAULT_TARGET_DURATION);

        service.mUidObserver.onUidStateChanged(
                a.mUid, ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND, 0, 0);

        // Using CountDownLatch to ensure above onUidStateChanged() job was digested.
        final CountDownLatch latch = new CountDownLatch(1);
        FgThread.getHandler().post(() -> {
            latch.countDown();
        });
        latch.await();

        assertFalse(service.mUidObserver.isUidForeground(a.mUid));
    }

    @Test
    public void testDoHintInForeground() throws Exception {
        HintManagerService service = createService();
        IBinder token = new Binder();

        AppHintSession a = (AppHintSession) service.getBinderServiceInstance()
                .createHintSession(token, SESSION_TIDS_A, DEFAULT_TARGET_DURATION);

        service.mUidObserver.onUidStateChanged(
                a.mUid, ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND, 0, 0);
        assertTrue(service.mUidObserver.isUidForeground(a.mUid));
    }

    @Test
    public void testSetThreads() throws Exception {
        HintManagerService service = createService();
        IBinder token = new Binder();

        AppHintSession a = (AppHintSession) service.getBinderServiceInstance()
                .createHintSession(token, SESSION_TIDS_A, DEFAULT_TARGET_DURATION);

        a.updateTargetWorkDuration(100L);

        assertThrows(IllegalArgumentException.class, () -> {
            a.setThreads(new int[]{});
        });

        a.setThreads(SESSION_TIDS_B);
        verify(mNativeWrapperMock, times(1)).halSetThreads(anyLong(), eq(SESSION_TIDS_B));
        assertArrayEquals(SESSION_TIDS_B, a.getThreadIds());

        reset(mNativeWrapperMock);
        // Set session to background, then the duration would not be updated.
        service.mUidObserver.onUidStateChanged(
                a.mUid, ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND, 0, 0);
        FgThread.getHandler().runWithScissors(() -> { }, 500);
        assertFalse(service.mUidObserver.isUidForeground(a.mUid));
        a.setThreads(SESSION_TIDS_A);
        verify(mNativeWrapperMock, never()).halSetThreads(anyLong(), any());
    }

    @Test
    public void testSetMode() throws Exception {
        HintManagerService service = createService();
        IBinder token = new Binder();

        AppHintSession a = (AppHintSession) service.getBinderServiceInstance()
                .createHintSession(token, SESSION_TIDS_A, DEFAULT_TARGET_DURATION);

        a.setMode(0, true);
        verify(mNativeWrapperMock, times(1)).halSetMode(anyLong(),
                eq(0), eq(true));

        a.setMode(0, false);
        verify(mNativeWrapperMock, times(1)).halSetMode(anyLong(),
                eq(0), eq(false));

        assertThrows(IllegalArgumentException.class, () -> {
            a.setMode(-1, true);
        });

        reset(mNativeWrapperMock);
        // Set session to background, then the duration would not be updated.
        service.mUidObserver.onUidStateChanged(
                a.mUid, ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND, 0, 0);
        FgThread.getHandler().runWithScissors(() -> { }, 500);
        assertFalse(service.mUidObserver.isUidForeground(a.mUid));
        a.setMode(0, true);
        verify(mNativeWrapperMock, never()).halSetMode(anyLong(), anyInt(), anyBoolean());
    }

    // This test checks that concurrent operations from different threads on IHintService,
    // IHintSession and UidObserver will not cause data race or deadlock. Ideally we should also
    // check the output of threads' reportActualDuration performance to detect lock starvation
    // but the result is not stable, so it's better checked manually.
    @Test
    public void testConcurrency() throws Exception {
        HintManagerService service = createServiceWithFakeWrapper();
        // initialize session threads to run in parallel
        final int sessionCount = 10;
        // the signal that the main thread will send to session threads to check for run or exit
        AtomicReference<Boolean> shouldRun = new AtomicReference<>(true);
        // the signal for main test thread to wait for session threads and notifier thread to
        // finish and exit
        CountDownLatch latch = new CountDownLatch(sessionCount + 1);
        // list of exceptions with one per session thread or notifier thread
        List<AtomicReference<Exception>> execs = new ArrayList<>(sessionCount + 1);
        List<Thread> threads = new ArrayList<>(sessionCount + 1);
        for (int i = 0; i < sessionCount; i++) {
            final AtomicReference<Exception> exec = new AtomicReference<>();
            execs.add(exec);
            int j = i;
            Thread app = new Thread(() -> {
                try {
                    while (shouldRun.get()) {
                        runAppHintSession(service, j, shouldRun);
                    }
                } catch (Exception e) {
                    exec.set(e);
                } finally {
                    latch.countDown();
                }
            });
            threads.add(app);
        }

        // initialize a UID state notifier thread to run in parallel
        final AtomicReference<Exception> notifierExec = new AtomicReference<>();
        execs.add(notifierExec);
        Thread notifier = new Thread(() -> {
            try {
                long min = Long.MAX_VALUE;
                long max = Long.MIN_VALUE;
                long sum = 0;
                int count = 0;
                while (shouldRun.get()) {
                    long start = System.nanoTime();
                    service.mUidObserver.onUidStateChanged(UID,
                            ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND, 0, 0);
                    long elapsed = System.nanoTime() - start;
                    sum += elapsed;
                    count++;
                    min = Math.min(min, elapsed);
                    max = Math.max(max, elapsed);
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(500));
                    service.mUidObserver.onUidStateChanged(UID,
                            ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND, 0, 0);
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(500));
                }
                Log.d(TAG, "notifier thread min " + min + " max " + max + " avg " + sum / count);
                service.mUidObserver.onUidGone(UID, true);
            } catch (Exception e) {
                notifierExec.set(e);
            } finally {
                latch.countDown();
            }
        });
        threads.add(notifier);

        // start all the threads
        for (Thread thread : threads) {
            thread.start();
        }
        // keep the test running for a few seconds
        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(CONCURRENCY_TEST_DURATION_SEC));
        // send signal to stop all threads
        shouldRun.set(false);
        // wait for all threads to exit
        latch.await();
        // check if any thread throws exception
        for (AtomicReference<Exception> exec : execs) {
            if (exec.get() != null) {
                throw exec.get();
            }
        }
    }

    private void runAppHintSession(HintManagerService service, int logId,
            AtomicReference<Boolean> shouldRun) throws Exception {
        IBinder token = new Binder();
        AppHintSession a = (AppHintSession) service.getBinderServiceInstance()
                .createHintSession(token, SESSION_TIDS_A, DEFAULT_TARGET_DURATION);
        // we will start some threads and get their valid TIDs to update
        int threadCount = 3;
        // the list of TIDs
        int[] tids = new int[threadCount];
        // atomic index for each thread to set its TID in the list
        AtomicInteger k = new AtomicInteger(0);
        // signal for the session main thread to wait for child threads to finish updating TIDs
        CountDownLatch latch = new CountDownLatch(threadCount);
        // signal for the session main thread to notify child threads to exit
        CountDownLatch stopLatch = new CountDownLatch(1);
        for (int j = 0; j < threadCount; j++) {
            Thread thread = new Thread(() -> {
                try {
                    tids[k.getAndIncrement()] = android.os.Process.myTid();
                    latch.countDown();
                    stopLatch.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
            thread.start();
        }
        latch.await();
        a.setThreads(tids);
        // we don't need the threads to exist after update
        stopLatch.countDown();
        a.updateTargetWorkDuration(5);
        // measure the time it takes in HintManagerService to run reportActualDuration
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        long sum = 0;
        int count = 0;
        List<Long> values = new ArrayList<>();
        long testStart = System.nanoTime();
        // run report actual for 4-second per cycle
        while (shouldRun.get() && System.nanoTime() - testStart < TimeUnit.SECONDS.toNanos(
                Math.min(4, CONCURRENCY_TEST_DURATION_SEC))) {
            long start = System.nanoTime();
            a.reportActualWorkDuration(new long[]{5}, new long[]{start});
            long elapsed = System.nanoTime() - start;
            values.add(elapsed);
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
            sum += elapsed;
            count++;
            min = Math.min(min, elapsed);
            max = Math.max(max, elapsed);
        }
        Collections.sort(values);
        if (!values.isEmpty()) {
            Log.d(TAG, "app thread " + logId + " min " + min + " max " + max
                    + " avg " + sum / count + " count " + count
                    + " 80th " + values.get((int) (values.size() * 0.8))
                    + " 90th " + values.get((int) (values.size() * 0.9))
                    + " 95th " + values.get((int) (values.size() * 0.95)));
        } else {
            Log.w(TAG, "No reportActualWorkDuration executed");
        }
        a.close();
    }

    @Test
    public void testReportActualWorkDuration2() throws Exception {
        HintManagerService service = createService();
        IBinder token = new Binder();

        AppHintSession a = (AppHintSession) service.getBinderServiceInstance()
                .createHintSession(token, SESSION_TIDS_A, DEFAULT_TARGET_DURATION);

        a.updateTargetWorkDuration(100L);
        a.reportActualWorkDuration2(WORK_DURATIONS_FIVE);
        verify(mNativeWrapperMock, times(1)).halReportActualWorkDuration(anyLong(),
                eq(WORK_DURATIONS_FIVE));

        assertThrows(IllegalArgumentException.class, () -> {
            a.reportActualWorkDuration2(new WorkDuration[] {});
        });

        assertThrows(IllegalArgumentException.class, () -> {
            a.reportActualWorkDuration2(
                    new WorkDuration[] {new WorkDuration(-1L, 11L, 8L, 4L, 1L)});
        });

        assertThrows(IllegalArgumentException.class, () -> {
            a.reportActualWorkDuration2(new WorkDuration[] {new WorkDuration(1L, 0L, 8L, 4L, 1L)});
        });

        assertThrows(IllegalArgumentException.class, () -> {
            a.reportActualWorkDuration2(new WorkDuration[] {new WorkDuration(1L, 11L, 0L, 0L, 1L)});
        });

        assertThrows(IllegalArgumentException.class, () -> {
            a.reportActualWorkDuration2(
                    new WorkDuration[] {new WorkDuration(1L, 11L, 8L, -1L, 1L)});
        });

        reset(mNativeWrapperMock);
        // Set session to background, then the duration would not be updated.
        service.mUidObserver.onUidStateChanged(
                a.mUid, ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND, 0, 0);

        // Using CountDownLatch to ensure above onUidStateChanged() job was digested.
        final CountDownLatch latch = new CountDownLatch(1);
        FgThread.getHandler().post(() -> {
            latch.countDown();
        });
        latch.await();

        assertFalse(service.mUidObserver.isUidForeground(a.mUid));
        a.reportActualWorkDuration2(WORK_DURATIONS_FIVE);
        verify(mNativeWrapperMock, never()).halReportActualWorkDuration(anyLong(), any(), any());
    }
}

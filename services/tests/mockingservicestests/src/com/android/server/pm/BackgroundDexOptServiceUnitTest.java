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

package com.android.server.pm;

import static com.android.server.pm.BackgroundDexOptService.STATUS_DEX_OPT_FAILED;
import static com.android.server.pm.BackgroundDexOptService.STATUS_FATAL_ERROR;
import static com.android.server.pm.BackgroundDexOptService.STATUS_OK;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import android.annotation.Nullable;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.Process;
import android.util.Log;

import com.android.internal.util.IndentingPrintWriter;
import com.android.server.LocalServices;
import com.android.server.PinnerService;
import com.android.server.pm.dex.DexManager;
import com.android.server.pm.dex.DexoptOptions;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

@RunWith(MockitoJUnitRunner.class)
public final class BackgroundDexOptServiceUnitTest {
    private static final String TAG = BackgroundDexOptServiceUnitTest.class.getSimpleName();

    private static final long USABLE_SPACE_NORMAL = 1_000_000_000;
    private static final long STORAGE_LOW_BYTES = 1_000_000;

    private static final long TEST_WAIT_TIMEOUT_MS = 10_000;

    private static final String PACKAGE_AAA = "aaa";
    private static final List<String> DEFAULT_PACKAGE_LIST = List.of(PACKAGE_AAA, "bbb");
    private int mDexOptResultForPackageAAA = PackageDexOptimizer.DEX_OPT_PERFORMED;

    // Store expected dexopt sequence for verification.
    private ArrayList<DexOptInfo> mDexInfoSequence = new ArrayList<>();

    @Mock
    private Context mContext;
    @Mock
    private PackageManagerService mPackageManager;
    @Mock
    private DexOptHelper mDexOptHelper;
    @Mock
    private DexManager mDexManager;
    @Mock
    private PinnerService mPinnerService;
    @Mock
    private JobScheduler mJobScheduler;
    @Mock
    private BackgroundDexOptService.Injector mInjector;
    @Mock
    private BackgroundDexOptJobService mJobServiceForPostBoot;
    @Mock
    private BackgroundDexOptJobService mJobServiceForIdle;

    private final JobParameters mJobParametersForPostBoot =
            createJobParameters(BackgroundDexOptService.JOB_POST_BOOT_UPDATE);
    private final JobParameters mJobParametersForIdle =
            createJobParameters(BackgroundDexOptService.JOB_IDLE_OPTIMIZE);

    private static JobParameters createJobParameters(int jobId) {
        JobParameters params = mock(JobParameters.class);
        when(params.getJobId()).thenReturn(jobId);
        return params;
    }

    private BackgroundDexOptService mService;

    private StartAndWaitThread mDexOptThread;
    private StartAndWaitThread mCancelThread;

    @Before
    public void setUp() throws Exception {
        when(mInjector.getCallingUid()).thenReturn(Process.FIRST_APPLICATION_UID);
        when(mInjector.getContext()).thenReturn(mContext);
        when(mInjector.getDexOptHelper()).thenReturn(mDexOptHelper);
        when(mInjector.getDexManager()).thenReturn(mDexManager);
        when(mInjector.getPinnerService()).thenReturn(mPinnerService);
        when(mInjector.getJobScheduler()).thenReturn(mJobScheduler);
        when(mInjector.getPackageManagerService()).thenReturn(mPackageManager);

        // These mocking can be overwritten in some tests but still keep it here as alternative
        // takes too many repetitive codes.
        when(mInjector.getDataDirUsableSpace()).thenReturn(USABLE_SPACE_NORMAL);
        when(mInjector.getDataDirStorageLowBytes()).thenReturn(STORAGE_LOW_BYTES);
        when(mInjector.getDexOptThermalCutoff()).thenReturn(PowerManager.THERMAL_STATUS_CRITICAL);
        when(mInjector.getCurrentThermalStatus()).thenReturn(PowerManager.THERMAL_STATUS_NONE);
        when(mInjector.supportSecondaryDex()).thenReturn(true);
        setupDexOptHelper();

        mService = new BackgroundDexOptService(mInjector);
    }

    private void setupDexOptHelper() {
        when(mDexOptHelper.getOptimizablePackages(any())).thenReturn(DEFAULT_PACKAGE_LIST);
        when(mDexOptHelper.performDexOptWithStatus(any())).thenAnswer(inv -> {
            DexoptOptions opt = inv.getArgument(0);
            if (opt.getPackageName().equals(PACKAGE_AAA)) {
                return mDexOptResultForPackageAAA;
            }
            return PackageDexOptimizer.DEX_OPT_PERFORMED;
        });
        when(mDexOptHelper.performDexOpt(any())).thenReturn(true);
    }

    @After
    public void tearDown() throws Exception {
        LocalServices.removeServiceForTest(BackgroundDexOptService.class);
    }

    @Test
    public void testGetService() {
        assertThat(BackgroundDexOptService.getService()).isEqualTo(mService);
    }

    @Test
    public void testBootCompleted() throws Exception {
        initUntilBootCompleted();
    }

    @Test
    public void testNoExecutionForIdleJobBeforePostBootUpdate() throws Exception {
        initUntilBootCompleted();

        assertThat(mService.onStartJob(mJobServiceForIdle, mJobParametersForIdle)).isFalse();
    }

    @Test
    public void testNoExecutionForLowStorage() throws Exception {
        initUntilBootCompleted();
        when(mPackageManager.isStorageLow()).thenReturn(true);

        assertThat(mService.onStartJob(mJobServiceForPostBoot,
                mJobParametersForPostBoot)).isFalse();
        verify(mDexOptHelper, never()).performDexOpt(any());
    }

    @Test
    public void testNoExecutionForNoOptimizablePackages() throws Exception {
        initUntilBootCompleted();
        when(mDexOptHelper.getOptimizablePackages(any())).thenReturn(Collections.emptyList());

        assertThat(mService.onStartJob(mJobServiceForPostBoot,
                mJobParametersForPostBoot)).isFalse();
        verify(mDexOptHelper, never()).performDexOpt(any());
    }

    @Test
    public void testPostBootUpdateFullRun() throws Exception {
        initUntilBootCompleted();

        runFullJob(mJobServiceForPostBoot, mJobParametersForPostBoot,
                /* expectedReschedule= */ false, /* expectedStatus= */ STATUS_OK,
                /* totalJobFinishedWithParams= */ 1, /* expectedSkippedPackage= */ null);
    }

    @Test
    public void testPostBootUpdateFullRunWithPackageFailure() throws Exception {
        mDexOptResultForPackageAAA = PackageDexOptimizer.DEX_OPT_FAILED;

        initUntilBootCompleted();

        runFullJob(mJobServiceForPostBoot, mJobParametersForPostBoot,
                /* expectedReschedule= */ false, /* expectedStatus= */ STATUS_DEX_OPT_FAILED,
                /* totalJobFinishedWithParams= */ 1, /* expectedSkippedPackage= */ PACKAGE_AAA);

        assertThat(getFailedPackageNamesPrimary()).containsExactly(PACKAGE_AAA);
        assertThat(getFailedPackageNamesSecondary()).isEmpty();
    }

    @Test
    public void testIdleJobFullRun() throws Exception {
        initUntilBootCompleted();
        runFullJob(mJobServiceForPostBoot, mJobParametersForPostBoot,
                /* expectedReschedule= */ false, /* expectedStatus= */ STATUS_OK,
                /* totalJobFinishedWithParams= */ 1, /* expectedSkippedPackage= */ null);
        runFullJob(mJobServiceForIdle, mJobParametersForIdle,
                /* expectedReschedule= */ false, /* expectedStatus= */ STATUS_OK,
                /* totalJobFinishedWithParams= */ 1, /* expectedSkippedPackage= */ null);
    }

    @Test
    public void testIdleJobFullRunWithFailureOnceAndSuccessAfterUpdate() throws Exception {
        mDexOptResultForPackageAAA = PackageDexOptimizer.DEX_OPT_FAILED;

        initUntilBootCompleted();

        runFullJob(mJobServiceForPostBoot, mJobParametersForPostBoot,
                /* expectedReschedule= */ false, /* expectedStatus= */ STATUS_DEX_OPT_FAILED,
                /* totalJobFinishedWithParams= */ 1, /* expectedSkippedPackage= */ PACKAGE_AAA);

        assertThat(getFailedPackageNamesPrimary()).containsExactly(PACKAGE_AAA);
        assertThat(getFailedPackageNamesSecondary()).isEmpty();

        runFullJob(mJobServiceForIdle, mJobParametersForIdle,
                /* expectedReschedule= */ false, /* expectedStatus= */ STATUS_OK,
                /* totalJobFinishedWithParams= */ 1, /* expectedSkippedPackage= */ PACKAGE_AAA);

        assertThat(getFailedPackageNamesPrimary()).containsExactly(PACKAGE_AAA);
        assertThat(getFailedPackageNamesSecondary()).isEmpty();

        mService.notifyPackageChanged(PACKAGE_AAA);

        assertThat(getFailedPackageNamesPrimary()).isEmpty();
        assertThat(getFailedPackageNamesSecondary()).isEmpty();

        // Succeed this time.
        mDexOptResultForPackageAAA = PackageDexOptimizer.DEX_OPT_PERFORMED;

        runFullJob(mJobServiceForIdle, mJobParametersForIdle,
                /* expectedReschedule= */ false, /* expectedStatus= */ STATUS_OK,
                /* totalJobFinishedWithParams= */ 2, /* expectedSkippedPackage= */ null);

        assertThat(getFailedPackageNamesPrimary()).isEmpty();
        assertThat(getFailedPackageNamesSecondary()).isEmpty();
    }

    @Test
    public void testIdleJobFullRunWithFatalError() throws Exception {
        initUntilBootCompleted();
        runFullJob(mJobServiceForPostBoot, mJobParametersForPostBoot,
                /* expectedReschedule= */ false, /* expectedStatus= */ STATUS_OK,
                /* totalJobFinishedWithParams= */ 1, /* expectedSkippedPackage= */ null);

        doThrow(RuntimeException.class).when(mDexOptHelper).performDexOptWithStatus(any());

        runFullJob(mJobServiceForIdle, mJobParametersForIdle,
                /* expectedReschedule= */ false, /* expectedStatus= */ STATUS_FATAL_ERROR,
                /* totalJobFinishedWithParams= */ 1, /* expectedSkippedPackage= */ null);
    }

    @Test
    public void testSystemReadyWhenDisabled() throws Exception {
        when(mInjector.isBackgroundDexOptDisabled()).thenReturn(true);

        mService.systemReady();

        verify(mContext, never()).registerReceiver(any(), any());
    }

    @Test
    public void testStopByCancelFlag() throws Exception {
        when(mInjector.createAndStartThread(any(), any())).thenReturn(Thread.currentThread());
        initUntilBootCompleted();

        assertThat(mService.onStartJob(mJobServiceForPostBoot, mJobParametersForPostBoot)).isTrue();

        ArgumentCaptor<Runnable> argDexOptThreadRunnable = ArgumentCaptor.forClass(Runnable.class);
        verify(mInjector, atLeastOnce()).createAndStartThread(any(),
                argDexOptThreadRunnable.capture());

        // Stopping requires a separate thread
        HandlerThread cancelThread = new HandlerThread("Stopping");
        cancelThread.start();
        when(mInjector.createAndStartThread(any(), any())).thenReturn(cancelThread);

        // Cancel
        assertThat(mService.onStopJob(mJobServiceForPostBoot, mJobParametersForPostBoot)).isTrue();

        // Capture Runnable for cancel
        ArgumentCaptor<Runnable> argCancelThreadRunnable = ArgumentCaptor.forClass(Runnable.class);
        verify(mInjector, atLeastOnce()).createAndStartThread(any(),
                argCancelThreadRunnable.capture());

        // Execute cancelling part
        cancelThread.getThreadHandler().post(argCancelThreadRunnable.getValue());

        verify(mDexOptHelper, timeout(TEST_WAIT_TIMEOUT_MS)).controlDexOptBlocking(true);

        // Dexopt thread run and cancelled
        argDexOptThreadRunnable.getValue().run();

        // Wait until cancellation Runnable is completed.
        assertThat(cancelThread.getThreadHandler().runWithScissors(
                argCancelThreadRunnable.getValue(), TEST_WAIT_TIMEOUT_MS)).isTrue();

        // Now cancel completed
        verify(mJobServiceForPostBoot).jobFinished(mJobParametersForPostBoot, true);
        verifyLastControlDexOptBlockingCall(false);
    }

    @Test
    public void testPostUpdateCancelFirst() throws Exception {
        initUntilBootCompleted();
        when(mInjector.createAndStartThread(any(), any())).thenAnswer(
                i -> createAndStartExecutionThread(i.getArgument(0), i.getArgument(1)));

        // Start
        assertThat(mService.onStartJob(mJobServiceForPostBoot, mJobParametersForPostBoot)).isTrue();
        // Cancel
        assertThat(mService.onStopJob(mJobServiceForPostBoot, mJobParametersForPostBoot)).isTrue();

        mCancelThread.runActualRunnable();

        // Wait until cancel has set the flag.
        verify(mDexOptHelper, timeout(TEST_WAIT_TIMEOUT_MS)).controlDexOptBlocking(
                true);

        mDexOptThread.runActualRunnable();

        // All threads should finish.
        mDexOptThread.join(TEST_WAIT_TIMEOUT_MS);
        mCancelThread.join(TEST_WAIT_TIMEOUT_MS);

        // Retry later if post boot job was cancelled
        verify(mJobServiceForPostBoot).jobFinished(mJobParametersForPostBoot, true);
        verifyLastControlDexOptBlockingCall(false);
    }

    @Test
    public void testPostUpdateCancelLater() throws Exception {
        initUntilBootCompleted();
        when(mInjector.createAndStartThread(any(), any())).thenAnswer(
                i -> createAndStartExecutionThread(i.getArgument(0), i.getArgument(1)));

        // Start
        assertThat(mService.onStartJob(mJobServiceForPostBoot, mJobParametersForPostBoot)).isTrue();
        // Cancel
        assertThat(mService.onStopJob(mJobServiceForPostBoot, mJobParametersForPostBoot)).isTrue();

        // Dexopt thread runs and finishes
        mDexOptThread.runActualRunnable();
        mDexOptThread.join(TEST_WAIT_TIMEOUT_MS);

        mCancelThread.runActualRunnable();
        mCancelThread.join(TEST_WAIT_TIMEOUT_MS);

        // Already completed before cancel, so no rescheduling.
        verify(mJobServiceForPostBoot).jobFinished(mJobParametersForPostBoot, false);
        verify(mDexOptHelper, never()).controlDexOptBlocking(true);
    }

    @Test
    public void testPeriodicJobCancelFirst() throws Exception {
        initUntilBootCompleted();
        when(mInjector.createAndStartThread(any(), any())).thenAnswer(
                i -> createAndStartExecutionThread(i.getArgument(0), i.getArgument(1)));

        // Start and finish post boot job
        assertThat(mService.onStartJob(mJobServiceForPostBoot, mJobParametersForPostBoot)).isTrue();
        mDexOptThread.runActualRunnable();
        mDexOptThread.join(TEST_WAIT_TIMEOUT_MS);

        // Start
        assertThat(mService.onStartJob(mJobServiceForIdle, mJobParametersForIdle)).isTrue();
        // Cancel
        assertThat(mService.onStopJob(mJobServiceForIdle, mJobParametersForIdle)).isTrue();

        mCancelThread.runActualRunnable();

        // Wait until cancel has set the flag.
        verify(mDexOptHelper, timeout(TEST_WAIT_TIMEOUT_MS)).controlDexOptBlocking(
                true);

        mDexOptThread.runActualRunnable();

        // All threads should finish.
        mDexOptThread.join(TEST_WAIT_TIMEOUT_MS);
        mCancelThread.join(TEST_WAIT_TIMEOUT_MS);

        // Always reschedule for periodic job
        verify(mJobServiceForIdle).jobFinished(mJobParametersForIdle, false);
        verifyLastControlDexOptBlockingCall(false);
    }

    @Test
    public void testPeriodicJobCancelLater() throws Exception {
        initUntilBootCompleted();
        when(mInjector.createAndStartThread(any(), any())).thenAnswer(
                i -> createAndStartExecutionThread(i.getArgument(0), i.getArgument(1)));

        // Start and finish post boot job
        assertThat(mService.onStartJob(mJobServiceForPostBoot, mJobParametersForPostBoot)).isTrue();
        mDexOptThread.runActualRunnable();
        mDexOptThread.join(TEST_WAIT_TIMEOUT_MS);

        // Start
        assertThat(mService.onStartJob(mJobServiceForIdle, mJobParametersForIdle)).isTrue();
        // Cancel
        assertThat(mService.onStopJob(mJobServiceForIdle, mJobParametersForIdle)).isTrue();

        // Dexopt thread finishes first.
        mDexOptThread.runActualRunnable();
        mDexOptThread.join(TEST_WAIT_TIMEOUT_MS);

        mCancelThread.runActualRunnable();
        mCancelThread.join(TEST_WAIT_TIMEOUT_MS);

        // Always reschedule for periodic job
        verify(mJobServiceForIdle).jobFinished(mJobParametersForIdle, false);
        verify(mDexOptHelper, never()).controlDexOptBlocking(true);
    }

    @Test
    public void testStopByThermal() throws Exception {
        when(mInjector.createAndStartThread(any(), any())).thenReturn(Thread.currentThread());
        initUntilBootCompleted();

        assertThat(mService.onStartJob(mJobServiceForPostBoot, mJobParametersForPostBoot)).isTrue();

        ArgumentCaptor<Runnable> argThreadRunnable = ArgumentCaptor.forClass(Runnable.class);
        verify(mInjector, atLeastOnce()).createAndStartThread(any(), argThreadRunnable.capture());

        // Thermal cancel level
        when(mInjector.getCurrentThermalStatus()).thenReturn(PowerManager.THERMAL_STATUS_CRITICAL);

        argThreadRunnable.getValue().run();

        verify(mJobServiceForPostBoot).jobFinished(mJobParametersForPostBoot, true);
        verifyLastControlDexOptBlockingCall(false);
    }

    @Test
    public void testRunShellCommandWithInvalidUid() {
        // Test uid cannot execute the command APIs
        assertThrows(SecurityException.class, () -> mService.runBackgroundDexoptJob(null));
    }

    @Test
    public void testCancelShellCommandWithInvalidUid() {
        // Test uid cannot execute the command APIs
        assertThrows(SecurityException.class, () -> mService.cancelBackgroundDexoptJob());
    }

    @Test
    public void testDisableJobSchedulerJobs() throws Exception {
        when(mInjector.getCallingUid()).thenReturn(Process.SHELL_UID);
        mService.setDisableJobSchedulerJobs(true);
        assertThat(mService.onStartJob(mJobServiceForIdle, mJobParametersForIdle)).isFalse();
        verify(mDexOptHelper, never()).performDexOpt(any());
        verify(mDexOptHelper, never()).performDexOptWithStatus(any());
    }

    @Test
    public void testSetDisableJobSchedulerJobsWithInvalidUid() {
        // Test uid cannot execute the command APIs
        assertThrows(SecurityException.class, () -> mService.setDisableJobSchedulerJobs(true));
    }

    private void initUntilBootCompleted() throws Exception {
        ArgumentCaptor<BroadcastReceiver> argReceiver = ArgumentCaptor.forClass(
                BroadcastReceiver.class);
        ArgumentCaptor<IntentFilter> argIntentFilter = ArgumentCaptor.forClass(IntentFilter.class);

        mService.systemReady();

        verify(mContext).registerReceiver(argReceiver.capture(), argIntentFilter.capture());
        assertThat(argIntentFilter.getValue().getAction(0)).isEqualTo(Intent.ACTION_BOOT_COMPLETED);

        argReceiver.getValue().onReceive(mContext, null);

        verify(mContext).unregisterReceiver(argReceiver.getValue());
        ArgumentCaptor<JobInfo> argJobs = ArgumentCaptor.forClass(JobInfo.class);
        verify(mJobScheduler, times(2)).schedule(argJobs.capture());

        List<Integer> expectedJobIds = Arrays.asList(BackgroundDexOptService.JOB_IDLE_OPTIMIZE,
                BackgroundDexOptService.JOB_POST_BOOT_UPDATE);
        List<Integer> jobIds = argJobs.getAllValues().stream().map(job -> job.getId()).collect(
                Collectors.toList());
        assertThat(jobIds).containsExactlyElementsIn(expectedJobIds);
    }

    private void verifyLastControlDexOptBlockingCall(boolean expected) throws Exception {
        ArgumentCaptor<Boolean> argDexOptBlock = ArgumentCaptor.forClass(Boolean.class);
        verify(mDexOptHelper, atLeastOnce()).controlDexOptBlocking(argDexOptBlock.capture());
        assertThat(argDexOptBlock.getValue()).isEqualTo(expected);
    }

    private void runFullJob(BackgroundDexOptJobService jobService, JobParameters params,
            boolean expectedReschedule, int expectedStatus, int totalJobFinishedWithParams,
            @Nullable String expectedSkippedPackage) throws Exception {
        when(mInjector.createAndStartThread(any(), any())).thenReturn(Thread.currentThread());
        addFullRunSequence(expectedSkippedPackage);
        assertThat(mService.onStartJob(jobService, params)).isTrue();

        ArgumentCaptor<Runnable> argThreadRunnable = ArgumentCaptor.forClass(Runnable.class);
        verify(mInjector, atLeastOnce()).createAndStartThread(any(), argThreadRunnable.capture());

        try {
            argThreadRunnable.getValue().run();
        } catch (RuntimeException e) {
            if (expectedStatus != STATUS_FATAL_ERROR) {
                throw e;
            }
        }

        verify(jobService, times(totalJobFinishedWithParams)).jobFinished(params,
                expectedReschedule);
        // Never block
        verify(mDexOptHelper, never()).controlDexOptBlocking(true);
        if (expectedStatus != STATUS_FATAL_ERROR) {
            verifyPerformDexOpt();
        }
        assertThat(getLastExecutionStatus()).isEqualTo(expectedStatus);
    }

    private void verifyPerformDexOpt() {
        InOrder inOrder = inOrder(mDexOptHelper);
        inOrder.verify(mDexOptHelper).getOptimizablePackages(any());
        for (DexOptInfo info : mDexInfoSequence) {
            if (info.isPrimary) {
                verify(mDexOptHelper).performDexOptWithStatus(
                        argThat((option) -> option.getPackageName().equals(info.packageName)
                                && !option.isDexoptOnlySecondaryDex()));
            } else {
                inOrder.verify(mDexOptHelper).performDexOpt(
                        argThat((option) -> option.getPackageName().equals(info.packageName)
                                && option.isDexoptOnlySecondaryDex()));
            }
        }

        // Even InOrder cannot check the order if the same call is made multiple times.
        // To check the order across multiple runs, we reset the mock so that order can be checked
        // in each call.
        mDexInfoSequence.clear();
        reset(mDexOptHelper);
        setupDexOptHelper();
    }

    private String findDumpValueForKey(String key) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintWriter pw = new PrintWriter(out, true);
        IndentingPrintWriter writer = new IndentingPrintWriter(pw, "");
        try {
            mService.dump(writer);
            writer.flush();
            Log.i(TAG, "dump output:" + out.toString());
            for (String line : out.toString().split(System.lineSeparator())) {
                String[] vals = line.split(":");
                if (vals[0].equals(key)) {
                    if (vals.length == 2) {
                        return vals[1].strip();
                    } else {
                        break;
                    }
                }
            }
            return "";
        } finally {
            writer.close();
        }
    }

    List<String> findStringListFromDump(String key) {
        String values = findDumpValueForKey(key);
        if (values.isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.asList(values.split(","));
    }

    private List<String> getFailedPackageNamesPrimary() {
        return findStringListFromDump("mFailedPackageNamesPrimary");
    }

    private List<String> getFailedPackageNamesSecondary() {
        return findStringListFromDump("mFailedPackageNamesSecondary");
    }

    private int getLastExecutionStatus() {
        return Integer.parseInt(findDumpValueForKey("mLastExecutionStatus"));
    }

    private static class DexOptInfo {
        public final String packageName;
        public final boolean isPrimary;

        private DexOptInfo(String packageName, boolean isPrimary) {
            this.packageName = packageName;
            this.isPrimary = isPrimary;
        }
    }

    private void addFullRunSequence(@Nullable String expectedSkippedPackage) {
        for (String packageName : DEFAULT_PACKAGE_LIST) {
            if (packageName.equals(expectedSkippedPackage)) {
                // only fails primary dexopt in mocking but add secodary
                mDexInfoSequence.add(new DexOptInfo(packageName, /* isPrimary= */ false));
            } else {
                mDexInfoSequence.add(new DexOptInfo(packageName, /* isPrimary= */ true));
                mDexInfoSequence.add(new DexOptInfo(packageName, /* isPrimary= */ false));
            }
        }
    }

    private static class StartAndWaitThread extends Thread {
        private final Runnable mActualRunnable;
        private final CountDownLatch mLatch = new CountDownLatch(1);

        private StartAndWaitThread(String name, Runnable runnable) {
            super(name);
            mActualRunnable = runnable;
        }

        private void runActualRunnable() {
            mLatch.countDown();
        }

        @Override
        public void run() {
            // Thread is started but does not run actual code. This is for controlling the execution
            // order while still meeting Thread.isAlive() check.
            try {
                mLatch.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            mActualRunnable.run();
        }
    }

    private Thread createAndStartExecutionThread(String name, Runnable runnable) {
        final boolean isDexOptThread = !name.equals("DexOptCancel");
        StartAndWaitThread thread = new StartAndWaitThread(name, runnable);
        if (isDexOptThread) {
            mDexOptThread = thread;
        } else {
            mCancelThread = thread;
        }
        thread.start();
        return thread;
    }
}

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
package com.android.server.selinux;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.job.JobParameters;
import android.app.job.JobService;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class SelinuxAuditLogsJobTest {

    private final JobService mJobService = mock(JobService.class);
    private final SelinuxAuditLogsCollector mAuditLogsCollector =
            mock(SelinuxAuditLogsCollector.class);
    private final JobParameters mParams = createJobParameters(666);
    private final SelinuxAuditLogsJob mAuditLogsJob = new SelinuxAuditLogsJob(mAuditLogsCollector);

    @Before
    public void setUp() {
        mAuditLogsCollector.mStopRequested = new AtomicBoolean();
    }

    @Test
    public void testFinishSuccessfully() {
        when(mAuditLogsCollector.collect(anyInt())).thenReturn(true);

        mAuditLogsJob.start(mJobService, mParams);

        verify(mJobService).jobFinished(mParams, /* wantsReschedule= */ false);
        assertThat(mAuditLogsJob.isRunning()).isFalse();
    }

    @Test
    public void testInterrupt() {
        when(mAuditLogsCollector.collect(anyInt())).thenReturn(false);

        mAuditLogsJob.start(mJobService, mParams);

        verify(mJobService, never()).jobFinished(any(), anyBoolean());
        assertThat(mAuditLogsJob.isRunning()).isFalse();
    }

    @Test
    public void testInterruptAndResume() {
        when(mAuditLogsCollector.collect(anyInt())).thenReturn(false);
        mAuditLogsJob.start(mJobService, mParams);
        verify(mJobService, never()).jobFinished(any(), anyBoolean());

        when(mAuditLogsCollector.collect(anyInt())).thenReturn(true);
        mAuditLogsJob.start(mJobService, mParams);
        verify(mJobService).jobFinished(mParams, /* wantsReschedule= */ false);
        assertThat(mAuditLogsJob.isRunning()).isFalse();
    }

    @Test
    public void testRequestStop() throws InterruptedException {
        Semaphore isRunning = new Semaphore(0);
        Semaphore stopRequested = new Semaphore(0);
        AtomicReference<Throwable> uncaughtException = new AtomicReference<>();

        // Set up a logs collector that runs in a worker thread until a stop is requested.
        when(mAuditLogsCollector.collect(anyInt()))
                .thenAnswer(
                        invocation -> {
                            assertThat(mAuditLogsCollector.mStopRequested.get()).isFalse();
                            isRunning.release();
                            stopRequested.acquire();
                            assertThat(mAuditLogsCollector.mStopRequested.get()).isTrue();
                            return true;
                        });
        Thread jobThread =
                new Thread(
                        () -> {
                            mAuditLogsJob.start(mJobService, mParams);
                        });
        jobThread.setUncaughtExceptionHandler(
                (thread, exception) -> uncaughtException.set(exception));
        assertThat(mAuditLogsJob.isRunning()).isFalse();
        jobThread.start();

        // Wait until the worker thread is running.
        isRunning.acquire();
        assertThat(mAuditLogsJob.isRunning()).isTrue();

        // Request for the worker thread to stop, and wait to verify.
        mAuditLogsJob.requestStop();
        stopRequested.release();
        jobThread.join();
        assertThat(uncaughtException.get()).isNull();
        assertThat(mAuditLogsJob.isRunning()).isFalse();
    }

    private static JobParameters createJobParameters(int jobId) {
        JobParameters jobParameters = mock(JobParameters.class);
        when(jobParameters.getJobId()).thenReturn(jobId);
        return jobParameters;
    }
}

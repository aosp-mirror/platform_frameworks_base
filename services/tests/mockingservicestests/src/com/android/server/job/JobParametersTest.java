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

package com.android.server.job;

import static android.app.job.Flags.FLAG_CLEANUP_EMPTY_JOBS;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;

import android.app.job.IJobCallback;
import android.app.job.JobParameters;
import android.net.Uri;
import android.os.Parcel;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.platform.test.flag.junit.SetFlagsRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

public class JobParametersTest {
    private static final String TAG = JobParametersTest.class.getSimpleName();
    private static final int TEST_JOB_ID_1 = 123;
    private static final String TEST_NAMESPACE = "TEST_NAMESPACE";
    private static final String TEST_DEBUG_STOP_REASON = "TEST_DEBUG_STOP_REASON";
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    private MockitoSession mMockingSession;
    @Mock private Parcel mMockParcel;
    @Mock private IJobCallback.Stub mMockJobCallbackStub;

    @Before
    public void setUp() throws Exception {
        mMockingSession =
                mockitoSession().initMocks(this).strictness(Strictness.LENIENT).startMocking();
    }

    @After
    public void tearDown() {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }

        when(mMockParcel.readInt())
                .thenReturn(TEST_JOB_ID_1) // Job ID
                .thenReturn(0) // No clip data
                .thenReturn(0) // No deadline expired
                .thenReturn(0) // No network
                .thenReturn(0) // No stop reason
                .thenReturn(0); // Internal stop reason
        when(mMockParcel.readString())
                .thenReturn(TEST_NAMESPACE) // Job namespace
                .thenReturn(TEST_DEBUG_STOP_REASON); // Debug stop reason
        when(mMockParcel.readPersistableBundle()).thenReturn(null);
        when(mMockParcel.readBundle()).thenReturn(null);
        when(mMockParcel.readStrongBinder()).thenReturn(mMockJobCallbackStub);
        when(mMockParcel.readBoolean())
                .thenReturn(false) // expedited
                .thenReturn(false); // user initiated
        when(mMockParcel.createTypedArray(any())).thenReturn(new Uri[0]);
        when(mMockParcel.createStringArray()).thenReturn(new String[0]);
    }

    /**
     * Test to verify that the JobParameters created using Non-Parcelable constructor has not
     * cleaner attached
     */
    @Test
    public void testJobParametersNonParcelableConstructor_noCleaner() {
        JobParameters jobParameters =
                new JobParameters(
                        null,
                        TEST_NAMESPACE,
                        TEST_JOB_ID_1,
                        null,
                        null,
                        null,
                        0,
                        false,
                        false,
                        false,
                        null,
                        null,
                        null);

        // Verify that cleaner is not registered
        assertThat(jobParameters.getCleanable()).isNull();
        assertThat(jobParameters.getJobCleanupCallback()).isNull();
    }

    /**
     * Test to verify that the JobParameters created using Parcelable constructor has not cleaner
     * attached
     */
    @Test
    public void testJobParametersParcelableConstructor_noCleaner() {
        JobParameters jobParameters = JobParameters.CREATOR.createFromParcel(mMockParcel);

        // Verify that cleaner is not registered
        assertThat(jobParameters.getCleanable()).isNull();
        assertThat(jobParameters.getJobCleanupCallback()).isNull();
    }

    /** Test to verify that the JobParameters Cleaner is disabled */
    @RequiresFlagsEnabled(FLAG_CLEANUP_EMPTY_JOBS)
    @Test
    public void testCleanerWithLeakedJobCleanerDisabled_flagCleanupEmptyJobsEnabled() {
        // Inject real JobCallbackCleanup
        JobParameters jobParameters = JobParameters.CREATOR.createFromParcel(mMockParcel);

        // Enable the cleaner
        jobParameters.enableCleaner();

        // Verify the cleaner is enabled
        assertThat(jobParameters.getCleanable()).isNotNull();
        assertThat(jobParameters.getJobCleanupCallback()).isNotNull();
        assertThat(jobParameters.getJobCleanupCallback().isCleanerEnabled()).isTrue();

        // Disable the cleaner
        jobParameters.disableCleaner();

        // Verify the cleaner is disabled
        assertThat(jobParameters.getCleanable()).isNull();
        assertThat(jobParameters.getJobCleanupCallback()).isNull();
    }
}

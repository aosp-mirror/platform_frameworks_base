/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.job;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.job.JobInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManagerInternal;
import android.os.Build;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.LocalServices;
import com.android.server.job.controllers.JobStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class JobSetTest {
    private static final String TAG = JobSetTest.class.getSimpleName();
    private static final int SECONDARY_USER_ID_1 = 12;
    private static final int SECONDARY_USER_ID_2 = 13;

    private Context mContext;
    private ComponentName mComponent;
    private JobStore.JobSet mJobSet;

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getTargetContext();
        mComponent = new ComponentName(mContext, JobStoreTest.class);
        mJobSet = new JobStore.JobSet();
        final PackageManagerInternal pm = mock(PackageManagerInternal.class);
        when(pm.getPackageTargetSdkVersion(anyString()))
                .thenReturn(Build.VERSION_CODES.CUR_DEVELOPMENT);
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.addService(PackageManagerInternal.class, pm);
        assumeFalse("Test cannot run in user " + mContext.getUserId(),
                mContext.getUserId() == SECONDARY_USER_ID_1
                        || mContext.getUserId() == SECONDARY_USER_ID_2);
    }

    private JobStatus getJobStatusWithCallinUid(int jobId, int callingUid) {
        final JobInfo jobInfo = new JobInfo.Builder(jobId, mComponent)
                .setPeriodic(10)
                .setRequiresCharging(true)
                .build();
        return JobStatus.createFromJobInfo(jobInfo, callingUid, mContext.getPackageName(),
                mContext.getUserId(), "Test");
    }

    @Test
    public void testBothMapsHaveSameJobs() {
        final int callingUid1 = UserHandle.getUid(SECONDARY_USER_ID_1, 1);
        final int callingUid2 = UserHandle.getUid(SECONDARY_USER_ID_2, 1);
        final JobStatus testJob1 = getJobStatusWithCallinUid(1, callingUid1);
        final JobStatus testJob2 = getJobStatusWithCallinUid(2, callingUid2);
        mJobSet.add(testJob1);
        mJobSet.add(testJob2);
        for (int i = 11; i <= 20; i++) {
            mJobSet.add(getJobStatusWithCallinUid(i, (i%2 == 0) ? callingUid2 : callingUid1));
        }
        assertHaveSameJobs(mJobSet.mJobsPerSourceUid, mJobSet.mJobs);
        mJobSet.remove(testJob1);
        mJobSet.remove(testJob2);
        assertHaveSameJobs(mJobSet.mJobsPerSourceUid, mJobSet.mJobs);
        mJobSet.removeJobsOfNonUsers(new int[] {mContext.getUserId(), SECONDARY_USER_ID_1});
        assertHaveSameJobs(mJobSet.mJobsPerSourceUid, mJobSet.mJobs);
        mJobSet.removeJobsOfNonUsers(new int[] {mContext.getUserId()});
        assertTrue("mJobs should be empty", mJobSet.mJobs.size() == 0);
        assertTrue("mJobsPerSourceUid should be empty", mJobSet.mJobsPerSourceUid.size() == 0);
    }

    private static void assertHaveSameJobs(SparseArray<ArraySet<JobStatus>> map1,
            SparseArray<ArraySet<JobStatus>> map2) {
        final ArraySet<JobStatus> set1 = new ArraySet<>();
        final ArraySet<JobStatus> set2 = new ArraySet<>();
        int size1 = 0;
        for (int i = 0; i < map1.size(); i++) {
            final ArraySet<JobStatus> jobs = map1.valueAt(i);
            if (jobs == null) return;
            size1 += jobs.size();
            set1.addAll(jobs);
        }
        for (int i = 0; i < map2.size(); i++) {
            final ArraySet<JobStatus> jobs = map2.valueAt(i);
            if (jobs == null) return;
            size1 -= jobs.size();
            set2.addAll(jobs);
        }
        if (size1 != 0 || !set1.equals(set2)) {
            dump("map1", map1);
            dump("map2", map2);
            fail("Both maps have different sets of jobs");
        }
    }

    private static void dump(String prefix, SparseArray<ArraySet<JobStatus>> jobMap) {
        final StringBuilder str = new StringBuilder();
        for (int i = 0; i < jobMap.size(); i++) {
            final ArraySet<JobStatus> jobs = jobMap.valueAt(i);
            if (jobs == null) return;
            str.append("[Key: " + jobMap.keyAt(i) + ", Value: {");
            for (int j = 0; j < jobs.size(); j++) {
                final JobStatus job = jobs.valueAt(j);
                str.append("(s=" + job.getSourceUid() + ", c=" + job.getUid() + "), ");
            }
            str.append("}], ");
        }
        Log.d(TAG, prefix + ": " + str.toString());
    }

    @After
    public void tearDown() throws Exception {
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
    }
}

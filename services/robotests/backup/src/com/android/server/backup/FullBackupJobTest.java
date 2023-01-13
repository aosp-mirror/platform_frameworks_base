/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.backup;

import static com.google.common.truth.Truth.assertThat;

import android.annotation.UserIdInt;
import android.app.job.JobScheduler;
import android.content.Context;
import android.os.Handler;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;

import com.android.server.testing.shadows.ShadowSystemServiceRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowJobScheduler;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = {ShadowJobScheduler.class, ShadowSystemServiceRegistry.class})
@Presubmit
public class FullBackupJobTest {
    private Context mContext;
    private BackupManagerConstants mConstants;
    private ShadowJobScheduler mShadowJobScheduler;

    @UserIdInt private int mUserOneId;
    @UserIdInt private int mUserTwoId;

    @Before
    public void setUp() throws Exception {
        mContext = RuntimeEnvironment.application;
        mConstants = new BackupManagerConstants(Handler.getMain(), mContext.getContentResolver());
        mConstants.start();

        mShadowJobScheduler = Shadows.shadowOf(mContext.getSystemService(JobScheduler.class));

        mUserOneId = UserHandle.USER_SYSTEM;
        mUserTwoId = mUserOneId + 1;
    }

    @After
    public void tearDown() throws Exception {
        mConstants.stop();
        FullBackupJob.cancel(mUserOneId, mContext);
        FullBackupJob.cancel(mUserTwoId, mContext);
    }

    @Test
    public void testSchedule_afterScheduling_jobExists() {
        FullBackupJob.schedule(mUserOneId, mContext, 0, mConstants);
        FullBackupJob.schedule(mUserTwoId, mContext, 0, mConstants);

        assertThat(mShadowJobScheduler.getPendingJob(getJobIdForUserId(mUserOneId))).isNotNull();
        assertThat(mShadowJobScheduler.getPendingJob(getJobIdForUserId(mUserTwoId))).isNotNull();
    }

    @Test
    public void testCancel_afterCancelling_jobDoesntExist() {
        FullBackupJob.schedule(mUserOneId, mContext, 0, mConstants);
        FullBackupJob.schedule(mUserTwoId, mContext, 0, mConstants);
        FullBackupJob.cancel(mUserOneId, mContext);
        FullBackupJob.cancel(mUserTwoId, mContext);

        assertThat(mShadowJobScheduler.getPendingJob(getJobIdForUserId(mUserOneId))).isNull();
        assertThat(mShadowJobScheduler.getPendingJob(getJobIdForUserId(mUserTwoId))).isNull();
    }
//
    @Test
    public void testSchedule_onlySchedulesForRequestedUser() {
        FullBackupJob.schedule(mUserOneId, mContext, 0, mConstants);

        assertThat(mShadowJobScheduler.getPendingJob(getJobIdForUserId(mUserOneId))).isNotNull();
        assertThat(mShadowJobScheduler.getPendingJob(getJobIdForUserId(mUserTwoId))).isNull();
    }
//
    @Test
    public void testCancel_onlyCancelsForRequestedUser() {
        FullBackupJob.schedule(mUserOneId, mContext, 0, mConstants);
        FullBackupJob.schedule(mUserTwoId, mContext, 0, mConstants);
        FullBackupJob.cancel(mUserOneId, mContext);

        assertThat(mShadowJobScheduler.getPendingJob(getJobIdForUserId(mUserOneId))).isNull();
        assertThat(mShadowJobScheduler.getPendingJob(getJobIdForUserId(mUserTwoId))).isNotNull();
    }

    private static int getJobIdForUserId(int userId) {
        return JobIdManager.getJobIdForUserId(FullBackupJob.MIN_JOB_ID, FullBackupJob.MAX_JOB_ID,
                userId);
    }
}

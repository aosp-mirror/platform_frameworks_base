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

package com.android.server.job;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.server.job.JobConcurrencyManager.KEY_PKG_CONCURRENCY_LIMIT_EJ;
import static com.android.server.job.JobConcurrencyManager.KEY_PKG_CONCURRENCY_LIMIT_REGULAR;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.ActivityManagerInternal;
import android.app.job.JobInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.os.UserHandle;
import android.provider.DeviceConfig;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.R;
import com.android.server.LocalServices;
import com.android.server.job.JobConcurrencyManager.GracePeriodObserver;
import com.android.server.job.JobConcurrencyManager.WorkTypeConfig;
import com.android.server.job.controllers.JobStatus;
import com.android.server.pm.UserManagerInternal;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public final class JobConcurrencyManagerTest {
    private static final int UNAVAILABLE_USER = 0;
    private JobConcurrencyManager mJobConcurrencyManager;
    private UserManagerInternal mUserManagerInternal;
    private ActivityManagerInternal mActivityManagerInternal;
    private int mNextUserId;
    private int mDefaultUserId;
    private GracePeriodObserver mGracePeriodObserver;
    private Context mContext;
    private Resources mResources;
    private PendingJobQueue mPendingJobQueue;
    private DeviceConfig.Properties.Builder mConfigBuilder;

    @BeforeClass
    public static void setUpOnce() {
        LocalServices.addService(UserManagerInternal.class, mock(UserManagerInternal.class));
        LocalServices.addService(
                ActivityManagerInternal.class, mock(ActivityManagerInternal.class));
    }

    @AfterClass
    public static void tearDownOnce() {
        LocalServices.removeServiceForTest(UserManagerInternal.class);
        LocalServices.removeServiceForTest(ActivityManagerInternal.class);
    }

    @Before
    public void setUp() {
        final JobSchedulerService jobSchedulerService = mock(JobSchedulerService.class);
        mContext = mock(Context.class);
        mResources = mock(Resources.class);
        doReturn(true).when(mResources).getBoolean(
                R.bool.config_jobSchedulerRestrictBackgroundUser);
        when(mContext.getResources()).thenReturn(mResources);
        doReturn(mContext).when(jobSchedulerService).getTestableContext();
        mConfigBuilder = new DeviceConfig.Properties.Builder(DeviceConfig.NAMESPACE_JOB_SCHEDULER);
        mPendingJobQueue = new PendingJobQueue();
        doReturn(mPendingJobQueue).when(jobSchedulerService).getPendingJobQueue();
        mJobConcurrencyManager = new JobConcurrencyManager(jobSchedulerService);
        mGracePeriodObserver = mock(GracePeriodObserver.class);
        mUserManagerInternal = LocalServices.getService(UserManagerInternal.class);
        mActivityManagerInternal = LocalServices.getService(ActivityManagerInternal.class);
        mDefaultUserId = mNextUserId;
        createCurrentUser(true);
        mNextUserId = 10;
        mJobConcurrencyManager.mGracePeriodObserver = mGracePeriodObserver;
    }

    @After
    public void tearDown() throws Exception {
        resetConfig();
    }

    @Test
    public void testIsPkgConcurrencyLimited_top() {
        final JobStatus topJob = createJob(mDefaultUserId * UserHandle.PER_USER_RANGE, 0);
        topJob.lastEvaluatedBias = JobInfo.BIAS_TOP_APP;

        assertFalse(mJobConcurrencyManager.isPkgConcurrencyLimitedLocked(topJob));

        // Pending jobs shouldn't affect TOP job's status.
        for (int i = 1; i <= JobConcurrencyManager.STANDARD_CONCURRENCY_LIMIT; ++i) {
            final JobStatus job = createJob(mDefaultUserId * UserHandle.PER_USER_RANGE + i);
            mPendingJobQueue.add(job);
        }
        assertFalse(mJobConcurrencyManager.isPkgConcurrencyLimitedLocked(topJob));

        // Already running jobs shouldn't affect TOP job's status.
        for (int i = 1; i <= JobConcurrencyManager.STANDARD_CONCURRENCY_LIMIT; ++i) {
            final JobStatus job = createJob(mDefaultUserId * UserHandle.PER_USER_RANGE, i);
            mJobConcurrencyManager.addRunningJobForTesting(job);
        }
        assertFalse(mJobConcurrencyManager.isPkgConcurrencyLimitedLocked(topJob));

        // Currently running or staged jobs shouldn't affect TOP job's status.
        final JobConcurrencyManager.PackageStats packageStats =
                mJobConcurrencyManager.getPackageStatsForTesting(
                        topJob.getSourceUserId(), topJob.getSourcePackageName());
        packageStats.numStagedEj = mJobConcurrencyManager.getPackageConcurrencyLimitEj();
        packageStats.numStagedRegular = mJobConcurrencyManager.getPackageConcurrencyLimitRegular();
        packageStats.numRunningEj = 0;
        packageStats.numRunningRegular = 0;
        assertFalse(mJobConcurrencyManager.isPkgConcurrencyLimitedLocked(topJob));

        packageStats.numStagedEj = 0;
        packageStats.numStagedRegular = 0;
        packageStats.numRunningEj = mJobConcurrencyManager.getPackageConcurrencyLimitEj();
        packageStats.numRunningRegular = mJobConcurrencyManager.getPackageConcurrencyLimitRegular();
        assertFalse(mJobConcurrencyManager.isPkgConcurrencyLimitedLocked(topJob));
    }

    @Test
    public void testIsPkgConcurrencyLimited_belowTotalLimit() throws Exception {
        final JobStatus testJob = createJob(mDefaultUserId * UserHandle.PER_USER_RANGE);

        setConcurrencyConfig(8);

        // Pending jobs below limit shouldn't affect job's status.
        for (int i = 0; i < 5; ++i) {
            final JobStatus job = createJob(mDefaultUserId * UserHandle.PER_USER_RANGE + i);
            mPendingJobQueue.add(job);
        }
        assertFalse(mJobConcurrencyManager.isPkgConcurrencyLimitedLocked(testJob));

        mPendingJobQueue.clear();

        // Already running jobs below limit shouldn't affect job's status.
        for (int i = 0; i < 4; ++i) {
            final JobStatus job = createJob(mDefaultUserId * UserHandle.PER_USER_RANGE + i);
            mJobConcurrencyManager.addRunningJobForTesting(job);
        }
        assertFalse(mJobConcurrencyManager.isPkgConcurrencyLimitedLocked(testJob));

        // Mix of pending + running.
        for (int i = 4; i < 8; ++i) {
            final JobStatus job = createJob(mDefaultUserId * UserHandle.PER_USER_RANGE + i);
            mPendingJobQueue.add(job);
        }
        assertFalse(mJobConcurrencyManager.isPkgConcurrencyLimitedLocked(testJob));
    }

    @Test
    public void testIsPkgConcurrencyLimited() throws Exception {
        final JobStatus testReg = createJob(mDefaultUserId * UserHandle.PER_USER_RANGE, 0);
        final JobStatus testEj = createJob(mDefaultUserId * UserHandle.PER_USER_RANGE, 1);
        spyOn(testEj);
        doReturn(true).when(testEj).shouldTreatAsExpeditedJob();

        setConcurrencyConfig(JobConcurrencyManager.STANDARD_CONCURRENCY_LIMIT);

        for (int i = 0; i < JobConcurrencyManager.STANDARD_CONCURRENCY_LIMIT; ++i) {
            final JobStatus job = createJob(mDefaultUserId * UserHandle.PER_USER_RANGE + i, i + 1);
            mPendingJobQueue.add(job);
        }

        // App has no running jobs, so shouldn't be limited.
        assertFalse(mJobConcurrencyManager.isPkgConcurrencyLimitedLocked(testEj));
        assertFalse(mJobConcurrencyManager.isPkgConcurrencyLimitedLocked(testReg));

        // Already running jobs shouldn't affect TOP job's status.
        final JobConcurrencyManager.PackageStats packageStats =
                mJobConcurrencyManager.getPackageStatsForTesting(
                        testReg.getSourceUserId(), testReg.getSourcePackageName());

        // Only running counts
        packageStats.numStagedEj = 0;
        packageStats.numStagedRegular = 0;
        packageStats.numRunningEj = 4;
        packageStats.numRunningRegular = 4;

        mConfigBuilder.setInt(KEY_PKG_CONCURRENCY_LIMIT_EJ, 8);
        mConfigBuilder.setInt(KEY_PKG_CONCURRENCY_LIMIT_REGULAR, 8);
        updateDeviceConfig();
        assertFalse(mJobConcurrencyManager.isPkgConcurrencyLimitedLocked(testEj));
        assertFalse(mJobConcurrencyManager.isPkgConcurrencyLimitedLocked(testReg));

        mConfigBuilder.setInt(KEY_PKG_CONCURRENCY_LIMIT_EJ, 8);
        mConfigBuilder.setInt(KEY_PKG_CONCURRENCY_LIMIT_REGULAR, 4);
        updateDeviceConfig();
        assertFalse(mJobConcurrencyManager.isPkgConcurrencyLimitedLocked(testEj));
        assertTrue(mJobConcurrencyManager.isPkgConcurrencyLimitedLocked(testReg));

        mConfigBuilder.setInt(KEY_PKG_CONCURRENCY_LIMIT_EJ, 8);
        mConfigBuilder.setInt(KEY_PKG_CONCURRENCY_LIMIT_REGULAR, 3);
        updateDeviceConfig();
        assertFalse(mJobConcurrencyManager.isPkgConcurrencyLimitedLocked(testEj));
        assertTrue(mJobConcurrencyManager.isPkgConcurrencyLimitedLocked(testReg));

        mConfigBuilder.setInt(KEY_PKG_CONCURRENCY_LIMIT_EJ, 4);
        mConfigBuilder.setInt(KEY_PKG_CONCURRENCY_LIMIT_REGULAR, 8);
        updateDeviceConfig();
        assertTrue(mJobConcurrencyManager.isPkgConcurrencyLimitedLocked(testEj));
        assertFalse(mJobConcurrencyManager.isPkgConcurrencyLimitedLocked(testReg));

        mConfigBuilder.setInt(KEY_PKG_CONCURRENCY_LIMIT_EJ, 3);
        mConfigBuilder.setInt(KEY_PKG_CONCURRENCY_LIMIT_REGULAR, 8);
        updateDeviceConfig();
        assertTrue(mJobConcurrencyManager.isPkgConcurrencyLimitedLocked(testEj));
        assertFalse(mJobConcurrencyManager.isPkgConcurrencyLimitedLocked(testReg));

        // Only staged counts
        packageStats.numStagedEj = 4;
        packageStats.numStagedRegular = 4;
        packageStats.numRunningEj = 0;
        packageStats.numRunningRegular = 0;

        mConfigBuilder.setInt(KEY_PKG_CONCURRENCY_LIMIT_EJ, 8);
        mConfigBuilder.setInt(KEY_PKG_CONCURRENCY_LIMIT_REGULAR, 8);
        updateDeviceConfig();
        assertFalse(mJobConcurrencyManager.isPkgConcurrencyLimitedLocked(testEj));
        assertFalse(mJobConcurrencyManager.isPkgConcurrencyLimitedLocked(testReg));

        mConfigBuilder.setInt(KEY_PKG_CONCURRENCY_LIMIT_EJ, 8);
        mConfigBuilder.setInt(KEY_PKG_CONCURRENCY_LIMIT_REGULAR, 4);
        updateDeviceConfig();
        assertFalse(mJobConcurrencyManager.isPkgConcurrencyLimitedLocked(testEj));
        assertTrue(mJobConcurrencyManager.isPkgConcurrencyLimitedLocked(testReg));

        mConfigBuilder.setInt(KEY_PKG_CONCURRENCY_LIMIT_EJ, 8);
        mConfigBuilder.setInt(KEY_PKG_CONCURRENCY_LIMIT_REGULAR, 3);
        updateDeviceConfig();
        assertFalse(mJobConcurrencyManager.isPkgConcurrencyLimitedLocked(testEj));
        assertTrue(mJobConcurrencyManager.isPkgConcurrencyLimitedLocked(testReg));

        mConfigBuilder.setInt(KEY_PKG_CONCURRENCY_LIMIT_EJ, 4);
        mConfigBuilder.setInt(KEY_PKG_CONCURRENCY_LIMIT_REGULAR, 8);
        updateDeviceConfig();
        assertTrue(mJobConcurrencyManager.isPkgConcurrencyLimitedLocked(testEj));
        assertFalse(mJobConcurrencyManager.isPkgConcurrencyLimitedLocked(testReg));

        mConfigBuilder.setInt(KEY_PKG_CONCURRENCY_LIMIT_EJ, 3);
        mConfigBuilder.setInt(KEY_PKG_CONCURRENCY_LIMIT_REGULAR, 8);
        updateDeviceConfig();
        assertTrue(mJobConcurrencyManager.isPkgConcurrencyLimitedLocked(testEj));
        assertFalse(mJobConcurrencyManager.isPkgConcurrencyLimitedLocked(testReg));

        // Running + staged counts
        packageStats.numStagedEj = 2;
        packageStats.numStagedRegular = 1;
        packageStats.numRunningEj = 2;
        packageStats.numRunningRegular = 3;

        mConfigBuilder.setInt(KEY_PKG_CONCURRENCY_LIMIT_EJ, 8);
        mConfigBuilder.setInt(KEY_PKG_CONCURRENCY_LIMIT_REGULAR, 8);
        updateDeviceConfig();
        assertFalse(mJobConcurrencyManager.isPkgConcurrencyLimitedLocked(testEj));
        assertFalse(mJobConcurrencyManager.isPkgConcurrencyLimitedLocked(testReg));

        mConfigBuilder.setInt(KEY_PKG_CONCURRENCY_LIMIT_EJ, 8);
        mConfigBuilder.setInt(KEY_PKG_CONCURRENCY_LIMIT_REGULAR, 4);
        updateDeviceConfig();
        assertFalse(mJobConcurrencyManager.isPkgConcurrencyLimitedLocked(testEj));
        assertTrue(mJobConcurrencyManager.isPkgConcurrencyLimitedLocked(testReg));

        mConfigBuilder.setInt(KEY_PKG_CONCURRENCY_LIMIT_EJ, 8);
        mConfigBuilder.setInt(KEY_PKG_CONCURRENCY_LIMIT_REGULAR, 3);
        updateDeviceConfig();
        assertFalse(mJobConcurrencyManager.isPkgConcurrencyLimitedLocked(testEj));
        assertTrue(mJobConcurrencyManager.isPkgConcurrencyLimitedLocked(testReg));

        mConfigBuilder.setInt(KEY_PKG_CONCURRENCY_LIMIT_EJ, 4);
        mConfigBuilder.setInt(KEY_PKG_CONCURRENCY_LIMIT_REGULAR, 8);
        updateDeviceConfig();
        assertTrue(mJobConcurrencyManager.isPkgConcurrencyLimitedLocked(testEj));
        assertFalse(mJobConcurrencyManager.isPkgConcurrencyLimitedLocked(testReg));

        mConfigBuilder.setInt(KEY_PKG_CONCURRENCY_LIMIT_EJ, 3);
        mConfigBuilder.setInt(KEY_PKG_CONCURRENCY_LIMIT_REGULAR, 8);
        updateDeviceConfig();
        assertTrue(mJobConcurrencyManager.isPkgConcurrencyLimitedLocked(testEj));
        assertFalse(mJobConcurrencyManager.isPkgConcurrencyLimitedLocked(testReg));
    }

    @Test
    public void testShouldRunAsFgUserJob_currentUser() {
        assertTrue(mJobConcurrencyManager.shouldRunAsFgUserJob(
                createJob(createCurrentUser(false))));
    }

    @Test
    public void testShouldRunAsFgUserJob_currentProfile() {
        assertTrue(mJobConcurrencyManager.shouldRunAsFgUserJob(
                createJob(createCurrentUser(true))));
    }

    @Test
    public void testShouldRunAsFgUserJob_primaryUser() {
        assertTrue(mJobConcurrencyManager.shouldRunAsFgUserJob(
                createJob(createPrimaryUser(false))));
    }

    @Test
    public void testShouldRunAsFgUserJob_primaryProfile() {
        assertTrue(mJobConcurrencyManager.shouldRunAsFgUserJob(
                createJob(createPrimaryUser(true))));
    }

    @Test
    public void testShouldRunAsFgUserJob_UnexpiredUser() {
        assertTrue(mJobConcurrencyManager.shouldRunAsFgUserJob(
                createJob(createUnexpiredUser(false))));
    }

    @Test
    public void testShouldRunAsFgUserJob_UnexpiredProfile() {
        assertTrue(mJobConcurrencyManager.shouldRunAsFgUserJob(
                createJob(createUnexpiredUser(true))));
    }

    @Test
    public void testShouldRunAsFgUserJob_restrictedUser() {
        assertFalse(mJobConcurrencyManager.shouldRunAsFgUserJob(
                createJob(createRestrictedUser(false))));
    }

    @Test
    public void testShouldRunAsFgUserJob_restrictedProfile() {
        assertFalse(mJobConcurrencyManager.shouldRunAsFgUserJob(
                createJob(createRestrictedUser(true))));
    }

    private UserInfo createCurrentUser(boolean isProfile) {
        final UserInfo ui = createNewUser();
        doReturn(ui.id).when(mActivityManagerInternal).getCurrentUserId();
        return isProfile ? createNewProfile(ui) : ui;
    }

    private UserInfo createPrimaryUser(boolean isProfile) {
        final UserInfo ui = createNewUser();
        doReturn(true).when(ui).isPrimary();
        return isProfile ? createNewProfile(ui) : ui;
    }

    private UserInfo createUnexpiredUser(boolean isProfile) {
        final UserInfo ui = createNewUser();
        doReturn(true).when(mGracePeriodObserver).isWithinGracePeriodForUser(ui.id);
        return isProfile ? createNewProfile(ui) : ui;
    }

    private UserInfo createRestrictedUser(boolean isProfile) {
        final UserInfo ui = createNewUser();
        doReturn(UNAVAILABLE_USER).when(mActivityManagerInternal).getCurrentUserId();
        doReturn(false).when(ui).isPrimary();
        doReturn(false).when(mGracePeriodObserver).isWithinGracePeriodForUser(ui.id);
        return isProfile ? createNewProfile(ui) : ui;
    }

    private UserInfo createNewProfile(UserInfo parent) {
        final UserInfo ui = createNewUser();
        parent.profileGroupId = parent.id;
        ui.profileGroupId = parent.id;
        doReturn(true).when(ui).isProfile();
        return ui;
    }

    private UserInfo createNewUser() {
        final UserInfo ui = mock(UserInfo.class);
        ui.id = mNextUserId++;
        doReturn(ui).when(mUserManagerInternal).getUserInfo(ui.id);
        ui.profileGroupId = UserInfo.NO_PROFILE_GROUP_ID;
        return ui;
    }

    private static JobStatus createJob(UserInfo userInfo) {
        return createJob(userInfo.id * UserHandle.PER_USER_RANGE);
    }

    private static JobStatus createJob(int uid) {
        return createJob(uid, 1);
    }

    private static JobStatus createJob(int uid, int jobId) {
        return JobStatus.createFromJobInfo(
                new JobInfo.Builder(jobId, new ComponentName("foo", "bar")).build(), uid,
                null, UserHandle.getUserId(uid), "JobConcurrencyManagerTest");
    }

    private void setConcurrencyConfig(int total) throws Exception {
        // Set the values for all memory states so we don't have to worry about memory on the device
        // during testing.
        final String[] identifiers = {
                "screen_on_normal", "screen_on_moderate", "screen_on_low", "screen_on_critical",
                "screen_off_normal", "screen_off_moderate", "screen_off_low", "screen_off_critical"
        };
        for (String identifier : identifiers) {
            mConfigBuilder
                    .setInt(WorkTypeConfig.KEY_PREFIX_MAX_TOTAL + identifier, total);
        }
        updateDeviceConfig();
    }

    private void updateDeviceConfig() throws Exception {
        DeviceConfig.setProperties(mConfigBuilder.build());
        mJobConcurrencyManager.updateConfigLocked();
    }

    private void resetConfig() throws Exception {
        mConfigBuilder = new DeviceConfig.Properties.Builder(DeviceConfig.NAMESPACE_JOB_SCHEDULER);
        updateDeviceConfig();
    }
}

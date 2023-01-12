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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.server.job.JobConcurrencyManager.KEY_PKG_CONCURRENCY_LIMIT_EJ;
import static com.android.server.job.JobConcurrencyManager.KEY_PKG_CONCURRENCY_LIMIT_REGULAR;
import static com.android.server.job.JobConcurrencyManager.WORK_TYPE_BG;
import static com.android.server.job.JobConcurrencyManager.WORK_TYPE_BGUSER;
import static com.android.server.job.JobConcurrencyManager.WORK_TYPE_BGUSER_IMPORTANT;
import static com.android.server.job.JobConcurrencyManager.WORK_TYPE_EJ;
import static com.android.server.job.JobConcurrencyManager.WORK_TYPE_FGS;
import static com.android.server.job.JobConcurrencyManager.WORK_TYPE_NONE;
import static com.android.server.job.JobConcurrencyManager.WORK_TYPE_TOP;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AppGlobals;
import android.app.IActivityManager;
import android.app.job.JobInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.IPackageManager;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.util.ArrayMap;
import android.util.ArraySet;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.R;
import com.android.internal.app.IBatteryStats;
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
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.List;

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
    private InjectorForTest mInjector;
    private MockitoSession mMockingSession;
    private Resources mResources;
    private PendingJobQueue mPendingJobQueue;
    private DeviceConfig.Properties.Builder mConfigBuilder;

    @Mock
    private IPackageManager mIPackageManager;

    private static class InjectorForTest extends JobConcurrencyManager.Injector {
        public final ArrayMap<JobServiceContext, JobStatus> contexts = new ArrayMap<>();

        @Override
        JobServiceContext createJobServiceContext(JobSchedulerService service,
                JobConcurrencyManager concurrencyManager,
                JobNotificationCoordinator notificationCoordinator, IBatteryStats batteryStats,
                JobPackageTracker tracker, Looper looper) {
            final JobServiceContext context = mock(JobServiceContext.class);
            doAnswer((Answer<Boolean>) invocationOnMock -> {
                Object[] args = invocationOnMock.getArguments();
                final JobStatus job = (JobStatus) args[0];
                contexts.put(context, job);
                doReturn(job).when(context).getRunningJobLocked();
                return true;
            }).when(context).executeRunnableJob(any(), anyInt());
            contexts.put(context, null);
            return context;
        }
    }

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
        mMockingSession = mockitoSession()
                .initMocks(this)
                .mockStatic(AppGlobals.class)
                .spyStatic(DeviceConfig.class)
                .strictness(Strictness.LENIENT)
                .startMocking();
        final JobSchedulerService jobSchedulerService = mock(JobSchedulerService.class);
        mContext = mock(Context.class);
        mResources = mock(Resources.class);
        doReturn(true).when(mResources).getBoolean(
                R.bool.config_jobSchedulerRestrictBackgroundUser);
        when(mContext.getResources()).thenReturn(mResources);
        doReturn(mContext).when(jobSchedulerService).getTestableContext();
        doReturn(jobSchedulerService).when(jobSchedulerService).getLock();
        mConfigBuilder = new DeviceConfig.Properties.Builder(DeviceConfig.NAMESPACE_JOB_SCHEDULER);
        doAnswer((Answer<DeviceConfig.Properties>) invocationOnMock -> mConfigBuilder.build())
                .when(() -> DeviceConfig.getProperties(eq(DeviceConfig.NAMESPACE_JOB_SCHEDULER)));
        mPendingJobQueue = new PendingJobQueue();
        doReturn(mPendingJobQueue).when(jobSchedulerService).getPendingJobQueue();
        doReturn(mIPackageManager).when(AppGlobals::getPackageManager);
        doReturn(mock(PowerManager.class)).when(mContext).getSystemService(PowerManager.class);
        mInjector = new InjectorForTest();
        doAnswer((Answer<Long>) invocationOnMock -> {
            Object[] args = invocationOnMock.getArguments();
            final JobStatus job = (JobStatus) args[0];
            return job.shouldTreatAsExpeditedJob()
                    ? JobSchedulerService.Constants.DEFAULT_RUNTIME_MIN_EJ_GUARANTEE_MS
                    : JobSchedulerService.Constants.DEFAULT_RUNTIME_MIN_GUARANTEE_MS;
        }).when(jobSchedulerService).getMinJobExecutionGuaranteeMs(any());
        mJobConcurrencyManager = new JobConcurrencyManager(jobSchedulerService, mInjector);
        mGracePeriodObserver = mock(GracePeriodObserver.class);
        mUserManagerInternal = LocalServices.getService(UserManagerInternal.class);
        mActivityManagerInternal = LocalServices.getService(ActivityManagerInternal.class);
        mDefaultUserId = mNextUserId;
        createCurrentUser(true);
        mNextUserId = 10;
        mJobConcurrencyManager.mGracePeriodObserver = mGracePeriodObserver;

        IActivityManager activityManager = ActivityManager.getService();
        spyOn(activityManager);
        try {
            doNothing().when(activityManager).registerUserSwitchObserver(any(), anyString());
        } catch (RemoteException e) {
            fail("registerUserSwitchObserver threw exception: " + e.getMessage());
        }

        mJobConcurrencyManager.onSystemReady();
    }

    @After
    public void tearDown() throws Exception {
        resetConfig();
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    @Test
    public void testPrepareForAssignmentDetermination_noJobs() {
        mPendingJobQueue.clear();

        final ArraySet<JobConcurrencyManager.ContextAssignment> idle = new ArraySet<>();
        final List<JobConcurrencyManager.ContextAssignment> preferredUidOnly = new ArrayList<>();
        final List<JobConcurrencyManager.ContextAssignment> stoppable = new ArrayList<>();
        final JobConcurrencyManager.AssignmentInfo assignmentInfo =
                new JobConcurrencyManager.AssignmentInfo();
        mJobConcurrencyManager.prepareForAssignmentDeterminationLocked(
                idle, preferredUidOnly, stoppable, assignmentInfo);

        assertEquals(JobConcurrencyManager.DEFAULT_CONCURRENCY_LIMIT, idle.size());
        assertEquals(0, preferredUidOnly.size());
        assertEquals(0, stoppable.size());
        assertEquals(0, assignmentInfo.minPreferredUidOnlyWaitingTimeMs);
        assertEquals(0, assignmentInfo.numRunningImmediacyPrivileged);
    }

    @Test
    public void testPrepareForAssignmentDetermination_onlyPendingJobs() {
        for (int i = 0; i < JobConcurrencyManager.DEFAULT_CONCURRENCY_LIMIT; ++i) {
            JobStatus job = createJob(mDefaultUserId * UserHandle.PER_USER_RANGE + i);
            mPendingJobQueue.add(job);
        }

        final ArraySet<JobConcurrencyManager.ContextAssignment> idle = new ArraySet<>();
        final List<JobConcurrencyManager.ContextAssignment> preferredUidOnly = new ArrayList<>();
        final List<JobConcurrencyManager.ContextAssignment> stoppable = new ArrayList<>();
        final JobConcurrencyManager.AssignmentInfo assignmentInfo =
                new JobConcurrencyManager.AssignmentInfo();
        mJobConcurrencyManager.prepareForAssignmentDeterminationLocked(
                idle, preferredUidOnly, stoppable, assignmentInfo);

        assertEquals(JobConcurrencyManager.DEFAULT_CONCURRENCY_LIMIT, idle.size());
        assertEquals(0, preferredUidOnly.size());
        assertEquals(0, stoppable.size());
        assertEquals(0, assignmentInfo.minPreferredUidOnlyWaitingTimeMs);
        assertEquals(0, assignmentInfo.numRunningImmediacyPrivileged);
    }

    @Test
    public void testPrepareForAssignmentDetermination_onlyPreferredUidOnly() {
        for (int i = 0; i < JobConcurrencyManager.DEFAULT_CONCURRENCY_LIMIT; ++i) {
            JobStatus job = createJob(mDefaultUserId * UserHandle.PER_USER_RANGE + i);
            mJobConcurrencyManager.addRunningJobForTesting(job);
        }

        for (int i = 0; i < mInjector.contexts.size(); ++i) {
            doReturn(true).when(mInjector.contexts.keyAt(i)).isWithinExecutionGuaranteeTime();
        }

        final ArraySet<JobConcurrencyManager.ContextAssignment> idle = new ArraySet<>();
        final List<JobConcurrencyManager.ContextAssignment> preferredUidOnly = new ArrayList<>();
        final List<JobConcurrencyManager.ContextAssignment> stoppable = new ArrayList<>();
        final JobConcurrencyManager.AssignmentInfo assignmentInfo =
                new JobConcurrencyManager.AssignmentInfo();
        mJobConcurrencyManager.prepareForAssignmentDeterminationLocked(
                idle, preferredUidOnly, stoppable, assignmentInfo);

        assertEquals(0, idle.size());
        assertEquals(JobConcurrencyManager.DEFAULT_CONCURRENCY_LIMIT, preferredUidOnly.size());
        assertEquals(0, stoppable.size());
        assertEquals(0, assignmentInfo.minPreferredUidOnlyWaitingTimeMs);
        assertEquals(0, assignmentInfo.numRunningImmediacyPrivileged);
    }

    @Test
    public void testPrepareForAssignmentDetermination_onlyStartedWithImmediacyPrivilege() {
        for (int i = 0; i < JobConcurrencyManager.DEFAULT_CONCURRENCY_LIMIT; ++i) {
            JobStatus job = createJob(mDefaultUserId * UserHandle.PER_USER_RANGE + i);
            job.startedWithImmediacyPrivilege = true;
            mJobConcurrencyManager.addRunningJobForTesting(job);
        }

        for (int i = 0; i < mInjector.contexts.size(); ++i) {
            doReturn(i % 2 == 0).when(mInjector.contexts.keyAt(i)).isWithinExecutionGuaranteeTime();
        }

        final ArraySet<JobConcurrencyManager.ContextAssignment> idle = new ArraySet<>();
        final List<JobConcurrencyManager.ContextAssignment> preferredUidOnly = new ArrayList<>();
        final List<JobConcurrencyManager.ContextAssignment> stoppable = new ArrayList<>();
        final JobConcurrencyManager.AssignmentInfo assignmentInfo =
                new JobConcurrencyManager.AssignmentInfo();
        mJobConcurrencyManager.prepareForAssignmentDeterminationLocked(
                idle, preferredUidOnly, stoppable, assignmentInfo);

        assertEquals(0, idle.size());
        assertEquals(JobConcurrencyManager.DEFAULT_CONCURRENCY_LIMIT / 2, preferredUidOnly.size());
        assertEquals(JobConcurrencyManager.DEFAULT_CONCURRENCY_LIMIT / 2, stoppable.size());
        assertEquals(0, assignmentInfo.minPreferredUidOnlyWaitingTimeMs);
        assertEquals(JobConcurrencyManager.DEFAULT_CONCURRENCY_LIMIT,
                assignmentInfo.numRunningImmediacyPrivileged);
    }

    @Test
    public void testDetermineAssignments_allRegular() throws Exception {
        setConcurrencyConfig(JobConcurrencyManager.DEFAULT_CONCURRENCY_LIMIT,
                new TypeConfig(WORK_TYPE_BG, 0, JobConcurrencyManager.DEFAULT_CONCURRENCY_LIMIT));
        final ArraySet<JobStatus> jobs = new ArraySet<>();
        for (int i = 0; i < JobConcurrencyManager.DEFAULT_CONCURRENCY_LIMIT; ++i) {
            final int uid = mDefaultUserId * UserHandle.PER_USER_RANGE + i;
            final String sourcePkgName = "com.source.package." + UserHandle.getAppId(uid);
            setPackageUid(sourcePkgName, uid);
            final JobStatus job = createJob(uid, sourcePkgName);
            mPendingJobQueue.add(job);
            jobs.add(job);
        }

        final ArraySet<JobConcurrencyManager.ContextAssignment> changed = new ArraySet<>();
        final ArraySet<JobConcurrencyManager.ContextAssignment> idle = new ArraySet<>();
        final List<JobConcurrencyManager.ContextAssignment> preferredUidOnly = new ArrayList<>();
        final List<JobConcurrencyManager.ContextAssignment> stoppable = new ArrayList<>();
        final JobConcurrencyManager.AssignmentInfo assignmentInfo =
                new JobConcurrencyManager.AssignmentInfo();
        mJobConcurrencyManager.prepareForAssignmentDeterminationLocked(
                idle, preferredUidOnly, stoppable, assignmentInfo);
        mJobConcurrencyManager
                .determineAssignmentsLocked(changed, idle, preferredUidOnly, stoppable,
                        assignmentInfo);

        assertEquals(JobConcurrencyManager.DEFAULT_CONCURRENCY_LIMIT, changed.size());
        for (int i = changed.size() - 1; i >= 0; --i) {
            jobs.remove(changed.valueAt(i).newJob);
        }
        assertTrue("Some jobs weren't assigned", jobs.isEmpty());
    }

    @Test
    public void testDetermineAssignments_allPreferredUidOnly_shortTimeLeft() throws Exception {
        mConfigBuilder.setBoolean(JobConcurrencyManager.KEY_ENABLE_MAX_WAIT_TIME_BYPASS, true);
        setConcurrencyConfig(JobConcurrencyManager.DEFAULT_CONCURRENCY_LIMIT,
                new TypeConfig(WORK_TYPE_BG, 0, JobConcurrencyManager.DEFAULT_CONCURRENCY_LIMIT));
        for (int i = 0; i < JobConcurrencyManager.DEFAULT_CONCURRENCY_LIMIT * 2; ++i) {
            final int uid = mDefaultUserId * UserHandle.PER_USER_RANGE + i;
            final String sourcePkgName = "com.source.package." + UserHandle.getAppId(uid);
            setPackageUid(sourcePkgName, uid);
            final JobStatus job = createJob(uid, sourcePkgName);
            spyOn(job);
            doReturn(i % 2 == 0).when(job).shouldTreatAsExpeditedJob();
            if (i < JobConcurrencyManager.DEFAULT_CONCURRENCY_LIMIT) {
                mJobConcurrencyManager.addRunningJobForTesting(job);
            } else {
                mPendingJobQueue.add(job);
            }
        }

        // Waiting time is too short, so we shouldn't create any extra contexts.
        final long remainingTimeMs = JobConcurrencyManager.DEFAULT_MAX_WAIT_EJ_MS / 2;
        for (int i = 0; i < mInjector.contexts.size(); ++i) {
            doReturn(true).when(mInjector.contexts.keyAt(i)).isWithinExecutionGuaranteeTime();
            doReturn(remainingTimeMs)
                    .when(mInjector.contexts.keyAt(i)).getRemainingGuaranteedTimeMs(anyLong());
        }

        final ArraySet<JobConcurrencyManager.ContextAssignment> changed = new ArraySet<>();
        final ArraySet<JobConcurrencyManager.ContextAssignment> idle = new ArraySet<>();
        final List<JobConcurrencyManager.ContextAssignment> preferredUidOnly = new ArrayList<>();
        final List<JobConcurrencyManager.ContextAssignment> stoppable = new ArrayList<>();
        final JobConcurrencyManager.AssignmentInfo assignmentInfo =
                new JobConcurrencyManager.AssignmentInfo();

        mJobConcurrencyManager.prepareForAssignmentDeterminationLocked(
                idle, preferredUidOnly, stoppable, assignmentInfo);
        assertEquals(remainingTimeMs, assignmentInfo.minPreferredUidOnlyWaitingTimeMs);
        assertEquals(JobConcurrencyManager.DEFAULT_CONCURRENCY_LIMIT, preferredUidOnly.size());

        mJobConcurrencyManager
                .determineAssignmentsLocked(changed, idle, preferredUidOnly, stoppable,
                        assignmentInfo);

        assertEquals(JobConcurrencyManager.DEFAULT_CONCURRENCY_LIMIT, preferredUidOnly.size());
        assertEquals(0, changed.size());
    }

    @Test
    public void testDetermineAssignments_allPreferredUidOnly_mediumTimeLeft() throws Exception {
        mConfigBuilder.setBoolean(JobConcurrencyManager.KEY_ENABLE_MAX_WAIT_TIME_BYPASS, true);
        setConcurrencyConfig(JobConcurrencyManager.DEFAULT_CONCURRENCY_LIMIT,
                new TypeConfig(WORK_TYPE_BG, 0, JobConcurrencyManager.DEFAULT_CONCURRENCY_LIMIT));
        final ArraySet<JobStatus> jobs = new ArraySet<>();
        for (int i = 0; i < JobConcurrencyManager.DEFAULT_CONCURRENCY_LIMIT * 2; ++i) {
            final int uid = mDefaultUserId * UserHandle.PER_USER_RANGE + i;
            final String sourcePkgName = "com.source.package." + UserHandle.getAppId(uid);
            setPackageUid(sourcePkgName, uid);
            final JobStatus job = createJob(uid, sourcePkgName);
            spyOn(job);
            doReturn(i % 2 == 0).when(job).shouldTreatAsExpeditedJob();
            if (i < JobConcurrencyManager.DEFAULT_CONCURRENCY_LIMIT) {
                mJobConcurrencyManager.addRunningJobForTesting(job);
            } else {
                mPendingJobQueue.add(job);
                jobs.add(job);
            }
        }

        // Waiting time is longer than the EJ waiting time, but shorter than regular job waiting
        // time, so we should only create an extra context for an EJ.
        final long remainingTimeMs = (JobConcurrencyManager.DEFAULT_MAX_WAIT_EJ_MS
                + JobConcurrencyManager.DEFAULT_MAX_WAIT_REGULAR_MS) / 2;
        for (int i = 0; i < mInjector.contexts.size(); ++i) {
            doReturn(true).when(mInjector.contexts.keyAt(i)).isWithinExecutionGuaranteeTime();
            doReturn(remainingTimeMs)
                    .when(mInjector.contexts.keyAt(i)).getRemainingGuaranteedTimeMs(anyLong());
        }

        final ArraySet<JobConcurrencyManager.ContextAssignment> changed = new ArraySet<>();
        final ArraySet<JobConcurrencyManager.ContextAssignment> idle = new ArraySet<>();
        final List<JobConcurrencyManager.ContextAssignment> preferredUidOnly = new ArrayList<>();
        final List<JobConcurrencyManager.ContextAssignment> stoppable = new ArrayList<>();
        final JobConcurrencyManager.AssignmentInfo assignmentInfo =
                new JobConcurrencyManager.AssignmentInfo();

        mJobConcurrencyManager.prepareForAssignmentDeterminationLocked(
                idle, preferredUidOnly, stoppable, assignmentInfo);
        assertEquals(remainingTimeMs, assignmentInfo.minPreferredUidOnlyWaitingTimeMs);
        assertEquals(JobConcurrencyManager.DEFAULT_CONCURRENCY_LIMIT, preferredUidOnly.size());

        mJobConcurrencyManager
                .determineAssignmentsLocked(changed, idle, preferredUidOnly, stoppable,
                        assignmentInfo);

        assertEquals(JobConcurrencyManager.DEFAULT_CONCURRENCY_LIMIT, preferredUidOnly.size());
        for (int i = changed.size() - 1; i >= 0; --i) {
            jobs.remove(changed.valueAt(i).newJob);
        }
        assertEquals(JobConcurrencyManager.DEFAULT_CONCURRENCY_LIMIT - 1, jobs.size());
        assertEquals(1, changed.size());
        JobStatus assignedJob = changed.valueAt(0).newJob;
        assertTrue(assignedJob.shouldTreatAsExpeditedJob());
    }

    @Test
    public void testDetermineAssignments_allPreferredUidOnly_longTimeLeft() throws Exception {
        mConfigBuilder.setBoolean(JobConcurrencyManager.KEY_ENABLE_MAX_WAIT_TIME_BYPASS, true);
        setConcurrencyConfig(JobConcurrencyManager.DEFAULT_CONCURRENCY_LIMIT,
                new TypeConfig(WORK_TYPE_BG, 0, JobConcurrencyManager.DEFAULT_CONCURRENCY_LIMIT));
        final ArraySet<JobStatus> jobs = new ArraySet<>();
        for (int i = 0; i < JobConcurrencyManager.DEFAULT_CONCURRENCY_LIMIT * 2; ++i) {
            final int uid = mDefaultUserId * UserHandle.PER_USER_RANGE + i;
            final String sourcePkgName = "com.source.package." + UserHandle.getAppId(uid);
            setPackageUid(sourcePkgName, uid);
            final JobStatus job = createJob(uid, sourcePkgName);
            spyOn(job);
            doReturn(i % 2 == 0).when(job).shouldTreatAsExpeditedJob();
            if (i < JobConcurrencyManager.DEFAULT_CONCURRENCY_LIMIT) {
                mJobConcurrencyManager.addRunningJobForTesting(job);
            } else {
                mPendingJobQueue.add(job);
                jobs.add(job);
            }
        }

        // Waiting time is longer than even the regular job waiting time, so we should
        // create an extra context for an EJ, and potentially one for a regular job.
        final long remainingTimeMs = 2 * JobConcurrencyManager.DEFAULT_MAX_WAIT_REGULAR_MS;
        for (int i = 0; i < mInjector.contexts.size(); ++i) {
            doReturn(true).when(mInjector.contexts.keyAt(i)).isWithinExecutionGuaranteeTime();
            doReturn(remainingTimeMs)
                    .when(mInjector.contexts.keyAt(i)).getRemainingGuaranteedTimeMs(anyLong());
        }

        final ArraySet<JobConcurrencyManager.ContextAssignment> changed = new ArraySet<>();
        final ArraySet<JobConcurrencyManager.ContextAssignment> idle = new ArraySet<>();
        final List<JobConcurrencyManager.ContextAssignment> preferredUidOnly = new ArrayList<>();
        final List<JobConcurrencyManager.ContextAssignment> stoppable = new ArrayList<>();
        final JobConcurrencyManager.AssignmentInfo assignmentInfo =
                new JobConcurrencyManager.AssignmentInfo();

        mJobConcurrencyManager.prepareForAssignmentDeterminationLocked(
                idle, preferredUidOnly, stoppable, assignmentInfo);
        assertEquals(remainingTimeMs, assignmentInfo.minPreferredUidOnlyWaitingTimeMs);
        assertEquals(JobConcurrencyManager.DEFAULT_CONCURRENCY_LIMIT, preferredUidOnly.size());

        mJobConcurrencyManager
                .determineAssignmentsLocked(changed, idle, preferredUidOnly, stoppable,
                        assignmentInfo);

        assertEquals(JobConcurrencyManager.DEFAULT_CONCURRENCY_LIMIT, preferredUidOnly.size());
        // Depending on iteration order, we may create 1 or 2 contexts.
        final long numAssignedJobs = changed.size();
        assertTrue(numAssignedJobs > 0);
        assertTrue(numAssignedJobs <= 2);
        for (int i = 0; i < numAssignedJobs; ++i) {
            jobs.remove(changed.valueAt(i).newJob);
        }
        assertEquals(numAssignedJobs,
                JobConcurrencyManager.DEFAULT_CONCURRENCY_LIMIT - jobs.size());
        JobStatus firstAssignedJob = changed.valueAt(0).newJob;
        if (!firstAssignedJob.shouldTreatAsExpeditedJob()) {
            assertEquals(2, numAssignedJobs);
            assertTrue(changed.valueAt(1).newJob.shouldTreatAsExpeditedJob());
        } else if (numAssignedJobs == 2) {
            assertFalse(changed.valueAt(1).newJob.shouldTreatAsExpeditedJob());
        }
    }

    @Test
    public void testHasImmediacyPrivilege() {
        JobStatus job = createJob(mDefaultUserId * UserHandle.PER_USER_RANGE, 0);
        spyOn(job);
        assertFalse(mJobConcurrencyManager.hasImmediacyPrivilegeLocked(job));

        doReturn(false).when(job).shouldTreatAsExpeditedJob();
        doReturn(false).when(job).shouldTreatAsUserInitiatedJob();
        job.lastEvaluatedBias = JobInfo.BIAS_TOP_APP;
        assertFalse(mJobConcurrencyManager.hasImmediacyPrivilegeLocked(job));

        doReturn(true).when(job).shouldTreatAsExpeditedJob();
        doReturn(false).when(job).shouldTreatAsUserInitiatedJob();
        job.lastEvaluatedBias = JobInfo.BIAS_DEFAULT;
        assertFalse(mJobConcurrencyManager.hasImmediacyPrivilegeLocked(job));

        doReturn(false).when(job).shouldTreatAsExpeditedJob();
        doReturn(true).when(job).shouldTreatAsUserInitiatedJob();
        job.lastEvaluatedBias = JobInfo.BIAS_DEFAULT;
        assertFalse(mJobConcurrencyManager.hasImmediacyPrivilegeLocked(job));

        doReturn(false).when(job).shouldTreatAsExpeditedJob();
        doReturn(true).when(job).shouldTreatAsUserInitiatedJob();
        job.lastEvaluatedBias = JobInfo.BIAS_TOP_APP;
        assertTrue(mJobConcurrencyManager.hasImmediacyPrivilegeLocked(job));

        doReturn(true).when(job).shouldTreatAsExpeditedJob();
        doReturn(false).when(job).shouldTreatAsUserInitiatedJob();
        job.lastEvaluatedBias = JobInfo.BIAS_TOP_APP;
        assertTrue(mJobConcurrencyManager.hasImmediacyPrivilegeLocked(job));
    }

    @Test
    public void testIsPkgConcurrencyLimited_top() {
        final JobStatus topJob = createJob(mDefaultUserId * UserHandle.PER_USER_RANGE, 0);
        topJob.lastEvaluatedBias = JobInfo.BIAS_TOP_APP;

        assertFalse(mJobConcurrencyManager.isPkgConcurrencyLimitedLocked(topJob));

        // Pending jobs shouldn't affect TOP job's status.
        for (int i = 1; i <= JobConcurrencyManager.DEFAULT_CONCURRENCY_LIMIT; ++i) {
            final JobStatus job = createJob(mDefaultUserId * UserHandle.PER_USER_RANGE + i);
            mPendingJobQueue.add(job);
        }
        assertFalse(mJobConcurrencyManager.isPkgConcurrencyLimitedLocked(topJob));

        // Already running jobs shouldn't affect TOP job's status.
        for (int i = 1; i <= JobConcurrencyManager.DEFAULT_CONCURRENCY_LIMIT; ++i) {
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

        setConcurrencyConfig(JobConcurrencyManager.DEFAULT_CONCURRENCY_LIMIT);

        for (int i = 0; i < JobConcurrencyManager.DEFAULT_CONCURRENCY_LIMIT; ++i) {
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
        return createJob(uid, 1, null);
    }

    private static JobStatus createJob(int uid, String sourcePackageName) {
        return createJob(uid, 1, sourcePackageName);
    }

    private static JobStatus createJob(int uid, int jobId) {
        return createJob(uid, jobId, null);
    }

    private static JobStatus createJob(int uid, int jobId, @Nullable String sourcePackageName) {
        return JobStatus.createFromJobInfo(
                new JobInfo.Builder(jobId, new ComponentName("foo", "bar")).build(), uid,
                sourcePackageName, UserHandle.getUserId(uid), "JobConcurrencyManagerTest", null);
    }

    private static final class TypeConfig {
        public final String workTypeString;
        public final int min;
        public final int max;

        private TypeConfig(@JobConcurrencyManager.WorkType int workType, int min, int max) {
            switch (workType) {
                case WORK_TYPE_TOP:
                    workTypeString = "top";
                    break;
                case WORK_TYPE_FGS:
                    workTypeString = "fgs";
                    break;
                case WORK_TYPE_EJ:
                    workTypeString = "ej";
                    break;
                case WORK_TYPE_BG:
                    workTypeString = "bg";
                    break;
                case WORK_TYPE_BGUSER:
                    workTypeString = "bguser";
                    break;
                case WORK_TYPE_BGUSER_IMPORTANT:
                    workTypeString = "bguser_important";
                    break;
                case WORK_TYPE_NONE:
                default:
                    throw new IllegalArgumentException("invalid work type: " + workType);
            }
            this.min = min;
            this.max = max;
        }
    }

    private void setConcurrencyConfig(int total, TypeConfig... typeConfigs) throws Exception {
        // Set the values for all memory states so we don't have to worry about memory on the device
        // during testing.
        final String[] identifiers = {
                "screen_on_normal", "screen_on_moderate", "screen_on_low", "screen_on_critical",
                "screen_off_normal", "screen_off_moderate", "screen_off_low", "screen_off_critical"
        };
        for (String identifier : identifiers) {
            mConfigBuilder
                    .setInt(WorkTypeConfig.KEY_PREFIX_MAX_TOTAL + identifier, total);
            for (TypeConfig config : typeConfigs) {
                mConfigBuilder.setFloat(
                        WorkTypeConfig.KEY_PREFIX_MAX_RATIO + config.workTypeString + "_"
                                + identifier,
                        (float) config.max / total);
                mConfigBuilder.setFloat(
                        WorkTypeConfig.KEY_PREFIX_MIN_RATIO + config.workTypeString + "_"
                                + identifier,
                        (float) config.min / total);
            }
        }
        updateDeviceConfig();
    }

    private void setPackageUid(final String pkgName, final int uid) throws Exception {
        doReturn(uid).when(mIPackageManager)
                .getPackageUid(eq(pkgName), anyLong(), eq(UserHandle.getUserId(uid)));
    }

    private void updateDeviceConfig() throws Exception {
        mJobConcurrencyManager.updateConfigLocked();
    }

    private void resetConfig() throws Exception {
        mConfigBuilder = new DeviceConfig.Properties.Builder(DeviceConfig.NAMESPACE_JOB_SCHEDULER);
        updateDeviceConfig();
    }
}

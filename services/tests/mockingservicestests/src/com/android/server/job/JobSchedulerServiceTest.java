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
 * limitations under the License.
 */

package com.android.server.job;

import static android.text.format.DateUtils.DAY_IN_MILLIS;
import static android.text.format.DateUtils.HOUR_IN_MILLIS;
import static android.text.format.DateUtils.MINUTE_IN_MILLIS;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.server.job.JobSchedulerService.ACTIVE_INDEX;
import static com.android.server.job.JobSchedulerService.RARE_INDEX;
import static com.android.server.job.JobSchedulerService.sElapsedRealtimeClock;
import static com.android.server.job.JobSchedulerService.sUptimeMillisClock;
import static com.android.server.job.Flags.FLAG_BATCH_ACTIVE_BUCKET_JOBS;
import static com.android.server.job.Flags.FLAG_BATCH_CONNECTIVITY_JOBS_PER_NETWORK;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.IActivityManager;
import android.app.UiModeManager;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobWorkItem;
import android.app.usage.UsageStatsManagerInternal;
import android.content.ComponentName;
import android.content.Context;
import android.content.PermissionChecker;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkPolicyManager;
import android.os.BatteryManagerInternal;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.platform.test.flag.junit.SetFlagsRule;

import com.android.server.AppStateTracker;
import com.android.server.AppStateTrackerImpl;
import com.android.server.DeviceIdleInternal;
import com.android.server.LocalServices;
import com.android.server.PowerAllowlistInternal;
import com.android.server.SystemServiceManager;
import com.android.server.job.controllers.ConnectivityController;
import com.android.server.job.controllers.JobStatus;
import com.android.server.job.controllers.QuotaController;
import com.android.server.job.controllers.TareController;
import com.android.server.pm.UserManagerInternal;
import com.android.server.usage.AppStandbyInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;

public class JobSchedulerServiceTest {
    private static final String TAG = JobSchedulerServiceTest.class.getSimpleName();
    private static final int TEST_UID = 10123;

    private JobSchedulerService mService;

    private MockitoSession mMockingSession;
    @Mock
    private ActivityManagerInternal mActivityMangerInternal;
    @Mock
    private Context mContext;
    @Mock
    private PackageManagerInternal mPackageManagerInternal;

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private class TestJobSchedulerService extends JobSchedulerService {
        TestJobSchedulerService(Context context) {
            super(context);
            mAppStateTracker = mock(AppStateTrackerImpl.class);
        }
    }

    @Before
    public void setUp() throws Exception {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .mockStatic(LocalServices.class)
                .mockStatic(PermissionChecker.class)
                .mockStatic(ServiceManager.class)
                .startMocking();

        // Called in JobSchedulerService constructor.
        when(mContext.getMainLooper()).thenReturn(Looper.getMainLooper());
        doReturn(mActivityMangerInternal)
                .when(() -> LocalServices.getService(ActivityManagerInternal.class));
        doReturn(mock(AppStandbyInternal.class))
                .when(() -> LocalServices.getService(AppStandbyInternal.class));
        doReturn(mock(BatteryManagerInternal.class))
                .when(() -> LocalServices.getService(BatteryManagerInternal.class));
        doReturn(mPackageManagerInternal)
                .when(() -> LocalServices.getService(PackageManagerInternal.class));
        doReturn(mock(UsageStatsManagerInternal.class))
                .when(() -> LocalServices.getService(UsageStatsManagerInternal.class));
        when(mContext.getString(anyInt())).thenReturn("some_test_string");
        // Called in BackgroundJobsController constructor.
        doReturn(mock(AppStateTrackerImpl.class))
                .when(() -> LocalServices.getService(AppStateTracker.class));
        // Called in ConnectivityController constructor.
        when(mContext.getSystemService(ConnectivityManager.class))
                .thenReturn(mock(ConnectivityManager.class));
        when(mContext.getSystemService(NetworkPolicyManager.class))
                .thenReturn(mock(NetworkPolicyManager.class));
        // Called in DeviceIdleJobsController constructor.
        doReturn(mock(DeviceIdleInternal.class))
                .when(() -> LocalServices.getService(DeviceIdleInternal.class));
        // Used in JobConcurrencyManager.
        doReturn(mock(UserManagerInternal.class))
                .when(() -> LocalServices.getService(UserManagerInternal.class));
        // Used in JobStatus.
        doReturn(mock(JobSchedulerInternal.class))
                .when(() -> LocalServices.getService(JobSchedulerInternal.class));
        // Called via IdleController constructor.
        when(mContext.getPackageManager()).thenReturn(mock(PackageManager.class));
        when(mContext.getResources()).thenReturn(mock(Resources.class));
        // Called in QuotaController constructor.
        doReturn(mock(PowerAllowlistInternal.class))
                .when(() -> LocalServices.getService(PowerAllowlistInternal.class));
        IActivityManager activityManager = ActivityManager.getService();
        spyOn(activityManager);
        try {
            doNothing().when(activityManager).registerUidObserver(any(), anyInt(), anyInt(), any());
        } catch (RemoteException e) {
            fail("registerUidObserver threw exception: " + e.getMessage());
        }
        // Called by QuotaTracker
        doReturn(mock(SystemServiceManager.class))
                .when(() -> LocalServices.getService(SystemServiceManager.class));

        JobSchedulerService.sSystemClock = Clock.fixed(Clock.systemUTC().instant(), ZoneOffset.UTC);
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(SystemClock.elapsedRealtimeClock().instant(), ZoneOffset.UTC);
        // Make sure the uptime is at least 24 hours so that tests that rely on high uptime work.
        sUptimeMillisClock = getAdvancedClock(sUptimeMillisClock, 24 * HOUR_IN_MILLIS);
        // Called by DeviceIdlenessTracker
        when(mContext.getSystemService(UiModeManager.class)).thenReturn(mock(UiModeManager.class));

        mService = new TestJobSchedulerService(mContext);
        mService.waitOnAsyncLoadingForTesting();
    }

    @After
    public void tearDown() {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
        mService.cancelJobsForUid(TEST_UID, true,
                JobParameters.STOP_REASON_UNDEFINED, JobParameters.INTERNAL_STOP_REASON_UNKNOWN,
                "test cleanup");
    }

    private Clock getAdvancedClock(Clock clock, long incrementMs) {
        return Clock.offset(clock, Duration.ofMillis(incrementMs));
    }

    private void advanceElapsedClock(long incrementMs) {
        JobSchedulerService.sElapsedRealtimeClock = getAdvancedClock(
                JobSchedulerService.sElapsedRealtimeClock, incrementMs);
    }

    private static JobInfo.Builder createJobInfo() {
        return createJobInfo(351);
    }

    private static JobInfo.Builder createJobInfo(int jobId) {
        return new JobInfo.Builder(jobId, new ComponentName("foo", "bar"));
    }

    private JobStatus createJobStatus(String testTag, JobInfo.Builder jobInfoBuilder) {
        return createJobStatus(testTag, jobInfoBuilder, 1234);
    }

    private JobStatus createJobStatus(String testTag, JobInfo.Builder jobInfoBuilder,
            int callingUid) {
        return createJobStatus(testTag, jobInfoBuilder, callingUid, "com.android.test");
    }

    private JobStatus createJobStatus(String testTag, JobInfo.Builder jobInfoBuilder,
            int callingUid, String sourcePkg) {
        return JobStatus.createFromJobInfo(
                jobInfoBuilder.build(), callingUid, sourcePkg, 0, "JSSTest", testTag);
    }

    private void grantRunUserInitiatedJobsPermission(boolean grant) {
        final int permissionStatus = grant
                ? PermissionChecker.PERMISSION_GRANTED : PermissionChecker.PERMISSION_HARD_DENIED;
        doReturn(permissionStatus)
                .when(() -> PermissionChecker.checkPermissionForPreflight(
                        any(), eq(android.Manifest.permission.RUN_USER_INITIATED_JOBS),
                        anyInt(), anyInt(), anyString()));
    }

    @Test
    public void testGetMinJobExecutionGuaranteeMs() {
        JobStatus ejMax = createJobStatus("testGetMinJobExecutionGuaranteeMs",
                createJobInfo(1).setExpedited(true));
        JobStatus ejHigh = createJobStatus("testGetMinJobExecutionGuaranteeMs",
                createJobInfo(2).setExpedited(true).setPriority(JobInfo.PRIORITY_HIGH));
        JobStatus ejMaxDowngraded = createJobStatus("testGetMinJobExecutionGuaranteeMs",
                createJobInfo(3).setExpedited(true));
        JobStatus ejHighDowngraded = createJobStatus("testGetMinJobExecutionGuaranteeMs",
                createJobInfo(4).setExpedited(true).setPriority(JobInfo.PRIORITY_HIGH));
        JobStatus jobHigh = createJobStatus("testGetMinJobExecutionGuaranteeMs",
                createJobInfo(5).setPriority(JobInfo.PRIORITY_HIGH));
        JobStatus jobDef = createJobStatus("testGetMinJobExecutionGuaranteeMs",
                createJobInfo(6));
        JobStatus jobUIDT = createJobStatus("testGetMinJobExecutionGuaranteeMs",
                createJobInfo(9)
                        .setUserInitiated(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY));

        spyOn(ejMax);
        spyOn(ejHigh);
        spyOn(ejMaxDowngraded);
        spyOn(ejHighDowngraded);
        spyOn(jobHigh);
        spyOn(jobDef);
        spyOn(jobUIDT);

        when(ejMax.shouldTreatAsExpeditedJob()).thenReturn(true);
        when(ejHigh.shouldTreatAsExpeditedJob()).thenReturn(true);
        when(ejMaxDowngraded.shouldTreatAsExpeditedJob()).thenReturn(false);
        when(ejHighDowngraded.shouldTreatAsExpeditedJob()).thenReturn(false);
        when(jobHigh.shouldTreatAsExpeditedJob()).thenReturn(false);
        when(jobDef.shouldTreatAsExpeditedJob()).thenReturn(false);
        when(jobUIDT.shouldTreatAsUserInitiatedJob()).thenReturn(true);

        ConnectivityController connectivityController = mService.getConnectivityController();
        spyOn(connectivityController);
        mService.mConstants.RUNTIME_MIN_GUARANTEE_MS = 10 * MINUTE_IN_MILLIS;
        mService.mConstants.RUNTIME_MIN_UI_GUARANTEE_MS = 2 * HOUR_IN_MILLIS;
        mService.mConstants.RUNTIME_MIN_UI_DATA_TRANSFER_GUARANTEE_BUFFER_FACTOR = 1.5f;
        mService.mConstants.RUNTIME_MIN_UI_DATA_TRANSFER_GUARANTEE_MS = HOUR_IN_MILLIS;
        mService.mConstants.RUNTIME_UI_LIMIT_MS = 6 * HOUR_IN_MILLIS;

        assertEquals(mService.mConstants.RUNTIME_MIN_EJ_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(ejMax));
        assertEquals(mService.mConstants.RUNTIME_MIN_EJ_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(ejHigh));
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(ejMaxDowngraded));
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(ejHighDowngraded));
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobHigh));
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobDef));
        // UserInitiated
        grantRunUserInitiatedJobsPermission(false);
        // Permission isn't granted, so it should just be treated as a regular job.
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobUIDT));

        grantRunUserInitiatedJobsPermission(true); // With permission
        mService.mConstants.RUNTIME_USE_DATA_ESTIMATES_FOR_LIMITS = true;
        doReturn(ConnectivityController.UNKNOWN_TIME)
                .when(connectivityController).getEstimatedTransferTimeMs(any());
        assertEquals(mService.mConstants.RUNTIME_MIN_UI_DATA_TRANSFER_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobUIDT));
        doReturn(mService.mConstants.RUNTIME_MIN_UI_DATA_TRANSFER_GUARANTEE_MS / 2)
                .when(connectivityController).getEstimatedTransferTimeMs(any());
        assertEquals(mService.mConstants.RUNTIME_MIN_UI_DATA_TRANSFER_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobUIDT));
        final long estimatedTransferTimeMs =
                mService.mConstants.RUNTIME_MIN_UI_DATA_TRANSFER_GUARANTEE_MS * 2;
        doReturn(estimatedTransferTimeMs)
                .when(connectivityController).getEstimatedTransferTimeMs(any());
        assertEquals((long) (estimatedTransferTimeMs
                        * mService.mConstants.RUNTIME_MIN_UI_DATA_TRANSFER_GUARANTEE_BUFFER_FACTOR),
                mService.getMinJobExecutionGuaranteeMs(jobUIDT));
        doReturn(mService.mConstants.RUNTIME_UI_LIMIT_MS * 2)
                .when(connectivityController).getEstimatedTransferTimeMs(any());
        assertEquals(mService.mConstants.RUNTIME_UI_LIMIT_MS,
                mService.getMinJobExecutionGuaranteeMs(jobUIDT));

        mService.mConstants.RUNTIME_USE_DATA_ESTIMATES_FOR_LIMITS = false;
        assertEquals(mService.mConstants.RUNTIME_MIN_UI_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobUIDT));
    }

    @Test
    public void testGetMinJobExecutionGuaranteeMs_timeoutSafeguards_disabled() {
        JobStatus jobUij = createJobStatus("testGetMinJobExecutionGuaranteeMs_timeoutSafeguards",
                createJobInfo(1)
                        .setUserInitiated(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY));
        JobStatus jobEj = createJobStatus("testGetMinJobExecutionGuaranteeMs_timeoutSafeguards",
                createJobInfo(2).setExpedited(true));
        JobStatus jobReg = createJobStatus("testGetMinJobExecutionGuaranteeMs_timeoutSafeguards",
                createJobInfo(3));
        spyOn(jobUij);
        when(jobUij.shouldTreatAsUserInitiatedJob()).thenReturn(true);
        jobUij.startedAsUserInitiatedJob = true;
        spyOn(jobEj);
        when(jobEj.shouldTreatAsExpeditedJob()).thenReturn(true);
        jobEj.startedAsExpeditedJob = true;

        mService.mConstants.ENABLE_EXECUTION_SAFEGUARDS_UDC = false;
        mService.mConstants.EXECUTION_SAFEGUARDS_UDC_TIMEOUT_UIJ_COUNT = 2;
        mService.mConstants.EXECUTION_SAFEGUARDS_UDC_TIMEOUT_EJ_COUNT = 2;
        mService.mConstants.EXECUTION_SAFEGUARDS_UDC_TIMEOUT_REG_COUNT = 2;
        mService.updateQuotaTracker();
        mService.resetScheduleQuota();

        // Safeguards disabled -> no penalties.
        grantRunUserInitiatedJobsPermission(true);
        assertEquals(mService.mConstants.RUNTIME_MIN_UI_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobUij));
        grantRunUserInitiatedJobsPermission(false);
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobUij));
        assertEquals(mService.mConstants.RUNTIME_MIN_EJ_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobEj));
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobReg));

        // 1 UIJ timeout. No max execution penalty yet.
        mService.maybeProcessBuggyJob(jobUij, JobParameters.INTERNAL_STOP_REASON_TIMEOUT);
        grantRunUserInitiatedJobsPermission(true);
        assertEquals(mService.mConstants.RUNTIME_MIN_UI_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobUij));
        grantRunUserInitiatedJobsPermission(false);
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobUij));
        assertEquals(mService.mConstants.RUNTIME_MIN_EJ_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobEj));
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobReg));

        // 2 UIJ timeouts. Safeguards disabled -> no penalties.
        jobUij.madeActive =
                sUptimeMillisClock.millis() - mService.mConstants.RUNTIME_MIN_UI_GUARANTEE_MS;
        mService.maybeProcessBuggyJob(jobUij, JobParameters.INTERNAL_STOP_REASON_UNKNOWN);
        grantRunUserInitiatedJobsPermission(true);
        assertEquals(mService.mConstants.RUNTIME_MIN_UI_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobUij));
        grantRunUserInitiatedJobsPermission(false);
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobUij));
        assertEquals(mService.mConstants.RUNTIME_MIN_EJ_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobEj));
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobReg));

        // 1 EJ timeout. No max execution penalty yet.
        mService.maybeProcessBuggyJob(jobEj, JobParameters.INTERNAL_STOP_REASON_TIMEOUT);
        grantRunUserInitiatedJobsPermission(true);
        assertEquals(mService.mConstants.RUNTIME_MIN_UI_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobUij));
        grantRunUserInitiatedJobsPermission(false);
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobUij));
        assertEquals(mService.mConstants.RUNTIME_MIN_EJ_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobEj));
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobReg));

        // 2 EJ timeouts. Safeguards disabled -> no penalties.
        jobEj.madeActive =
                sUptimeMillisClock.millis() - mService.mConstants.RUNTIME_MIN_EJ_GUARANTEE_MS;
        mService.maybeProcessBuggyJob(jobEj, JobParameters.INTERNAL_STOP_REASON_UNKNOWN);
        grantRunUserInitiatedJobsPermission(true);
        assertEquals(mService.mConstants.RUNTIME_MIN_UI_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobUij));
        grantRunUserInitiatedJobsPermission(false);
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobUij));
        assertEquals(mService.mConstants.RUNTIME_MIN_EJ_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobEj));
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobReg));

        // 1 reg timeout. No max execution penalty yet.
        mService.maybeProcessBuggyJob(jobReg, JobParameters.INTERNAL_STOP_REASON_TIMEOUT);
        grantRunUserInitiatedJobsPermission(true);
        assertEquals(mService.mConstants.RUNTIME_MIN_UI_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobUij));
        grantRunUserInitiatedJobsPermission(false);
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobUij));
        assertEquals(mService.mConstants.RUNTIME_MIN_EJ_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobEj));
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobReg));

        // 2 Reg timeouts. Safeguards disabled -> no penalties.
        jobReg.madeActive =
                sUptimeMillisClock.millis() - mService.mConstants.RUNTIME_MIN_GUARANTEE_MS;
        mService.maybeProcessBuggyJob(jobReg, JobParameters.INTERNAL_STOP_REASON_UNKNOWN);
        grantRunUserInitiatedJobsPermission(true);
        assertEquals(mService.mConstants.RUNTIME_MIN_UI_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobUij));
        grantRunUserInitiatedJobsPermission(false);
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobUij));
        assertEquals(mService.mConstants.RUNTIME_MIN_EJ_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobEj));
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobReg));
    }

    @Test
    public void testGetMinJobExecutionGuaranteeMs_timeoutSafeguards_enabled() {
        JobStatus jobUij = createJobStatus("testGetMinJobExecutionGuaranteeMs_timeoutSafeguards",
                createJobInfo(1)
                        .setUserInitiated(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY));
        JobStatus jobEj = createJobStatus("testGetMinJobExecutionGuaranteeMs_timeoutSafeguards",
                createJobInfo(2).setExpedited(true));
        JobStatus jobReg = createJobStatus("testGetMinJobExecutionGuaranteeMs_timeoutSafeguards",
                createJobInfo(3));
        spyOn(jobUij);
        when(jobUij.shouldTreatAsUserInitiatedJob()).thenReturn(true);
        jobUij.startedAsUserInitiatedJob = true;
        spyOn(jobEj);
        when(jobEj.shouldTreatAsExpeditedJob()).thenReturn(true);
        jobEj.startedAsExpeditedJob = true;

        mService.mConstants.ENABLE_EXECUTION_SAFEGUARDS_UDC = true;
        mService.mConstants.EXECUTION_SAFEGUARDS_UDC_TIMEOUT_UIJ_COUNT = 2;
        mService.mConstants.EXECUTION_SAFEGUARDS_UDC_TIMEOUT_EJ_COUNT = 2;
        mService.mConstants.EXECUTION_SAFEGUARDS_UDC_TIMEOUT_REG_COUNT = 2;
        mService.updateQuotaTracker();
        mService.resetScheduleQuota();

        // No timeouts -> no penalties.
        grantRunUserInitiatedJobsPermission(true);
        assertEquals(mService.mConstants.RUNTIME_MIN_UI_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobUij));
        grantRunUserInitiatedJobsPermission(false);
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobUij));
        assertEquals(mService.mConstants.RUNTIME_MIN_EJ_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobEj));
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobReg));

        // 1 UIJ timeout. No execution penalty yet.
        mService.maybeProcessBuggyJob(jobUij, JobParameters.INTERNAL_STOP_REASON_TIMEOUT);
        grantRunUserInitiatedJobsPermission(true);
        assertEquals(mService.mConstants.RUNTIME_MIN_UI_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobUij));
        grantRunUserInitiatedJobsPermission(false);
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobUij));
        assertEquals(mService.mConstants.RUNTIME_MIN_EJ_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobEj));
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobReg));

        // Not a timeout -> 1 UIJ timeout. No execution penalty yet.
        jobUij.madeActive = sUptimeMillisClock.millis() - 1;
        mService.maybeProcessBuggyJob(jobUij, JobParameters.INTERNAL_STOP_REASON_UNKNOWN);
        grantRunUserInitiatedJobsPermission(true);
        assertEquals(mService.mConstants.RUNTIME_MIN_UI_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobUij));
        grantRunUserInitiatedJobsPermission(false);
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobUij));
        assertEquals(mService.mConstants.RUNTIME_MIN_EJ_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobEj));
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobReg));

        // 2 UIJ timeouts. Min execution penalty only for UIJs.
        jobUij.madeActive =
                sUptimeMillisClock.millis() - mService.mConstants.RUNTIME_MIN_UI_GUARANTEE_MS;
        mService.maybeProcessBuggyJob(jobUij, JobParameters.INTERNAL_STOP_REASON_UNKNOWN);
        grantRunUserInitiatedJobsPermission(true);
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMinJobExecutionGuaranteeMs(jobUij));
        grantRunUserInitiatedJobsPermission(false);
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobUij));
        assertEquals(mService.mConstants.RUNTIME_MIN_EJ_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobEj));
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobReg));

        // 1 EJ timeout. No max execution penalty yet.
        mService.maybeProcessBuggyJob(jobEj, JobParameters.INTERNAL_STOP_REASON_TIMEOUT);
        grantRunUserInitiatedJobsPermission(true);
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMinJobExecutionGuaranteeMs(jobUij));
        grantRunUserInitiatedJobsPermission(false);
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobUij));
        assertEquals(mService.mConstants.RUNTIME_MIN_EJ_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobEj));
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobReg));

        // 2 EJ timeouts. Max execution penalty for EJs.
        jobEj.madeActive =
                sUptimeMillisClock.millis() - mService.mConstants.RUNTIME_MIN_EJ_GUARANTEE_MS;
        mService.maybeProcessBuggyJob(jobEj, JobParameters.INTERNAL_STOP_REASON_UNKNOWN);
        grantRunUserInitiatedJobsPermission(true);
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMinJobExecutionGuaranteeMs(jobUij));
        grantRunUserInitiatedJobsPermission(false);
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobUij));
        assertEquals(mService.mConstants.RUNTIME_MIN_EJ_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobEj));
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobReg));

        // 1 reg timeout. No max execution penalty yet.
        mService.maybeProcessBuggyJob(jobReg, JobParameters.INTERNAL_STOP_REASON_TIMEOUT);
        grantRunUserInitiatedJobsPermission(true);
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMinJobExecutionGuaranteeMs(jobUij));
        grantRunUserInitiatedJobsPermission(false);
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobUij));
        assertEquals(mService.mConstants.RUNTIME_MIN_EJ_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobEj));
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobReg));

        // 2 Reg timeouts. Max execution penalty for regular jobs.
        jobReg.madeActive =
                sUptimeMillisClock.millis() - mService.mConstants.RUNTIME_MIN_GUARANTEE_MS;
        mService.maybeProcessBuggyJob(jobReg, JobParameters.INTERNAL_STOP_REASON_UNKNOWN);
        grantRunUserInitiatedJobsPermission(true);
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMinJobExecutionGuaranteeMs(jobUij));
        grantRunUserInitiatedJobsPermission(false);
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobUij));
        assertEquals(mService.mConstants.RUNTIME_MIN_EJ_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobEj));
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMinJobExecutionGuaranteeMs(jobReg));
    }

    @Test
    public void testGetMaxJobExecutionTimeMs() {
        JobStatus jobUIDT = createJobStatus("testGetMaxJobExecutionTimeMs",
                createJobInfo(10)
                        .setUserInitiated(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY));
        JobStatus jobEj = createJobStatus("testGetMaxJobExecutionTimeMs",
                createJobInfo(2).setExpedited(true));
        JobStatus jobReg = createJobStatus("testGetMaxJobExecutionTimeMs",
                createJobInfo(3));
        spyOn(jobUIDT);
        when(jobUIDT.shouldTreatAsUserInitiatedJob()).thenReturn(true);
        spyOn(jobEj);
        when(jobEj.shouldTreatAsExpeditedJob()).thenReturn(true);

        QuotaController quotaController = mService.getQuotaController();
        spyOn(quotaController);
        TareController tareController = mService.getTareController();
        spyOn(tareController);
        doReturn(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS)
                .when(quotaController).getMaxJobExecutionTimeMsLocked(any());
        doReturn(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS)
                .when(tareController).getMaxJobExecutionTimeMsLocked(any());

        grantRunUserInitiatedJobsPermission(true);
        assertEquals(mService.mConstants.RUNTIME_UI_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobUIDT));
        grantRunUserInitiatedJobsPermission(false);
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobUIDT));

        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMaxJobExecutionTimeMs(jobEj));
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobReg));
    }

    @Test
    public void testGetMaxJobExecutionTimeMs_timeoutSafeguards_disabled() {
        JobStatus jobUij = createJobStatus("testGetMaxJobExecutionTimeMs_timeoutSafeguards",
                createJobInfo(1)
                        .setUserInitiated(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY));
        JobStatus jobEj = createJobStatus("testGetMaxJobExecutionTimeMs_timeoutSafeguards",
                createJobInfo(2).setExpedited(true));
        JobStatus jobReg = createJobStatus("testGetMaxJobExecutionTimeMs_timeoutSafeguards",
                createJobInfo(3));
        spyOn(jobUij);
        when(jobUij.shouldTreatAsUserInitiatedJob()).thenReturn(true);
        jobUij.startedAsUserInitiatedJob = true;
        spyOn(jobEj);
        when(jobEj.shouldTreatAsExpeditedJob()).thenReturn(true);
        jobEj.startedAsExpeditedJob = true;

        QuotaController quotaController = mService.getQuotaController();
        spyOn(quotaController);
        TareController tareController = mService.getTareController();
        spyOn(tareController);
        doReturn(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS)
                .when(quotaController).getMaxJobExecutionTimeMsLocked(any());
        doReturn(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS)
                .when(tareController).getMaxJobExecutionTimeMsLocked(any());

        mService.mConstants.ENABLE_EXECUTION_SAFEGUARDS_UDC = false;
        mService.mConstants.EXECUTION_SAFEGUARDS_UDC_TIMEOUT_UIJ_COUNT = 2;
        mService.mConstants.EXECUTION_SAFEGUARDS_UDC_TIMEOUT_EJ_COUNT = 2;
        mService.mConstants.EXECUTION_SAFEGUARDS_UDC_TIMEOUT_REG_COUNT = 2;
        mService.updateQuotaTracker();
        mService.resetScheduleQuota();

        // Safeguards disabled -> no penalties.
        grantRunUserInitiatedJobsPermission(true);
        assertEquals(mService.mConstants.RUNTIME_UI_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobUij));
        grantRunUserInitiatedJobsPermission(false);
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobUij));
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMaxJobExecutionTimeMs(jobEj));
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobReg));

        // 1 UIJ timeout. No max execution penalty yet.
        mService.maybeProcessBuggyJob(jobUij, JobParameters.INTERNAL_STOP_REASON_TIMEOUT);
        grantRunUserInitiatedJobsPermission(true);
        assertEquals(mService.mConstants.RUNTIME_UI_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobUij));
        grantRunUserInitiatedJobsPermission(false);
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobUij));
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMaxJobExecutionTimeMs(jobEj));
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobReg));

        // 2 UIJ timeouts. Safeguards disabled -> no penalties.
        jobUij.madeActive =
                sUptimeMillisClock.millis() - mService.mConstants.RUNTIME_MIN_UI_GUARANTEE_MS;
        mService.maybeProcessBuggyJob(jobUij, JobParameters.INTERNAL_STOP_REASON_UNKNOWN);
        grantRunUserInitiatedJobsPermission(true);
        assertEquals(mService.mConstants.RUNTIME_UI_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobUij));
        grantRunUserInitiatedJobsPermission(false);
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobUij));
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMaxJobExecutionTimeMs(jobEj));
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobReg));

        // 1 EJ timeout. No max execution penalty yet.
        mService.maybeProcessBuggyJob(jobEj, JobParameters.INTERNAL_STOP_REASON_TIMEOUT);
        grantRunUserInitiatedJobsPermission(true);
        assertEquals(mService.mConstants.RUNTIME_UI_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobUij));
        grantRunUserInitiatedJobsPermission(false);
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobUij));
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMaxJobExecutionTimeMs(jobEj));
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobReg));

        // 2 EJ timeouts. Safeguards disabled -> no penalties.
        jobEj.madeActive =
                sUptimeMillisClock.millis() - mService.mConstants.RUNTIME_MIN_EJ_GUARANTEE_MS;
        mService.maybeProcessBuggyJob(jobEj, JobParameters.INTERNAL_STOP_REASON_UNKNOWN);
        grantRunUserInitiatedJobsPermission(true);
        assertEquals(mService.mConstants.RUNTIME_UI_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobUij));
        grantRunUserInitiatedJobsPermission(false);
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobUij));
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMaxJobExecutionTimeMs(jobEj));
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobReg));

        // 1 reg timeout. No max execution penalty yet.
        mService.maybeProcessBuggyJob(jobReg, JobParameters.INTERNAL_STOP_REASON_TIMEOUT);
        grantRunUserInitiatedJobsPermission(true);
        assertEquals(mService.mConstants.RUNTIME_UI_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobUij));
        grantRunUserInitiatedJobsPermission(false);
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobUij));
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMaxJobExecutionTimeMs(jobEj));
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobReg));

        // 2 Reg timeouts. Safeguards disabled -> no penalties.
        jobReg.madeActive =
                sUptimeMillisClock.millis() - mService.mConstants.RUNTIME_MIN_GUARANTEE_MS;
        mService.maybeProcessBuggyJob(jobReg, JobParameters.INTERNAL_STOP_REASON_UNKNOWN);
        grantRunUserInitiatedJobsPermission(true);
        assertEquals(mService.mConstants.RUNTIME_UI_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobUij));
        grantRunUserInitiatedJobsPermission(false);
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobUij));
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMaxJobExecutionTimeMs(jobEj));
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobReg));
    }

    @Test
    public void testGetMaxJobExecutionTimeMs_timeoutSafeguards_enabled() {
        JobStatus jobUij = createJobStatus("testGetMaxJobExecutionTimeMs_timeoutSafeguards",
                createJobInfo(1)
                        .setUserInitiated(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY));
        JobStatus jobEj = createJobStatus("testGetMaxJobExecutionTimeMs_timeoutSafeguards",
                createJobInfo(2).setExpedited(true));
        JobStatus jobReg = createJobStatus("testGetMaxJobExecutionTimeMs_timeoutSafeguards",
                createJobInfo(3));
        spyOn(jobUij);
        when(jobUij.shouldTreatAsUserInitiatedJob()).thenReturn(true);
        jobUij.startedAsUserInitiatedJob = true;
        spyOn(jobEj);
        when(jobEj.shouldTreatAsExpeditedJob()).thenReturn(true);
        jobEj.startedAsExpeditedJob = true;

        QuotaController quotaController = mService.getQuotaController();
        spyOn(quotaController);
        TareController tareController = mService.getTareController();
        spyOn(tareController);
        doReturn(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS)
                .when(quotaController).getMaxJobExecutionTimeMsLocked(any());
        doReturn(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS)
                .when(tareController).getMaxJobExecutionTimeMsLocked(any());

        mService.mConstants.ENABLE_EXECUTION_SAFEGUARDS_UDC = true;
        mService.mConstants.EXECUTION_SAFEGUARDS_UDC_TIMEOUT_UIJ_COUNT = 2;
        mService.mConstants.EXECUTION_SAFEGUARDS_UDC_TIMEOUT_EJ_COUNT = 2;
        mService.mConstants.EXECUTION_SAFEGUARDS_UDC_TIMEOUT_REG_COUNT = 2;
        mService.updateQuotaTracker();
        mService.resetScheduleQuota();

        // No timeouts -> no penalties.
        grantRunUserInitiatedJobsPermission(true);
        assertEquals(mService.mConstants.RUNTIME_UI_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobUij));
        grantRunUserInitiatedJobsPermission(false);
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobUij));
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMaxJobExecutionTimeMs(jobEj));
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobReg));

        // 1 UIJ timeout. No max execution penalty yet.
        mService.maybeProcessBuggyJob(jobUij, JobParameters.INTERNAL_STOP_REASON_TIMEOUT);
        grantRunUserInitiatedJobsPermission(true);
        assertEquals(mService.mConstants.RUNTIME_UI_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobUij));
        grantRunUserInitiatedJobsPermission(false);
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobUij));
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMaxJobExecutionTimeMs(jobEj));
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobReg));

        // Not a timeout -> 1 UIJ timeout. No max execution penalty yet.
        jobUij.madeActive = sUptimeMillisClock.millis() - 1;
        mService.maybeProcessBuggyJob(jobUij, JobParameters.INTERNAL_STOP_REASON_UNKNOWN);
        grantRunUserInitiatedJobsPermission(true);
        assertEquals(mService.mConstants.RUNTIME_UI_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobUij));
        grantRunUserInitiatedJobsPermission(false);
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobUij));
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMaxJobExecutionTimeMs(jobEj));
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobReg));

        // 2 UIJ timeouts. Max execution penalty only for UIJs.
        jobUij.madeActive =
                sUptimeMillisClock.millis() - mService.mConstants.RUNTIME_MIN_UI_GUARANTEE_MS;
        mService.maybeProcessBuggyJob(jobUij, JobParameters.INTERNAL_STOP_REASON_UNKNOWN);
        grantRunUserInitiatedJobsPermission(true);
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobUij));
        grantRunUserInitiatedJobsPermission(false);
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobUij));
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMaxJobExecutionTimeMs(jobEj));
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobReg));

        // 1 EJ timeout. No max execution penalty yet.
        mService.maybeProcessBuggyJob(jobEj, JobParameters.INTERNAL_STOP_REASON_TIMEOUT);
        grantRunUserInitiatedJobsPermission(true);
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobUij));
        grantRunUserInitiatedJobsPermission(false);
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobUij));
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMaxJobExecutionTimeMs(jobEj));
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobReg));

        // Not a timeout -> 1 EJ timeout. No max execution penalty yet.
        jobEj.madeActive = sUptimeMillisClock.millis() - 1;
        mService.maybeProcessBuggyJob(jobEj, JobParameters.INTERNAL_STOP_REASON_UNKNOWN);
        grantRunUserInitiatedJobsPermission(true);
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobUij));
        grantRunUserInitiatedJobsPermission(false);
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobUij));
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMaxJobExecutionTimeMs(jobEj));
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobReg));

        // 2 EJ timeouts. Max execution penalty for EJs.
        jobEj.madeActive =
                sUptimeMillisClock.millis() - mService.mConstants.RUNTIME_MIN_EJ_GUARANTEE_MS;
        mService.maybeProcessBuggyJob(jobEj, JobParameters.INTERNAL_STOP_REASON_UNKNOWN);
        grantRunUserInitiatedJobsPermission(true);
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobUij));
        grantRunUserInitiatedJobsPermission(false);
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobUij));
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMaxJobExecutionTimeMs(jobEj));
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobReg));

        // 1 reg timeout. No max execution penalty yet.
        mService.maybeProcessBuggyJob(jobReg, JobParameters.INTERNAL_STOP_REASON_TIMEOUT);
        grantRunUserInitiatedJobsPermission(true);
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobUij));
        grantRunUserInitiatedJobsPermission(false);
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobUij));
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMaxJobExecutionTimeMs(jobEj));
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobReg));

        // Not a timeout -> 1 reg timeout. No max execution penalty yet.
        jobReg.madeActive = sUptimeMillisClock.millis() - 1;
        mService.maybeProcessBuggyJob(jobReg, JobParameters.INTERNAL_STOP_REASON_UNKNOWN);
        grantRunUserInitiatedJobsPermission(true);
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobUij));
        grantRunUserInitiatedJobsPermission(false);
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobUij));
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMaxJobExecutionTimeMs(jobEj));
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobReg));

        // 2 Reg timeouts. Max execution penalty for regular jobs.
        jobReg.madeActive =
                sUptimeMillisClock.millis() - mService.mConstants.RUNTIME_MIN_GUARANTEE_MS;
        mService.maybeProcessBuggyJob(jobReg, JobParameters.INTERNAL_STOP_REASON_UNKNOWN);
        grantRunUserInitiatedJobsPermission(true);
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobUij));
        grantRunUserInitiatedJobsPermission(false);
        assertEquals(mService.mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                mService.getMaxJobExecutionTimeMs(jobUij));
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMaxJobExecutionTimeMs(jobEj));
        assertEquals(mService.mConstants.RUNTIME_MIN_GUARANTEE_MS,
                mService.getMaxJobExecutionTimeMs(jobReg));
    }

    /**
     * Confirm that
     * {@link JobSchedulerService#getRescheduleJobForFailureLocked(JobStatus, int, int)}
     * returns a job that is no longer allowed to run as a user-initiated job after it hits
     * the cumulative execution limit.
     */
    @Test
    public void testGetRescheduleJobForFailure_cumulativeExecution() {
        JobStatus originalJob = createJobStatus("testGetRescheduleJobForFailure",
                createJobInfo()
                        .setUserInitiated(true)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY));
        assertTrue(originalJob.shouldTreatAsUserInitiatedJob());

        // Cumulative time = 0
        JobStatus rescheduledJob = mService.getRescheduleJobForFailureLocked(originalJob,
                JobParameters.STOP_REASON_UNDEFINED,
                JobParameters.INTERNAL_STOP_REASON_UNKNOWN);
        assertTrue(rescheduledJob.shouldTreatAsUserInitiatedJob());

        // Cumulative time = 50% of limit
        rescheduledJob.incrementCumulativeExecutionTime(
                mService.mConstants.RUNTIME_CUMULATIVE_UI_LIMIT_MS / 2);
        rescheduledJob = mService.getRescheduleJobForFailureLocked(rescheduledJob,
                JobParameters.STOP_REASON_UNDEFINED,
                JobParameters.INTERNAL_STOP_REASON_UNKNOWN);
        assertTrue(rescheduledJob.shouldTreatAsUserInitiatedJob());

        // Cumulative time = 99.999999% of limit
        rescheduledJob.incrementCumulativeExecutionTime(
                mService.mConstants.RUNTIME_CUMULATIVE_UI_LIMIT_MS / 2 - 1);
        rescheduledJob = mService.getRescheduleJobForFailureLocked(rescheduledJob,
                JobParameters.STOP_REASON_UNDEFINED,
                JobParameters.INTERNAL_STOP_REASON_UNKNOWN);
        assertTrue(rescheduledJob.shouldTreatAsUserInitiatedJob());

        // Cumulative time = 100+% of limit
        rescheduledJob.incrementCumulativeExecutionTime(2);
        rescheduledJob = mService.getRescheduleJobForFailureLocked(rescheduledJob,
                JobParameters.STOP_REASON_UNDEFINED,
                JobParameters.INTERNAL_STOP_REASON_UNKNOWN);
        assertFalse(rescheduledJob.shouldTreatAsUserInitiatedJob());
    }

    /**
     * Confirm that
     * {@link JobSchedulerService#getRescheduleJobForFailureLocked(JobStatus, int, int)}
     * returns a job with the correct delay and deadline constraints.
     */
    @Test
    public void testGetRescheduleJobForFailure_timingCalculations() {
        final long nowElapsed = sElapsedRealtimeClock.millis();
        final long initialBackoffMs = MINUTE_IN_MILLIS;
        mService.mConstants.SYSTEM_STOP_TO_FAILURE_RATIO = 3;

        JobStatus originalJob = createJobStatus("testGetRescheduleJobForFailure",
                createJobInfo()
                        .setBackoffCriteria(initialBackoffMs, JobInfo.BACKOFF_POLICY_LINEAR));
        assertEquals(JobStatus.NO_EARLIEST_RUNTIME, originalJob.getEarliestRunTime());
        assertEquals(JobStatus.NO_LATEST_RUNTIME, originalJob.getLatestRunTimeElapsed());

        // failure = 0, systemStop = 1
        JobStatus rescheduledJob = mService.getRescheduleJobForFailureLocked(originalJob,
                JobParameters.STOP_REASON_DEVICE_STATE,
                JobParameters.INTERNAL_STOP_REASON_DEVICE_THERMAL);
        assertEquals(JobStatus.NO_EARLIEST_RUNTIME, rescheduledJob.getEarliestRunTime());
        assertEquals(JobStatus.NO_LATEST_RUNTIME, rescheduledJob.getLatestRunTimeElapsed());

        // failure = 0, systemStop = 2
        rescheduledJob = mService.getRescheduleJobForFailureLocked(rescheduledJob,
                JobParameters.STOP_REASON_DEVICE_STATE,
                JobParameters.INTERNAL_STOP_REASON_PREEMPT);
        assertEquals(JobStatus.NO_EARLIEST_RUNTIME, rescheduledJob.getEarliestRunTime());
        assertEquals(JobStatus.NO_LATEST_RUNTIME, rescheduledJob.getLatestRunTimeElapsed());
        // failure = 0, systemStop = 3
        rescheduledJob = mService.getRescheduleJobForFailureLocked(rescheduledJob,
                JobParameters.STOP_REASON_CONSTRAINT_CHARGING,
                JobParameters.INTERNAL_STOP_REASON_CONSTRAINTS_NOT_SATISFIED);
        assertEquals(nowElapsed + initialBackoffMs, rescheduledJob.getEarliestRunTime());
        assertEquals(JobStatus.NO_LATEST_RUNTIME, rescheduledJob.getLatestRunTimeElapsed());

        // failure = 0, systemStop = 2 * SYSTEM_STOP_TO_FAILURE_RATIO
        for (int i = 0; i < mService.mConstants.SYSTEM_STOP_TO_FAILURE_RATIO; ++i) {
            rescheduledJob = mService.getRescheduleJobForFailureLocked(rescheduledJob,
                    JobParameters.STOP_REASON_SYSTEM_PROCESSING,
                    JobParameters.INTERNAL_STOP_REASON_RTC_UPDATED);
        }
        assertEquals(nowElapsed + 2 * initialBackoffMs, rescheduledJob.getEarliestRunTime());
        assertEquals(JobStatus.NO_LATEST_RUNTIME, rescheduledJob.getLatestRunTimeElapsed());

        // failure = 1, systemStop = 2 * SYSTEM_STOP_TO_FAILURE_RATIO
        rescheduledJob = mService.getRescheduleJobForFailureLocked(rescheduledJob,
                JobParameters.STOP_REASON_TIMEOUT,
                JobParameters.INTERNAL_STOP_REASON_TIMEOUT);
        assertEquals(nowElapsed + 3 * initialBackoffMs, rescheduledJob.getEarliestRunTime());
        assertEquals(JobStatus.NO_LATEST_RUNTIME, rescheduledJob.getLatestRunTimeElapsed());

        // failure = 2, systemStop = 2 * SYSTEM_STOP_TO_FAILURE_RATIO
        rescheduledJob = mService.getRescheduleJobForFailureLocked(rescheduledJob,
                JobParameters.STOP_REASON_UNDEFINED,
                JobParameters.INTERNAL_STOP_REASON_SUCCESSFUL_FINISH);
        assertEquals(nowElapsed + 4 * initialBackoffMs, rescheduledJob.getEarliestRunTime());
        assertEquals(JobStatus.NO_LATEST_RUNTIME, rescheduledJob.getLatestRunTimeElapsed());

        // failure = 3, systemStop = 2 * SYSTEM_STOP_TO_FAILURE_RATIO
        rescheduledJob = mService.getRescheduleJobForFailureLocked(rescheduledJob,
                JobParameters.STOP_REASON_UNDEFINED,
                JobParameters.INTERNAL_STOP_REASON_ANR);
        assertEquals(nowElapsed + 5 * initialBackoffMs, rescheduledJob.getEarliestRunTime());
        assertEquals(JobStatus.NO_LATEST_RUNTIME, rescheduledJob.getLatestRunTimeElapsed());
    }

    /**
     * Confirm that
     * {@link JobSchedulerService#getRescheduleJobForFailureLocked(JobStatus, int, int)}
     * returns a job that is correctly marked as demoted by the user.
     */
    @Test
    public void testGetRescheduleJobForFailure_userDemotion() {
        JobStatus originalJob = createJobStatus("testGetRescheduleJobForFailure", createJobInfo());
        assertEquals(0, originalJob.getInternalFlags() & JobStatus.INTERNAL_FLAG_DEMOTED_BY_USER);

        // Reschedule for a non-user reason
        JobStatus rescheduledJob = mService.getRescheduleJobForFailureLocked(originalJob,
                JobParameters.STOP_REASON_DEVICE_STATE,
                JobParameters.INTERNAL_STOP_REASON_DEVICE_THERMAL);
        assertEquals(0,
                rescheduledJob.getInternalFlags() & JobStatus.INTERNAL_FLAG_DEMOTED_BY_USER);

        // Reschedule for a user reason
        rescheduledJob = mService.getRescheduleJobForFailureLocked(rescheduledJob,
                JobParameters.STOP_REASON_USER,
                JobParameters.INTERNAL_STOP_REASON_USER_UI_STOP);
        assertNotEquals(0,
                rescheduledJob.getInternalFlags() & JobStatus.INTERNAL_FLAG_DEMOTED_BY_USER);

        // Reschedule a previously demoted job for a non-user reason
        rescheduledJob = mService.getRescheduleJobForFailureLocked(rescheduledJob,
                JobParameters.STOP_REASON_CONSTRAINT_CHARGING,
                JobParameters.INTERNAL_STOP_REASON_CONSTRAINTS_NOT_SATISFIED);
        assertNotEquals(0,
                rescheduledJob.getInternalFlags() & JobStatus.INTERNAL_FLAG_DEMOTED_BY_USER);
    }

    /**
     * Confirm that
     * returns {@code null} when for user-visible jobs stopped by the user.
     */
    @Test
    public void testGetRescheduleJobForFailure_userStopped() {
        JobStatus uiJob = createJobStatus("testGetRescheduleJobForFailure",
                createJobInfo().setUserInitiated(true)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY));
        JobStatus uvJob = createJobStatus("testGetRescheduleJobForFailure", createJobInfo());
        spyOn(uvJob);
        doReturn(true).when(uvJob).isUserVisibleJob();
        JobStatus regJob = createJobStatus("testGetRescheduleJobForFailure", createJobInfo());

        // Reschedule for a non-user reason
        JobStatus rescheduledUiJob = mService.getRescheduleJobForFailureLocked(uiJob,
                JobParameters.STOP_REASON_DEVICE_STATE,
                JobParameters.INTERNAL_STOP_REASON_DEVICE_THERMAL);
        JobStatus rescheduledUvJob = mService.getRescheduleJobForFailureLocked(uvJob,
                JobParameters.STOP_REASON_DEVICE_STATE,
                JobParameters.INTERNAL_STOP_REASON_DEVICE_THERMAL);
        JobStatus rescheduledRegJob = mService.getRescheduleJobForFailureLocked(regJob,
                JobParameters.STOP_REASON_DEVICE_STATE,
                JobParameters.INTERNAL_STOP_REASON_DEVICE_THERMAL);
        assertNotNull(rescheduledUiJob);
        assertNotNull(rescheduledUvJob);
        assertNotNull(rescheduledRegJob);

        // Reschedule for a user reason. The user-visible jobs shouldn't be rescheduled.
        spyOn(rescheduledUvJob);
        doReturn(true).when(rescheduledUvJob).isUserVisibleJob();
        rescheduledUiJob = mService.getRescheduleJobForFailureLocked(rescheduledUiJob,
                JobParameters.STOP_REASON_USER,
                JobParameters.INTERNAL_STOP_REASON_USER_UI_STOP);
        rescheduledUvJob = mService.getRescheduleJobForFailureLocked(rescheduledUvJob,
                JobParameters.STOP_REASON_USER,
                JobParameters.INTERNAL_STOP_REASON_USER_UI_STOP);
        rescheduledRegJob = mService.getRescheduleJobForFailureLocked(rescheduledRegJob,
                JobParameters.STOP_REASON_USER,
                JobParameters.INTERNAL_STOP_REASON_USER_UI_STOP);
        assertNull(rescheduledUiJob);
        assertNull(rescheduledUvJob);
        assertNotNull(rescheduledRegJob);
    }

    /**
     * Confirm that {@link JobSchedulerService#getRescheduleJobForPeriodic(JobStatus)} returns a job
     * with the correct delay and deadline constraints if the periodic job is scheduled with the
     * minimum possible period.
     */
    @Test
    public void testGetRescheduleJobForPeriodic_minPeriod() {
        final long now = sElapsedRealtimeClock.millis();
        JobStatus job = createJobStatus("testGetRescheduleJobForPeriodic_insideWindow",
                createJobInfo().setPeriodic(15 * MINUTE_IN_MILLIS));
        final long nextWindowStartTime = now + 15 * MINUTE_IN_MILLIS;
        final long nextWindowEndTime = now + 30 * MINUTE_IN_MILLIS;

        for (int i = 0; i < 25; i++) {
            JobStatus rescheduledJob = mService.getRescheduleJobForPeriodic(job);
            assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
            assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());
            advanceElapsedClock(30_000); // 30 seconds
        }

        for (int i = 0; i < 5; i++) {
            // Window buffering in last 1/6 of window.
            JobStatus rescheduledJob = mService.getRescheduleJobForPeriodic(job);
            assertEquals(nextWindowStartTime + i * 30_000, rescheduledJob.getEarliestRunTime());
            assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());
            advanceElapsedClock(30_000); // 30 seconds
        }
    }

    /**
     * Confirm that {@link JobSchedulerService#getRescheduleJobForPeriodic(JobStatus)} returns a job
     * with the correct delay and deadline constraints if the periodic job is scheduled with a
     * period that's too large.
     */
    @Test
    public void testGetRescheduleJobForPeriodic_largePeriod() {
        final long now = sElapsedRealtimeClock.millis();
        JobStatus job = createJobStatus("testGetRescheduleJobForPeriodic_insideWindow",
                createJobInfo().setPeriodic(2 * 365 * DAY_IN_MILLIS));
        assertEquals(now, job.getEarliestRunTime());
        // Periods are capped at 365 days (1 year).
        assertEquals(now + 365 * DAY_IN_MILLIS, job.getLatestRunTimeElapsed());
        final long nextWindowStartTime = now + 365 * DAY_IN_MILLIS;
        final long nextWindowEndTime = nextWindowStartTime + 365 * DAY_IN_MILLIS;

        JobStatus rescheduledJob = mService.getRescheduleJobForPeriodic(job);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());
    }

    /**
     * Confirm that {@link JobSchedulerService#getRescheduleJobForPeriodic(JobStatus)} returns a job
     * with the correct delay and deadline constraints if the periodic job is completed and
     * rescheduled while run in its expected running window.
     */
    @Test
    public void testGetRescheduleJobForPeriodic_insideWindow() {
        final long now = sElapsedRealtimeClock.millis();
        JobStatus job = createJobStatus("testGetRescheduleJobForPeriodic_insideWindow",
                createJobInfo().setPeriodic(HOUR_IN_MILLIS));
        final long nextWindowStartTime = now + HOUR_IN_MILLIS;
        final long nextWindowEndTime = now + 2 * HOUR_IN_MILLIS;

        JobStatus rescheduledJob = mService.getRescheduleJobForPeriodic(job);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        advanceElapsedClock(10 * MINUTE_IN_MILLIS); // now + 10 minutes

        rescheduledJob = mService.getRescheduleJobForPeriodic(job);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        advanceElapsedClock(20 * MINUTE_IN_MILLIS); // now + 30 minutes

        rescheduledJob = mService.getRescheduleJobForPeriodic(job);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        advanceElapsedClock(25 * MINUTE_IN_MILLIS); // now + 55 minutes

        rescheduledJob = mService.getRescheduleJobForPeriodic(job);
        // Shifted because it's close to the end of the window.
        assertEquals(nextWindowStartTime + 5 * MINUTE_IN_MILLIS,
                rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        advanceElapsedClock(4 * MINUTE_IN_MILLIS); // now + 59 minutes

        rescheduledJob = mService.getRescheduleJobForPeriodic(job);
        // Shifted because it's close to the end of the window.
        assertEquals(nextWindowStartTime + 9 * MINUTE_IN_MILLIS,
                rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());
    }

    /**
     * Confirm that {@link JobSchedulerService#getRescheduleJobForPeriodic(JobStatus)} returns a job
     * with an extra delay and correct deadline constraint if the periodic job is completed near the
     * end of its expected running window.
     */
    @Test
    public void testGetRescheduleJobForPeriodic_closeToEndOfWindow() {
        JobStatus frequentJob = createJobStatus(
                "testGetRescheduleJobForPeriodic_closeToEndOfWindow",
                createJobInfo().setPeriodic(15 * MINUTE_IN_MILLIS));
        long now = sElapsedRealtimeClock.millis();
        long nextWindowStartTime = now + 15 * MINUTE_IN_MILLIS;
        long nextWindowEndTime = now + 30 * MINUTE_IN_MILLIS;

        // At the beginning of the window. Next window should be unaffected.
        JobStatus rescheduledJob = mService.getRescheduleJobForPeriodic(frequentJob);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        // Halfway through window. Next window should be unaffected.
        advanceElapsedClock((long) (7.5 * MINUTE_IN_MILLIS));
        rescheduledJob = mService.getRescheduleJobForPeriodic(frequentJob);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        // In last 1/6 of window. Next window start time should be shifted slightly.
        advanceElapsedClock(6 * MINUTE_IN_MILLIS);
        rescheduledJob = mService.getRescheduleJobForPeriodic(frequentJob);
        assertEquals(nextWindowStartTime + MINUTE_IN_MILLIS,
                rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        JobStatus mediumJob = createJobStatus("testGetRescheduleJobForPeriodic_closeToEndOfWindow",
                createJobInfo().setPeriodic(HOUR_IN_MILLIS));
        now = sElapsedRealtimeClock.millis();
        nextWindowStartTime = now + HOUR_IN_MILLIS;
        nextWindowEndTime = now + 2 * HOUR_IN_MILLIS;

        // At the beginning of the window. Next window should be unaffected.
        rescheduledJob = mService.getRescheduleJobForPeriodic(mediumJob);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        // Halfway through window. Next window should be unaffected.
        advanceElapsedClock(30 * MINUTE_IN_MILLIS);
        rescheduledJob = mService.getRescheduleJobForPeriodic(mediumJob);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        // At the edge 1/6 of window. Next window should be unaffected.
        advanceElapsedClock(20 * MINUTE_IN_MILLIS);
        rescheduledJob = mService.getRescheduleJobForPeriodic(mediumJob);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        // In last 1/6 of window. Next window start time should be shifted slightly.
        advanceElapsedClock(6 * MINUTE_IN_MILLIS);
        rescheduledJob = mService.getRescheduleJobForPeriodic(mediumJob);
        assertEquals(nextWindowStartTime + (6 * MINUTE_IN_MILLIS),
                rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        JobStatus longJob = createJobStatus("testGetRescheduleJobForPeriodic_closeToEndOfWindow",
                createJobInfo().setPeriodic(6 * HOUR_IN_MILLIS));
        now = sElapsedRealtimeClock.millis();
        nextWindowStartTime = now + 6 * HOUR_IN_MILLIS;
        nextWindowEndTime = now + 12 * HOUR_IN_MILLIS;

        // At the beginning of the window. Next window should be unaffected.
        rescheduledJob = mService.getRescheduleJobForPeriodic(longJob);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        // Halfway through window. Next window should be unaffected.
        advanceElapsedClock(3 * HOUR_IN_MILLIS);
        rescheduledJob = mService.getRescheduleJobForPeriodic(longJob);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        // At the edge 1/6 of window. Next window should be unaffected.
        advanceElapsedClock(2 * HOUR_IN_MILLIS);
        rescheduledJob = mService.getRescheduleJobForPeriodic(longJob);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        // In last 1/6 of window. Next window should be unaffected since we're over the shift cap.
        advanceElapsedClock(15 * MINUTE_IN_MILLIS);
        rescheduledJob = mService.getRescheduleJobForPeriodic(longJob);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        // In last 1/6 of window. Next window start time should be shifted slightly.
        advanceElapsedClock(30 * MINUTE_IN_MILLIS);
        rescheduledJob = mService.getRescheduleJobForPeriodic(longJob);
        assertEquals(nextWindowStartTime + (30 * MINUTE_IN_MILLIS),
                rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        // Flex duration close to period duration.
        JobStatus gameyFlex = createJobStatus("testGetRescheduleJobForPeriodic_closeToEndOfWindow",
                createJobInfo().setPeriodic(HOUR_IN_MILLIS, 59 * MINUTE_IN_MILLIS));
        now = sElapsedRealtimeClock.millis();
        nextWindowStartTime = now + HOUR_IN_MILLIS + MINUTE_IN_MILLIS;
        nextWindowEndTime = now + 2 * HOUR_IN_MILLIS;
        advanceElapsedClock(MINUTE_IN_MILLIS);

        // At the beginning of the window. Next window should be unaffected.
        rescheduledJob = mService.getRescheduleJobForPeriodic(gameyFlex);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        // Halfway through window. Next window should be unaffected.
        advanceElapsedClock(29 * MINUTE_IN_MILLIS);
        rescheduledJob = mService.getRescheduleJobForPeriodic(gameyFlex);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        // At the edge 1/6 of window. Next window should be unaffected.
        advanceElapsedClock(20 * MINUTE_IN_MILLIS);
        rescheduledJob = mService.getRescheduleJobForPeriodic(gameyFlex);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        // In last 1/6 of window. Next window start time should be shifted slightly.
        advanceElapsedClock(6 * MINUTE_IN_MILLIS);
        rescheduledJob = mService.getRescheduleJobForPeriodic(gameyFlex);
        assertEquals(nextWindowStartTime + (5 * MINUTE_IN_MILLIS),
                rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        // Very short flex duration compared to period duration.
        JobStatus superFlex = createJobStatus("testGetRescheduleJobForPeriodic_closeToEndOfWindow",
                createJobInfo().setPeriodic(HOUR_IN_MILLIS, 10 * MINUTE_IN_MILLIS));
        now = sElapsedRealtimeClock.millis();
        nextWindowStartTime = now + HOUR_IN_MILLIS + 50 * MINUTE_IN_MILLIS;
        nextWindowEndTime = now + 2 * HOUR_IN_MILLIS;
        advanceElapsedClock(MINUTE_IN_MILLIS);

        // At the beginning of the window. Next window should be unaffected.
        rescheduledJob = mService.getRescheduleJobForPeriodic(superFlex);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        // Halfway through window. Next window should be unaffected.
        advanceElapsedClock(29 * MINUTE_IN_MILLIS);
        rescheduledJob = mService.getRescheduleJobForPeriodic(superFlex);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        // At the edge 1/6 of window. Next window should be unaffected.
        advanceElapsedClock(20 * MINUTE_IN_MILLIS);
        rescheduledJob = mService.getRescheduleJobForPeriodic(superFlex);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        // In last 1/6 of window. Next window should be unaffected since the flex duration pushes
        // the next window start time far enough away.
        advanceElapsedClock(6 * MINUTE_IN_MILLIS);
        rescheduledJob = mService.getRescheduleJobForPeriodic(superFlex);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());
    }

    /**
     * Confirm that {@link JobSchedulerService#getRescheduleJobForPeriodic(JobStatus)} returns a job
     * with the correct delay and deadline constraints if the periodic job with a custom flex
     * setting is completed and rescheduled while run in its expected running window.
     */
    @Test
    public void testGetRescheduleJobForPeriodic_insideWindow_flex() {
        JobStatus job = createJobStatus("testGetRescheduleJobForPeriodic_insideWindow_flex",
                createJobInfo().setPeriodic(HOUR_IN_MILLIS, 30 * MINUTE_IN_MILLIS));
        // First window starts 30 minutes from now.
        advanceElapsedClock(30 * MINUTE_IN_MILLIS);
        final long now = sElapsedRealtimeClock.millis();
        final long nextWindowStartTime = now + HOUR_IN_MILLIS;
        final long nextWindowEndTime = now + 90 * MINUTE_IN_MILLIS;

        JobStatus rescheduledJob = mService.getRescheduleJobForPeriodic(job);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        advanceElapsedClock(10 * MINUTE_IN_MILLIS); // now + 10 minutes

        rescheduledJob = mService.getRescheduleJobForPeriodic(job);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        advanceElapsedClock(15 * MINUTE_IN_MILLIS); // now + 25 minutes

        rescheduledJob = mService.getRescheduleJobForPeriodic(job);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        advanceElapsedClock(4 * MINUTE_IN_MILLIS); // now + 29 minutes

        rescheduledJob = mService.getRescheduleJobForPeriodic(job);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());
    }

    /**
     * Confirm that {@link JobSchedulerService#getRescheduleJobForPeriodic(JobStatus)} returns a job
     * with the correct delay and deadline constraints if the periodic job failed but then ran
     * successfully and was rescheduled while run in its expected running window.
     */
    @Test
    public void testGetRescheduleJobForPeriodic_insideWindow_failedJob() {
        final long now = sElapsedRealtimeClock.millis();
        final long nextWindowStartTime = now + HOUR_IN_MILLIS;
        final long nextWindowEndTime = now + 2 * HOUR_IN_MILLIS;
        JobStatus job = createJobStatus("testGetRescheduleJobForPeriodic_insideWindow_failedJob",
                createJobInfo().setPeriodic(HOUR_IN_MILLIS));
        JobStatus failedJob = mService.getRescheduleJobForFailureLocked(job,
                JobParameters.STOP_REASON_UNDEFINED,
                JobParameters.INTERNAL_STOP_REASON_SUCCESSFUL_FINISH);

        JobStatus rescheduledJob = mService.getRescheduleJobForPeriodic(failedJob);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        advanceElapsedClock(5 * MINUTE_IN_MILLIS); // now + 5 minutes
        failedJob = mService.getRescheduleJobForFailureLocked(job,
                JobParameters.STOP_REASON_UNDEFINED,
                JobParameters.INTERNAL_STOP_REASON_SUCCESSFUL_FINISH);
        advanceElapsedClock(5 * MINUTE_IN_MILLIS); // now + 10 minutes

        rescheduledJob = mService.getRescheduleJobForPeriodic(failedJob);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        advanceElapsedClock(35 * MINUTE_IN_MILLIS); // now + 45 minutes
        failedJob = mService.getRescheduleJobForFailureLocked(job,
                JobParameters.STOP_REASON_UNDEFINED,
                JobParameters.INTERNAL_STOP_REASON_SUCCESSFUL_FINISH);
        advanceElapsedClock(10 * MINUTE_IN_MILLIS); // now + 55 minutes

        rescheduledJob = mService.getRescheduleJobForPeriodic(failedJob);
        // Shifted because it's close to the end of the window.
        assertEquals(nextWindowStartTime + 5 * MINUTE_IN_MILLIS,
                rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        advanceElapsedClock(2 * MINUTE_IN_MILLIS); // now + 57 minutes
        failedJob = mService.getRescheduleJobForFailureLocked(job,
                JobParameters.STOP_REASON_UNDEFINED,
                JobParameters.INTERNAL_STOP_REASON_SUCCESSFUL_FINISH);
        advanceElapsedClock(2 * MINUTE_IN_MILLIS); // now + 59 minutes

        rescheduledJob = mService.getRescheduleJobForPeriodic(failedJob);
        // Shifted because it's close to the end of the window.
        assertEquals(nextWindowStartTime + 9 * MINUTE_IN_MILLIS,
                rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());
    }

    /**
     * Confirm that {@link JobSchedulerService#getRescheduleJobForPeriodic(JobStatus)} returns a job
     * with the correct delay and deadline constraints if the periodic job is completed and
     * rescheduled when run after its expected running window.
     */
    @Test
    public void testGetRescheduleJobForPeriodic_outsideWindow() {
        JobStatus job = createJobStatus("testGetRescheduleJobForPeriodic_outsideWindow",
                createJobInfo().setPeriodic(HOUR_IN_MILLIS));
        long now = sElapsedRealtimeClock.millis();
        long nextWindowStartTime = now + HOUR_IN_MILLIS;
        long nextWindowEndTime = now + 2 * HOUR_IN_MILLIS;

        advanceElapsedClock(HOUR_IN_MILLIS + MINUTE_IN_MILLIS);
        // Say the job ran at the very end of its previous window. The intended JSS behavior is to
        // have consistent windows, so the new window should start as soon as the previous window
        // ended and end PERIOD time after the previous window ended.
        JobStatus rescheduledJob = mService.getRescheduleJobForPeriodic(job);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        advanceElapsedClock(2 * HOUR_IN_MILLIS);
        // Say that the job ran at this point, possibly due to device idle.
        // The next window should be consistent (start and end at the time it would have had the job
        // run normally in previous windows).
        nextWindowStartTime += 2 * HOUR_IN_MILLIS;
        nextWindowEndTime += 2 * HOUR_IN_MILLIS;

        rescheduledJob = mService.getRescheduleJobForPeriodic(job);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());
    }

    /**
     * Confirm that {@link JobSchedulerService#getRescheduleJobForPeriodic(JobStatus)} returns a job
     * with the correct delay and deadline constraints if the periodic job with a custom flex
     * setting is completed and rescheduled when run after its expected running window.
     */
    @Test
    public void testGetRescheduleJobForPeriodic_outsideWindow_flex() {
        JobStatus job = createJobStatus("testGetRescheduleJobForPeriodic_outsideWindow_flex",
                createJobInfo().setPeriodic(HOUR_IN_MILLIS, 30 * MINUTE_IN_MILLIS));
        // First window starts 30 minutes from now.
        advanceElapsedClock(30 * MINUTE_IN_MILLIS);
        long now = sElapsedRealtimeClock.millis();
        long nextWindowStartTime = now + HOUR_IN_MILLIS;
        long nextWindowEndTime = now + 90 * MINUTE_IN_MILLIS;

        advanceElapsedClock(31 * MINUTE_IN_MILLIS);
        // Say the job ran at the very end of its previous window. The intended JSS behavior is to
        // have consistent windows, so the new window should start as soon as the previous window
        // ended and end PERIOD time after the previous window ended.
        JobStatus rescheduledJob = mService.getRescheduleJobForPeriodic(job);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        // 5 minutes before the start of the next window. It's too close to the next window, so the
        // returned job should be for the window after.
        advanceElapsedClock(24 * MINUTE_IN_MILLIS);
        nextWindowStartTime += HOUR_IN_MILLIS;
        nextWindowEndTime += HOUR_IN_MILLIS;
        rescheduledJob = mService.getRescheduleJobForPeriodic(job);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        advanceElapsedClock(2 * HOUR_IN_MILLIS + 10 * MINUTE_IN_MILLIS);
        // Say that the job ran at this point, possibly due to device idle.
        // The next window should be consistent (start and end at the time it would have had the job
        // run normally in previous windows).
        nextWindowStartTime += 2 * HOUR_IN_MILLIS;
        nextWindowEndTime += 2 * HOUR_IN_MILLIS;

        rescheduledJob = mService.getRescheduleJobForPeriodic(job);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());
    }

    /**
     * Confirm that {@link JobSchedulerService#getRescheduleJobForPeriodic(JobStatus)} returns a job
     * with the correct delay and deadline constraints if the periodic job failed but then ran
     * successfully and was rescheduled when run after its expected running window.
     */
    @Test
    public void testGetRescheduleJobForPeriodic_outsideWindow_failedJob() {
        JobStatus job = createJobStatus("testGetRescheduleJobForPeriodic_outsideWindow_failedJob",
                createJobInfo().setPeriodic(HOUR_IN_MILLIS));
        JobStatus failedJob = mService.getRescheduleJobForFailureLocked(job,
                JobParameters.STOP_REASON_UNDEFINED,
                JobParameters.INTERNAL_STOP_REASON_SUCCESSFUL_FINISH);
        long now = sElapsedRealtimeClock.millis();
        long nextWindowStartTime = now + HOUR_IN_MILLIS;
        long nextWindowEndTime = now + 2 * HOUR_IN_MILLIS;

        advanceElapsedClock(HOUR_IN_MILLIS + MINUTE_IN_MILLIS);
        // Say the job ran at the very end of its previous window. The intended JSS behavior is to
        // have consistent windows, so the new window should start as soon as the previous window
        // ended and end PERIOD time after the previous window ended.
        JobStatus rescheduledJob = mService.getRescheduleJobForPeriodic(failedJob);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        advanceElapsedClock(2 * HOUR_IN_MILLIS);
        // Say that the job ran at this point, possibly due to device idle.
        // The next window should be consistent (start and end at the time it would have had the job
        // run normally in previous windows).
        nextWindowStartTime += 2 * HOUR_IN_MILLIS;
        nextWindowEndTime += 2 * HOUR_IN_MILLIS;

        rescheduledJob = mService.getRescheduleJobForPeriodic(failedJob);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());
    }

    /**
     * Confirm that {@link JobSchedulerService#getRescheduleJobForPeriodic(JobStatus)} returns a job
     * with the correct delay and deadline constraints if the periodic job with a custom flex
     * setting failed but then ran successfully and was rescheduled when run after its expected
     * running window.
     */
    @Test
    public void testGetRescheduleJobForPeriodic_outsideWindow_flex_failedJob() {
        JobStatus job = createJobStatus(
                "testGetRescheduleJobForPeriodic_outsideWindow_flex_failedJob",
                createJobInfo().setPeriodic(HOUR_IN_MILLIS, 30 * MINUTE_IN_MILLIS));
        JobStatus failedJob = mService.getRescheduleJobForFailureLocked(job,
                JobParameters.STOP_REASON_UNDEFINED,
                JobParameters.INTERNAL_STOP_REASON_SUCCESSFUL_FINISH);
        // First window starts 30 minutes from now.
        advanceElapsedClock(30 * MINUTE_IN_MILLIS);
        long now = sElapsedRealtimeClock.millis();
        long nextWindowStartTime = now + HOUR_IN_MILLIS;
        long nextWindowEndTime = now + 90 * MINUTE_IN_MILLIS;

        advanceElapsedClock(31 * MINUTE_IN_MILLIS);
        // Say the job ran at the very end of its previous window. The intended JSS behavior is to
        // have consistent windows, so the new window should start as soon as the previous window
        // ended and end PERIOD time after the previous window ended.
        JobStatus rescheduledJob = mService.getRescheduleJobForPeriodic(failedJob);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        // 5 minutes before the start of the next window. It's too close to the next window, so the
        // returned job should be for the window after.
        advanceElapsedClock(24 * MINUTE_IN_MILLIS);
        nextWindowStartTime += HOUR_IN_MILLIS;
        nextWindowEndTime += HOUR_IN_MILLIS;
        rescheduledJob = mService.getRescheduleJobForPeriodic(job);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        advanceElapsedClock(2 * HOUR_IN_MILLIS);
        // Say that the job ran at this point, possibly due to device idle.
        // The next window should be consistent (start and end at the time it would have had the job
        // run normally in previous windows).
        nextWindowStartTime += 2 * HOUR_IN_MILLIS;
        nextWindowEndTime += 2 * HOUR_IN_MILLIS;

        rescheduledJob = mService.getRescheduleJobForPeriodic(failedJob);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());
    }

    @Test
    public void testGetRescheduleJobForPeriodic_outsideWindow_flex_failedJob_longPeriod() {
        JobStatus job = createJobStatus(
                "testGetRescheduleJobForPeriodic_outsideWindow_flex_failedJob_longPeriod",
                createJobInfo().setPeriodic(7 * DAY_IN_MILLIS, 9 * HOUR_IN_MILLIS));
        JobStatus failedJob = mService.getRescheduleJobForFailureLocked(job,
                JobParameters.STOP_REASON_UNDEFINED,
                JobParameters.INTERNAL_STOP_REASON_SUCCESSFUL_FINISH);
        // First window starts 6.625 days from now.
        advanceElapsedClock(6 * DAY_IN_MILLIS + 15 * HOUR_IN_MILLIS);
        long now = sElapsedRealtimeClock.millis();
        long nextWindowStartTime = now + 7 * DAY_IN_MILLIS;
        long nextWindowEndTime = nextWindowStartTime + 9 * HOUR_IN_MILLIS;

        advanceElapsedClock(6 * HOUR_IN_MILLIS + MINUTE_IN_MILLIS);
        // Say the job ran at the very end of its previous window. The intended JSS behavior is to
        // have consistent windows, so the new window should start as soon as the previous window
        // ended and end PERIOD time after the previous window ended.
        JobStatus rescheduledJob = mService.getRescheduleJobForPeriodic(failedJob);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        advanceElapsedClock(DAY_IN_MILLIS);
        // Say the job ran a day late. Since the period is massive compared to the flex, JSS should
        // put the rescheduled job in the original window.
        rescheduledJob = mService.getRescheduleJobForPeriodic(failedJob);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        // 1 day before the start of the next window. Given the large period, respect the original
        // next window.
        advanceElapsedClock(nextWindowStartTime - sElapsedRealtimeClock.millis() - DAY_IN_MILLIS);
        rescheduledJob = mService.getRescheduleJobForPeriodic(job);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        // 1 hour before the start of the next window. It's too close to the next window, so the
        // returned job should be for the window after.
        long oneHourBeforeNextWindow =
                nextWindowStartTime - sElapsedRealtimeClock.millis() - HOUR_IN_MILLIS;
        long fiveMinsBeforeNextWindow =
                nextWindowStartTime - sElapsedRealtimeClock.millis() - 5 * MINUTE_IN_MILLIS;
        advanceElapsedClock(oneHourBeforeNextWindow);
        nextWindowStartTime += 7 * DAY_IN_MILLIS;
        nextWindowEndTime += 7 * DAY_IN_MILLIS;
        rescheduledJob = mService.getRescheduleJobForPeriodic(job);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        // 5 minutes before the start of the next window. It's too close to the next window, so the
        // returned job should be for the window after.
        advanceElapsedClock(fiveMinsBeforeNextWindow);
        rescheduledJob = mService.getRescheduleJobForPeriodic(job);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        advanceElapsedClock(14 * DAY_IN_MILLIS);
        // Say that the job ran at this point, probably because the phone was off the entire time.
        // The next window should be consistent (start and end at the time it would have had the job
        // run normally in previous windows).
        nextWindowStartTime += 14 * DAY_IN_MILLIS;
        nextWindowEndTime += 14 * DAY_IN_MILLIS;

        rescheduledJob = mService.getRescheduleJobForPeriodic(failedJob);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        // Test original job again but with a huge delay from the original execution window

        // 1 day before the start of the next window. Given the large period, respect the original
        // next window.
        advanceElapsedClock(nextWindowStartTime - sElapsedRealtimeClock.millis() - DAY_IN_MILLIS);
        rescheduledJob = mService.getRescheduleJobForPeriodic(job);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        // 1 hour before the start of the next window. It's too close to the next window, so the
        // returned job should be for the window after.
        oneHourBeforeNextWindow =
                nextWindowStartTime - sElapsedRealtimeClock.millis() - HOUR_IN_MILLIS;
        fiveMinsBeforeNextWindow =
                nextWindowStartTime - sElapsedRealtimeClock.millis() - 5 * MINUTE_IN_MILLIS;
        advanceElapsedClock(oneHourBeforeNextWindow);
        nextWindowStartTime += 7 * DAY_IN_MILLIS;
        nextWindowEndTime += 7 * DAY_IN_MILLIS;
        rescheduledJob = mService.getRescheduleJobForPeriodic(job);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());

        // 5 minutes before the start of the next window. It's too close to the next window, so the
        // returned job should be for the window after.
        advanceElapsedClock(fiveMinsBeforeNextWindow);
        rescheduledJob = mService.getRescheduleJobForPeriodic(job);
        assertEquals(nextWindowStartTime, rescheduledJob.getEarliestRunTime());
        assertEquals(nextWindowEndTime, rescheduledJob.getLatestRunTimeElapsed());
    }

    /** Tests that rare job batching works as expected. */
    @Test
    public void testConnectivityJobBatching() {
        mSetFlagsRule.enableFlags(FLAG_BATCH_CONNECTIVITY_JOBS_PER_NETWORK);

        spyOn(mService);
        doReturn(false).when(mService).evaluateControllerStatesLocked(any());
        doNothing().when(mService).noteJobsPending(any());
        doReturn(true).when(mService).isReadyToBeExecutedLocked(any(), anyBoolean());
        ConnectivityController connectivityController = mService.getConnectivityController();
        spyOn(connectivityController);
        advanceElapsedClock(24 * HOUR_IN_MILLIS);

        JobSchedulerService.MaybeReadyJobQueueFunctor maybeQueueFunctor =
                mService.new MaybeReadyJobQueueFunctor();
        mService.mConstants.CONN_TRANSPORT_BATCH_THRESHOLD.clear();
        mService.mConstants.CONN_TRANSPORT_BATCH_THRESHOLD
                .put(NetworkCapabilities.TRANSPORT_CELLULAR, 5);
        mService.mConstants.CONN_TRANSPORT_BATCH_THRESHOLD
                .put(NetworkCapabilities.TRANSPORT_WIFI, 2);
        mService.mConstants.CONN_MAX_CONNECTIVITY_JOB_BATCH_DELAY_MS = HOUR_IN_MILLIS;

        final Network network = mock(Network.class);

        // Not enough connectivity jobs to run.
        mService.getPendingJobQueue().clear();
        maybeQueueFunctor.reset();
        NetworkCapabilities capabilities = new NetworkCapabilities.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build();
        doReturn(capabilities).when(connectivityController).getNetworkCapabilities(network);
        doReturn(false).when(connectivityController).isNetworkInStateForJobRunLocked(any());
        for (int i = 0; i < 4; ++i) {
            JobStatus job = createJobStatus(
                    "testConnectivityJobBatching",
                    createJobInfo().setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY));
            job.setStandbyBucket(ACTIVE_INDEX);
            job.network = network;

            maybeQueueFunctor.accept(job);
            assertNull(maybeQueueFunctor.mBatches.get(null));
            assertEquals(i + 1, maybeQueueFunctor.mBatches.get(network).size());
            assertEquals(i + 1, maybeQueueFunctor.runnableJobs.size());
            assertEquals(sElapsedRealtimeClock.millis(), job.getFirstForceBatchedTimeElapsed());
        }
        maybeQueueFunctor.postProcessLocked();
        assertEquals(0, mService.getPendingJobQueue().size());

        // Not enough connectivity jobs to run, but the network is already active
        mService.getPendingJobQueue().clear();
        maybeQueueFunctor.reset();
        doReturn(capabilities).when(connectivityController).getNetworkCapabilities(network);
        doReturn(true).when(connectivityController).isNetworkInStateForJobRunLocked(any());
        for (int i = 0; i < 4; ++i) {
            JobStatus job = createJobStatus(
                    "testConnectivityJobBatching",
                    createJobInfo().setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY));
            job.setStandbyBucket(ACTIVE_INDEX);
            job.network = network;

            maybeQueueFunctor.accept(job);
            assertNull(maybeQueueFunctor.mBatches.get(null));
            assertEquals(i + 1, maybeQueueFunctor.mBatches.get(network).size());
            assertEquals(i + 1, maybeQueueFunctor.runnableJobs.size());
            assertEquals(0, job.getFirstForceBatchedTimeElapsed());
        }
        maybeQueueFunctor.postProcessLocked();
        assertEquals(4, mService.getPendingJobQueue().size());

        // Enough connectivity jobs to run.
        mService.getPendingJobQueue().clear();
        maybeQueueFunctor.reset();
        capabilities = new NetworkCapabilities.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();
        doReturn(capabilities).when(connectivityController).getNetworkCapabilities(network);
        doReturn(false).when(connectivityController).isNetworkInStateForJobRunLocked(any());
        for (int i = 0; i < 3; ++i) {
            JobStatus job = createJobStatus(
                    "testConnectivityJobBatching",
                    createJobInfo().setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY));
            job.setStandbyBucket(ACTIVE_INDEX);
            job.network = network;

            maybeQueueFunctor.accept(job);
            assertEquals(i + 1, maybeQueueFunctor.mBatches.get(network).size());
            assertEquals(i + 1, maybeQueueFunctor.runnableJobs.size());
            assertEquals(sElapsedRealtimeClock.millis(), job.getFirstForceBatchedTimeElapsed());
        }
        maybeQueueFunctor.postProcessLocked();
        assertEquals(3, mService.getPendingJobQueue().size());

        // Not enough connectivity jobs to run, but a non-batched job saves the day.
        mService.getPendingJobQueue().clear();
        maybeQueueFunctor.reset();
        JobStatus runningJob = createJobStatus(
                "testConnectivityJobBatching",
                createJobInfo().setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY));
        runningJob.network = network;
        doReturn(true).when(mService).isCurrentlyRunningLocked(runningJob);
        capabilities = new NetworkCapabilities.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build();
        doReturn(capabilities).when(connectivityController).getNetworkCapabilities(network);
        for (int i = 0; i < 3; ++i) {
            JobStatus job = createJobStatus(
                    "testConnectivityJobBatching",
                    createJobInfo().setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY));
            job.setStandbyBucket(ACTIVE_INDEX);
            job.network = network;

            maybeQueueFunctor.accept(job);
            assertEquals(i + 1, maybeQueueFunctor.mBatches.get(network).size());
            assertEquals(i + 1, maybeQueueFunctor.runnableJobs.size());
            assertEquals(sElapsedRealtimeClock.millis(), job.getFirstForceBatchedTimeElapsed());
        }
        maybeQueueFunctor.accept(runningJob);
        maybeQueueFunctor.postProcessLocked();
        assertEquals(3, mService.getPendingJobQueue().size());

        // Not enough connectivity jobs to run, but an old connectivity job saves the day.
        mService.getPendingJobQueue().clear();
        maybeQueueFunctor.reset();
        JobStatus oldConnJob = createJobStatus("testConnectivityJobBatching",
                createJobInfo().setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY));
        oldConnJob.network = network;
        final long oldBatchTime = sElapsedRealtimeClock.millis()
                - 2 * mService.mConstants.CONN_MAX_CONNECTIVITY_JOB_BATCH_DELAY_MS;
        oldConnJob.setFirstForceBatchedTimeElapsed(oldBatchTime);
        for (int i = 0; i < 2; ++i) {
            JobStatus job = createJobStatus(
                    "testConnectivityJobBatching",
                    createJobInfo().setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY));
            job.setStandbyBucket(ACTIVE_INDEX);
            job.network = network;

            maybeQueueFunctor.accept(job);
            assertEquals(i + 1, maybeQueueFunctor.mBatches.get(network).size());
            assertEquals(i + 1, maybeQueueFunctor.runnableJobs.size());
            assertEquals(sElapsedRealtimeClock.millis(), job.getFirstForceBatchedTimeElapsed());
        }
        maybeQueueFunctor.accept(oldConnJob);
        assertEquals(oldBatchTime, oldConnJob.getFirstForceBatchedTimeElapsed());
        maybeQueueFunctor.postProcessLocked();
        assertEquals(3, mService.getPendingJobQueue().size());

        // Transport type doesn't have a set threshold. One job should be the default threshold.
        mService.getPendingJobQueue().clear();
        maybeQueueFunctor.reset();
        capabilities = new NetworkCapabilities.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build();
        doReturn(capabilities).when(connectivityController).getNetworkCapabilities(network);
        JobStatus job = createJobStatus(
                "testConnectivityJobBatching",
                createJobInfo().setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY));
        job.setStandbyBucket(ACTIVE_INDEX);
        job.network = network;
        maybeQueueFunctor.accept(job);
        assertEquals(1, maybeQueueFunctor.mBatches.get(network).size());
        assertEquals(1, maybeQueueFunctor.runnableJobs.size());
        assertEquals(sElapsedRealtimeClock.millis(), job.getFirstForceBatchedTimeElapsed());
        maybeQueueFunctor.postProcessLocked();
        assertEquals(1, mService.getPendingJobQueue().size());
    }

    /** Tests that active job batching works as expected. */
    @Test
    public void testActiveJobBatching_activeBatchingEnabled() {
        mSetFlagsRule.enableFlags(FLAG_BATCH_ACTIVE_BUCKET_JOBS);

        spyOn(mService);
        doReturn(false).when(mService).evaluateControllerStatesLocked(any());
        doNothing().when(mService).noteJobsPending(any());
        doReturn(true).when(mService).isReadyToBeExecutedLocked(any(), anyBoolean());
        advanceElapsedClock(24 * HOUR_IN_MILLIS);

        JobSchedulerService.MaybeReadyJobQueueFunctor maybeQueueFunctor =
                mService.new MaybeReadyJobQueueFunctor();
        mService.mConstants.MIN_READY_CPU_ONLY_JOBS_COUNT = 5;
        mService.mConstants.MAX_CPU_ONLY_JOB_BATCH_DELAY_MS = HOUR_IN_MILLIS;

        // Not enough ACTIVE jobs to run.
        mService.getPendingJobQueue().clear();
        maybeQueueFunctor.reset();
        for (int i = 0; i < mService.mConstants.MIN_READY_CPU_ONLY_JOBS_COUNT / 2; ++i) {
            JobStatus job = createJobStatus("testActiveJobBatching", createJobInfo());
            job.setStandbyBucket(ACTIVE_INDEX);

            maybeQueueFunctor.accept(job);
            assertEquals(i + 1, maybeQueueFunctor.mBatches.get(null).size());
            assertEquals(i + 1, maybeQueueFunctor.runnableJobs.size());
            assertEquals(sElapsedRealtimeClock.millis(), job.getFirstForceBatchedTimeElapsed());
        }
        maybeQueueFunctor.postProcessLocked();
        assertEquals(0, mService.getPendingJobQueue().size());

        // Enough ACTIVE jobs to run.
        mService.getPendingJobQueue().clear();
        maybeQueueFunctor.reset();
        for (int i = 0; i < mService.mConstants.MIN_READY_CPU_ONLY_JOBS_COUNT; ++i) {
            JobStatus job = createJobStatus("testActiveJobBatching", createJobInfo());
            job.setStandbyBucket(ACTIVE_INDEX);

            maybeQueueFunctor.accept(job);
            assertEquals(i + 1, maybeQueueFunctor.mBatches.get(null).size());
            assertEquals(i + 1, maybeQueueFunctor.runnableJobs.size());
            assertEquals(sElapsedRealtimeClock.millis(), job.getFirstForceBatchedTimeElapsed());
        }
        maybeQueueFunctor.postProcessLocked();
        assertEquals(5, mService.getPendingJobQueue().size());

        // Not enough ACTIVE jobs to run, but a non-batched job saves the day.
        mService.getPendingJobQueue().clear();
        maybeQueueFunctor.reset();
        JobStatus expeditedJob = createJobStatus("testActiveJobBatching",
                createJobInfo().setExpedited(true));
        spyOn(expeditedJob);
        when(expeditedJob.shouldTreatAsExpeditedJob()).thenReturn(true);
        expeditedJob.setStandbyBucket(RARE_INDEX);
        for (int i = 0; i < mService.mConstants.MIN_READY_CPU_ONLY_JOBS_COUNT / 2; ++i) {
            JobStatus job = createJobStatus("testActiveJobBatching", createJobInfo());
            job.setStandbyBucket(ACTIVE_INDEX);

            maybeQueueFunctor.accept(job);
            assertEquals(i + 1, maybeQueueFunctor.mBatches.get(null).size());
            assertEquals(i + 1, maybeQueueFunctor.runnableJobs.size());
            assertEquals(sElapsedRealtimeClock.millis(), job.getFirstForceBatchedTimeElapsed());
        }
        maybeQueueFunctor.accept(expeditedJob);
        maybeQueueFunctor.postProcessLocked();
        assertEquals(3, mService.getPendingJobQueue().size());

        // Not enough ACTIVE jobs to run, but an old ACTIVE job saves the day.
        mService.getPendingJobQueue().clear();
        maybeQueueFunctor.reset();
        JobStatus oldActiveJob = createJobStatus("testActiveJobBatching", createJobInfo());
        oldActiveJob.setStandbyBucket(ACTIVE_INDEX);
        final long oldBatchTime = sElapsedRealtimeClock.millis()
                - 2 * mService.mConstants.MAX_CPU_ONLY_JOB_BATCH_DELAY_MS;
        oldActiveJob.setFirstForceBatchedTimeElapsed(oldBatchTime);
        for (int i = 0; i < mService.mConstants.MIN_READY_CPU_ONLY_JOBS_COUNT / 2; ++i) {
            JobStatus job = createJobStatus("testActiveJobBatching", createJobInfo());
            job.setStandbyBucket(ACTIVE_INDEX);

            maybeQueueFunctor.accept(job);
            assertEquals(i + 1, maybeQueueFunctor.mBatches.get(null).size());
            assertEquals(i + 1, maybeQueueFunctor.runnableJobs.size());
            assertEquals(sElapsedRealtimeClock.millis(), job.getFirstForceBatchedTimeElapsed());
        }
        maybeQueueFunctor.accept(oldActiveJob);
        assertEquals(oldBatchTime, oldActiveJob.getFirstForceBatchedTimeElapsed());
        maybeQueueFunctor.postProcessLocked();
        assertEquals(3, mService.getPendingJobQueue().size());
    }

    /** Tests that rare job batching works as expected. */
    @Test
    public void testRareJobBatching() {
        spyOn(mService);
        doReturn(false).when(mService).evaluateControllerStatesLocked(any());
        doNothing().when(mService).noteJobsPending(any());
        doReturn(true).when(mService).isReadyToBeExecutedLocked(any(), anyBoolean());
        advanceElapsedClock(24 * HOUR_IN_MILLIS);

        JobSchedulerService.MaybeReadyJobQueueFunctor maybeQueueFunctor =
                mService.new MaybeReadyJobQueueFunctor();
        mService.mConstants.MIN_READY_NON_ACTIVE_JOBS_COUNT = 5;
        mService.mConstants.MAX_NON_ACTIVE_JOB_BATCH_DELAY_MS = HOUR_IN_MILLIS;

        // Not enough RARE jobs to run.
        mService.getPendingJobQueue().clear();
        maybeQueueFunctor.reset();
        for (int i = 0; i < mService.mConstants.MIN_READY_NON_ACTIVE_JOBS_COUNT / 2; ++i) {
            JobStatus job = createJobStatus("testRareJobBatching", createJobInfo());
            job.setStandbyBucket(RARE_INDEX);

            maybeQueueFunctor.accept(job);
            assertEquals(i + 1, maybeQueueFunctor.mBatches.get(null).size());
            assertEquals(i + 1, maybeQueueFunctor.runnableJobs.size());
            assertEquals(sElapsedRealtimeClock.millis(), job.getFirstForceBatchedTimeElapsed());
        }
        maybeQueueFunctor.postProcessLocked();
        assertEquals(0, mService.getPendingJobQueue().size());

        // Enough RARE jobs to run.
        mService.getPendingJobQueue().clear();
        maybeQueueFunctor.reset();
        for (int i = 0; i < mService.mConstants.MIN_READY_NON_ACTIVE_JOBS_COUNT; ++i) {
            JobStatus job = createJobStatus("testRareJobBatching", createJobInfo());
            job.setStandbyBucket(RARE_INDEX);

            maybeQueueFunctor.accept(job);
            assertEquals(i + 1, maybeQueueFunctor.mBatches.get(null).size());
            assertEquals(i + 1, maybeQueueFunctor.runnableJobs.size());
            assertEquals(sElapsedRealtimeClock.millis(), job.getFirstForceBatchedTimeElapsed());
        }
        maybeQueueFunctor.postProcessLocked();
        assertEquals(5, mService.getPendingJobQueue().size());

        // Not enough RARE jobs to run, but a non-batched job saves the day.
        mSetFlagsRule.disableFlags(FLAG_BATCH_ACTIVE_BUCKET_JOBS);
        mService.getPendingJobQueue().clear();
        maybeQueueFunctor.reset();
        JobStatus activeJob = createJobStatus("testRareJobBatching", createJobInfo());
        activeJob.setStandbyBucket(ACTIVE_INDEX);
        for (int i = 0; i < mService.mConstants.MIN_READY_NON_ACTIVE_JOBS_COUNT / 2; ++i) {
            JobStatus job = createJobStatus("testRareJobBatching", createJobInfo());
            job.setStandbyBucket(RARE_INDEX);

            maybeQueueFunctor.accept(job);
            assertEquals(i + 1, maybeQueueFunctor.mBatches.get(null).size());
            assertEquals(i + 1, maybeQueueFunctor.runnableJobs.size());
            assertEquals(sElapsedRealtimeClock.millis(), job.getFirstForceBatchedTimeElapsed());
        }
        maybeQueueFunctor.accept(activeJob);
        maybeQueueFunctor.postProcessLocked();
        assertEquals(3, mService.getPendingJobQueue().size());

        // Not enough RARE jobs to run, but an old RARE job saves the day.
        mService.getPendingJobQueue().clear();
        maybeQueueFunctor.reset();
        JobStatus oldRareJob = createJobStatus("testRareJobBatching", createJobInfo());
        oldRareJob.setStandbyBucket(RARE_INDEX);
        final long oldBatchTime = sElapsedRealtimeClock.millis()
                - 2 * mService.mConstants.MAX_NON_ACTIVE_JOB_BATCH_DELAY_MS;
        oldRareJob.setFirstForceBatchedTimeElapsed(oldBatchTime);
        for (int i = 0; i < mService.mConstants.MIN_READY_NON_ACTIVE_JOBS_COUNT / 2; ++i) {
            JobStatus job = createJobStatus("testRareJobBatching", createJobInfo());
            job.setStandbyBucket(RARE_INDEX);

            maybeQueueFunctor.accept(job);
            assertEquals(i + 1, maybeQueueFunctor.mBatches.get(null).size());
            assertEquals(i + 1, maybeQueueFunctor.runnableJobs.size());
            assertEquals(sElapsedRealtimeClock.millis(), job.getFirstForceBatchedTimeElapsed());
        }
        maybeQueueFunctor.accept(oldRareJob);
        assertEquals(oldBatchTime, oldRareJob.getFirstForceBatchedTimeElapsed());
        maybeQueueFunctor.postProcessLocked();
        assertEquals(3, mService.getPendingJobQueue().size());
    }

    /** Tests that jobs scheduled by the app itself are counted towards scheduling limits. */
    @Test
    public void testScheduleLimiting_RegularSchedule_Blocked() {
        mService.mConstants.ENABLE_API_QUOTAS = true;
        mService.mConstants.API_QUOTA_SCHEDULE_COUNT = 300;
        mService.mConstants.API_QUOTA_SCHEDULE_WINDOW_MS = 300000;
        mService.mConstants.API_QUOTA_SCHEDULE_THROW_EXCEPTION = false;
        mService.mConstants.API_QUOTA_SCHEDULE_RETURN_FAILURE_RESULT = true;
        mService.updateQuotaTracker();
        mService.resetScheduleQuota();

        final JobInfo job = createJobInfo().setPersisted(true).build();
        for (int i = 0; i < 500; ++i) {
            final int expected =
                    i < 300 ? JobScheduler.RESULT_SUCCESS : JobScheduler.RESULT_FAILURE;
            assertEquals("Got unexpected result for schedule #" + (i + 1),
                    expected,
                    mService.scheduleAsPackage(job, null, TEST_UID, null, 0, "JSSTest", ""));
        }
    }

    /**
     * Tests that jobs scheduled by the app itself succeed even if the app is above the scheduling
     * limit.
     */
    @Test
    public void testScheduleLimiting_RegularSchedule_Allowed() {
        mService.mConstants.ENABLE_API_QUOTAS = true;
        mService.mConstants.API_QUOTA_SCHEDULE_COUNT = 300;
        mService.mConstants.API_QUOTA_SCHEDULE_WINDOW_MS = 300000;
        mService.mConstants.API_QUOTA_SCHEDULE_THROW_EXCEPTION = false;
        mService.mConstants.API_QUOTA_SCHEDULE_RETURN_FAILURE_RESULT = false;
        mService.updateQuotaTracker();
        mService.resetScheduleQuota();

        final JobInfo job = createJobInfo().setPersisted(true).build();
        for (int i = 0; i < 500; ++i) {
            assertEquals("Got unexpected result for schedule #" + (i + 1),
                    JobScheduler.RESULT_SUCCESS,
                    mService.scheduleAsPackage(job, null, TEST_UID, null, 0, "JSSTest", ""));
        }
    }

    /**
     * Tests that jobs scheduled through a proxy (eg. system server) don't count towards scheduling
     * limits.
     */
    @Test
    public void testScheduleLimiting_Proxy() {
        mService.mConstants.ENABLE_API_QUOTAS = true;
        mService.mConstants.API_QUOTA_SCHEDULE_COUNT = 300;
        mService.mConstants.API_QUOTA_SCHEDULE_WINDOW_MS = 300000;
        mService.mConstants.API_QUOTA_SCHEDULE_THROW_EXCEPTION = false;
        mService.mConstants.API_QUOTA_SCHEDULE_RETURN_FAILURE_RESULT = true;
        mService.updateQuotaTracker();
        mService.resetScheduleQuota();

        final JobInfo job = createJobInfo().setPersisted(true).build();
        for (int i = 0; i < 500; ++i) {
            assertEquals("Got unexpected result for schedule #" + (i + 1),
                    JobScheduler.RESULT_SUCCESS,
                    mService.scheduleAsPackage(job, null, TEST_UID, "proxied.package", 0, "JSSTest",
                            ""));
        }
    }

    /**
     * Tests that jobs scheduled by an app for itself as if through a proxy are counted towards
     * scheduling limits.
     */
    @Test
    public void testScheduleLimiting_SelfProxy() {
        mService.mConstants.ENABLE_API_QUOTAS = true;
        mService.mConstants.API_QUOTA_SCHEDULE_COUNT = 300;
        mService.mConstants.API_QUOTA_SCHEDULE_WINDOW_MS = 300000;
        mService.mConstants.API_QUOTA_SCHEDULE_THROW_EXCEPTION = false;
        mService.mConstants.API_QUOTA_SCHEDULE_RETURN_FAILURE_RESULT = true;
        mService.updateQuotaTracker();
        mService.resetScheduleQuota();

        final JobInfo job = createJobInfo().setPersisted(true).build();
        for (int i = 0; i < 500; ++i) {
            final int expected =
                    i < 300 ? JobScheduler.RESULT_SUCCESS : JobScheduler.RESULT_FAILURE;
            assertEquals("Got unexpected result for schedule #" + (i + 1),
                    expected,
                    mService.scheduleAsPackage(job, null, TEST_UID,
                            job.getService().getPackageName(),
                            0, "JSSTest", ""));
        }
    }

    /**
     * Tests that the number of persisted JobWorkItems is capped.
     */
    @Test
    public void testScheduleLimiting_JobWorkItems_Nonpersisted() {
        mService.mConstants.MAX_NUM_PERSISTED_JOB_WORK_ITEMS = 500;
        mService.mConstants.ENABLE_API_QUOTAS = false;
        mService.mConstants.API_QUOTA_SCHEDULE_THROW_EXCEPTION = false;
        mService.mConstants.API_QUOTA_SCHEDULE_RETURN_FAILURE_RESULT = false;
        mService.updateQuotaTracker();
        mService.resetScheduleQuota();

        final JobInfo job = createJobInfo().setPersisted(false).build();
        final JobWorkItem item = new JobWorkItem.Builder().build();
        for (int i = 0; i < 1000; ++i) {
            assertEquals("Got unexpected result for schedule #" + (i + 1),
                    JobScheduler.RESULT_SUCCESS,
                    mService.scheduleAsPackage(job, item, TEST_UID,
                            job.getService().getPackageName(),
                            0, "JSSTest", ""));
        }
    }

    /**
     * Tests that the number of persisted JobWorkItems is capped.
     */
    @Test
    public void testScheduleLimiting_JobWorkItems_Persisted() {
        mService.mConstants.MAX_NUM_PERSISTED_JOB_WORK_ITEMS = 500;
        mService.mConstants.ENABLE_API_QUOTAS = false;
        mService.mConstants.API_QUOTA_SCHEDULE_THROW_EXCEPTION = false;
        mService.mConstants.API_QUOTA_SCHEDULE_RETURN_FAILURE_RESULT = false;
        mService.updateQuotaTracker();
        mService.resetScheduleQuota();

        final JobInfo job = createJobInfo().setPersisted(true).build();
        final JobWorkItem item = new JobWorkItem.Builder().build();
        for (int i = 0; i < 500; ++i) {
            assertEquals("Got unexpected result for schedule #" + (i + 1),
                    JobScheduler.RESULT_SUCCESS,
                    mService.scheduleAsPackage(job, item, TEST_UID,
                            job.getService().getPackageName(),
                            0, "JSSTest", ""));
        }
        try {
            mService.scheduleAsPackage(job, item, TEST_UID, job.getService().getPackageName(),
                    0, "JSSTest", "");
            fail("Added more items than allowed");
        } catch (IllegalStateException expected) {
            // Success
        }
    }

    /** Tests that jobs are removed from the pending list if the user stops the app. */
    @Test
    public void testUserStopRemovesPending() {
        spyOn(mService);

        JobStatus job1a = createJobStatus("testUserStopRemovesPending",
                createJobInfo(1), 1, "pkg1");
        JobStatus job1b = createJobStatus("testUserStopRemovesPending",
                createJobInfo(2), 1, "pkg1");
        JobStatus job2a = createJobStatus("testUserStopRemovesPending",
                createJobInfo(1), 2, "pkg2");
        JobStatus job2b = createJobStatus("testUserStopRemovesPending",
                createJobInfo(2), 2, "pkg2");
        doReturn(1).when(mPackageManagerInternal).getPackageUid("pkg1", 0, 0);
        doReturn(11).when(mPackageManagerInternal).getPackageUid("pkg1", 0, 1);
        doReturn(2).when(mPackageManagerInternal).getPackageUid("pkg2", 0, 0);

        mService.getPendingJobQueue().clear();
        mService.getPendingJobQueue().add(job1a);
        mService.getPendingJobQueue().add(job1b);
        mService.getPendingJobQueue().add(job2a);
        mService.getPendingJobQueue().add(job2b);
        mService.getJobStore().add(job1a);
        mService.getJobStore().add(job1b);
        mService.getJobStore().add(job2a);
        mService.getJobStore().add(job2b);

        mService.notePendingUserRequestedAppStopInternal("pkg1", 1, "test");
        assertEquals(4, mService.getPendingJobQueue().size());
        assertTrue(mService.getPendingJobQueue().contains(job1a));
        assertTrue(mService.getPendingJobQueue().contains(job1b));
        assertTrue(mService.getPendingJobQueue().contains(job2a));
        assertTrue(mService.getPendingJobQueue().contains(job2b));

        mService.notePendingUserRequestedAppStopInternal("pkg1", 0, "test");
        assertEquals(2, mService.getPendingJobQueue().size());
        assertFalse(mService.getPendingJobQueue().contains(job1a));
        assertEquals(JobScheduler.PENDING_JOB_REASON_USER, mService.getPendingJobReason(job1a));
        assertFalse(mService.getPendingJobQueue().contains(job1b));
        assertEquals(JobScheduler.PENDING_JOB_REASON_USER, mService.getPendingJobReason(job1b));
        assertTrue(mService.getPendingJobQueue().contains(job2a));
        assertTrue(mService.getPendingJobQueue().contains(job2b));

        mService.notePendingUserRequestedAppStopInternal("pkg2", 0, "test");
        assertEquals(0, mService.getPendingJobQueue().size());
        assertFalse(mService.getPendingJobQueue().contains(job1a));
        assertFalse(mService.getPendingJobQueue().contains(job1b));
        assertFalse(mService.getPendingJobQueue().contains(job2a));
        assertEquals(JobScheduler.PENDING_JOB_REASON_USER, mService.getPendingJobReason(job2a));
        assertFalse(mService.getPendingJobQueue().contains(job2b));
        assertEquals(JobScheduler.PENDING_JOB_REASON_USER, mService.getPendingJobReason(job2b));
    }
}

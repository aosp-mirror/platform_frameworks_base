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
 * limitations under the License.
 */

package com.android.server.job.controllers;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.inOrder;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;
import static com.android.server.job.JobSchedulerService.ACTIVE_INDEX;
import static com.android.server.job.JobSchedulerService.EXEMPTED_INDEX;
import static com.android.server.job.JobSchedulerService.FREQUENT_INDEX;
import static com.android.server.job.JobSchedulerService.NEVER_INDEX;
import static com.android.server.job.JobSchedulerService.RARE_INDEX;
import static com.android.server.job.JobSchedulerService.RESTRICTED_INDEX;
import static com.android.server.job.JobSchedulerService.WORKING_INDEX;
import static com.android.server.job.JobSchedulerService.sElapsedRealtimeClock;
import static com.android.server.job.JobSchedulerService.sSystemClock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.Manifest;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AlarmManager;
import android.app.AppGlobals;
import android.app.IActivityManager;
import android.app.IUidObserver;
import android.app.job.JobInfo;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.app.usage.UsageStatsManagerInternal;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ServiceInfo;
import android.os.BatteryManagerInternal;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.platform.test.annotations.LargeTest;
import android.provider.DeviceConfig;
import android.util.ArraySet;
import android.util.SparseBooleanArray;

import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.ArrayUtils;
import com.android.server.LocalServices;
import com.android.server.PowerAllowlistInternal;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.JobStore;
import com.android.server.job.controllers.QuotaController.ExecutionStats;
import com.android.server.job.controllers.QuotaController.QcConstants;
import com.android.server.job.controllers.QuotaController.ShrinkableDebits;
import com.android.server.job.controllers.QuotaController.TimingSession;
import com.android.server.usage.AppStandbyInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

@RunWith(AndroidJUnit4.class)
public class QuotaControllerTest {
    private static final long SECOND_IN_MILLIS = 1000L;
    private static final long MINUTE_IN_MILLIS = 60 * SECOND_IN_MILLIS;
    private static final long HOUR_IN_MILLIS = 60 * MINUTE_IN_MILLIS;
    private static final String TAG_CLEANUP = "*job.cleanup*";
    private static final String TAG_QUOTA_CHECK = "*job.quota_check*";
    private static final int CALLING_UID = 1000;
    private static final String SOURCE_PACKAGE = "com.android.frameworks.mockingservicestests";
    private static final int SOURCE_USER_ID = 0;

    private QuotaController mQuotaController;
    private QuotaController.QcConstants mQcConstants;
    private JobSchedulerService.Constants mConstants = new JobSchedulerService.Constants();
    private int mSourceUid;
    private PowerAllowlistInternal.TempAllowlistChangeListener mTempAllowlistListener;
    private IUidObserver mUidObserver;
    private UsageStatsManagerInternal.UsageEventListener mUsageEventListener;
    DeviceConfig.Properties.Builder mDeviceConfigPropertiesBuilder;

    private MockitoSession mMockingSession;
    @Mock
    private ActivityManagerInternal mActivityMangerInternal;
    @Mock
    private AlarmManager mAlarmManager;
    @Mock
    private Context mContext;
    @Mock
    private JobSchedulerService mJobSchedulerService;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private PackageManagerInternal mPackageManagerInternal;
    @Mock
    private PowerAllowlistInternal mPowerAllowlistInternal;
    @Mock
    private UsageStatsManagerInternal mUsageStatsManager;

    private JobStore mJobStore;

    @Before
    public void setUp() {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .spyStatic(DeviceConfig.class)
                .mockStatic(LocalServices.class)
                .startMocking();

        // Called in StateController constructor.
        when(mJobSchedulerService.getTestableContext()).thenReturn(mContext);
        when(mJobSchedulerService.getLock()).thenReturn(mJobSchedulerService);
        when(mJobSchedulerService.getConstants()).thenReturn(mConstants);
        // Called in QuotaController constructor.
        IActivityManager activityManager = ActivityManager.getService();
        spyOn(activityManager);
        try {
            doNothing().when(activityManager).registerUidObserver(any(), anyInt(), anyInt(), any());
        } catch (RemoteException e) {
            fail("registerUidObserver threw exception: " + e.getMessage());
        }
        when(mContext.getMainLooper()).thenReturn(Looper.getMainLooper());
        when(mContext.getSystemService(AlarmManager.class)).thenReturn(mAlarmManager);
        doReturn(mActivityMangerInternal)
                .when(() -> LocalServices.getService(ActivityManagerInternal.class));
        doReturn(mock(AppStandbyInternal.class))
                .when(() -> LocalServices.getService(AppStandbyInternal.class));
        doReturn(mock(BatteryManagerInternal.class))
                .when(() -> LocalServices.getService(BatteryManagerInternal.class));
        doReturn(mUsageStatsManager)
                .when(() -> LocalServices.getService(UsageStatsManagerInternal.class));
        doReturn(mPowerAllowlistInternal)
                .when(() -> LocalServices.getService(PowerAllowlistInternal.class));
        // Used in JobStatus.
        doReturn(mPackageManagerInternal)
                .when(() -> LocalServices.getService(PackageManagerInternal.class));
        // Used in QuotaController.Handler.
        mJobStore = JobStore.initAndGetForTesting(mContext, mContext.getFilesDir());
        when(mJobSchedulerService.getJobStore()).thenReturn(mJobStore);
        // Used in QuotaController.QcConstants
        doAnswer((Answer<Void>) invocationOnMock -> null)
                .when(() -> DeviceConfig.addOnPropertiesChangedListener(
                        anyString(), any(Executor.class),
                        any(DeviceConfig.OnPropertiesChangedListener.class)));
        mDeviceConfigPropertiesBuilder =
                new DeviceConfig.Properties.Builder(DeviceConfig.NAMESPACE_JOB_SCHEDULER);
        doAnswer(
                (Answer<DeviceConfig.Properties>) invocationOnMock
                        -> mDeviceConfigPropertiesBuilder.build())
                .when(() -> DeviceConfig.getProperties(
                        eq(DeviceConfig.NAMESPACE_JOB_SCHEDULER), ArgumentMatchers.<String>any()));
        // Used in QuotaController.onSystemServicesReady
        when(mContext.getPackageManager()).thenReturn(mPackageManager);

        // Freeze the clocks at 24 hours after this moment in time. Several tests create sessions
        // in the past, and QuotaController sometimes floors values at 0, so if the test time
        // causes sessions with negative timestamps, they will fail.
        JobSchedulerService.sSystemClock =
                getAdvancedClock(Clock.fixed(Clock.systemUTC().instant(), ZoneOffset.UTC),
                        24 * HOUR_IN_MILLIS);
        JobSchedulerService.sUptimeMillisClock = getAdvancedClock(
                Clock.fixed(SystemClock.uptimeClock().instant(), ZoneOffset.UTC),
                24 * HOUR_IN_MILLIS);
        JobSchedulerService.sElapsedRealtimeClock = getAdvancedClock(
                Clock.fixed(SystemClock.elapsedRealtimeClock().instant(), ZoneOffset.UTC),
                24 * HOUR_IN_MILLIS);

        // Initialize real objects.
        // Capture the listeners.
        ArgumentCaptor<IUidObserver> uidObserverCaptor =
                ArgumentCaptor.forClass(IUidObserver.class);
        ArgumentCaptor<PowerAllowlistInternal.TempAllowlistChangeListener> taChangeCaptor =
                ArgumentCaptor.forClass(PowerAllowlistInternal.TempAllowlistChangeListener.class);
        ArgumentCaptor<UsageStatsManagerInternal.UsageEventListener> ueListenerCaptor =
                ArgumentCaptor.forClass(UsageStatsManagerInternal.UsageEventListener.class);
        mQuotaController = new QuotaController(mJobSchedulerService,
                mock(BackgroundJobsController.class), mock(ConnectivityController.class));

        verify(mPowerAllowlistInternal)
                .registerTempAllowlistChangeListener(taChangeCaptor.capture());
        mTempAllowlistListener = taChangeCaptor.getValue();
        verify(mUsageStatsManager).registerListener(ueListenerCaptor.capture());
        mUsageEventListener = ueListenerCaptor.getValue();
        try {
            verify(activityManager).registerUidObserver(
                    uidObserverCaptor.capture(),
                    eq(ActivityManager.UID_OBSERVER_PROCSTATE),
                    eq(ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE),
                    any());
            mUidObserver = uidObserverCaptor.getValue();
            mSourceUid = AppGlobals.getPackageManager().getPackageUid(SOURCE_PACKAGE, 0, 0);
            // Need to do this since we're using a mock JS and not a real object.
            doReturn(new ArraySet<>(new String[]{SOURCE_PACKAGE}))
                    .when(mJobSchedulerService).getPackagesForUidLocked(mSourceUid);
        } catch (RemoteException e) {
            fail(e.getMessage());
        }
        mQcConstants = mQuotaController.getQcConstants();
    }

    @After
    public void tearDown() {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    private Clock getAdvancedClock(Clock clock, long incrementMs) {
        return Clock.offset(clock, Duration.ofMillis(incrementMs));
    }

    private void advanceElapsedClock(long incrementMs) {
        JobSchedulerService.sElapsedRealtimeClock = getAdvancedClock(
                JobSchedulerService.sElapsedRealtimeClock, incrementMs);
    }

    private void setCharging() {
        doReturn(true).when(mJobSchedulerService).isBatteryCharging();
        synchronized (mQuotaController.mLock) {
            mQuotaController.onBatteryStateChangedLocked();
        }
    }

    private void setDischarging() {
        doReturn(false).when(mJobSchedulerService).isBatteryCharging();
        synchronized (mQuotaController.mLock) {
            mQuotaController.onBatteryStateChangedLocked();
        }
    }

    private void setProcessState(int procState) {
        setProcessState(procState, mSourceUid);
    }

    private void setProcessState(int procState, int uid) {
        try {
            doReturn(procState).when(mActivityMangerInternal).getUidProcessState(uid);
            SparseBooleanArray foregroundUids = mQuotaController.getForegroundUids();
            spyOn(foregroundUids);
            final boolean contained = foregroundUids.get(uid);
            mUidObserver.onUidStateChanged(uid, procState, 0,
                    ActivityManager.PROCESS_CAPABILITY_NONE);
            if (procState <= ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE) {
                if (!contained) {
                    verify(foregroundUids, timeout(2 * SECOND_IN_MILLIS).times(1))
                            .put(eq(uid), eq(true));
                }
                assertTrue(foregroundUids.get(uid));
            } else {
                if (contained) {
                    verify(foregroundUids, timeout(2 * SECOND_IN_MILLIS).times(1))
                            .delete(eq(uid));
                }
                assertFalse(foregroundUids.get(uid));
            }
            waitForNonDelayedMessagesProcessed();
        } catch (Exception e) {
            fail("exception encountered: " + e.getMessage());
        }
    }

    private int bucketIndexToUsageStatsBucket(int bucketIndex) {
        switch (bucketIndex) {
            case EXEMPTED_INDEX:
                return UsageStatsManager.STANDBY_BUCKET_EXEMPTED;
            case ACTIVE_INDEX:
                return UsageStatsManager.STANDBY_BUCKET_ACTIVE;
            case WORKING_INDEX:
                return UsageStatsManager.STANDBY_BUCKET_WORKING_SET;
            case FREQUENT_INDEX:
                return UsageStatsManager.STANDBY_BUCKET_FREQUENT;
            case RARE_INDEX:
                return UsageStatsManager.STANDBY_BUCKET_RARE;
            case RESTRICTED_INDEX:
                return UsageStatsManager.STANDBY_BUCKET_RESTRICTED;
            default:
                return UsageStatsManager.STANDBY_BUCKET_NEVER;
        }
    }

    private void setStandbyBucket(int bucketIndex) {
        when(mUsageStatsManager.getAppStandbyBucket(eq(SOURCE_PACKAGE), eq(SOURCE_USER_ID),
                anyLong())).thenReturn(bucketIndexToUsageStatsBucket(bucketIndex));
        mQuotaController.updateStandbyBucket(SOURCE_USER_ID, SOURCE_PACKAGE, bucketIndex);
    }

    private void setStandbyBucket(int bucketIndex, JobStatus... jobs) {
        setStandbyBucket(bucketIndex);
        for (JobStatus job : jobs) {
            job.setStandbyBucket(bucketIndex);
            when(mUsageStatsManager.getAppStandbyBucket(
                    eq(job.getSourcePackageName()), eq(job.getSourceUserId()), anyLong()))
                    .thenReturn(bucketIndexToUsageStatsBucket(bucketIndex));
        }
    }

    private void trackJobs(JobStatus... jobs) {
        for (JobStatus job : jobs) {
            mJobStore.add(job);
            synchronized (mQuotaController.mLock) {
                mQuotaController.maybeStartTrackingJobLocked(job, null);
            }
        }
    }

    private JobStatus createJobStatus(String testTag, int jobId) {
        JobInfo jobInfo = new JobInfo.Builder(jobId,
                new ComponentName(mContext, "TestQuotaJobService"))
                .build();
        return createJobStatus(testTag, SOURCE_PACKAGE, CALLING_UID, jobInfo);
    }

    private JobStatus createExpeditedJobStatus(String testTag, int jobId) {
        JobInfo jobInfo = new JobInfo.Builder(jobId,
                new ComponentName(mContext, "TestQuotaExpeditedJobService"))
                .setExpedited(true)
                .build();
        return createJobStatus(testTag, SOURCE_PACKAGE, CALLING_UID, jobInfo);
    }

    private JobStatus createJobStatus(String testTag, String packageName, int callingUid,
            JobInfo jobInfo) {
        JobStatus js = JobStatus.createFromJobInfo(
                jobInfo, callingUid, packageName, SOURCE_USER_ID, testTag);
        js.serviceInfo = mock(ServiceInfo.class);
        // Make sure tests aren't passing just because the default bucket is likely ACTIVE.
        js.setStandbyBucket(FREQUENT_INDEX);
        // Make sure Doze and background-not-restricted don't affect tests.
        js.setDeviceNotDozingConstraintSatisfied(/* nowElapsed */ sElapsedRealtimeClock.millis(),
                /* state */ true, /* allowlisted */false);
        js.setBackgroundNotRestrictedConstraintSatisfied(
                sElapsedRealtimeClock.millis(), true, false);
        js.setTareWealthConstraintSatisfied(sElapsedRealtimeClock.millis(), true);
        js.setExpeditedJobTareApproved(sElapsedRealtimeClock.millis(), true);
        return js;
    }

    private TimingSession createTimingSession(long start, long duration, int count) {
        return new TimingSession(start, start + duration, count);
    }

    private void setDeviceConfigLong(String key, long val) {
        mDeviceConfigPropertiesBuilder.setLong(key, val);
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForUpdatedConstantsLocked();
            mQcConstants.processConstantLocked(mDeviceConfigPropertiesBuilder.build(), key);
        }
    }

    private void setDeviceConfigInt(String key, int val) {
        mDeviceConfigPropertiesBuilder.setInt(key, val);
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForUpdatedConstantsLocked();
            mQcConstants.processConstantLocked(mDeviceConfigPropertiesBuilder.build(), key);
        }
    }

    private void waitForNonDelayedMessagesProcessed() {
        mQuotaController.getHandler().runWithScissors(() -> {}, 15_000);
    }

    @Test
    public void testSaveTimingSession() {
        assertNull(mQuotaController.getTimingSessions(0, "com.android.test"));

        List<TimingSession> expectedRegular = new ArrayList<>();
        List<TimingSession> expectedEJ = new ArrayList<>();
        TimingSession one = new TimingSession(1, 10, 1);
        TimingSession two = new TimingSession(11, 20, 2);
        TimingSession thr = new TimingSession(21, 30, 3);
        TimingSession fou = new TimingSession(31, 40, 4);

        mQuotaController.saveTimingSession(0, "com.android.test", one, false);
        expectedRegular.add(one);
        assertEquals(expectedRegular, mQuotaController.getTimingSessions(0, "com.android.test"));
        assertTrue(
                ArrayUtils.isEmpty(mQuotaController.getEJTimingSessions(0, "com.android.test")));

        mQuotaController.saveTimingSession(0, "com.android.test", two, false);
        expectedRegular.add(two);
        assertEquals(expectedRegular, mQuotaController.getTimingSessions(0, "com.android.test"));
        assertTrue(
                ArrayUtils.isEmpty(mQuotaController.getEJTimingSessions(0, "com.android.test")));

        mQuotaController.saveTimingSession(0, "com.android.test", thr, true);
        expectedEJ.add(thr);
        assertEquals(expectedRegular, mQuotaController.getTimingSessions(0, "com.android.test"));
        assertEquals(expectedEJ, mQuotaController.getEJTimingSessions(0, "com.android.test"));

        mQuotaController.saveTimingSession(0, "com.android.test", fou, false);
        mQuotaController.saveTimingSession(0, "com.android.test", fou, true);
        expectedRegular.add(fou);
        expectedEJ.add(fou);
        assertEquals(expectedRegular, mQuotaController.getTimingSessions(0, "com.android.test"));
        assertEquals(expectedEJ, mQuotaController.getEJTimingSessions(0, "com.android.test"));
    }

    @Test
    public void testDeleteObsoleteSessionsLocked() {
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        TimingSession one = createTimingSession(
                now - 10 * MINUTE_IN_MILLIS, 9 * MINUTE_IN_MILLIS, 3);
        TimingSession two = createTimingSession(
                now - (70 * MINUTE_IN_MILLIS), 9 * MINUTE_IN_MILLIS, 1);
        TimingSession thr = createTimingSession(
                now - (3 * HOUR_IN_MILLIS + 10 * MINUTE_IN_MILLIS), 9 * MINUTE_IN_MILLIS, 1);
        // Overlaps 24 hour boundary.
        TimingSession fou = createTimingSession(
                now - (24 * HOUR_IN_MILLIS + 2 * MINUTE_IN_MILLIS), 7 * MINUTE_IN_MILLIS, 1);
        // Way past the 24 hour boundary.
        TimingSession fiv = createTimingSession(
                now - (25 * HOUR_IN_MILLIS), 5 * MINUTE_IN_MILLIS, 4);
        List<TimingSession> expectedRegular = new ArrayList<>();
        List<TimingSession> expectedEJ = new ArrayList<>();
        // Added in correct (chronological) order.
        expectedRegular.add(fou);
        expectedRegular.add(thr);
        expectedRegular.add(two);
        expectedRegular.add(one);
        expectedEJ.add(fou);
        expectedEJ.add(one);
        mQuotaController.saveTimingSession(0, "com.android.test", fiv, false);
        mQuotaController.saveTimingSession(0, "com.android.test", fou, false);
        mQuotaController.saveTimingSession(0, "com.android.test", thr, false);
        mQuotaController.saveTimingSession(0, "com.android.test", two, false);
        mQuotaController.saveTimingSession(0, "com.android.test", one, false);
        mQuotaController.saveTimingSession(0, "com.android.test", fiv, true);
        mQuotaController.saveTimingSession(0, "com.android.test", fou, true);
        mQuotaController.saveTimingSession(0, "com.android.test", one, true);

        synchronized (mQuotaController.mLock) {
            mQuotaController.deleteObsoleteSessionsLocked();
        }

        assertEquals(expectedRegular, mQuotaController.getTimingSessions(0, "com.android.test"));
        assertEquals(expectedEJ, mQuotaController.getEJTimingSessions(0, "com.android.test"));
    }

    @Test
    public void testOnAppRemovedLocked() {
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.saveTimingSession(0, "com.android.test.remove",
                createTimingSession(now - (6 * HOUR_IN_MILLIS), 10 * MINUTE_IN_MILLIS, 5), false);
        mQuotaController.saveTimingSession(0, "com.android.test.remove",
                createTimingSession(
                        now - (2 * HOUR_IN_MILLIS + MINUTE_IN_MILLIS), 6 * MINUTE_IN_MILLIS, 5),
                false);
        mQuotaController.saveTimingSession(0, "com.android.test.remove",
                createTimingSession(now - (HOUR_IN_MILLIS), MINUTE_IN_MILLIS, 1), false);
        mQuotaController.saveTimingSession(0, "com.android.test.remove",
                createTimingSession(now - (30 * MINUTE_IN_MILLIS), MINUTE_IN_MILLIS, 1), true);
        mQuotaController.saveTimingSession(0, "com.android.test.remove",
                createTimingSession(now - (15 * MINUTE_IN_MILLIS), MINUTE_IN_MILLIS, 1), true);
        // Test that another app isn't affected.
        TimingSession one = createTimingSession(
                now - 10 * MINUTE_IN_MILLIS, 9 * MINUTE_IN_MILLIS, 3);
        TimingSession two = createTimingSession(
                now - (70 * MINUTE_IN_MILLIS), 9 * MINUTE_IN_MILLIS, 1);
        List<TimingSession> expected = new ArrayList<>();
        // Added in correct (chronological) order.
        expected.add(two);
        expected.add(one);
        mQuotaController.saveTimingSession(0, "com.android.test.stay", two, false);
        mQuotaController.saveTimingSession(0, "com.android.test.stay", one, false);
        mQuotaController.saveTimingSession(0, "com.android.test.stay", one, true);

        assertNotNull(mQuotaController.getTimingSessions(0, "com.android.test.remove"));
        assertNotNull(mQuotaController.getEJTimingSessions(0, "com.android.test.remove"));
        assertNotNull(mQuotaController.getTimingSessions(0, "com.android.test.stay"));
        assertNotNull(mQuotaController.getEJTimingSessions(0, "com.android.test.stay"));

        ExecutionStats expectedStats = new ExecutionStats();
        expectedStats.expirationTimeElapsed = now + 24 * HOUR_IN_MILLIS;
        expectedStats.allowedTimePerPeriodMs = 10 * MINUTE_IN_MILLIS;
        expectedStats.windowSizeMs = 24 * HOUR_IN_MILLIS;
        expectedStats.jobCountLimit = mQcConstants.MAX_JOB_COUNT_RARE;
        expectedStats.sessionCountLimit = mQcConstants.MAX_SESSION_COUNT_RARE;

        final int uid = 10001;
        synchronized (mQuotaController.mLock) {
            mQuotaController.onAppRemovedLocked("com.android.test.remove", uid);
        }
        assertNull(mQuotaController.getTimingSessions(0, "com.android.test.remove"));
        assertNull(mQuotaController.getEJTimingSessions(0, "com.android.test.remove"));
        assertEquals(expected, mQuotaController.getTimingSessions(0, "com.android.test.stay"));
        synchronized (mQuotaController.mLock) {
            assertEquals(expectedStats,
                    mQuotaController.getExecutionStatsLocked(
                            0, "com.android.test.remove", RARE_INDEX));
            assertNotEquals(expectedStats,
                    mQuotaController.getExecutionStatsLocked(
                            0, "com.android.test.stay", RARE_INDEX));

            assertFalse(mQuotaController.getForegroundUids().get(uid));
        }
    }

    @Test
    public void testOnUserRemovedLocked() {
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - (6 * HOUR_IN_MILLIS), 10 * MINUTE_IN_MILLIS, 5), false);
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(
                        now - (2 * HOUR_IN_MILLIS + MINUTE_IN_MILLIS), 6 * MINUTE_IN_MILLIS, 5),
                false);
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - (HOUR_IN_MILLIS), MINUTE_IN_MILLIS, 1), false);
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - (30 * MINUTE_IN_MILLIS), MINUTE_IN_MILLIS, 1), true);
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - (15 * MINUTE_IN_MILLIS), MINUTE_IN_MILLIS, 1), true);
        // Test that another user isn't affected.
        TimingSession one = createTimingSession(
                now - 10 * MINUTE_IN_MILLIS, 9 * MINUTE_IN_MILLIS, 3);
        TimingSession two = createTimingSession(
                now - (70 * MINUTE_IN_MILLIS), 9 * MINUTE_IN_MILLIS, 1);
        List<TimingSession> expectedRegular = new ArrayList<>();
        List<TimingSession> expectedEJ = new ArrayList<>();
        // Added in correct (chronological) order.
        expectedRegular.add(two);
        expectedRegular.add(one);
        expectedEJ.add(one);
        mQuotaController.saveTimingSession(10, "com.android.test", two, false);
        mQuotaController.saveTimingSession(10, "com.android.test", one, false);
        mQuotaController.saveTimingSession(10, "com.android.test", one, true);

        assertNotNull(mQuotaController.getTimingSessions(0, "com.android.test"));
        assertNotNull(mQuotaController.getEJTimingSessions(0, "com.android.test"));
        assertNotNull(mQuotaController.getTimingSessions(10, "com.android.test"));
        assertNotNull(mQuotaController.getEJTimingSessions(10, "com.android.test"));

        ExecutionStats expectedStats = new ExecutionStats();
        expectedStats.allowedTimePerPeriodMs = 10 * MINUTE_IN_MILLIS;
        expectedStats.expirationTimeElapsed = now + 24 * HOUR_IN_MILLIS;
        expectedStats.windowSizeMs = 24 * HOUR_IN_MILLIS;
        expectedStats.jobCountLimit = mQcConstants.MAX_JOB_COUNT_RARE;
        expectedStats.sessionCountLimit = mQcConstants.MAX_SESSION_COUNT_RARE;

        synchronized (mQuotaController.mLock) {
            mQuotaController.onUserRemovedLocked(0);
            assertNull(mQuotaController.getTimingSessions(0, "com.android.test"));
            assertNull(mQuotaController.getEJTimingSessions(0, "com.android.test"));
            assertEquals(expectedRegular,
                    mQuotaController.getTimingSessions(10, "com.android.test"));
            assertEquals(expectedEJ,
                    mQuotaController.getEJTimingSessions(10, "com.android.test"));
            assertEquals(expectedStats,
                    mQuotaController.getExecutionStatsLocked(0, "com.android.test", RARE_INDEX));
            assertNotEquals(expectedStats,
                    mQuotaController.getExecutionStatsLocked(10, "com.android.test", RARE_INDEX));
        }
    }

    @Test
    public void testUpdateExecutionStatsLocked_NoTimer() {
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        // Added in chronological order.
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - (6 * HOUR_IN_MILLIS), 10 * MINUTE_IN_MILLIS, 5), false);
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(
                        now - (2 * HOUR_IN_MILLIS + MINUTE_IN_MILLIS), 6 * MINUTE_IN_MILLIS, 5),
                false);
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - (HOUR_IN_MILLIS), MINUTE_IN_MILLIS, 1), false);
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(
                        now - (HOUR_IN_MILLIS - 10 * MINUTE_IN_MILLIS), MINUTE_IN_MILLIS, 1),
                false);
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - 5 * MINUTE_IN_MILLIS, 4 * MINUTE_IN_MILLIS, 3), false);

        // Test an app that hasn't had any activity.
        ExecutionStats expectedStats = new ExecutionStats();
        ExecutionStats inputStats = new ExecutionStats();

        inputStats.allowedTimePerPeriodMs = 10 * MINUTE_IN_MILLIS;
        inputStats.windowSizeMs = expectedStats.windowSizeMs = 12 * HOUR_IN_MILLIS;
        inputStats.jobCountLimit = expectedStats.jobCountLimit = 100;
        inputStats.sessionCountLimit = expectedStats.sessionCountLimit = 100;
        // Invalid time is now +24 hours since there are no sessions at all for the app.
        expectedStats.expirationTimeElapsed = now + 24 * HOUR_IN_MILLIS;
        expectedStats.allowedTimePerPeriodMs = 10 * MINUTE_IN_MILLIS;
        synchronized (mQuotaController.mLock) {
            mQuotaController.updateExecutionStatsLocked(0, "com.android.test.not.run", inputStats);
        }
        assertEquals(expectedStats, inputStats);

        inputStats.windowSizeMs = expectedStats.windowSizeMs = MINUTE_IN_MILLIS;
        // Invalid time is now +18 hours since there are no sessions in the window but the earliest
        // session is 6 hours ago.
        expectedStats.expirationTimeElapsed = now + 18 * HOUR_IN_MILLIS;
        expectedStats.executionTimeInWindowMs = 0;
        expectedStats.bgJobCountInWindow = 0;
        expectedStats.executionTimeInMaxPeriodMs = 22 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInMaxPeriod = 15;
        expectedStats.sessionCountInWindow = 0;
        synchronized (mQuotaController.mLock) {
            mQuotaController.updateExecutionStatsLocked(0, "com.android.test", inputStats);
        }
        assertEquals(expectedStats, inputStats);

        inputStats.windowSizeMs = expectedStats.windowSizeMs = 3 * MINUTE_IN_MILLIS;
        // Invalid time is now since the session straddles the window cutoff time.
        expectedStats.expirationTimeElapsed = now;
        expectedStats.executionTimeInWindowMs = 2 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInWindow = 3;
        expectedStats.executionTimeInMaxPeriodMs = 22 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInMaxPeriod = 15;
        expectedStats.sessionCountInWindow = 1;
        synchronized (mQuotaController.mLock) {
            mQuotaController.updateExecutionStatsLocked(0, "com.android.test", inputStats);
        }
        assertEquals(expectedStats, inputStats);

        inputStats.windowSizeMs = expectedStats.windowSizeMs = 5 * MINUTE_IN_MILLIS;
        // Invalid time is now since the start of the session is at the very edge of the window
        // cutoff time.
        expectedStats.expirationTimeElapsed = now;
        expectedStats.executionTimeInWindowMs = 4 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInWindow = 3;
        expectedStats.executionTimeInMaxPeriodMs = 22 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInMaxPeriod = 15;
        expectedStats.sessionCountInWindow = 1;
        synchronized (mQuotaController.mLock) {
            mQuotaController.updateExecutionStatsLocked(0, "com.android.test", inputStats);
        }
        assertEquals(expectedStats, inputStats);

        inputStats.windowSizeMs = expectedStats.windowSizeMs = 49 * MINUTE_IN_MILLIS;
        // Invalid time is now +44 minutes since the earliest session in the window is now-5
        // minutes.
        expectedStats.expirationTimeElapsed = now + 44 * MINUTE_IN_MILLIS;
        expectedStats.executionTimeInWindowMs = 4 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInWindow = 3;
        expectedStats.executionTimeInMaxPeriodMs = 22 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInMaxPeriod = 15;
        expectedStats.sessionCountInWindow = 1;
        synchronized (mQuotaController.mLock) {
            mQuotaController.updateExecutionStatsLocked(0, "com.android.test", inputStats);
        }
        assertEquals(expectedStats, inputStats);

        inputStats.windowSizeMs = expectedStats.windowSizeMs = 50 * MINUTE_IN_MILLIS;
        // Invalid time is now since the session is at the very edge of the window cutoff time.
        expectedStats.expirationTimeElapsed = now;
        expectedStats.executionTimeInWindowMs = 5 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInWindow = 4;
        expectedStats.executionTimeInMaxPeriodMs = 22 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInMaxPeriod = 15;
        expectedStats.sessionCountInWindow = 2;
        synchronized (mQuotaController.mLock) {
            mQuotaController.updateExecutionStatsLocked(0, "com.android.test", inputStats);
        }
        assertEquals(expectedStats, inputStats);

        inputStats.windowSizeMs = expectedStats.windowSizeMs = HOUR_IN_MILLIS;
        inputStats.sessionCountLimit = expectedStats.sessionCountLimit = 2;
        // Invalid time is now since the start of the session is at the very edge of the window
        // cutoff time.
        expectedStats.expirationTimeElapsed = now;
        expectedStats.executionTimeInWindowMs = 6 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInWindow = 5;
        expectedStats.executionTimeInMaxPeriodMs = 22 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInMaxPeriod = 15;
        expectedStats.sessionCountInWindow = 3;
        expectedStats.inQuotaTimeElapsed = now + 11 * MINUTE_IN_MILLIS;
        synchronized (mQuotaController.mLock) {
            mQuotaController.updateExecutionStatsLocked(0, "com.android.test", inputStats);
        }
        assertEquals(expectedStats, inputStats);

        inputStats.windowSizeMs = expectedStats.windowSizeMs = 2 * HOUR_IN_MILLIS;
        inputStats.jobCountLimit = expectedStats.jobCountLimit = 6;
        inputStats.sessionCountLimit = expectedStats.sessionCountLimit = 100;
        // Invalid time is now since the session straddles the window cutoff time.
        expectedStats.expirationTimeElapsed = now;
        expectedStats.executionTimeInWindowMs = 11 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInWindow = 10;
        expectedStats.executionTimeInMaxPeriodMs = 22 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInMaxPeriod = 15;
        expectedStats.sessionCountInWindow = 4;
        expectedStats.inQuotaTimeElapsed = now + 5 * MINUTE_IN_MILLIS;
        synchronized (mQuotaController.mLock) {
            mQuotaController.updateExecutionStatsLocked(0, "com.android.test", inputStats);
        }
        assertEquals(expectedStats, inputStats);

        inputStats.windowSizeMs = expectedStats.windowSizeMs = 3 * HOUR_IN_MILLIS;
        // Invalid time is now +59 minutes since the earliest session in the window is now-121
        // minutes.
        expectedStats.expirationTimeElapsed = now + 59 * MINUTE_IN_MILLIS;
        expectedStats.executionTimeInWindowMs = 12 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInWindow = 10;
        expectedStats.executionTimeInMaxPeriodMs = 22 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInMaxPeriod = 15;
        expectedStats.sessionCountInWindow = 4;
        // App goes under job execution time limit in ~61 minutes, but will be under job count limit
        // in 65 minutes.
        expectedStats.inQuotaTimeElapsed = now + 65 * MINUTE_IN_MILLIS;
        synchronized (mQuotaController.mLock) {
            mQuotaController.updateExecutionStatsLocked(0, "com.android.test", inputStats);
        }
        assertEquals(expectedStats, inputStats);

        inputStats.windowSizeMs = expectedStats.windowSizeMs = 6 * HOUR_IN_MILLIS;
        // Invalid time is now since the start of the session is at the very edge of the window
        // cutoff time.
        expectedStats.expirationTimeElapsed = now;
        expectedStats.executionTimeInWindowMs = 22 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInWindow = 15;
        expectedStats.executionTimeInMaxPeriodMs = 22 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInMaxPeriod = 15;
        expectedStats.sessionCountInWindow = 5;
        expectedStats.inQuotaTimeElapsed = now + 4 * HOUR_IN_MILLIS + 5 * MINUTE_IN_MILLIS;
        synchronized (mQuotaController.mLock) {
            mQuotaController.updateExecutionStatsLocked(0, "com.android.test", inputStats);
        }
        assertEquals(expectedStats, inputStats);

        // Make sure expirationTimeElapsed is set correctly when it's dependent on the max period.
        mQuotaController.getTimingSessions(0, "com.android.test")
                .add(0,
                        createTimingSession(now - (23 * HOUR_IN_MILLIS), MINUTE_IN_MILLIS, 3));
        inputStats.windowSizeMs = expectedStats.windowSizeMs = 8 * HOUR_IN_MILLIS;
        inputStats.jobCountLimit = expectedStats.jobCountLimit = 100;
        // Invalid time is now +1 hour since the earliest session in the max period is 1 hour
        // before the end of the max period cutoff time.
        expectedStats.expirationTimeElapsed = now + HOUR_IN_MILLIS;
        expectedStats.executionTimeInWindowMs = 22 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInWindow = 15;
        expectedStats.executionTimeInMaxPeriodMs = 23 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInMaxPeriod = 18;
        expectedStats.sessionCountInWindow = 5;
        expectedStats.inQuotaTimeElapsed = now + 6 * HOUR_IN_MILLIS + MINUTE_IN_MILLIS
                + mQcConstants.IN_QUOTA_BUFFER_MS;
        synchronized (mQuotaController.mLock) {
            mQuotaController.updateExecutionStatsLocked(0, "com.android.test", inputStats);
        }
        assertEquals(expectedStats, inputStats);

        mQuotaController.getTimingSessions(0, "com.android.test")
                .add(0,
                        createTimingSession(now - (24 * HOUR_IN_MILLIS + MINUTE_IN_MILLIS),
                                2 * MINUTE_IN_MILLIS, 2));
        inputStats.windowSizeMs = expectedStats.windowSizeMs = 8 * HOUR_IN_MILLIS;
        // Invalid time is now since the earliest session straddles the max period cutoff time.
        expectedStats.expirationTimeElapsed = now;
        expectedStats.executionTimeInWindowMs = 22 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInWindow = 15;
        expectedStats.executionTimeInMaxPeriodMs = 24 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInMaxPeriod = 20;
        expectedStats.sessionCountInWindow = 5;
        expectedStats.inQuotaTimeElapsed = now + 6 * HOUR_IN_MILLIS + MINUTE_IN_MILLIS
                + mQcConstants.IN_QUOTA_BUFFER_MS;
        synchronized (mQuotaController.mLock) {
            mQuotaController.updateExecutionStatsLocked(0, "com.android.test", inputStats);
        }
        assertEquals(expectedStats, inputStats);
    }

    @Test
    public void testUpdateExecutionStatsLocked_WithTimer() {
        setProcessState(ActivityManager.PROCESS_STATE_SERVICE);

        ExecutionStats expectedStats = new ExecutionStats();
        ExecutionStats inputStats = new ExecutionStats();
        inputStats.allowedTimePerPeriodMs = expectedStats.allowedTimePerPeriodMs =
                10 * MINUTE_IN_MILLIS;
        inputStats.windowSizeMs = expectedStats.windowSizeMs = 24 * HOUR_IN_MILLIS;
        inputStats.jobCountLimit = expectedStats.jobCountLimit = mQcConstants.MAX_JOB_COUNT_RARE;
        inputStats.sessionCountLimit = expectedStats.sessionCountLimit =
                mQcConstants.MAX_SESSION_COUNT_RARE;
        // Active timer isn't counted as session yet.
        expectedStats.sessionCountInWindow = 0;
        // Timer only, under quota.
        for (int i = 1; i < mQcConstants.MAX_JOB_COUNT_RARE; ++i) {
            JobStatus jobStatus = createJobStatus("testUpdateExecutionStatsLocked_WithTimer", i);
            setStandbyBucket(RARE_INDEX, jobStatus); // 24 hour window
            synchronized (mQuotaController.mLock) {
                mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);
                mQuotaController.prepareForExecutionLocked(jobStatus);
            }
            advanceElapsedClock(7000);

            expectedStats.expirationTimeElapsed = sElapsedRealtimeClock.millis();
            expectedStats.executionTimeInWindowMs = expectedStats.executionTimeInMaxPeriodMs =
                    7000 * i;
            expectedStats.bgJobCountInWindow = expectedStats.bgJobCountInMaxPeriod = i;
            synchronized (mQuotaController.mLock) {
                mQuotaController.updateExecutionStatsLocked(
                        SOURCE_USER_ID, SOURCE_PACKAGE, inputStats);
                assertEquals(expectedStats, inputStats);
                assertTrue(mQuotaController.isWithinQuotaLocked(
                        SOURCE_USER_ID, SOURCE_PACKAGE, RARE_INDEX));
            }
            assertTrue("Job not ready: " + jobStatus, jobStatus.isReady());
        }

        // Add old session. Make sure values are combined correctly.
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(sElapsedRealtimeClock.millis() - (6 * HOUR_IN_MILLIS),
                        10 * MINUTE_IN_MILLIS, 5), false);
        expectedStats.sessionCountInWindow = 1;

        expectedStats.expirationTimeElapsed = sElapsedRealtimeClock.millis() + 18 * HOUR_IN_MILLIS;
        expectedStats.executionTimeInWindowMs += 10 * MINUTE_IN_MILLIS;
        expectedStats.executionTimeInMaxPeriodMs += 10 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInWindow += 5;
        expectedStats.bgJobCountInMaxPeriod += 5;
        // Active timer is under quota, so out of quota due to old session.
        expectedStats.inQuotaTimeElapsed =
                sElapsedRealtimeClock.millis() + 18 * HOUR_IN_MILLIS + 10 * MINUTE_IN_MILLIS;
        synchronized (mQuotaController.mLock) {
            mQuotaController.updateExecutionStatsLocked(SOURCE_USER_ID, SOURCE_PACKAGE, inputStats);
            assertEquals(expectedStats, inputStats);
            assertFalse(
                    mQuotaController.isWithinQuotaLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE, RARE_INDEX));
        }

        // Quota should be exceeded due to activity in active timer.
        JobStatus jobStatus = createJobStatus("testUpdateExecutionStatsLocked_WithTimer", 0);
        setStandbyBucket(RARE_INDEX, jobStatus); // 24 hour window
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);
            mQuotaController.prepareForExecutionLocked(jobStatus);
        }
        advanceElapsedClock(10000);

        expectedStats.executionTimeInWindowMs += 10000;
        expectedStats.executionTimeInMaxPeriodMs += 10000;
        expectedStats.bgJobCountInWindow++;
        expectedStats.bgJobCountInMaxPeriod++;
        // Out of quota due to activity in active timer, so in quota time should be when enough
        // time has passed since active timer.
        expectedStats.inQuotaTimeElapsed =
                sElapsedRealtimeClock.millis() + expectedStats.windowSizeMs;
        synchronized (mQuotaController.mLock) {
            mQuotaController.updateExecutionStatsLocked(SOURCE_USER_ID, SOURCE_PACKAGE, inputStats);
            assertEquals(expectedStats, inputStats);
            assertFalse(
                    mQuotaController.isWithinQuotaLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE, RARE_INDEX));
            assertFalse("Job unexpectedly ready: " + jobStatus, jobStatus.isReady());
        }
    }

    /**
     * Tests that getExecutionStatsLocked returns the correct stats.
     */
    @Test
    public void testGetExecutionStatsLocked_Values() {
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - (23 * HOUR_IN_MILLIS), 10 * MINUTE_IN_MILLIS, 5), false);
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - (7 * HOUR_IN_MILLIS), 10 * MINUTE_IN_MILLIS, 5), false);
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - (2 * HOUR_IN_MILLIS), 10 * MINUTE_IN_MILLIS, 5), false);
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - (6 * MINUTE_IN_MILLIS), 3 * MINUTE_IN_MILLIS, 5), false);

        ExecutionStats expectedStats = new ExecutionStats();

        // Active
        expectedStats.allowedTimePerPeriodMs = 10 * MINUTE_IN_MILLIS;
        expectedStats.windowSizeMs = 10 * MINUTE_IN_MILLIS;
        expectedStats.jobCountLimit = mQcConstants.MAX_JOB_COUNT_ACTIVE;
        expectedStats.sessionCountLimit = mQcConstants.MAX_SESSION_COUNT_ACTIVE;
        expectedStats.expirationTimeElapsed = now + 4 * MINUTE_IN_MILLIS;
        expectedStats.executionTimeInWindowMs = 3 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInWindow = 5;
        expectedStats.executionTimeInMaxPeriodMs = 33 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInMaxPeriod = 20;
        expectedStats.sessionCountInWindow = 1;
        synchronized (mQuotaController.mLock) {
            assertEquals(expectedStats,
                    mQuotaController.getExecutionStatsLocked(0, "com.android.test", ACTIVE_INDEX));
        }

        // Working
        expectedStats.windowSizeMs = 2 * HOUR_IN_MILLIS;
        expectedStats.jobCountLimit = mQcConstants.MAX_JOB_COUNT_WORKING;
        expectedStats.sessionCountLimit = mQcConstants.MAX_SESSION_COUNT_WORKING;
        expectedStats.expirationTimeElapsed = now;
        expectedStats.executionTimeInWindowMs = 13 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInWindow = 10;
        expectedStats.executionTimeInMaxPeriodMs = 33 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInMaxPeriod = 20;
        expectedStats.sessionCountInWindow = 2;
        expectedStats.inQuotaTimeElapsed = now + 3 * MINUTE_IN_MILLIS
                + mQcConstants.IN_QUOTA_BUFFER_MS;
        synchronized (mQuotaController.mLock) {
            assertEquals(expectedStats,
                    mQuotaController.getExecutionStatsLocked(0, "com.android.test", WORKING_INDEX));
        }

        // Frequent
        expectedStats.windowSizeMs = 8 * HOUR_IN_MILLIS;
        expectedStats.jobCountLimit = mQcConstants.MAX_JOB_COUNT_FREQUENT;
        expectedStats.sessionCountLimit = mQcConstants.MAX_SESSION_COUNT_FREQUENT;
        expectedStats.expirationTimeElapsed = now + HOUR_IN_MILLIS;
        expectedStats.executionTimeInWindowMs = 23 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInWindow = 15;
        expectedStats.executionTimeInMaxPeriodMs = 33 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInMaxPeriod = 20;
        expectedStats.sessionCountInWindow = 3;
        expectedStats.inQuotaTimeElapsed = now + 6 * HOUR_IN_MILLIS + 3 * MINUTE_IN_MILLIS
                + mQcConstants.IN_QUOTA_BUFFER_MS;
        synchronized (mQuotaController.mLock) {
            assertEquals(expectedStats,
                    mQuotaController.getExecutionStatsLocked(
                            0, "com.android.test", FREQUENT_INDEX));
        }

        // Rare
        expectedStats.windowSizeMs = 24 * HOUR_IN_MILLIS;
        expectedStats.jobCountLimit = mQcConstants.MAX_JOB_COUNT_RARE;
        expectedStats.sessionCountLimit = mQcConstants.MAX_SESSION_COUNT_RARE;
        expectedStats.expirationTimeElapsed = now + HOUR_IN_MILLIS;
        expectedStats.executionTimeInWindowMs = 33 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInWindow = 20;
        expectedStats.executionTimeInMaxPeriodMs = 33 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInMaxPeriod = 20;
        expectedStats.sessionCountInWindow = 4;
        expectedStats.inQuotaTimeElapsed = now + 22 * HOUR_IN_MILLIS + 3 * MINUTE_IN_MILLIS
                + mQcConstants.IN_QUOTA_BUFFER_MS;
        synchronized (mQuotaController.mLock) {
            assertEquals(expectedStats,
                    mQuotaController.getExecutionStatsLocked(0, "com.android.test", RARE_INDEX));
        }
    }

    /**
     * Tests that getExecutionStatsLocked returns the correct stats soon after device startup.
     */
    @Test
    public void testGetExecutionStatsLocked_Values_BeginningOfTime() {
        // Set time to 3 minutes after boot.
        advanceElapsedClock(-JobSchedulerService.sElapsedRealtimeClock.millis());
        advanceElapsedClock(3 * MINUTE_IN_MILLIS);

        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(MINUTE_IN_MILLIS, MINUTE_IN_MILLIS, 2), false);

        ExecutionStats expectedStats = new ExecutionStats();

        // Active
        expectedStats.allowedTimePerPeriodMs = 10 * MINUTE_IN_MILLIS;
        expectedStats.windowSizeMs = 10 * MINUTE_IN_MILLIS;
        expectedStats.jobCountLimit = mQcConstants.MAX_JOB_COUNT_ACTIVE;
        expectedStats.sessionCountLimit = mQcConstants.MAX_SESSION_COUNT_ACTIVE;
        expectedStats.expirationTimeElapsed = 11 * MINUTE_IN_MILLIS;
        expectedStats.executionTimeInWindowMs = MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInWindow = 2;
        expectedStats.executionTimeInMaxPeriodMs = MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInMaxPeriod = 2;
        expectedStats.sessionCountInWindow = 1;
        synchronized (mQuotaController.mLock) {
            assertEquals(expectedStats,
                    mQuotaController.getExecutionStatsLocked(0, "com.android.test", ACTIVE_INDEX));
        }

        // Working
        expectedStats.windowSizeMs = 2 * HOUR_IN_MILLIS;
        expectedStats.jobCountLimit = mQcConstants.MAX_JOB_COUNT_WORKING;
        expectedStats.sessionCountLimit = mQcConstants.MAX_SESSION_COUNT_WORKING;
        expectedStats.expirationTimeElapsed = 2 * HOUR_IN_MILLIS + MINUTE_IN_MILLIS;
        synchronized (mQuotaController.mLock) {
            assertEquals(expectedStats,
                    mQuotaController.getExecutionStatsLocked(0, "com.android.test", WORKING_INDEX));
        }

        // Frequent
        expectedStats.windowSizeMs = 8 * HOUR_IN_MILLIS;
        expectedStats.jobCountLimit = mQcConstants.MAX_JOB_COUNT_FREQUENT;
        expectedStats.sessionCountLimit = mQcConstants.MAX_SESSION_COUNT_FREQUENT;
        expectedStats.expirationTimeElapsed = 8 * HOUR_IN_MILLIS + MINUTE_IN_MILLIS;
        synchronized (mQuotaController.mLock) {
            assertEquals(expectedStats,
                    mQuotaController.getExecutionStatsLocked(
                            0, "com.android.test", FREQUENT_INDEX));
        }

        // Rare
        expectedStats.windowSizeMs = 24 * HOUR_IN_MILLIS;
        expectedStats.jobCountLimit = mQcConstants.MAX_JOB_COUNT_RARE;
        expectedStats.sessionCountLimit = mQcConstants.MAX_SESSION_COUNT_RARE;
        expectedStats.expirationTimeElapsed = 24 * HOUR_IN_MILLIS + MINUTE_IN_MILLIS;
        synchronized (mQuotaController.mLock) {
            assertEquals(expectedStats,
                    mQuotaController.getExecutionStatsLocked(0, "com.android.test", RARE_INDEX));
        }
    }

    /**
     * Tests that getExecutionStatsLocked returns the correct timing session stats when coalescing.
     */
    @Test
    public void testGetExecutionStatsLocked_CoalescingSessions() {
        for (int i = 0; i < 10; ++i) {
            mQuotaController.saveTimingSession(0, "com.android.test",
                    createTimingSession(
                            JobSchedulerService.sElapsedRealtimeClock.millis(),
                            5 * MINUTE_IN_MILLIS, 5), false);
            advanceElapsedClock(5 * MINUTE_IN_MILLIS);
            advanceElapsedClock(5 * MINUTE_IN_MILLIS);
            for (int j = 0; j < 5; ++j) {
                mQuotaController.saveTimingSession(0, "com.android.test",
                        createTimingSession(
                                JobSchedulerService.sElapsedRealtimeClock.millis(),
                                MINUTE_IN_MILLIS, 2), false);
                advanceElapsedClock(MINUTE_IN_MILLIS);
                advanceElapsedClock(54 * SECOND_IN_MILLIS);
                mQuotaController.saveTimingSession(0, "com.android.test",
                        createTimingSession(
                                JobSchedulerService.sElapsedRealtimeClock.millis(), 500, 1), false);
                advanceElapsedClock(500);
                advanceElapsedClock(400);
                mQuotaController.saveTimingSession(0, "com.android.test",
                        createTimingSession(
                                JobSchedulerService.sElapsedRealtimeClock.millis(), 100, 1), false);
                advanceElapsedClock(100);
                advanceElapsedClock(5 * SECOND_IN_MILLIS);
            }
            advanceElapsedClock(40 * MINUTE_IN_MILLIS);
        }

        setDeviceConfigLong(QcConstants.KEY_TIMING_SESSION_COALESCING_DURATION_MS, 0);

        synchronized (mQuotaController.mLock) {
            mQuotaController.invalidateAllExecutionStatsLocked();
            assertEquals(0, mQuotaController.getExecutionStatsLocked(
                    0, "com.android.test", ACTIVE_INDEX).sessionCountInWindow);
            assertEquals(32, mQuotaController.getExecutionStatsLocked(
                    0, "com.android.test", WORKING_INDEX).sessionCountInWindow);
            assertEquals(128, mQuotaController.getExecutionStatsLocked(
                    0, "com.android.test", FREQUENT_INDEX).sessionCountInWindow);
            assertEquals(160, mQuotaController.getExecutionStatsLocked(
                    0, "com.android.test", RARE_INDEX).sessionCountInWindow);
        }

        setDeviceConfigLong(QcConstants.KEY_TIMING_SESSION_COALESCING_DURATION_MS, 500);

        synchronized (mQuotaController.mLock) {
            mQuotaController.invalidateAllExecutionStatsLocked();
            assertEquals(0, mQuotaController.getExecutionStatsLocked(
                    0, "com.android.test", ACTIVE_INDEX).sessionCountInWindow);
            assertEquals(22, mQuotaController.getExecutionStatsLocked(
                    0, "com.android.test", WORKING_INDEX).sessionCountInWindow);
            assertEquals(88, mQuotaController.getExecutionStatsLocked(
                    0, "com.android.test", FREQUENT_INDEX).sessionCountInWindow);
            assertEquals(110, mQuotaController.getExecutionStatsLocked(
                    0, "com.android.test", RARE_INDEX).sessionCountInWindow);
        }

        setDeviceConfigLong(QcConstants.KEY_TIMING_SESSION_COALESCING_DURATION_MS, 1000);

        synchronized (mQuotaController.mLock) {
            mQuotaController.invalidateAllExecutionStatsLocked();
            assertEquals(0, mQuotaController.getExecutionStatsLocked(
                    0, "com.android.test", ACTIVE_INDEX).sessionCountInWindow);
            assertEquals(22, mQuotaController.getExecutionStatsLocked(
                    0, "com.android.test", WORKING_INDEX).sessionCountInWindow);
            assertEquals(88, mQuotaController.getExecutionStatsLocked(
                    0, "com.android.test", FREQUENT_INDEX).sessionCountInWindow);
            assertEquals(110, mQuotaController.getExecutionStatsLocked(
                    0, "com.android.test", RARE_INDEX).sessionCountInWindow);
        }

        setDeviceConfigLong(QcConstants.KEY_TIMING_SESSION_COALESCING_DURATION_MS,
                5 * SECOND_IN_MILLIS);

        synchronized (mQuotaController.mLock) {
            mQuotaController.invalidateAllExecutionStatsLocked();
            assertEquals(0, mQuotaController.getExecutionStatsLocked(
                    0, "com.android.test", ACTIVE_INDEX).sessionCountInWindow);
            assertEquals(14, mQuotaController.getExecutionStatsLocked(
                    0, "com.android.test", WORKING_INDEX).sessionCountInWindow);
            assertEquals(56, mQuotaController.getExecutionStatsLocked(
                    0, "com.android.test", FREQUENT_INDEX).sessionCountInWindow);
            assertEquals(70, mQuotaController.getExecutionStatsLocked(
                    0, "com.android.test", RARE_INDEX).sessionCountInWindow);
        }

        setDeviceConfigLong(QcConstants.KEY_TIMING_SESSION_COALESCING_DURATION_MS,
                MINUTE_IN_MILLIS);

        synchronized (mQuotaController.mLock) {
            mQuotaController.invalidateAllExecutionStatsLocked();
            assertEquals(0, mQuotaController.getExecutionStatsLocked(
                    0, "com.android.test", ACTIVE_INDEX).sessionCountInWindow);
            assertEquals(4, mQuotaController.getExecutionStatsLocked(
                    0, "com.android.test", WORKING_INDEX).sessionCountInWindow);
            assertEquals(16, mQuotaController.getExecutionStatsLocked(
                    0, "com.android.test", FREQUENT_INDEX).sessionCountInWindow);
            assertEquals(20, mQuotaController.getExecutionStatsLocked(
                    0, "com.android.test", RARE_INDEX).sessionCountInWindow);
        }

        setDeviceConfigLong(QcConstants.KEY_TIMING_SESSION_COALESCING_DURATION_MS,
                5 * MINUTE_IN_MILLIS);

        synchronized (mQuotaController.mLock) {
            mQuotaController.invalidateAllExecutionStatsLocked();
            assertEquals(0, mQuotaController.getExecutionStatsLocked(
                    0, "com.android.test", ACTIVE_INDEX).sessionCountInWindow);
            assertEquals(2, mQuotaController.getExecutionStatsLocked(
                    0, "com.android.test", WORKING_INDEX).sessionCountInWindow);
            assertEquals(8, mQuotaController.getExecutionStatsLocked(
                    0, "com.android.test", FREQUENT_INDEX).sessionCountInWindow);
            assertEquals(10, mQuotaController.getExecutionStatsLocked(
                    0, "com.android.test", RARE_INDEX).sessionCountInWindow);
        }

        setDeviceConfigLong(QcConstants.KEY_TIMING_SESSION_COALESCING_DURATION_MS,
                15 * MINUTE_IN_MILLIS);

        synchronized (mQuotaController.mLock) {
            mQuotaController.invalidateAllExecutionStatsLocked();
            assertEquals(0, mQuotaController.getExecutionStatsLocked(
                    0, "com.android.test", ACTIVE_INDEX).sessionCountInWindow);
            assertEquals(2, mQuotaController.getExecutionStatsLocked(
                    0, "com.android.test", WORKING_INDEX).sessionCountInWindow);
            assertEquals(8, mQuotaController.getExecutionStatsLocked(
                    0, "com.android.test", FREQUENT_INDEX).sessionCountInWindow);
            assertEquals(10, mQuotaController.getExecutionStatsLocked(
                    0, "com.android.test", RARE_INDEX).sessionCountInWindow);
        }

        // QuotaController caps the duration at 15 minutes, so there shouldn't be any difference
        // between an hour and 15 minutes.
        setDeviceConfigLong(QcConstants.KEY_TIMING_SESSION_COALESCING_DURATION_MS, HOUR_IN_MILLIS);

        synchronized (mQuotaController.mLock) {
            mQuotaController.invalidateAllExecutionStatsLocked();
            assertEquals(0, mQuotaController.getExecutionStatsLocked(
                    0, "com.android.test", ACTIVE_INDEX).sessionCountInWindow);
            assertEquals(2, mQuotaController.getExecutionStatsLocked(
                    0, "com.android.test", WORKING_INDEX).sessionCountInWindow);
            assertEquals(8, mQuotaController.getExecutionStatsLocked(
                    0, "com.android.test", FREQUENT_INDEX).sessionCountInWindow);
            assertEquals(10, mQuotaController.getExecutionStatsLocked(
                    0, "com.android.test", RARE_INDEX).sessionCountInWindow);
        }
    }

    /**
     * Tests that getExecutionStatsLocked properly caches the stats and returns the cached object.
     */
    @Test
    public void testGetExecutionStatsLocked_Caching() {
        spyOn(mQuotaController);
        doNothing().when(mQuotaController).invalidateAllExecutionStatsLocked();

        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - (23 * HOUR_IN_MILLIS), 10 * MINUTE_IN_MILLIS, 5), false);
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - (7 * HOUR_IN_MILLIS), 10 * MINUTE_IN_MILLIS, 5), false);
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - (2 * HOUR_IN_MILLIS), 10 * MINUTE_IN_MILLIS, 5), false);
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - (6 * MINUTE_IN_MILLIS), 3 * MINUTE_IN_MILLIS, 5), false);
        final ExecutionStats originalStatsActive;
        final ExecutionStats originalStatsWorking;
        final ExecutionStats originalStatsFrequent;
        final ExecutionStats originalStatsRare;
        synchronized (mQuotaController.mLock) {
            originalStatsActive = mQuotaController.getExecutionStatsLocked(
                    0, "com.android.test", ACTIVE_INDEX);
            originalStatsWorking = mQuotaController.getExecutionStatsLocked(
                    0, "com.android.test", WORKING_INDEX);
            originalStatsFrequent = mQuotaController.getExecutionStatsLocked(
                    0, "com.android.test", FREQUENT_INDEX);
            originalStatsRare = mQuotaController.getExecutionStatsLocked(
                    0, "com.android.test", RARE_INDEX);
        }

        // Advance clock so that the working stats shouldn't be the same.
        advanceElapsedClock(MINUTE_IN_MILLIS);
        // Change frequent bucket size so that the stats need to be recalculated.
        setDeviceConfigLong(QcConstants.KEY_WINDOW_SIZE_FREQUENT_MS, 6 * HOUR_IN_MILLIS);

        ExecutionStats expectedStats = new ExecutionStats();
        expectedStats.allowedTimePerPeriodMs = originalStatsActive.allowedTimePerPeriodMs;
        expectedStats.windowSizeMs = originalStatsActive.windowSizeMs;
        expectedStats.jobCountLimit = originalStatsActive.jobCountLimit;
        expectedStats.sessionCountLimit = originalStatsActive.sessionCountLimit;
        expectedStats.expirationTimeElapsed = originalStatsActive.expirationTimeElapsed;
        expectedStats.executionTimeInWindowMs = originalStatsActive.executionTimeInWindowMs;
        expectedStats.bgJobCountInWindow = originalStatsActive.bgJobCountInWindow;
        expectedStats.executionTimeInMaxPeriodMs = originalStatsActive.executionTimeInMaxPeriodMs;
        expectedStats.bgJobCountInMaxPeriod = originalStatsActive.bgJobCountInMaxPeriod;
        expectedStats.sessionCountInWindow = originalStatsActive.sessionCountInWindow;
        expectedStats.inQuotaTimeElapsed = originalStatsActive.inQuotaTimeElapsed;
        final ExecutionStats newStatsActive;
        synchronized (mQuotaController.mLock) {
            newStatsActive = mQuotaController.getExecutionStatsLocked(
                    0, "com.android.test", ACTIVE_INDEX);
        }
        // Stats for the same bucket should use the same object.
        assertTrue(originalStatsActive == newStatsActive);
        assertEquals(expectedStats, newStatsActive);

        expectedStats.allowedTimePerPeriodMs = originalStatsWorking.allowedTimePerPeriodMs;
        expectedStats.windowSizeMs = originalStatsWorking.windowSizeMs;
        expectedStats.jobCountLimit = originalStatsWorking.jobCountLimit;
        expectedStats.sessionCountLimit = originalStatsWorking.sessionCountLimit;
        expectedStats.expirationTimeElapsed = originalStatsWorking.expirationTimeElapsed;
        expectedStats.executionTimeInWindowMs = originalStatsWorking.executionTimeInWindowMs;
        expectedStats.bgJobCountInWindow = originalStatsWorking.bgJobCountInWindow;
        expectedStats.sessionCountInWindow = originalStatsWorking.sessionCountInWindow;
        expectedStats.inQuotaTimeElapsed = originalStatsWorking.inQuotaTimeElapsed;
        final ExecutionStats newStatsWorking;
        synchronized (mQuotaController.mLock) {
            newStatsWorking = mQuotaController.getExecutionStatsLocked(
                    0, "com.android.test", WORKING_INDEX);
        }
        assertTrue(originalStatsWorking == newStatsWorking);
        assertNotEquals(expectedStats, newStatsWorking);

        expectedStats.allowedTimePerPeriodMs = originalStatsFrequent.allowedTimePerPeriodMs;
        expectedStats.windowSizeMs = originalStatsFrequent.windowSizeMs;
        expectedStats.jobCountLimit = originalStatsFrequent.jobCountLimit;
        expectedStats.sessionCountLimit = originalStatsFrequent.sessionCountLimit;
        expectedStats.expirationTimeElapsed = originalStatsFrequent.expirationTimeElapsed;
        expectedStats.executionTimeInWindowMs = originalStatsFrequent.executionTimeInWindowMs;
        expectedStats.bgJobCountInWindow = originalStatsFrequent.bgJobCountInWindow;
        expectedStats.sessionCountInWindow = originalStatsFrequent.sessionCountInWindow;
        expectedStats.inQuotaTimeElapsed = originalStatsFrequent.inQuotaTimeElapsed;
        final ExecutionStats newStatsFrequent;
        synchronized (mQuotaController.mLock) {
            newStatsFrequent = mQuotaController.getExecutionStatsLocked(
                    0, "com.android.test", FREQUENT_INDEX);
        }
        assertTrue(originalStatsFrequent == newStatsFrequent);
        assertNotEquals(expectedStats, newStatsFrequent);

        expectedStats.allowedTimePerPeriodMs = originalStatsRare.allowedTimePerPeriodMs;
        expectedStats.windowSizeMs = originalStatsRare.windowSizeMs;
        expectedStats.jobCountLimit = originalStatsRare.jobCountLimit;
        expectedStats.sessionCountLimit = originalStatsRare.sessionCountLimit;
        expectedStats.expirationTimeElapsed = originalStatsRare.expirationTimeElapsed;
        expectedStats.executionTimeInWindowMs = originalStatsRare.executionTimeInWindowMs;
        expectedStats.bgJobCountInWindow = originalStatsRare.bgJobCountInWindow;
        expectedStats.sessionCountInWindow = originalStatsRare.sessionCountInWindow;
        expectedStats.inQuotaTimeElapsed = originalStatsRare.inQuotaTimeElapsed;
        final ExecutionStats newStatsRare;
        synchronized (mQuotaController.mLock) {
            newStatsRare = mQuotaController.getExecutionStatsLocked(
                    0, "com.android.test", RARE_INDEX);
        }
        assertTrue(originalStatsRare == newStatsRare);
        assertEquals(expectedStats, newStatsRare);
    }

    @Test
    public void testGetMaxJobExecutionTimeLocked_Regular() {
        mQuotaController.saveTimingSession(0, SOURCE_PACKAGE,
                createTimingSession(sElapsedRealtimeClock.millis() - (6 * MINUTE_IN_MILLIS),
                        3 * MINUTE_IN_MILLIS, 5), false);
        JobStatus job = createJobStatus("testGetMaxJobExecutionTimeLocked", 0);
        setStandbyBucket(RARE_INDEX, job);

        setCharging();
        synchronized (mQuotaController.mLock) {
            assertEquals(JobSchedulerService.Constants.DEFAULT_RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                    mQuotaController.getMaxJobExecutionTimeMsLocked((job)));
        }

        setDischarging();
        setProcessState(ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE);
        synchronized (mQuotaController.mLock) {
            assertEquals(JobSchedulerService.Constants.DEFAULT_RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                    mQuotaController.getMaxJobExecutionTimeMsLocked((job)));
        }

        // Top-started job
        setProcessState(ActivityManager.PROCESS_STATE_TOP);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(job, null);
            mQuotaController.prepareForExecutionLocked(job);
        }
        setProcessState(ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND);
        synchronized (mQuotaController.mLock) {
            assertEquals(JobSchedulerService.Constants.DEFAULT_RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                    mQuotaController.getMaxJobExecutionTimeMsLocked((job)));
            mQuotaController.maybeStopTrackingJobLocked(job, null, false);
        }

        setProcessState(ActivityManager.PROCESS_STATE_RECEIVER);
        synchronized (mQuotaController.mLock) {
            assertEquals(7 * MINUTE_IN_MILLIS,
                    mQuotaController.getMaxJobExecutionTimeMsLocked(job));
        }
    }

    @Test
    public void testGetMaxJobExecutionTimeLocked_Regular_Active() {
        JobStatus job = createJobStatus("testGetMaxJobExecutionTimeLocked_Regular_Active", 0);
        setDeviceConfigLong(QcConstants.KEY_ALLOWED_TIME_PER_PERIOD_ACTIVE_MS,
                10 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_WINDOW_SIZE_ACTIVE_MS, 10 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_MAX_EXECUTION_TIME_MS, 2 * HOUR_IN_MILLIS);
        setDischarging();
        setStandbyBucket(ACTIVE_INDEX, job);
        setProcessState(ActivityManager.PROCESS_STATE_CACHED_ACTIVITY);

        // ACTIVE apps (where allowed time = window size) should be capped at max execution limit.
        synchronized (mQuotaController.mLock) {
            assertEquals(2 * HOUR_IN_MILLIS,
                    mQuotaController.getMaxJobExecutionTimeMsLocked((job)));
        }

        // Make sure sessions are factored in properly.
        mQuotaController.saveTimingSession(0, SOURCE_PACKAGE,
                createTimingSession(sElapsedRealtimeClock.millis() - (6 * HOUR_IN_MILLIS),
                        30 * MINUTE_IN_MILLIS, 1), false);
        synchronized (mQuotaController.mLock) {
            assertEquals(90 * MINUTE_IN_MILLIS,
                    mQuotaController.getMaxJobExecutionTimeMsLocked((job)));
        }

        mQuotaController.saveTimingSession(0, SOURCE_PACKAGE,
                createTimingSession(sElapsedRealtimeClock.millis() - (5 * HOUR_IN_MILLIS),
                        30 * MINUTE_IN_MILLIS, 1), false);
        mQuotaController.saveTimingSession(0, SOURCE_PACKAGE,
                createTimingSession(sElapsedRealtimeClock.millis() - (4 * HOUR_IN_MILLIS),
                        30 * MINUTE_IN_MILLIS, 1), false);
        mQuotaController.saveTimingSession(0, SOURCE_PACKAGE,
                createTimingSession(sElapsedRealtimeClock.millis() - (3 * HOUR_IN_MILLIS),
                        25 * MINUTE_IN_MILLIS, 1), false);
        synchronized (mQuotaController.mLock) {
            assertEquals(5 * MINUTE_IN_MILLIS,
                    mQuotaController.getMaxJobExecutionTimeMsLocked((job)));
        }
    }

    @Test
    public void testGetMaxJobExecutionTimeLocked_EJ() {
        final long timeUsedMs = 3 * MINUTE_IN_MILLIS;
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(sElapsedRealtimeClock.millis() - (6 * MINUTE_IN_MILLIS),
                        timeUsedMs, 5), true);
        JobStatus job = createExpeditedJobStatus("testGetMaxJobExecutionTimeLocked_EJ", 0);
        setStandbyBucket(RARE_INDEX, job);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(job, null);
        }

        setCharging();
        synchronized (mQuotaController.mLock) {
            assertEquals(JobSchedulerService.Constants.DEFAULT_RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                    mQuotaController.getMaxJobExecutionTimeMsLocked(job));
        }

        setDischarging();
        setProcessState(ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE);
        synchronized (mQuotaController.mLock) {
            assertEquals(mQcConstants.EJ_LIMIT_WORKING_MS / 2,
                    mQuotaController.getMaxJobExecutionTimeMsLocked(job));
        }

        // Top-started job
        setProcessState(ActivityManager.PROCESS_STATE_TOP);
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(job);
        }
        setProcessState(ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND);
        synchronized (mQuotaController.mLock) {
            assertEquals(mQcConstants.EJ_LIMIT_ACTIVE_MS / 2,
                    mQuotaController.getMaxJobExecutionTimeMsLocked(job));
            mQuotaController.maybeStopTrackingJobLocked(job, null, false);
        }

        setProcessState(ActivityManager.PROCESS_STATE_RECEIVER);
        synchronized (mQuotaController.mLock) {
            assertEquals(mQcConstants.EJ_LIMIT_RARE_MS - timeUsedMs,
                    mQuotaController.getMaxJobExecutionTimeMsLocked(job));
        }

        // Test used quota rolling out of window.
        synchronized (mQuotaController.mLock) {
            mQuotaController.clearAppStatsLocked(SOURCE_USER_ID, SOURCE_PACKAGE);
        }
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(sElapsedRealtimeClock.millis() - mQcConstants.EJ_WINDOW_SIZE_MS,
                        timeUsedMs, 5), true);

        setProcessState(ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE);
        synchronized (mQuotaController.mLock) {
            assertEquals(mQcConstants.EJ_LIMIT_WORKING_MS / 2,
                    mQuotaController.getMaxJobExecutionTimeMsLocked(job));
        }

        // Top-started job
        setProcessState(ActivityManager.PROCESS_STATE_TOP);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(job, null);
            mQuotaController.prepareForExecutionLocked(job);
        }
        setProcessState(ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND);
        synchronized (mQuotaController.mLock) {
            assertEquals(mQcConstants.EJ_LIMIT_ACTIVE_MS / 2,
                    mQuotaController.getMaxJobExecutionTimeMsLocked(job));
            mQuotaController.maybeStopTrackingJobLocked(job, null, false);
        }

        setProcessState(ActivityManager.PROCESS_STATE_RECEIVER);
        synchronized (mQuotaController.mLock) {
            assertEquals(mQcConstants.EJ_LIMIT_RARE_MS,
                    mQuotaController.getMaxJobExecutionTimeMsLocked(job));
        }
    }

    /**
     * Test getTimeUntilQuotaConsumedLocked when allowed time equals the bucket window size.
     */
    @Test
    public void testGetTimeUntilQuotaConsumedLocked_AllowedEqualsWindow() {
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - (8 * HOUR_IN_MILLIS), 20 * MINUTE_IN_MILLIS, 5), false);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - (10 * MINUTE_IN_MILLIS), 10 * MINUTE_IN_MILLIS, 5),
                false);

        setDeviceConfigLong(QcConstants.KEY_ALLOWED_TIME_PER_PERIOD_EXEMPTED_MS,
                10 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_WINDOW_SIZE_EXEMPTED_MS, 10 * MINUTE_IN_MILLIS);
        // window size = allowed time, so jobs can essentially run non-stop until they reach the
        // max execution time.
        setStandbyBucket(EXEMPTED_INDEX);
        synchronized (mQuotaController.mLock) {
            assertEquals(0,
                    mQuotaController.getRemainingExecutionTimeLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE));
            assertEquals(mQcConstants.MAX_EXECUTION_TIME_MS - 30 * MINUTE_IN_MILLIS,
                    mQuotaController.getTimeUntilQuotaConsumedLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE));
        }
    }

    /**
     * Test getTimeUntilQuotaConsumedLocked when the determination is based within the bucket
     * window.
     */
    @Test
    public void testGetTimeUntilQuotaConsumedLocked_BucketWindow() {
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        // Close to RARE boundary.
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - (24 * HOUR_IN_MILLIS - 30 * SECOND_IN_MILLIS),
                        30 * SECOND_IN_MILLIS, 5), false);
        // Far away from FREQUENT boundary.
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - (7 * HOUR_IN_MILLIS), 3 * MINUTE_IN_MILLIS, 5), false);
        // Overlap WORKING_SET boundary.
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - (2 * HOUR_IN_MILLIS + MINUTE_IN_MILLIS),
                        3 * MINUTE_IN_MILLIS, 5), false);
        // Close to ACTIVE boundary.
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - (9 * MINUTE_IN_MILLIS), 3 * MINUTE_IN_MILLIS, 5), false);

        setStandbyBucket(RARE_INDEX);
        synchronized (mQuotaController.mLock) {
            assertEquals(30 * SECOND_IN_MILLIS,
                    mQuotaController.getRemainingExecutionTimeLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE));
            assertEquals(MINUTE_IN_MILLIS,
                    mQuotaController.getTimeUntilQuotaConsumedLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE));
        }

        setStandbyBucket(FREQUENT_INDEX);
        synchronized (mQuotaController.mLock) {
            assertEquals(MINUTE_IN_MILLIS,
                    mQuotaController.getRemainingExecutionTimeLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE));
            assertEquals(MINUTE_IN_MILLIS,
                    mQuotaController.getTimeUntilQuotaConsumedLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE));
        }

        setStandbyBucket(WORKING_INDEX);
        synchronized (mQuotaController.mLock) {
            assertEquals(5 * MINUTE_IN_MILLIS,
                    mQuotaController.getRemainingExecutionTimeLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE));
            assertEquals(7 * MINUTE_IN_MILLIS,
                    mQuotaController.getTimeUntilQuotaConsumedLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE));
        }

        // ACTIVE window = allowed time, so jobs can essentially run non-stop until they reach the
        // max execution time.
        setStandbyBucket(ACTIVE_INDEX);
        synchronized (mQuotaController.mLock) {
            assertEquals(7 * MINUTE_IN_MILLIS,
                    mQuotaController.getRemainingExecutionTimeLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE));
            assertEquals(mQcConstants.MAX_EXECUTION_TIME_MS - 9 * MINUTE_IN_MILLIS,
                    mQuotaController.getTimeUntilQuotaConsumedLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE));
        }
    }

    /**
     * Test getTimeUntilQuotaConsumedLocked when the app is close to the max execution limit.
     */
    @Test
    public void testGetTimeUntilQuotaConsumedLocked_MaxExecution() {
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        // Overlap boundary.
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(
                        now - (24 * HOUR_IN_MILLIS + 8 * MINUTE_IN_MILLIS), 4 * HOUR_IN_MILLIS, 5),
                false);

        setStandbyBucket(WORKING_INDEX);
        synchronized (mQuotaController.mLock) {
            assertEquals(8 * MINUTE_IN_MILLIS,
                    mQuotaController.getRemainingExecutionTimeLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE));
            // Max time will phase out, so should use bucket limit.
            assertEquals(10 * MINUTE_IN_MILLIS,
                    mQuotaController.getTimeUntilQuotaConsumedLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE));
        }

        mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE).clear();
        // Close to boundary.
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - (24 * HOUR_IN_MILLIS - MINUTE_IN_MILLIS),
                        4 * HOUR_IN_MILLIS - 5 * MINUTE_IN_MILLIS, 5), false);

        setStandbyBucket(WORKING_INDEX);
        synchronized (mQuotaController.mLock) {
            assertEquals(5 * MINUTE_IN_MILLIS,
                    mQuotaController.getRemainingExecutionTimeLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE));
            assertEquals(10 * MINUTE_IN_MILLIS,
                    mQuotaController.getTimeUntilQuotaConsumedLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE));
        }

        mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE).clear();
        // Far from boundary.
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(
                        now - (20 * HOUR_IN_MILLIS), 4 * HOUR_IN_MILLIS - 3 * MINUTE_IN_MILLIS, 5),
                false);

        setStandbyBucket(WORKING_INDEX);
        synchronized (mQuotaController.mLock) {
            assertEquals(3 * MINUTE_IN_MILLIS,
                    mQuotaController.getRemainingExecutionTimeLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE));
            assertEquals(3 * MINUTE_IN_MILLIS,
                    mQuotaController.getTimeUntilQuotaConsumedLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE));
        }
    }

    /**
     * Test getTimeUntilQuotaConsumedLocked when the max execution time and bucket window time
     * remaining are equal.
     */
    @Test
    public void testGetTimeUntilQuotaConsumedLocked_EqualTimeRemaining() {
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        setStandbyBucket(FREQUENT_INDEX);

        // Overlap boundary.
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(
                        now - (24 * HOUR_IN_MILLIS + 11 * MINUTE_IN_MILLIS),
                        4 * HOUR_IN_MILLIS,
                        5), false);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(
                        now - (8 * HOUR_IN_MILLIS + MINUTE_IN_MILLIS), 3 * MINUTE_IN_MILLIS, 5),
                false);

        synchronized (mQuotaController.mLock) {
            // Both max and bucket time have 8 minutes left.
            assertEquals(8 * MINUTE_IN_MILLIS,
                    mQuotaController.getRemainingExecutionTimeLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE));
            // Max time essentially free. Bucket time has 2 min phase out plus original 8 minute
            // window time.
            assertEquals(10 * MINUTE_IN_MILLIS,
                    mQuotaController.getTimeUntilQuotaConsumedLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE));
        }

        mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE).clear();
        // Overlap boundary.
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(
                        now - (24 * HOUR_IN_MILLIS + MINUTE_IN_MILLIS), 2 * MINUTE_IN_MILLIS, 5),
                false);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(
                        now - (20 * HOUR_IN_MILLIS),
                        3 * HOUR_IN_MILLIS + 48 * MINUTE_IN_MILLIS,
                        5), false);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(
                        now - (8 * HOUR_IN_MILLIS + MINUTE_IN_MILLIS), 3 * MINUTE_IN_MILLIS, 5),
                false);

        synchronized (mQuotaController.mLock) {
            // Both max and bucket time have 8 minutes left.
            assertEquals(8 * MINUTE_IN_MILLIS,
                    mQuotaController.getRemainingExecutionTimeLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE));
            // Max time only has one minute phase out. Bucket time has 2 minute phase out.
            assertEquals(9 * MINUTE_IN_MILLIS,
                    mQuotaController.getTimeUntilQuotaConsumedLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE));
        }
    }

    /**
     * Test getTimeUntilQuotaConsumedLocked when allowed time equals the bucket window size.
     */
    @Test
    public void testGetTimeUntilQuotaConsumedLocked_EdgeOfWindow_AllowedEqualsWindow() {
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - (24 * HOUR_IN_MILLIS),
                        mQcConstants.MAX_EXECUTION_TIME_MS - 10 * MINUTE_IN_MILLIS, 5),
                false);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - (10 * MINUTE_IN_MILLIS), 10 * MINUTE_IN_MILLIS, 5),
                false);

        setDeviceConfigLong(QcConstants.KEY_ALLOWED_TIME_PER_PERIOD_EXEMPTED_MS,
                10 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_WINDOW_SIZE_EXEMPTED_MS, 10 * MINUTE_IN_MILLIS);
        // window size = allowed time, so jobs can essentially run non-stop until they reach the
        // max execution time.
        setStandbyBucket(EXEMPTED_INDEX);
        synchronized (mQuotaController.mLock) {
            assertEquals(0,
                    mQuotaController.getRemainingExecutionTimeLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE));
            assertEquals(mQcConstants.MAX_EXECUTION_TIME_MS - 10 * MINUTE_IN_MILLIS,
                    mQuotaController.getTimeUntilQuotaConsumedLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE));
        }
    }

    /**
     * Test getTimeUntilQuotaConsumedLocked when the determination is based within the bucket
     * window and the session is rolling out of the window.
     */
    @Test
    public void testGetTimeUntilQuotaConsumedLocked_EdgeOfWindow_BucketWindow() {
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();

        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - (24 * HOUR_IN_MILLIS),
                        10 * MINUTE_IN_MILLIS, 5), false);
        setStandbyBucket(RARE_INDEX);
        synchronized (mQuotaController.mLock) {
            assertEquals(0,
                    mQuotaController.getRemainingExecutionTimeLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE));
            assertEquals(10 * MINUTE_IN_MILLIS,
                    mQuotaController.getTimeUntilQuotaConsumedLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE));
        }

        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - (8 * HOUR_IN_MILLIS), 10 * MINUTE_IN_MILLIS, 5), false);
        setStandbyBucket(FREQUENT_INDEX);
        synchronized (mQuotaController.mLock) {
            assertEquals(0,
                    mQuotaController.getRemainingExecutionTimeLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE));
            assertEquals(10 * MINUTE_IN_MILLIS,
                    mQuotaController.getTimeUntilQuotaConsumedLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE));
        }

        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - (2 * HOUR_IN_MILLIS),
                        10 * MINUTE_IN_MILLIS, 5), false);
        setStandbyBucket(WORKING_INDEX);
        synchronized (mQuotaController.mLock) {
            assertEquals(0,
                    mQuotaController.getRemainingExecutionTimeLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE));
            assertEquals(10 * MINUTE_IN_MILLIS,
                    mQuotaController.getTimeUntilQuotaConsumedLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE));
        }

        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - (10 * MINUTE_IN_MILLIS), 10 * MINUTE_IN_MILLIS, 5),
                false);
        // ACTIVE window = allowed time, so jobs can essentially run non-stop until they reach the
        // max execution time.
        setStandbyBucket(ACTIVE_INDEX);
        synchronized (mQuotaController.mLock) {
            assertEquals(0,
                    mQuotaController.getRemainingExecutionTimeLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE));
            assertEquals(mQcConstants.MAX_EXECUTION_TIME_MS - 30 * MINUTE_IN_MILLIS,
                    mQuotaController.getTimeUntilQuotaConsumedLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE));
        }
    }

    @Test
    public void testIsWithinQuotaLocked_NeverApp() {
        synchronized (mQuotaController.mLock) {
            assertFalse(
                    mQuotaController.isWithinQuotaLocked(0, "com.android.test.never", NEVER_INDEX));
        }
    }

    @Test
    public void testIsWithinQuotaLocked_Charging() {
        setCharging();
        synchronized (mQuotaController.mLock) {
            assertTrue(mQuotaController.isWithinQuotaLocked(0, "com.android.test", RARE_INDEX));
        }
    }

    @Test
    public void testIsWithinQuotaLocked_UnderDuration_UnderJobCount() {
        setDischarging();
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - (HOUR_IN_MILLIS), 3 * MINUTE_IN_MILLIS, 5), false);
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - (5 * MINUTE_IN_MILLIS), 3 * MINUTE_IN_MILLIS, 5), false);
        synchronized (mQuotaController.mLock) {
            mQuotaController.incrementJobCountLocked(0, "com.android.test", 5);
            assertTrue(mQuotaController.isWithinQuotaLocked(0, "com.android.test", WORKING_INDEX));
        }
    }

    @Test
    public void testIsWithinQuotaLocked_UnderDuration_OverJobCount() {
        setDischarging();
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        final int jobCount = mQcConstants.MAX_JOB_COUNT_PER_RATE_LIMITING_WINDOW;
        mQuotaController.saveTimingSession(0, "com.android.test.spam",
                createTimingSession(now - (HOUR_IN_MILLIS), 15 * MINUTE_IN_MILLIS, 25), false);
        mQuotaController.saveTimingSession(0, "com.android.test.spam",
                createTimingSession(now - (5 * MINUTE_IN_MILLIS), 3 * MINUTE_IN_MILLIS, jobCount),
                false);
        synchronized (mQuotaController.mLock) {
            mQuotaController.incrementJobCountLocked(0, "com.android.test.spam", jobCount);
            assertFalse(mQuotaController.isWithinQuotaLocked(
                    0, "com.android.test.spam", WORKING_INDEX));
        }

        mQuotaController.saveTimingSession(0, "com.android.test.frequent",
                createTimingSession(now - (2 * HOUR_IN_MILLIS), 15 * MINUTE_IN_MILLIS, 2000),
                false);
        mQuotaController.saveTimingSession(0, "com.android.test.frequent",
                createTimingSession(now - (HOUR_IN_MILLIS), 3 * MINUTE_IN_MILLIS, 500), false);
        synchronized (mQuotaController.mLock) {
            assertFalse(mQuotaController.isWithinQuotaLocked(
                    0, "com.android.test.frequent", FREQUENT_INDEX));
        }
    }

    @Test
    public void testIsWithinQuotaLocked_OverDuration_UnderJobCount() {
        setDischarging();
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - (HOUR_IN_MILLIS), 3 * MINUTE_IN_MILLIS, 5), false);
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - (30 * MINUTE_IN_MILLIS), 3 * MINUTE_IN_MILLIS, 5), false);
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - (5 * MINUTE_IN_MILLIS), 4 * MINUTE_IN_MILLIS, 5), false);
        synchronized (mQuotaController.mLock) {
            mQuotaController.incrementJobCountLocked(0, "com.android.test", 5);
            assertFalse(mQuotaController.isWithinQuotaLocked(0, "com.android.test", WORKING_INDEX));
        }
    }

    @Test
    public void testIsWithinQuotaLocked_OverDuration_OverJobCount() {
        setDischarging();
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        final int jobCount = mQcConstants.MAX_JOB_COUNT_PER_RATE_LIMITING_WINDOW;
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - (HOUR_IN_MILLIS), 15 * MINUTE_IN_MILLIS, 25), false);
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - (5 * MINUTE_IN_MILLIS), 3 * MINUTE_IN_MILLIS, jobCount),
                false);
        synchronized (mQuotaController.mLock) {
            mQuotaController.incrementJobCountLocked(0, "com.android.test", jobCount);
            assertFalse(mQuotaController.isWithinQuotaLocked(0, "com.android.test", WORKING_INDEX));
        }
    }

    @Test
    public void testIsWithinQuotaLocked_UnderDuration_UnderJobCount_MultiStateChange_BelowFGS() {
        setDischarging();

        JobStatus jobStatus = createJobStatus(
                "testIsWithinQuotaLocked_UnderDuration_UnderJobCount_MultiStateChange_BelowFGS", 1);
        setStandbyBucket(ACTIVE_INDEX, jobStatus);
        setProcessState(ActivityManager.PROCESS_STATE_BACKUP);

        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);
            mQuotaController.prepareForExecutionLocked(jobStatus);
        }
        for (int i = 0; i < 20; ++i) {
            advanceElapsedClock(SECOND_IN_MILLIS);
            setProcessState(ActivityManager.PROCESS_STATE_SERVICE);
            setProcessState(ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND);
        }
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobStatus, null, false);
        }

        advanceElapsedClock(15 * SECOND_IN_MILLIS);

        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);
            mQuotaController.prepareForExecutionLocked(jobStatus);
        }
        for (int i = 0; i < 20; ++i) {
            advanceElapsedClock(SECOND_IN_MILLIS);
            setProcessState(ActivityManager.PROCESS_STATE_SERVICE);
            setProcessState(ActivityManager.PROCESS_STATE_RECEIVER);
        }
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobStatus, null, false);
        }

        advanceElapsedClock(10 * MINUTE_IN_MILLIS + 30 * SECOND_IN_MILLIS);

        synchronized (mQuotaController.mLock) {
            assertEquals(2, mQuotaController.getExecutionStatsLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, ACTIVE_INDEX).jobCountInRateLimitingWindow);
            assertTrue(mQuotaController.isWithinQuotaLocked(jobStatus));
            assertTrue(jobStatus.isReady());
        }
    }

    @Test
    public void testIsWithinQuotaLocked_UnderDuration_UnderJobCount_MultiStateChange_SeparateApps()
            throws Exception {
        setDischarging();

        final String unaffectedPkgName = "com.android.unaffected";
        final int unaffectedUid = 10987;
        JobInfo unaffectedJobInfo = new JobInfo.Builder(1,
                new ComponentName(unaffectedPkgName, "foo"))
                .build();
        JobStatus unaffected = createJobStatus(
                "testIsWithinQuotaLocked_UnderDuration_UnderJobCount_MultiStateChange_SeparateApps",
                unaffectedPkgName, unaffectedUid, unaffectedJobInfo);
        setStandbyBucket(FREQUENT_INDEX, unaffected);
        setProcessState(ActivityManager.PROCESS_STATE_SERVICE, unaffectedUid);

        final String fgChangerPkgName = "com.android.foreground.changer";
        final int fgChangerUid = 10234;
        JobInfo fgChangerJobInfo = new JobInfo.Builder(2,
                new ComponentName(fgChangerPkgName, "foo"))
                .build();
        JobStatus fgStateChanger = createJobStatus(
                "testIsWithinQuotaLocked_UnderDuration_UnderJobCount_MultiStateChange_SeparateApps",
                fgChangerPkgName, fgChangerUid, fgChangerJobInfo);
        setStandbyBucket(ACTIVE_INDEX, fgStateChanger);
        setProcessState(ActivityManager.PROCESS_STATE_BACKUP, fgChangerUid);

        doReturn(new ArraySet<>(new String[]{unaffectedPkgName}))
                .when(mJobSchedulerService).getPackagesForUidLocked(unaffectedUid);
        doReturn(new ArraySet<>(new String[]{fgChangerPkgName}))
                .when(mJobSchedulerService).getPackagesForUidLocked(fgChangerUid);

        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(unaffected, null);
            mQuotaController.prepareForExecutionLocked(unaffected);

            mQuotaController.maybeStartTrackingJobLocked(fgStateChanger, null);
            mQuotaController.prepareForExecutionLocked(fgStateChanger);
        }
        for (int i = 0; i < 20; ++i) {
            advanceElapsedClock(SECOND_IN_MILLIS);
            setProcessState(ActivityManager.PROCESS_STATE_TOP, fgChangerUid);
            setProcessState(ActivityManager.PROCESS_STATE_TOP_SLEEPING, fgChangerUid);
        }
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(fgStateChanger, null, false);
        }

        advanceElapsedClock(15 * SECOND_IN_MILLIS);

        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(fgStateChanger, null);
            mQuotaController.prepareForExecutionLocked(fgStateChanger);
        }
        for (int i = 0; i < 20; ++i) {
            advanceElapsedClock(SECOND_IN_MILLIS);
            setProcessState(ActivityManager.PROCESS_STATE_TOP, fgChangerUid);
            setProcessState(ActivityManager.PROCESS_STATE_TOP_SLEEPING, fgChangerUid);
        }
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(fgStateChanger, null, false);

            mQuotaController.maybeStopTrackingJobLocked(unaffected, null, false);

            assertTrue(mQuotaController.isWithinQuotaLocked(unaffected));
            assertTrue(unaffected.isReady());
            assertFalse(mQuotaController.isWithinQuotaLocked(fgStateChanger));
            assertFalse(fgStateChanger.isReady());
        }
        assertEquals(1,
                mQuotaController.getTimingSessions(SOURCE_USER_ID, unaffectedPkgName).size());
        assertEquals(42,
                mQuotaController.getTimingSessions(SOURCE_USER_ID, fgChangerPkgName).size());
        synchronized (mQuotaController.mLock) {
            for (int i = ACTIVE_INDEX; i < RARE_INDEX; ++i) {
                assertEquals(42, mQuotaController.getExecutionStatsLocked(
                        SOURCE_USER_ID, fgChangerPkgName, i).jobCountInRateLimitingWindow);
                assertEquals(1, mQuotaController.getExecutionStatsLocked(
                        SOURCE_USER_ID, unaffectedPkgName, i).jobCountInRateLimitingWindow);
            }
        }
    }

    @Test
    public void testIsWithinQuotaLocked_TimingSession() {
        setDischarging();
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        setDeviceConfigInt(QcConstants.KEY_MAX_SESSION_COUNT_RARE, 3);
        setDeviceConfigInt(QcConstants.KEY_MAX_SESSION_COUNT_FREQUENT, 4);
        setDeviceConfigInt(QcConstants.KEY_MAX_SESSION_COUNT_WORKING, 5);
        setDeviceConfigInt(QcConstants.KEY_MAX_SESSION_COUNT_ACTIVE, 6);

        for (int i = 0; i < 7; ++i) {
            mQuotaController.saveTimingSession(0, "com.android.test",
                    createTimingSession(now - ((10 - i) * MINUTE_IN_MILLIS), 30 * SECOND_IN_MILLIS,
                            2), false);

            synchronized (mQuotaController.mLock) {
                mQuotaController.incrementJobCountLocked(0, "com.android.test", 2);

                assertEquals("Rare has incorrect quota status with " + (i + 1) + " sessions",
                        i < 2,
                        mQuotaController.isWithinQuotaLocked(0, "com.android.test", RARE_INDEX));
                assertEquals("Frequent has incorrect quota status with " + (i + 1) + " sessions",
                        i < 3,
                        mQuotaController.isWithinQuotaLocked(
                                0, "com.android.test", FREQUENT_INDEX));
                assertEquals("Working has incorrect quota status with " + (i + 1) + " sessions",
                        i < 4,
                        mQuotaController.isWithinQuotaLocked(0, "com.android.test", WORKING_INDEX));
                assertEquals("Active has incorrect quota status with " + (i + 1) + " sessions",
                        i < 5,
                        mQuotaController.isWithinQuotaLocked(0, "com.android.test", ACTIVE_INDEX));
            }
        }
    }

    @Test
    public void testIsWithinEJQuotaLocked_NeverApp() {
        JobStatus js = createExpeditedJobStatus("testIsWithinEJQuotaLocked_NeverApp", 1);
        setStandbyBucket(NEVER_INDEX, js);
        synchronized (mQuotaController.mLock) {
            assertFalse(mQuotaController.isWithinEJQuotaLocked(js));
        }
    }

    @Test
    public void testIsWithinEJQuotaLocked_Charging() {
        setCharging();
        JobStatus js = createExpeditedJobStatus("testIsWithinEJQuotaLocked_Charging", 1);
        synchronized (mQuotaController.mLock) {
            assertTrue(mQuotaController.isWithinEJQuotaLocked(js));
        }
    }

    @Test
    public void testIsWithinEJQuotaLocked_UnderDuration() {
        setDischarging();
        JobStatus js = createExpeditedJobStatus("testIsWithinEJQuotaLocked_UnderDuration", 1);
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - (HOUR_IN_MILLIS), 3 * MINUTE_IN_MILLIS, 5), true);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - (5 * MINUTE_IN_MILLIS), 3 * MINUTE_IN_MILLIS, 5), true);
        synchronized (mQuotaController.mLock) {
            assertTrue(mQuotaController.isWithinEJQuotaLocked(js));
        }
    }

    @Test
    public void testIsWithinEJQuotaLocked_OverDuration() {
        setDischarging();
        JobStatus js = createExpeditedJobStatus("testIsWithinEJQuotaLocked_OverDuration", 1);
        setStandbyBucket(FREQUENT_INDEX, js);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_FREQUENT_MS, 10 * MINUTE_IN_MILLIS);
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - (HOUR_IN_MILLIS), 3 * MINUTE_IN_MILLIS, 5), true);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - (30 * MINUTE_IN_MILLIS), 3 * MINUTE_IN_MILLIS, 5), true);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - (5 * MINUTE_IN_MILLIS), 4 * MINUTE_IN_MILLIS, 5), true);
        synchronized (mQuotaController.mLock) {
            assertFalse(mQuotaController.isWithinEJQuotaLocked(js));
        }
    }

    @Test
    public void testIsWithinEJQuotaLocked_TimingSession() {
        setDischarging();
        setProcessState(ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND);
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_ACTIVE_MS, 20 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_WORKING_MS, 15 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_FREQUENT_MS, 13 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_RARE_MS, 10 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_RESTRICTED_MS, 8 * MINUTE_IN_MILLIS);

        JobStatus js = createExpeditedJobStatus("testIsWithinEJQuotaLocked_TimingSession", 1);
        for (int i = 0; i < 25; ++i) {
            mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                    createTimingSession(now - ((60 - i) * MINUTE_IN_MILLIS), MINUTE_IN_MILLIS,
                            2), true);

            synchronized (mQuotaController.mLock) {
                setStandbyBucket(ACTIVE_INDEX, js);
                assertEquals("Active has incorrect quota status with " + (i + 1) + " sessions",
                        i < 19, mQuotaController.isWithinEJQuotaLocked(js));

                setStandbyBucket(WORKING_INDEX, js);
                assertEquals("Working has incorrect quota status with " + (i + 1) + " sessions",
                        i < 14, mQuotaController.isWithinEJQuotaLocked(js));

                setStandbyBucket(FREQUENT_INDEX, js);
                assertEquals("Frequent has incorrect quota status with " + (i + 1) + " sessions",
                        i < 12, mQuotaController.isWithinEJQuotaLocked(js));

                setStandbyBucket(RARE_INDEX, js);
                assertEquals("Rare has incorrect quota status with " + (i + 1) + " sessions",
                        i < 9, mQuotaController.isWithinEJQuotaLocked(js));

                setStandbyBucket(RESTRICTED_INDEX, js);
                assertEquals("Restricted has incorrect quota status with " + (i + 1) + " sessions",
                        i < 7, mQuotaController.isWithinEJQuotaLocked(js));
            }
        }
    }

    /**
     * Tests that Timers properly track sessions when an app is added and removed from the temp
     * allowlist.
     */
    @Test
    public void testIsWithinEJQuotaLocked_TempAllowlisting() {
        setDischarging();
        JobStatus js = createExpeditedJobStatus("testIsWithinEJQuotaLocked_TempAllowlisting", 1);
        setStandbyBucket(FREQUENT_INDEX, js);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_FREQUENT_MS, 10 * MINUTE_IN_MILLIS);
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - (HOUR_IN_MILLIS), 3 * MINUTE_IN_MILLIS, 5), true);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - (30 * MINUTE_IN_MILLIS), 3 * MINUTE_IN_MILLIS, 5), true);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - (5 * MINUTE_IN_MILLIS), 4 * MINUTE_IN_MILLIS, 5), true);
        synchronized (mQuotaController.mLock) {
            assertFalse(mQuotaController.isWithinEJQuotaLocked(js));
        }

        setProcessState(ActivityManager.PROCESS_STATE_RECEIVER);
        final long gracePeriodMs = 15 * SECOND_IN_MILLIS;
        setDeviceConfigLong(QcConstants.KEY_EJ_GRACE_PERIOD_TEMP_ALLOWLIST_MS, gracePeriodMs);
        Handler handler = mQuotaController.getHandler();
        spyOn(handler);

        // Apps on the temp allowlist should be able to schedule & start EJs, even if they're out
        // of quota (as long as they are in the temp allowlist grace period).
        mTempAllowlistListener.onAppAdded(mSourceUid);
        synchronized (mQuotaController.mLock) {
            assertTrue(mQuotaController.isWithinEJQuotaLocked(js));
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mTempAllowlistListener.onAppRemoved(mSourceUid);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        // Still in grace period
        synchronized (mQuotaController.mLock) {
            assertTrue(mQuotaController.isWithinEJQuotaLocked(js));
        }
        advanceElapsedClock(6 * SECOND_IN_MILLIS);
        // Out of grace period.
        synchronized (mQuotaController.mLock) {
            assertFalse(mQuotaController.isWithinEJQuotaLocked(js));
        }
    }

    @Test
    public void testIsWithinEJQuotaLocked_TempAllowlisting_Restricted() {
        setDischarging();
        JobStatus js = createExpeditedJobStatus("testIsWithinEJQuotaLocked_TempAllowlisting_Restricted", 1);
        setStandbyBucket(RESTRICTED_INDEX, js);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_FREQUENT_MS, 10 * MINUTE_IN_MILLIS);
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - (HOUR_IN_MILLIS), 3 * MINUTE_IN_MILLIS, 5), true);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - (30 * MINUTE_IN_MILLIS), 3 * MINUTE_IN_MILLIS, 5), true);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - (5 * MINUTE_IN_MILLIS), 4 * MINUTE_IN_MILLIS, 5), true);
        synchronized (mQuotaController.mLock) {
            assertFalse(mQuotaController.isWithinEJQuotaLocked(js));
        }

        setProcessState(ActivityManager.PROCESS_STATE_RECEIVER);
        final long gracePeriodMs = 15 * SECOND_IN_MILLIS;
        setDeviceConfigLong(QcConstants.KEY_EJ_GRACE_PERIOD_TEMP_ALLOWLIST_MS, gracePeriodMs);
        Handler handler = mQuotaController.getHandler();
        spyOn(handler);

        // The temp allowlist should not enable RESTRICTED apps' to schedule & start EJs if they're
        // out of quota.
        mTempAllowlistListener.onAppAdded(mSourceUid);
        synchronized (mQuotaController.mLock) {
            assertFalse(mQuotaController.isWithinEJQuotaLocked(js));
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mTempAllowlistListener.onAppRemoved(mSourceUid);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        // Still in grace period
        synchronized (mQuotaController.mLock) {
            assertFalse(mQuotaController.isWithinEJQuotaLocked(js));
        }
        advanceElapsedClock(6 * SECOND_IN_MILLIS);
        // Out of grace period.
        synchronized (mQuotaController.mLock) {
            assertFalse(mQuotaController.isWithinEJQuotaLocked(js));
        }
    }

    /**
     * Tests that Timers properly track sessions when an app becomes top and is closed.
     */
    @Test
    public void testIsWithinEJQuotaLocked_TopApp() {
        setDischarging();
        JobStatus js = createExpeditedJobStatus("testIsWithinEJQuotaLocked_TopApp", 1);
        setStandbyBucket(FREQUENT_INDEX, js);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_FREQUENT_MS, 10 * MINUTE_IN_MILLIS);
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - (HOUR_IN_MILLIS), 3 * MINUTE_IN_MILLIS, 5), true);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - (30 * MINUTE_IN_MILLIS), 3 * MINUTE_IN_MILLIS, 5), true);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - (5 * MINUTE_IN_MILLIS), 4 * MINUTE_IN_MILLIS, 5), true);
        setProcessState(ActivityManager.PROCESS_STATE_RECEIVER);
        synchronized (mQuotaController.mLock) {
            assertFalse(mQuotaController.isWithinEJQuotaLocked(js));
        }

        final long gracePeriodMs = 15 * SECOND_IN_MILLIS;
        setDeviceConfigLong(QcConstants.KEY_EJ_GRACE_PERIOD_TOP_APP_MS, gracePeriodMs);
        Handler handler = mQuotaController.getHandler();
        spyOn(handler);

        // Apps on top should be able to schedule & start EJs, even if they're out
        // of quota (as long as they are in the top grace period).
        setProcessState(ActivityManager.PROCESS_STATE_TOP);
        synchronized (mQuotaController.mLock) {
            assertTrue(mQuotaController.isWithinEJQuotaLocked(js));
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        setProcessState(ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        // Still in grace period
        synchronized (mQuotaController.mLock) {
            assertTrue(mQuotaController.isWithinEJQuotaLocked(js));
        }
        advanceElapsedClock(6 * SECOND_IN_MILLIS);
        // Out of grace period.
        synchronized (mQuotaController.mLock) {
            assertFalse(mQuotaController.isWithinEJQuotaLocked(js));
        }
    }

    @Test
    public void testMaybeScheduleCleanupAlarmLocked() {
        // No sessions saved yet.
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleCleanupAlarmLocked();
        }
        verify(mAlarmManager, never()).set(anyInt(), anyLong(), eq(TAG_CLEANUP), any(), any());

        // Test with only one timing session saved.
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        final long end = now - (6 * HOUR_IN_MILLIS - 5 * MINUTE_IN_MILLIS);
        mQuotaController.saveTimingSession(0, "com.android.test",
                new TimingSession(now - 6 * HOUR_IN_MILLIS, end, 1), false);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleCleanupAlarmLocked();
        }
        verify(mAlarmManager, times(1))
                .set(anyInt(), eq(end + 24 * HOUR_IN_MILLIS), eq(TAG_CLEANUP), any(), any());

        // Test with new (more recent) timing sessions saved. AlarmManger shouldn't be called again.
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - 3 * HOUR_IN_MILLIS, MINUTE_IN_MILLIS, 1), false);
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - HOUR_IN_MILLIS, 3 * MINUTE_IN_MILLIS, 1), false);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleCleanupAlarmLocked();
        }
        verify(mAlarmManager, times(1))
                .set(anyInt(), eq(end + 24 * HOUR_IN_MILLIS), eq(TAG_CLEANUP), any(), any());
    }

    @Test
    public void testMaybeScheduleStartAlarmLocked_Active() {
        // saveTimingSession calls maybeScheduleCleanupAlarmLocked which interferes with these tests
        // because it schedules an alarm too. Prevent it from doing so.
        spyOn(mQuotaController);
        doNothing().when(mQuotaController).maybeScheduleCleanupAlarmLocked();

        // Active window size is 10 minutes.
        final int standbyBucket = ACTIVE_INDEX;
        setProcessState(ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND);

        JobStatus jobStatus = createJobStatus("testMaybeScheduleStartAlarmLocked_Active", 1);
        setStandbyBucket(standbyBucket, jobStatus);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);
        }

        // No sessions saved yet.
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, standbyBucket);
        }
        verify(mAlarmManager, timeout(1000).times(0)).setWindow(
                anyInt(), anyLong(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        // Test with timing sessions out of window but still under max execution limit.
        final long expectedAlarmTime =
                (now - 18 * HOUR_IN_MILLIS) + 24 * HOUR_IN_MILLIS + mQcConstants.IN_QUOTA_BUFFER_MS;
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 18 * HOUR_IN_MILLIS, HOUR_IN_MILLIS, 1), false);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 12 * HOUR_IN_MILLIS, HOUR_IN_MILLIS, 1), false);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 7 * HOUR_IN_MILLIS, HOUR_IN_MILLIS, 1), false);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, standbyBucket);
        }
        verify(mAlarmManager, timeout(1000).times(0)).setWindow(
                anyInt(), anyLong(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 2 * HOUR_IN_MILLIS, 55 * MINUTE_IN_MILLIS, 1), false);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, standbyBucket);
        }
        verify(mAlarmManager, timeout(1000).times(0)).setWindow(
                anyInt(), anyLong(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobStatus);
        }
        advanceElapsedClock(5 * MINUTE_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            // Timer has only been going for 5 minutes in the past 10 minutes, which is under the
            // window size limit, but the total execution time for the past 24 hours is 6 hours, so
            // the job no longer has quota.
            assertEquals(0, mQuotaController.getRemainingExecutionTimeLocked(jobStatus));
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, standbyBucket);
        }
        verify(mAlarmManager, timeout(1000).times(1)).setWindow(
                anyInt(), eq(expectedAlarmTime), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());
    }

    @Test
    public void testMaybeScheduleStartAlarmLocked_WorkingSet() {
        // saveTimingSession calls maybeScheduleCleanupAlarmLocked which interferes with these tests
        // because it schedules an alarm too. Prevent it from doing so.
        spyOn(mQuotaController);
        doNothing().when(mQuotaController).maybeScheduleCleanupAlarmLocked();

        // Working set window size is 2 hours.
        final int standbyBucket = WORKING_INDEX;

        JobStatus jobStatus = createJobStatus("testMaybeScheduleStartAlarmLocked_WorkingSet", 1);
        setStandbyBucket(standbyBucket, jobStatus);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);
            // No sessions saved yet.
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, standbyBucket);
        }
        verify(mAlarmManager, timeout(1000).times(0)).setWindow(
                anyInt(), anyLong(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Test with timing sessions out of window.
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 10 * HOUR_IN_MILLIS, 5 * MINUTE_IN_MILLIS, 1), false);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, standbyBucket);
        }
        verify(mAlarmManager, timeout(1000).times(0)).setWindow(
                anyInt(), anyLong(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Test with timing sessions in window but still in quota.
        final long end = now - (2 * HOUR_IN_MILLIS - 5 * MINUTE_IN_MILLIS);
        // Counting backwards, the quota will come back one minute before the end.
        final long expectedAlarmTime =
                end - MINUTE_IN_MILLIS + 2 * HOUR_IN_MILLIS + mQcConstants.IN_QUOTA_BUFFER_MS;
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                new TimingSession(now - 2 * HOUR_IN_MILLIS, end, 1), false);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, standbyBucket);
        }
        verify(mAlarmManager, timeout(1000).times(0)).setWindow(
                anyInt(), anyLong(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Add some more sessions, but still in quota.
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - HOUR_IN_MILLIS, MINUTE_IN_MILLIS, 1), false);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - (50 * MINUTE_IN_MILLIS), 3 * MINUTE_IN_MILLIS, 1), false);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, standbyBucket);
        }
        verify(mAlarmManager, timeout(1000).times(0)).setWindow(
                anyInt(), anyLong(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Test when out of quota.
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 30 * MINUTE_IN_MILLIS, 5 * MINUTE_IN_MILLIS, 1), false);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, standbyBucket);
        }
        verify(mAlarmManager, timeout(1000).times(1)).setWindow(
                anyInt(), eq(expectedAlarmTime), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Alarm already scheduled, so make sure it's not scheduled again.
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, standbyBucket);
        }
        verify(mAlarmManager, timeout(1000).times(1)).setWindow(
                anyInt(), eq(expectedAlarmTime), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());
    }

    @Test
    public void testMaybeScheduleStartAlarmLocked_Frequent() {
        // saveTimingSession calls maybeScheduleCleanupAlarmLocked which interferes with these tests
        // because it schedules an alarm too. Prevent it from doing so.
        spyOn(mQuotaController);
        doNothing().when(mQuotaController).maybeScheduleCleanupAlarmLocked();

        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(
                    createJobStatus("testMaybeScheduleStartAlarmLocked_Frequent", 1), null);
        }

        // Frequent window size is 8 hours.
        final int standbyBucket = FREQUENT_INDEX;

        // No sessions saved yet.
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, standbyBucket);
        }
        verify(mAlarmManager, timeout(1000).times(0)).setWindow(
                anyInt(), anyLong(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Test with timing sessions out of window.
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 10 * HOUR_IN_MILLIS, 5 * MINUTE_IN_MILLIS, 1), false);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, standbyBucket);
        }
        verify(mAlarmManager, timeout(1000).times(0)).setWindow(
                anyInt(), anyLong(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Test with timing sessions in window but still in quota.
        final long start = now - (6 * HOUR_IN_MILLIS);
        final long expectedAlarmTime = start + 8 * HOUR_IN_MILLIS + mQcConstants.IN_QUOTA_BUFFER_MS;
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(start, 5 * MINUTE_IN_MILLIS, 1), false);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, standbyBucket);
        }
        verify(mAlarmManager, timeout(1000).times(0)).setWindow(
                anyInt(), anyLong(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Add some more sessions, but still in quota.
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 3 * HOUR_IN_MILLIS, MINUTE_IN_MILLIS, 1), false);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - HOUR_IN_MILLIS, 3 * MINUTE_IN_MILLIS, 1), false);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, standbyBucket);
        }
        verify(mAlarmManager, timeout(1000).times(0)).setWindow(
                anyInt(), anyLong(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Test when out of quota.
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - HOUR_IN_MILLIS, MINUTE_IN_MILLIS, 1), false);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, standbyBucket);
        }
        verify(mAlarmManager, timeout(1000).times(1)).setWindow(
                anyInt(), eq(expectedAlarmTime), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Alarm already scheduled, so make sure it's not scheduled again.
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, standbyBucket);
        }
        verify(mAlarmManager, timeout(1000).times(1)).setWindow(
                anyInt(), eq(expectedAlarmTime), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());
    }

    /**
     * Test that QC handles invalid cases where an app is in the NEVER bucket but has still run
     * jobs.
     */
    @Test
    public void testMaybeScheduleStartAlarmLocked_Never_EffectiveNotNever() {
        // saveTimingSession calls maybeScheduleCleanupAlarmLocked which interferes with these tests
        // because it schedules an alarm too. Prevent it from doing so.
        spyOn(mQuotaController);
        doNothing().when(mQuotaController).maybeScheduleCleanupAlarmLocked();

        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(
                    createJobStatus("testMaybeScheduleStartAlarmLocked_Never", 1), null);
        }

        // The app is really in the NEVER bucket but is elevated somehow (eg via uidActive).
        setStandbyBucket(NEVER_INDEX);
        final int effectiveStandbyBucket = FREQUENT_INDEX;

        // No sessions saved yet.
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, effectiveStandbyBucket);
        }
        verify(mAlarmManager, timeout(1000).times(0)).setWindow(
                anyInt(), anyLong(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Test with timing sessions out of window.
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 10 * HOUR_IN_MILLIS, 5 * MINUTE_IN_MILLIS, 1), false);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, effectiveStandbyBucket);
        }
        verify(mAlarmManager, timeout(1000).times(0)).setWindow(
                anyInt(), anyLong(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Test with timing sessions in window but still in quota.
        final long start = now - (6 * HOUR_IN_MILLIS);
        final long expectedAlarmTime = start + 8 * HOUR_IN_MILLIS + mQcConstants.IN_QUOTA_BUFFER_MS;
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(start, 5 * MINUTE_IN_MILLIS, 1), false);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, effectiveStandbyBucket);
        }
        verify(mAlarmManager, timeout(1000).times(0)).setWindow(
                anyInt(), anyLong(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Add some more sessions, but still in quota.
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 3 * HOUR_IN_MILLIS, MINUTE_IN_MILLIS, 1), false);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - HOUR_IN_MILLIS, 3 * MINUTE_IN_MILLIS, 1), false);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, effectiveStandbyBucket);
        }
        verify(mAlarmManager, timeout(1000).times(0)).setWindow(
                anyInt(), anyLong(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Test when out of quota.
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - HOUR_IN_MILLIS, MINUTE_IN_MILLIS, 1), false);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, effectiveStandbyBucket);
        }
        verify(mAlarmManager, timeout(1000).times(1)).setWindow(
                anyInt(), eq(expectedAlarmTime), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Alarm already scheduled, so make sure it's not scheduled again.
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, effectiveStandbyBucket);
        }
        verify(mAlarmManager, timeout(1000).times(1)).setWindow(
                anyInt(), eq(expectedAlarmTime), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());
    }

    @Test
    public void testMaybeScheduleStartAlarmLocked_Rare() {
        // saveTimingSession calls maybeScheduleCleanupAlarmLocked which interferes with these tests
        // because it schedules an alarm too. Prevent it from doing so.
        spyOn(mQuotaController);
        doNothing().when(mQuotaController).maybeScheduleCleanupAlarmLocked();

        // Rare window size is 24 hours.
        final int standbyBucket = RARE_INDEX;

        JobStatus jobStatus = createJobStatus("testMaybeScheduleStartAlarmLocked_Rare", 1);
        setStandbyBucket(standbyBucket, jobStatus);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);
        }

        // Prevent timing session throttling from affecting the test.
        setDeviceConfigInt(QcConstants.KEY_MAX_SESSION_COUNT_RARE, 50);

        // No sessions saved yet.
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, standbyBucket);
        }
        verify(mAlarmManager, timeout(1000).times(0)).setWindow(
                anyInt(), anyLong(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Test with timing sessions out of window.
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 25 * HOUR_IN_MILLIS, 5 * MINUTE_IN_MILLIS, 1), false);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", standbyBucket);
        }
        verify(mAlarmManager, timeout(1000).times(0)).setWindow(
                anyInt(), anyLong(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Test with timing sessions in window but still in quota.
        final long start = now - (6 * HOUR_IN_MILLIS);
        // Counting backwards, the first minute in the session is over the allowed time, so it
        // needs to be excluded.
        final long expectedAlarmTime =
                start + MINUTE_IN_MILLIS + 24 * HOUR_IN_MILLIS
                        + mQcConstants.IN_QUOTA_BUFFER_MS;
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(start, 5 * MINUTE_IN_MILLIS, 1), false);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, standbyBucket);
        }
        verify(mAlarmManager, timeout(1000).times(0)).setWindow(
                anyInt(), anyLong(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Add some more sessions, but still in quota.
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 3 * HOUR_IN_MILLIS, MINUTE_IN_MILLIS, 1), false);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - HOUR_IN_MILLIS, 3 * MINUTE_IN_MILLIS, 1), false);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, standbyBucket);
        }
        verify(mAlarmManager, timeout(1000).times(0)).setWindow(
                anyInt(), anyLong(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Test when out of quota.
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - HOUR_IN_MILLIS, 2 * MINUTE_IN_MILLIS, 1), false);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, standbyBucket);
        }
        verify(mAlarmManager, timeout(1000).times(1)).setWindow(
                anyInt(), eq(expectedAlarmTime), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Alarm already scheduled, so make sure it's not scheduled again.
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, standbyBucket);
        }
        verify(mAlarmManager, timeout(1000).times(1)).setWindow(
                anyInt(), eq(expectedAlarmTime), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());
    }

    /** Tests that the start alarm is properly rescheduled if the app's bucket is changed. */
    @Test
    public void testMaybeScheduleStartAlarmLocked_BucketChange() {
        // saveTimingSession calls maybeScheduleCleanupAlarmLocked which interferes with these tests
        // because it schedules an alarm too. Prevent it from doing so.
        spyOn(mQuotaController);
        doNothing().when(mQuotaController).maybeScheduleCleanupAlarmLocked();

        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();

        // Affects rare bucket
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 12 * HOUR_IN_MILLIS, 9 * MINUTE_IN_MILLIS, 3), false);
        // Affects frequent and rare buckets
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 4 * HOUR_IN_MILLIS, 4 * MINUTE_IN_MILLIS, 3), false);
        // Affects working, frequent, and rare buckets
        final long outOfQuotaTime = now - HOUR_IN_MILLIS;
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(outOfQuotaTime, 7 * MINUTE_IN_MILLIS, 10), false);
        // Affects all buckets
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 5 * MINUTE_IN_MILLIS, 3 * MINUTE_IN_MILLIS, 3), false);

        InOrder inOrder = inOrder(mAlarmManager);

        JobStatus jobStatus = createJobStatus("testMaybeScheduleStartAlarmLocked_BucketChange", 1);

        // Start in ACTIVE bucket.
        setStandbyBucket(ACTIVE_INDEX, jobStatus);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, ACTIVE_INDEX);
        }
        inOrder.verify(mAlarmManager, timeout(1000).times(0))
                .setWindow(anyInt(), anyLong(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());
        inOrder.verify(mAlarmManager, timeout(1000).times(0))
                .cancel(any(AlarmManager.OnAlarmListener.class));

        // And down from there.
        final long expectedWorkingAlarmTime =
                outOfQuotaTime + (2 * HOUR_IN_MILLIS)
                        + mQcConstants.IN_QUOTA_BUFFER_MS;
        setStandbyBucket(WORKING_INDEX, jobStatus);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, WORKING_INDEX);
        }
        inOrder.verify(mAlarmManager, timeout(1000).times(1)).setWindow(
                anyInt(), eq(expectedWorkingAlarmTime), anyLong(),
                eq(TAG_QUOTA_CHECK), any(), any());

        final long expectedFrequentAlarmTime =
                outOfQuotaTime + (8 * HOUR_IN_MILLIS)
                        + mQcConstants.IN_QUOTA_BUFFER_MS;
        setStandbyBucket(FREQUENT_INDEX, jobStatus);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, FREQUENT_INDEX);
        }
        inOrder.verify(mAlarmManager, timeout(1000).times(1)).setWindow(
                anyInt(), eq(expectedFrequentAlarmTime), anyLong(),
                eq(TAG_QUOTA_CHECK), any(), any());

        final long expectedRareAlarmTime =
                outOfQuotaTime + (24 * HOUR_IN_MILLIS)
                        + mQcConstants.IN_QUOTA_BUFFER_MS;
        setStandbyBucket(RARE_INDEX, jobStatus);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, RARE_INDEX);
        }
        inOrder.verify(mAlarmManager, timeout(1000).times(1)).setWindow(
                anyInt(), eq(expectedRareAlarmTime), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // And back up again.
        setStandbyBucket(FREQUENT_INDEX, jobStatus);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, FREQUENT_INDEX);
        }
        inOrder.verify(mAlarmManager, timeout(1000).times(1)).setWindow(
                anyInt(), eq(expectedFrequentAlarmTime), anyLong(),
                eq(TAG_QUOTA_CHECK), any(), any());

        setStandbyBucket(WORKING_INDEX, jobStatus);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, WORKING_INDEX);
        }
        inOrder.verify(mAlarmManager, timeout(1000).times(1)).setWindow(
                anyInt(), eq(expectedWorkingAlarmTime), anyLong(),
                eq(TAG_QUOTA_CHECK), any(), any());

        setStandbyBucket(ACTIVE_INDEX, jobStatus);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, ACTIVE_INDEX);
        }
        inOrder.verify(mAlarmManager, timeout(1000).times(0))
                .setWindow(anyInt(), anyLong(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());
        inOrder.verify(mAlarmManager, timeout(1000).times(1))
                .cancel(any(AlarmManager.OnAlarmListener.class));
    }

    @Test
    public void testMaybeScheduleStartAlarmLocked_JobCount_RateLimitingWindow() {
        // Set rate limiting period different from allowed time to confirm code sets based on
        // the former.
        final int standbyBucket = WORKING_INDEX;
        setDeviceConfigLong(QcConstants.KEY_ALLOWED_TIME_PER_PERIOD_WORKING_MS,
                10 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_RATE_LIMITING_WINDOW_MS, 5 * MINUTE_IN_MILLIS);

        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();

        JobStatus jobStatus = createJobStatus("testMaybeScheduleStartAlarmLocked", 1);
        setStandbyBucket(standbyBucket, jobStatus);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);
        }

        ExecutionStats stats;
        synchronized (mQuotaController.mLock) {
            stats = mQuotaController.getExecutionStatsLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, standbyBucket);
        }
        stats.jobCountInRateLimitingWindow =
                mQcConstants.MAX_JOB_COUNT_PER_RATE_LIMITING_WINDOW + 2;

        // Invalid time in the past, so the count shouldn't be used.
        stats.jobRateLimitExpirationTimeElapsed = now - 5 * MINUTE_IN_MILLIS / 2;
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, standbyBucket);
        }
        verify(mAlarmManager, timeout(1000).times(0)).setWindow(
                anyInt(), anyLong(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Valid time in the future, so the count should be used.
        stats.jobRateLimitExpirationTimeElapsed = now + 5 * MINUTE_IN_MILLIS / 2;
        final long expectedWorkingAlarmTime = stats.jobRateLimitExpirationTimeElapsed;
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, standbyBucket);
        }
        verify(mAlarmManager, timeout(1000).times(1)).setWindow(
                anyInt(), eq(expectedWorkingAlarmTime), anyLong(),
                eq(TAG_QUOTA_CHECK), any(), any());
    }

    /**
     * Tests that the start alarm is properly rescheduled if the earliest session that contributes
     * to the app being out of quota contributes less than the quota buffer time.
     */
    @Test
    public void testMaybeScheduleStartAlarmLocked_SmallRollingQuota_DefaultValues() {
        // Use the default values
        runTestMaybeScheduleStartAlarmLocked_SmallRollingQuota_AllowedTimeCheck();
        mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE).clear();
        runTestMaybeScheduleStartAlarmLocked_SmallRollingQuota_MaxTimeCheck();
    }

    @Test
    public void testMaybeScheduleStartAlarmLocked_SmallRollingQuota_UpdatedBufferSize() {
        // Make sure any new value is used correctly.
        setDeviceConfigLong(QcConstants.KEY_IN_QUOTA_BUFFER_MS,
                mQcConstants.IN_QUOTA_BUFFER_MS * 2);

        runTestMaybeScheduleStartAlarmLocked_SmallRollingQuota_AllowedTimeCheck();
        mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE).clear();
        runTestMaybeScheduleStartAlarmLocked_SmallRollingQuota_MaxTimeCheck();
    }

    @Test
    public void testMaybeScheduleStartAlarmLocked_SmallRollingQuota_UpdatedAllowedTime() {
        // Make sure any new value is used correctly.
        setDeviceConfigLong(QcConstants.KEY_ALLOWED_TIME_PER_PERIOD_WORKING_MS,
                mQcConstants.ALLOWED_TIME_PER_PERIOD_WORKING_MS / 2);

        runTestMaybeScheduleStartAlarmLocked_SmallRollingQuota_AllowedTimeCheck();
        mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE).clear();
        runTestMaybeScheduleStartAlarmLocked_SmallRollingQuota_MaxTimeCheck();
    }

    @Test
    public void testMaybeScheduleStartAlarmLocked_SmallRollingQuota_UpdatedMaxTime() {
        // Make sure any new value is used correctly.
        setDeviceConfigLong(QcConstants.KEY_MAX_EXECUTION_TIME_MS,
                mQcConstants.MAX_EXECUTION_TIME_MS / 2);

        runTestMaybeScheduleStartAlarmLocked_SmallRollingQuota_AllowedTimeCheck();
        mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE).clear();
        runTestMaybeScheduleStartAlarmLocked_SmallRollingQuota_MaxTimeCheck();
    }

    @Test
    public void testMaybeScheduleStartAlarmLocked_SmallRollingQuota_UpdatedEverything() {
        // Make sure any new value is used correctly.
        setDeviceConfigLong(QcConstants.KEY_IN_QUOTA_BUFFER_MS,
                mQcConstants.IN_QUOTA_BUFFER_MS * 2);
        setDeviceConfigLong(QcConstants.KEY_ALLOWED_TIME_PER_PERIOD_WORKING_MS,
                mQcConstants.ALLOWED_TIME_PER_PERIOD_WORKING_MS / 2);
        setDeviceConfigLong(QcConstants.KEY_MAX_EXECUTION_TIME_MS,
                mQcConstants.MAX_EXECUTION_TIME_MS / 2);

        runTestMaybeScheduleStartAlarmLocked_SmallRollingQuota_AllowedTimeCheck();
        mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE).clear();
        runTestMaybeScheduleStartAlarmLocked_SmallRollingQuota_MaxTimeCheck();
    }

    private void runTestMaybeScheduleStartAlarmLocked_SmallRollingQuota_AllowedTimeCheck() {
        // saveTimingSession calls maybeScheduleCleanupAlarmLocked which interferes with these tests
        // because it schedules an alarm too. Prevent it from doing so.
        spyOn(mQuotaController);
        doNothing().when(mQuotaController).maybeScheduleCleanupAlarmLocked();

        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(
                    createJobStatus("testMaybeScheduleStartAlarmLocked", 1), null);
        }

        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        // Working set window size is 2 hours.
        final int standbyBucket = WORKING_INDEX;
        final long contributionMs = mQcConstants.IN_QUOTA_BUFFER_MS / 2;
        final long remainingTimeMs =
                mQcConstants.ALLOWED_TIME_PER_PERIOD_WORKING_MS - contributionMs;

        // Session straddles edge of bucket window. Only the contribution should be counted towards
        // the quota.
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - (2 * HOUR_IN_MILLIS + 3 * MINUTE_IN_MILLIS),
                        3 * MINUTE_IN_MILLIS + contributionMs, 3), false);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - HOUR_IN_MILLIS, remainingTimeMs, 2), false);
        // Expected alarm time should be when the app will have QUOTA_BUFFER_MS time of quota, which
        // is 2 hours + (QUOTA_BUFFER_MS - contributionMs) after the start of the second session.
        final long expectedAlarmTime = now - HOUR_IN_MILLIS + 2 * HOUR_IN_MILLIS
                + (mQcConstants.IN_QUOTA_BUFFER_MS - contributionMs);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, standbyBucket);
        }
        verify(mAlarmManager, timeout(1000).times(1)).setWindow(
                anyInt(), eq(expectedAlarmTime), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());
    }

    private void runTestMaybeScheduleStartAlarmLocked_SmallRollingQuota_MaxTimeCheck() {
        // saveTimingSession calls maybeScheduleCleanupAlarmLocked which interferes with these tests
        // because it schedules an alarm too. Prevent it from doing so.
        spyOn(mQuotaController);
        doNothing().when(mQuotaController).maybeScheduleCleanupAlarmLocked();

        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(
                    createJobStatus("testMaybeScheduleStartAlarmLocked", 1), null);
        }

        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        // Working set window size is 2 hours.
        final int standbyBucket = WORKING_INDEX;
        final long contributionMs = mQcConstants.IN_QUOTA_BUFFER_MS / 2;
        final long remainingTimeMs = mQcConstants.MAX_EXECUTION_TIME_MS - contributionMs;

        // Session straddles edge of 24 hour window. Only the contribution should be counted towards
        // the quota.
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - (24 * HOUR_IN_MILLIS + 3 * MINUTE_IN_MILLIS),
                        3 * MINUTE_IN_MILLIS + contributionMs, 3), false);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 20 * HOUR_IN_MILLIS, remainingTimeMs, 300), false);
        // Expected alarm time should be when the app will have QUOTA_BUFFER_MS time of quota, which
        // is 24 hours + (QUOTA_BUFFER_MS - contributionMs) after the start of the second session.
        final long expectedAlarmTime = now - 20 * HOUR_IN_MILLIS
                + 24 * HOUR_IN_MILLIS
                + (mQcConstants.IN_QUOTA_BUFFER_MS - contributionMs);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, standbyBucket);
        }
        verify(mAlarmManager, timeout(1000).times(1)).setWindow(
                anyInt(), eq(expectedAlarmTime), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());
    }

    @Test
    public void testConstantsUpdating_ValidValues() {
        setDeviceConfigLong(QcConstants.KEY_ALLOWED_TIME_PER_PERIOD_EXEMPTED_MS,
                8 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_ALLOWED_TIME_PER_PERIOD_ACTIVE_MS,
                5 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_ALLOWED_TIME_PER_PERIOD_WORKING_MS,
                7 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_ALLOWED_TIME_PER_PERIOD_FREQUENT_MS,
                2 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_ALLOWED_TIME_PER_PERIOD_RARE_MS, 4 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_ALLOWED_TIME_PER_PERIOD_RESTRICTED_MS,
                11 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_IN_QUOTA_BUFFER_MS, 2 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_WINDOW_SIZE_EXEMPTED_MS, 99 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_WINDOW_SIZE_ACTIVE_MS, 15 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_WINDOW_SIZE_WORKING_MS, 30 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_WINDOW_SIZE_FREQUENT_MS, 45 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_WINDOW_SIZE_RARE_MS, 60 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_WINDOW_SIZE_RESTRICTED_MS, 120 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_MAX_EXECUTION_TIME_MS, 3 * HOUR_IN_MILLIS);
        setDeviceConfigInt(QcConstants.KEY_MAX_JOB_COUNT_EXEMPTED, 6000);
        setDeviceConfigInt(QcConstants.KEY_MAX_JOB_COUNT_ACTIVE, 5000);
        setDeviceConfigInt(QcConstants.KEY_MAX_JOB_COUNT_WORKING, 4000);
        setDeviceConfigInt(QcConstants.KEY_MAX_JOB_COUNT_FREQUENT, 3000);
        setDeviceConfigInt(QcConstants.KEY_MAX_JOB_COUNT_RARE, 2000);
        setDeviceConfigInt(QcConstants.KEY_MAX_JOB_COUNT_RESTRICTED, 2000);
        setDeviceConfigLong(QcConstants.KEY_RATE_LIMITING_WINDOW_MS, 15 * MINUTE_IN_MILLIS);
        setDeviceConfigInt(QcConstants.KEY_MAX_JOB_COUNT_PER_RATE_LIMITING_WINDOW, 500);
        setDeviceConfigInt(QcConstants.KEY_MAX_SESSION_COUNT_EXEMPTED, 600);
        setDeviceConfigInt(QcConstants.KEY_MAX_SESSION_COUNT_ACTIVE, 500);
        setDeviceConfigInt(QcConstants.KEY_MAX_SESSION_COUNT_WORKING, 400);
        setDeviceConfigInt(QcConstants.KEY_MAX_SESSION_COUNT_FREQUENT, 300);
        setDeviceConfigInt(QcConstants.KEY_MAX_SESSION_COUNT_RARE, 200);
        setDeviceConfigInt(QcConstants.KEY_MAX_SESSION_COUNT_RESTRICTED, 100);
        setDeviceConfigInt(QcConstants.KEY_MAX_SESSION_COUNT_PER_RATE_LIMITING_WINDOW, 50);
        setDeviceConfigLong(QcConstants.KEY_TIMING_SESSION_COALESCING_DURATION_MS,
                10 * SECOND_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_MIN_QUOTA_CHECK_DELAY_MS, 7 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_EXEMPTED_MS, 3 * HOUR_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_ACTIVE_MS, 2 * HOUR_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_WORKING_MS, 90 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_FREQUENT_MS, 1 * HOUR_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_RARE_MS, 30 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_RESTRICTED_MS, 27 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_ADDITION_INSTALLER_MS, 7 * HOUR_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_ADDITION_SPECIAL_MS, 10 * HOUR_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_WINDOW_SIZE_MS, 12 * HOUR_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_TOP_APP_TIME_CHUNK_SIZE_MS, 10 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_REWARD_TOP_APP_MS, 87 * SECOND_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_REWARD_INTERACTION_MS, 86 * SECOND_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_REWARD_NOTIFICATION_SEEN_MS, 85 * SECOND_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_GRACE_PERIOD_TEMP_ALLOWLIST_MS,
                84 * SECOND_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_GRACE_PERIOD_TOP_APP_MS, 83 * SECOND_IN_MILLIS);

        assertEquals(8 * MINUTE_IN_MILLIS,
                mQuotaController.getAllowedTimePerPeriodMs()[EXEMPTED_INDEX]);
        assertEquals(5 * MINUTE_IN_MILLIS,
                mQuotaController.getAllowedTimePerPeriodMs()[ACTIVE_INDEX]);
        assertEquals(7 * MINUTE_IN_MILLIS,
                mQuotaController.getAllowedTimePerPeriodMs()[WORKING_INDEX]);
        assertEquals(2 * MINUTE_IN_MILLIS,
                mQuotaController.getAllowedTimePerPeriodMs()[FREQUENT_INDEX]);
        assertEquals(4 * MINUTE_IN_MILLIS,
                mQuotaController.getAllowedTimePerPeriodMs()[RARE_INDEX]);
        assertEquals(11 * MINUTE_IN_MILLIS,
                mQuotaController.getAllowedTimePerPeriodMs()[RESTRICTED_INDEX]);
        assertEquals(2 * MINUTE_IN_MILLIS, mQuotaController.getInQuotaBufferMs());
        assertEquals(99 * MINUTE_IN_MILLIS,
                mQuotaController.getBucketWindowSizes()[EXEMPTED_INDEX]);
        assertEquals(15 * MINUTE_IN_MILLIS, mQuotaController.getBucketWindowSizes()[ACTIVE_INDEX]);
        assertEquals(30 * MINUTE_IN_MILLIS, mQuotaController.getBucketWindowSizes()[WORKING_INDEX]);
        assertEquals(45 * MINUTE_IN_MILLIS,
                mQuotaController.getBucketWindowSizes()[FREQUENT_INDEX]);
        assertEquals(60 * MINUTE_IN_MILLIS, mQuotaController.getBucketWindowSizes()[RARE_INDEX]);
        assertEquals(120 * MINUTE_IN_MILLIS,
                mQuotaController.getBucketWindowSizes()[RESTRICTED_INDEX]);
        assertEquals(3 * HOUR_IN_MILLIS, mQuotaController.getMaxExecutionTimeMs());
        assertEquals(15 * MINUTE_IN_MILLIS, mQuotaController.getRateLimitingWindowMs());
        assertEquals(500, mQuotaController.getMaxJobCountPerRateLimitingWindow());
        assertEquals(6000, mQuotaController.getBucketMaxJobCounts()[EXEMPTED_INDEX]);
        assertEquals(5000, mQuotaController.getBucketMaxJobCounts()[ACTIVE_INDEX]);
        assertEquals(4000, mQuotaController.getBucketMaxJobCounts()[WORKING_INDEX]);
        assertEquals(3000, mQuotaController.getBucketMaxJobCounts()[FREQUENT_INDEX]);
        assertEquals(2000, mQuotaController.getBucketMaxJobCounts()[RARE_INDEX]);
        assertEquals(2000, mQuotaController.getBucketMaxJobCounts()[RESTRICTED_INDEX]);
        assertEquals(50, mQuotaController.getMaxSessionCountPerRateLimitingWindow());
        assertEquals(600, mQuotaController.getBucketMaxSessionCounts()[EXEMPTED_INDEX]);
        assertEquals(500, mQuotaController.getBucketMaxSessionCounts()[ACTIVE_INDEX]);
        assertEquals(400, mQuotaController.getBucketMaxSessionCounts()[WORKING_INDEX]);
        assertEquals(300, mQuotaController.getBucketMaxSessionCounts()[FREQUENT_INDEX]);
        assertEquals(200, mQuotaController.getBucketMaxSessionCounts()[RARE_INDEX]);
        assertEquals(100, mQuotaController.getBucketMaxSessionCounts()[RESTRICTED_INDEX]);
        assertEquals(10 * SECOND_IN_MILLIS,
                mQuotaController.getTimingSessionCoalescingDurationMs());
        assertEquals(7 * MINUTE_IN_MILLIS, mQuotaController.getMinQuotaCheckDelayMs());
        assertEquals(3 * HOUR_IN_MILLIS, mQuotaController.getEJLimitsMs()[EXEMPTED_INDEX]);
        assertEquals(2 * HOUR_IN_MILLIS, mQuotaController.getEJLimitsMs()[ACTIVE_INDEX]);
        assertEquals(90 * MINUTE_IN_MILLIS, mQuotaController.getEJLimitsMs()[WORKING_INDEX]);
        assertEquals(HOUR_IN_MILLIS, mQuotaController.getEJLimitsMs()[FREQUENT_INDEX]);
        assertEquals(30 * MINUTE_IN_MILLIS, mQuotaController.getEJLimitsMs()[RARE_INDEX]);
        assertEquals(27 * MINUTE_IN_MILLIS, mQuotaController.getEJLimitsMs()[RESTRICTED_INDEX]);
        assertEquals(7 * HOUR_IN_MILLIS, mQuotaController.getEjLimitAdditionInstallerMs());
        assertEquals(10 * HOUR_IN_MILLIS, mQuotaController.getEjLimitAdditionSpecialMs());
        assertEquals(12 * HOUR_IN_MILLIS, mQuotaController.getEJLimitWindowSizeMs());
        assertEquals(10 * MINUTE_IN_MILLIS, mQuotaController.getEJTopAppTimeChunkSizeMs());
        assertEquals(87 * SECOND_IN_MILLIS, mQuotaController.getEJRewardTopAppMs());
        assertEquals(86 * SECOND_IN_MILLIS, mQuotaController.getEJRewardInteractionMs());
        assertEquals(85 * SECOND_IN_MILLIS, mQuotaController.getEJRewardNotificationSeenMs());
        assertEquals(84 * SECOND_IN_MILLIS, mQuotaController.getEJGracePeriodTempAllowlistMs());
        assertEquals(83 * SECOND_IN_MILLIS, mQuotaController.getEJGracePeriodTopAppMs());
    }

    @Test
    public void testConstantsUpdating_InvalidValues() {
        // Test negatives/too low.
        setDeviceConfigLong(QcConstants.KEY_ALLOWED_TIME_PER_PERIOD_EXEMPTED_MS, -MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_ALLOWED_TIME_PER_PERIOD_ACTIVE_MS, -MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_ALLOWED_TIME_PER_PERIOD_WORKING_MS, -MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_ALLOWED_TIME_PER_PERIOD_FREQUENT_MS, -MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_ALLOWED_TIME_PER_PERIOD_RARE_MS, -MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_ALLOWED_TIME_PER_PERIOD_RESTRICTED_MS,
                -MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_IN_QUOTA_BUFFER_MS, -MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_WINDOW_SIZE_EXEMPTED_MS, -MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_WINDOW_SIZE_ACTIVE_MS, -MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_WINDOW_SIZE_WORKING_MS, -MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_WINDOW_SIZE_FREQUENT_MS, -MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_WINDOW_SIZE_RARE_MS, -MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_WINDOW_SIZE_RESTRICTED_MS, -MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_MAX_EXECUTION_TIME_MS, -MINUTE_IN_MILLIS);
        setDeviceConfigInt(QcConstants.KEY_MAX_JOB_COUNT_EXEMPTED, -1);
        setDeviceConfigInt(QcConstants.KEY_MAX_JOB_COUNT_ACTIVE, -1);
        setDeviceConfigInt(QcConstants.KEY_MAX_JOB_COUNT_WORKING, 1);
        setDeviceConfigInt(QcConstants.KEY_MAX_JOB_COUNT_FREQUENT, 1);
        setDeviceConfigInt(QcConstants.KEY_MAX_JOB_COUNT_RARE, 1);
        setDeviceConfigInt(QcConstants.KEY_MAX_JOB_COUNT_RESTRICTED, -1);
        setDeviceConfigLong(QcConstants.KEY_RATE_LIMITING_WINDOW_MS, 15 * SECOND_IN_MILLIS);
        setDeviceConfigInt(QcConstants.KEY_MAX_JOB_COUNT_PER_RATE_LIMITING_WINDOW, 0);
        setDeviceConfigInt(QcConstants.KEY_MAX_SESSION_COUNT_EXEMPTED, -1);
        setDeviceConfigInt(QcConstants.KEY_MAX_SESSION_COUNT_ACTIVE, -1);
        setDeviceConfigInt(QcConstants.KEY_MAX_SESSION_COUNT_WORKING, 0);
        setDeviceConfigInt(QcConstants.KEY_MAX_SESSION_COUNT_FREQUENT, -3);
        setDeviceConfigInt(QcConstants.KEY_MAX_SESSION_COUNT_RARE, 0);
        setDeviceConfigInt(QcConstants.KEY_MAX_SESSION_COUNT_RESTRICTED, -5);
        setDeviceConfigInt(QcConstants.KEY_MAX_SESSION_COUNT_PER_RATE_LIMITING_WINDOW, 0);
        setDeviceConfigLong(QcConstants.KEY_TIMING_SESSION_COALESCING_DURATION_MS, -1);
        setDeviceConfigLong(QcConstants.KEY_MIN_QUOTA_CHECK_DELAY_MS, -1);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_EXEMPTED_MS, -1);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_ACTIVE_MS, -1);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_WORKING_MS, -1);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_FREQUENT_MS, -1);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_RARE_MS, -1);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_RESTRICTED_MS, -1);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_ADDITION_INSTALLER_MS, -1);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_ADDITION_SPECIAL_MS, -1);
        setDeviceConfigLong(QcConstants.KEY_EJ_WINDOW_SIZE_MS, -1);
        setDeviceConfigLong(QcConstants.KEY_EJ_TOP_APP_TIME_CHUNK_SIZE_MS, -1);
        setDeviceConfigLong(QcConstants.KEY_EJ_REWARD_TOP_APP_MS, -1);
        setDeviceConfigLong(QcConstants.KEY_EJ_REWARD_INTERACTION_MS, -1);
        setDeviceConfigLong(QcConstants.KEY_EJ_REWARD_NOTIFICATION_SEEN_MS, -1);
        setDeviceConfigLong(QcConstants.KEY_EJ_GRACE_PERIOD_TEMP_ALLOWLIST_MS, -1);
        setDeviceConfigLong(QcConstants.KEY_EJ_GRACE_PERIOD_TOP_APP_MS, -1);

        assertEquals(MINUTE_IN_MILLIS,
                mQuotaController.getAllowedTimePerPeriodMs()[EXEMPTED_INDEX]);
        assertEquals(MINUTE_IN_MILLIS, mQuotaController.getAllowedTimePerPeriodMs()[ACTIVE_INDEX]);
        assertEquals(MINUTE_IN_MILLIS, mQuotaController.getAllowedTimePerPeriodMs()[WORKING_INDEX]);
        assertEquals(MINUTE_IN_MILLIS,
                mQuotaController.getAllowedTimePerPeriodMs()[FREQUENT_INDEX]);
        assertEquals(MINUTE_IN_MILLIS, mQuotaController.getAllowedTimePerPeriodMs()[RARE_INDEX]);
        assertEquals(MINUTE_IN_MILLIS,
                mQuotaController.getAllowedTimePerPeriodMs()[RESTRICTED_INDEX]);
        assertEquals(0, mQuotaController.getInQuotaBufferMs());
        assertEquals(MINUTE_IN_MILLIS, mQuotaController.getBucketWindowSizes()[EXEMPTED_INDEX]);
        assertEquals(MINUTE_IN_MILLIS, mQuotaController.getBucketWindowSizes()[ACTIVE_INDEX]);
        assertEquals(MINUTE_IN_MILLIS, mQuotaController.getBucketWindowSizes()[WORKING_INDEX]);
        assertEquals(MINUTE_IN_MILLIS, mQuotaController.getBucketWindowSizes()[FREQUENT_INDEX]);
        assertEquals(MINUTE_IN_MILLIS, mQuotaController.getBucketWindowSizes()[RARE_INDEX]);
        assertEquals(MINUTE_IN_MILLIS, mQuotaController.getBucketWindowSizes()[RESTRICTED_INDEX]);
        assertEquals(HOUR_IN_MILLIS, mQuotaController.getMaxExecutionTimeMs());
        assertEquals(30 * SECOND_IN_MILLIS, mQuotaController.getRateLimitingWindowMs());
        assertEquals(10, mQuotaController.getMaxJobCountPerRateLimitingWindow());
        assertEquals(10, mQuotaController.getBucketMaxJobCounts()[EXEMPTED_INDEX]);
        assertEquals(10, mQuotaController.getBucketMaxJobCounts()[ACTIVE_INDEX]);
        assertEquals(10, mQuotaController.getBucketMaxJobCounts()[WORKING_INDEX]);
        assertEquals(10, mQuotaController.getBucketMaxJobCounts()[FREQUENT_INDEX]);
        assertEquals(10, mQuotaController.getBucketMaxJobCounts()[RARE_INDEX]);
        assertEquals(10, mQuotaController.getBucketMaxJobCounts()[RESTRICTED_INDEX]);
        assertEquals(10, mQuotaController.getMaxSessionCountPerRateLimitingWindow());
        assertEquals(1, mQuotaController.getBucketMaxSessionCounts()[EXEMPTED_INDEX]);
        assertEquals(1, mQuotaController.getBucketMaxSessionCounts()[ACTIVE_INDEX]);
        assertEquals(1, mQuotaController.getBucketMaxSessionCounts()[WORKING_INDEX]);
        assertEquals(1, mQuotaController.getBucketMaxSessionCounts()[FREQUENT_INDEX]);
        assertEquals(1, mQuotaController.getBucketMaxSessionCounts()[RARE_INDEX]);
        assertEquals(0, mQuotaController.getBucketMaxSessionCounts()[RESTRICTED_INDEX]);
        assertEquals(0, mQuotaController.getTimingSessionCoalescingDurationMs());
        assertEquals(0, mQuotaController.getMinQuotaCheckDelayMs());
        assertEquals(15 * MINUTE_IN_MILLIS, mQuotaController.getEJLimitsMs()[EXEMPTED_INDEX]);
        assertEquals(15 * MINUTE_IN_MILLIS, mQuotaController.getEJLimitsMs()[ACTIVE_INDEX]);
        assertEquals(15 * MINUTE_IN_MILLIS, mQuotaController.getEJLimitsMs()[WORKING_INDEX]);
        assertEquals(10 * MINUTE_IN_MILLIS, mQuotaController.getEJLimitsMs()[FREQUENT_INDEX]);
        assertEquals(10 * MINUTE_IN_MILLIS, mQuotaController.getEJLimitsMs()[RARE_INDEX]);
        assertEquals(5 * MINUTE_IN_MILLIS, mQuotaController.getEJLimitsMs()[RESTRICTED_INDEX]);
        assertEquals(0, mQuotaController.getEjLimitAdditionInstallerMs());
        assertEquals(0, mQuotaController.getEjLimitAdditionSpecialMs());
        assertEquals(HOUR_IN_MILLIS, mQuotaController.getEJLimitWindowSizeMs());
        assertEquals(1, mQuotaController.getEJTopAppTimeChunkSizeMs());
        assertEquals(10 * SECOND_IN_MILLIS, mQuotaController.getEJRewardTopAppMs());
        assertEquals(5 * SECOND_IN_MILLIS, mQuotaController.getEJRewardInteractionMs());
        assertEquals(0, mQuotaController.getEJRewardNotificationSeenMs());
        assertEquals(0, mQuotaController.getEJGracePeriodTempAllowlistMs());
        assertEquals(0, mQuotaController.getEJGracePeriodTopAppMs());

        // Invalid configurations.
        // In_QUOTA_BUFFER should never be greater than ALLOWED_TIME_PER_PERIOD
        setDeviceConfigLong(QcConstants.KEY_ALLOWED_TIME_PER_PERIOD_EXEMPTED_MS,
                10 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_ALLOWED_TIME_PER_PERIOD_ACTIVE_MS,
                10 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_ALLOWED_TIME_PER_PERIOD_WORKING_MS,
                10 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_ALLOWED_TIME_PER_PERIOD_FREQUENT_MS,
                2 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_ALLOWED_TIME_PER_PERIOD_RARE_MS, 10 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_ALLOWED_TIME_PER_PERIOD_RESTRICTED_MS,
                10 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_IN_QUOTA_BUFFER_MS, 5 * MINUTE_IN_MILLIS);

        assertTrue(mQuotaController.getInQuotaBufferMs()
                <= mQuotaController.getAllowedTimePerPeriodMs()[FREQUENT_INDEX]);

        // Test larger than a day. Controller should cap at one day.
        setDeviceConfigLong(QcConstants.KEY_ALLOWED_TIME_PER_PERIOD_EXEMPTED_MS,
                25 * HOUR_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_ALLOWED_TIME_PER_PERIOD_ACTIVE_MS, 25 * HOUR_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_ALLOWED_TIME_PER_PERIOD_WORKING_MS,
                25 * HOUR_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_ALLOWED_TIME_PER_PERIOD_FREQUENT_MS,
                25 * HOUR_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_ALLOWED_TIME_PER_PERIOD_RARE_MS, 25 * HOUR_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_ALLOWED_TIME_PER_PERIOD_RESTRICTED_MS,
                25 * HOUR_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_IN_QUOTA_BUFFER_MS, 25 * HOUR_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_WINDOW_SIZE_EXEMPTED_MS, 25 * HOUR_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_WINDOW_SIZE_ACTIVE_MS, 25 * HOUR_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_WINDOW_SIZE_WORKING_MS, 25 * HOUR_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_WINDOW_SIZE_FREQUENT_MS, 25 * HOUR_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_WINDOW_SIZE_RARE_MS, 25 * HOUR_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_WINDOW_SIZE_RESTRICTED_MS, 30 * 24 * HOUR_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_MAX_EXECUTION_TIME_MS, 25 * HOUR_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_RATE_LIMITING_WINDOW_MS, 25 * HOUR_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_TIMING_SESSION_COALESCING_DURATION_MS,
                25 * HOUR_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_MIN_QUOTA_CHECK_DELAY_MS, 25 * HOUR_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_EXEMPTED_MS, 25 * HOUR_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_ACTIVE_MS, 25 * HOUR_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_WORKING_MS, 25 * HOUR_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_FREQUENT_MS, 25 * HOUR_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_RARE_MS, 25 * HOUR_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_RESTRICTED_MS, 25 * HOUR_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_ADDITION_INSTALLER_MS, 25 * HOUR_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_ADDITION_SPECIAL_MS, 25 * HOUR_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_WINDOW_SIZE_MS, 25 * HOUR_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_TOP_APP_TIME_CHUNK_SIZE_MS, 25 * HOUR_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_REWARD_TOP_APP_MS, 25 * HOUR_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_REWARD_INTERACTION_MS, 25 * HOUR_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_REWARD_NOTIFICATION_SEEN_MS, 25 * HOUR_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_GRACE_PERIOD_TEMP_ALLOWLIST_MS, 25 * HOUR_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_GRACE_PERIOD_TOP_APP_MS, 25 * HOUR_IN_MILLIS);

        assertEquals(24 * HOUR_IN_MILLIS,
                mQuotaController.getAllowedTimePerPeriodMs()[EXEMPTED_INDEX]);
        assertEquals(24 * HOUR_IN_MILLIS,
                mQuotaController.getAllowedTimePerPeriodMs()[ACTIVE_INDEX]);
        assertEquals(24 * HOUR_IN_MILLIS,
                mQuotaController.getAllowedTimePerPeriodMs()[WORKING_INDEX]);
        assertEquals(24 * HOUR_IN_MILLIS,
                mQuotaController.getAllowedTimePerPeriodMs()[FREQUENT_INDEX]);
        assertEquals(24 * HOUR_IN_MILLIS, mQuotaController.getAllowedTimePerPeriodMs()[RARE_INDEX]);
        assertEquals(24 * HOUR_IN_MILLIS,
                mQuotaController.getAllowedTimePerPeriodMs()[RESTRICTED_INDEX]);
        assertEquals(5 * MINUTE_IN_MILLIS, mQuotaController.getInQuotaBufferMs());
        assertEquals(24 * HOUR_IN_MILLIS, mQuotaController.getBucketWindowSizes()[EXEMPTED_INDEX]);
        assertEquals(24 * HOUR_IN_MILLIS, mQuotaController.getBucketWindowSizes()[ACTIVE_INDEX]);
        assertEquals(24 * HOUR_IN_MILLIS, mQuotaController.getBucketWindowSizes()[WORKING_INDEX]);
        assertEquals(24 * HOUR_IN_MILLIS, mQuotaController.getBucketWindowSizes()[FREQUENT_INDEX]);
        assertEquals(24 * HOUR_IN_MILLIS, mQuotaController.getBucketWindowSizes()[RARE_INDEX]);
        assertEquals(7 * 24 * HOUR_IN_MILLIS,
                mQuotaController.getBucketWindowSizes()[RESTRICTED_INDEX]);
        assertEquals(24 * HOUR_IN_MILLIS, mQuotaController.getMaxExecutionTimeMs());
        assertEquals(24 * HOUR_IN_MILLIS, mQuotaController.getRateLimitingWindowMs());
        assertEquals(15 * MINUTE_IN_MILLIS,
                mQuotaController.getTimingSessionCoalescingDurationMs());
        assertEquals(15 * MINUTE_IN_MILLIS, mQuotaController.getMinQuotaCheckDelayMs());
        assertEquals(24 * HOUR_IN_MILLIS, mQuotaController.getEJLimitsMs()[EXEMPTED_INDEX]);
        assertEquals(24 * HOUR_IN_MILLIS, mQuotaController.getEJLimitsMs()[ACTIVE_INDEX]);
        assertEquals(24 * HOUR_IN_MILLIS, mQuotaController.getEJLimitsMs()[WORKING_INDEX]);
        assertEquals(24 * HOUR_IN_MILLIS, mQuotaController.getEJLimitsMs()[FREQUENT_INDEX]);
        assertEquals(24 * HOUR_IN_MILLIS, mQuotaController.getEJLimitsMs()[RARE_INDEX]);
        assertEquals(24 * HOUR_IN_MILLIS, mQuotaController.getEJLimitsMs()[RESTRICTED_INDEX]);
        assertEquals(0, mQuotaController.getEjLimitAdditionInstallerMs());
        assertEquals(0, mQuotaController.getEjLimitAdditionSpecialMs());
        assertEquals(24 * HOUR_IN_MILLIS, mQuotaController.getEJLimitWindowSizeMs());
        assertEquals(15 * MINUTE_IN_MILLIS, mQuotaController.getEJTopAppTimeChunkSizeMs());
        assertEquals(15 * MINUTE_IN_MILLIS, mQuotaController.getEJRewardTopAppMs());
        assertEquals(15 * MINUTE_IN_MILLIS, mQuotaController.getEJRewardInteractionMs());
        assertEquals(5 * MINUTE_IN_MILLIS, mQuotaController.getEJRewardNotificationSeenMs());
        assertEquals(HOUR_IN_MILLIS, mQuotaController.getEJGracePeriodTempAllowlistMs());
        assertEquals(HOUR_IN_MILLIS, mQuotaController.getEJGracePeriodTopAppMs());
    }

    /** Tests that TimingSessions aren't saved when the device is charging. */
    @Test
    public void testTimerTracking_Charging() {
        setCharging();

        JobStatus jobStatus = createJobStatus("testTimerTracking_Charging", 1);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);
        }

        assertNull(mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobStatus);
        }
        advanceElapsedClock(5 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobStatus, null, false);
        }
        assertNull(mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
    }

    /** Tests that TimingSessions are saved properly when the device is discharging. */
    @Test
    public void testTimerTracking_Discharging() {
        setDischarging();
        setProcessState(ActivityManager.PROCESS_STATE_BACKUP);

        JobStatus jobStatus = createJobStatus("testTimerTracking_Discharging", 1);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);
        }

        assertNull(mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        List<TimingSession> expected = new ArrayList<>();

        long start = JobSchedulerService.sElapsedRealtimeClock.millis();
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobStatus);
        }
        advanceElapsedClock(5 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobStatus, null, false);
        }
        expected.add(createTimingSession(start, 5 * SECOND_IN_MILLIS, 1));
        assertEquals(expected, mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        // Test overlapping jobs.
        JobStatus jobStatus2 = createJobStatus("testTimerTracking_Discharging", 2);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobStatus2, null);
        }

        JobStatus jobStatus3 = createJobStatus("testTimerTracking_Discharging", 3);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobStatus3, null);
        }

        advanceElapsedClock(SECOND_IN_MILLIS);

        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);
            mQuotaController.prepareForExecutionLocked(jobStatus);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobStatus2);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobStatus, null, false);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobStatus3);
        }
        advanceElapsedClock(20 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobStatus3, null, false);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobStatus2, null, false);
        }
        expected.add(createTimingSession(start, MINUTE_IN_MILLIS, 3));
        assertEquals(expected, mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
    }

    /**
     * Tests that TimingSessions are saved properly when the device alternates between
     * charging and discharging.
     */
    @Test
    public void testTimerTracking_ChargingAndDischarging() {
        setProcessState(ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND);

        JobStatus jobStatus = createJobStatus("testTimerTracking_ChargingAndDischarging", 1);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);
        }
        JobStatus jobStatus2 = createJobStatus("testTimerTracking_ChargingAndDischarging", 2);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobStatus2, null);
        }
        JobStatus jobStatus3 = createJobStatus("testTimerTracking_ChargingAndDischarging", 3);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobStatus3, null);
        }
        assertNull(mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
        List<TimingSession> expected = new ArrayList<>();

        // A job starting while charging. Only the portion that runs during the discharging period
        // should be counted.
        setCharging();

        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobStatus);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        setDischarging();
        long start = JobSchedulerService.sElapsedRealtimeClock.millis();
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobStatus, jobStatus, true);
        }
        expected.add(createTimingSession(start, 10 * SECOND_IN_MILLIS, 1));
        assertEquals(expected, mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        advanceElapsedClock(SECOND_IN_MILLIS);

        // One job starts while discharging, spans a charging session, and ends after the charging
        // session. Only the portions during the discharging periods should be counted. This should
        // result in two TimingSessions. A second job starts while discharging and ends within the
        // charging session. Only the portion during the first discharging portion should be
        // counted. A third job starts and ends within the charging session. The third job
        // shouldn't be included in either job count.
        setDischarging();
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);
            mQuotaController.prepareForExecutionLocked(jobStatus);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobStatus2);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        setCharging();
        expected.add(createTimingSession(start, 20 * SECOND_IN_MILLIS, 2));
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobStatus3);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobStatus3, null, false);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobStatus2, null, false);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        setDischarging();
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        advanceElapsedClock(20 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobStatus, null, false);
        }
        expected.add(createTimingSession(start, 20 * SECOND_IN_MILLIS, 1));
        assertEquals(expected, mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        // A job starting while discharging and ending while charging. Only the portion that runs
        // during the discharging period should be counted.
        setDischarging();
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobStatus2, null);
            mQuotaController.prepareForExecutionLocked(jobStatus2);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        expected.add(createTimingSession(start, 10 * SECOND_IN_MILLIS, 1));
        setCharging();
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobStatus2, null, false);
        }
        assertEquals(expected, mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
    }

    /** Tests that TimingSessions are saved properly when all the jobs are background jobs. */
    @Test
    public void testTimerTracking_AllBackground() {
        setDischarging();
        setProcessState(ActivityManager.PROCESS_STATE_RECEIVER);

        JobStatus jobStatus = createJobStatus("testTimerTracking_AllBackground", 1);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);
        }
        assertNull(mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        List<TimingSession> expected = new ArrayList<>();

        // Test single job.
        long start = JobSchedulerService.sElapsedRealtimeClock.millis();
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobStatus);
        }
        advanceElapsedClock(5 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobStatus, null, false);
        }
        expected.add(createTimingSession(start, 5 * SECOND_IN_MILLIS, 1));
        assertEquals(expected, mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        // Test overlapping jobs.
        JobStatus jobStatus2 = createJobStatus("testTimerTracking_AllBackground", 2);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobStatus2, null);
        }

        JobStatus jobStatus3 = createJobStatus("testTimerTracking_AllBackground", 3);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobStatus3, null);
        }

        advanceElapsedClock(SECOND_IN_MILLIS);

        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);
            mQuotaController.prepareForExecutionLocked(jobStatus);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobStatus2);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobStatus, null, false);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobStatus3);
        }
        advanceElapsedClock(20 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobStatus3, null, false);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobStatus2, null, false);
        }
        expected.add(createTimingSession(start, MINUTE_IN_MILLIS, 3));
        assertEquals(expected, mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
    }

    /** Tests that Timers don't count foreground jobs. */
    @Test
    public void testTimerTracking_AllForeground() {
        setDischarging();

        JobStatus jobStatus = createJobStatus("testTimerTracking_AllForeground", 1);
        setProcessState(ActivityManager.PROCESS_STATE_TOP);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);
        }

        assertNull(mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobStatus);
        }
        advanceElapsedClock(5 * SECOND_IN_MILLIS);
        // Change to a state that should still be considered foreground.
        setProcessState(ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE);
        advanceElapsedClock(5 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobStatus, null, false);
        }
        assertNull(mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
    }

    /**
     * Tests that Timers properly track sessions when switching between foreground and background
     * states.
     */
    @Test
    public void testTimerTracking_ForegroundAndBackground() {
        setDischarging();

        JobStatus jobBg1 = createJobStatus("testTimerTracking_ForegroundAndBackground", 1);
        JobStatus jobBg2 = createJobStatus("testTimerTracking_ForegroundAndBackground", 2);
        JobStatus jobFg3 = createJobStatus("testTimerTracking_ForegroundAndBackground", 3);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobBg1, null);
            mQuotaController.maybeStartTrackingJobLocked(jobBg2, null);
            mQuotaController.maybeStartTrackingJobLocked(jobFg3, null);
        }
        assertNull(mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
        List<TimingSession> expected = new ArrayList<>();

        // UID starts out inactive.
        setProcessState(ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND);
        long start = JobSchedulerService.sElapsedRealtimeClock.millis();
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobBg1);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobBg1, jobBg1, true);
        }
        expected.add(createTimingSession(start, 10 * SECOND_IN_MILLIS, 1));
        assertEquals(expected, mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        advanceElapsedClock(SECOND_IN_MILLIS);

        // Bg job starts while inactive, spans an entire active session, and ends after the
        // active session.
        // App switching to foreground state then fg job starts.
        // App remains in foreground state after coming to foreground, so there should only be one
        // session.
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobBg2, null);
            mQuotaController.prepareForExecutionLocked(jobBg2);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        expected.add(createTimingSession(start, 10 * SECOND_IN_MILLIS, 1));
        setProcessState(ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE);
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobFg3);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobFg3, null, false);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobBg2, null, false);
        }
        assertEquals(expected, mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        advanceElapsedClock(SECOND_IN_MILLIS);

        // Bg job 1 starts, then fg job starts. Bg job 1 job ends. Shortly after, uid goes
        // "inactive" and then bg job 2 starts. Then fg job ends.
        // This should result in two TimingSessions:
        //  * The first should have a count of 1
        //  * The second should have a count of 2 since it will include both jobs
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobBg1, null);
            mQuotaController.maybeStartTrackingJobLocked(jobBg2, null);
            mQuotaController.maybeStartTrackingJobLocked(jobFg3, null);
        }
        setProcessState(ActivityManager.PROCESS_STATE_LAST_ACTIVITY);
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobBg1);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        expected.add(createTimingSession(start, 10 * SECOND_IN_MILLIS, 1));
        setProcessState(ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE);
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobFg3);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobBg1, jobBg1, true);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS); // UID "inactive" now
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        setProcessState(ActivityManager.PROCESS_STATE_TOP_SLEEPING);
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobBg2);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobFg3, null, false);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobBg2, null, false);
        }
        expected.add(createTimingSession(start, 20 * SECOND_IN_MILLIS, 2));
        assertEquals(expected, mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
    }

    /**
     * Tests that Timers don't track job counts while in the foreground.
     */
    @Test
    public void testTimerTracking_JobCount_Foreground() {
        setDischarging();

        final int standbyBucket = ACTIVE_INDEX;
        JobStatus jobFg1 = createJobStatus("testTimerTracking_JobCount_Foreground", 1);
        JobStatus jobFg2 = createJobStatus("testTimerTracking_JobCount_Foreground", 2);

        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobFg1, null);
            mQuotaController.maybeStartTrackingJobLocked(jobFg2, null);
        }
        assertNull(mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
        ExecutionStats stats;
        synchronized (mQuotaController.mLock) {
            stats = mQuotaController.getExecutionStatsLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, standbyBucket);
        }
        assertEquals(0, stats.jobCountInRateLimitingWindow);

        setProcessState(ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE);
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobFg1);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobFg2);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobFg1, null, false);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobFg2, null, false);
        }
        assertNull(mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        assertEquals(0, stats.jobCountInRateLimitingWindow);
    }

    /**
     * Tests that Timers properly track job counts while in the background.
     */
    @Test
    public void testTimerTracking_JobCount_Background() {
        final int standbyBucket = WORKING_INDEX;
        JobStatus jobBg1 = createJobStatus("testTimerTracking_JobCount_Background", 1);
        JobStatus jobBg2 = createJobStatus("testTimerTracking_JobCount_Background", 2);
        ExecutionStats stats;
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobBg1, null);
            mQuotaController.maybeStartTrackingJobLocked(jobBg2, null);

            stats = mQuotaController.getExecutionStatsLocked(SOURCE_USER_ID,
                    SOURCE_PACKAGE, standbyBucket);
        }
        assertEquals(0, stats.jobCountInRateLimitingWindow);

        setProcessState(ActivityManager.PROCESS_STATE_TOP_SLEEPING);
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobBg1);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobBg2);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobBg1, null, false);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobBg2, null, false);
        }

        assertEquals(2, stats.jobCountInRateLimitingWindow);
    }

    /**
     * Tests that Timers properly track overlapping top and background jobs.
     */
    @Test
    public void testTimerTracking_TopAndNonTop() {
        setDischarging();

        JobStatus jobBg1 = createJobStatus("testTimerTracking_TopAndNonTop", 1);
        JobStatus jobBg2 = createJobStatus("testTimerTracking_TopAndNonTop", 2);
        JobStatus jobFg1 = createJobStatus("testTimerTracking_TopAndNonTop", 3);
        JobStatus jobTop = createJobStatus("testTimerTracking_TopAndNonTop", 4);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobBg1, null);
            mQuotaController.maybeStartTrackingJobLocked(jobBg2, null);
            mQuotaController.maybeStartTrackingJobLocked(jobFg1, null);
            mQuotaController.maybeStartTrackingJobLocked(jobTop, null);
        }
        assertNull(mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
        List<TimingSession> expected = new ArrayList<>();

        // UID starts out inactive.
        setProcessState(ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND);
        long start = JobSchedulerService.sElapsedRealtimeClock.millis();
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobBg1);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobBg1, jobBg1, true);
        }
        expected.add(createTimingSession(start, 10 * SECOND_IN_MILLIS, 1));
        assertEquals(expected, mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        advanceElapsedClock(SECOND_IN_MILLIS);

        // Bg job starts while inactive, spans an entire active session, and ends after the
        // active session.
        // App switching to top state then fg job starts.
        // App remains in top state after coming to top, so there should only be one
        // session.
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobBg2, null);
            mQuotaController.prepareForExecutionLocked(jobBg2);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        expected.add(createTimingSession(start, 10 * SECOND_IN_MILLIS, 1));
        setProcessState(ActivityManager.PROCESS_STATE_TOP);
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobTop);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobTop, null, false);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobBg2, null, false);
        }
        assertEquals(expected, mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        advanceElapsedClock(SECOND_IN_MILLIS);

        // Bg job 1 starts, then top job starts. Bg job 1 job ends. Then app goes to
        // foreground_service and a new job starts. Shortly after, uid goes
        // "inactive" and then bg job 2 starts. Then top job ends, followed by bg and fg jobs.
        // This should result in two TimingSessions:
        //  * The first should have a count of 1
        //  * The second should have a count of 2, which accounts for the bg2 and fg, but not top
        //    jobs.
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobBg1, null);
            mQuotaController.maybeStartTrackingJobLocked(jobBg2, null);
            mQuotaController.maybeStartTrackingJobLocked(jobTop, null);
        }
        setProcessState(ActivityManager.PROCESS_STATE_LAST_ACTIVITY);
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobBg1);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        expected.add(createTimingSession(start, 10 * SECOND_IN_MILLIS, 1));
        setProcessState(ActivityManager.PROCESS_STATE_TOP);
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobTop);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobBg1, jobBg1, true);
        }
        advanceElapsedClock(5 * SECOND_IN_MILLIS);
        setProcessState(ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE);
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobFg1);
        }
        advanceElapsedClock(5 * SECOND_IN_MILLIS);
        setProcessState(ActivityManager.PROCESS_STATE_TOP);
        advanceElapsedClock(10 * SECOND_IN_MILLIS); // UID "inactive" now
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        setProcessState(ActivityManager.PROCESS_STATE_TOP_SLEEPING);
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobBg2);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobTop, null, false);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobBg2, null, false);
            mQuotaController.maybeStopTrackingJobLocked(jobFg1, null, false);
        }
        expected.add(createTimingSession(start, 20 * SECOND_IN_MILLIS, 2));
        assertEquals(expected, mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
    }

    /**
     * Tests that Timers properly track regular sessions when an app is added and removed from the
     * temp allowlist.
     */
    @Test
    public void testTimerTracking_TempAllowlisting() {
        // None of these should be affected purely by the temp allowlist changing.
        setDischarging();
        setProcessState(ActivityManager.PROCESS_STATE_RECEIVER);
        final long gracePeriodMs = 15 * SECOND_IN_MILLIS;
        setDeviceConfigLong(QcConstants.KEY_EJ_GRACE_PERIOD_TEMP_ALLOWLIST_MS, gracePeriodMs);
        Handler handler = mQuotaController.getHandler();
        spyOn(handler);

        JobStatus job1 = createJobStatus("testTimerTracking_TempAllowlisting", 1);
        JobStatus job2 = createJobStatus("testTimerTracking_TempAllowlisting", 2);
        JobStatus job3 = createJobStatus("testTimerTracking_TempAllowlisting", 3);
        JobStatus job4 = createJobStatus("testTimerTracking_TempAllowlisting", 4);
        JobStatus job5 = createJobStatus("testTimerTracking_TempAllowlisting", 5);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(job1, null);
        }
        assertNull(mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
        List<TimingSession> expected = new ArrayList<>();

        long start = JobSchedulerService.sElapsedRealtimeClock.millis();
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(job1);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(job1, job1, true);
        }
        expected.add(createTimingSession(start, 10 * SECOND_IN_MILLIS, 1));
        assertEquals(expected,
                mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        advanceElapsedClock(SECOND_IN_MILLIS);

        // Job starts after app is added to temp allowlist and stops before removal.
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        mTempAllowlistListener.onAppAdded(mSourceUid);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(job2, null);
            mQuotaController.prepareForExecutionLocked(job2);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(job2, null, false);
        }
        expected.add(createTimingSession(start, 10 * SECOND_IN_MILLIS, 1));
        assertEquals(expected,
                mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        // Job starts after app is added to temp allowlist and stops after removal,
        // before grace period ends.
        mTempAllowlistListener.onAppAdded(mSourceUid);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(job3, null);
            mQuotaController.prepareForExecutionLocked(job3);
        }
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mTempAllowlistListener.onAppRemoved(mSourceUid);
        long elapsedGracePeriodMs = 2 * SECOND_IN_MILLIS;
        advanceElapsedClock(elapsedGracePeriodMs);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(job3, null, false);
        }
        expected.add(createTimingSession(start, 10 * SECOND_IN_MILLIS + elapsedGracePeriodMs, 1));
        assertEquals(expected,
                mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        advanceElapsedClock(SECOND_IN_MILLIS);
        elapsedGracePeriodMs += SECOND_IN_MILLIS;

        // Job starts during grace period and ends after grace period ends
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(job4, null);
            mQuotaController.prepareForExecutionLocked(job4);
        }
        final long remainingGracePeriod = gracePeriodMs - elapsedGracePeriodMs;
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        advanceElapsedClock(remainingGracePeriod);
        // Wait for handler to update Timer
        // Can't directly evaluate the message because for some reason, the captured message returns
        // the wrong 'what' even though the correct message goes to the handler and the correct
        // path executes.
        verify(handler, timeout(gracePeriodMs + 5 * SECOND_IN_MILLIS)).handleMessage(any());
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        expected.add(createTimingSession(start, remainingGracePeriod + 10 * SECOND_IN_MILLIS, 1));
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(job4, job4, true);
        }
        assertEquals(expected,
                mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        // Job starts and runs completely after temp allowlist grace period.
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(job5, null);
            mQuotaController.prepareForExecutionLocked(job5);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(job5, job5, true);
        }
        expected.add(createTimingSession(start, 10 * SECOND_IN_MILLIS, 1));
        assertEquals(expected,
                mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
    }

    /**
     * Tests that TOP jobs aren't stopped when an app runs out of quota.
     */
    @Test
    public void testTracking_OutOfQuota_ForegroundAndBackground() {
        setDischarging();

        JobStatus jobBg = createJobStatus("testTracking_OutOfQuota_ForegroundAndBackground", 1);
        JobStatus jobTop = createJobStatus("testTracking_OutOfQuota_ForegroundAndBackground", 2);
        trackJobs(jobBg, jobTop);
        setStandbyBucket(WORKING_INDEX, jobTop, jobBg); // 2 hour window
        // Now the package only has 20 seconds to run.
        final long remainingTimeMs = 20 * SECOND_IN_MILLIS;
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(
                        JobSchedulerService.sElapsedRealtimeClock.millis() - HOUR_IN_MILLIS,
                        10 * MINUTE_IN_MILLIS - remainingTimeMs, 1), false);

        InOrder inOrder = inOrder(mJobSchedulerService);

        // UID starts out inactive.
        setProcessState(ActivityManager.PROCESS_STATE_SERVICE);
        // Start the job.
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobBg);
        }
        advanceElapsedClock(remainingTimeMs / 2);
        // New job starts after UID is in the foreground. Since the app is now in the foreground, it
        // should continue to have remainingTimeMs / 2 time remaining.
        setProcessState(ActivityManager.PROCESS_STATE_TOP);
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobTop);
        }
        advanceElapsedClock(remainingTimeMs);

        // Wait for some extra time to allow for job processing.
        inOrder.verify(mJobSchedulerService,
                        timeout(remainingTimeMs + 2 * SECOND_IN_MILLIS).times(0))
                .onControllerStateChanged(argThat(jobs -> jobs.size() > 0));
        synchronized (mQuotaController.mLock) {
            assertEquals(remainingTimeMs / 2,
                    mQuotaController.getRemainingExecutionTimeLocked(jobBg));
            assertEquals(remainingTimeMs / 2,
                    mQuotaController.getRemainingExecutionTimeLocked(jobTop));
        }
        // Go to a background state.
        setProcessState(ActivityManager.PROCESS_STATE_TOP_SLEEPING);
        advanceElapsedClock(remainingTimeMs / 2 + 1);
        inOrder.verify(mJobSchedulerService,
                        timeout(remainingTimeMs / 2 + 2 * SECOND_IN_MILLIS).times(1))
                .onControllerStateChanged(argThat(jobs -> jobs.size() == 1));
        // Top job should still be allowed to run.
        assertFalse(jobBg.isConstraintSatisfied(JobStatus.CONSTRAINT_WITHIN_QUOTA));
        assertTrue(jobTop.isConstraintSatisfied(JobStatus.CONSTRAINT_WITHIN_QUOTA));

        // New jobs to run.
        JobStatus jobBg2 = createJobStatus("testTracking_OutOfQuota_ForegroundAndBackground", 3);
        JobStatus jobTop2 = createJobStatus("testTracking_OutOfQuota_ForegroundAndBackground", 4);
        JobStatus jobFg = createJobStatus("testTracking_OutOfQuota_ForegroundAndBackground", 5);
        setStandbyBucket(WORKING_INDEX, jobBg2, jobTop2, jobFg);

        advanceElapsedClock(20 * SECOND_IN_MILLIS);
        setProcessState(ActivityManager.PROCESS_STATE_TOP);
        inOrder.verify(mJobSchedulerService, timeout(SECOND_IN_MILLIS).times(1))
                .onControllerStateChanged(argThat(jobs -> jobs.size() == 1));
        trackJobs(jobFg, jobTop);
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobTop);
        }
        assertTrue(jobTop.isConstraintSatisfied(JobStatus.CONSTRAINT_WITHIN_QUOTA));
        assertTrue(jobFg.isConstraintSatisfied(JobStatus.CONSTRAINT_WITHIN_QUOTA));
        assertTrue(jobBg.isConstraintSatisfied(JobStatus.CONSTRAINT_WITHIN_QUOTA));

        // App still in foreground so everything should be in quota.
        advanceElapsedClock(20 * SECOND_IN_MILLIS);
        setProcessState(ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE);
        assertTrue(jobTop.isConstraintSatisfied(JobStatus.CONSTRAINT_WITHIN_QUOTA));
        assertTrue(jobFg.isConstraintSatisfied(JobStatus.CONSTRAINT_WITHIN_QUOTA));
        assertTrue(jobBg.isConstraintSatisfied(JobStatus.CONSTRAINT_WITHIN_QUOTA));

        advanceElapsedClock(20 * SECOND_IN_MILLIS);
        setProcessState(ActivityManager.PROCESS_STATE_SERVICE);
        inOrder.verify(mJobSchedulerService, timeout(SECOND_IN_MILLIS).times(1))
                .onControllerStateChanged(argThat(jobs -> jobs.size() == 2));
        // App is now in background and out of quota. Fg should now change to out of quota since it
        // wasn't started. Top should remain in quota since it started when the app was in TOP.
        assertTrue(jobTop.isConstraintSatisfied(JobStatus.CONSTRAINT_WITHIN_QUOTA));
        assertFalse(jobFg.isConstraintSatisfied(JobStatus.CONSTRAINT_WITHIN_QUOTA));
        assertFalse(jobBg.isConstraintSatisfied(JobStatus.CONSTRAINT_WITHIN_QUOTA));
        trackJobs(jobBg2);
        assertFalse(jobBg2.isConstraintSatisfied(JobStatus.CONSTRAINT_WITHIN_QUOTA));
    }

    /**
     * Tests that a job is properly updated and JobSchedulerService is notified when a job reaches
     * its quota.
     */
    @Test
    public void testTracking_OutOfQuota() {
        JobStatus jobStatus = createJobStatus("testTracking_OutOfQuota", 1);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);
        }
        setStandbyBucket(WORKING_INDEX, jobStatus); // 2 hour window
        setProcessState(ActivityManager.PROCESS_STATE_HOME);
        // Now the package only has two seconds to run.
        final long remainingTimeMs = 2 * SECOND_IN_MILLIS;
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(
                        JobSchedulerService.sElapsedRealtimeClock.millis() - HOUR_IN_MILLIS,
                        10 * MINUTE_IN_MILLIS - remainingTimeMs, 1), false);

        // Start the job.
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobStatus);
        }
        advanceElapsedClock(remainingTimeMs);

        // Wait for some extra time to allow for job processing.
        verify(mJobSchedulerService,
                timeout(remainingTimeMs + 2 * SECOND_IN_MILLIS).times(1))
                .onControllerStateChanged(argThat(jobs -> jobs.size() == 1));
        assertFalse(jobStatus.isConstraintSatisfied(JobStatus.CONSTRAINT_WITHIN_QUOTA));
        assertEquals(JobSchedulerService.sElapsedRealtimeClock.millis(),
                jobStatus.getWhenStandbyDeferred());
    }

    /**
     * Tests that a job is properly handled when it's at the edge of its quota and the old quota is
     * being phased out.
     */
    @Test
    public void testTracking_RollingQuota() {
        JobStatus jobStatus = createJobStatus("testTracking_OutOfQuota", 1);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);
        }
        setStandbyBucket(WORKING_INDEX, jobStatus); // 2 hour window
        setProcessState(ActivityManager.PROCESS_STATE_SERVICE);
        Handler handler = mQuotaController.getHandler();
        spyOn(handler);

        long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        final long remainingTimeMs = SECOND_IN_MILLIS;
        // The package only has one second to run, but this session is at the edge of the rolling
        // window, so as the package "reaches its quota" it will have more to keep running.
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 2 * HOUR_IN_MILLIS,
                        10 * SECOND_IN_MILLIS - remainingTimeMs, 1), false);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - HOUR_IN_MILLIS,
                        9 * MINUTE_IN_MILLIS + 50 * SECOND_IN_MILLIS, 1), false);

        synchronized (mQuotaController.mLock) {
            assertEquals(remainingTimeMs,
                    mQuotaController.getRemainingExecutionTimeLocked(jobStatus));

            // Start the job.
            mQuotaController.prepareForExecutionLocked(jobStatus);
        }
        advanceElapsedClock(remainingTimeMs);

        // Wait for some extra time to allow for job processing.
        verify(mJobSchedulerService,
                timeout(remainingTimeMs + 2 * SECOND_IN_MILLIS).times(0))
                .onControllerStateChanged(argThat(jobs -> jobs.size() > 0));
        assertTrue(jobStatus.isConstraintSatisfied(JobStatus.CONSTRAINT_WITHIN_QUOTA));
        // The job used up the remaining quota, but in that time, the same amount of time in the
        // old TimingSession also fell out of the quota window, so it should still have the same
        // amount of remaining time left its quota.
        synchronized (mQuotaController.mLock) {
            assertEquals(remainingTimeMs,
                    mQuotaController.getRemainingExecutionTimeLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE));
        }
        // Handler is told to check when the quota will be consumed, not when the initial
        // remaining time is over.
        verify(handler, atLeast(1)).sendMessageDelayed(
                argThat(msg -> msg.what == QuotaController.MSG_REACHED_QUOTA),
                eq(10 * SECOND_IN_MILLIS));
        verify(handler, never()).sendMessageDelayed(any(), eq(remainingTimeMs));

        // After 10 seconds, the job should finally be out of quota.
        advanceElapsedClock(10 * SECOND_IN_MILLIS - remainingTimeMs);
        // Wait for some extra time to allow for job processing.
        verify(mJobSchedulerService,
                timeout(12 * SECOND_IN_MILLIS).times(1))
                .onControllerStateChanged(argThat(jobs -> jobs.size() == 1));
        assertFalse(jobStatus.isConstraintSatisfied(JobStatus.CONSTRAINT_WITHIN_QUOTA));
        verify(handler, never()).sendMessageDelayed(any(), anyInt());
    }

    /**
     * Tests that the start alarm is properly scheduled when a job has been throttled due to the job
     * count rate limiting.
     */
    @Test
    public void testStartAlarmScheduled_JobCount_RateLimitingWindow() {
        // saveTimingSession calls maybeScheduleCleanupAlarmLocked which interferes with these tests
        // because it schedules an alarm too. Prevent it from doing so.
        spyOn(mQuotaController);
        doNothing().when(mQuotaController).maybeScheduleCleanupAlarmLocked();

        // Essentially disable session throttling.
        setDeviceConfigInt(QcConstants.KEY_MAX_SESSION_COUNT_WORKING, Integer.MAX_VALUE);
        setDeviceConfigInt(QcConstants.KEY_MAX_SESSION_COUNT_PER_RATE_LIMITING_WINDOW,
                Integer.MAX_VALUE);

        final int standbyBucket = WORKING_INDEX;
        setProcessState(ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND);

        // No sessions saved yet.
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, standbyBucket);
        }
        verify(mAlarmManager, timeout(1000).times(0)).setWindow(
                anyInt(), anyLong(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Ran jobs up to the job limit. All of them should be allowed to run.
        for (int i = 0; i < mQcConstants.MAX_JOB_COUNT_PER_RATE_LIMITING_WINDOW; ++i) {
            JobStatus job = createJobStatus("testStartAlarmScheduled_JobCount_AllowedTime", i);
            setStandbyBucket(WORKING_INDEX, job);
            synchronized (mQuotaController.mLock) {
                mQuotaController.maybeStartTrackingJobLocked(job, null);
                assertTrue(job.isConstraintSatisfied(JobStatus.CONSTRAINT_WITHIN_QUOTA));
                mQuotaController.prepareForExecutionLocked(job);
            }
            advanceElapsedClock(SECOND_IN_MILLIS);
            synchronized (mQuotaController.mLock) {
                mQuotaController.maybeStopTrackingJobLocked(job, null, false);
            }
            advanceElapsedClock(SECOND_IN_MILLIS);
        }
        // Start alarm shouldn't have been scheduled since the app was in quota up until this point.
        verify(mAlarmManager, timeout(1000).times(0)).setWindow(
                anyInt(), anyLong(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // The app is now out of job count quota
        JobStatus throttledJob = createJobStatus(
                "testStartAlarmScheduled_JobCount_AllowedTime", 42);
        setStandbyBucket(WORKING_INDEX, throttledJob);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(throttledJob, null);
        }
        assertFalse(throttledJob.isConstraintSatisfied(JobStatus.CONSTRAINT_WITHIN_QUOTA));

        ExecutionStats stats;
        synchronized (mQuotaController.mLock) {
            stats = mQuotaController.getExecutionStatsLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, standbyBucket);
        }
        final long expectedWorkingAlarmTime = stats.jobRateLimitExpirationTimeElapsed;
        verify(mAlarmManager, timeout(1000).times(1)).setWindow(
                anyInt(), eq(expectedWorkingAlarmTime), anyLong(),
                eq(TAG_QUOTA_CHECK), any(), any());
    }

    /**
     * Tests that the start alarm is properly scheduled when a job has been throttled due to the
     * session count rate limiting.
     */
    @Test
    public void testStartAlarmScheduled_TimingSessionCount_RateLimitingWindow() {
        // saveTimingSession calls maybeScheduleCleanupAlarmLocked which interferes with these tests
        // because it schedules an alarm too. Prevent it from doing so.
        spyOn(mQuotaController);
        doNothing().when(mQuotaController).maybeScheduleCleanupAlarmLocked();

        // Essentially disable job count throttling.
        setDeviceConfigInt(QcConstants.KEY_MAX_JOB_COUNT_FREQUENT, Integer.MAX_VALUE);
        setDeviceConfigInt(QcConstants.KEY_MAX_JOB_COUNT_PER_RATE_LIMITING_WINDOW,
                Integer.MAX_VALUE);
        // Make sure throttling is because of COUNT_PER_RATE_LIMITING_WINDOW.
        setDeviceConfigInt(QcConstants.KEY_MAX_SESSION_COUNT_FREQUENT,
                mQcConstants.MAX_SESSION_COUNT_PER_RATE_LIMITING_WINDOW + 1);

        final int standbyBucket = FREQUENT_INDEX;
        setProcessState(ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND);

        // No sessions saved yet.
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, standbyBucket);
        }
        verify(mAlarmManager, timeout(1000).times(0)).setWindow(
                anyInt(), anyLong(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Ran jobs up to the job limit. All of them should be allowed to run.
        for (int i = 0; i < mQcConstants.MAX_SESSION_COUNT_PER_RATE_LIMITING_WINDOW; ++i) {
            JobStatus job = createJobStatus(
                    "testStartAlarmScheduled_TimingSessionCount_AllowedTime", i);
            setStandbyBucket(FREQUENT_INDEX, job);
            synchronized (mQuotaController.mLock) {
                mQuotaController.maybeStartTrackingJobLocked(job, null);
                assertTrue("Constraint not satisfied for job #" + (i + 1),
                        job.isConstraintSatisfied(JobStatus.CONSTRAINT_WITHIN_QUOTA));
                mQuotaController.prepareForExecutionLocked(job);
            }
            advanceElapsedClock(SECOND_IN_MILLIS);
            synchronized (mQuotaController.mLock) {
                mQuotaController.maybeStopTrackingJobLocked(job, null, false);
            }
            advanceElapsedClock(SECOND_IN_MILLIS);
        }
        // Start alarm shouldn't have been scheduled since the app was in quota up until this point.
        verify(mAlarmManager, timeout(1000).times(0)).setWindow(
                anyInt(), anyLong(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // The app is now out of session count quota
        JobStatus throttledJob = createJobStatus(
                "testStartAlarmScheduled_TimingSessionCount_AllowedTime", 42);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(throttledJob, null);
        }
        assertFalse(throttledJob.isConstraintSatisfied(JobStatus.CONSTRAINT_WITHIN_QUOTA));
        assertEquals(JobSchedulerService.sElapsedRealtimeClock.millis(),
                throttledJob.getWhenStandbyDeferred());

        ExecutionStats stats;
        synchronized (mQuotaController.mLock) {
            stats = mQuotaController.getExecutionStatsLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, standbyBucket);
        }
        final long expectedWorkingAlarmTime = stats.sessionRateLimitExpirationTimeElapsed;
        verify(mAlarmManager, timeout(1000).times(1)).setWindow(
                anyInt(), eq(expectedWorkingAlarmTime), anyLong(),
                eq(TAG_QUOTA_CHECK), any(), any());
    }

    @Test
    public void testGetRemainingEJExecutionTimeLocked_NoHistory() {
        final long[] limits = mQuotaController.getEJLimitsMs();
        for (int i = 0; i < limits.length; ++i) {
            setStandbyBucket(i);
            assertEquals("Got wrong remaining EJ execution time for bucket #" + i,
                    limits[i],
                    mQuotaController.getRemainingEJExecutionTimeLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE));
        }
    }

    @Test
    public void testGetRemainingEJExecutionTimeLocked_AllSessionsWithinWindow() {
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - mQcConstants.EJ_WINDOW_SIZE_MS, MINUTE_IN_MILLIS, 5),
                true);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 40 * MINUTE_IN_MILLIS, MINUTE_IN_MILLIS, 5), true);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 30 * MINUTE_IN_MILLIS, MINUTE_IN_MILLIS, 5), true);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 20 * MINUTE_IN_MILLIS, MINUTE_IN_MILLIS, 5), true);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 10 * MINUTE_IN_MILLIS, MINUTE_IN_MILLIS, 5), true);

        final long[] limits = mQuotaController.getEJLimitsMs();
        for (int i = 0; i < limits.length; ++i) {
            setStandbyBucket(i);
            assertEquals("Got wrong remaining EJ execution time for bucket #" + i,
                    i == NEVER_INDEX ? 0 : (limits[i] - 5 * MINUTE_IN_MILLIS),
                    mQuotaController.getRemainingEJExecutionTimeLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE));
        }
    }

    @Test
    public void testGetRemainingEJExecutionTimeLocked_Installer() {
        PackageInfo pi = new PackageInfo();
        pi.packageName = SOURCE_PACKAGE;
        pi.requestedPermissions = new String[]{Manifest.permission.INSTALL_PACKAGES};
        pi.requestedPermissionsFlags = new int[]{PackageInfo.REQUESTED_PERMISSION_GRANTED};
        pi.applicationInfo = new ApplicationInfo();
        pi.applicationInfo.uid = mSourceUid;
        doReturn(List.of(pi)).when(mPackageManager).getInstalledPackagesAsUser(anyInt(), anyInt());
        doReturn(PackageManager.PERMISSION_GRANTED).when(mContext).checkPermission(
                eq(Manifest.permission.INSTALL_PACKAGES), anyInt(), eq(mSourceUid));
        mQuotaController.onSystemServicesReady();

        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - mQcConstants.EJ_WINDOW_SIZE_MS, MINUTE_IN_MILLIS, 5),
                true);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 40 * MINUTE_IN_MILLIS, MINUTE_IN_MILLIS, 5), true);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 30 * MINUTE_IN_MILLIS, MINUTE_IN_MILLIS, 5), true);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 20 * MINUTE_IN_MILLIS, MINUTE_IN_MILLIS, 5), true);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 10 * MINUTE_IN_MILLIS, MINUTE_IN_MILLIS, 5), true);

        final long[] limits = mQuotaController.getEJLimitsMs();
        for (int i = 0; i < limits.length; ++i) {
            setStandbyBucket(i);
            assertEquals("Got wrong remaining EJ execution time for bucket #" + i,
                    i == NEVER_INDEX ? 0
                            : (limits[i] + mQuotaController.getEjLimitAdditionInstallerMs()
                                    - 5 * MINUTE_IN_MILLIS),
                    mQuotaController.getRemainingEJExecutionTimeLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE));
        }
    }

    @Test
    public void testGetRemainingEJExecutionTimeLocked_OneSessionStraddlesEdge() {
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        final long[] limits = mQuotaController.getEJLimitsMs();
        for (int i = 0; i < limits.length; ++i) {
            synchronized (mQuotaController.mLock) {
                mQuotaController.onUserRemovedLocked(SOURCE_USER_ID);
            }
            mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                    createTimingSession(now - (mQcConstants.EJ_WINDOW_SIZE_MS + MINUTE_IN_MILLIS),
                            2 * MINUTE_IN_MILLIS, 5), true);
            mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                    createTimingSession(now - 40 * MINUTE_IN_MILLIS, MINUTE_IN_MILLIS, 5), true);
            mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                    createTimingSession(now - 30 * MINUTE_IN_MILLIS, MINUTE_IN_MILLIS, 5), true);
            mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                    createTimingSession(now - 20 * MINUTE_IN_MILLIS, MINUTE_IN_MILLIS, 5), true);
            mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                    createTimingSession(now - 10 * MINUTE_IN_MILLIS, MINUTE_IN_MILLIS, 5), true);

            setStandbyBucket(i);
            assertEquals("Got wrong remaining EJ execution time for bucket #" + i,
                    i == NEVER_INDEX ? 0 : (limits[i] - 5 * MINUTE_IN_MILLIS),
                    mQuotaController.getRemainingEJExecutionTimeLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE));
        }
    }

    @Test
    public void testGetRemainingEJExecutionTimeLocked_WithStaleSessions() {
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();

        final long[] limits = mQuotaController.getEJLimitsMs();
        for (int i = 0; i < limits.length; ++i) {
            synchronized (mQuotaController.mLock) {
                mQuotaController.onUserRemovedLocked(SOURCE_USER_ID);
            }
            mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                    createTimingSession(
                            now - (mQcConstants.EJ_WINDOW_SIZE_MS + 10 * MINUTE_IN_MILLIS),
                            2 * MINUTE_IN_MILLIS, 5), true);
            mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                    createTimingSession(
                            now - (mQcConstants.EJ_WINDOW_SIZE_MS + 5 * MINUTE_IN_MILLIS),
                            MINUTE_IN_MILLIS, 5), true);
            mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                    createTimingSession(now - (mQcConstants.EJ_WINDOW_SIZE_MS + MINUTE_IN_MILLIS),
                            2 * MINUTE_IN_MILLIS, 5), true);
            mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                    createTimingSession(now - 40 * MINUTE_IN_MILLIS, MINUTE_IN_MILLIS, 5), true);
            mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                    createTimingSession(now - 30 * MINUTE_IN_MILLIS, MINUTE_IN_MILLIS, 5), true);
            mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                    createTimingSession(now - 20 * MINUTE_IN_MILLIS, MINUTE_IN_MILLIS, 5), true);
            mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                    createTimingSession(now - 10 * MINUTE_IN_MILLIS, MINUTE_IN_MILLIS, 5), true);

            setStandbyBucket(i);
            assertEquals("Got wrong remaining EJ execution time for bucket #" + i,
                    i == NEVER_INDEX ? 0 : (limits[i] - 5 * MINUTE_IN_MILLIS),
                    mQuotaController.getRemainingEJExecutionTimeLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE));
        }
    }

    /**
     * Tests that getRemainingEJExecutionTimeLocked returns the correct stats soon after device
     * startup.
     */
    @Test
    public void testGetRemainingEJExecutionTimeLocked_BeginningOfTime() {
        // Set time to 3 minutes after boot.
        advanceElapsedClock(-JobSchedulerService.sElapsedRealtimeClock.millis());
        advanceElapsedClock(3 * MINUTE_IN_MILLIS);

        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(MINUTE_IN_MILLIS, MINUTE_IN_MILLIS, 2), true);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(150 * SECOND_IN_MILLIS, 15 * SECOND_IN_MILLIS, 5), true);

        final long[] limits = mQuotaController.getEJLimitsMs();
        for (int i = 0; i < limits.length; ++i) {
            setStandbyBucket(i);
            assertEquals("Got wrong remaining EJ execution time for bucket #" + i,
                    i == NEVER_INDEX ? 0 : (limits[i] - 75 * SECOND_IN_MILLIS),
                    mQuotaController.getRemainingEJExecutionTimeLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE));
        }
    }

    @Test
    public void testGetRemainingEJExecutionTimeLocked_IncrementalTimingSessions() {
        setDischarging();
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_ACTIVE_MS, 20 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_WORKING_MS, 15 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_FREQUENT_MS, 13 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_RARE_MS, 10 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_RESTRICTED_MS, 5 * MINUTE_IN_MILLIS);

        for (int i = 1; i <= 25; ++i) {
            mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                    createTimingSession(now - ((60 - i) * MINUTE_IN_MILLIS), MINUTE_IN_MILLIS,
                            2), true);

            synchronized (mQuotaController.mLock) {
                setStandbyBucket(ACTIVE_INDEX);
                assertEquals("Active has incorrect remaining EJ time with " + i + " sessions",
                        (20 - i) * MINUTE_IN_MILLIS,
                        mQuotaController.getRemainingEJExecutionTimeLocked(
                                SOURCE_USER_ID, SOURCE_PACKAGE));

                setStandbyBucket(WORKING_INDEX);
                assertEquals("Working has incorrect remaining EJ time with " + i + " sessions",
                        (15 - i) * MINUTE_IN_MILLIS,
                        mQuotaController.getRemainingEJExecutionTimeLocked(
                                SOURCE_USER_ID, SOURCE_PACKAGE));

                setStandbyBucket(FREQUENT_INDEX);
                assertEquals("Frequent has incorrect remaining EJ time with " + i + " sessions",
                        (13 - i) * MINUTE_IN_MILLIS,
                        mQuotaController.getRemainingEJExecutionTimeLocked(
                                SOURCE_USER_ID, SOURCE_PACKAGE));

                setStandbyBucket(RARE_INDEX);
                assertEquals("Rare has incorrect remaining EJ time with " + i + " sessions",
                        (10 - i) * MINUTE_IN_MILLIS,
                        mQuotaController.getRemainingEJExecutionTimeLocked(
                                SOURCE_USER_ID, SOURCE_PACKAGE));

                setStandbyBucket(RESTRICTED_INDEX);
                assertEquals("Restricted has incorrect remaining EJ time with " + i + " sessions",
                        (5 - i) * MINUTE_IN_MILLIS,
                        mQuotaController.getRemainingEJExecutionTimeLocked(
                                SOURCE_USER_ID, SOURCE_PACKAGE));
            }
        }
    }

    @Test
    public void testGetTimeUntilEJQuotaConsumedLocked_NoHistory() {
        final long[] limits = mQuotaController.getEJLimitsMs();
        for (int i = 0; i < limits.length; ++i) {
            setStandbyBucket(i);
            assertEquals("Got wrong time until EJ quota consumed for bucket #" + i,
                    limits[i], mQuotaController.getTimeUntilEJQuotaConsumedLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE));
        }
    }

    @Test
    public void testGetTimeUntilEJQuotaConsumedLocked_AllSessionsWithinWindow() {
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 40 * MINUTE_IN_MILLIS, MINUTE_IN_MILLIS, 5), true);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 30 * MINUTE_IN_MILLIS, MINUTE_IN_MILLIS, 5), true);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 20 * MINUTE_IN_MILLIS, 2 * MINUTE_IN_MILLIS, 5), true);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 10 * MINUTE_IN_MILLIS, MINUTE_IN_MILLIS, 5), true);

        final long[] limits = mQuotaController.getEJLimitsMs();
        for (int i = 0; i < limits.length; ++i) {
            setStandbyBucket(i);
            assertEquals("Got wrong time until EJ quota consumed for bucket #" + i,
                    i == NEVER_INDEX ? 0 : (limits[i] - 5 * MINUTE_IN_MILLIS),
                    mQuotaController.getTimeUntilEJQuotaConsumedLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE));
        }
    }

    @Test
    public void testGetTimeUntilEJQuotaConsumedLocked_SessionsAtEdgeOfWindow() {
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - mQcConstants.EJ_WINDOW_SIZE_MS, MINUTE_IN_MILLIS, 5),
                true);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - (mQcConstants.EJ_WINDOW_SIZE_MS - 2 * MINUTE_IN_MILLIS),
                        MINUTE_IN_MILLIS, 5), true);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - (mQcConstants.EJ_WINDOW_SIZE_MS - 10 * MINUTE_IN_MILLIS),
                        MINUTE_IN_MILLIS, 5), true);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 20 * MINUTE_IN_MILLIS, MINUTE_IN_MILLIS, 5), true);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 10 * MINUTE_IN_MILLIS, MINUTE_IN_MILLIS, 5), true);

        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_ACTIVE_MS, 30 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_WORKING_MS, 20 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_FREQUENT_MS, 15 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_RARE_MS, 10 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_RESTRICTED_MS, 5 * MINUTE_IN_MILLIS);

        setStandbyBucket(ACTIVE_INDEX);
        assertEquals("Got wrong time until EJ quota consumed for bucket #" + ACTIVE_INDEX,
                28 * MINUTE_IN_MILLIS,
                mQuotaController.getTimeUntilEJQuotaConsumedLocked(
                        SOURCE_USER_ID, SOURCE_PACKAGE));

        setStandbyBucket(WORKING_INDEX);
        assertEquals("Got wrong time until EJ quota consumed for bucket #" + WORKING_INDEX,
                18 * MINUTE_IN_MILLIS,
                mQuotaController.getTimeUntilEJQuotaConsumedLocked(
                        SOURCE_USER_ID, SOURCE_PACKAGE));

        setStandbyBucket(FREQUENT_INDEX);
        assertEquals("Got wrong time until EJ quota consumed for bucket #" + FREQUENT_INDEX,
                13 * MINUTE_IN_MILLIS,
                mQuotaController.getTimeUntilEJQuotaConsumedLocked(
                        SOURCE_USER_ID, SOURCE_PACKAGE));

        setStandbyBucket(RARE_INDEX);
        assertEquals("Got wrong time until EJ quota consumed for bucket #" + RARE_INDEX,
                7 * MINUTE_IN_MILLIS,
                mQuotaController.getTimeUntilEJQuotaConsumedLocked(
                        SOURCE_USER_ID, SOURCE_PACKAGE));

        setStandbyBucket(RESTRICTED_INDEX);
        assertEquals("Got wrong time until EJ quota consumed for bucket #" + RESTRICTED_INDEX,
                MINUTE_IN_MILLIS,
                mQuotaController.getTimeUntilEJQuotaConsumedLocked(
                        SOURCE_USER_ID, SOURCE_PACKAGE));
    }

    @Test
    public void testGetTimeUntilEJQuotaConsumedLocked_OneSessionStraddlesEdge() {
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();

        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - (mQcConstants.EJ_WINDOW_SIZE_MS + MINUTE_IN_MILLIS),
                        2 * MINUTE_IN_MILLIS, 5), true);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 40 * MINUTE_IN_MILLIS, MINUTE_IN_MILLIS, 5), true);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 30 * MINUTE_IN_MILLIS, MINUTE_IN_MILLIS, 5), true);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 20 * MINUTE_IN_MILLIS, MINUTE_IN_MILLIS, 5), true);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 10 * MINUTE_IN_MILLIS, MINUTE_IN_MILLIS, 5), true);

        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_ACTIVE_MS, 30 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_WORKING_MS, 20 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_FREQUENT_MS, 15 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_RARE_MS, 10 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_RESTRICTED_MS, 5 * MINUTE_IN_MILLIS);

        setStandbyBucket(ACTIVE_INDEX);
        assertEquals("Got wrong time until EJ quota consumed for bucket #" + ACTIVE_INDEX,
                26 * MINUTE_IN_MILLIS,
                mQuotaController.getTimeUntilEJQuotaConsumedLocked(
                        SOURCE_USER_ID, SOURCE_PACKAGE));

        setStandbyBucket(WORKING_INDEX);
        assertEquals("Got wrong time until EJ quota consumed for bucket #" + WORKING_INDEX,
                16 * MINUTE_IN_MILLIS,
                mQuotaController.getTimeUntilEJQuotaConsumedLocked(
                        SOURCE_USER_ID, SOURCE_PACKAGE));

        setStandbyBucket(FREQUENT_INDEX);
        assertEquals("Got wrong time until EJ quota consumed for bucket #" + FREQUENT_INDEX,
                11 * MINUTE_IN_MILLIS,
                mQuotaController.getTimeUntilEJQuotaConsumedLocked(
                        SOURCE_USER_ID, SOURCE_PACKAGE));

        setStandbyBucket(RARE_INDEX);
        assertEquals("Got wrong time until EJ quota consumed for bucket #" + RARE_INDEX,
                6 * MINUTE_IN_MILLIS,
                mQuotaController.getTimeUntilEJQuotaConsumedLocked(
                        SOURCE_USER_ID, SOURCE_PACKAGE));

        setStandbyBucket(RESTRICTED_INDEX);
        assertEquals("Got wrong time until EJ quota consumed for bucket #" + RESTRICTED_INDEX,
                MINUTE_IN_MILLIS,
                mQuotaController.getTimeUntilEJQuotaConsumedLocked(
                        SOURCE_USER_ID, SOURCE_PACKAGE));
    }

    @Test
    public void testGetTimeUntilEJQuotaConsumedLocked_WithStaleSessions() {
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();

        List<TimingSession> timingSessions = new ArrayList<>();
        timingSessions.add(
                createTimingSession(now - (mQcConstants.EJ_WINDOW_SIZE_MS + 10 * MINUTE_IN_MILLIS),
                        2 * MINUTE_IN_MILLIS, 5));
        timingSessions.add(
                createTimingSession(now - (mQcConstants.EJ_WINDOW_SIZE_MS + 5 * MINUTE_IN_MILLIS),
                        MINUTE_IN_MILLIS, 5));
        timingSessions.add(
                createTimingSession(now - (mQcConstants.EJ_WINDOW_SIZE_MS + MINUTE_IN_MILLIS),
                        2 * MINUTE_IN_MILLIS, 5));
        timingSessions.add(
                createTimingSession(now - 40 * MINUTE_IN_MILLIS, MINUTE_IN_MILLIS, 5));
        timingSessions.add(
                createTimingSession(now - 30 * MINUTE_IN_MILLIS, MINUTE_IN_MILLIS, 5));
        timingSessions.add(
                createTimingSession(now - 20 * MINUTE_IN_MILLIS, MINUTE_IN_MILLIS, 5));
        timingSessions.add(
                createTimingSession(now - 10 * MINUTE_IN_MILLIS, MINUTE_IN_MILLIS, 5));

        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_ACTIVE_MS, 30 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_WORKING_MS, 20 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_FREQUENT_MS, 15 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_RARE_MS, 10 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_RESTRICTED_MS, 5 * MINUTE_IN_MILLIS);

        runTestGetTimeUntilEJQuotaConsumedLocked(
                timingSessions, ACTIVE_INDEX, 26 * MINUTE_IN_MILLIS);
        runTestGetTimeUntilEJQuotaConsumedLocked(
                timingSessions, WORKING_INDEX, 16 * MINUTE_IN_MILLIS);
        runTestGetTimeUntilEJQuotaConsumedLocked(
                timingSessions, FREQUENT_INDEX, 11 * MINUTE_IN_MILLIS);
        runTestGetTimeUntilEJQuotaConsumedLocked(timingSessions, RARE_INDEX, 6 * MINUTE_IN_MILLIS);
        runTestGetTimeUntilEJQuotaConsumedLocked(
                timingSessions, RESTRICTED_INDEX, MINUTE_IN_MILLIS);
    }

    /**
     * Tests that getTimeUntilEJQuotaConsumedLocked returns the correct stats soon after device
     * startup.
     */
    @Test
    public void testGetTimeUntilEJQuotaConsumedLocked_BeginningOfTime() {
        // Set time to 3 minutes after boot.
        advanceElapsedClock(-JobSchedulerService.sElapsedRealtimeClock.millis());
        advanceElapsedClock(3 * MINUTE_IN_MILLIS);

        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(MINUTE_IN_MILLIS, MINUTE_IN_MILLIS, 2), true);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(150 * SECOND_IN_MILLIS, 15 * SECOND_IN_MILLIS, 5), true);

        final long[] limits = mQuotaController.getEJLimitsMs();
        for (int i = 0; i < limits.length; ++i) {
            setStandbyBucket(i);
            assertEquals("Got wrong time until EJ quota consumed for bucket #" + i,
                    limits[i], // All existing sessions will phase out
                    mQuotaController.getTimeUntilEJQuotaConsumedLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE));
        }
    }

    private void runTestGetTimeUntilEJQuotaConsumedLocked(
            List<TimingSession> timingSessions, int bucketIndex, long expectedValue) {
        synchronized (mQuotaController.mLock) {
            mQuotaController.onUserRemovedLocked(SOURCE_USER_ID);
        }
        if (timingSessions != null) {
            for (TimingSession session : timingSessions) {
                mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE, session, true);
            }
        }

        setStandbyBucket(bucketIndex);
        assertEquals("Got wrong time until EJ quota consumed for bucket #" + bucketIndex,
                expectedValue,
                mQuotaController.getTimeUntilEJQuotaConsumedLocked(
                        SOURCE_USER_ID, SOURCE_PACKAGE));
    }

    @Test
    public void testMaybeScheduleStartAlarmLocked_EJ() {
        // saveTimingSession calls maybeScheduleCleanupAlarmLocked which interferes with these tests
        // because it schedules an alarm too. Prevent it from doing so.
        spyOn(mQuotaController);
        doNothing().when(mQuotaController).maybeScheduleCleanupAlarmLocked();

        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(
                    createJobStatus("testMaybeScheduleStartAlarmLocked_EJ", 1), null);
        }

        final int standbyBucket = WORKING_INDEX;
        setStandbyBucket(standbyBucket);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_WORKING_MS, 20 * MINUTE_IN_MILLIS);

        InOrder inOrder = inOrder(mAlarmManager);

        synchronized (mQuotaController.mLock) {
            // No sessions saved yet.
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, standbyBucket);
        }
        inOrder.verify(mAlarmManager, timeout(1000).times(0))
                .setWindow(anyInt(), anyLong(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Test with timing sessions out of window.
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 25 * HOUR_IN_MILLIS, 30 * MINUTE_IN_MILLIS, 1), true);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, standbyBucket);
        }
        inOrder.verify(mAlarmManager, timeout(1000).times(0))
                .setWindow(anyInt(), anyLong(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Test with timing sessions in window but still in quota.
        final long end = now - (22 * HOUR_IN_MILLIS - 5 * MINUTE_IN_MILLIS);
        final long expectedAlarmTime = now + 2 * HOUR_IN_MILLIS + mQcConstants.IN_QUOTA_BUFFER_MS;
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                new TimingSession(now - 22 * HOUR_IN_MILLIS, end, 1), true);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, standbyBucket);
        }
        inOrder.verify(mAlarmManager, timeout(1000).times(0))
                .setWindow(anyInt(), anyLong(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Add some more sessions, but still in quota.
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - HOUR_IN_MILLIS, 5 * MINUTE_IN_MILLIS, 1), true);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - (50 * MINUTE_IN_MILLIS), 4 * MINUTE_IN_MILLIS, 1), true);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, standbyBucket);
        }
        inOrder.verify(mAlarmManager, timeout(1000).times(0))
                .setWindow(anyInt(), anyLong(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Test when out of quota.
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 30 * MINUTE_IN_MILLIS, 6 * MINUTE_IN_MILLIS, 1), true);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, standbyBucket);
        }
        inOrder.verify(mAlarmManager, timeout(1000).times(1)).setWindow(
                anyInt(), eq(expectedAlarmTime), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Alarm already scheduled, so make sure it's not scheduled again.
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, standbyBucket);
        }
        inOrder.verify(mAlarmManager, timeout(1000).times(0))
                .setWindow(anyInt(), anyLong(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());
    }

    /** Tests that the start alarm is properly rescheduled if the app's bucket is changed. */
    @Test
    public void testMaybeScheduleStartAlarmLocked_Ej_BucketChange() {
        // saveTimingSession calls maybeScheduleCleanupAlarmLocked which interferes with these tests
        // because it schedules an alarm too. Prevent it from doing so.
        spyOn(mQuotaController);
        doNothing().when(mQuotaController).maybeScheduleCleanupAlarmLocked();

        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(
                    createJobStatus("testMaybeScheduleStartAlarmLocked_Ej_BucketChange", 1), null);
        }

        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_ACTIVE_MS, 30 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_WORKING_MS, 20 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_FREQUENT_MS, 15 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_RARE_MS, 10 * MINUTE_IN_MILLIS);

        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        // Affects active bucket
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 12 * HOUR_IN_MILLIS, 9 * MINUTE_IN_MILLIS, 3), true);
        // Affects active and working buckets
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 4 * HOUR_IN_MILLIS, 5 * MINUTE_IN_MILLIS, 3), true);
        // Affects active, working, and frequent buckets
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - HOUR_IN_MILLIS, 5 * MINUTE_IN_MILLIS, 10), true);
        // Affects all buckets
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 5 * MINUTE_IN_MILLIS, 10 * MINUTE_IN_MILLIS, 3), true);

        InOrder inOrder = inOrder(mAlarmManager);

        // Start in ACTIVE bucket.
        setStandbyBucket(ACTIVE_INDEX);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, ACTIVE_INDEX);
        }
        inOrder.verify(mAlarmManager, timeout(1000).times(0))
                .setWindow(anyInt(), anyLong(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());
        inOrder.verify(mAlarmManager, timeout(1000).times(0))
                .cancel(any(AlarmManager.OnAlarmListener.class));

        // And down from there.
        setStandbyBucket(WORKING_INDEX);
        final long expectedWorkingAlarmTime =
                (now - 4 * HOUR_IN_MILLIS) + (24 * HOUR_IN_MILLIS)
                        + mQcConstants.IN_QUOTA_BUFFER_MS;
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, WORKING_INDEX);
        }
        inOrder.verify(mAlarmManager, timeout(1000).times(1)).setWindow(
                anyInt(), eq(expectedWorkingAlarmTime), anyLong(),
                eq(TAG_QUOTA_CHECK), any(), any());

        setStandbyBucket(FREQUENT_INDEX);
        final long expectedFrequentAlarmTime =
                (now - HOUR_IN_MILLIS) + (24 * HOUR_IN_MILLIS) + mQcConstants.IN_QUOTA_BUFFER_MS;
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, FREQUENT_INDEX);
        }
        inOrder.verify(mAlarmManager, timeout(1000).times(1)).setWindow(
                anyInt(), eq(expectedFrequentAlarmTime), anyLong(),
                eq(TAG_QUOTA_CHECK), any(), any());

        setStandbyBucket(RARE_INDEX);
        final long expectedRareAlarmTime =
                (now - 5 * MINUTE_IN_MILLIS) + (24 * HOUR_IN_MILLIS)
                        + mQcConstants.IN_QUOTA_BUFFER_MS;
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, RARE_INDEX);
        }
        inOrder.verify(mAlarmManager, timeout(1000).times(1)).setWindow(
                anyInt(), eq(expectedRareAlarmTime), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // And back up again.
        setStandbyBucket(FREQUENT_INDEX);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, FREQUENT_INDEX);
        }
        inOrder.verify(mAlarmManager, timeout(1000).times(1)).setWindow(
                anyInt(), eq(expectedFrequentAlarmTime), anyLong(),
                eq(TAG_QUOTA_CHECK), any(), any());

        setStandbyBucket(WORKING_INDEX);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, WORKING_INDEX);
        }
        inOrder.verify(mAlarmManager, timeout(1000).times(1)).setWindow(
                anyInt(), eq(expectedWorkingAlarmTime), anyLong(),
                eq(TAG_QUOTA_CHECK), any(), any());

        setStandbyBucket(ACTIVE_INDEX);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, ACTIVE_INDEX);
        }
        inOrder.verify(mAlarmManager, timeout(1000).times(0))
                .setWindow(anyInt(), anyLong(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());
        inOrder.verify(mAlarmManager, timeout(1000).times(1))
                .cancel(any(AlarmManager.OnAlarmListener.class));
    }

    /**
     * Tests that the start alarm is properly rescheduled if the earliest session that contributes
     * to the app being out of quota contributes less than the quota buffer time.
     */
    @Test
    public void testMaybeScheduleStartAlarmLocked_Ej_SmallRollingQuota() {
        // saveTimingSession calls maybeScheduleCleanupAlarmLocked which interferes with these tests
        // because it schedules an alarm too. Prevent it from doing so.
        spyOn(mQuotaController);
        doNothing().when(mQuotaController).maybeScheduleCleanupAlarmLocked();

        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(
                    createJobStatus("testMaybeScheduleStartAlarmLocked_Ej_SRQ", 1), null);
        }

        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        setStandbyBucket(WORKING_INDEX);
        final long contributionMs = mQcConstants.IN_QUOTA_BUFFER_MS / 2;
        final long remainingTimeMs = mQcConstants.EJ_LIMIT_WORKING_MS - contributionMs;

        // Session straddles edge of bucket window. Only the contribution should be counted towards
        // the quota.
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - (24 * HOUR_IN_MILLIS + 3 * MINUTE_IN_MILLIS),
                        3 * MINUTE_IN_MILLIS + contributionMs, 3), true);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 23 * HOUR_IN_MILLIS, remainingTimeMs, 2), true);
        // Expected alarm time should be when the app will have QUOTA_BUFFER_MS time of quota, which
        // is 24 hours + (QUOTA_BUFFER_MS - contributionMs) after the start of the second session.
        final long expectedAlarmTime =
                now + HOUR_IN_MILLIS + (mQcConstants.IN_QUOTA_BUFFER_MS - contributionMs);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeScheduleStartAlarmLocked(
                    SOURCE_USER_ID, SOURCE_PACKAGE, WORKING_INDEX);
        }
        verify(mAlarmManager, timeout(1000).times(1)).setWindow(
                anyInt(), eq(expectedAlarmTime), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());
    }

    /** Tests that TimingSessions aren't saved when the device is charging. */
    @Test
    public void testEJTimerTracking_Charging() {
        setCharging();

        JobStatus jobStatus = createExpeditedJobStatus("testEJTimerTracking_Charging", 1);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);
        }

        assertNull(mQuotaController.getEJTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobStatus);
        }
        advanceElapsedClock(5 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobStatus, null, false);
        }
        assertNull(mQuotaController.getEJTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
    }

    /** Tests that TimingSessions are saved properly when the device is discharging. */
    @Test
    public void testEJTimerTracking_Discharging() {
        setDischarging();
        setProcessState(ActivityManager.PROCESS_STATE_BACKUP);

        JobStatus jobStatus = createExpeditedJobStatus("testEJTimerTracking_Discharging", 1);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);
        }

        assertNull(mQuotaController.getEJTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        List<TimingSession> expected = new ArrayList<>();

        long start = JobSchedulerService.sElapsedRealtimeClock.millis();
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobStatus);
        }
        advanceElapsedClock(5 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobStatus, null, false);
        }
        expected.add(createTimingSession(start, 5 * SECOND_IN_MILLIS, 1));
        assertEquals(expected,
                mQuotaController.getEJTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        // Test overlapping jobs.
        JobStatus jobStatus2 = createExpeditedJobStatus("testEJTimerTracking_Discharging", 2);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobStatus2, null);
        }

        JobStatus jobStatus3 = createExpeditedJobStatus("testEJTimerTracking_Discharging", 3);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobStatus3, null);
        }

        advanceElapsedClock(SECOND_IN_MILLIS);

        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);
            mQuotaController.prepareForExecutionLocked(jobStatus);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobStatus2);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobStatus, null, false);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobStatus3);
        }
        advanceElapsedClock(20 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobStatus3, null, false);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobStatus2, null, false);
        }
        expected.add(createTimingSession(start, MINUTE_IN_MILLIS, 3));
        assertEquals(expected,
                mQuotaController.getEJTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
    }

    /**
     * Tests that TimingSessions are saved properly when the device alternates between
     * charging and discharging.
     */
    @Test
    public void testEJTimerTracking_ChargingAndDischarging() {
        setProcessState(ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND);

        JobStatus jobStatus =
                createExpeditedJobStatus("testEJTimerTracking_ChargingAndDischarging", 1);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);
        }
        JobStatus jobStatus2 =
                createExpeditedJobStatus("testEJTimerTracking_ChargingAndDischarging", 2);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobStatus2, null);
        }
        JobStatus jobStatus3 =
                createExpeditedJobStatus("testEJTimerTracking_ChargingAndDischarging", 3);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobStatus3, null);
        }
        assertNull(mQuotaController.getEJTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
        List<TimingSession> expected = new ArrayList<>();

        // A job starting while charging. Only the portion that runs during the discharging period
        // should be counted.
        setCharging();

        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobStatus);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        setDischarging();
        long start = JobSchedulerService.sElapsedRealtimeClock.millis();
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobStatus, jobStatus, true);
        }
        expected.add(createTimingSession(start, 10 * SECOND_IN_MILLIS, 1));
        assertEquals(expected,
                mQuotaController.getEJTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        advanceElapsedClock(SECOND_IN_MILLIS);

        // One job starts while discharging, spans a charging session, and ends after the charging
        // session. Only the portions during the discharging periods should be counted. This should
        // result in two TimingSessions. A second job starts while discharging and ends within the
        // charging session. Only the portion during the first discharging portion should be
        // counted. A third job starts and ends within the charging session. The third job
        // shouldn't be included in either job count.
        setDischarging();
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);
            mQuotaController.prepareForExecutionLocked(jobStatus);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobStatus2);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        setCharging();
        expected.add(createTimingSession(start, 20 * SECOND_IN_MILLIS, 2));
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobStatus3);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobStatus3, null, false);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobStatus2, null, false);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        setDischarging();
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        advanceElapsedClock(20 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobStatus, null, false);
        }
        expected.add(createTimingSession(start, 20 * SECOND_IN_MILLIS, 1));
        assertEquals(expected,
                mQuotaController.getEJTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        // A job starting while discharging and ending while charging. Only the portion that runs
        // during the discharging period should be counted.
        setDischarging();
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobStatus2, null);
            mQuotaController.prepareForExecutionLocked(jobStatus2);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        expected.add(createTimingSession(start, 10 * SECOND_IN_MILLIS, 1));
        setCharging();
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobStatus2, null, false);
        }
        assertEquals(expected,
                mQuotaController.getEJTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
    }

    /** Tests that TimingSessions are saved properly when all the jobs are background jobs. */
    @Test
    public void testEJTimerTracking_AllBackground() {
        setDischarging();
        setProcessState(ActivityManager.PROCESS_STATE_RECEIVER);

        JobStatus jobStatus = createExpeditedJobStatus("testEJTimerTracking_AllBackground", 1);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);
        }
        assertNull(mQuotaController.getEJTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        List<TimingSession> expected = new ArrayList<>();

        // Test single job.
        long start = JobSchedulerService.sElapsedRealtimeClock.millis();
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobStatus);
        }
        advanceElapsedClock(5 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobStatus, null, false);
        }
        expected.add(createTimingSession(start, 5 * SECOND_IN_MILLIS, 1));
        assertEquals(expected,
                mQuotaController.getEJTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        // Test overlapping jobs.
        JobStatus jobStatus2 = createExpeditedJobStatus("testEJTimerTracking_AllBackground", 2);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobStatus2, null);
        }

        JobStatus jobStatus3 = createExpeditedJobStatus("testEJTimerTracking_AllBackground", 3);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobStatus3, null);
        }

        advanceElapsedClock(SECOND_IN_MILLIS);

        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);
            mQuotaController.prepareForExecutionLocked(jobStatus);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobStatus2);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobStatus, null, false);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobStatus3);
        }
        advanceElapsedClock(20 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobStatus3, null, false);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobStatus2, null, false);
        }
        expected.add(createTimingSession(start, MINUTE_IN_MILLIS, 3));
        assertEquals(expected,
                mQuotaController.getEJTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
    }

    /** Tests that Timers don't count foreground jobs. */
    @Test
    public void testEJTimerTracking_AllForeground() {
        setDischarging();

        JobStatus jobStatus = createExpeditedJobStatus("testEJTimerTracking_AllForeground", 1);
        setProcessState(ActivityManager.PROCESS_STATE_TOP);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);
        }

        assertNull(mQuotaController.getEJTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobStatus);
        }
        advanceElapsedClock(5 * SECOND_IN_MILLIS);
        // Change to a state that should still be considered foreground.
        setProcessState(ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE);
        advanceElapsedClock(5 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobStatus, null, false);
        }
        assertNull(mQuotaController.getEJTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
    }

    /**
     * Tests that Timers properly track sessions when switching between foreground and background
     * states.
     */
    @Test
    public void testEJTimerTracking_ForegroundAndBackground() {
        setDischarging();

        JobStatus jobBg1 =
                createExpeditedJobStatus("testEJTimerTracking_ForegroundAndBackground", 1);
        JobStatus jobBg2 =
                createExpeditedJobStatus("testEJTimerTracking_ForegroundAndBackground", 2);
        JobStatus jobFg3 =
                createExpeditedJobStatus("testEJTimerTracking_ForegroundAndBackground", 3);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobBg1, null);
            mQuotaController.maybeStartTrackingJobLocked(jobBg2, null);
            mQuotaController.maybeStartTrackingJobLocked(jobFg3, null);
        }
        assertNull(mQuotaController.getEJTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
        List<TimingSession> expected = new ArrayList<>();

        // UID starts out inactive.
        setProcessState(ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND);
        long start = JobSchedulerService.sElapsedRealtimeClock.millis();
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobBg1);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobBg1, jobBg1, true);
        }
        expected.add(createTimingSession(start, 10 * SECOND_IN_MILLIS, 1));
        assertEquals(expected,
                mQuotaController.getEJTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        advanceElapsedClock(SECOND_IN_MILLIS);

        // Bg job starts while inactive, spans an entire active session, and ends after the
        // active session.
        // App switching to foreground state then fg job starts.
        // App remains in foreground state after coming to foreground, so there should only be one
        // session.
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobBg2, null);
            mQuotaController.prepareForExecutionLocked(jobBg2);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        expected.add(createTimingSession(start, 10 * SECOND_IN_MILLIS, 1));
        setProcessState(ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE);
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobFg3);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobFg3, null, false);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobBg2, null, false);
        }
        assertEquals(expected,
                mQuotaController.getEJTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        advanceElapsedClock(SECOND_IN_MILLIS);

        // Bg job 1 starts, then fg job starts. Bg job 1 job ends. Shortly after, uid goes
        // "inactive" and then bg job 2 starts. Then fg job ends.
        // This should result in two TimingSessions:
        //  * The first should have a count of 1
        //  * The second should have a count of 2 since it will include both jobs
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobBg1, null);
            mQuotaController.maybeStartTrackingJobLocked(jobBg2, null);
            mQuotaController.maybeStartTrackingJobLocked(jobFg3, null);
        }
        setProcessState(ActivityManager.PROCESS_STATE_LAST_ACTIVITY);
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobBg1);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        expected.add(createTimingSession(start, 10 * SECOND_IN_MILLIS, 1));
        setProcessState(ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE);
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobFg3);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobBg1, jobBg1, true);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS); // UID "inactive" now
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        setProcessState(ActivityManager.PROCESS_STATE_TOP_SLEEPING);
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobBg2);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobFg3, null, false);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobBg2, null, false);
        }
        expected.add(createTimingSession(start, 20 * SECOND_IN_MILLIS, 2));
        assertEquals(expected,
                mQuotaController.getEJTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
    }

    /**
     * Tests that Timers properly track overlapping top and background jobs.
     */
    @Test
    public void testEJTimerTracking_TopAndNonTop() {
        setDischarging();
        setDeviceConfigLong(QcConstants.KEY_EJ_GRACE_PERIOD_TOP_APP_MS, 0);

        JobStatus jobBg1 = createExpeditedJobStatus("testEJTimerTracking_TopAndNonTop", 1);
        JobStatus jobBg2 = createExpeditedJobStatus("testEJTimerTracking_TopAndNonTop", 2);
        JobStatus jobFg1 = createExpeditedJobStatus("testEJTimerTracking_TopAndNonTop", 3);
        JobStatus jobTop = createExpeditedJobStatus("testEJTimerTracking_TopAndNonTop", 4);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobBg1, null);
            mQuotaController.maybeStartTrackingJobLocked(jobBg2, null);
            mQuotaController.maybeStartTrackingJobLocked(jobFg1, null);
            mQuotaController.maybeStartTrackingJobLocked(jobTop, null);
        }
        assertNull(mQuotaController.getEJTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
        List<TimingSession> expected = new ArrayList<>();

        // UID starts out inactive.
        setProcessState(ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND);
        long start = JobSchedulerService.sElapsedRealtimeClock.millis();
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobBg1);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobBg1, jobBg1, true);
        }
        expected.add(createTimingSession(start, 10 * SECOND_IN_MILLIS, 1));
        assertEquals(expected,
                mQuotaController.getEJTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        advanceElapsedClock(SECOND_IN_MILLIS);

        // Bg job starts while inactive, spans an entire active session, and ends after the
        // active session.
        // App switching to top state then fg job starts.
        // App remains in top state after coming to top, so there should only be one
        // session.
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobBg2, null);
            mQuotaController.prepareForExecutionLocked(jobBg2);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        expected.add(createTimingSession(start, 10 * SECOND_IN_MILLIS, 1));
        setProcessState(ActivityManager.PROCESS_STATE_TOP);
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobTop);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobTop, null, false);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobBg2, null, false);
        }
        assertEquals(expected,
                mQuotaController.getEJTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        advanceElapsedClock(SECOND_IN_MILLIS);

        // Bg job 1 starts, then top job starts. Bg job 1 job ends. Then app goes to
        // foreground_service and a new job starts. Shortly after, uid goes
        // "inactive" and then bg job 2 starts. Then top job ends, followed by bg and fg jobs.
        // This should result in two TimingSessions:
        //  * The first should have a count of 1
        //  * The second should have a count of 2, which accounts for the bg2 and fg, but not top
        //    jobs.
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobBg1, null);
            mQuotaController.maybeStartTrackingJobLocked(jobBg2, null);
            mQuotaController.maybeStartTrackingJobLocked(jobTop, null);
        }
        setProcessState(ActivityManager.PROCESS_STATE_LAST_ACTIVITY);
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobBg1);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        expected.add(createTimingSession(start, 10 * SECOND_IN_MILLIS, 1));
        setProcessState(ActivityManager.PROCESS_STATE_TOP);
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobTop);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobBg1, jobBg1, true);
        }
        advanceElapsedClock(5 * SECOND_IN_MILLIS);
        setProcessState(ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE);
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobFg1);
        }
        advanceElapsedClock(5 * SECOND_IN_MILLIS);
        setProcessState(ActivityManager.PROCESS_STATE_TOP);
        advanceElapsedClock(10 * SECOND_IN_MILLIS); // UID "inactive" now
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        setProcessState(ActivityManager.PROCESS_STATE_TOP_SLEEPING);
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobBg2);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobTop, null, false);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobBg2, null, false);
            mQuotaController.maybeStopTrackingJobLocked(jobFg1, null, false);
        }
        expected.add(createTimingSession(start, 20 * SECOND_IN_MILLIS, 2));
        assertEquals(expected,
                mQuotaController.getEJTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
    }

    /**
     * Tests that Timers properly track sessions when an app is added and removed from the temp
     * allowlist.
     */
    @Test
    public void testEJTimerTracking_TempAllowlisting() {
        setDischarging();
        setProcessState(ActivityManager.PROCESS_STATE_RECEIVER);
        final long gracePeriodMs = 15 * SECOND_IN_MILLIS;
        setDeviceConfigLong(QcConstants.KEY_EJ_GRACE_PERIOD_TEMP_ALLOWLIST_MS, gracePeriodMs);
        Handler handler = mQuotaController.getHandler();
        spyOn(handler);

        JobStatus job1 = createExpeditedJobStatus("testEJTimerTracking_TempAllowlisting", 1);
        JobStatus job2 = createExpeditedJobStatus("testEJTimerTracking_TempAllowlisting", 2);
        JobStatus job3 = createExpeditedJobStatus("testEJTimerTracking_TempAllowlisting", 3);
        JobStatus job4 = createExpeditedJobStatus("testEJTimerTracking_TempAllowlisting", 4);
        JobStatus job5 = createExpeditedJobStatus("testEJTimerTracking_TempAllowlisting", 5);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(job1, null);
        }
        assertNull(mQuotaController.getEJTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
        List<TimingSession> expected = new ArrayList<>();

        long start = JobSchedulerService.sElapsedRealtimeClock.millis();
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(job1);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(job1, job1, true);
        }
        expected.add(createTimingSession(start, 10 * SECOND_IN_MILLIS, 1));
        assertEquals(expected,
                mQuotaController.getEJTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        advanceElapsedClock(SECOND_IN_MILLIS);

        // Job starts after app is added to temp allowlist and stops before removal.
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        mTempAllowlistListener.onAppAdded(mSourceUid);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(job2, null);
            mQuotaController.prepareForExecutionLocked(job2);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(job2, null, false);
        }
        assertEquals(expected,
                mQuotaController.getEJTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        // Job starts after app is added to temp allowlist and stops after removal,
        // before grace period ends.
        mTempAllowlistListener.onAppAdded(mSourceUid);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(job3, null);
            mQuotaController.prepareForExecutionLocked(job3);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mTempAllowlistListener.onAppRemoved(mSourceUid);
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        long elapsedGracePeriodMs = 2 * SECOND_IN_MILLIS;
        advanceElapsedClock(elapsedGracePeriodMs);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(job3, null, false);
        }
        assertEquals(expected,
                mQuotaController.getEJTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        advanceElapsedClock(SECOND_IN_MILLIS);
        elapsedGracePeriodMs += SECOND_IN_MILLIS;

        // Job starts during grace period and ends after grace period ends
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(job4, null);
            mQuotaController.prepareForExecutionLocked(job4);
        }
        final long remainingGracePeriod = gracePeriodMs - elapsedGracePeriodMs;
        start = JobSchedulerService.sElapsedRealtimeClock.millis() + remainingGracePeriod;
        advanceElapsedClock(remainingGracePeriod);
        // Wait for handler to update Timer
        // Can't directly evaluate the message because for some reason, the captured message returns
        // the wrong 'what' even though the correct message goes to the handler and the correct
        // path executes.
        verify(handler, timeout(gracePeriodMs + 5 * SECOND_IN_MILLIS)).handleMessage(any());
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        expected.add(createTimingSession(start, 10 * SECOND_IN_MILLIS, 1));
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(job4, job4, true);
        }
        assertEquals(expected,
                mQuotaController.getEJTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        // Job starts and runs completely after temp allowlist grace period.
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(job5, null);
            mQuotaController.prepareForExecutionLocked(job5);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(job5, job5, true);
        }
        expected.add(createTimingSession(start, 10 * SECOND_IN_MILLIS, 1));
        assertEquals(expected,
                mQuotaController.getEJTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
    }

    @Test
    public void testEJTimerTracking_TempAllowlisting_Restricted() {
        setDischarging();
        setProcessState(ActivityManager.PROCESS_STATE_RECEIVER);
        final long gracePeriodMs = 15 * SECOND_IN_MILLIS;
        setDeviceConfigLong(QcConstants.KEY_EJ_GRACE_PERIOD_TEMP_ALLOWLIST_MS, gracePeriodMs);
        Handler handler = mQuotaController.getHandler();
        spyOn(handler);

        JobStatus job = createExpeditedJobStatus("testEJTimerTracking_TempAllowlisting_Restricted", 1);
        setStandbyBucket(RESTRICTED_INDEX, job);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(job, null);
        }
        assertNull(mQuotaController.getEJTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
        List<TimingSession> expected = new ArrayList<>();

        long start = JobSchedulerService.sElapsedRealtimeClock.millis();
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(job);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(job, job, true);
        }
        expected.add(createTimingSession(start, 10 * SECOND_IN_MILLIS, 1));
        assertEquals(expected,
                mQuotaController.getEJTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        advanceElapsedClock(SECOND_IN_MILLIS);

        // Job starts after app is added to temp allowlist and stops before removal.
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        mTempAllowlistListener.onAppAdded(mSourceUid);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(job, null);
            mQuotaController.prepareForExecutionLocked(job);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(job, null, false);
        }
        expected.add(createTimingSession(start, 10 * SECOND_IN_MILLIS, 1));
        assertEquals(expected,
                mQuotaController.getEJTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        // Job starts after app is added to temp allowlist and stops after removal,
        // before grace period ends.
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        mTempAllowlistListener.onAppAdded(mSourceUid);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(job, null);
            mQuotaController.prepareForExecutionLocked(job);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mTempAllowlistListener.onAppRemoved(mSourceUid);
        long elapsedGracePeriodMs = 2 * SECOND_IN_MILLIS;
        advanceElapsedClock(elapsedGracePeriodMs);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(job, null, false);
        }
        expected.add(createTimingSession(start, 12 * SECOND_IN_MILLIS, 1));
        assertEquals(expected,
                mQuotaController.getEJTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        advanceElapsedClock(SECOND_IN_MILLIS);
        elapsedGracePeriodMs += SECOND_IN_MILLIS;

        // Job starts during grace period and ends after grace period ends
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(job, null);
            mQuotaController.prepareForExecutionLocked(job);
        }
        final long remainingGracePeriod = gracePeriodMs - elapsedGracePeriodMs;
        advanceElapsedClock(remainingGracePeriod);
        // Wait for handler to update Timer
        // Can't directly evaluate the message because for some reason, the captured message returns
        // the wrong 'what' even though the correct message goes to the handler and the correct
        // path executes.
        verify(handler, timeout(gracePeriodMs + 5 * SECOND_IN_MILLIS)).handleMessage(any());
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        expected.add(createTimingSession(start, 10 * SECOND_IN_MILLIS + remainingGracePeriod, 1));
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(job, job, true);
        }
        assertEquals(expected,
                mQuotaController.getEJTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        // Job starts and runs completely after temp allowlist grace period.
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(job, null);
            mQuotaController.prepareForExecutionLocked(job);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(job, job, true);
        }
        expected.add(createTimingSession(start, 10 * SECOND_IN_MILLIS, 1));
        assertEquals(expected,
                mQuotaController.getEJTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
    }

    /**
     * Tests that Timers properly track sessions when TOP state and temp allowlisting overlaps.
     */
    @Test
    @LargeTest
    public void testEJTimerTracking_TopAndTempAllowlisting() throws Exception {
        setDischarging();
        setProcessState(ActivityManager.PROCESS_STATE_RECEIVER);
        final long gracePeriodMs = 5 * SECOND_IN_MILLIS;
        setDeviceConfigLong(QcConstants.KEY_EJ_GRACE_PERIOD_TEMP_ALLOWLIST_MS, gracePeriodMs);
        setDeviceConfigLong(QcConstants.KEY_EJ_GRACE_PERIOD_TOP_APP_MS, gracePeriodMs);
        Handler handler = mQuotaController.getHandler();
        spyOn(handler);

        JobStatus job1 = createExpeditedJobStatus("testEJTimerTracking_TopAndTempAllowlisting", 1);
        JobStatus job2 = createExpeditedJobStatus("testEJTimerTracking_TopAndTempAllowlisting", 2);
        JobStatus job3 = createExpeditedJobStatus("testEJTimerTracking_TopAndTempAllowlisting", 3);
        JobStatus job4 = createExpeditedJobStatus("testEJTimerTracking_TopAndTempAllowlisting", 4);
        JobStatus job5 = createExpeditedJobStatus("testEJTimerTracking_TopAndTempAllowlisting", 5);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(job1, null);
        }
        assertNull(mQuotaController.getEJTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
        List<TimingSession> expected = new ArrayList<>();

        // Case 1: job starts in TA grace period then app becomes TOP
        long start = JobSchedulerService.sElapsedRealtimeClock.millis();
        mTempAllowlistListener.onAppAdded(mSourceUid);
        mTempAllowlistListener.onAppRemoved(mSourceUid);
        advanceElapsedClock(gracePeriodMs / 2);
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(job1);
        }
        setProcessState(ActivityManager.PROCESS_STATE_TOP);
        advanceElapsedClock(gracePeriodMs);
        // Wait for the grace period to expire so the handler can process the message.
        Thread.sleep(gracePeriodMs);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(job1, job1, true);
        }
        assertNull(mQuotaController.getEJTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        advanceElapsedClock(gracePeriodMs);

        // Case 2: job starts in TOP grace period then is TAed
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        setProcessState(ActivityManager.PROCESS_STATE_TOP);
        setProcessState(ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND);
        advanceElapsedClock(gracePeriodMs / 2);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(job2, null);
            mQuotaController.prepareForExecutionLocked(job2);
        }
        mTempAllowlistListener.onAppAdded(mSourceUid);
        advanceElapsedClock(gracePeriodMs);
        // Wait for the grace period to expire so the handler can process the message.
        Thread.sleep(gracePeriodMs);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(job2, null, false);
        }
        assertNull(mQuotaController.getEJTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        advanceElapsedClock(gracePeriodMs);

        // Case 3: job starts in TA grace period then app becomes TOP; job ends after TOP grace
        mTempAllowlistListener.onAppAdded(mSourceUid);
        mTempAllowlistListener.onAppRemoved(mSourceUid);
        advanceElapsedClock(gracePeriodMs / 2);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(job3, null);
            mQuotaController.prepareForExecutionLocked(job3);
        }
        advanceElapsedClock(SECOND_IN_MILLIS);
        setProcessState(ActivityManager.PROCESS_STATE_TOP);
        advanceElapsedClock(gracePeriodMs);
        // Wait for the grace period to expire so the handler can process the message.
        Thread.sleep(gracePeriodMs);
        setProcessState(ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND);
        advanceElapsedClock(gracePeriodMs);
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        // Wait for the grace period to expire so the handler can process the message.
        Thread.sleep(2 * gracePeriodMs);
        advanceElapsedClock(gracePeriodMs);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(job3, job3, true);
        }
        expected.add(createTimingSession(start, gracePeriodMs, 1));
        assertEquals(expected,
                mQuotaController.getEJTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        advanceElapsedClock(gracePeriodMs);

        // Case 4: job starts in TOP grace period then app becomes TAed; job ends after TA grace
        setProcessState(ActivityManager.PROCESS_STATE_TOP);
        setProcessState(ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND);
        advanceElapsedClock(gracePeriodMs / 2);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(job4, null);
            mQuotaController.prepareForExecutionLocked(job4);
        }
        advanceElapsedClock(SECOND_IN_MILLIS);
        mTempAllowlistListener.onAppAdded(mSourceUid);
        advanceElapsedClock(gracePeriodMs);
        // Wait for the grace period to expire so the handler can process the message.
        Thread.sleep(gracePeriodMs);
        mTempAllowlistListener.onAppRemoved(mSourceUid);
        advanceElapsedClock(gracePeriodMs);
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        // Wait for the grace period to expire so the handler can process the message.
        Thread.sleep(2 * gracePeriodMs);
        advanceElapsedClock(gracePeriodMs);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(job4, job4, true);
        }
        expected.add(createTimingSession(start, gracePeriodMs, 1));
        assertEquals(expected,
                mQuotaController.getEJTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        advanceElapsedClock(gracePeriodMs);

        // Case 5: job starts during overlapping grace period
        setProcessState(ActivityManager.PROCESS_STATE_TOP);
        advanceElapsedClock(SECOND_IN_MILLIS);
        setProcessState(ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND);
        advanceElapsedClock(SECOND_IN_MILLIS);
        mTempAllowlistListener.onAppAdded(mSourceUid);
        advanceElapsedClock(SECOND_IN_MILLIS);
        mTempAllowlistListener.onAppRemoved(mSourceUid);
        advanceElapsedClock(gracePeriodMs - SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(job5, null);
            mQuotaController.prepareForExecutionLocked(job5);
        }
        advanceElapsedClock(SECOND_IN_MILLIS);
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        // Wait for the grace period to expire so the handler can process the message.
        Thread.sleep(2 * gracePeriodMs);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(job5, job5, true);
        }
        expected.add(createTimingSession(start, 10 * SECOND_IN_MILLIS, 1));
        assertEquals(expected,
                mQuotaController.getEJTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
    }

    /**
     * Tests that expedited jobs aren't stopped when an app runs out of quota.
     */
    @Test
    public void testEJTracking_OutOfQuota_ForegroundAndBackground() {
        setDischarging();
        setDeviceConfigLong(QcConstants.KEY_EJ_GRACE_PERIOD_TOP_APP_MS, 0);

        JobStatus jobBg =
                createExpeditedJobStatus("testEJTracking_OutOfQuota_ForegroundAndBackground", 1);
        JobStatus jobTop =
                createExpeditedJobStatus("testEJTracking_OutOfQuota_ForegroundAndBackground", 2);
        JobStatus jobUnstarted =
                createExpeditedJobStatus("testEJTracking_OutOfQuota_ForegroundAndBackground", 3);
        trackJobs(jobBg, jobTop, jobUnstarted);
        setStandbyBucket(WORKING_INDEX, jobTop, jobBg, jobUnstarted);
        // Now the package only has 20 seconds to run.
        final long remainingTimeMs = 20 * SECOND_IN_MILLIS;
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(
                        JobSchedulerService.sElapsedRealtimeClock.millis() - HOUR_IN_MILLIS,
                        mQcConstants.EJ_LIMIT_WORKING_MS - remainingTimeMs, 1), true);

        InOrder inOrder = inOrder(mJobSchedulerService);

        // UID starts out inactive.
        setProcessState(ActivityManager.PROCESS_STATE_SERVICE);
        // Start the job.
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobBg);
        }
        advanceElapsedClock(remainingTimeMs / 2);
        // New job starts after UID is in the foreground. Since the app is now in the foreground, it
        // should continue to have remainingTimeMs / 2 time remaining.
        setProcessState(ActivityManager.PROCESS_STATE_TOP);
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobTop);
        }
        advanceElapsedClock(remainingTimeMs);

        // Wait for some extra time to allow for job processing.
        inOrder.verify(mJobSchedulerService,
                        timeout(remainingTimeMs + 2 * SECOND_IN_MILLIS).times(0))
                .onControllerStateChanged(argThat(jobs -> jobs.size() > 0));
        synchronized (mQuotaController.mLock) {
            assertEquals(remainingTimeMs / 2,
                    mQuotaController.getRemainingEJExecutionTimeLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE));
        }
        // Go to a background state.
        setProcessState(ActivityManager.PROCESS_STATE_TOP_SLEEPING);
        advanceElapsedClock(remainingTimeMs / 2 + 1);
        inOrder.verify(mJobSchedulerService,
                        timeout(remainingTimeMs / 2 + 2 * SECOND_IN_MILLIS).times(1))
                .onControllerStateChanged(argThat(jobs -> jobs.size() == 2));
        // Top should still be "in quota" since it started before the app ran on top out of quota.
        assertFalse(jobBg.isExpeditedQuotaApproved());
        assertTrue(jobTop.isExpeditedQuotaApproved());
        assertFalse(jobUnstarted.isExpeditedQuotaApproved());
        synchronized (mQuotaController.mLock) {
            assertTrue(
                    0 >= mQuotaController
                            .getRemainingEJExecutionTimeLocked(SOURCE_USER_ID, SOURCE_PACKAGE));
        }

        // New jobs to run.
        JobStatus jobBg2 =
                createExpeditedJobStatus("testEJTracking_OutOfQuota_ForegroundAndBackground", 4);
        JobStatus jobTop2 =
                createExpeditedJobStatus("testEJTracking_OutOfQuota_ForegroundAndBackground", 5);
        JobStatus jobFg =
                createExpeditedJobStatus("testEJTracking_OutOfQuota_ForegroundAndBackground", 6);
        setStandbyBucket(WORKING_INDEX, jobBg2, jobTop2, jobFg);

        advanceElapsedClock(20 * SECOND_IN_MILLIS);
        setProcessState(ActivityManager.PROCESS_STATE_TOP);
        // Confirm QC recognizes that jobUnstarted has changed from out-of-quota to in-quota.
        inOrder.verify(mJobSchedulerService, timeout(SECOND_IN_MILLIS).times(1))
                .onControllerStateChanged(argThat(jobs -> jobs.size() == 2));
        trackJobs(jobTop2, jobFg);
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobTop2);
        }
        assertTrue(jobTop2.isExpeditedQuotaApproved());
        assertTrue(jobFg.isExpeditedQuotaApproved());
        assertTrue(jobBg.isExpeditedQuotaApproved());
        assertTrue(jobUnstarted.isExpeditedQuotaApproved());

        // App still in foreground so everything should be in quota.
        advanceElapsedClock(20 * SECOND_IN_MILLIS);
        setProcessState(ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE);
        assertTrue(jobTop2.isExpeditedQuotaApproved());
        assertTrue(jobFg.isExpeditedQuotaApproved());
        assertTrue(jobBg.isExpeditedQuotaApproved());
        assertTrue(jobUnstarted.isExpeditedQuotaApproved());

        advanceElapsedClock(20 * SECOND_IN_MILLIS);
        setProcessState(ActivityManager.PROCESS_STATE_SERVICE);
        inOrder.verify(mJobSchedulerService, timeout(SECOND_IN_MILLIS).times(1))
                .onControllerStateChanged(argThat(jobs -> jobs.size() == 3));
        // App is now in background and out of quota. Fg should now change to out of quota since it
        // wasn't started. Top should remain in quota since it started when the app was in TOP.
        assertTrue(jobTop2.isExpeditedQuotaApproved());
        assertFalse(jobFg.isExpeditedQuotaApproved());
        assertFalse(jobBg.isExpeditedQuotaApproved());
        trackJobs(jobBg2);
        assertFalse(jobBg2.isExpeditedQuotaApproved());
        assertFalse(jobUnstarted.isExpeditedQuotaApproved());
        synchronized (mQuotaController.mLock) {
            assertTrue(
                    0 >= mQuotaController
                            .getRemainingEJExecutionTimeLocked(SOURCE_USER_ID, SOURCE_PACKAGE));
        }
    }

    /**
     * Tests that Timers properly track overlapping top and background jobs.
     */
    @Test
    public void testEJTimerTrackingSeparateFromRegularTracking() {
        setDischarging();
        setProcessState(ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND);

        JobStatus jobReg1 = createJobStatus("testEJTimerTrackingSeparateFromRegularTracking", 1);
        JobStatus jobEJ1 =
                createExpeditedJobStatus("testEJTimerTrackingSeparateFromRegularTracking", 2);
        JobStatus jobReg2 = createJobStatus("testEJTimerTrackingSeparateFromRegularTracking", 3);
        JobStatus jobEJ2 =
                createExpeditedJobStatus("testEJTimerTrackingSeparateFromRegularTracking", 4);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobReg1, null);
            mQuotaController.maybeStartTrackingJobLocked(jobEJ1, null);
            mQuotaController.maybeStartTrackingJobLocked(jobReg2, null);
            mQuotaController.maybeStartTrackingJobLocked(jobEJ2, null);
        }
        assertNull(mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
        assertNull(mQuotaController.getEJTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
        List<TimingSession> expectedRegular = new ArrayList<>();
        List<TimingSession> expectedEJ = new ArrayList<>();

        // First, regular job runs by itself.
        long start = JobSchedulerService.sElapsedRealtimeClock.millis();
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobReg1);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobReg1, jobReg1, true);
        }
        expectedRegular.add(createTimingSession(start, 10 * SECOND_IN_MILLIS, 1));
        assertEquals(expectedRegular,
                mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
        assertNull(mQuotaController.getEJTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        advanceElapsedClock(SECOND_IN_MILLIS);

        // Next, EJ runs by itself.
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobEJ1);
        }
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobEJ1, null, false);
        }
        expectedEJ.add(createTimingSession(start, 10 * SECOND_IN_MILLIS, 1));
        assertEquals(expectedRegular,
                mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
        assertEquals(expectedEJ,
                mQuotaController.getEJTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        advanceElapsedClock(SECOND_IN_MILLIS);

        // Finally, a regular job and EJ happen to overlap runs.
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobEJ2);
        }
        advanceElapsedClock(5 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.prepareForExecutionLocked(jobReg2);
        }
        advanceElapsedClock(5 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobEJ2, null, false);
        }
        expectedEJ.add(createTimingSession(start, 10 * SECOND_IN_MILLIS, 1));
        advanceElapsedClock(5 * SECOND_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(jobReg2, null, false);
        }
        expectedRegular.add(
                createTimingSession(start + 5 * SECOND_IN_MILLIS, 10 * SECOND_IN_MILLIS, 1));
        assertEquals(expectedRegular,
                mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
        assertEquals(expectedEJ,
                mQuotaController.getEJTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
    }

    /**
     * Tests that a job is properly handled when it's at the edge of its quota and the old quota is
     * being phased out.
     */
    @Test
    public void testEJTracking_RollingQuota() {
        JobStatus jobStatus = createExpeditedJobStatus("testEJTracking_RollingQuota", 1);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);
        }
        setStandbyBucket(WORKING_INDEX, jobStatus);
        setProcessState(ActivityManager.PROCESS_STATE_SERVICE);
        Handler handler = mQuotaController.getHandler();
        spyOn(handler);

        long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        final long remainingTimeMs = SECOND_IN_MILLIS;
        // The package only has one second to run, but this session is at the edge of the rolling
        // window, so as the package "reaches its quota" it will have more to keep running.
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - mQcConstants.EJ_WINDOW_SIZE_MS,
                        10 * SECOND_IN_MILLIS - remainingTimeMs, 1), true);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - HOUR_IN_MILLIS,
                        mQcConstants.EJ_LIMIT_WORKING_MS - 10 * SECOND_IN_MILLIS, 1), true);

        synchronized (mQuotaController.mLock) {
            assertEquals(remainingTimeMs,
                    mQuotaController.getRemainingEJExecutionTimeLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE));

            // Start the job.
            mQuotaController.prepareForExecutionLocked(jobStatus);
        }
        advanceElapsedClock(remainingTimeMs);

        // Wait for some extra time to allow for job processing.
        verify(mJobSchedulerService,
                timeout(remainingTimeMs + 2 * SECOND_IN_MILLIS).times(0))
                .onControllerStateChanged(argThat(jobs -> jobs.size() > 0));
        assertTrue(jobStatus.isExpeditedQuotaApproved());
        // The job used up the remaining quota, but in that time, the same amount of time in the
        // old TimingSession also fell out of the quota window, so it should still have the same
        // amount of remaining time left its quota.
        synchronized (mQuotaController.mLock) {
            assertEquals(remainingTimeMs,
                    mQuotaController.getRemainingEJExecutionTimeLocked(
                            SOURCE_USER_ID, SOURCE_PACKAGE));
        }
        // Handler is told to check when the quota will be consumed, not when the initial
        // remaining time is over.
        verify(handler, atLeast(1)).sendMessageDelayed(
                argThat(msg -> msg.what == QuotaController.MSG_REACHED_EJ_QUOTA),
                eq(10 * SECOND_IN_MILLIS));
        verify(handler, never()).sendMessageDelayed(any(), eq(remainingTimeMs));
    }

    @Test
    public void testEJDebitTallying() {
        setStandbyBucket(RARE_INDEX);
        setProcessState(ActivityManager.PROCESS_STATE_SERVICE);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_RARE_MS, 10 * MINUTE_IN_MILLIS);
        // 15 seconds for each 30 second chunk.
        setDeviceConfigLong(QcConstants.KEY_EJ_TOP_APP_TIME_CHUNK_SIZE_MS, 30 * SECOND_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_REWARD_TOP_APP_MS, 15 * SECOND_IN_MILLIS);

        // No history. Debits should be 0.
        ShrinkableDebits debit = mQuotaController.getEJDebitsLocked(SOURCE_USER_ID, SOURCE_PACKAGE);
        assertEquals(0, debit.getTallyLocked());
        assertEquals(10 * MINUTE_IN_MILLIS,
                mQuotaController.getRemainingEJExecutionTimeLocked(SOURCE_USER_ID, SOURCE_PACKAGE));

        // Regular job shouldn't affect EJ tally.
        JobStatus regJob = createJobStatus("testEJDebitTallying", 1);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(regJob, null);
            mQuotaController.prepareForExecutionLocked(regJob);
        }
        advanceElapsedClock(5000);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(regJob, null, false);
        }
        assertEquals(0, debit.getTallyLocked());
        assertEquals(10 * MINUTE_IN_MILLIS,
                mQuotaController.getRemainingEJExecutionTimeLocked(SOURCE_USER_ID, SOURCE_PACKAGE));

        // EJ job should affect EJ tally.
        JobStatus eJob = createExpeditedJobStatus("testEJDebitTallying", 2);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(eJob, null);
            mQuotaController.prepareForExecutionLocked(eJob);
        }
        advanceElapsedClock(5 * MINUTE_IN_MILLIS);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStopTrackingJobLocked(eJob, null, false);
        }
        assertEquals(5 * MINUTE_IN_MILLIS, debit.getTallyLocked());
        assertEquals(5 * MINUTE_IN_MILLIS,
                mQuotaController.getRemainingEJExecutionTimeLocked(SOURCE_USER_ID, SOURCE_PACKAGE));

        // Instantaneous event for a different user shouldn't affect tally.
        advanceElapsedClock(5 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_REWARD_INTERACTION_MS, MINUTE_IN_MILLIS);

        UsageEvents.Event event =
                new UsageEvents.Event(UsageEvents.Event.USER_INTERACTION, sSystemClock.millis());
        event.mPackage = SOURCE_PACKAGE;
        mUsageEventListener.onUsageEvent(SOURCE_USER_ID + 10, event);
        assertEquals(5 * MINUTE_IN_MILLIS, debit.getTallyLocked());

        // Instantaneous event for correct user should reduce tally.
        advanceElapsedClock(5 * MINUTE_IN_MILLIS);

        mUsageEventListener.onUsageEvent(SOURCE_USER_ID, event);
        waitForNonDelayedMessagesProcessed();
        assertEquals(4 * MINUTE_IN_MILLIS, debit.getTallyLocked());
        assertEquals(6 * MINUTE_IN_MILLIS,
                mQuotaController.getRemainingEJExecutionTimeLocked(SOURCE_USER_ID, SOURCE_PACKAGE));

        // Activity start shouldn't reduce tally, but duration with activity started should affect
        // remaining EJ time.
        advanceElapsedClock(5 * MINUTE_IN_MILLIS);
        event = new UsageEvents.Event(UsageEvents.Event.ACTIVITY_RESUMED, sSystemClock.millis());
        event.mPackage = SOURCE_PACKAGE;
        mUsageEventListener.onUsageEvent(SOURCE_USER_ID, event);
        waitForNonDelayedMessagesProcessed();
        advanceElapsedClock(30 * SECOND_IN_MILLIS);
        assertEquals(4 * MINUTE_IN_MILLIS, debit.getTallyLocked());
        assertEquals(6 * MINUTE_IN_MILLIS + 15 * SECOND_IN_MILLIS,
                mQuotaController.getRemainingEJExecutionTimeLocked(SOURCE_USER_ID, SOURCE_PACKAGE));
        advanceElapsedClock(30 * SECOND_IN_MILLIS);
        assertEquals(4 * MINUTE_IN_MILLIS, debit.getTallyLocked());
        assertEquals(6 * MINUTE_IN_MILLIS + 30 * SECOND_IN_MILLIS,
                mQuotaController.getRemainingEJExecutionTimeLocked(SOURCE_USER_ID, SOURCE_PACKAGE));

        // With activity pausing/stopping/destroying, tally should be updated.
        advanceElapsedClock(MINUTE_IN_MILLIS);
        event = new UsageEvents.Event(UsageEvents.Event.ACTIVITY_DESTROYED, sSystemClock.millis());
        event.mPackage = SOURCE_PACKAGE;
        mUsageEventListener.onUsageEvent(SOURCE_USER_ID, event);
        waitForNonDelayedMessagesProcessed();
        assertEquals(3 * MINUTE_IN_MILLIS, debit.getTallyLocked());
        assertEquals(7 * MINUTE_IN_MILLIS,
                mQuotaController.getRemainingEJExecutionTimeLocked(SOURCE_USER_ID, SOURCE_PACKAGE));
    }

    @Test
    public void testEJDebitTallying_StaleSession() {
        setStandbyBucket(RARE_INDEX);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_RARE_MS, 10 * MINUTE_IN_MILLIS);

        final long nowElapsed = sElapsedRealtimeClock.millis();
        TimingSession ts = new TimingSession(nowElapsed, nowElapsed + 10 * MINUTE_IN_MILLIS, 5);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE, ts, true);

        // Make the session stale.
        advanceElapsedClock(12 * MINUTE_IN_MILLIS + mQcConstants.EJ_WINDOW_SIZE_MS);

        // With lazy deletion, we don't update the tally until getRemainingEJExecutionTimeLocked()
        // is called, so call that first.
        assertEquals(10 * MINUTE_IN_MILLIS,
                mQuotaController.getRemainingEJExecutionTimeLocked(SOURCE_USER_ID, SOURCE_PACKAGE));
        ShrinkableDebits debit = mQuotaController.getEJDebitsLocked(SOURCE_USER_ID, SOURCE_PACKAGE);
        assertEquals(0, debit.getTallyLocked());
    }

    /**
     * Tests that rewards are properly accounted when there's no EJ running and the rewards exceed
     * the accumulated debits.
     */
    @Test
    public void testEJDebitTallying_RewardExceedDebits_NoActiveSession() {
        setStandbyBucket(WORKING_INDEX);
        setProcessState(ActivityManager.PROCESS_STATE_SERVICE);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_WORKING_MS, 30 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_REWARD_INTERACTION_MS, MINUTE_IN_MILLIS);

        final long nowElapsed = sElapsedRealtimeClock.millis();
        TimingSession ts = new TimingSession(nowElapsed - 5 * MINUTE_IN_MILLIS,
                nowElapsed - 4 * MINUTE_IN_MILLIS, 2);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE, ts, true);

        ShrinkableDebits debit = mQuotaController.getEJDebitsLocked(SOURCE_USER_ID, SOURCE_PACKAGE);
        assertEquals(MINUTE_IN_MILLIS, debit.getTallyLocked());
        assertEquals(29 * MINUTE_IN_MILLIS,
                mQuotaController.getRemainingEJExecutionTimeLocked(SOURCE_USER_ID, SOURCE_PACKAGE));

        advanceElapsedClock(30 * SECOND_IN_MILLIS);
        UsageEvents.Event event =
                new UsageEvents.Event(UsageEvents.Event.USER_INTERACTION, sSystemClock.millis());
        event.mPackage = SOURCE_PACKAGE;
        mUsageEventListener.onUsageEvent(SOURCE_USER_ID, event);
        waitForNonDelayedMessagesProcessed();
        assertEquals(0, debit.getTallyLocked());
        assertEquals(30 * MINUTE_IN_MILLIS,
                mQuotaController.getRemainingEJExecutionTimeLocked(SOURCE_USER_ID, SOURCE_PACKAGE));

        advanceElapsedClock(MINUTE_IN_MILLIS);
        assertEquals(0, debit.getTallyLocked());
        assertEquals(30 * MINUTE_IN_MILLIS,
                mQuotaController.getRemainingEJExecutionTimeLocked(SOURCE_USER_ID, SOURCE_PACKAGE));

        // Excessive rewards don't increase maximum quota.
        event = new UsageEvents.Event(UsageEvents.Event.USER_INTERACTION, sSystemClock.millis());
        event.mPackage = SOURCE_PACKAGE;
        mUsageEventListener.onUsageEvent(SOURCE_USER_ID, event);
        waitForNonDelayedMessagesProcessed();
        assertEquals(0, debit.getTallyLocked());
        assertEquals(30 * MINUTE_IN_MILLIS,
                mQuotaController.getRemainingEJExecutionTimeLocked(SOURCE_USER_ID, SOURCE_PACKAGE));
    }

    /**
     * Tests that rewards are properly accounted when there's an active EJ running and the rewards
     * exceed the accumulated debits.
     */
    @Test
    public void testEJDebitTallying_RewardExceedDebits_ActiveSession() {
        setStandbyBucket(WORKING_INDEX);
        setProcessState(ActivityManager.PROCESS_STATE_SERVICE);
        setDeviceConfigLong(QcConstants.KEY_EJ_LIMIT_WORKING_MS, 30 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_REWARD_INTERACTION_MS, MINUTE_IN_MILLIS);
        // 15 seconds for each 30 second chunk.
        setDeviceConfigLong(QcConstants.KEY_EJ_TOP_APP_TIME_CHUNK_SIZE_MS, 30 * SECOND_IN_MILLIS);
        setDeviceConfigLong(QcConstants.KEY_EJ_REWARD_TOP_APP_MS, 15 * SECOND_IN_MILLIS);

        final long nowElapsed = sElapsedRealtimeClock.millis();
        TimingSession ts = new TimingSession(nowElapsed - 5 * MINUTE_IN_MILLIS,
                nowElapsed - 4 * MINUTE_IN_MILLIS, 2);
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE, ts, true);

        ShrinkableDebits debit = mQuotaController.getEJDebitsLocked(SOURCE_USER_ID, SOURCE_PACKAGE);
        assertEquals(MINUTE_IN_MILLIS, debit.getTallyLocked());
        assertEquals(29 * MINUTE_IN_MILLIS,
                mQuotaController.getRemainingEJExecutionTimeLocked(SOURCE_USER_ID, SOURCE_PACKAGE));

        // With rewards coming in while an EJ is running, the remaining execution time should be
        // adjusted accordingly (decrease due to EJ running + increase from reward).
        JobStatus eJob =
                createExpeditedJobStatus("testEJDebitTallying_RewardExceedDebits_ActiveSession", 1);
        synchronized (mQuotaController.mLock) {
            mQuotaController.maybeStartTrackingJobLocked(eJob, null);
            mQuotaController.prepareForExecutionLocked(eJob);
        }
        advanceElapsedClock(30 * SECOND_IN_MILLIS);
        assertEquals(MINUTE_IN_MILLIS, debit.getTallyLocked());
        assertEquals(28 * MINUTE_IN_MILLIS + 30 * SECOND_IN_MILLIS,
                mQuotaController.getRemainingEJExecutionTimeLocked(SOURCE_USER_ID, SOURCE_PACKAGE));

        advanceElapsedClock(30 * SECOND_IN_MILLIS);
        UsageEvents.Event event =
                new UsageEvents.Event(UsageEvents.Event.USER_INTERACTION, sSystemClock.millis());
        event.mPackage = SOURCE_PACKAGE;
        mUsageEventListener.onUsageEvent(SOURCE_USER_ID, event);
        waitForNonDelayedMessagesProcessed();
        assertEquals(0, debit.getTallyLocked());
        assertEquals(29 * MINUTE_IN_MILLIS,
                mQuotaController.getRemainingEJExecutionTimeLocked(SOURCE_USER_ID, SOURCE_PACKAGE));

        advanceElapsedClock(MINUTE_IN_MILLIS);
        assertEquals(0, debit.getTallyLocked());
        assertEquals(28 * MINUTE_IN_MILLIS,
                mQuotaController.getRemainingEJExecutionTimeLocked(SOURCE_USER_ID, SOURCE_PACKAGE));

        // Activity start shouldn't reduce tally, but duration with activity started should affect
        // remaining EJ time.
        event = new UsageEvents.Event(UsageEvents.Event.ACTIVITY_RESUMED, sSystemClock.millis());
        event.mPackage = SOURCE_PACKAGE;
        mUsageEventListener.onUsageEvent(SOURCE_USER_ID, event);
        waitForNonDelayedMessagesProcessed();
        advanceElapsedClock(30 * SECOND_IN_MILLIS);
        assertEquals(0, debit.getTallyLocked());
        // Decrease by 30 seconds for running EJ, increase by 15 seconds due to ongoing activity.
        assertEquals(27 * MINUTE_IN_MILLIS + 45 * SECOND_IN_MILLIS,
                mQuotaController.getRemainingEJExecutionTimeLocked(SOURCE_USER_ID, SOURCE_PACKAGE));
        advanceElapsedClock(30 * SECOND_IN_MILLIS);
        assertEquals(0, debit.getTallyLocked());
        assertEquals(27 * MINUTE_IN_MILLIS + 30 * SECOND_IN_MILLIS,
                mQuotaController.getRemainingEJExecutionTimeLocked(SOURCE_USER_ID, SOURCE_PACKAGE));

        advanceElapsedClock(MINUTE_IN_MILLIS);
        assertEquals(0, debit.getTallyLocked());
        assertEquals(27 * MINUTE_IN_MILLIS,
                mQuotaController.getRemainingEJExecutionTimeLocked(SOURCE_USER_ID, SOURCE_PACKAGE));

        event = new UsageEvents.Event(UsageEvents.Event.USER_INTERACTION, sSystemClock.millis());
        event.mPackage = SOURCE_PACKAGE;
        mUsageEventListener.onUsageEvent(SOURCE_USER_ID, event);
        waitForNonDelayedMessagesProcessed();
        assertEquals(0, debit.getTallyLocked());
        assertEquals(28 * MINUTE_IN_MILLIS,
                mQuotaController.getRemainingEJExecutionTimeLocked(SOURCE_USER_ID, SOURCE_PACKAGE));

        advanceElapsedClock(MINUTE_IN_MILLIS);
        assertEquals(0, debit.getTallyLocked());
        assertEquals(27 * MINUTE_IN_MILLIS + 30 * SECOND_IN_MILLIS,
                mQuotaController.getRemainingEJExecutionTimeLocked(SOURCE_USER_ID, SOURCE_PACKAGE));

        // At this point, with activity pausing/stopping/destroying, since we're giving a reward,
        // tally should remain 0, and time remaining shouldn't change since it was accounted for
        // at every step.
        event = new UsageEvents.Event(UsageEvents.Event.ACTIVITY_DESTROYED, sSystemClock.millis());
        event.mPackage = SOURCE_PACKAGE;
        mUsageEventListener.onUsageEvent(SOURCE_USER_ID, event);
        waitForNonDelayedMessagesProcessed();
        assertEquals(0, debit.getTallyLocked());
        assertEquals(27 * MINUTE_IN_MILLIS + 30 * SECOND_IN_MILLIS,
                mQuotaController.getRemainingEJExecutionTimeLocked(SOURCE_USER_ID, SOURCE_PACKAGE));
    }
}

/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;
import static com.android.server.job.JobSchedulerService.FREQUENT_INDEX;
import static com.android.server.job.controllers.JobStatus.CONSTRAINT_BACKGROUND_NOT_RESTRICTED;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.app.ActivityManagerInternal;
import android.app.AppGlobals;
import android.app.job.JobInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.net.Uri;
import android.os.UserHandle;
import android.platform.test.flag.junit.SetFlagsRule;
import android.util.ArraySet;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.AppStateTracker;
import com.android.server.AppStateTrackerImpl;
import com.android.server.LocalServices;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.JobStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

@RunWith(AndroidJUnit4.class)
public class BackgroundJobsControllerTest {
    private static final int CALLING_UID = 1000;
    private static final String CALLING_PACKAGE = "com.test.calling.package";
    private static final String SOURCE_PACKAGE = "com.android.frameworks.mockingservicestests";
    private static final int SOURCE_UID = 10001;
    private static final int ALTERNATE_UID = 12345;
    private static final String ALTERNATE_SOURCE_PACKAGE = "com.test.alternate.package";
    private static final int SOURCE_USER_ID = 0;

    private BackgroundJobsController mBackgroundJobsController;
    private BroadcastReceiver mStoppedReceiver;
    private JobStore mJobStore;

    private MockitoSession mMockingSession;
    @Mock
    private Context mContext;
    @Mock
    private AppStateTrackerImpl mAppStateTrackerImpl;
    @Mock
    private IPackageManager mIPackageManager;
    @Mock
    private JobSchedulerService mJobSchedulerService;
    @Mock
    private PackageManagerInternal mPackageManagerInternal;
    @Mock
    private PackageManager mPackageManager;

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Before
    public void setUp() throws Exception {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .mockStatic(AppGlobals.class)
                .mockStatic(LocalServices.class)
                .strictness(Strictness.LENIENT)
                .startMocking();

        // Called in StateController constructor.
        when(mJobSchedulerService.getTestableContext()).thenReturn(mContext);
        when(mJobSchedulerService.getLock()).thenReturn(mJobSchedulerService);
        // Called in BackgroundJobsController constructor.
        doReturn(mock(ActivityManagerInternal.class))
                .when(() -> LocalServices.getService(ActivityManagerInternal.class));
        doReturn(mAppStateTrackerImpl)
                .when(() -> LocalServices.getService(AppStateTracker.class));
        doReturn(mPackageManagerInternal)
                .when(() -> LocalServices.getService(PackageManagerInternal.class));
        mJobStore = JobStore.initAndGetForTesting(mContext, mContext.getFilesDir());
        when(mJobSchedulerService.getJobStore()).thenReturn(mJobStore);
        // Called in JobStatus constructor.
        doReturn(mIPackageManager).when(AppGlobals::getPackageManager);

        doReturn(false).when(mAppStateTrackerImpl)
                .areJobsRestricted(anyInt(), anyString(), anyBoolean());
        doReturn(true).when(mAppStateTrackerImpl)
                .isRunAnyInBackgroundAppOpsAllowed(anyInt(), anyString());

        // Initialize real objects.
        // Capture the listeners.
        ArgumentCaptor<BroadcastReceiver> receiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);

        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        mBackgroundJobsController = new BackgroundJobsController(mJobSchedulerService);
        mBackgroundJobsController.startTrackingLocked();

        verify(mContext).registerReceiverAsUser(receiverCaptor.capture(), any(),
                ArgumentMatchers.argThat(filter ->
                        filter.hasAction(Intent.ACTION_PACKAGE_RESTARTED)
                                && filter.hasAction(Intent.ACTION_PACKAGE_UNSTOPPED)),
                any(), any());
        mStoppedReceiver = receiverCaptor.getValue();

        // Need to do this since we're using a mock JS and not a real object.
        doReturn(new ArraySet<>(new String[]{SOURCE_PACKAGE}))
                .when(mJobSchedulerService).getPackagesForUidLocked(SOURCE_UID);
        doReturn(new ArraySet<>(new String[]{ALTERNATE_SOURCE_PACKAGE}))
                .when(mJobSchedulerService).getPackagesForUidLocked(ALTERNATE_UID);
        setPackageUid(ALTERNATE_UID, ALTERNATE_SOURCE_PACKAGE);
        setPackageUid(SOURCE_UID, SOURCE_PACKAGE);
    }

    @After
    public void tearDown() {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    private void setPackageUid(final int uid, final String pkgName) throws Exception {
        doReturn(uid).when(mIPackageManager)
                .getPackageUid(eq(pkgName), anyLong(), eq(UserHandle.getUserId(uid)));
    }

    private void setStoppedState(int uid, String pkgName, boolean stopped) {
        try {
            doReturn(stopped).when(mPackageManagerInternal).isPackageStopped(pkgName, uid);
            sendPackageStoppedBroadcast(uid, pkgName, stopped);
        } catch (PackageManager.NameNotFoundException e) {
            fail("Unable to set stopped state for unknown package: " + pkgName);
        }
    }

    private void sendPackageStoppedBroadcast(int uid, String pkgName, boolean stopped) {
        Intent intent = new Intent(
                stopped ? Intent.ACTION_PACKAGE_RESTARTED : Intent.ACTION_PACKAGE_UNSTOPPED);
        intent.putExtra(Intent.EXTRA_UID, uid);
        intent.setData(Uri.fromParts(IntentFilter.SCHEME_PACKAGE, pkgName, null));
        mStoppedReceiver.onReceive(mContext, intent);
    }

    private void trackJobs(JobStatus... jobs) {
        for (JobStatus job : jobs) {
            mJobStore.add(job);
            synchronized (mBackgroundJobsController.mLock) {
                mBackgroundJobsController.maybeStartTrackingJobLocked(job, null);
            }
        }
    }

    private JobInfo.Builder createBaseJobInfoBuilder(String pkgName, int jobId) {
        final ComponentName cn = spy(new ComponentName(pkgName, "TestBJCJobService"));
        doReturn("TestBJCJobService").when(cn).flattenToShortString();
        return new JobInfo.Builder(jobId, cn);
    }

    private JobStatus createJobStatus(String testTag, String packageName, int callingUid,
            JobInfo jobInfo) {
        JobStatus js = JobStatus.createFromJobInfo(
                jobInfo, callingUid, packageName, SOURCE_USER_ID, "BJCTest", testTag);
        js.serviceProcessName = "testProcess";
        // Make sure tests aren't passing just because the default bucket is likely ACTIVE.
        js.setStandbyBucket(FREQUENT_INDEX);
        return js;
    }

    @Test
    public void testRestartedBroadcastWithoutStopping() {
        mSetFlagsRule.enableFlags(android.content.pm.Flags.FLAG_STAY_STOPPED);
        // Scheduled by SOURCE_UID:SOURCE_PACKAGE for itself.
        JobStatus directJob1 = createJobStatus("testStopped", SOURCE_PACKAGE, SOURCE_UID,
                createBaseJobInfoBuilder(SOURCE_PACKAGE, 1).build());
        // Scheduled by ALTERNATE_UID:ALTERNATE_SOURCE_PACKAGE for itself.
        JobStatus directJob2 = createJobStatus("testStopped",
                ALTERNATE_SOURCE_PACKAGE, ALTERNATE_UID,
                createBaseJobInfoBuilder(ALTERNATE_SOURCE_PACKAGE, 2).build());
        // Scheduled by CALLING_PACKAGE for SOURCE_PACKAGE.
        JobStatus proxyJob1 = createJobStatus("testStopped", SOURCE_PACKAGE, CALLING_UID,
                createBaseJobInfoBuilder(CALLING_PACKAGE, 3).build());
        // Scheduled by CALLING_PACKAGE for ALTERNATE_SOURCE_PACKAGE.
        JobStatus proxyJob2 = createJobStatus("testStopped",
                ALTERNATE_SOURCE_PACKAGE, CALLING_UID,
                createBaseJobInfoBuilder(CALLING_PACKAGE, 4).build());

        trackJobs(directJob1, directJob2, proxyJob1, proxyJob2);

        sendPackageStoppedBroadcast(ALTERNATE_UID, ALTERNATE_SOURCE_PACKAGE, true);
        assertTrue(directJob1.isConstraintSatisfied(CONSTRAINT_BACKGROUND_NOT_RESTRICTED));
        assertFalse(directJob1.isUserBgRestricted());
        assertTrue(directJob2.isConstraintSatisfied(CONSTRAINT_BACKGROUND_NOT_RESTRICTED));
        assertFalse(directJob2.isUserBgRestricted());
        assertTrue(proxyJob1.isConstraintSatisfied(CONSTRAINT_BACKGROUND_NOT_RESTRICTED));
        assertFalse(proxyJob1.isUserBgRestricted());
        assertTrue(proxyJob2.isConstraintSatisfied(CONSTRAINT_BACKGROUND_NOT_RESTRICTED));
        assertFalse(proxyJob2.isUserBgRestricted());

        sendPackageStoppedBroadcast(ALTERNATE_UID, ALTERNATE_SOURCE_PACKAGE, false);
        assertTrue(directJob1.isConstraintSatisfied(CONSTRAINT_BACKGROUND_NOT_RESTRICTED));
        assertFalse(directJob1.isUserBgRestricted());
        assertTrue(directJob2.isConstraintSatisfied(CONSTRAINT_BACKGROUND_NOT_RESTRICTED));
        assertFalse(directJob2.isUserBgRestricted());
        assertTrue(proxyJob1.isConstraintSatisfied(CONSTRAINT_BACKGROUND_NOT_RESTRICTED));
        assertFalse(proxyJob1.isUserBgRestricted());
        assertTrue(proxyJob2.isConstraintSatisfied(CONSTRAINT_BACKGROUND_NOT_RESTRICTED));
        assertFalse(proxyJob2.isUserBgRestricted());
    }

    @Test
    public void testStopped_disabled() {
        mSetFlagsRule.disableFlags(android.content.pm.Flags.FLAG_STAY_STOPPED);
        // Scheduled by SOURCE_UID:SOURCE_PACKAGE for itself.
        JobStatus directJob1 = createJobStatus("testStopped", SOURCE_PACKAGE, SOURCE_UID,
                createBaseJobInfoBuilder(SOURCE_PACKAGE, 1).build());
        // Scheduled by ALTERNATE_UID:ALTERNATE_SOURCE_PACKAGE for itself.
        JobStatus directJob2 = createJobStatus("testStopped",
                ALTERNATE_SOURCE_PACKAGE, ALTERNATE_UID,
                createBaseJobInfoBuilder(ALTERNATE_SOURCE_PACKAGE, 2).build());
        // Scheduled by CALLING_PACKAGE for SOURCE_PACKAGE.
        JobStatus proxyJob1 = createJobStatus("testStopped", SOURCE_PACKAGE, CALLING_UID,
                createBaseJobInfoBuilder(CALLING_PACKAGE, 3).build());
        // Scheduled by CALLING_PACKAGE for ALTERNATE_SOURCE_PACKAGE.
        JobStatus proxyJob2 = createJobStatus("testStopped",
                ALTERNATE_SOURCE_PACKAGE, CALLING_UID,
                createBaseJobInfoBuilder(CALLING_PACKAGE, 4).build());

        trackJobs(directJob1, directJob2, proxyJob1, proxyJob2);

        setStoppedState(ALTERNATE_UID, ALTERNATE_SOURCE_PACKAGE, true);
        assertTrue(directJob1.isConstraintSatisfied(CONSTRAINT_BACKGROUND_NOT_RESTRICTED));
        assertFalse(directJob1.isUserBgRestricted());
        assertTrue(directJob2.isConstraintSatisfied(CONSTRAINT_BACKGROUND_NOT_RESTRICTED));
        assertFalse(directJob2.isUserBgRestricted());
        assertTrue(proxyJob1.isConstraintSatisfied(CONSTRAINT_BACKGROUND_NOT_RESTRICTED));
        assertFalse(proxyJob1.isUserBgRestricted());
        assertTrue(proxyJob2.isConstraintSatisfied(CONSTRAINT_BACKGROUND_NOT_RESTRICTED));
        assertFalse(proxyJob2.isUserBgRestricted());

        setStoppedState(ALTERNATE_UID, ALTERNATE_SOURCE_PACKAGE, false);
        assertTrue(directJob1.isConstraintSatisfied(CONSTRAINT_BACKGROUND_NOT_RESTRICTED));
        assertFalse(directJob1.isUserBgRestricted());
        assertTrue(directJob2.isConstraintSatisfied(CONSTRAINT_BACKGROUND_NOT_RESTRICTED));
        assertFalse(directJob2.isUserBgRestricted());
        assertTrue(proxyJob1.isConstraintSatisfied(CONSTRAINT_BACKGROUND_NOT_RESTRICTED));
        assertFalse(proxyJob1.isUserBgRestricted());
        assertTrue(proxyJob2.isConstraintSatisfied(CONSTRAINT_BACKGROUND_NOT_RESTRICTED));
        assertFalse(proxyJob2.isUserBgRestricted());
    }

    @Test
    public void testStopped_enabled() {
        mSetFlagsRule.enableFlags(android.content.pm.Flags.FLAG_STAY_STOPPED);
        // Scheduled by SOURCE_UID:SOURCE_PACKAGE for itself.
        JobStatus directJob1 = createJobStatus("testStopped", SOURCE_PACKAGE, SOURCE_UID,
                createBaseJobInfoBuilder(SOURCE_PACKAGE, 1).build());
        // Scheduled by ALTERNATE_UID:ALTERNATE_SOURCE_PACKAGE for itself.
        JobStatus directJob2 = createJobStatus("testStopped",
                ALTERNATE_SOURCE_PACKAGE, ALTERNATE_UID,
                createBaseJobInfoBuilder(ALTERNATE_SOURCE_PACKAGE, 2).build());
        // Scheduled by CALLING_PACKAGE for SOURCE_PACKAGE.
        JobStatus proxyJob1 = createJobStatus("testStopped", SOURCE_PACKAGE, CALLING_UID,
                createBaseJobInfoBuilder(CALLING_PACKAGE, 3).build());
        // Scheduled by CALLING_PACKAGE for ALTERNATE_SOURCE_PACKAGE.
        JobStatus proxyJob2 = createJobStatus("testStopped",
                ALTERNATE_SOURCE_PACKAGE, CALLING_UID,
                createBaseJobInfoBuilder(CALLING_PACKAGE, 4).build());

        trackJobs(directJob1, directJob2, proxyJob1, proxyJob2);

        setStoppedState(ALTERNATE_UID, ALTERNATE_SOURCE_PACKAGE, true);
        assertTrue(directJob1.isConstraintSatisfied(CONSTRAINT_BACKGROUND_NOT_RESTRICTED));
        assertFalse(directJob1.isUserBgRestricted());
        assertFalse(directJob2.isConstraintSatisfied(CONSTRAINT_BACKGROUND_NOT_RESTRICTED));
        assertTrue(directJob2.isUserBgRestricted());
        assertTrue(proxyJob1.isConstraintSatisfied(CONSTRAINT_BACKGROUND_NOT_RESTRICTED));
        assertFalse(proxyJob1.isUserBgRestricted());
        assertFalse(proxyJob2.isConstraintSatisfied(CONSTRAINT_BACKGROUND_NOT_RESTRICTED));
        assertTrue(proxyJob2.isUserBgRestricted());

        setStoppedState(ALTERNATE_UID, ALTERNATE_SOURCE_PACKAGE, false);
        assertTrue(directJob1.isConstraintSatisfied(CONSTRAINT_BACKGROUND_NOT_RESTRICTED));
        assertFalse(directJob1.isUserBgRestricted());
        assertTrue(directJob2.isConstraintSatisfied(CONSTRAINT_BACKGROUND_NOT_RESTRICTED));
        assertFalse(directJob2.isUserBgRestricted());
        assertTrue(proxyJob1.isConstraintSatisfied(CONSTRAINT_BACKGROUND_NOT_RESTRICTED));
        assertFalse(proxyJob1.isUserBgRestricted());
        assertTrue(proxyJob2.isConstraintSatisfied(CONSTRAINT_BACKGROUND_NOT_RESTRICTED));
        assertFalse(proxyJob2.isUserBgRestricted());
    }
}

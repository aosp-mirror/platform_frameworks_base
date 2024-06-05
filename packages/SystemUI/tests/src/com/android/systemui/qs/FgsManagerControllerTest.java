/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.qs;

import static android.os.PowerExemptionManager.REASON_ALLOWLISTED_PACKAGE;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.IActivityManager;
import android.app.IForegroundServiceObserver;
import android.app.job.IUserVisibleJobObserver;
import android.app.job.JobScheduler;
import android.app.job.UserVisibleJobSummary;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.os.Binder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.testing.TestableLooper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.util.DeviceConfigProxyFake;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper
@SmallTest
public class FgsManagerControllerTest extends SysuiTestCase {

    FakeSystemClock mSystemClock;
    FakeExecutor mMainExecutor;
    FakeExecutor mBackgroundExecutor;
    DeviceConfigProxyFake mDeviceConfigProxyFake;

    @Mock
    IActivityManager mIActivityManager;
    @Mock
    JobScheduler mJobScheduler;
    @Mock
    PackageManager mPackageManager;
    @Mock
    UserTracker mUserTracker;
    @Mock
    DialogTransitionAnimator mDialogTransitionAnimator;
    @Mock
    BroadcastDispatcher mBroadcastDispatcher;
    @Mock
    DumpManager mDumpManager;
    @Mock
    SystemUIDialog.Factory mSystemUIDialogFactory;
    @Mock
    SystemUIDialog mSystemUIDialog;

    private FgsManagerController mFmc;

    private IForegroundServiceObserver mIForegroundServiceObserver;
    private IUserVisibleJobObserver mIUserVisibleJobObserver;
    private UserTracker.Callback mUserTrackerCallback;
    private BroadcastReceiver mShowFgsManagerReceiver;
    private InOrder mJobSchedulerInOrder;

    private List<UserInfo> mUserProfiles;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);

        mDeviceConfigProxyFake = new DeviceConfigProxyFake();
        mSystemClock = new FakeSystemClock();
        mMainExecutor = new FakeExecutor(mSystemClock);
        mBackgroundExecutor = new FakeExecutor(mSystemClock);
        when(mSystemUIDialogFactory.create()).thenReturn(mSystemUIDialog);

        mUserProfiles = new ArrayList<>();
        Mockito.doReturn(mUserProfiles).when(mUserTracker).getUserProfiles();

        mJobSchedulerInOrder = Mockito.inOrder(mJobScheduler);

        mFmc = createFgsManagerController();
    }

    @Test
    public void testNumPackages() throws RemoteException {
        setUserProfiles(0);

        Binder b1 = new Binder();
        Binder b2 = new Binder();
        Assert.assertEquals(0, mFmc.getNumRunningPackages());
        mIForegroundServiceObserver.onForegroundStateChanged(b1, "pkg1", 0, true);
        Assert.assertEquals(1, mFmc.getNumRunningPackages());
        mIForegroundServiceObserver.onForegroundStateChanged(b2, "pkg2", 0, true);
        Assert.assertEquals(2, mFmc.getNumRunningPackages());
        mIForegroundServiceObserver.onForegroundStateChanged(b1, "pkg1", 0, false);
        Assert.assertEquals(1, mFmc.getNumRunningPackages());
        mIForegroundServiceObserver.onForegroundStateChanged(b2, "pkg2", 0, false);
        Assert.assertEquals(0, mFmc.getNumRunningPackages());
    }

    @Test
    public void testNumPackages_jobs() throws RemoteException {
        setUserProfiles(0);
        setShowUserVisibleJobs(true);

        UserVisibleJobSummary j1 = new UserVisibleJobSummary(0, "pkg1", 0, "pkg1", null, 0);
        UserVisibleJobSummary j2 = new UserVisibleJobSummary(1, "pkg2", 0, "pkg2", null, 1);
        // pkg2 is performing work on behalf of pkg3. Since pkg2 will show the notification
        // It should be the one shown in TaskManager.
        UserVisibleJobSummary j3 = new UserVisibleJobSummary(1, "pkg2", 0, "pkg3", null, 3);
        Assert.assertEquals(0, mFmc.getNumRunningPackages());
        mIUserVisibleJobObserver.onUserVisibleJobStateChanged(j1, true);
        Assert.assertEquals(1, mFmc.getNumRunningPackages());
        mIUserVisibleJobObserver.onUserVisibleJobStateChanged(j2, true);
        Assert.assertEquals(2, mFmc.getNumRunningPackages());
        // Job3 starts running. The source package (pkg3) shouldn't matter. Since pkg2 is
        // already running, the running package count shouldn't increase.
        mIUserVisibleJobObserver.onUserVisibleJobStateChanged(j3, true);
        Assert.assertEquals(2, mFmc.getNumRunningPackages());
        mIUserVisibleJobObserver.onUserVisibleJobStateChanged(j1, false);
        Assert.assertEquals(1, mFmc.getNumRunningPackages());
        mIUserVisibleJobObserver.onUserVisibleJobStateChanged(j2, false);
        Assert.assertEquals(1, mFmc.getNumRunningPackages());
        mIUserVisibleJobObserver.onUserVisibleJobStateChanged(j3, false);
        Assert.assertEquals(0, mFmc.getNumRunningPackages());
    }

    @Test
    public void testNumPackages_FgsAndJobs() throws RemoteException {
        setUserProfiles(0);
        setShowUserVisibleJobs(true);

        Binder b1 = new Binder();
        Binder b2 = new Binder();
        UserVisibleJobSummary j1 = new UserVisibleJobSummary(0, "pkg1", 0, "pkg1", null, 0);
        UserVisibleJobSummary j3 = new UserVisibleJobSummary(1, "pkg3", 0, "pkg3", null, 1);
        Assert.assertEquals(0, mFmc.getNumRunningPackages());
        mIForegroundServiceObserver.onForegroundStateChanged(b1, "pkg1", 0, true);
        Assert.assertEquals(1, mFmc.getNumRunningPackages());
        mIForegroundServiceObserver.onForegroundStateChanged(b2, "pkg2", 0, true);
        Assert.assertEquals(2, mFmc.getNumRunningPackages());
        mIUserVisibleJobObserver.onUserVisibleJobStateChanged(j1, true);
        Assert.assertEquals(2, mFmc.getNumRunningPackages());
        mIUserVisibleJobObserver.onUserVisibleJobStateChanged(j3, true);
        Assert.assertEquals(3, mFmc.getNumRunningPackages());
        mIForegroundServiceObserver.onForegroundStateChanged(b2, "pkg2", 0, false);
        Assert.assertEquals(2, mFmc.getNumRunningPackages());
        mIUserVisibleJobObserver.onUserVisibleJobStateChanged(j3, false);
        Assert.assertEquals(1, mFmc.getNumRunningPackages());
        mIForegroundServiceObserver.onForegroundStateChanged(b1, "pkg1", 0, false);
        Assert.assertEquals(1, mFmc.getNumRunningPackages());
        mIUserVisibleJobObserver.onUserVisibleJobStateChanged(j1, false);
        Assert.assertEquals(0, mFmc.getNumRunningPackages());
    }

    @Test
    public void testNumPackagesDoesNotChangeWhenSecondFgsIsStarted() throws RemoteException {
        setUserProfiles(0);

        // Different tokens == different services
        Binder b1 = new Binder();
        Binder b2 = new Binder();
        Assert.assertEquals(0, mFmc.getNumRunningPackages());
        mIForegroundServiceObserver.onForegroundStateChanged(b1, "pkg1", 0, true);
        Assert.assertEquals(1, mFmc.getNumRunningPackages());
        mIForegroundServiceObserver.onForegroundStateChanged(b2, "pkg1", 0, true);
        Assert.assertEquals(1, mFmc.getNumRunningPackages());
        mIForegroundServiceObserver.onForegroundStateChanged(b1, "pkg1", 0, false);
        Assert.assertEquals(1, mFmc.getNumRunningPackages());
        mIForegroundServiceObserver.onForegroundStateChanged(b2, "pkg1", 0, false);
        Assert.assertEquals(0, mFmc.getNumRunningPackages());
    }

    @Test
    public void testNumPackagesListener() throws RemoteException {
        setUserProfiles(0);

        FgsManagerController.OnNumberOfPackagesChangedListener onNumberOfPackagesChangedListener =
                Mockito.mock(FgsManagerController.OnNumberOfPackagesChangedListener.class);

        mFmc.addOnNumberOfPackagesChangedListener(onNumberOfPackagesChangedListener);

        Binder b1 = new Binder();
        Binder b2 = new Binder();

        verify(onNumberOfPackagesChangedListener, never()).onNumberOfPackagesChanged(anyInt());

        mIForegroundServiceObserver.onForegroundStateChanged(b1, "pkg1", 0, true);
        mBackgroundExecutor.advanceClockToLast();
        mBackgroundExecutor.runAllReady();
        verify(onNumberOfPackagesChangedListener).onNumberOfPackagesChanged(1);

        mIForegroundServiceObserver.onForegroundStateChanged(b2, "pkg2", 0, true);
        mBackgroundExecutor.advanceClockToLast();
        mBackgroundExecutor.runAllReady();
        verify(onNumberOfPackagesChangedListener).onNumberOfPackagesChanged(2);

        mIForegroundServiceObserver.onForegroundStateChanged(b1, "pkg1", 0, false);
        mBackgroundExecutor.advanceClockToLast();
        mBackgroundExecutor.runAllReady();
        verify(onNumberOfPackagesChangedListener, times(2)).onNumberOfPackagesChanged(1);

        mIForegroundServiceObserver.onForegroundStateChanged(b2, "pkg2", 0, false);
        mBackgroundExecutor.advanceClockToLast();
        mBackgroundExecutor.runAllReady();
        verify(onNumberOfPackagesChangedListener).onNumberOfPackagesChanged(0);
    }

    @Test
    public void testChangesSinceLastDialog() throws RemoteException {
        setUserProfiles(0);

        Assert.assertFalse(mFmc.getNewChangesSinceDialogWasDismissed());
        mIForegroundServiceObserver.onForegroundStateChanged(new Binder(), "pkg", 0, true);
        Assert.assertTrue(mFmc.getNewChangesSinceDialogWasDismissed());
    }

    @Test
    public void testProfilePackagesCounted() throws RemoteException {
        setUserProfiles(0, 10);

        mIForegroundServiceObserver.onForegroundStateChanged(new Binder(), "pkg1", 0, true);
        mIForegroundServiceObserver.onForegroundStateChanged(new Binder(), "pkg2", 10, true);
        Assert.assertEquals(2, mFmc.getNumRunningPackages());
    }

    @Test
    public void testSecondaryUserPackagesAreNotCounted() throws RemoteException {
        setUserProfiles(0);

        mIForegroundServiceObserver.onForegroundStateChanged(new Binder(), "pkg1", 0, true);
        mIForegroundServiceObserver.onForegroundStateChanged(new Binder(), "pkg2", 10, true);
        Assert.assertEquals(1, mFmc.getNumRunningPackages());
    }

    @Test
    public void testSecondaryUserPackagesAreCountedWhenUserSwitch() throws RemoteException {
        setUserProfiles(0);

        mIForegroundServiceObserver.onForegroundStateChanged(new Binder(), "pkg1", 0, true);
        mIForegroundServiceObserver.onForegroundStateChanged(new Binder(), "pkg2", 10, true);
        mIForegroundServiceObserver.onForegroundStateChanged(new Binder(), "pkg3", 10, true);

        Assert.assertEquals(1, mFmc.getNumRunningPackages());

        setUserProfiles(10);
        Assert.assertEquals(2, mFmc.getNumRunningPackages());
    }

    @Test
    public void testButtonVisibilityOnShowAllowlistButtonFlagChange() throws Exception {
        setUserProfiles(0);
        setBackgroundRestrictionExemptionReason("pkg", 12345, REASON_ALLOWLISTED_PACKAGE);

        final Binder binder = new Binder();
        setShowStopButtonForUserAllowlistedApps(true);
        mIForegroundServiceObserver.onForegroundStateChanged(binder, "pkg", 0, true);
        Assert.assertEquals(1, mFmc.visibleButtonsCount());

        mIForegroundServiceObserver.onForegroundStateChanged(binder, "pkg", 0, false);
        Assert.assertEquals(0, mFmc.visibleButtonsCount());

        setShowStopButtonForUserAllowlistedApps(false);
        mIForegroundServiceObserver.onForegroundStateChanged(binder, "pkg", 0, true);
        Assert.assertEquals(0, mFmc.visibleButtonsCount());
    }

    @Test
    public void testShowUserVisibleJobsOnCreation() {
        // Test when the default is on.
        mDeviceConfigProxyFake.setProperty(DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.TASK_MANAGER_SHOW_USER_VISIBLE_JOBS,
                "true", false);
        FgsManagerController fmc = new FgsManagerControllerImpl(
                mContext,
                mMainExecutor,
                mBackgroundExecutor,
                mSystemClock,
                mIActivityManager,
                mJobScheduler,
                mPackageManager,
                mUserTracker,
                mDeviceConfigProxyFake,
                mDialogTransitionAnimator,
                mBroadcastDispatcher,
                mDumpManager,
                mSystemUIDialogFactory
        );
        fmc.init();
        Assert.assertTrue(fmc.getIncludesUserVisibleJobs());
        ArgumentCaptor<IUserVisibleJobObserver> iUserVisibleJobObserverArgumentCaptor =
                ArgumentCaptor.forClass(IUserVisibleJobObserver.class);
        mJobSchedulerInOrder.verify(mJobScheduler)
                .registerUserVisibleJobObserver(iUserVisibleJobObserverArgumentCaptor.capture());
        Assert.assertNotNull(iUserVisibleJobObserverArgumentCaptor.getValue());

        // Test when the default is off.
        mDeviceConfigProxyFake.setProperty(DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.TASK_MANAGER_SHOW_USER_VISIBLE_JOBS,
                "false", false);
        fmc = new FgsManagerControllerImpl(
                mContext,
                mMainExecutor,
                mBackgroundExecutor,
                mSystemClock,
                mIActivityManager,
                mJobScheduler,
                mPackageManager,
                mUserTracker,
                mDeviceConfigProxyFake,
                mDialogTransitionAnimator,
                mBroadcastDispatcher,
                mDumpManager,
                mSystemUIDialogFactory
        );
        fmc.init();
        Assert.assertFalse(fmc.getIncludesUserVisibleJobs());
        mJobSchedulerInOrder.verify(mJobScheduler, never()).registerUserVisibleJobObserver(any());
    }

    @Test
    public void testShowUserVisibleJobsToggling() throws Exception {
        setUserProfiles(0);
        setShowUserVisibleJobs(true);

        // pkg1 has only job
        // pkg2 has both job and fgs
        // pkg3 has only fgs
        UserVisibleJobSummary j1 = new UserVisibleJobSummary(0, "pkg1", 0, "pkg1", null, 0);
        UserVisibleJobSummary j2 = new UserVisibleJobSummary(1, "pkg2", 0, "pkg2", null, 1);
        Binder b2 = new Binder();
        Binder b3 = new Binder();

        Assert.assertEquals(0, mFmc.getNumRunningPackages());
        mIForegroundServiceObserver.onForegroundStateChanged(b2, "pkg2", 0, true);
        mIForegroundServiceObserver.onForegroundStateChanged(b3, "pkg3", 0, true);
        Assert.assertEquals(2, mFmc.getNumRunningPackages());
        mIUserVisibleJobObserver.onUserVisibleJobStateChanged(j1, true);
        mIUserVisibleJobObserver.onUserVisibleJobStateChanged(j2, true);
        Assert.assertEquals(3, mFmc.getNumRunningPackages());

        // Turn off the flag, confirm the number of packages is updated properly.
        setShowUserVisibleJobs(false);
        // Only pkg1 should be removed since the other two have fgs
        Assert.assertEquals(2, mFmc.getNumRunningPackages());

        setShowUserVisibleJobs(true);

        Assert.assertEquals(2, mFmc.getNumRunningPackages());
        mIUserVisibleJobObserver.onUserVisibleJobStateChanged(j1, true);
        mIUserVisibleJobObserver.onUserVisibleJobStateChanged(j2, true);
        Assert.assertEquals(3, mFmc.getNumRunningPackages());
    }

    private void setShowStopButtonForUserAllowlistedApps(boolean enable) {
        mDeviceConfigProxyFake.setProperty(DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.TASK_MANAGER_SHOW_STOP_BUTTON_FOR_USER_ALLOWLISTED_APPS,
                enable ? "true" : "false", false);
        mBackgroundExecutor.advanceClockToLast();
        mBackgroundExecutor.runAllReady();
    }

    private void setShowUserVisibleJobs(boolean enable) {
        if (mFmc.getIncludesUserVisibleJobs() == enable) {
            // No change.
            return;
        }

        mDeviceConfigProxyFake.setProperty(DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.TASK_MANAGER_SHOW_USER_VISIBLE_JOBS,
                enable ? "true" : "false", false);
        mBackgroundExecutor.advanceClockToLast();
        mBackgroundExecutor.runAllReady();

        ArgumentCaptor<IUserVisibleJobObserver> iUserVisibleJobObserverArgumentCaptor =
                ArgumentCaptor.forClass(IUserVisibleJobObserver.class);
        if (enable) {
            mJobSchedulerInOrder.verify(mJobScheduler).registerUserVisibleJobObserver(
                    iUserVisibleJobObserverArgumentCaptor.capture()
            );
            mIUserVisibleJobObserver = iUserVisibleJobObserverArgumentCaptor.getValue();
        } else {
            mJobSchedulerInOrder.verify(mJobScheduler).unregisterUserVisibleJobObserver(
                    eq(mIUserVisibleJobObserver)
            );
            mIUserVisibleJobObserver = null;
        }
    }

    private void setBackgroundRestrictionExemptionReason(String pkgName, int uid, int reason)
            throws Exception {
        Mockito.doReturn(uid)
                .when(mPackageManager)
                .getPackageUidAsUser(pkgName, UserHandle.getUserId(uid));
        Mockito.doReturn(reason)
                .when(mIActivityManager)
                .getBackgroundRestrictionExemptionReason(uid);
    }

    FgsManagerController createFgsManagerController() throws RemoteException {
        ArgumentCaptor<IForegroundServiceObserver> iForegroundServiceObserverArgumentCaptor =
                ArgumentCaptor.forClass(IForegroundServiceObserver.class);
        ArgumentCaptor<UserTracker.Callback> userTrackerCallbackArgumentCaptor =
                ArgumentCaptor.forClass(UserTracker.Callback.class);
        ArgumentCaptor<BroadcastReceiver> showFgsManagerReceiverArgumentCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);

        FgsManagerController result = new FgsManagerControllerImpl(
                mContext,
                mMainExecutor,
                mBackgroundExecutor,
                mSystemClock,
                mIActivityManager,
                mJobScheduler,
                mPackageManager,
                mUserTracker,
                mDeviceConfigProxyFake,
                mDialogTransitionAnimator,
                mBroadcastDispatcher,
                mDumpManager,
                mSystemUIDialogFactory
        );
        result.init();

        verify(mIActivityManager).registerForegroundServiceObserver(
                iForegroundServiceObserverArgumentCaptor.capture()
        );
        verify(mUserTracker).addCallback(
                userTrackerCallbackArgumentCaptor.capture(),
                ArgumentMatchers.eq(mBackgroundExecutor)
        );
        verify(mBroadcastDispatcher).registerReceiver(
                showFgsManagerReceiverArgumentCaptor.capture(),
                argThat(fltr -> fltr.matchAction(Intent.ACTION_SHOW_FOREGROUND_SERVICE_MANAGER)),
                eq(mMainExecutor),
                isNull(),
                eq(Context.RECEIVER_NOT_EXPORTED),
                isNull()
        );

        mIForegroundServiceObserver = iForegroundServiceObserverArgumentCaptor.getValue();
        mUserTrackerCallback = userTrackerCallbackArgumentCaptor.getValue();
        mShowFgsManagerReceiver = showFgsManagerReceiverArgumentCaptor.getValue();

        if (result.getIncludesUserVisibleJobs()) {
            ArgumentCaptor<IUserVisibleJobObserver> iUserVisibleJobObserverArgumentCaptor =
                    ArgumentCaptor.forClass(IUserVisibleJobObserver.class);
            mJobSchedulerInOrder.verify(mJobScheduler).registerUserVisibleJobObserver(
                    iUserVisibleJobObserverArgumentCaptor.capture()
            );
            mIUserVisibleJobObserver = iUserVisibleJobObserverArgumentCaptor.getValue();
        }

        return result;
    }

    private void setUserProfiles(int current, int... profileUserIds) {
        mUserProfiles.clear();
        mUserProfiles.add(new UserInfo(current, "current:" + current, 0));
        for (int id : profileUserIds) {
            mUserProfiles.add(new UserInfo(id, "profile:" + id, 0));
        }

        if (mUserTrackerCallback != null) {
            mUserTrackerCallback.onUserChanged(current, mock(Context.class));
            mUserTrackerCallback.onProfilesChanged(mUserProfiles);
        }
    }
}

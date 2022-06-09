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

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.IActivityManager;
import android.app.IForegroundServiceObserver;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.os.Binder;
import android.os.RemoteException;
import android.provider.DeviceConfig;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.animation.DialogLaunchAnimator;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.util.DeviceConfigProxyFake;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidTestingRunner.class)
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
    PackageManager mPackageManager;
    @Mock
    UserTracker mUserTracker;
    @Mock
    DialogLaunchAnimator mDialogLaunchAnimator;
    @Mock
    BroadcastDispatcher mBroadcastDispatcher;
    @Mock
    DumpManager mDumpManager;

    private FgsManagerController mFmc;

    private IForegroundServiceObserver mIForegroundServiceObserver;
    private UserTracker.Callback mUserTrackerCallback;
    private BroadcastReceiver mShowFgsManagerReceiver;

    private List<UserInfo> mUserProfiles;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);

        mDeviceConfigProxyFake = new DeviceConfigProxyFake();
        mDeviceConfigProxyFake.setProperty(DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.TASK_MANAGER_ENABLED, "true", false);
        mSystemClock = new FakeSystemClock();
        mMainExecutor = new FakeExecutor(mSystemClock);
        mBackgroundExecutor = new FakeExecutor(mSystemClock);

        mUserProfiles = new ArrayList<>();
        Mockito.doReturn(mUserProfiles).when(mUserTracker).getUserProfiles();

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

        Assert.assertFalse(mFmc.getChangesSinceDialog());
        mIForegroundServiceObserver.onForegroundStateChanged(new Binder(), "pkg", 0, true);
        Assert.assertTrue(mFmc.getChangesSinceDialog());
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



    FgsManagerController createFgsManagerController() throws RemoteException {
        ArgumentCaptor<IForegroundServiceObserver> iForegroundServiceObserverArgumentCaptor =
                ArgumentCaptor.forClass(IForegroundServiceObserver.class);
        ArgumentCaptor<UserTracker.Callback> userTrackerCallbackArgumentCaptor =
                ArgumentCaptor.forClass(UserTracker.Callback.class);
        ArgumentCaptor<BroadcastReceiver> showFgsManagerReceiverArgumentCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);

        FgsManagerController result = new FgsManagerController(
                mContext,
                mMainExecutor,
                mBackgroundExecutor,
                mSystemClock,
                mIActivityManager,
                mPackageManager,
                mUserTracker,
                mDeviceConfigProxyFake,
                mDialogLaunchAnimator,
                mBroadcastDispatcher,
                mDumpManager
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

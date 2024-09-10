/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.systemui.qs.external;

import static android.os.PowerExemptionManager.REASON_TILE_ONCLICK;
import static android.platform.test.flag.junit.FlagsParameterization.allCombinationsOf;
import static android.service.quicksettings.TileService.START_ACTIVITY_NEEDS_PENDING_INTENT;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.systemui.Flags.FLAG_QS_CUSTOM_TILE_CLICK_GUARANTEED_BUG_FIX;
import static com.android.systemui.Flags.FLAG_QS_QUICK_REBIND_ACTIVE_TILES;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.compat.CompatChanges;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IDeviceIdleController;
import android.os.UserHandle;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.FlagsParameterization;
import android.service.quicksettings.IQSService;
import android.service.quicksettings.IQSTileService;
import android.service.quicksettings.TileService;

import androidx.annotation.Nullable;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import com.google.common.truth.Truth;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.mockito.MockitoSession;

import java.util.List;

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;

@SmallTest
@RunWith(ParameterizedAndroidJunit4.class)
public class TileLifecycleManagerTest extends SysuiTestCase {

    @Parameters(name = "{0}")
    public static List<FlagsParameterization> getParams() {
        return allCombinationsOf(FLAG_QS_CUSTOM_TILE_CLICK_GUARANTEED_BUG_FIX,
                FLAG_QS_QUICK_REBIND_ACTIVE_TILES);
    }

    private final PackageManagerAdapter mMockPackageManagerAdapter =
            mock(PackageManagerAdapter.class);
    private final BroadcastDispatcher mMockBroadcastDispatcher =
            mock(BroadcastDispatcher.class);
    private final IQSTileService.Stub mMockTileService = mock(IQSTileService.Stub.class);
    private final ActivityManager mActivityManager = mock(ActivityManager.class);
    private final IDeviceIdleController mDeviceIdleController = mock(IDeviceIdleController.class);

    private ComponentName mTileServiceComponentName;
    private Intent mTileServiceIntent;
    private UserHandle mUser;
    private FakeSystemClock mClock;
    private FakeExecutor mExecutor;
    private HandlerThread mThread;
    private Handler mHandler;
    private TileLifecycleManager mStateManager;
    private TestContextWrapper mWrappedContext;
    private MockitoSession mMockitoSession;

    public TileLifecycleManagerTest(FlagsParameterization flags) {
        super();
        mSetFlagsRule.setFlagsParameterization(flags);
    }

    @Before
    public void setUp() throws Exception {
        setPackageEnabled(true);
        mTileServiceComponentName = new ComponentName(mContext, "FakeTileService.class");
        mMockitoSession = mockitoSession()
                .initMocks(this)
                .mockStatic(CompatChanges.class)
                .startMocking();

        // Stub.asInterface will just return itself.
        when(mMockTileService.queryLocalInterface(anyString())).thenReturn(mMockTileService);
        when(mMockTileService.asBinder()).thenReturn(mMockTileService);

        mContext.addMockService(mTileServiceComponentName, mMockTileService);

        mWrappedContext = new TestContextWrapper(mContext);

        mTileServiceIntent = new Intent().setComponent(mTileServiceComponentName);
        mUser = new UserHandle(UserHandle.myUserId());
        mThread = new HandlerThread("TestThread");
        mThread.start();
        mHandler = Handler.createAsync(mThread.getLooper());
        mClock = new FakeSystemClock();
        mExecutor = new FakeExecutor(mClock);
        mStateManager = new TileLifecycleManager(mHandler, mWrappedContext,
                mock(IQSService.class),
                mMockPackageManagerAdapter,
                mMockBroadcastDispatcher,
                mTileServiceIntent,
                mUser,
                mActivityManager,
                mDeviceIdleController,
                mExecutor,
                mClock);
    }

    @After
    public void tearDown() throws Exception {
        if (mMockitoSession != null) {
            mMockitoSession.finishMocking();
        }
        if (mThread != null) {
            mThread.quit();
        }

        mStateManager.handleDestroy();
    }

    private void setPackageEnabledAndActive(boolean enabled, boolean active) throws Exception {
        ServiceInfo defaultServiceInfo = null;
        if (enabled) {
            defaultServiceInfo = new ServiceInfo();
            defaultServiceInfo.metaData = new Bundle();
            defaultServiceInfo.metaData.putBoolean(TileService.META_DATA_ACTIVE_TILE, active);
            defaultServiceInfo.metaData.putBoolean(TileService.META_DATA_TOGGLEABLE_TILE, true);
        }
        when(mMockPackageManagerAdapter.getServiceInfo(any(), anyInt(), anyInt()))
                .thenReturn(defaultServiceInfo);
        when(mMockPackageManagerAdapter.getServiceInfo(any(), anyInt()))
                .thenReturn(defaultServiceInfo);
        PackageInfo defaultPackageInfo = new PackageInfo();
        when(mMockPackageManagerAdapter.getPackageInfoAsUser(anyString(), anyInt(), anyInt()))
                .thenReturn(defaultPackageInfo);
    }

    private void setPackageEnabled(boolean enabled) throws Exception {
        setPackageEnabledAndActive(enabled, true);
    }

    private void setPackageInstalledForUser(
            boolean installed,
            boolean active,
            boolean toggleable,
            int user
    ) throws Exception {
        ServiceInfo defaultServiceInfo = null;
        if (installed) {
            defaultServiceInfo = new ServiceInfo();
            defaultServiceInfo.metaData = new Bundle();
            defaultServiceInfo.metaData.putBoolean(TileService.META_DATA_ACTIVE_TILE, active);
            defaultServiceInfo.metaData
                    .putBoolean(TileService.META_DATA_TOGGLEABLE_TILE, toggleable);
            when(mMockPackageManagerAdapter.getServiceInfo(any(), anyInt(), eq(user)))
                    .thenReturn(defaultServiceInfo);
            if (user == 0) {
                when(mMockPackageManagerAdapter.getServiceInfo(any(), anyInt()))
                        .thenReturn(defaultServiceInfo);
            }
            PackageInfo defaultPackageInfo = new PackageInfo();
            when(mMockPackageManagerAdapter.getPackageInfoAsUser(anyString(), anyInt(), eq(user)))
                    .thenReturn(defaultPackageInfo);
        } else {
            when(mMockPackageManagerAdapter.getServiceInfo(any(), anyInt(), eq(user)))
                    .thenReturn(null);
            if (user == 0) {
                when(mMockPackageManagerAdapter.getServiceInfo(any(), anyInt()))
                        .thenThrow(new PackageManager.NameNotFoundException());
            }
            when(mMockPackageManagerAdapter.getPackageInfoAsUser(anyString(), anyInt(), eq(user)))
                    .thenThrow(new PackageManager.NameNotFoundException());
        }
    }

    private void verifyBind(int times) {
        assertEquals(times > 0, mContext.isBound(mTileServiceComponentName));
    }

    @Test
    public void testBind() {
        mStateManager.executeSetBindService(true);
        mExecutor.runAllReady();
        verifyBind(1);
    }

    @Test
    public void testPackageReceiverExported() throws Exception {
        // Make sure that we register a receiver
        setPackageEnabled(false);
        mStateManager.executeSetBindService(true);
        mExecutor.runAllReady();
        IntentFilter filter = mWrappedContext.mLastIntentFilter;
        assertTrue(filter.hasAction(Intent.ACTION_PACKAGE_ADDED));
        assertTrue(filter.hasAction(Intent.ACTION_PACKAGE_CHANGED));
        assertTrue(filter.hasDataScheme("package"));
        assertNotEquals(0, mWrappedContext.mLastFlag & Context.RECEIVER_EXPORTED);
    }

    @Test
    public void testUnbind() {
        mStateManager.executeSetBindService(true);
        mExecutor.runAllReady();
        mStateManager.executeSetBindService(false);
        mExecutor.runAllReady();
        assertFalse(mContext.isBound(mTileServiceComponentName));
    }

    @Test
    public void testTileServiceCallbacks() throws Exception {
        mStateManager.executeSetBindService(true);
        mExecutor.runAllReady();
        mStateManager.onTileAdded();
        verify(mMockTileService).onTileAdded();
        mStateManager.onStartListening();
        verify(mMockTileService).onStartListening();
        mStateManager.onClick(null);
        verify(mMockTileService).onClick(null);
        mStateManager.onStopListening();
        verify(mMockTileService).onStopListening();
        mStateManager.onTileRemoved();
        verify(mMockTileService).onTileRemoved();
    }

    @Test
    public void testAddedBeforeBind() throws Exception {
        mStateManager.onTileAdded();
        mStateManager.executeSetBindService(true);
        mExecutor.runAllReady();

        verifyBind(1);
        verify(mMockTileService).onTileAdded();
    }

    @Test
    public void testListeningBeforeBind() throws Exception {
        mStateManager.onTileAdded();
        mStateManager.onStartListening();
        mStateManager.executeSetBindService(true);
        mExecutor.runAllReady();

        verifyBind(1);
        verify(mMockTileService).onTileAdded();
        verify(mMockTileService).onStartListening();
    }

    @Test
    public void testClickBeforeBind() throws Exception {
        mStateManager.onTileAdded();
        mStateManager.onStartListening();
        mStateManager.onClick(null);
        mStateManager.executeSetBindService(true);
        mExecutor.runAllReady();

        verifyBind(1);
        verify(mMockTileService).onTileAdded();
        verify(mMockTileService).onStartListening();
        verify(mMockTileService).onClick(null);
    }

    @Test
    public void testListeningNotListeningBeforeBind() throws Exception {
        mStateManager.onTileAdded();
        mStateManager.onStartListening();
        mStateManager.onStopListening();
        mStateManager.executeSetBindService(true);
        mExecutor.runAllReady();

        verifyBind(1);
        mStateManager.executeSetBindService(false);
        mExecutor.runAllReady();
        assertFalse(mContext.isBound(mTileServiceComponentName));
        verify(mMockTileService, never()).onStartListening();
    }

    @Test
    @DisableFlags(FLAG_QS_CUSTOM_TILE_CLICK_GUARANTEED_BUG_FIX)
    public void testNoClickIfNotListeningAnymore() throws Exception {
        mStateManager.onTileAdded();
        mStateManager.onStartListening();
        mStateManager.onClick(null);
        mStateManager.onStopListening();
        mStateManager.executeSetBindService(true);
        mExecutor.runAllReady();

        verifyBind(1);
        mStateManager.executeSetBindService(false);
        mExecutor.runAllReady();
        assertFalse(mContext.isBound(mTileServiceComponentName));
        verify(mMockTileService, never()).onClick(null);
    }

    @Test
    @EnableFlags(FLAG_QS_CUSTOM_TILE_CLICK_GUARANTEED_BUG_FIX)
    public void testNoClickIfNotListeningBeforeClick() throws Exception {
        mStateManager.onTileAdded();
        mStateManager.onStartListening();
        mStateManager.onStopListening();
        mStateManager.onClick(null);
        mStateManager.executeSetBindService(true);
        mExecutor.runAllReady();

        verifyBind(1);
        mStateManager.executeSetBindService(false);
        mExecutor.runAllReady();
        assertFalse(mContext.isBound(mTileServiceComponentName));
        verify(mMockTileService, never()).onClick(null);
    }

    @Test
    @EnableFlags(FLAG_QS_CUSTOM_TILE_CLICK_GUARANTEED_BUG_FIX)
    public void testClickIfStopListeningBeforeProcessedClick() throws Exception {
        mStateManager.onTileAdded();
        mStateManager.onStartListening();
        mStateManager.onClick(null);
        mStateManager.onStopListening();
        mStateManager.executeSetBindService(true);
        mExecutor.runAllReady();

        verifyBind(1);
        mStateManager.executeSetBindService(false);
        mExecutor.runAllReady();
        assertFalse(mContext.isBound(mTileServiceComponentName));
        InOrder inOrder = Mockito.inOrder(mMockTileService);
        inOrder.verify(mMockTileService).onClick(null);
        inOrder.verify(mMockTileService).onStopListening();
    }

    @Test
    public void testComponentEnabling() throws Exception {
        mStateManager.onTileAdded();
        mStateManager.onStartListening();
        setPackageEnabled(false);
        mStateManager.executeSetBindService(true);
        mExecutor.runAllReady();
        // Package not available, not yet created.
        verifyBind(0);

        // Package is re-enabled.
        setPackageEnabled(true);
        mStateManager.onReceive(
                mContext,
                new Intent(
                        Intent.ACTION_PACKAGE_CHANGED,
                        Uri.fromParts(
                                "package", mTileServiceComponentName.getPackageName(), null)));
        mExecutor.runAllReady();
        verifyBind(1);
    }

    @Test
    public void testKillProcessWhenTileServiceIsNotActive() throws Exception {
        setPackageEnabledAndActive(true, false);
        mStateManager.onStartListening();
        mStateManager.executeSetBindService(true);
        mExecutor.runAllReady();
        verifyBind(1);
        verify(mMockTileService, times(1)).onStartListening();

        mStateManager.onBindingDied(mTileServiceComponentName);
        mExecutor.runAllReady();
        mClock.advanceTime(1000);
        mExecutor.runAllReady();

        // still 4 seconds left because non active tile service rebind time is 5 seconds
        Truth.assertThat(mContext.isBound(mTileServiceComponentName)).isFalse();

        mClock.advanceTime(4000); // 5 seconds delay for nonActive service rebinding
        mExecutor.runAllReady();
        verifyBind(2);
        verify(mMockTileService, times(2)).onStartListening();
    }

    @EnableFlags(FLAG_QS_QUICK_REBIND_ACTIVE_TILES)
    @Test
    public void testKillProcessWhenTileServiceIsActive_withRebindFlagOn() throws Exception {
        mStateManager.onStartListening();
        mStateManager.executeSetBindService(true);
        mExecutor.runAllReady();
        verifyBind(1);
        verify(mMockTileService, times(1)).onStartListening();

        mStateManager.onBindingDied(mTileServiceComponentName);
        mExecutor.runAllReady();
        mClock.advanceTime(1000);
        mExecutor.runAllReady();

        // Two calls: one for the first bind, one for the restart.
        verifyBind(2);
        verify(mMockTileService, times(2)).onStartListening();
    }

    @DisableFlags(FLAG_QS_QUICK_REBIND_ACTIVE_TILES)
    @Test
    public void testKillProcessWhenTileServiceIsActive_withRebindFlagOff() throws Exception {
        mStateManager.onStartListening();
        mStateManager.executeSetBindService(true);
        mExecutor.runAllReady();
        verifyBind(1);
        verify(mMockTileService, times(1)).onStartListening();

        mStateManager.onBindingDied(mTileServiceComponentName);
        mExecutor.runAllReady();
        mClock.advanceTime(1000);
        mExecutor.runAllReady();
        verifyBind(0); // the rebind happens after 4 more seconds

        mClock.advanceTime(4000);
        mExecutor.runAllReady();
        verifyBind(1);
    }

    @EnableFlags(FLAG_QS_QUICK_REBIND_ACTIVE_TILES)
    @Test
    public void testKillProcessWhenTileServiceIsActiveTwice_withRebindFlagOn_delaysSecondRebind()
            throws Exception {
        mStateManager.onStartListening();
        mStateManager.executeSetBindService(true);
        mExecutor.runAllReady();
        verifyBind(1);
        verify(mMockTileService, times(1)).onStartListening();

        mStateManager.onBindingDied(mTileServiceComponentName);
        mExecutor.runAllReady();
        mClock.advanceTime(1000);
        mExecutor.runAllReady();

        // Two calls: one for the first bind, one for the restart.
        verifyBind(2);
        verify(mMockTileService, times(2)).onStartListening();

        mStateManager.onBindingDied(mTileServiceComponentName);
        mExecutor.runAllReady();
        mClock.advanceTime(1000);
        mExecutor.runAllReady();
        // because active tile will take 5 seconds to bind the second time, not 1
        verifyBind(0);

        mClock.advanceTime(4000);
        mExecutor.runAllReady();
        verifyBind(1);
    }

    @DisableFlags(FLAG_QS_QUICK_REBIND_ACTIVE_TILES)
    @Test
    public void testKillProcessWhenTileServiceIsActiveTwice_withRebindFlagOff_rebindsFromFirstKill()
            throws Exception {
        mStateManager.onStartListening();
        mStateManager.executeSetBindService(true);
        mExecutor.runAllReady();
        verifyBind(1);
        verify(mMockTileService, times(1)).onStartListening();

        mStateManager.onBindingDied(mTileServiceComponentName); // rebind scheduled for 5 seconds
        mExecutor.runAllReady();
        mClock.advanceTime(1000);
        mExecutor.runAllReady();

        verifyBind(0); // it would bind in 4 more seconds

        mStateManager.onBindingDied(mTileServiceComponentName); // this does not affect the rebind
        mExecutor.runAllReady();
        mClock.advanceTime(1000);
        mExecutor.runAllReady();

        verifyBind(0); // only 2 seconds passed from first kill

        mClock.advanceTime(3000);
        mExecutor.runAllReady();
        verifyBind(1); // the rebind scheduled 5 seconds from the first kill should now happen
    }

    @Test
    public void testKillProcessLowMemory() throws Exception {
        doAnswer(invocation -> {
            ActivityManager.MemoryInfo memoryInfo = invocation.getArgument(0);
            memoryInfo.lowMemory = true;
            return null;
        }).when(mActivityManager).getMemoryInfo(any());
        mStateManager.onStartListening();
        mStateManager.executeSetBindService(true);
        mExecutor.runAllReady();
        verify(mMockTileService, times(1)).onStartListening();
        mStateManager.onBindingDied(mTileServiceComponentName);
        mExecutor.runAllReady();

        // Longer delay than a regular one
        mClock.advanceTime(5000);
        mExecutor.runAllReady();

        assertFalse(mContext.isBound(mTileServiceComponentName));

        mClock.advanceTime(20000);
        mExecutor.runAllReady();
        // Two calls: one for the first bind, one for the restart.
        verifyBind(2);
        verify(mMockTileService, times(2)).onStartListening();
    }

    @Test
    public void testOnServiceDisconnectedDoesnUnbind_doesntForwardToBinder() throws Exception {
        mStateManager.executeSetBindService(true);
        mExecutor.runAllReady();

        mStateManager.onStartListening();
        verify(mMockTileService).onStartListening();

        clearInvocations(mMockTileService);
        mStateManager.onServiceDisconnected(mTileServiceComponentName);
        mExecutor.runAllReady();

        mStateManager.onStartListening();
        verify(mMockTileService, never()).onStartListening();
    }

    @Test
    public void testKillProcessLowMemory_unbound_doesntBindAgain() throws Exception {
        doAnswer(invocation -> {
            ActivityManager.MemoryInfo memoryInfo = invocation.getArgument(0);
            memoryInfo.lowMemory = true;
            return null;
        }).when(mActivityManager).getMemoryInfo(any());
        mStateManager.onStartListening();
        mStateManager.executeSetBindService(true);
        mExecutor.runAllReady();
        verifyBind(1);
        verify(mMockTileService, times(1)).onStartListening();

        mStateManager.onBindingDied(mTileServiceComponentName);
        mExecutor.runAllReady();

        clearInvocations(mMockTileService);
        mStateManager.executeSetBindService(false);
        mExecutor.runAllReady();
        mClock.advanceTime(30000);
        mExecutor.runAllReady();

        verifyBind(0);
        verify(mMockTileService, never()).onStartListening();
    }

    @Test
    public void testToggleableTile() throws Exception {
        assertTrue(mStateManager.isToggleableTile());
    }

    @Test
    public void testClickCallsDeviceIdleManager() throws Exception {
        mStateManager.onTileAdded();
        mStateManager.onStartListening();
        mStateManager.onClick(null);
        mStateManager.executeSetBindService(true);
        mExecutor.runAllReady();

        verify(mMockTileService).onClick(null);
        verify(mDeviceIdleController).addPowerSaveTempWhitelistApp(
                mTileServiceComponentName.getPackageName(), 15000,
                mUser.getIdentifier(), REASON_TILE_ONCLICK, "tile onclick");
    }

    @Test
    public void testFalseBindCallsUnbind() {
        Context falseContext = mock(Context.class);
        when(falseContext.bindServiceAsUser(any(), any(), anyInt(), any())).thenReturn(false);
        TileLifecycleManager manager = new TileLifecycleManager(mHandler, falseContext,
                mock(IQSService.class),
                mMockPackageManagerAdapter,
                mMockBroadcastDispatcher,
                mTileServiceIntent,
                mUser,
                mActivityManager,
                mDeviceIdleController,
                mExecutor,
                mClock);

        manager.executeSetBindService(true);
        mExecutor.runAllReady();

        ArgumentCaptor<ServiceConnection> captor = ArgumentCaptor.forClass(ServiceConnection.class);
        verify(falseContext).bindServiceAsUser(any(), captor.capture(), anyInt(), any());

        verify(falseContext).unbindService(captor.getValue());
    }

    @Test
    public void testVersionUDoesNotBindsAllowBackgroundActivity() {
        mockChangeEnabled(START_ACTIVITY_NEEDS_PENDING_INTENT, true);
        Context falseContext = mock(Context.class);
        TileLifecycleManager manager = new TileLifecycleManager(mHandler, falseContext,
                mock(IQSService.class),
                mMockPackageManagerAdapter,
                mMockBroadcastDispatcher,
                mTileServiceIntent,
                mUser,
                mActivityManager,
                mDeviceIdleController,
                mExecutor,
                mClock);

        manager.executeSetBindService(true);
        mExecutor.runAllReady();
        int flags = Context.BIND_AUTO_CREATE
                | Context.BIND_FOREGROUND_SERVICE_WHILE_AWAKE
                | Context.BIND_WAIVE_PRIORITY;

        verify(falseContext).bindServiceAsUser(any(), any(), eq(flags), any());
    }

    @Test
    public void testVersionLessThanUBindsAllowBackgroundActivity() {
        mockChangeEnabled(START_ACTIVITY_NEEDS_PENDING_INTENT, false);
        Context falseContext = mock(Context.class);
        TileLifecycleManager manager = new TileLifecycleManager(mHandler, falseContext,
                mock(IQSService.class),
                mMockPackageManagerAdapter,
                mMockBroadcastDispatcher,
                mTileServiceIntent,
                mUser,
                mActivityManager,
                mDeviceIdleController,
                mExecutor,
                mClock);

        manager.executeSetBindService(true);
        mExecutor.runAllReady();
        int flags = Context.BIND_AUTO_CREATE
                | Context.BIND_FOREGROUND_SERVICE_WHILE_AWAKE
                | Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS
                | Context.BIND_WAIVE_PRIORITY;

        verify(falseContext).bindServiceAsUser(any(), any(), eq(flags), any());
    }

    @Test
    public void testNullBindingCallsUnbind() {
        Context mockContext = mock(Context.class);
        // Binding has to succeed
        when(mockContext.bindServiceAsUser(any(), any(), anyInt(), any())).thenReturn(true);
        TileLifecycleManager manager = new TileLifecycleManager(mHandler, mockContext,
                mock(IQSService.class),
                mMockPackageManagerAdapter,
                mMockBroadcastDispatcher,
                mTileServiceIntent,
                mUser,
                mActivityManager,
                mDeviceIdleController,
                mExecutor,
                mClock);

        manager.executeSetBindService(true);
        mExecutor.runAllReady();

        ArgumentCaptor<ServiceConnection> captor = ArgumentCaptor.forClass(ServiceConnection.class);
        verify(mockContext).bindServiceAsUser(any(), captor.capture(), anyInt(), any());

        captor.getValue().onNullBinding(mTileServiceComponentName);
        mExecutor.runAllReady();
        verify(mockContext).unbindService(captor.getValue());
    }

    @Test
    public void testIsActive_user0_packageInstalled() throws Exception {
        setPackageInstalledForUser(true, true, false, 0);
        mUser = UserHandle.of(0);

        TileLifecycleManager manager = new TileLifecycleManager(mHandler, mWrappedContext,
                mock(IQSService.class),
                mMockPackageManagerAdapter,
                mMockBroadcastDispatcher,
                mTileServiceIntent,
                mUser,
                mActivityManager,
                mDeviceIdleController,
                mExecutor,
                mClock);

        assertThat(manager.isActiveTile()).isTrue();
    }

    @Test
    public void testIsActive_user10_packageInstalled_notForUser0() throws Exception {
        setPackageInstalledForUser(true, true, false, 10);
        setPackageInstalledForUser(false, false, false, 0);
        mUser = UserHandle.of(10);

        TileLifecycleManager manager = new TileLifecycleManager(mHandler, mWrappedContext,
                mock(IQSService.class),
                mMockPackageManagerAdapter,
                mMockBroadcastDispatcher,
                mTileServiceIntent,
                mUser,
                mActivityManager,
                mDeviceIdleController,
                mExecutor,
                mClock);

        assertThat(manager.isActiveTile()).isTrue();
    }

    @Test
    public void testIsToggleable_user0_packageInstalled() throws Exception {
        setPackageInstalledForUser(true, false, true, 0);
        mUser = UserHandle.of(0);

        TileLifecycleManager manager = new TileLifecycleManager(mHandler, mWrappedContext,
                mock(IQSService.class),
                mMockPackageManagerAdapter,
                mMockBroadcastDispatcher,
                mTileServiceIntent,
                mUser,
                mActivityManager,
                mDeviceIdleController,
                mExecutor,
                mClock);

        assertThat(manager.isToggleableTile()).isTrue();
    }

    @Test
    public void testIsToggleable_user10_packageInstalled_notForUser0() throws Exception {
        setPackageInstalledForUser(true, false, true, 10);
        setPackageInstalledForUser(false, false, false, 0);
        mUser = UserHandle.of(10);

        TileLifecycleManager manager = new TileLifecycleManager(mHandler, mWrappedContext,
                mock(IQSService.class),
                mMockPackageManagerAdapter,
                mMockBroadcastDispatcher,
                mTileServiceIntent,
                mUser,
                mActivityManager,
                mDeviceIdleController,
                mExecutor,
                mClock);

        assertThat(manager.isToggleableTile()).isTrue();
    }

    @Test
    public void testIsToggleableActive_installedForDifferentUser() throws Exception {
        setPackageInstalledForUser(true, false, false, 10);
        setPackageInstalledForUser(false, true, true, 0);
        mUser = UserHandle.of(10);

        TileLifecycleManager manager = new TileLifecycleManager(mHandler, mWrappedContext,
                mock(IQSService.class),
                mMockPackageManagerAdapter,
                mMockBroadcastDispatcher,
                mTileServiceIntent,
                mUser,
                mActivityManager,
                mDeviceIdleController,
                mExecutor,
                mClock);

        assertThat(manager.isToggleableTile()).isFalse();
        assertThat(manager.isActiveTile()).isFalse();
    }

    private void mockChangeEnabled(long changeId, boolean enabled) {
        doReturn(enabled).when(() -> CompatChanges.isChangeEnabled(eq(changeId), anyString(),
                any(UserHandle.class)));
    }

    private static class TestContextWrapper extends ContextWrapper {
        private IntentFilter mLastIntentFilter;
        private int mLastFlag;

        TestContextWrapper(Context base) {
            super(base);
        }

        @Override
        public Intent registerReceiverAsUser(@Nullable BroadcastReceiver receiver, UserHandle user,
                IntentFilter filter, @Nullable String broadcastPermission,
                @Nullable Handler scheduler, int flags) {
            mLastIntentFilter = filter;
            mLastFlag = flags;
            return super.registerReceiverAsUser(receiver, user, filter, broadcastPermission,
                    scheduler, flags);
        }
    }
}

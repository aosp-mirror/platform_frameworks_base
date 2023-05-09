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

import static android.service.quicksettings.TileService.START_ACTIVITY_NEEDS_PENDING_INTENT;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.compat.CompatChanges;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.UserHandle;
import android.service.quicksettings.IQSService;
import android.service.quicksettings.IQSTileService;
import android.service.quicksettings.TileService;
import android.test.suitebuilder.annotation.SmallTest;

import androidx.annotation.Nullable;
import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockitoSession;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class TileLifecycleManagerTest extends SysuiTestCase {
    private static final int TEST_FAIL_TIMEOUT = 5000;

    private final PackageManagerAdapter mMockPackageManagerAdapter =
            mock(PackageManagerAdapter.class);
    private final BroadcastDispatcher mMockBroadcastDispatcher =
            mock(BroadcastDispatcher.class);
    private final IQSTileService.Stub mMockTileService = mock(IQSTileService.Stub.class);
    private ComponentName mTileServiceComponentName;
    private Intent mTileServiceIntent;
    private UserHandle mUser;
    private FakeExecutor mExecutor;
    private HandlerThread mThread;
    private Handler mHandler;
    private TileLifecycleManager mStateManager;
    private TestContextWrapper mWrappedContext;
    private MockitoSession mMockitoSession;

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
        mExecutor = new FakeExecutor(new FakeSystemClock());
        mStateManager = new TileLifecycleManager(mHandler, mWrappedContext,
                mock(IQSService.class),
                mMockPackageManagerAdapter,
                mMockBroadcastDispatcher,
                mTileServiceIntent,
                mUser,
                mExecutor);
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

    private void setPackageEnabled(boolean enabled) throws Exception {
        ServiceInfo defaultServiceInfo = null;
        if (enabled) {
            defaultServiceInfo = new ServiceInfo();
            defaultServiceInfo.metaData = new Bundle();
            defaultServiceInfo.metaData.putBoolean(TileService.META_DATA_ACTIVE_TILE, true);
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
    public void testNoClickOfNotListeningAnymore() throws Exception {
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
    public void testKillProcess() throws Exception {
        mStateManager.onStartListening();
        mStateManager.executeSetBindService(true);
        mExecutor.runAllReady();
        mStateManager.setBindRetryDelay(0);
        mExecutor.runAllReady();
        mStateManager.onServiceDisconnected(mTileServiceComponentName);
        mExecutor.runAllReady();

        // Two calls: one for the first bind, one for the restart.
        verifyBind(2);
        verify(mMockTileService, times(2)).onStartListening();
    }

    @Test
    public void testToggleableTile() throws Exception {
        assertTrue(mStateManager.isToggleableTile());
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
                mExecutor);

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
                mExecutor);

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
                mExecutor);

        manager.executeSetBindService(true);
        mExecutor.runAllReady();
        int flags = Context.BIND_AUTO_CREATE
                | Context.BIND_FOREGROUND_SERVICE_WHILE_AWAKE
                | Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS
                | Context.BIND_WAIVE_PRIORITY;

        verify(falseContext).bindServiceAsUser(any(), any(), eq(flags), any());
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

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

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.UserHandle;
import android.service.quicksettings.IQSService;
import android.service.quicksettings.IQSTileService;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.test.suitebuilder.annotation.SmallTest;

import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class TileLifecycleManagerTest extends SysuiTestCase {
    private static final int TEST_FAIL_TIMEOUT = 5000;

    private final PackageManagerAdapter mMockPackageManagerAdapter =
            Mockito.mock(PackageManagerAdapter.class);
    private final IQSTileService.Stub mMockTileService = Mockito.mock(IQSTileService.Stub.class);
    private ComponentName mTileServiceComponentName;
    private Intent mTileServiceIntent;
    private UserHandle mUser;
    private HandlerThread mThread;
    private Handler mHandler;
    private TileLifecycleManager mStateManager;

    @Before
    public void setUp() throws Exception {
        setPackageEnabled(true);
        mTileServiceComponentName = new ComponentName(mContext, "FakeTileService.class");

        // Stub.asInterface will just return itself.
        when(mMockTileService.queryLocalInterface(anyString())).thenReturn(mMockTileService);

        mContext.addMockService(mTileServiceComponentName, mMockTileService);


        mTileServiceIntent = new Intent().setComponent(mTileServiceComponentName);
        mUser = new UserHandle(UserHandle.myUserId());
        mThread = new HandlerThread("TestThread");
        mThread.start();
        mHandler = Handler.createAsync(mThread.getLooper());
        mStateManager = new TileLifecycleManager(mHandler, mContext,
                Mockito.mock(IQSService.class), new Tile(),
                mTileServiceIntent,
                mUser,
                mMockPackageManagerAdapter);
    }

    @After
    public void tearDown() throws Exception {
        mThread.quit();
    }

    private void setPackageEnabled(boolean enabled) throws Exception {
        ServiceInfo defaultServiceInfo = null;
        if (enabled) {
            defaultServiceInfo = new ServiceInfo();
            defaultServiceInfo.metaData = new Bundle();
            defaultServiceInfo.metaData.putBoolean(TileService.META_DATA_ACTIVE_TILE, true);
            defaultServiceInfo.metaData.putBoolean(TileService.META_DATA_BOOLEAN_TILE, true);
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
        mStateManager.setBindService(true);
        verifyBind(1);
    }

    @Test
    public void testUnbind() {
        mStateManager.setBindService(true);
        mStateManager.setBindService(false);
        assertFalse(mContext.isBound(mTileServiceComponentName));
    }

    @Test
    public void testTileServiceCallbacks() throws Exception {
        mStateManager.setBindService(true);
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
        mStateManager.setBindService(true);

        verifyBind(1);
        verify(mMockTileService).onTileAdded();
    }

    @Test
    public void testListeningBeforeBind() throws Exception {
        mStateManager.onTileAdded();
        mStateManager.onStartListening();
        mStateManager.setBindService(true);

        verifyBind(1);
        verify(mMockTileService).onTileAdded();
        verify(mMockTileService).onStartListening();
    }

    @Test
    public void testClickBeforeBind() throws Exception {
        mStateManager.onTileAdded();
        mStateManager.onStartListening();
        mStateManager.onClick(null);
        mStateManager.setBindService(true);

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
        mStateManager.setBindService(true);

        verifyBind(1);
        mStateManager.setBindService(false);
        assertFalse(mContext.isBound(mTileServiceComponentName));
        verify(mMockTileService, never()).onStartListening();
    }

    @Test
    public void testNoClickOfNotListeningAnymore() throws Exception {
        mStateManager.onTileAdded();
        mStateManager.onStartListening();
        mStateManager.onClick(null);
        mStateManager.onStopListening();
        mStateManager.setBindService(true);

        verifyBind(1);
        mStateManager.setBindService(false);
        assertFalse(mContext.isBound(mTileServiceComponentName));
        verify(mMockTileService, never()).onClick(null);
    }

    @Test
    public void testComponentEnabling() throws Exception {
        mStateManager.onTileAdded();
        mStateManager.onStartListening();
        setPackageEnabled(false);
        mStateManager.setBindService(true);
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
        verifyBind(1);
    }

    @Test
    public void testKillProcess() throws Exception {
        mStateManager.onStartListening();
        mStateManager.setBindService(true);
        mStateManager.setBindRetryDelay(0);
        mStateManager.onServiceDisconnected(mTileServiceComponentName);

        // Guarantees mHandler has processed all messages.
        assertTrue(mHandler.runWithScissors(()->{}, TEST_FAIL_TIMEOUT));

        // Two calls: one for the first bind, one for the restart.
        verifyBind(2);
        verify(mMockTileService, times(2)).onStartListening();
    }

    @Test
    public void testBooleanTile() throws Exception {
        assertTrue(mStateManager.isBooleanTile());
    }
}

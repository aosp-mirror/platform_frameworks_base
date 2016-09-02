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
import static junit.framework.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.when;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.quicksettings.IQSService;
import android.service.quicksettings.IQSTileService;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.ArraySet;
import android.util.Log;
import com.android.systemui.SysuiTestCase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class TileLifecycleManagerTest extends SysuiTestCase {
    public static final String TILE_UPDATE_BROADCAST = "com.android.systemui.tests.TILE_UPDATE";
    public static final String EXTRA_CALLBACK = "callback";

    private HandlerThread mThread;
    private Handler mHandler;
    private TileLifecycleManager mStateManager;
    private final Object mBroadcastLock = new Object();
    private final ArraySet<String> mCallbacks = new ArraySet<>();
    private final PackageManagerAdapter mMockPackageManagerAdapter =
            Mockito.mock(PackageManagerAdapter.class);
    private boolean mBound;

    @Before
    public void setUp() throws Exception {
        setPackageEnabled(true);
        mThread = new HandlerThread("TestThread");
        mThread.start();
        mHandler = new Handler(mThread.getLooper());
        ComponentName component = new ComponentName(getContext(), FakeTileService.class);
        mStateManager = new TileLifecycleManager(mHandler, getContext(),
                Mockito.mock(IQSService.class), new Tile(),
                new Intent().setComponent(component),
                new UserHandle(UserHandle.myUserId()),
                mMockPackageManagerAdapter);
        mCallbacks.clear();
        getContext().registerReceiver(mReceiver, new IntentFilter(TILE_UPDATE_BROADCAST));
    }

    @After
    public void tearDown() throws Exception {
        if (mBound) {
            unbindService();
        }
        mThread.quit();
        getContext().unregisterReceiver(mReceiver);
    }

    private void setPackageEnabled(boolean enabled) throws Exception {
        ServiceInfo defaultServiceInfo = null;
        if (enabled) {
            defaultServiceInfo = new ServiceInfo();
            defaultServiceInfo.metaData = new Bundle();
            defaultServiceInfo.metaData.putBoolean(TileService.META_DATA_ACTIVE_TILE, true);
        }
        when(mMockPackageManagerAdapter.getServiceInfo(any(), anyInt(), anyInt()))
                .thenReturn(defaultServiceInfo);
        when(mMockPackageManagerAdapter.getServiceInfo(any(), anyInt()))
                .thenReturn(defaultServiceInfo);
        PackageInfo defaultPackageInfo = new PackageInfo();
        when(mMockPackageManagerAdapter.getPackageInfoAsUser(anyString(), anyInt(), anyInt()))
                .thenReturn(defaultPackageInfo);
    }

    @Test
    public void testSync() {
        syncWithHandler();
    }

    @Test
    public void testBind() {
        bindService();
        waitForCallback("onCreate");
    }

    @Test
    public void testUnbind() {
        bindService();
        waitForCallback("onCreate");
        unbindService();
        waitForCallback("onDestroy");
    }

    @Test
    public void testTileServiceCallbacks() {
        bindService();
        waitForCallback("onCreate");

        mStateManager.onTileAdded();
        waitForCallback("onTileAdded");
        mStateManager.onStartListening();
        waitForCallback("onStartListening");
        mStateManager.onClick(null);
        waitForCallback("onClick");
        mStateManager.onStopListening();
        waitForCallback("onStopListening");
        mStateManager.onTileRemoved();
        waitForCallback("onTileRemoved");

        unbindService();
    }

    @Test
    public void testAddedBeforeBind() {
        mStateManager.onTileAdded();

        bindService();
        waitForCallback("onCreate");
        waitForCallback("onTileAdded");
    }

    @Test
    public void testListeningBeforeBind() {
        mStateManager.onTileAdded();
        mStateManager.onStartListening();

        bindService();
        waitForCallback("onCreate");
        waitForCallback("onTileAdded");
        waitForCallback("onStartListening");
    }

    @Test
    public void testClickBeforeBind() {
        mStateManager.onTileAdded();
        mStateManager.onStartListening();
        mStateManager.onClick(null);

        bindService();
        waitForCallback("onCreate");
        waitForCallback("onTileAdded");
        waitForCallback("onStartListening");
        waitForCallback("onClick");
    }

    @Test
    public void testListeningNotListeningBeforeBind() {
        mStateManager.onTileAdded();
        mStateManager.onStartListening();
        mStateManager.onStopListening();

        bindService();
        waitForCallback("onCreate");
        unbindService();
        waitForCallback("onDestroy");
        assertFalse(mCallbacks.contains("onStartListening"));
    }

    @Test
    public void testNoClickOfNotListeningAnymore() {
        mStateManager.onTileAdded();
        mStateManager.onStartListening();
        mStateManager.onClick(null);
        mStateManager.onStopListening();

        bindService();
        waitForCallback("onCreate");
        unbindService();
        waitForCallback("onDestroy");
        assertFalse(mCallbacks.contains("onClick"));
    }

    @Test
    public void testComponentEnabling() throws Exception {
        mStateManager.onTileAdded();
        mStateManager.onStartListening();

        setPackageEnabled(false);
        bindService();
        // Package not available, should be listening for package changes.
        assertTrue(mStateManager.mReceiverRegistered);

        // Package is re-enabled.
        setPackageEnabled(true);
        mStateManager.onReceive(
                mContext,
                new Intent(
                        Intent.ACTION_PACKAGE_CHANGED,
                        Uri.fromParts("package", getContext().getPackageName(), null)));
        waitForCallback("onCreate");
    }

    @Test
    public void testKillProcess() {
        mStateManager.onStartListening();
        bindService();
        waitForCallback("onCreate");
        waitForCallback("onStartListening");

        getContext().sendBroadcast(new Intent(FakeTileService.ACTION_KILL));

        waitForCallback("onCreate");
        waitForCallback("onStartListening");
    }

    private void bindService() {
        mBound = true;
        mStateManager.setBindService(true);
    }

    private void unbindService() {
        mBound = false;
        mStateManager.setBindService(false);
    }

    private void waitForCallback(String callback) {
        for (int i = 0; i < 50; i++) {
            if (mCallbacks.contains(callback)) {
                mCallbacks.remove(callback);
                return;
            }
            synchronized (mBroadcastLock) {
                try {
                    mBroadcastLock.wait(500);
                } catch (InterruptedException e) {
                }
            }
        }
        if (mCallbacks.contains(callback)) {
            mCallbacks.remove(callback);
            return;
        }
        fail("Didn't receive callback: " + callback);
    }

    private void syncWithHandler() {
        final Object lock = new Object();
        synchronized (lock) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    synchronized (lock) {
                        lock.notify();
                    }
                }
            });
            try {
                lock.wait(10000);
            } catch (InterruptedException e) {
            }
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mCallbacks.add(intent.getStringExtra(EXTRA_CALLBACK));
            synchronized (mBroadcastLock) {
                mBroadcastLock.notify();
            }
        }
    };

    public static class FakeTileService extends Service {
        public static final String ACTION_KILL = "com.android.systemui.test.KILL";

        @Override
        public IBinder onBind(Intent intent) {
            return new IQSTileService.Stub() {
                @Override
                public void onTileAdded() throws RemoteException {
                    sendCallback("onTileAdded");
                }

                @Override
                public void onTileRemoved() throws RemoteException {
                    sendCallback("onTileRemoved");
                }

                @Override
                public void onStartListening() throws RemoteException {
                    sendCallback("onStartListening");
                }

                @Override
                public void onStopListening() throws RemoteException {
                    sendCallback("onStopListening");
                }

                @Override
                public void onClick(IBinder iBinder) throws RemoteException {
                    sendCallback("onClick");
                }

                @Override
                public void onUnlockComplete() throws RemoteException {
                    sendCallback("onUnlockComplete");
                }
            };
        }

        @Override
        public void onCreate() {
            super.onCreate();
            registerReceiver(mReceiver, new IntentFilter(ACTION_KILL));
            sendCallback("onCreate");
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            unregisterReceiver(mReceiver);
            sendCallback("onDestroy");
        }

        private void sendCallback(String callback) {
            Log.d("TileLifecycleManager", "Relaying: " + callback);
            sendBroadcast(new Intent(TILE_UPDATE_BROADCAST)
                    .putExtra(EXTRA_CALLBACK, callback));
        }

        private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_KILL.equals(intent.getAction())) {
                    Process.killProcess(Process.myPid());
                }
            }
        };
    }
}

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
 * limitations under the License
 */
package com.android.systemui.qs.external;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.quicksettings.IQSService;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.systemui.Dependency;
import com.android.systemui.qs.QSTileHost;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.policy.KeyguardMonitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/**
 * Runs the day-to-day operations of which tiles should be bound and when.
 */
public class TileServices extends IQSService.Stub {
    static final int DEFAULT_MAX_BOUND = 3;
    static final int REDUCED_MAX_BOUND = 1;
    private static final String TAG = "TileServices";

    private final ArrayMap<CustomTile, TileServiceManager> mServices = new ArrayMap<>();
    private final ArrayMap<ComponentName, CustomTile> mTiles = new ArrayMap<>();
    private final ArrayMap<IBinder, CustomTile> mTokenMap = new ArrayMap<>();
    private final Context mContext;
    private final Handler mHandler;
    private final Handler mMainHandler;
    private final QSTileHost mHost;

    private int mMaxBound = DEFAULT_MAX_BOUND;

    public TileServices(QSTileHost host, Looper looper) {
        mHost = host;
        mContext = mHost.getContext();
        mContext.registerReceiver(mRequestListeningReceiver,
                new IntentFilter(TileService.ACTION_REQUEST_LISTENING));
        mHandler = new Handler(looper);
        mMainHandler = new Handler(Looper.getMainLooper());
    }

    public Context getContext() {
        return mContext;
    }

    public QSTileHost getHost() {
        return mHost;
    }

    public TileServiceManager getTileWrapper(CustomTile tile) {
        ComponentName component = tile.getComponent();
        TileServiceManager service = onCreateTileService(component, tile.getQsTile());
        synchronized (mServices) {
            mServices.put(tile, service);
            mTiles.put(component, tile);
            mTokenMap.put(service.getToken(), tile);
        }
        // Makes sure binding only happens after the maps have been populated
        service.startLifecycleManagerAndAddTile();
        return service;
    }

    protected TileServiceManager onCreateTileService(ComponentName component, Tile tile) {
        return new TileServiceManager(this, mHandler, component, tile);
    }

    public void freeService(CustomTile tile, TileServiceManager service) {
        synchronized (mServices) {
            service.setBindAllowed(false);
            service.handleDestroy();
            mServices.remove(tile);
            mTokenMap.remove(service.getToken());
            mTiles.remove(tile.getComponent());
            final String slot = tile.getComponent().getClassName();
            // TileServices doesn't know how to add more than 1 icon per slot, so remove all
            mMainHandler.post(() -> mHost.getIconController()
                    .removeAllIconsForSlot(slot));
        }
    }

    public void setMemoryPressure(boolean memoryPressure) {
        mMaxBound = memoryPressure ? REDUCED_MAX_BOUND : DEFAULT_MAX_BOUND;
        recalculateBindAllowance();
    }

    public void recalculateBindAllowance() {
        final ArrayList<TileServiceManager> services;
        synchronized (mServices) {
            services = new ArrayList<>(mServices.values());
        }
        final int N = services.size();
        if (N > mMaxBound) {
            long currentTime = System.currentTimeMillis();
            // Precalculate the priority of services for binding.
            for (int i = 0; i < N; i++) {
                services.get(i).calculateBindPriority(currentTime);
            }
            // Sort them so we can bind the most important first.
            Collections.sort(services, SERVICE_SORT);
        }
        int i;
        // Allow mMaxBound items to bind.
        for (i = 0; i < mMaxBound && i < N; i++) {
            services.get(i).setBindAllowed(true);
        }
        // The rest aren't allowed to bind for now.
        while (i < N) {
            services.get(i).setBindAllowed(false);
            i++;
        }
    }

    private void verifyCaller(CustomTile tile) {
        try {
            String packageName = tile.getComponent().getPackageName();
            int uid = mContext.getPackageManager().getPackageUidAsUser(packageName,
                    Binder.getCallingUserHandle().getIdentifier());
            if (Binder.getCallingUid() != uid) {
                throw new SecurityException("Component outside caller's uid");
            }
        } catch (PackageManager.NameNotFoundException e) {
            throw new SecurityException(e);
        }
    }

    private void requestListening(ComponentName component) {
        synchronized (mServices) {
            CustomTile customTile = getTileForComponent(component);
            if (customTile == null) {
                Log.d("TileServices", "Couldn't find tile for " + component);
                return;
            }
            TileServiceManager service = mServices.get(customTile);
            if (!service.isActiveTile()) {
                return;
            }
            service.setBindRequested(true);
            try {
                service.getTileService().onStartListening();
            } catch (RemoteException e) {
            }
        }
    }

    @Override
    public void updateQsTile(Tile tile, IBinder token) {
        CustomTile customTile = getTileForToken(token);
        if (customTile != null) {
            verifyCaller(customTile);
            synchronized (mServices) {
                final TileServiceManager tileServiceManager = mServices.get(customTile);
                if (tileServiceManager == null || !tileServiceManager.isLifecycleStarted()) {
                    Log.e(TAG, "TileServiceManager not started for " + customTile.getComponent(),
                            new IllegalStateException());
                    return;
                }
                tileServiceManager.clearPendingBind();
                tileServiceManager.setLastUpdate(System.currentTimeMillis());
            }
            customTile.updateState(tile);
            customTile.refreshState();
        }
    }

    @Override
    public void onStartSuccessful(IBinder token) {
        CustomTile customTile = getTileForToken(token);
        if (customTile != null) {
            verifyCaller(customTile);
            synchronized (mServices) {
                final TileServiceManager tileServiceManager = mServices.get(customTile);
                // This should not happen as the TileServiceManager should have been started for the
                // first bind to happen.
                if (tileServiceManager == null || !tileServiceManager.isLifecycleStarted()) {
                    Log.e(TAG, "TileServiceManager not started for " + customTile.getComponent(),
                            new IllegalStateException());
                    return;
                }
                tileServiceManager.clearPendingBind();
            }
            customTile.refreshState();
        }
    }

    @Override
    public void onShowDialog(IBinder token) {
        CustomTile customTile = getTileForToken(token);
        if (customTile != null) {
            verifyCaller(customTile);
            customTile.onDialogShown();
            mHost.forceCollapsePanels();
            mServices.get(customTile).setShowingDialog(true);
        }
    }

    @Override
    public void onDialogHidden(IBinder token) {
        CustomTile customTile = getTileForToken(token);
        if (customTile != null) {
            verifyCaller(customTile);
            mServices.get(customTile).setShowingDialog(false);
            customTile.onDialogHidden();
        }
    }

    @Override
    public void onStartActivity(IBinder token) {
        CustomTile customTile = getTileForToken(token);
        if (customTile != null) {
            verifyCaller(customTile);
            mHost.forceCollapsePanels();
        }
    }

    @Override
    public void updateStatusIcon(IBinder token, Icon icon, String contentDescription) {
        CustomTile customTile = getTileForToken(token);
        if (customTile != null) {
            verifyCaller(customTile);
            try {
                ComponentName componentName = customTile.getComponent();
                String packageName = componentName.getPackageName();
                UserHandle userHandle = getCallingUserHandle();
                PackageInfo info = mContext.getPackageManager().getPackageInfoAsUser(packageName, 0,
                        userHandle.getIdentifier());
                if (info.applicationInfo.isSystemApp()) {
                    final StatusBarIcon statusIcon = icon != null
                            ? new StatusBarIcon(userHandle, packageName, icon, 0, 0,
                                    contentDescription)
                            : null;
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            StatusBarIconController iconController = mHost.getIconController();
                            iconController.setIcon(componentName.getClassName(), statusIcon);
                            iconController.setExternalIcon(componentName.getClassName());
                        }
                    });
                }
            } catch (PackageManager.NameNotFoundException e) {
            }
        }
    }

    @Override
    public Tile getTile(IBinder token) {
        CustomTile customTile = getTileForToken(token);
        if (customTile != null) {
            verifyCaller(customTile);
            return customTile.getQsTile();
        }
        return null;
    }

    @Override
    public void startUnlockAndRun(IBinder token) {
        CustomTile customTile = getTileForToken(token);
        if (customTile != null) {
            verifyCaller(customTile);
            customTile.startUnlockAndRun();
        }
    }

    @Override
    public boolean isLocked() {
        KeyguardMonitor keyguardMonitor = Dependency.get(KeyguardMonitor.class);
        return keyguardMonitor.isShowing();
    }

    @Override
    public boolean isSecure() {
        KeyguardMonitor keyguardMonitor = Dependency.get(KeyguardMonitor.class);
        return keyguardMonitor.isSecure() && keyguardMonitor.isShowing();
    }

    private CustomTile getTileForToken(IBinder token) {
        synchronized (mServices) {
            return mTokenMap.get(token);
        }
    }

    private CustomTile getTileForComponent(ComponentName component) {
        synchronized (mServices) {
            return mTiles.get(component);
        }
    }

    public void destroy() {
        synchronized (mServices) {
            mServices.values().forEach(service -> service.handleDestroy());
            mContext.unregisterReceiver(mRequestListeningReceiver);
        }
    }

    private final BroadcastReceiver mRequestListeningReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (TileService.ACTION_REQUEST_LISTENING.equals(intent.getAction())) {
                requestListening(
                        (ComponentName) intent.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME));
            }
        }
    };

    private static final Comparator<TileServiceManager> SERVICE_SORT =
            new Comparator<TileServiceManager>() {
        @Override
        public int compare(TileServiceManager left, TileServiceManager right) {
            return -Integer.compare(left.getBindPriority(), right.getBindPriority());
        }
    };
}

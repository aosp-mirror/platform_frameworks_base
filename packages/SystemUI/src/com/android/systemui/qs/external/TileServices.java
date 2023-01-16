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

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.quicksettings.IQSService;
import android.service.quicksettings.Tile;
import android.util.ArrayMap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.qs.QSTileHost;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Provider;

/**
 * Runs the day-to-day operations of which tiles should be bound and when.
 */
@SysUISingleton
public class TileServices extends IQSService.Stub {
    static final int DEFAULT_MAX_BOUND = 3;
    static final int REDUCED_MAX_BOUND = 1;
    private static final String TAG = "TileServices";

    private final ArrayMap<CustomTile, TileServiceManager> mServices = new ArrayMap<>();
    private final ArrayMap<ComponentName, CustomTile> mTiles = new ArrayMap<>();
    private final ArrayMap<IBinder, CustomTile> mTokenMap = new ArrayMap<>();
    private final Context mContext;
    private final Handler mMainHandler;
    private final Provider<Handler> mHandlerProvider;
    private final QSTileHost mHost;
    private final KeyguardStateController mKeyguardStateController;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final CommandQueue mCommandQueue;
    private final UserTracker mUserTracker;

    private int mMaxBound = DEFAULT_MAX_BOUND;

    @Inject
    public TileServices(
            QSTileHost host,
            @Main Provider<Handler> handlerProvider,
            BroadcastDispatcher broadcastDispatcher,
            UserTracker userTracker,
            KeyguardStateController keyguardStateController,
            CommandQueue commandQueue) {
        mHost = host;
        mKeyguardStateController = keyguardStateController;
        mContext = mHost.getContext();
        mBroadcastDispatcher = broadcastDispatcher;
        mHandlerProvider = handlerProvider;
        mMainHandler = mHandlerProvider.get();
        mUserTracker = userTracker;
        mCommandQueue = commandQueue;
        mCommandQueue.addCallback(mRequestListeningCallback);
    }

    public Context getContext() {
        return mContext;
    }

    public QSTileHost getHost() {
        return mHost;
    }

    public TileServiceManager getTileWrapper(CustomTile tile) {
        ComponentName component = tile.getComponent();
        TileServiceManager service = onCreateTileService(component, mBroadcastDispatcher);
        synchronized (mServices) {
            mServices.put(tile, service);
            mTiles.put(component, tile);
            mTokenMap.put(service.getToken(), tile);
        }
        // Makes sure binding only happens after the maps have been populated
        service.startLifecycleManagerAndAddTile();
        return service;
    }

    protected TileServiceManager onCreateTileService(ComponentName component,
            BroadcastDispatcher broadcastDispatcher) {
        return new TileServiceManager(this, mHandlerProvider.get(), component,
                broadcastDispatcher, mUserTracker);
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
                    .removeAllIconsForExternalSlot(slot));
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
            if (service == null) {
                Log.e(
                        TAG,
                        "No TileServiceManager found in requestListening for tile "
                                + customTile.getTileSpec());
                return;
            }
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
            customTile.updateTileState(tile);
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
            Objects.requireNonNull(mServices.get(customTile)).setShowingDialog(true);
        }
    }

    @Override
    public void onDialogHidden(IBinder token) {
        CustomTile customTile = getTileForToken(token);
        if (customTile != null) {
            verifyCaller(customTile);
            Objects.requireNonNull(mServices.get(customTile)).setShowingDialog(false);
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
    public void startActivity(IBinder token, PendingIntent pendingIntent) {
        startActivity(getTileForToken(token), pendingIntent);
    }

    @VisibleForTesting
    protected void startActivity(CustomTile customTile, PendingIntent pendingIntent) {
        if (customTile != null) {
            verifyCaller(customTile);
            customTile.startActivityAndCollapse(pendingIntent);
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

    @Nullable
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
        return mKeyguardStateController.isShowing();
    }

    @Override
    public boolean isSecure() {
        return mKeyguardStateController.isMethodSecure() && mKeyguardStateController.isShowing();
    }

    @Nullable
    public CustomTile getTileForToken(IBinder token) {
        synchronized (mServices) {
            return mTokenMap.get(token);
        }
    }

    @Nullable
    private CustomTile getTileForComponent(ComponentName component) {
        synchronized (mServices) {
            return mTiles.get(component);
        }
    }

    public void destroy() {
        synchronized (mServices) {
            mServices.values().forEach(service -> service.handleDestroy());
        }
        mCommandQueue.removeCallback(mRequestListeningCallback);
    }

    private final CommandQueue.Callbacks mRequestListeningCallback = new CommandQueue.Callbacks() {
        @Override
        public void requestTileServiceListeningState(@NonNull ComponentName componentName) {
            mMainHandler.post(() -> requestListening(componentName));
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

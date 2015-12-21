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

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.IQSService;
import android.service.quicksettings.Tile;
import android.util.ArrayMap;
import com.android.systemui.statusbar.phone.QSTileHost;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/**
 * Runs the day-to-day operations of which tiles should be bound and when.
 */
public class TileServices extends IQSService.Stub {
    static final int DEFAULT_MAX_BOUND = 3;
    static final int REDUCED_MAX_BOUND = 1;

    private final ArrayMap<CustomTile, TileServiceManager> mServices = new ArrayMap<>();
    private final ArrayMap<ComponentName, CustomTile> mTiles = new ArrayMap<>();
    private final Context mContext;
    private final Handler mHandler;
    private final QSTileHost mHost;

    private int mMaxBound = DEFAULT_MAX_BOUND;

    public TileServices(QSTileHost host, Looper looper) {
        mHost = host;
        mContext = mHost.getContext();
        mHandler = new Handler(looper);
    }

    public Context getContext() {
        return mContext;
    }

    public TileServiceManager getTileWrapper(CustomTile tile) {
        ComponentName component = tile.getComponent();
        TileServiceManager service = onCreateTileService(component);
        synchronized (mServices) {
            mServices.put(tile, service);
            mTiles.put(component, tile);
        }
        return service;
    }

    protected TileServiceManager onCreateTileService(ComponentName component) {
        return new TileServiceManager(this, mHandler, component);
    }

    public void freeService(CustomTile tile, TileServiceManager service) {
        synchronized (mServices) {
            service.setBindAllowed(false);
            mServices.remove(tile);
            mTiles.remove(tile.getComponent());
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

    private void verifyCaller(String packageName) {
        try {
            int uid = mContext.getPackageManager().getPackageUid(packageName,
                    Binder.getCallingUserHandle().getIdentifier());
            if (Binder.getCallingUid() != uid) {
                throw new SecurityException("Component outside caller's uid");
            }
        } catch (PackageManager.NameNotFoundException e) {
            throw new SecurityException(e);
        }
    }

    @Override
    public void updateQsTile(Tile tile) {
        verifyCaller(tile.getComponentName().getPackageName());
        CustomTile customTile = getTileForComponent(tile.getComponentName());
        if (customTile != null) {
            mServices.get(customTile).setLastUpdate(System.currentTimeMillis());
            customTile.updateState(tile);
            customTile.refreshState();
        }
    }

    @Override
    public void onShowDialog(Tile tile) {
        verifyCaller(tile.getComponentName().getPackageName());
        CustomTile customTile = getTileForComponent(tile.getComponentName());
        if (customTile != null) {
            customTile.onDialogShown();
            mHost.collapsePanels();
        }
    }

    private CustomTile getTileForComponent(ComponentName component) {
        return mTiles.get(component);
    }

    private static final Comparator<TileServiceManager> SERVICE_SORT =
            new Comparator<TileServiceManager>() {
        @Override
        public int compare(TileServiceManager left, TileServiceManager right) {
            return -Integer.compare(left.getBindPriority(), right.getBindPriority());
        }
    };
}

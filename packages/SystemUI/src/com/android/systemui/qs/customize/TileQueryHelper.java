/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.qs.customize;

import android.Manifest.permission;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.service.quicksettings.TileService;
import android.text.TextUtils;
import android.util.ArraySet;
import android.widget.Button;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.qs.QSTile.State;
import com.android.systemui.qs.QSTileHost;
import com.android.systemui.qs.external.CustomTile;
import com.android.systemui.qs.tileimpl.QSTileImpl.DrawableIcon;
import com.android.systemui.util.leak.GarbageMonitor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public class TileQueryHelper {
    private static final String TAG = "TileQueryHelper";

    private final ArrayList<TileInfo> mTiles = new ArrayList<>();
    private final ArraySet<String> mSpecs = new ArraySet<>();
    private final Handler mBgHandler;
    private final Handler mMainHandler;
    private final Context mContext;
    private final TileStateListener mListener;

    private boolean mFinished;

    public TileQueryHelper(Context context, TileStateListener listener) {
        mContext = context;
        mListener = listener;
        mBgHandler = new Handler(Dependency.get(Dependency.BG_LOOPER));
        mMainHandler = Dependency.get(Dependency.MAIN_HANDLER);
    }

    public void queryTiles(QSTileHost host) {
        mTiles.clear();
        mSpecs.clear();
        mFinished = false;
        // Enqueue jobs to fetch every system tile and then ever package tile.
        addStockTiles(host);
        addPackageTiles(host);
    }

    public boolean isFinished() {
        return mFinished;
    }

    private void addStockTiles(QSTileHost host) {
        String possible = mContext.getString(R.string.quick_settings_tiles_stock);
        final ArrayList<String> possibleTiles = new ArrayList<>();
        possibleTiles.addAll(Arrays.asList(possible.split(",")));
        if (Build.IS_DEBUGGABLE) {
            possibleTiles.add(GarbageMonitor.MemoryTile.TILE_SPEC);
        }

        final ArrayList<QSTile> tilesToAdd = new ArrayList<>();
        for (String spec : possibleTiles) {
            final QSTile tile = host.createTile(spec);
            if (tile == null) {
                continue;
            } else if (!tile.isAvailable()) {
                tile.destroy();
                continue;
            }
            tile.setListening(this, true);
            tile.clearState();
            tile.refreshState();
            tile.setListening(this, false);
            tile.setTileSpec(spec);
            tilesToAdd.add(tile);
        }

        mBgHandler.post(() -> {
            for (QSTile tile : tilesToAdd) {
                final QSTile.State state = tile.getState().copy();
                // Ignore the current state and get the generic label instead.
                state.label = tile.getTileLabel();
                tile.destroy();
                addTile(tile.getTileSpec(), null, state, true);
            }
            notifyTilesChanged(false);
        });
    }

    private void addPackageTiles(final QSTileHost host) {
        mBgHandler.post(() -> {
            Collection<QSTile> params = host.getTiles();
            PackageManager pm = mContext.getPackageManager();
            List<ResolveInfo> services = pm.queryIntentServicesAsUser(
                    new Intent(TileService.ACTION_QS_TILE), 0, ActivityManager.getCurrentUser());
            String stockTiles = mContext.getString(R.string.quick_settings_tiles_stock);

            for (ResolveInfo info : services) {
                String packageName = info.serviceInfo.packageName;
                ComponentName componentName = new ComponentName(packageName, info.serviceInfo.name);

                // Don't include apps that are a part of the default tile set.
                if (stockTiles.contains(componentName.flattenToString())) {
                    continue;
                }

                final CharSequence appLabel = info.serviceInfo.applicationInfo.loadLabel(pm);
                String spec = CustomTile.toSpec(componentName);
                State state = getState(params, spec);
                if (state != null) {
                    addTile(spec, appLabel, state, false);
                    continue;
                }
                if (info.serviceInfo.icon == 0 && info.serviceInfo.applicationInfo.icon == 0) {
                    continue;
                }
                Drawable icon = info.serviceInfo.loadIcon(pm);
                if (!permission.BIND_QUICK_SETTINGS_TILE.equals(info.serviceInfo.permission)) {
                    continue;
                }
                if (icon == null) {
                    continue;
                }
                icon.mutate();
                icon.setTint(mContext.getColor(android.R.color.white));
                CharSequence label = info.serviceInfo.loadLabel(pm);
                addTile(spec, icon, label != null ? label.toString() : "null", appLabel);
            }

            notifyTilesChanged(true);
        });
    }

    private void notifyTilesChanged(final boolean finished) {
        final ArrayList<TileInfo> tilesToReturn = new ArrayList<>(mTiles);
        mMainHandler.post(() -> {
            mListener.onTilesChanged(tilesToReturn);
            mFinished = finished;
        });
    }

    private State getState(Collection<QSTile> tiles, String spec) {
        for (QSTile tile : tiles) {
            if (spec.equals(tile.getTileSpec())) {
                return tile.getState().copy();
            }
        }
        return null;
    }

    private void addTile(String spec, CharSequence appLabel, State state, boolean isSystem) {
        if (mSpecs.contains(spec)) {
            return;
        }
        TileInfo info = new TileInfo();
        info.state = state;
        info.state.dualTarget = false; // No dual targets in edit.
        info.state.expandedAccessibilityClassName =
                Button.class.getName();
        info.spec = spec;
        info.state.secondaryLabel = (isSystem || TextUtils.equals(state.label, appLabel))
                ? null : appLabel;
        info.isSystem = isSystem;
        mTiles.add(info);
        mSpecs.add(spec);
    }

    private void addTile(
            String spec, Drawable drawable, CharSequence label, CharSequence appLabel) {
        QSTile.State state = new QSTile.State();
        state.label = label;
        state.contentDescription = label;
        state.icon = new DrawableIcon(drawable);
        addTile(spec, appLabel, state, false);
    }

    public static class TileInfo {
        public String spec;
        public QSTile.State state;
        public boolean isSystem;
    }

    public interface TileStateListener {
        void onTilesChanged(List<TileInfo> tiles);
    }
}

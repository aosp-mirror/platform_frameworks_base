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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.text.TextUtils;
import android.util.ArraySet;
import android.widget.Button;

import androidx.annotation.Nullable;

import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.qs.QSTile.State;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.dagger.QSScope;
import com.android.systemui.qs.external.CustomTile;
import com.android.systemui.qs.tileimpl.QSTileImpl.DrawableIcon;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/** */
@QSScope
public class TileQueryHelper {
    private static final String TAG = "TileQueryHelper";

    private final ArrayList<TileInfo> mTiles = new ArrayList<>();
    private final ArraySet<String> mSpecs = new ArraySet<>();
    private final Executor mMainExecutor;
    private final Executor mBgExecutor;
    private final Context mContext;
    private final UserTracker mUserTracker;
    private TileStateListener mListener;

    private boolean mFinished;

    @Inject
    public TileQueryHelper(
            Context context,
            UserTracker userTracker,
            @Main Executor mainExecutor,
            @Background Executor bgExecutor
    ) {
        mContext = context;
        mMainExecutor = mainExecutor;
        mBgExecutor = bgExecutor;
        mUserTracker = userTracker;
    }

    public void setListener(@Nullable TileStateListener listener) {
        mListener = listener;
    }

    public void queryTiles(QSHost host) {
        mTiles.clear();
        mSpecs.clear();
        mFinished = false;
        // Enqueue jobs to fetch every system tile and then ever package tile.
        addCurrentAndStockTiles(host);
    }

    public boolean isFinished() {
        return mFinished;
    }

    private void addCurrentAndStockTiles(QSHost host) {
        String stock = mContext.getString(R.string.quick_settings_tiles_stock);
        String current = Settings.Secure.getString(mContext.getContentResolver(),
                Settings.Secure.QS_TILES);
        final ArrayList<String> possibleTiles = new ArrayList<>();
        if (current != null) {
            // The setting QS_TILES is not populated immediately upon Factory Reset
            possibleTiles.addAll(Arrays.asList(current.split(",")));
        } else {
            current = "";
        }
        String[] stockSplit =  stock.split(",");
        for (String spec : stockSplit) {
            if (!current.contains(spec)) {
                possibleTiles.add(spec);
            }
        }

        final ArrayList<QSTile> tilesToAdd = new ArrayList<>();
        possibleTiles.remove("cell");
        possibleTiles.remove("wifi");

        for (String spec : possibleTiles) {
            // Only add current and stock tiles that can be created from QSFactoryImpl.
            // Do not include CustomTile. Those will be created by `addPackageTiles`.
            if (spec.startsWith(CustomTile.PREFIX)) continue;
            final QSTile tile = host.createTile(spec);
            if (tile == null) {
                continue;
            } else if (!tile.isAvailable()) {
                tile.destroy();
                continue;
            }
            tilesToAdd.add(tile);
        }

        new TileCollector(tilesToAdd, host).startListening();
    }

    private static class TilePair {
        private TilePair(QSTile tile) {
            mTile = tile;
        }

        QSTile mTile;
        boolean mReady = false;
    }

    private class TileCollector implements QSTile.Callback {

        private final List<TilePair> mQSTileList = new ArrayList<>();
        private final QSHost mQSHost;

        TileCollector(List<QSTile> tilesToAdd, QSHost host) {
            for (QSTile tile: tilesToAdd) {
                TilePair pair = new TilePair(tile);
                mQSTileList.add(pair);
            }
            mQSHost = host;
            if (tilesToAdd.isEmpty()) {
                mBgExecutor.execute(this::finished);
            }
        }

        private void finished() {
            notifyTilesChanged(false);
            addPackageTiles(mQSHost);
        }

        private void startListening() {
            for (TilePair pair: mQSTileList) {
                pair.mTile.addCallback(this);
                pair.mTile.setListening(this, true);
                // Make sure that at least one refresh state happens
                pair.mTile.refreshState();
            }
        }

        // This is called in the Bg thread
        @Override
        public void onStateChanged(State s) {
            boolean allReady = true;
            for (TilePair pair: mQSTileList) {
                if (!pair.mReady && pair.mTile.isTileReady()) {
                    pair.mTile.removeCallback(this);
                    pair.mTile.setListening(this, false);
                    pair.mReady = true;
                } else if (!pair.mReady) {
                    allReady = false;
                }
            }
            if (allReady) {
                for (TilePair pair : mQSTileList) {
                    QSTile tile = pair.mTile;
                    final QSTile.State state = tile.getState().copy();
                    // Ignore the current state and get the generic label instead.
                    state.label = tile.getTileLabel();
                    tile.destroy();
                    addTile(tile.getTileSpec(), null, state, true);
                }
                finished();
            }
        }
    }

    private void addPackageTiles(final QSHost host) {
        mBgExecutor.execute(() -> {
            Collection<QSTile> params = host.getTiles();
            PackageManager pm = mContext.getPackageManager();
            List<ResolveInfo> services = pm.queryIntentServicesAsUser(
                    new Intent(TileService.ACTION_QS_TILE), 0, mUserTracker.getUserId());
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
                createStateAndAddTile(spec, icon, label != null ? label.toString() : "null",
                        appLabel);
            }

            notifyTilesChanged(true);
        });
    }

    private void notifyTilesChanged(final boolean finished) {
        final ArrayList<TileInfo> tilesToReturn = new ArrayList<>(mTiles);
        mMainExecutor.execute(() -> {
            if (mListener != null) {
                mListener.onTilesChanged(tilesToReturn);
            }
            mFinished = finished;
        });
    }

    @Nullable
    private State getState(Collection<QSTile> tiles, String spec) {
        for (QSTile tile : tiles) {
            if (spec.equals(tile.getTileSpec())) {
                if (tile.isTileReady()) {
                    return tile.getState().copy();
                } else {
                    return null;
                }
            }
        }
        return null;
    }

    private void addTile(
            String spec, @Nullable CharSequence appLabel, State state, boolean isSystem) {
        if (mSpecs.contains(spec)) {
            return;
        }
        state.dualTarget = false; // No dual targets in edit.
        state.expandedAccessibilityClassName = Button.class.getName();
        state.secondaryLabel = (isSystem || TextUtils.equals(state.label, appLabel))
                ? null : appLabel;
        TileInfo info = new TileInfo(spec, state, isSystem);
        mTiles.add(info);
        mSpecs.add(spec);
    }

    private void createStateAndAddTile(
            String spec, Drawable drawable, CharSequence label, CharSequence appLabel) {
        QSTile.State state = new QSTile.State();
        state.state = Tile.STATE_INACTIVE;
        state.label = label;
        state.contentDescription = label;
        state.icon = new DrawableIcon(drawable);
        addTile(spec, appLabel, state, false);
    }

    public static class TileInfo {
        public TileInfo(String spec, QSTile.State state, boolean isSystem) {
            this.spec = spec;
            this.state = state;
            this.isSystem = isSystem;
        }

        public String spec;
        public QSTile.State state;
        public boolean isSystem;
    }

    public interface TileStateListener {
        void onTilesChanged(List<TileInfo> tiles);
    }
}

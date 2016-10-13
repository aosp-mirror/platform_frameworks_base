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
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.TileService;
import android.widget.Button;

import com.android.systemui.R;
import com.android.systemui.qs.QSTile;
import com.android.systemui.qs.QSTile.DrawableIcon;
import com.android.systemui.qs.QSTile.State;
import com.android.systemui.qs.external.CustomTile;
import com.android.systemui.statusbar.phone.QSTileHost;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class TileQueryHelper {

    private static final String TAG = "TileQueryHelper";

    private final ArrayList<TileInfo> mTiles = new ArrayList<>();
    private final ArrayList<String> mSpecs = new ArrayList<>();
    private final Context mContext;
    private TileStateListener mListener;

    public TileQueryHelper(Context context, QSTileHost host) {
        mContext = context;
        addSystemTiles(host);
        // TODO: Live?
    }

    private void addSystemTiles(final QSTileHost host) {
        String possible = mContext.getString(R.string.quick_settings_tiles_default)
                + ",hotspot,inversion,saver,work,cast,night";
        String[] possibleTiles = possible.split(",");
        final Handler qsHandler = new Handler(host.getLooper());
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        for (int i = 0; i < possibleTiles.length; i++) {
            final String spec = possibleTiles[i];
            final QSTile<?> tile = host.createTile(spec);
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
            qsHandler.post(new Runnable() {
                @Override
                public void run() {
                    final QSTile.State state = tile.newTileState();
                    tile.getState().copyTo(state);
                    // Ignore the current state and get the generic label instead.
                    state.label = tile.getTileLabel();
                    tile.destroy();
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            addTile(spec, null, state, true);
                            mListener.onTilesChanged(mTiles);
                        }
                    });
                }
            });
        }
        qsHandler.post(new Runnable() {
            @Override
            public void run() {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        new QueryTilesTask().execute(host.getTiles());
                    }
                });
            }
        });
    }

    public void setListener(TileStateListener listener) {
        mListener = listener;
    }

    private void addTile(String spec, CharSequence appLabel, State state, boolean isSystem) {
        if (mSpecs.contains(spec)) {
            return;
        }
        TileInfo info = new TileInfo();
        info.state = state;
        info.state.minimalAccessibilityClassName = info.state.expandedAccessibilityClassName =
                Button.class.getName();
        info.spec = spec;
        info.appLabel = appLabel;
        info.isSystem = isSystem;
        mTiles.add(info);
        mSpecs.add(spec);
    }

    private void addTile(String spec, Drawable drawable, CharSequence label, CharSequence appLabel,
            Context context) {
        QSTile.State state = new QSTile.State();
        state.label = label;
        state.contentDescription = label;
        state.icon = new DrawableIcon(drawable);
        addTile(spec, appLabel, state, false);
    }

    public static class TileInfo {
        public String spec;
        public CharSequence appLabel;
        public QSTile.State state;
        public boolean isSystem;
    }

    private class QueryTilesTask extends
            AsyncTask<Collection<QSTile<?>>, Void, Collection<TileInfo>> {
        @Override
        protected Collection<TileInfo> doInBackground(Collection<QSTile<?>>... params) {
            List<TileInfo> tiles = new ArrayList<>();
            PackageManager pm = mContext.getPackageManager();
            List<ResolveInfo> services = pm.queryIntentServicesAsUser(
                    new Intent(TileService.ACTION_QS_TILE), 0, ActivityManager.getCurrentUser());
            for (ResolveInfo info : services) {
                String packageName = info.serviceInfo.packageName;
                ComponentName componentName = new ComponentName(packageName, info.serviceInfo.name);
                final CharSequence appLabel = info.serviceInfo.applicationInfo.loadLabel(pm);
                String spec = CustomTile.toSpec(componentName);
                State state = getState(params[0], spec);
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
                addTile(spec, icon, label != null ? label.toString() : "null", appLabel, mContext);
            }
            return tiles;
        }

        private State getState(Collection<QSTile<?>> tiles, String spec) {
            for (QSTile<?> tile : tiles) {
                if (spec.equals(tile.getTileSpec())) {
                    final QSTile.State state = tile.newTileState();
                    tile.getState().copyTo(state);
                    return state;
                }
            }
            return null;
        }

        @Override
        protected void onPostExecute(Collection<TileInfo> result) {
            mTiles.addAll(result);
            mListener.onTilesChanged(mTiles);
        }
    }

    public interface TileStateListener {
        void onTilesChanged(List<TileInfo> tiles);
    }
}

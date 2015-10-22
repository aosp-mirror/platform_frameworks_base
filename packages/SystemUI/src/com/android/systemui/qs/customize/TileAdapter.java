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

package com.android.systemui.qs.customize;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.qs.QSTile;
import com.android.systemui.qs.QSTile.Icon;
import com.android.systemui.qs.tiles.CustomTile;
import com.android.systemui.statusbar.phone.QSTileHost;
import com.android.systemui.tuner.QSPagingSwitch;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;

public class TileAdapter extends BaseAdapter {

    private static final String TAG = "TileAdapter";

    private final ArrayList<TileGroup> mGroups = new ArrayList<>();
    private final Context mContext;

    private TileSelectedListener mListener;
    private ArrayList<String> mCurrentTiles;

    public TileAdapter(Context context, Collection<QSTile<?>> currentTiles, QSTileHost host) {
        mContext = context;
        addSystemTiles(currentTiles, host);
        // TODO: Live?
    }

    private void addSystemTiles(Collection<QSTile<?>> currentTiles, QSTileHost host) {
        try {
            ArrayList<String> tileSpecs = new ArrayList<>();
            for (QSTile<?> tile : currentTiles) {
                tileSpecs.add(tile.getTileSpec());
            }
            mCurrentTiles = tileSpecs;
            final TileGroup group = new TileGroup("com.android.settings", mContext);
            // TODO: Pull this list from a more authoritative place.
            String[] possibleTiles = QSPagingSwitch.QS_PAGE_TILES.split(",");
            for (int i = 0; i < possibleTiles.length; i++) {
                final String spec = possibleTiles[i];
                if (spec.startsWith("q")) {
                    // Quick tiles can't be customized.
                    continue;
                }
                if (tileSpecs.contains(spec)) {
                    continue;
                }
                final QSTile<?> tile = host.createTile(spec);
                // Bad, bad, very bad.
                tile.setListening(true);
                tile.clearState();
                tile.refreshState();
                tile.setListening(false);
                new Handler(host.getLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        group.addTile(spec, tile.getState().icon, tile.getState().label, mContext);
                    }
                });
            }
            // Error: Badness (10000).
            // Serialize this work after the host's looper's queue is empty.
            new Handler(host.getLooper()).post(new Runnable() {
                @Override
                public void run() {
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            if (group.mTiles.size() > 0) {
                                mGroups.add(group);
                                notifyDataSetChanged();
                            }
                            new QueryTilesTask().execute();
                        }
                    });
                }
            });
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Couldn't load system tiles", e);
        }
    }

    public void setListener(TileSelectedListener listener) {
        mListener = listener;
    }

    @Override
    public int getCount() {
        return mGroups.size();
    }

    @Override
    public Object getItem(int position) {
        return mGroups.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        return mGroups.get(position).getView(mContext, convertView, parent, mListener);
    }

    private static class TileGroup {
        private final ArrayList<TileInfo> mTiles = new ArrayList<>();
        private CharSequence mLabel;
        private Drawable mIcon;

        public TileGroup(String pkg, Context context) throws NameNotFoundException {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            mLabel = info.loadLabel(pm);
            mIcon = info.loadIcon(pm);
            Log.d(TAG, "Added " + mLabel);
        }

        private void addTile(String spec, Drawable icon, String label) {
            TileInfo info = new TileInfo();
            info.label = label;
            info.drawable = icon;
            info.spec = spec;
            mTiles.add(info);
        }

        private void addTile(String spec, Icon icon, String label, Context context) {
            addTile(spec, icon.getDrawable(context), label);
        }

        private View getView(Context context, View convertView, ViewGroup parent,
                final TileSelectedListener listener) {
            if (convertView == null) {
                convertView = LayoutInflater.from(context).inflate(R.layout.tile_listing, parent,
                        false);
            }
            ((TextView) convertView.findViewById(android.R.id.title)).setText(mLabel);
            ((ImageView) convertView.findViewById(android.R.id.icon)).setImageDrawable(mIcon);
            GridLayout grid = (GridLayout) convertView.findViewById(R.id.tile_grid);
            final int N = mTiles.size();
            if (grid.getChildCount() != N) {
                grid.removeAllViews();
            }
            for (int i = 0; i < N; i++) {
                if (grid.getChildCount() <= i) {
                    grid.addView(createTile(context));
                }
                View view = grid.getChildAt(i);
                final TileInfo tileInfo = mTiles.get(i);
                ((ImageView) view.findViewById(R.id.tile_icon)).setImageDrawable(tileInfo.drawable);
                ((TextView) view.findViewById(R.id.tile_label)).setText(tileInfo.label);
                view.setClickable(true);
                view.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        listener.onTileSelected(tileInfo.spec);
                    }
                });
            }
            return convertView;
        }

        private View createTile(Context context) {
            return LayoutInflater.from(context).inflate(R.layout.qs_add_tile_layout, null);
        }
    }

    private static class TileInfo {
        private String spec;
        private Drawable drawable;
        private String label;
    }

    private class QueryTilesTask extends AsyncTask<Void, Void, Collection<TileGroup>> {
        // TODO: Become non-prototype and an API.
        private static final String TILE_ACTION = "android.intent.action.QS_TILE";

        @Override
        protected Collection<TileGroup> doInBackground(Void... params) {
            HashMap<String, TileGroup> pkgMap = new HashMap<>();
            PackageManager pm = mContext.getPackageManager();
            // TODO: Handle userness.
            List<ResolveInfo> services = pm.queryIntentServices(new Intent(TILE_ACTION), 0);
            for (ResolveInfo info : services) {
                String packageName = info.serviceInfo.packageName;
                ComponentName componentName = new ComponentName(packageName, info.serviceInfo.name);
                String spec = CustomTile.PREFIX + componentName.flattenToShortString() + ")";
                if (mCurrentTiles.contains(spec)) {
                    continue;
                }
                try {
                    TileGroup group = pkgMap.get(packageName);
                    if (group == null) {
                        group = new TileGroup(packageName, mContext);
                        pkgMap.put(packageName, group);
                    }
                    Drawable icon = info.serviceInfo.loadIcon(pm);
                    CharSequence label = info.serviceInfo.loadLabel(pm);
                    group.addTile(spec, icon, label != null ? label.toString() : "null");
                } catch (NameNotFoundException e) {
                    Log.w(TAG, "Couldn't find resolved package... " + packageName, e);
                }
            }
            return pkgMap.values();
        }

        @Override
        protected void onPostExecute(Collection<TileGroup> result) {
            mGroups.addAll(result);
            notifyDataSetChanged();
        }
    }

    public interface TileSelectedListener {
        void onTileSelected(String spec);
    }
}

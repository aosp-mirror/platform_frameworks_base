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

import android.app.ActivityManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings.Secure;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;

import com.android.systemui.R;
import com.android.systemui.qs.QSPanel;
import com.android.systemui.qs.QSTile;
import com.android.systemui.qs.external.CustomTile;
import com.android.systemui.qs.external.TileLifecycleManager;
import com.android.systemui.statusbar.phone.QSTileHost;
import com.android.systemui.tuner.TunerService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * A version of QSPanel that allows tiles to be dragged around rather than
 * clicked on.  Dragging starting and receiving is handled in the NonPagedTileLayout,
 * and the saving/ordering is handled by the CustomQSTileHost.
 */
public class CustomQSPanel extends QSPanel {
    
    private static final String TAG = "CustomQSPanel";
    private static final boolean DEBUG = false;

    private List<String> mSavedTiles = Collections.emptyList();
    private ArrayList<String> mStash;
    private List<String> mTiles = new ArrayList<>();

    private ArrayList<QSTile<?>> mCurrentTiles = new ArrayList<>();

    public CustomQSPanel(Context context, AttributeSet attrs) {
        super(context, attrs);
        mTileLayout = (QSTileLayout) LayoutInflater.from(mContext)
                .inflate(R.layout.qs_customize_layout, mQsContainer, false);
        mQsContainer.addView((View) mTileLayout, 1 /* Between brightness and footer */);
        ((NonPagedTileLayout) mTileLayout).setCustomQsPanel(this);
        removeView(mFooter.getView());

        if (DEBUG) Log.d(TAG, "new CustomQSPanel", new Throwable());
        TunerService.get(mContext).addTunable(this, QSTileHost.TILES_SETTING);
    }

    @Override
    protected void showDetail(boolean show, Record r) {
        // No detail here.
    }

    @Override
    protected void onDetachedFromWindow() {
        // Don't allow the super to unregister the tunable.
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (key.equals(QS_SHOW_BRIGHTNESS)) {
            // No Brightness for you.
            super.onTuningChanged(key, "0");
        }
        if (QSTileHost.TILES_SETTING.equals(key)) {
            mSavedTiles = Collections.unmodifiableList(
                    QSTileHost.loadTileSpecs(mContext, newValue));
            if (DEBUG) Log.d(TAG, "New saved tiles " + TextUtils.join(",", mSavedTiles));
        }
    }

    @Override
    protected void createCustomizePanel() {
        // Already in CustomizePanel.
    }

    public void tileSelected(QSTile<?> tile, ClipData currentClip) {
        String sourceSpec = getSpec(currentClip);
        String destSpec = tile.getTileSpec();
        if (!sourceSpec.equals(destSpec)) {
            moveTo(sourceSpec, destSpec);
        }
    }

    public ClipData getClip(QSTile<?> tile) {
        String tileSpec = tile.getTileSpec();
        // TODO: Something better than plain text.
        // TODO: Once using something better than plain text, stop listening to non-QS drag events.
        return ClipData.newPlainText(tileSpec, tileSpec);
    }

    public String getSpec(ClipData data) {
        return data.getItemAt(0).getText().toString();
    }

    public void setSavedTiles() {
        if (DEBUG) Log.d(TAG, "setSavedTiles " + TextUtils.join(",", mSavedTiles));
        setTiles(mSavedTiles);
    }

    public void saveCurrentTiles() {
        mHost.changeTiles(mSavedTiles, mTiles);
    }

    public void stashCurrentTiles() {
        mStash = new ArrayList<>(mTiles);
    }

    public void unstashTiles() {
        setTiles(mStash);
    }

    @Override
    public void setTiles(Collection<QSTile<?>> tiles) {
        setTilesInternal();
    }

    private void setTilesInternal() {
        if (DEBUG) Log.d(TAG, "Set tiles internal");
        for (int i = 0; i < mCurrentTiles.size(); i++) {
            mCurrentTiles.get(i).destroy();
        }
        mCurrentTiles.clear();
        for (int i = 0; i < mTiles.size(); i++) {
            if (mTiles.get(i).startsWith(CustomTile.PREFIX)) {
                QSTile<?> tile = BlankCustomTile.create(mHost, mTiles.get(i));
                tile.setTileSpec(mTiles.get(i));
                mCurrentTiles.add(tile);
            } else {
                QSTile<?> tile = mHost.createTile(mTiles.get(i));
                if (tile != null) {
                    tile.setTileSpec(mTiles.get(i));
                    mCurrentTiles.add(tile);
                } else {
                    if (DEBUG) Log.d(TAG, "Skipping " + mTiles.get(i));
                }
            }
        }
        super.setTiles(mCurrentTiles);
    }

    public void addTile(String spec) {
        if (DEBUG) Log.d(TAG, "addTile " + spec);
        mTiles.add(spec);
        setTilesInternal();
    }

    public void moveTo(String from, String to) {
        if (DEBUG) Log.d(TAG, "moveTo " + from + " " + to);
        int fromIndex = mTiles.indexOf(from);
        if (fromIndex < 0) {
            Log.e(TAG, "Unknown from tile " + from);
            return;
        }
        int index = mTiles.indexOf(to);
        if (index < 0) {
            Log.e(TAG, "Unknown to tile " + to);
            return;
        }
        mTiles.remove(fromIndex);
        mTiles.add(index, from);
        setTilesInternal();
    }

    public void remove(String spec) {
        if (!mTiles.remove(spec)) {
            Log.e(TAG, "Unknown remove spec " + spec);
        }
        setTilesInternal();
    }

    public void setTiles(List<String> tiles) {
        if (DEBUG) Log.d(TAG, "Set tiles " + TextUtils.join(",", tiles));
        mTiles = new ArrayList<>(tiles);
        setTilesInternal();
    }

    public Collection<QSTile<?>> getTiles() {
        return mCurrentTiles;
    }
}

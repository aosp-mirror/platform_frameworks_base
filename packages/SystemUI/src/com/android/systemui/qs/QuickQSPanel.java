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

package com.android.systemui.qs;

import android.content.Context;
import android.content.res.Configuration;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Space;

import com.android.systemui.R;
import com.android.systemui.qs.QSTile.SignalState;
import com.android.systemui.qs.QSTile.State;
import com.android.systemui.qs.customize.QSCustomizer;
import com.android.systemui.statusbar.phone.QSTileHost;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;

import java.util.ArrayList;
import java.util.Collection;

/**
 * Version of QSPanel that only shows N Quick Tiles in the QS Header.
 */
public class QuickQSPanel extends QSPanel {

    public static final String NUM_QUICK_TILES = "sysui_qqs_count";

    private int mMaxTiles;
    private QSPanel mFullPanel;
    private View mHeader;

    public QuickQSPanel(Context context, AttributeSet attrs) {
        super(context, attrs);
        if (mTileLayout != null) {
            for (int i = 0; i < mRecords.size(); i++) {
                mTileLayout.removeTile(mRecords.get(i));
            }
            removeView((View) mTileLayout);
        }
        mTileLayout = new HeaderTileLayout(context);
        mTileLayout.setListening(mListening);
        addView((View) mTileLayout, 1 /* Between brightness and footer */);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        TunerService.get(mContext).addTunable(mNumTiles, NUM_QUICK_TILES);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        TunerService.get(mContext).removeTunable(mNumTiles);
    }

    public void setQSPanelAndHeader(QSPanel fullPanel, View header) {
        mFullPanel = fullPanel;
        mHeader = header;
    }

    @Override
    protected boolean shouldShowDetail() {
        return !mExpanded;
    }

    @Override
    protected void drawTile(TileRecord r, State state) {
        if (state instanceof SignalState) {
            State copy = r.tile.newTileState();
            state.copyTo(copy);
            // No activity shown in the quick panel.
            ((SignalState) copy).activityIn = false;
            ((SignalState) copy).activityOut = false;
            state = copy;
        }
        super.drawTile(r, state);
    }

    @Override
    protected QSTileBaseView createTileView(QSTile<?> tile, boolean collapsedView) {
        return new QSTileBaseView(mContext, tile.createTileView(mContext), collapsedView);
    }

    @Override
    public void setHost(QSTileHost host, QSCustomizer customizer) {
        super.setHost(host, customizer);
        setTiles(mHost.getTiles());
    }

    public void setMaxTiles(int maxTiles) {
        mMaxTiles = maxTiles;
        if (mHost != null) {
            setTiles(mHost.getTiles());
        }
    }

    @Override
    protected void onTileClick(QSTile<?> tile) {
        tile.secondaryClick();
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        // No tunings for you.
        if (key.equals(QS_SHOW_BRIGHTNESS)) {
            // No Brightness for you.
            super.onTuningChanged(key, "0");
        }
    }

    @Override
    public void setTiles(Collection<QSTile<?>> tiles) {
        ArrayList<QSTile<?>> quickTiles = new ArrayList<>();
        for (QSTile<?> tile : tiles) {
            quickTiles.add(tile);
            if (quickTiles.size() == mMaxTiles) {
                break;
            }
        }
        super.setTiles(quickTiles, true);
    }

    private final Tunable mNumTiles = new Tunable() {
        @Override
        public void onTuningChanged(String key, String newValue) {
            setMaxTiles(getNumQuickTiles(mContext));
        }
    };

    public int getNumQuickTiles(Context context) {
        return TunerService.get(context).getValue(NUM_QUICK_TILES, 6);
    }

    private static class HeaderTileLayout extends LinearLayout implements QSTileLayout {

        protected final ArrayList<TileRecord> mRecords = new ArrayList<>();
        private boolean mListening;

        public HeaderTileLayout(Context context) {
            super(context);
            setClipChildren(false);
            setClipToPadding(false);
            setGravity(Gravity.CENTER_VERTICAL);
            setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }

        @Override
        public void setListening(boolean listening) {
            if (mListening == listening) return;
            mListening = listening;
            for (TileRecord record : mRecords) {
                record.tile.setListening(this, mListening);
            }
        }

        @Override
        public void addTile(TileRecord tile) {
            if (getChildCount() != 0) {
                // Add a spacer.
                addView(new Space(mContext), getChildCount(), generateSpaceParams());
            }
            addView(tile.tileView, getChildCount(), generateLayoutParams());
            mRecords.add(tile);
            tile.tile.setListening(this, mListening);
        }

        private LayoutParams generateSpaceParams() {
            int size = mContext.getResources().getDimensionPixelSize(R.dimen.qs_quick_tile_size);
            LayoutParams lp = new LayoutParams(0, size);
            lp.weight = 1;
            lp.gravity = Gravity.CENTER;
            return lp;
        }

        private LayoutParams generateLayoutParams() {
            int size = mContext.getResources().getDimensionPixelSize(R.dimen.qs_quick_tile_size);
            LayoutParams lp = new LayoutParams(size, size);
            lp.gravity = Gravity.CENTER;
            return lp;
        }

        @Override
        public void removeTile(TileRecord tile) {
            int childIndex = getChildIndex(tile.tileView);
            // Remove the tile.
            removeViewAt(childIndex);
            if (getChildCount() != 0) {
                // Remove its spacer as well.
                removeViewAt(childIndex);
            }
            mRecords.remove(tile);
            tile.tile.setListening(this, false);
        }

        private int getChildIndex(QSTileBaseView tileView) {
            final int N = getChildCount();
            for (int i = 0; i < N; i++) {
                if (getChildAt(i) == tileView) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public int getOffsetTop(TileRecord tile) {
            return 0;
        }

        @Override
        public boolean updateResources() {
            // No resources here.
            return false;
        }

        @Override
        public boolean hasOverlappingRendering() {
            return false;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            if (mRecords != null && mRecords.size() > 0) {
                View previousView = this;
                for (TileRecord record : mRecords) {
                    if (record.tileView.getVisibility() == GONE) continue;
                    previousView = record.tileView.updateAccessibilityOrder(previousView);
                }
                mRecords.get(0).tileView.setAccessibilityTraversalAfter(
                        R.id.alarm_status_collapsed);
                mRecords.get(mRecords.size() - 1).tileView.setAccessibilityTraversalBefore(
                        R.id.expand_indicator);
            }
        }
    }
}

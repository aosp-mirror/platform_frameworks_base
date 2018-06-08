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

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.qs.QSTile.SignalState;
import com.android.systemui.plugins.qs.QSTile.State;
import com.android.systemui.plugins.qs.QSTileView;
import com.android.systemui.qs.customize.QSCustomizer;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;

import java.util.ArrayList;
import java.util.Collection;

/**
 * Version of QSPanel that only shows N Quick Tiles in the QS Header.
 */
public class QuickQSPanel extends QSPanel {

    public static final String NUM_QUICK_TILES = "sysui_qqs_count";

    private boolean mDisabledByPolicy;
    private int mMaxTiles;
    protected QSPanel mFullPanel;

    public QuickQSPanel(Context context, AttributeSet attrs) {
        super(context, attrs);
        if (mFooter != null) {
            removeView(mFooter.getView());
        }
        if (mTileLayout != null) {
            for (int i = 0; i < mRecords.size(); i++) {
                mTileLayout.removeTile(mRecords.get(i));
            }
            removeView((View) mTileLayout);
        }
        mTileLayout = new HeaderTileLayout(context);
        mTileLayout.setListening(mListening);
        addView((View) mTileLayout, 0 /* Between brightness and footer */);
        super.setPadding(0, 0, 0, 0);
    }

    @Override
    public void setPadding(int left, int top, int right, int bottom) {
        // Always have no padding.
    }

    @Override
    protected void addDivider() {
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Dependency.get(TunerService.class).addTunable(mNumTiles, NUM_QUICK_TILES);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Dependency.get(TunerService.class).removeTunable(mNumTiles);
    }

    public void setQSPanelAndHeader(QSPanel fullPanel, View header) {
        mFullPanel = fullPanel;
    }

    @Override
    protected boolean shouldShowDetail() {
        return !mExpanded;
    }

    @Override
    protected void drawTile(TileRecord r, State state) {
        if (state instanceof SignalState) {
            SignalState copy = new SignalState();
            state.copyTo(copy);
            // No activity shown in the quick panel.
            copy.activityIn = false;
            copy.activityOut = false;
            state = copy;
        }
        super.drawTile(r, state);
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
    public void onTuningChanged(String key, String newValue) {
        if (QS_SHOW_BRIGHTNESS.equals(key)) {
            // No Brightness or Tooltip for you!
            super.onTuningChanged(key, "0");
        }
    }

    @Override
    public void setTiles(Collection<QSTile> tiles) {
        ArrayList<QSTile> quickTiles = new ArrayList<>();
        for (QSTile tile : tiles) {
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

    public static int getNumQuickTiles(Context context) {
        return Dependency.get(TunerService.class).getValue(NUM_QUICK_TILES, 6);
    }

    void setDisabledByPolicy(boolean disabled) {
        if (disabled != mDisabledByPolicy) {
            mDisabledByPolicy = disabled;
            setVisibility(disabled ? View.GONE : View.VISIBLE);
        }
    }

    /**
     * Sets the visibility of this {@link QuickQSPanel}. This method has no effect when this panel
     * is disabled by policy through {@link #setDisabledByPolicy(boolean)}, and in this case the
     * visibility will always be {@link View#GONE}. This method is called externally by
     * {@link QSAnimator} only.
     */
    @Override
    public void setVisibility(int visibility) {
        if (mDisabledByPolicy) {
            if (getVisibility() == View.GONE) {
                return;
            }
            visibility = View.GONE;
        }
        super.setVisibility(visibility);
    }

    private static class HeaderTileLayout extends LinearLayout implements QSTileLayout {

        protected final ArrayList<TileRecord> mRecords = new ArrayList<>();
        private boolean mListening;
        /** Size of the QS tile (width & height). */
        private int mTileDimensionSize;

        public HeaderTileLayout(Context context) {
            super(context);
            setClipChildren(false);
            setClipToPadding(false);

            mTileDimensionSize = mContext.getResources().getDimensionPixelSize(
                    R.dimen.qs_quick_tile_size);

            setGravity(Gravity.CENTER);
            setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }

        @Override
        protected void onConfigurationChanged(Configuration newConfig) {
            super.onConfigurationChanged(newConfig);

            setGravity(Gravity.CENTER);
            LayoutParams staticSpaceLayoutParams = generateSpaceLayoutParams(
                    mContext.getResources().getDimensionPixelSize(
                            R.dimen.qs_quick_tile_space_width));

            // Update space params since they fill any open space in portrait orientation and have
            // a static width in landscape orientation.
            final int childViewCount = getChildCount();
            for (int i = 0; i < childViewCount; i++) {
                View childView = getChildAt(i);
                if (childView instanceof Space) {
                    childView.setLayoutParams(staticSpaceLayoutParams);
                }
            }
        }

        /**
         * Returns {@link LayoutParams} based on the given {@code spaceWidth}. If the width is 0,
         * then we're going to have the space expand to take up as much space as possible. If the
         * width is non-zero, we want the inter-tile spacers to be fixed.
         */
        private LayoutParams generateSpaceLayoutParams(int spaceWidth) {
            LayoutParams lp = new LayoutParams(spaceWidth, mTileDimensionSize);
            if (spaceWidth == 0) {
                lp.weight = 1;
            }
            lp.gravity = Gravity.CENTER;
            return lp;
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
                // Add a spacer between tiles. We want static-width spaces if we're in landscape to
                // keep the tiles close. For portrait, we stick with spaces that fill up any
                // available space.
                LayoutParams spaceLayoutParams = generateSpaceLayoutParams(
                        mContext.getResources().getDimensionPixelSize(
                                R.dimen.qs_quick_tile_space_width));
                addView(new Space(mContext), getChildCount(), spaceLayoutParams);
            }

            addView(tile.tileView, getChildCount(), generateTileLayoutParams());
            mRecords.add(tile);
            tile.tile.setListening(this, mListening);
        }

        private LayoutParams generateTileLayoutParams() {
            LayoutParams lp = new LayoutParams(mTileDimensionSize, mTileDimensionSize);
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

        private int getChildIndex(QSTileView tileView) {
            final int childViewCount = getChildCount();
            for (int i = 0; i < childViewCount; i++) {
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

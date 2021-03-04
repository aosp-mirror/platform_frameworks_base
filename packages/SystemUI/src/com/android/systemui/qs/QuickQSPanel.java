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
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.R;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.qs.QSTile.SignalState;
import com.android.systemui.plugins.qs.QSTile.State;

/**
 * Version of QSPanel that only shows N Quick Tiles in the QS Header.
 */
public class QuickQSPanel extends QSPanel {

    public static final String NUM_QUICK_TILES = "sysui_qqs_count";
    private static final String TAG = "QuickQSPanel";
    // A default value so that we never return 0.
    public static final int DEFAULT_MAX_TILES = 6;

    private boolean mDisabledByPolicy;
    private int mMaxTiles;

    public QuickQSPanel(Context context, AttributeSet attrs) {
        super(context, attrs);
        mMaxTiles = Math.min(DEFAULT_MAX_TILES,
                getResources().getInteger(R.integer.quick_qs_panel_max_columns));
    }

    @Override
    void initialize(boolean sideLabels) {
        super.initialize(sideLabels);
        applyBottomMargin((View) mRegularTileLayout);
    }

    private void applyBottomMargin(View view) {
        int margin = getResources().getDimensionPixelSize(R.dimen.qs_header_tile_margin_bottom);
        MarginLayoutParams layoutParams = (MarginLayoutParams) view.getLayoutParams();
        layoutParams.bottomMargin = margin;
        view.setLayoutParams(layoutParams);
    }

    @Override
    public void setBrightnessView(View view) {
        // Don't add brightness view
    }

    @Override
    public TileLayout createRegularTileLayout() {
        if (mSideLabels) {
            return new QQSSideLabelTileLayout(mContext);
        } else {
            return new QuickQSPanel.HeaderTileLayout(mContext);
        }
    }

    @Override
    protected QSTileLayout createHorizontalTileLayout() {
        if (mSideLabels) {
            TileLayout t = createRegularTileLayout();
            t.setMaxColumns(2);
            return t;
        } else {
            return new DoubleLineTileLayout(mContext);
        }
    }

    @Override
    protected boolean needsDynamicRowsAndColumns() {
        return false; // QQS always have the same layout
    }

    @Override
    protected boolean displayMediaMarginsOnMedia() {
        // Margins should be on the container to visually center the view
        return false;
    }

    @Override
    protected void updatePadding() {
        // QS Panel is setting a top padding by default, which we don't need.
    }

    @Override
    protected String getDumpableTag() {
        return TAG;
    }

    @Override
    protected boolean shouldShowDetail() {
        return !mExpanded;
    }

    @Override
    protected void drawTile(QSPanelControllerBase.TileRecord r, State state) {
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

    public void setMaxTiles(int maxTiles) {
        mMaxTiles = Math.min(maxTiles, DEFAULT_MAX_TILES);
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (QS_SHOW_BRIGHTNESS.equals(key)) {
            // No Brightness or Tooltip for you!
            super.onTuningChanged(key, "0");
        }
    }

    public int getNumQuickTiles() {
        return mMaxTiles;
    }

    /**
     * Parses the String setting into the number of tiles. Defaults to {@code mDefaultMaxTiles}
     *
     * @param numTilesValue value of the setting to parse
     * @return parsed value of numTilesValue OR {@code mDefaultMaxTiles} on error
     */
    public static int parseNumTiles(String numTilesValue) {
        try {
            return Integer.parseInt(numTilesValue);
        } catch (NumberFormatException e) {
            // Couldn't read an int from the new setting value. Use default.
            return DEFAULT_MAX_TILES;
        }
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

    @Override
    protected QSEvent openPanelEvent() {
        return QSEvent.QQS_PANEL_EXPANDED;
    }

    @Override
    protected QSEvent closePanelEvent() {
        return QSEvent.QQS_PANEL_COLLAPSED;
    }

    @Override
    protected QSEvent tileVisibleEvent() {
        return QSEvent.QQS_TILE_VISIBLE;
    }

    private static class HeaderTileLayout extends TileLayout {

        private Rect mClippingBounds = new Rect();

        HeaderTileLayout(Context context) {
            super(context);
            setClipChildren(false);
            setClipToPadding(false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            setLayoutParams(lp);
        }

        @Override
        protected void onConfigurationChanged(Configuration newConfig) {
            super.onConfigurationChanged(newConfig);
            updateResources();
        }

        @Override
        public void onFinishInflate(){
            super.onFinishInflate();
            updateResources();
        }

        private LayoutParams generateTileLayoutParams() {
            LayoutParams lp = new LayoutParams(mCellWidth, mCellHeight);
            return lp;
        }

        @Override
        protected void addTileView(QSPanelControllerBase.TileRecord tile) {
            addView(tile.tileView, getChildCount(), generateTileLayoutParams());
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            // We only care about clipping on the right side
            mClippingBounds.set(0, 0, r - l, 10000);
            setClipBounds(mClippingBounds);

            calculateColumns();

            for (int i = 0; i < mRecords.size(); i++) {
                mRecords.get(i).tileView.setVisibility( i < mColumns ? View.VISIBLE : View.GONE);
            }

            setAccessibilityOrder();
            layoutTileRecords(mColumns);
        }

        @Override
        public boolean updateResources() {
            mCellWidth = mContext.getResources().getDimensionPixelSize(R.dimen.qs_quick_tile_size);
            mCellHeight = mCellWidth;

            return false;
        }

        private boolean calculateColumns() {
            int prevNumColumns = mColumns;
            int maxTiles = mRecords.size();

            if (maxTiles == 0){ // Early return during setup
                mColumns = 0;
                return true;
            }

            final int availableWidth = getMeasuredWidth() - getPaddingStart() - getPaddingEnd();
            final int leftoverWhitespace = availableWidth - maxTiles * mCellWidth;

            if (leftoverWhitespace > 0) {
                mCellMarginHorizontal = leftoverWhitespace / Math.max(1, maxTiles);
                mColumns = maxTiles;
            } else{
                mColumns = mCellWidth == 0 ? 1 :
                        Math.min(maxTiles, availableWidth / mCellWidth );
                // If we can only fit one column, use mCellMarginHorizontal to center it.
                if (mColumns == 1) {
                    mCellMarginHorizontal = (availableWidth - mCellWidth) / 2;
                } else {
                    mCellMarginHorizontal =
                            (availableWidth - mColumns * mCellWidth) / mColumns;
                }

            }
            return mColumns != prevNumColumns;
        }

        private void setAccessibilityOrder() {
            if (mRecords != null && mRecords.size() > 0) {
                View previousView = this;
                for (QSPanelControllerBase.TileRecord record : mRecords) {
                    if (record.tileView.getVisibility() == GONE) continue;
                    previousView = record.tileView.updateAccessibilityOrder(previousView);
                }
                mRecords.get(mRecords.size() - 1).tileView.setAccessibilityTraversalBefore(
                        R.id.expand_indicator);
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            // Measure each QS tile.
            for (QSPanelControllerBase.TileRecord record : mRecords) {
                if (record.tileView.getVisibility() == GONE) continue;
                record.tileView.measure(exactly(mCellWidth), exactly(mCellHeight));
            }

            int height = mCellHeight;
            if (height < 0) height = 0;

            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height);
        }

        @Override
        public int getNumVisibleTiles() {
            return Math.min(mRecords.size(), mColumns);
        }

        @Override
        protected int getColumnStart(int column) {
            if (mColumns == 1) {
                // Only one column/tile. Use the margin to center the tile.
                return getPaddingStart() + mCellMarginHorizontal;
            }
            return getPaddingStart() + mCellMarginHorizontal / 2
                    + column *  (mCellWidth + mCellMarginHorizontal);
        }

        @Override
        public void setListening(boolean listening, UiEventLogger uiEventLogger) {
            boolean startedListening = !mListening && listening;
            super.setListening(listening, uiEventLogger);
            if (startedListening) {
                // getNumVisibleTiles() <= mRecords.size()
                for (int i = 0; i < getNumVisibleTiles(); i++) {
                    QSTile tile = mRecords.get(i).tile;
                    uiEventLogger.logWithInstanceId(QSEvent.QQS_TILE_VISIBLE, 0,
                            tile.getMetricsSpec(), tile.getInstanceId());
                }
            }
        }
    }

    static class QQSSideLabelTileLayout extends SideLabelTileLayout {
        QQSSideLabelTileLayout(Context context) {
            super(context, null);
            setClipChildren(false);
            setClipToPadding(false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT);
            setLayoutParams(lp);
            setMaxColumns(4);
        }

        @Override
        public boolean updateResources() {
            boolean b = super.updateResources();
            mMaxAllowedRows = 2;
            return b;
        }

        @Override
        protected void onConfigurationChanged(Configuration newConfig) {
            super.onConfigurationChanged(newConfig);
            updateResources();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            // Make sure to always use the correct number of rows. As it's determined by the
            // columns, just use as many as needed.
            updateMaxRows(10000, mRecords.size());
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }
}

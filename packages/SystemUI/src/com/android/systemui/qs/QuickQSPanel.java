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

    private static final String TAG = "QuickQSPanel";
    // A fallback value for max tiles number when setting via Tuner (parseNumTiles)
    public static final int TUNER_MAX_TILES_FALLBACK = 6;

    private boolean mDisabledByPolicy;
    private int mMaxTiles;

    public QuickQSPanel(Context context, AttributeSet attrs) {
        super(context, attrs);
        mMaxTiles = getResources().getInteger(R.integer.quick_qs_panel_max_tiles);
    }

    @Override
    void initialize() {
        super.initialize();
        if (mHorizontalContentContainer != null) {
            mHorizontalContentContainer.setClipChildren(false);
        }
    }

    @Override
    public TileLayout getOrCreateTileLayout() {
        return new QQSSideLabelTileLayout(mContext);
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
        mMaxTiles = maxTiles;
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
     * Parses the String setting into the number of tiles. Defaults to
     * {@link #TUNER_MAX_TILES_FALLBACK}
     *
     * @param numTilesValue value of the setting to parse
     * @return parsed value of numTilesValue OR {@link #TUNER_MAX_TILES_FALLBACK} on error
     */
    public static int parseNumTiles(String numTilesValue) {
        try {
            return Integer.parseInt(numTilesValue);
        } catch (NumberFormatException e) {
            // Couldn't read an int from the new setting value. Use default.
            return TUNER_MAX_TILES_FALLBACK;
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

    static class QQSSideLabelTileLayout extends SideLabelTileLayout {

        QQSSideLabelTileLayout(Context context) {
            super(context, null);
            setClipChildren(false);
            setClipToPadding(false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT);
            setLayoutParams(lp);
            setMaxColumns(4);
            mLastRowPadding = true;
        }

        @Override
        public boolean updateResources() {
            mCellHeightResId = R.dimen.qs_quick_tile_size;
            boolean b = super.updateResources();
            mMaxAllowedRows = getResources().getInteger(R.integer.quick_qs_panel_max_rows);
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
}

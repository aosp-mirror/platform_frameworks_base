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
import android.content.res.ColorStateList;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import com.android.systemui.R;

import java.util.ArrayList;
import java.util.Collection;

/**
 * Version of QSPanel that only shows N Quick Tiles in the QS Header.
 */
public class QuickQSPanel extends QSPanel {

    private int mMaxTiles;
    private QSPanel mFullPanel;
    private View mHeader;

    public QuickQSPanel(Context context, AttributeSet attrs) {
        super(context, attrs);
        if (mTileLayout != null) {
            for (int i = 0; i < mRecords.size(); i++) {
                mTileLayout.removeTile(mRecords.get(i));
            }
            mQsContainer.removeView((View) mTileLayout);
        }
        mTileLayout = new HeaderTileLayout(context);
        mQsContainer.addView((View) mTileLayout, 1 /* Between brightness and footer */);
    }

    public void setQSPanelAndHeader(QSPanel fullPanel, View header) {
        mFullPanel = fullPanel;
        mHeader = header;
    }

    @Override
    protected void handleShowDetail(QSPanel.Record r, boolean show) {
        if (show) {
            mHeader.performClick();
            mFullPanel.showDetail(show, r);
        } else {
            // Not sure how we would end up here...
            super.handleShowDetail(r, show);
        }
    }

    @Override
    protected QSTileBaseView createTileView(QSTile<?> tile) {
        return new QSTileBaseView(mContext, tile.createTileView(mContext));
    }

    public void setMaxTiles(int maxTiles) {
        mMaxTiles = maxTiles;
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
        super.setTiles(quickTiles);
    }

    private static class HeaderTileLayout extends LinearLayout implements QSTileLayout {

        public HeaderTileLayout(Context context) {
            super(context);
            setClipChildren(false);
            setClipToPadding(false);
            setGravity(Gravity.CENTER_VERTICAL);
            setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

            int padding =
                    mContext.getResources().getDimensionPixelSize(R.dimen.qs_quick_tile_padding);
            ImageView downArrow = new ImageView(context);
            downArrow.setImageResource(R.drawable.ic_expand_more);
            downArrow.setImageTintList(ColorStateList.valueOf(context.getResources().getColor(
                    android.R.color.white, null)));
            downArrow.setLayoutParams(generateLayoutParams());
            downArrow.setPadding(padding, padding, padding, padding);
            addView(downArrow);
            setOrientation(LinearLayout.HORIZONTAL);
        }

        @Override
        public void addTile(TileRecord tile) {
            tile.tileView.setLayoutParams(generateLayoutParams());
            addView(tile.tileView, getChildCount() - 1 /* Leave icon at end */);
        }

        private LayoutParams generateLayoutParams() {
            int size =
                    mContext.getResources().getDimensionPixelSize(R.dimen.qs_quick_tile_size);
            LayoutParams lp = new LayoutParams(size, size);
            return lp;
        }

        @Override
        public void removeTile(TileRecord tile) {
            removeView(tile.tileView);
        }

        @Override
        public void setTileVisibility(TileRecord tile, int visibility) {
            tile.tileView.setVisibility(visibility);
        }

        @Override
        public int getOffsetTop(TileRecord tile) {
            return 0;
        }

        @Override
        public void updateResources() {
            // No resources here.
        }
    }
}

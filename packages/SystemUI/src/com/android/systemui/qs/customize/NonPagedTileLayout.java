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

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.DragEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;

import com.android.systemui.R;
import com.android.systemui.qs.PagedTileLayout;
import com.android.systemui.qs.PagedTileLayout.TilePage;
import com.android.systemui.qs.QSPanel.QSTileLayout;
import com.android.systemui.qs.QSPanel.TileRecord;
import com.android.systemui.qs.QSTileView;
import com.android.systemui.qs.QuickTileLayout;

import java.util.ArrayList;

/**
 * Similar to PagedTileLayout, except that instead of pages it lays them out
 * vertically and expects to be inside a ScrollView.
 * @see CustomQSPanel
 */
public class NonPagedTileLayout extends LinearLayout implements QSTileLayout {

    private QuickTileLayout mQuickTiles;
    private final ArrayList<TilePage> mPages = new ArrayList<>();
    private final ArrayList<TileRecord> mTiles = new ArrayList<TileRecord>();
    private CustomQSPanel mPanel;
    private final Rect mHitRect = new Rect();

    public NonPagedTileLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mQuickTiles = (QuickTileLayout) findViewById(R.id.quick_tile_layout);
        TilePage page = (PagedTileLayout.TilePage) findViewById(R.id.tile_page);
        page.setMaxRows(3 /* First page only gets 3 */);
        mPages.add(page);
    }

    public void setCustomQsPanel(CustomQSPanel qsPanel) {
        mPanel = qsPanel;
    }

    private void clear() {
        mQuickTiles.removeAllViews();
        for (int i = 0; i < mPages.size(); i++) {
            mPages.get(i).removeAllViews();
        }
    }

    @Override
    public void addTile(TileRecord tile) {
        mTiles.add(tile);
        distributeTiles();
    }

    @Override
    public void removeTile(TileRecord tile) {
        if (mTiles.remove(tile)) {
            distributeTiles();
        }
    }

    private void distributeTiles() {
        mQuickTiles.removeAllViews();
        final int NP = mPages.size();
        for (int i = 0; i < NP; i++) {
            mPages.get(i).removeAllViews();
        }
        int index = 0;
        final int NT = mTiles.size();
        for (int i = 0; i < NT; i++) {
            TileRecord tile = mTiles.get(i);
            if (tile.tile.getTileType() == QSTileView.QS_TYPE_QUICK) {
                tile.tileView.setType(QSTileView.QS_TYPE_QUICK);
                mQuickTiles.addView(tile.tileView);
                continue;
            }
            mPages.get(index).addTile(tile);
            if (mPages.get(index).isFull()) {
                if (++index == mPages.size()) {
                    LayoutInflater inflater = LayoutInflater.from(mContext);
                    inflater.inflate(R.layout.horizontal_divider, this);
                    mPages.add((TilePage) inflater.inflate(R.layout.qs_paged_page, this, false));
                    addView(mPages.get(mPages.size() - 1));
                }
            }
        }
    }

    @Override
    public void setTileVisibility(TileRecord tile, int visibility) {
        // All tiles visible here, so that they can be re-arranged.
        tile.tileView.setVisibility(View.VISIBLE);
    }

    @Override
    public int getOffsetTop(TileRecord tile) {
        // TODO: Fix this.
        return getTop();
    }

    @Override
    public void updateResources() {
    }

    @Override
    public boolean onDragEvent(DragEvent event) {
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_LOCATION:
                float x = event.getX();
                float y = event.getY();
                if (contains(mQuickTiles, x, y)) {
                    // TODO: Reset to pre-drag state.
                } else {
                    final int NP = mPages.size();
                    for (int i = 0; i < NP; i++) {
                        TilePage page = mPages.get(i);
                        if (contains(page, x, y)) {
                            x -= page.getLeft();
                            y -= page.getTop();
                            final int NC = page.getChildCount();
                            for (int j = 0; j < NC; j++) {
                                View child = page.getChildAt(j);
                                if (contains(child, x, y)) {
                                    mPanel.tileSelected(child);
                                }
                            }
                            break;
                        }
                    }
                }
                break;
            case DragEvent.ACTION_DRAG_ENDED:
                mPanel.onDragEnded();
                break;
        }
        return true;
    }

    private boolean contains(View v, float x, float y) {
        v.getHitRect(mHitRect);
        return mHitRect.contains((int) x, (int) y);
    }
}

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

import android.content.ClipData;
import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;

import com.android.systemui.R;
import com.android.systemui.qs.QSPanel;
import com.android.systemui.qs.QSTile;
import com.android.systemui.qs.QSTileView;
import com.android.systemui.statusbar.phone.QSTileHost;

/**
 * A version of QSPanel that allows tiles to be dragged around rather than
 * clicked on.  Dragging is started here, receiving is handled in the NonPagedTileLayout,
 * and the saving/ordering is handled by the CustomQSTileHost.
 */
public class CustomQSPanel extends QSPanel implements OnTouchListener {

    private CustomQSTileHost mCustomHost;
    private ClipData mCurrentClip;
    private View mCurrentView;

    public CustomQSPanel(Context context, AttributeSet attrs) {
        super(context, attrs);
        mTileLayout = (QSTileLayout) LayoutInflater.from(mContext)
                .inflate(R.layout.qs_customize_layout, mQsContainer, false);
        mQsContainer.addView((View) mTileLayout, 1 /* Between brightness and footer */);
        ((NonPagedTileLayout) mTileLayout).setCustomQsPanel(this);
    }

    @Override
    public void setHost(QSTileHost host) {
        super.setHost(host);
        mCustomHost = (CustomQSTileHost) host;
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (key.equals(QS_SHOW_BRIGHTNESS)) {
            // No Brightness for you.
            super.onTuningChanged(key, "0");
        }
    }

    @Override
    protected void addTile(QSTile<?> tile) {
        super.addTile(tile);
        if (tile.getTileType() != QSTileView.QS_TYPE_QUICK) {
            TileRecord record = mRecords.get(mRecords.size() - 1);
            if (record.tileView.getTag() == record.tile) {
                return;
            }
            record.tileView.setTag(record.tile);
            record.tileView.setVisibility(View.VISIBLE);
            record.tileView.init(null, null, null);
            record.tileView.setOnTouchListener(this);
            if (mCurrentClip != null
                    && mCurrentClip.getItemAt(0).getText().toString().equals(tile.getTileSpec())) {
                record.tileView.setAlpha(.3f);
                mCurrentView = record.tileView;
            }
        }
    }

    public void tileSelected(View v) {
        String sourceSpec = mCurrentClip.getItemAt(0).getText().toString();
        String destSpec = ((QSTile<?>) v.getTag()).getTileSpec();
        if (!sourceSpec.equals(destSpec)) {
            mCustomHost.moveTo(sourceSpec, destSpec);
        }
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                String tileSpec = (String) ((QSTile<?>) v.getTag()).getTileSpec();
                mCurrentView = v;
                mCurrentClip = ClipData.newPlainText(tileSpec, tileSpec);
                View.DragShadowBuilder shadow = new View.DragShadowBuilder(v);
                ((View) getParent().getParent()).startDrag(mCurrentClip, shadow, null, 0);
                v.setAlpha(.3f);
                return true;
        }
        return false;
    }

    public void onDragEnded() {
        mCurrentView.setAlpha(1f);
        mCurrentView = null;
        mCurrentClip = null;
    }
}

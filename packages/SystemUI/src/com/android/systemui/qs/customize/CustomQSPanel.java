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
import android.view.View;

import com.android.systemui.R;
import com.android.systemui.qs.QSPanel;
import com.android.systemui.qs.QSTile;
import com.android.systemui.statusbar.phone.QSTileHost;

/**
 * A version of QSPanel that allows tiles to be dragged around rather than
 * clicked on.  Dragging starting and receiving is handled in the NonPagedTileLayout,
 * and the saving/ordering is handled by the CustomQSTileHost.
 */
public class CustomQSPanel extends QSPanel {

    private CustomQSTileHost mCustomHost;

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

    public CustomQSTileHost getCustomHost() {
        return mCustomHost;
    }

    public void tileSelected(QSTile<?> tile, ClipData currentClip) {
        String sourceSpec = getSpec(currentClip);
        String destSpec = tile.getTileSpec();
        if (!sourceSpec.equals(destSpec)) {
            mCustomHost.moveTo(sourceSpec, destSpec);
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
}

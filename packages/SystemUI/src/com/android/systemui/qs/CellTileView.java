/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs;

import android.content.Context;
import android.content.res.ColorStateList;
import android.widget.FrameLayout;
import android.widget.FrameLayout.LayoutParams;
import android.widget.ImageView;

import com.android.settingslib.Utils;
import com.android.systemui.R;
import com.android.systemui.plugins.qs.QSTile.State;
import com.android.systemui.qs.tileimpl.QSTileImpl.ResourceIcon;

// Exists to provide easy way to add sim icon to cell tile
// TODO Find a better way to handle this and remove it.
public class CellTileView extends SignalTileView {

    private final ImageView mOverlay;

    public CellTileView(Context context) {
        super(context);
        mOverlay = new ImageView(mContext);
        mOverlay.setImageTintList(ColorStateList.valueOf(Utils.getColorAttr(context,
                android.R.attr.colorPrimary)));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT);
        int padding = context.getResources().getDimensionPixelOffset(R.dimen.cell_overlay_padding);
        params.leftMargin = params.rightMargin = padding;
        mIconFrame.addView(mOverlay, params);
    }

    @Override
    public void setIcon(State state) {
        State s = state.copy();
        updateIcon(mOverlay, state);
        s.icon = ResourceIcon.get(R.drawable.ic_sim);
        super.setIcon(s);
    }
}

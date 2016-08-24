/*
 * Copyright (C) 2014 The Android Open Source Project
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
import android.content.res.Resources;
import android.util.MathUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.FontSizeUtils;
import com.android.systemui.R;
import libcore.util.Objects;

/** View that represents a standard quick settings tile. **/
public class QSTileView extends QSTileBaseView {
    protected final Context mContext;
    private final int mTileSpacingPx;
    private int mTilePaddingTopPx;

    protected TextView mLabel;
    private ImageView mPadLock;

    public QSTileView(Context context, QSIconView icon) {
        this(context, icon, false);
    }

    public QSTileView(Context context, QSIconView icon, boolean collapsedView) {
        super(context, icon, collapsedView);

        mContext = context;
        final Resources res = context.getResources();
        mTileSpacingPx = res.getDimensionPixelSize(R.dimen.qs_tile_spacing);

        setClipChildren(false);

        setClickable(true);
        updateTopPadding();
        setId(View.generateViewId());
        createLabel();
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
    }

    TextView getLabel() {
        return mLabel;
    }

    private void updateTopPadding() {
        Resources res = getResources();
        int padding = res.getDimensionPixelSize(R.dimen.qs_tile_padding_top);
        int largePadding = res.getDimensionPixelSize(R.dimen.qs_tile_padding_top_large_text);
        float largeFactor = (MathUtils.constrain(getResources().getConfiguration().fontScale,
                1.0f, FontSizeUtils.LARGE_TEXT_SCALE) - 1f) / (FontSizeUtils.LARGE_TEXT_SCALE - 1f);
        mTilePaddingTopPx = Math.round((1 - largeFactor) * padding + largeFactor * largePadding);
        setPadding(mTileSpacingPx, mTilePaddingTopPx + mTileSpacingPx, mTileSpacingPx,
                mTileSpacingPx);
        requestLayout();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateTopPadding();
        FontSizeUtils.updateFontSize(mLabel, R.dimen.qs_tile_text_size);
    }

    protected void createLabel() {
        View view = LayoutInflater.from(mContext).inflate(R.layout.qs_tile_label, null);
        mLabel = (TextView) view.findViewById(R.id.tile_label);
        mPadLock = (ImageView) view.findViewById(R.id.restricted_padlock);
        addView(view);
    }

    @Override
    protected void handleStateChanged(QSTile.State state) {
        super.handleStateChanged(state);
        if (!Objects.equal(mLabel.getText(), state.label)) {
            mLabel.setText(state.label);
        }
        mLabel.setEnabled(!state.disabledByPolicy);
        mPadLock.setVisibility(state.disabledByPolicy ? View.VISIBLE : View.GONE);
    }
}

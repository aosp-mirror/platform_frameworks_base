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
import android.graphics.Typeface;
import android.util.MathUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;
import com.android.systemui.FontSizeUtils;
import com.android.systemui.R;

/** View that represents a standard quick settings tile. **/
public class QSTileView extends QSTileBaseView {
    private static final Typeface CONDENSED = Typeface.create("sans-serif-condensed",
            Typeface.NORMAL);

    protected final Context mContext;
    private final int mTileSpacingPx;
    private int mTilePaddingTopPx;

    private TextView mLabel;

    public QSTileView(Context context, QSIconView icon) {
        super(context, icon);

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

    private void createLabel() {
        final Resources res = mContext.getResources();
        mLabel = new TextView(mContext);
        mLabel.setTextColor(mContext.getColor(R.color.qs_tile_text));
        mLabel.setGravity(Gravity.CENTER_HORIZONTAL);
        mLabel.setMinLines(2);
        mLabel.setPadding(0, 0, 0, 0);
        mLabel.setTypeface(CONDENSED);
        mLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                res.getDimensionPixelSize(R.dimen.qs_tile_text_size));
        mLabel.setClickable(false);
        addView(mLabel);
    }

    public void init(OnClickListener clickPrimary, OnLongClickListener longClick) {
        setOnClickListener(clickPrimary);
        setOnLongClickListener(longClick);
    }

    protected void handleStateChanged(QSTile.State state) {
        super.handleStateChanged(state);
        mLabel.setText(state.label);
    }
}

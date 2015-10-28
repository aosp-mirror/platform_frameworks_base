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

package com.android.systemui;

import android.content.Context;
import android.view.View;
import android.widget.ImageView;
import com.android.systemui.qs.QSTile;
import com.android.systemui.qs.QSTileBaseView;

public class QSQuickTileView extends QSTileBaseView {

    private final int mPadding;
    private final ImageView mIcon;

    public QSQuickTileView(Context context) {
        super(context);
        mPadding = context.getResources().getDimensionPixelSize(R.dimen.qs_quick_tile_padding);
        mIcon = createIcon();
        addView(mIcon);
    }

    protected ImageView createIcon() {
        final ImageView icon = new ImageView(mContext);
        icon.setId(android.R.id.icon);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        return icon;
    }

    @Override
    public void init(OnClickListener click, OnClickListener clickSecondary,
                     OnLongClickListener longClick) {
        setClickable(true);
        setOnClickListener(click);
    }

    @Override
    protected void handleStateChanged(QSTile.State state) {
        mIcon.setImageDrawable(state.icon.getDrawable(getContext()));
        setContentDescription(state.contentDescription);
    }

    @Override
    public boolean setType(int type) {
        return false;
    }

    @Override
    public View updateAccessibilityOrder(View previousView) {
        return this;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        mIcon.measure(exactly(getMeasuredWidth() - 2 * mPadding),
                exactly(getMeasuredHeight() - 2 * mPadding));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        layout(mIcon, mPadding, mPadding);
    }
}

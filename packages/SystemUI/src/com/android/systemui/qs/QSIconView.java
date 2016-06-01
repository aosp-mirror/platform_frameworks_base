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
import android.content.res.Resources;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;
import com.android.systemui.R;

import java.util.Objects;

public class QSIconView extends ViewGroup {

    protected final View mIcon;
    protected final int mIconSizePx;
    protected final int mTilePaddingBelowIconPx;
    private boolean mAnimationEnabled = true;

    public QSIconView(Context context) {
        super(context);

        final Resources res = context.getResources();
        mIconSizePx = res.getDimensionPixelSize(R.dimen.qs_tile_icon_size);
        mTilePaddingBelowIconPx =  res.getDimensionPixelSize(R.dimen.qs_tile_padding_below_icon);

        mIcon = createIcon();
        addView(mIcon);
    }

    public void disableAnimation() {
        mAnimationEnabled = false;
    }

    public View getIconView() {
        return mIcon;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int w = MeasureSpec.getSize(widthMeasureSpec);
        final int iconSpec = exactly(mIconSizePx);
        mIcon.measure(MeasureSpec.makeMeasureSpec(w, getIconMeasureMode()), iconSpec);
        setMeasuredDimension(w, mIcon.getMeasuredHeight() + mTilePaddingBelowIconPx);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        final int w = getMeasuredWidth();
        final int h = getMeasuredHeight();
        int top = 0;
        final int iconLeft = (w - mIcon.getMeasuredWidth()) / 2;
        layout(mIcon, iconLeft, top);
    }

    public void setIcon(QSTile.State state) {
        setIcon((ImageView) mIcon, state);
    }

    protected void setIcon(ImageView iv, QSTile.State state) {
        if (!Objects.equals(state.icon, iv.getTag(R.id.qs_icon_tag))) {
            Drawable d = state.icon != null
                    ? iv.isShown() && mAnimationEnabled ? state.icon.getDrawable(mContext)
                    : state.icon.getInvisibleDrawable(mContext) : null;
            int padding = state.icon != null ? state.icon.getPadding() : 0;
            if (d != null && state.autoMirrorDrawable) {
                d.setAutoMirrored(true);
            }
            iv.setImageDrawable(d);
            iv.setTag(R.id.qs_icon_tag, state.icon);
            iv.setPadding(0, padding, 0, padding);
            if (d instanceof Animatable && iv.isShown()) {
                Animatable a = (Animatable) d;
                a.start();
                if (!iv.isShown()) {
                    a.stop(); // skip directly to end state
                }
            }
        }
        if (state.disabledByPolicy) {
            iv.setColorFilter(getContext().getColor(R.color.qs_tile_disabled_color));
        } else {
            iv.clearColorFilter();
        }
    }

    protected int getIconMeasureMode() {
        return MeasureSpec.EXACTLY;
    }

    protected View createIcon() {
        final ImageView icon = new ImageView(mContext);
        icon.setId(android.R.id.icon);
        icon.setScaleType(ScaleType.FIT_CENTER);
        return icon;
    }

    protected final int exactly(int size) {
        return MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY);
    }

    protected final void layout(View child, int left, int top) {
        child.layout(left, top, left + child.getMeasuredWidth(), top + child.getMeasuredHeight());
    }
}

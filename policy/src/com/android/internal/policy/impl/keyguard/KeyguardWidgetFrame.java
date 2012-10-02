/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.internal.policy.impl.keyguard;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.NinePatchDrawable;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import com.android.internal.R;

public class KeyguardWidgetFrame extends FrameLayout {
    private final static PorterDuffXfermode sAddBlendMode =
            new PorterDuffXfermode(PorterDuff.Mode.ADD);
    private static Drawable sLeftOverscrollDrawable;
    private static Drawable sRightOverscrollDrawable;

    private Drawable mForegroundDrawable;
    private float mOverScrollAmount = 0f;
    private final Rect mForegroundRect = new Rect();
    private int mForegroundAlpha = 0;

    public KeyguardWidgetFrame(Context context) {
        this(context, null, 0);
    }

    public KeyguardWidgetFrame(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyguardWidgetFrame(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        Resources res = context.getResources();
        if (sLeftOverscrollDrawable == null) {
            sLeftOverscrollDrawable = res.getDrawable(R.drawable.kg_widget_overscroll_layer_left);
            sRightOverscrollDrawable = res.getDrawable(R.drawable.kg_widget_overscroll_layer_right);
        }

        int hPadding = res.getDimensionPixelSize(R.dimen.kg_widget_pager_horizontal_padding);
        int topPadding = res.getDimensionPixelSize(R.dimen.kg_widget_pager_top_padding);
        int bottomPadding = res.getDimensionPixelSize(R.dimen.kg_widget_pager_bottom_padding);
        setPadding(hPadding, topPadding, hPadding, bottomPadding);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (mForegroundAlpha > 0) {
            mForegroundDrawable.setBounds(mForegroundRect);
            Paint p = ((NinePatchDrawable) mForegroundDrawable).getPaint();
            p.setXfermode(sAddBlendMode);
            mForegroundDrawable.draw(canvas);
            p.setXfermode(null);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mForegroundRect.set(getPaddingLeft(), getPaddingTop(),
                w - getPaddingRight(), h - getPaddingBottom());
    }

    void setOverScrollAmount(float r, boolean left) {
        if (Float.compare(mOverScrollAmount, r) != 0) {
            mOverScrollAmount = r;
            if (left && mForegroundDrawable != sLeftOverscrollDrawable) {
                mForegroundDrawable = sLeftOverscrollDrawable;
            } else if (!left && mForegroundDrawable != sRightOverscrollDrawable) {
                mForegroundDrawable = sRightOverscrollDrawable;
            }

            mForegroundAlpha = (int) Math.round((r * 255));
            mForegroundDrawable.setAlpha(mForegroundAlpha);
            if (getLayerType() != LAYER_TYPE_HARDWARE) {
                setLayerType(LAYER_TYPE_HARDWARE, null);
            }
            invalidate();
        } else {
            if (getLayerType() != LAYER_TYPE_NONE) {
                setLayerType(LAYER_TYPE_NONE, null);
            }
        }
    }
}

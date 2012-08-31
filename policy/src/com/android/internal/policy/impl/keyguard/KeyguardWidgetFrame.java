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
    private static int sWidgetPagePadding;
    private static Drawable sLeftOverscrollDrawable;
    private static Drawable sRightOverscrollDrawable;

    private Drawable mForegroundDrawable;
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
        if (sLeftOverscrollDrawable == null) {
            Resources res = context.getResources();
            sLeftOverscrollDrawable = res.getDrawable(R.drawable.kg_widget_overscroll_layer_left);
            sRightOverscrollDrawable = res.getDrawable(R.drawable.kg_widget_overscroll_layer_right);
            sWidgetPagePadding = res.getDimensionPixelSize(R.dimen.kg_widget_page_padding);
        }
        setPadding(sWidgetPagePadding, sWidgetPagePadding, sWidgetPagePadding, sWidgetPagePadding);
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
        mForegroundRect.set(sWidgetPagePadding, sWidgetPagePadding,
                w - sWidgetPagePadding, h - sWidgetPagePadding);
    }

    void setOverScrollAmount(float r, boolean left) {
        if (left && mForegroundDrawable != sLeftOverscrollDrawable) {
            mForegroundDrawable = sLeftOverscrollDrawable;
        } else if (!left && mForegroundDrawable != sRightOverscrollDrawable) {
            mForegroundDrawable = sRightOverscrollDrawable;
        }

        mForegroundAlpha = (int) Math.round((r * 255));
        mForegroundDrawable.setAlpha(mForegroundAlpha);
        invalidate();
    }
}

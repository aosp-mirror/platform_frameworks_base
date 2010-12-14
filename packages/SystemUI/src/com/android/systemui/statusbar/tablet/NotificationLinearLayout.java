/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.systemui.statusbar.tablet;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.Slog;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.R;

public class NotificationLinearLayout extends LinearLayout {
    private static final String TAG = "NotificationLinearLayout";

    Drawable mItemGlow;
    int mInsetLeft;
    Rect mTmp = new Rect();

    public NotificationLinearLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NotificationLinearLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        final Resources res = context.getResources();

        mItemGlow = res.getDrawable(R.drawable.notify_item_glow_bottom);

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.NotificationLinearLayout,
                defStyle, 0);
        mInsetLeft = a.getDimensionPixelSize(R.styleable.NotificationLinearLayout_insetLeft, 0);
        a.recycle();
    }

    @Override
    public void onFinishInflate() {
        super.onFinishInflate();
        setWillNotDraw(false);
    }

    @Override
    public void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        final Rect padding = mTmp;
        final Drawable glow = mItemGlow;
        glow.getPadding(padding);
        final int glowHeight = glow.getIntrinsicHeight();
        final int insetLeft = mInsetLeft;

        final int N = getChildCount();
        for (int i=0; i<N; i++) {
            final View child = getChildAt(i);

            final int childBottom = child.getBottom();

            glow.setBounds(child.getLeft() - padding.left + insetLeft, childBottom,
                    child.getRight() - padding.right, childBottom + glowHeight);
            glow.draw(canvas);
        }
    }
}

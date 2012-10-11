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
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.FrameLayout;

import com.android.internal.R;

public class KeyguardWidgetFrame extends FrameLayout {
    private final static PorterDuffXfermode sAddBlendMode =
            new PorterDuffXfermode(PorterDuff.Mode.ADD);

    private int mGradientColor;
    private LinearGradient mForegroundGradient;
    private LinearGradient mLeftToRightGradient;
    private LinearGradient mRightToLeftGradient;
    private Paint mGradientPaint = new Paint();
    boolean mLeftToRight = true;

    private float mOverScrollAmount = 0f;
    private final Rect mForegroundRect = new Rect();
    private int mForegroundAlpha = 0;
    private PowerManager mPowerManager;
    private boolean mDisableInteraction;

    public KeyguardWidgetFrame(Context context) {
        this(context, null, 0);
    }

    public KeyguardWidgetFrame(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyguardWidgetFrame(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);

        Resources res = context.getResources();
        int hPadding = res.getDimensionPixelSize(R.dimen.kg_widget_pager_horizontal_padding);
        int topPadding = res.getDimensionPixelSize(R.dimen.kg_widget_pager_top_padding);
        int bottomPadding = res.getDimensionPixelSize(R.dimen.kg_widget_pager_bottom_padding);
        setPadding(hPadding, topPadding, hPadding, bottomPadding);
        mGradientColor = res.getColor(com.android.internal.R.color.kg_widget_pager_gradient);
        mGradientPaint.setXfermode(sAddBlendMode);
    }

    public void setDisableUserInteraction(boolean disabled) {
        mDisableInteraction = disabled;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (!mDisableInteraction) {
            mPowerManager.userActivity(SystemClock.uptimeMillis(), false);
            return super.onInterceptTouchEvent(ev);
        }
        return true;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        drawGradientOverlay(canvas);

    }

    private void drawGradientOverlay(Canvas c) {
        mGradientPaint.setShader(mForegroundGradient);
        mGradientPaint.setAlpha(mForegroundAlpha);
        c.drawRect(mForegroundRect, mGradientPaint);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mForegroundRect.set(getPaddingLeft(), getPaddingTop(),
                w - getPaddingRight(), h - getPaddingBottom());
        float x0 = mLeftToRight ? 0 : mForegroundRect.width();
        float x1 = mLeftToRight ? mForegroundRect.width(): 0;
        mLeftToRightGradient = new LinearGradient(x0, 0f, x1, 0f,
                mGradientColor, 0, Shader.TileMode.CLAMP);
        mRightToLeftGradient = new LinearGradient(x1, 0f, x0, 0f,
                mGradientColor, 0, Shader.TileMode.CLAMP);
    }

    void setOverScrollAmount(float r, boolean left) {
        if (Float.compare(mOverScrollAmount, r) != 0) {
            mOverScrollAmount = r;
            mForegroundGradient = left ? mLeftToRightGradient : mRightToLeftGradient;
            mForegroundAlpha = (int) Math.round((0.85f * r * 255));
            invalidate();
        }
    }
}

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

import android.appwidget.AppWidgetHostView;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
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
    private CheckLongPressHelper mLongPressHelper;

    private float mBackgroundAlpha;
    private float mBackgroundAlphaMultiplier = 1.0f;
    private Drawable mBackgroundDrawable;
    private Rect mBackgroundRect = new Rect();

    public KeyguardWidgetFrame(Context context) {
        this(context, null, 0);
    }

    public KeyguardWidgetFrame(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyguardWidgetFrame(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        mLongPressHelper = new CheckLongPressHelper(this);

        Resources res = context.getResources();
        // TODO: this padding should really correspond to the padding embedded in the background
        // drawable (ie. outlines).
        int padding = (int) (res.getDisplayMetrics().density * 8);
        setPadding(padding, padding, padding, padding);

        mBackgroundDrawable = res.getDrawable(R.drawable.security_frame);
        mGradientColor = res.getColor(com.android.internal.R.color.kg_widget_pager_gradient);
        mGradientPaint.setXfermode(sAddBlendMode);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // Watch for longpress events at this level to make sure
        // users can always pick up this widget
        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mLongPressHelper.postCheckForLongPress(ev);
                break;
            case MotionEvent.ACTION_MOVE:
                mLongPressHelper.onMove(ev);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mLongPressHelper.cancelLongPress();
                break;
        }

        // Otherwise continue letting touch events fall through to children
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        // Watch for longpress events at this level to make sure
        // users can always pick up this widget
        switch (ev.getAction()) {
            case MotionEvent.ACTION_MOVE:
                mLongPressHelper.onMove(ev);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mLongPressHelper.cancelLongPress();
                break;
        }

        // We return true here to ensure that we will get cancel / up signal
        // even if none of our children have requested touch.
        return true;
    }

    @Override
    public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        super.requestDisallowInterceptTouchEvent(disallowIntercept);
        cancelLongPress();
    }

    @Override
    public void cancelLongPress() {
        super.cancelLongPress();
        mLongPressHelper.cancelLongPress();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        drawBg(canvas);
        super.dispatchDraw(canvas);
        drawGradientOverlay(canvas);
    }

    /**
     * Because this view has fading outlines, it is essential that we enable hardware
     * layers on the content (child) so that updating the alpha of the outlines doesn't
     * result in the content layer being recreated.
     */
    public void enableHardwareLayersForContent() {
        View widget = getContent();
        if (widget != null) {
            widget.setLayerType(LAYER_TYPE_HARDWARE, null);
        }
    }

    /**
     * Because this view has fading outlines, it is essential that we enable hardware
     * layers on the content (child) so that updating the alpha of the outlines doesn't
     * result in the content layer being recreated.
     */
    public void disableHardwareLayersForContent() {
        View widget = getContent();
        if (widget != null) {
            widget.setLayerType(LAYER_TYPE_NONE, null);
        }
    }

    public View getContent() {
        return getChildAt(0);
    }

    public int getContentAppWidgetId() {
        View content = getContent();
        if (content instanceof AppWidgetHostView) {
            return ((AppWidgetHostView) content).getAppWidgetId();
        } else {
            return ((KeyguardStatusView) content).getAppWidgetId();
        }
    }

    private void drawGradientOverlay(Canvas c) {
        mGradientPaint.setShader(mForegroundGradient);
        mGradientPaint.setAlpha(mForegroundAlpha);
        c.drawRect(mForegroundRect, mGradientPaint);
    }

    protected void drawBg(Canvas canvas) {
        if (mBackgroundAlpha > 0.0f) {
            Drawable bg = mBackgroundDrawable;

            bg.setAlpha((int) (mBackgroundAlpha * mBackgroundAlphaMultiplier * 255));
            bg.setBounds(mBackgroundRect);
            bg.draw(canvas);
        }
    }

    public float getBackgroundAlpha() {
        return mBackgroundAlpha;
    }

    public void setBackgroundAlphaMultiplier(float multiplier) {
        if (mBackgroundAlphaMultiplier != multiplier) {
            mBackgroundAlphaMultiplier = multiplier;
            invalidate();
        }
    }

    public float getBackgroundAlphaMultiplier() {
        return mBackgroundAlphaMultiplier;
    }

    public void setBackgroundAlpha(float alpha) {
        if (mBackgroundAlpha != alpha) {
            mBackgroundAlpha = alpha;
            invalidate();
        }
    }

    public void setContentAlpha(float alpha) {
        View content = getContent();
        if (content != null) {
            content.setAlpha(alpha);
        }
    }

    /**
     * Depending on whether the security is up, the widget size needs to change
     * 
     * @param height The height of the widget, -1 for full height
     */
    public void setWidgetHeight(int height) {
        boolean needLayout = false;
        View widget = getContent();
        if (widget != null) {
            LayoutParams lp = (LayoutParams) widget.getLayoutParams();
            if (lp.height != height) {
                needLayout = true;
                lp.height = height;
            }
        }
        if (needLayout) {
            requestLayout();
        }
    }

    /**
     * Set the top location of the challenge.
     *
     * @param top The top of the challenge, in _local_ coordinates, or -1 to indicate the challenge
     *              is down.
     */
    public void setChallengeTop(int top) {
        // The widget starts below the padding, and extends to the top of the challengs.
        int widgetHeight = top - getPaddingTop();
        setWidgetHeight(widgetHeight);
    }

    public void resetSize() {
        setWidgetHeight(LayoutParams.MATCH_PARENT);
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
        mBackgroundRect.set(0, 0, w, h);
    }

    void setOverScrollAmount(float r, boolean left) {
        if (Float.compare(mOverScrollAmount, r) != 0) {
            mOverScrollAmount = r;
            mForegroundGradient = left ? mLeftToRightGradient : mRightToLeftGradient;
            mForegroundAlpha = (int) Math.round((0.85f * r * 255));
            invalidate();
        }
    }

    public void onActive(boolean isActive) {
        // hook for subclasses
    }
}

/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.systemui.bubbles;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.PathParser;
import android.widget.ImageView;

import com.android.internal.graphics.ColorUtils;
import com.android.launcher3.icons.BitmapInfo;
import com.android.launcher3.icons.DotRenderer;
import com.android.systemui.Interpolators;
import com.android.systemui.R;

/**
 * View that displays an adaptive icon with an app-badge and a dot.
 *
 * Dot = a small colored circle that indicates whether this bubble has an unread update.
 * Badge = the icon associated with the app that created this bubble, this will show work profile
 * badge if appropriate.
 */
public class BadgedImageView extends ImageView {

    /** Same value as Launcher3 dot code */
    private static final float WHITE_SCRIM_ALPHA = 0.54f;
    /** Same as value in Launcher3 IconShape */
    private static final int DEFAULT_PATH_SIZE = 100;

    static final int DOT_STATE_DEFAULT = 0;
    static final int DOT_STATE_SUPPRESSED_FOR_FLYOUT = 1;
    static final int DOT_STATE_ANIMATING = 2;

    // Flyout gets shown before the dot
    private int mCurrentDotState = DOT_STATE_SUPPRESSED_FOR_FLYOUT;

    private Bubble mBubble;
    private BubbleIconFactory mBubbleIconFactory;

    private int mIconBitmapSize;
    private DotRenderer mDotRenderer;
    private DotRenderer.DrawParams mDrawParams;
    private boolean mOnLeft;

    private int mDotColor;
    private float mDotScale = 0f;
    private boolean mDotDrawn;

    private Rect mTempBounds = new Rect();

    public BadgedImageView(Context context) {
        this(context, null);
    }

    public BadgedImageView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BadgedImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public BadgedImageView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mIconBitmapSize = getResources().getDimensionPixelSize(R.dimen.bubble_icon_bitmap_size);
        mDrawParams = new DotRenderer.DrawParams();

        Path iconPath = PathParser.createPathFromPathData(
                getResources().getString(com.android.internal.R.string.config_icon_mask));
        mDotRenderer = new DotRenderer(mIconBitmapSize, iconPath, DEFAULT_PATH_SIZE);
    }

    @Override
    public void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (isDotHidden()) {
            mDotDrawn = false;
            return;
        }
        mDotDrawn = mDotScale > 0.1f;
        getDrawingRect(mTempBounds);

        mDrawParams.color = mDotColor;
        mDrawParams.iconBounds = mTempBounds;
        mDrawParams.leftAlign = mOnLeft;
        mDrawParams.scale = mDotScale;

        mDotRenderer.draw(canvas, mDrawParams);
    }

    /**
     * Sets the dot state, does not animate changes.
     */
    void setDotState(int state) {
        mCurrentDotState = state;
        if (state == DOT_STATE_SUPPRESSED_FOR_FLYOUT || state == DOT_STATE_DEFAULT) {
            mDotScale = mBubble.showDot() ? 1f : 0f;
            invalidate();
        }
    }

    /**
     * Whether the dot should be hidden based on current dot state.
     */
    private boolean isDotHidden() {
        return (mCurrentDotState == DOT_STATE_DEFAULT && !mBubble.showDot())
                || mCurrentDotState == DOT_STATE_SUPPRESSED_FOR_FLYOUT;
    }

    /**
     * Set whether the dot should appear on left or right side of the view.
     */
    void setDotOnLeft(boolean onLeft) {
        mOnLeft = onLeft;
        invalidate();
    }

    /**
     * The colour to use for the dot.
     */
    void setDotColor(int color) {
        mDotColor = ColorUtils.setAlphaComponent(color, 255 /* alpha */);
        invalidate();
    }

    /**
     * @param iconPath The new icon path to use when calculating dot position.
     */
    void drawDot(Path iconPath) {
        mDotRenderer = new DotRenderer(mIconBitmapSize, iconPath, DEFAULT_PATH_SIZE);
        invalidate();
    }

    /**
     * How big the dot should be, fraction from 0 to 1.
     */
    void setDotScale(float fraction) {
        mDotScale = fraction;
        invalidate();
    }

    /**
     * Whether decorations (badges or dots) are on the left.
     */
    boolean getDotOnLeft() {
        return mOnLeft;
    }

    /**
     * Return dot position relative to bubble view container bounds.
     */
    float[] getDotCenter() {
        float[] dotPosition;
        if (mOnLeft) {
            dotPosition = mDotRenderer.getLeftDotPosition();
        } else {
            dotPosition = mDotRenderer.getRightDotPosition();
        }
        getDrawingRect(mTempBounds);
        float dotCenterX = mTempBounds.width() * dotPosition[0];
        float dotCenterY = mTempBounds.height() * dotPosition[1];
        return new float[]{dotCenterX, dotCenterY};
    }

    /**
     * Populates this view with a bubble.
     * <p>
     * This should only be called when a new bubble is being set on the view, updates to the
     * current bubble should use {@link #update(Bubble)}.
     *
     * @param bubble the bubble to display in this view.
     */
    public void setBubble(Bubble bubble) {
        mBubble = bubble;
    }

    /**
     * @param factory Factory for creating normalized bubble icons.
     */
    public void setBubbleIconFactory(BubbleIconFactory factory) {
        mBubbleIconFactory = factory;
    }

    /**
     * The key for the {@link Bubble} associated with this view, if one exists.
     */
    @Nullable
    public String getKey() {
        return (mBubble != null) ? mBubble.getKey() : null;
    }

    /**
     * Updates the UI based on the bubble, updates badge and animates messages as needed.
     */
    public void update(Bubble bubble) {
        mBubble = bubble;
        setDotState(DOT_STATE_SUPPRESSED_FOR_FLYOUT);
        updateViews();
    }

    int getDotColor() {
        return mDotColor;
    }

    /** Sets the position of the 'new' dot, animating it out and back in if requested. */
    void setDotPosition(boolean onLeft, boolean animate) {
        if (animate && onLeft != getDotOnLeft() && !isDotHidden()) {
            animateDot(false /* showDot */, () -> {
                setDotOnLeft(onLeft);
                animateDot(true /* showDot */, null);
            });
        } else {
            setDotOnLeft(onLeft);
        }
    }

    boolean getDotPositionOnLeft() {
        return getDotOnLeft();
    }

    /** Changes the dot's visibility to match the bubble view's state. */
    void animateDot() {
        if (mCurrentDotState == DOT_STATE_DEFAULT) {
            animateDot(mBubble.showDot(), null);
        }
    }

    /**
     * Animates the dot to show or hide.
     */
    private void animateDot(boolean showDot, Runnable after) {
        if (mDotDrawn == showDot) {
            // State is consistent, do nothing.
            return;
        }

        setDotState(DOT_STATE_ANIMATING);

        // Do NOT wait until after animation ends to setShowDot
        // to avoid overriding more recent showDot states.
        clearAnimation();
        animate().setDuration(200)
                .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                .setUpdateListener((valueAnimator) -> {
                    float fraction = valueAnimator.getAnimatedFraction();
                    fraction = showDot ? fraction : 1f - fraction;
                    setDotScale(fraction);
                }).withEndAction(() -> {
                    setDotScale(showDot ? 1f : 0f);
                    setDotState(DOT_STATE_DEFAULT);
                    if (after != null) {
                        after.run();
                    }
                }).start();
    }

    void updateViews() {
        if (mBubble == null || mBubbleIconFactory == null) {
            return;
        }

        Drawable bubbleDrawable = mBubbleIconFactory.getBubbleDrawable(mBubble, mContext);
        BitmapInfo badgeBitmapInfo = mBubbleIconFactory.getBadgeBitmap(mBubble);
        BitmapInfo bubbleBitmapInfo = mBubbleIconFactory.getBubbleBitmap(bubbleDrawable,
                badgeBitmapInfo);
        setImageBitmap(bubbleBitmapInfo.icon);

        // Update badge.
        mDotColor = ColorUtils.blendARGB(badgeBitmapInfo.color, Color.WHITE, WHITE_SCRIM_ALPHA);
        setDotColor(mDotColor);

        // Update dot.
        Path iconPath = PathParser.createPathFromPathData(
                getResources().getString(com.android.internal.R.string.config_icon_mask));
        Matrix matrix = new Matrix();
        float scale = mBubbleIconFactory.getNormalizer().getScale(bubbleDrawable,
                null /* outBounds */, null /* path */, null /* outMaskShape */);
        float radius = BadgedImageView.DEFAULT_PATH_SIZE / 2f;
        matrix.setScale(scale /* x scale */, scale /* y scale */, radius /* pivot x */,
                radius /* pivot y */);
        iconPath.transform(matrix);
        drawDot(iconPath);

        animateDot();
    }
}

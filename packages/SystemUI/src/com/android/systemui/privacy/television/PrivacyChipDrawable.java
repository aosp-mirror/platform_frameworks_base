/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.privacy.television;

import android.animation.Animator;
import android.animation.AnimatorInflater;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.Log;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.systemui.R;

/**
 * Drawable that can go from being the background of the privacy icons to a small dot.
 * The icons are not included.
 */
public class PrivacyChipDrawable extends Drawable {

    private static final String TAG = PrivacyChipDrawable.class.getSimpleName();
    private static final boolean DEBUG = false;

    private float mWidth;
    private float mHeight;
    private float mMarginEnd;
    private float mRadius;
    private int mDotAlpha;
    private int mBgAlpha;

    private float mTargetWidth;
    private final int mMinWidth;
    private final int mIconWidth;
    private final int mIconPadding;
    private final int mBgWidth;
    private final int mBgHeight;
    private final int mBgRadius;
    private final int mDotSize;

    private final AnimatorSet mFadeIn;
    private final AnimatorSet mFadeOut;
    private final AnimatorSet mCollapse;
    private final AnimatorSet mExpand;
    private Animator mWidthAnimator;

    private final Paint mChipPaint;
    private final Paint mBgPaint;

    private boolean mIsRtl;

    private boolean mIsExpanded = true;

    private PrivacyChipDrawableListener mListener;

    interface PrivacyChipDrawableListener {
        void onFadeOutFinished();
    }

    public PrivacyChipDrawable(Context context) {
        mChipPaint = new Paint();
        mChipPaint.setStyle(Paint.Style.FILL);
        mChipPaint.setColor(context.getColor(R.color.privacy_circle));
        mChipPaint.setAlpha(mDotAlpha);
        mChipPaint.setFlags(Paint.ANTI_ALIAS_FLAG);

        mBgPaint = new Paint();
        mBgPaint.setStyle(Paint.Style.FILL);
        mBgPaint.setColor(context.getColor(R.color.privacy_chip_dot_bg_tint));
        mBgPaint.setAlpha(mBgAlpha);
        mBgPaint.setFlags(Paint.ANTI_ALIAS_FLAG);

        mBgWidth = context.getResources().getDimensionPixelSize(R.dimen.privacy_chip_dot_bg_width);
        mBgHeight = context.getResources().getDimensionPixelSize(
                R.dimen.privacy_chip_dot_bg_height);
        mBgRadius = context.getResources().getDimensionPixelSize(
                R.dimen.privacy_chip_dot_bg_radius);

        mMinWidth = context.getResources().getDimensionPixelSize(R.dimen.privacy_chip_min_width);
        mIconWidth = context.getResources().getDimensionPixelSize(R.dimen.privacy_chip_icon_size);
        mIconPadding = context.getResources().getDimensionPixelSize(
                R.dimen.privacy_chip_icon_margin_in_between);
        mDotSize = context.getResources().getDimensionPixelSize(R.dimen.privacy_chip_dot_size);

        mWidth = mMinWidth;
        mHeight = context.getResources().getDimensionPixelSize(R.dimen.privacy_chip_height);
        mRadius = context.getResources().getDimensionPixelSize(R.dimen.privacy_chip_radius);

        mExpand = (AnimatorSet) AnimatorInflater.loadAnimator(context,
                R.anim.tv_privacy_chip_expand);
        mExpand.setTarget(this);

        mCollapse = (AnimatorSet) AnimatorInflater.loadAnimator(context,
                R.anim.tv_privacy_chip_collapse);
        mCollapse.setTarget(this);

        mFadeIn = (AnimatorSet) AnimatorInflater.loadAnimator(context,
                R.anim.tv_privacy_chip_fade_in);
        mFadeIn.setTarget(this);

        mFadeOut = (AnimatorSet) AnimatorInflater.loadAnimator(context,
                R.anim.tv_privacy_chip_fade_out);
        mFadeOut.setTarget(this);
        mFadeOut.addListener(new Animator.AnimatorListener() {
            private boolean mCancelled;

            @Override
            public void onAnimationStart(Animator animation) {
                mCancelled = false;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (!mCancelled && mListener != null) {
                    if (DEBUG) Log.d(TAG, "Fade-out complete");
                    mListener.onFadeOutFinished();
                }
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationRepeat(Animator animation) {
                // no-op
            }
        });
    }

    /**
     * Pass null to remove listener.
     */
    public void setListener(@Nullable PrivacyChipDrawableListener listener) {
        this.mListener = listener;
    }

    /**
     * Call once the view that is showing the drawable is visible to start fading the chip in.
     */
    public void startInitialFadeIn() {
        if (DEBUG) Log.d(TAG, "initial fade-in");
        mFadeIn.start();
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();

        int centerVertical = (bounds.bottom - bounds.top) / 2;
        // Dot background
        RectF bgBounds = new RectF(
                mIsRtl ? bounds.left : bounds.right - mBgWidth,
                centerVertical - mBgHeight / 2f,
                mIsRtl ? bounds.left + mBgWidth : bounds.right,
                centerVertical + mBgHeight / 2f);
        if (DEBUG) Log.v(TAG, "bg: " + bgBounds.toShortString());
        canvas.drawRoundRect(bgBounds, mBgRadius, mBgRadius, mBgPaint);

        // Icon background / dot
        RectF greenBounds = new RectF(
                mIsRtl ? bounds.left + mMarginEnd : bounds.right - mWidth - mMarginEnd,
                centerVertical - mHeight / 2,
                mIsRtl ? bounds.left + mWidth + mMarginEnd : bounds.right - mMarginEnd,
                centerVertical + mHeight / 2);
        if (DEBUG) Log.v(TAG, "green: " + greenBounds.toShortString());
        canvas.drawRoundRect(greenBounds, mRadius, mRadius, mChipPaint);
    }

    private void animateToNewTargetWidth(float width) {
        if (DEBUG) Log.d(TAG, "new target width: " + width);
        if (width != mTargetWidth) {
            mTargetWidth = width;
            Animator newWidthAnimator = ObjectAnimator.ofFloat(this, "width", mTargetWidth);
            newWidthAnimator.start();
            if (mWidthAnimator != null) {
                mWidthAnimator.cancel();
            }
            mWidthAnimator = newWidthAnimator;
        }
    }

    private void expand() {
        if (DEBUG) Log.d(TAG, "expanding");
        if (mIsExpanded) {
            return;
        }
        mIsExpanded = true;

        mExpand.start();
        mCollapse.cancel();
    }

    /**
     * Starts the animation to a dot.
     */
    public void collapse() {
        if (DEBUG) Log.d(TAG, "collapsing");
        if (!mIsExpanded) {
            return;
        }
        mIsExpanded = false;

        animateToNewTargetWidth(mDotSize);
        mCollapse.start();
        mExpand.cancel();
    }

    /**
     * Fades out the view if 0 icons are to be shown, expands the chip if it has been collapsed and
     * makes the width of the chip adjust to the amount of icons to be shown.
     * Should not be called when only the order of the icons was changed as the chip will expand
     * again without there being any real update.
     *
     * @param iconCount Can be 0 to fade out the chip.
     */
    public void updateIcons(int iconCount) {
        if (DEBUG) Log.d(TAG, "updating icons: " + iconCount);

        // calculate chip size and use it for end value of animation that is specified in code,
        // not xml
        if (iconCount == 0) {
            // fade out if there are no icons
            mFadeOut.start();

            mWidthAnimator.cancel();
            mFadeIn.cancel();
            mExpand.cancel();
            mCollapse.cancel();
            return;
        }

        mFadeOut.cancel();
        expand();
        animateToNewTargetWidth(mMinWidth + (iconCount - 1) * (mIconWidth + mIconPadding));
    }

    @Override
    public void setAlpha(int alpha) {
        setDotAlpha(alpha);
        setBgAlpha(alpha);
    }

    @Override
    public int getAlpha() {
        return mDotAlpha;
    }

    /**
     * Set alpha value the green part of the chip.
     */
    @Keep
    public void setDotAlpha(int alpha) {
        if (DEBUG) Log.v(TAG, "dot alpha updated to: " + alpha);
        mDotAlpha = alpha;
        mChipPaint.setAlpha(alpha);
    }

    @Keep
    public int getDotAlpha() {
        return mDotAlpha;
    }

    /**
     * Set alpha value of the background of the chip.
     */
    @Keep
    public void setBgAlpha(int alpha) {
        if (DEBUG) Log.v(TAG, "bg alpha updated to: " + alpha);
        mBgAlpha = alpha;
        mBgPaint.setAlpha(alpha);
    }

    @Keep
    public int getBgAlpha() {
        return mBgAlpha;
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        // no-op
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    /**
     * The radius of the green part of the chip, not the background.
     */
    @Keep
    public void setRadius(float radius) {
        mRadius = radius;
        invalidateSelf();
    }

    /**
     * @return The radius of the green part of the chip, not the background.
     */
    @Keep
    public float getRadius() {
        return mRadius;
    }

    /**
     * Height of the green part of the chip, not including the background.
     */
    @Keep
    public void setHeight(float height) {
        mHeight = height;
        invalidateSelf();
    }

    /**
     * @return Height of the green part of the chip, not including the background.
     */
    @Keep
    public float getHeight() {
        return mHeight;
    }

    /**
     * Width of the green part of the chip, not including the background.
     */
    @Keep
    public void setWidth(float width) {
        mWidth = width;
        invalidateSelf();
    }

    /**
     * @return Width of the green part of the chip, not including the background.
     */
    @Keep
    public float getWidth() {
        return mWidth;
    }

    /**
     * Margin at the end of the green part of the chip, so that it will be placed in the middle of
     * the rounded rectangle in the background.
     */
    @Keep
    public void setMarginEnd(float marginEnd) {
        mMarginEnd = marginEnd;
        invalidateSelf();
    }

    /**
     * @return Margin at the end of the green part of the chip, so that it will be placed in the
     * middle of the rounded rectangle in the background.
     */
    @Keep
    public float getMarginEnd() {
        return mMarginEnd;
    }

    /**
     * Sets the layout direction.
     */
    public void setRtl(boolean isRtl) {
        mIsRtl = isRtl;
    }

}

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
 * limitations under the License
 */

package com.android.systemui.statusbar;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.support.v4.graphics.ColorUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Interpolator;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.colorextraction.ColorExtractor;
import com.android.internal.colorextraction.drawable.GradientDrawable;
import com.android.systemui.Dependency;
import com.android.systemui.statusbar.policy.ConfigurationController;

/**
 * A view which can draw a scrim
 */
public class ScrimView extends View implements ConfigurationController.ConfigurationListener {
    private static final String TAG = "ScrimView";
    private final ColorExtractor.GradientColors mColors;
    private boolean mDrawAsSrc;
    private float mViewAlpha = 1.0f;
    private ValueAnimator mAlphaAnimator;
    private Rect mExcludedRect = new Rect();
    private boolean mHasExcludedArea;
    private Drawable mDrawable;
    private PorterDuffColorFilter mColorFilter;
    private int mTintColor;
    private ValueAnimator.AnimatorUpdateListener mAlphaUpdateListener = animation -> {
        if (mDrawable == null) {
            Log.w(TAG, "Trying to animate null drawable");
            return;
        }
        mDrawable.setAlpha((int) (255 * (float) animation.getAnimatedValue()));
    };
    private AnimatorListenerAdapter mClearAnimatorListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            mAlphaAnimator = null;
        }
    };
    private Runnable mChangeRunnable;

    public ScrimView(Context context) {
        this(context, null);
    }

    public ScrimView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ScrimView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public ScrimView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        mDrawable = new GradientDrawable(context);
        mDrawable.setCallback(this);
        mColors = new ColorExtractor.GradientColors();
        updateScreenSize();
        updateColorWithTint(false);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        // We need to know about configuration changes to update the gradient size
        // since it's independent from view bounds.
        ConfigurationController config = Dependency.get(ConfigurationController.class);
        config.addCallback(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        ConfigurationController config = Dependency.get(ConfigurationController.class);
        config.removeCallback(this);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mDrawAsSrc || mDrawable.getAlpha() > 0) {
            if (!mHasExcludedArea) {
                mDrawable.draw(canvas);
            } else {
                if (mExcludedRect.top > 0) {
                    canvas.save();
                    canvas.clipRect(0, 0, getWidth(), mExcludedRect.top);
                    mDrawable.draw(canvas);
                    canvas.restore();
                }
                if (mExcludedRect.left > 0) {
                    canvas.save();
                    canvas.clipRect(0, mExcludedRect.top, mExcludedRect.left,
                            mExcludedRect.bottom);
                    mDrawable.draw(canvas);
                    canvas.restore();
                }
                if (mExcludedRect.right < getWidth()) {
                    canvas.save();
                    canvas.clipRect(mExcludedRect.right, mExcludedRect.top, getWidth(),
                            mExcludedRect.bottom);
                    mDrawable.draw(canvas);
                    canvas.restore();
                }
                if (mExcludedRect.bottom < getHeight()) {
                    canvas.save();
                    canvas.clipRect(0, mExcludedRect.bottom, getWidth(), getHeight());
                    mDrawable.draw(canvas);
                    canvas.restore();
                }
            }
        }
    }

    public void setDrawable(Drawable drawable) {
        mDrawable = drawable;
        mDrawable.setCallback(this);
        mDrawable.setBounds(getLeft(), getTop(), getRight(), getBottom());
        mDrawable.setAlpha((int) (255 * mViewAlpha));
        setDrawAsSrc(mDrawAsSrc);
        updateScreenSize();
        invalidate();
    }

    @Override
    public void invalidateDrawable(@NonNull Drawable drawable) {
        super.invalidateDrawable(drawable);
        if (drawable == mDrawable) {
            invalidate();
        }
    }

    public void setDrawAsSrc(boolean asSrc) {
        mDrawAsSrc = asSrc;
        PorterDuff.Mode mode = asSrc ? PorterDuff.Mode.SRC : PorterDuff.Mode.SRC_OVER;
        mDrawable.setXfermode(new PorterDuffXfermode(mode));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (changed) {
            mDrawable.setBounds(left, top, right, bottom);
            invalidate();
        }
    }

    public void setColors(@NonNull ColorExtractor.GradientColors colors) {
        setColors(colors, false);
    }

    public void setColors(@NonNull ColorExtractor.GradientColors colors, boolean animated) {
        if (colors == null) {
            throw new IllegalArgumentException("Colors cannot be null");
        }
        if (mColors.equals(colors)) {
            return;
        }
        mColors.set(colors);
        updateColorWithTint(animated);
    }

    @VisibleForTesting
    Drawable getDrawable() {
        return mDrawable;
    }

    public ColorExtractor.GradientColors getColors() {
        return mColors;
    }

    public void setTint(int color) {
        setTint(color, false);
    }

    public void setTint(int color, boolean animated) {
        if (mTintColor == color) {
            return;
        }
        mTintColor = color;
        updateColorWithTint(animated);
    }

    private void updateColorWithTint(boolean animated) {
        if (mDrawable instanceof GradientDrawable) {
            // Optimization to blend colors and avoid a color filter
            GradientDrawable drawable = (GradientDrawable) mDrawable;
            float tintAmount = Color.alpha(mTintColor) / 255f;
            int mainTinted = ColorUtils.blendARGB(mColors.getMainColor(), mTintColor,
                    tintAmount);
            int secondaryTinted = ColorUtils.blendARGB(mColors.getSecondaryColor(), mTintColor,
                    tintAmount);
            drawable.setColors(mainTinted, secondaryTinted, animated);
        } else {
            if (mColorFilter == null) {
                mColorFilter = new PorterDuffColorFilter(mTintColor, PorterDuff.Mode.SRC_OVER);
            } else {
                mColorFilter.setColor(mTintColor);
            }
            mDrawable.setColorFilter(Color.alpha(mTintColor) == 0 ? null : mColorFilter);
            mDrawable.invalidateSelf();
        }

        if (mChangeRunnable != null) {
            mChangeRunnable.run();
        }
    }

    public int getTint() {
        return mTintColor;
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    public void setViewAlpha(float alpha) {
        if (alpha != mViewAlpha) {
            mViewAlpha = alpha;

            if (mAlphaAnimator != null) {
                mAlphaAnimator.cancel();
            }

            mDrawable.setAlpha((int) (255 * alpha));
            if (mChangeRunnable != null) {
                mChangeRunnable.run();
            }
        }
    }

    public float getViewAlpha() {
        return mViewAlpha;
    }

    public void animateViewAlpha(float alpha, long durationOut, Interpolator interpolator) {
        if (mAlphaAnimator != null) {
            mAlphaAnimator.cancel();
        }
        mAlphaAnimator = ValueAnimator.ofFloat(getViewAlpha(), alpha);
        mAlphaAnimator.addUpdateListener(mAlphaUpdateListener);
        mAlphaAnimator.addListener(mClearAnimatorListener);
        mAlphaAnimator.setInterpolator(interpolator);
        mAlphaAnimator.setDuration(durationOut);
        mAlphaAnimator.start();
    }

    public void setExcludedArea(Rect area) {
        if (area == null) {
            mHasExcludedArea = false;
            invalidate();
            return;
        }

        int left = Math.max(area.left, 0);
        int top = Math.max(area.top, 0);
        int right = Math.min(area.right, getWidth());
        int bottom = Math.min(area.bottom, getHeight());
        mExcludedRect.set(left, top, right, bottom);
        mHasExcludedArea = left < right && top < bottom;
        invalidate();
    }

    public void setChangeRunnable(Runnable changeRunnable) {
        mChangeRunnable = changeRunnable;
    }

    @Override
    public void onConfigChanged(Configuration newConfig) {
        updateScreenSize();
    }

    private void updateScreenSize() {
        if (mDrawable instanceof GradientDrawable) {
            WindowManager wm = mContext.getSystemService(WindowManager.class);
            if (wm == null) {
                Log.w(TAG, "Can't resize gradient drawable to fit the screen");
                return;
            }
            Display display = wm.getDefaultDisplay();
            if (display != null) {
                Point size = new Point();
                display.getRealSize(size);
                ((GradientDrawable) mDrawable).setScreenSize(size.x, size.y);
            }
        }
    }
}

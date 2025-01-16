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

package com.android.systemui.scrim;

import static java.lang.Float.isNaN;

import android.annotation.NonNull;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuff.Mode;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.colorextraction.ColorExtractor;
import com.android.systemui.shade.TouchLogger;
import com.android.systemui.util.LargeScreenUtils;

import java.util.concurrent.Executor;

/**
 * A view which can draw a scrim.  This view maybe be used in multiple windows running on different
 * threads, but is controlled by {@link com.android.systemui.statusbar.phone.ScrimController} so we
 * need to be careful to synchronize when necessary.
 */
public class ScrimView extends View {

    private final Object mColorLock = new Object();

    @GuardedBy("mColorLock")
    private final ColorExtractor.GradientColors mColors;
    // Used only for returning the colors
    private final ColorExtractor.GradientColors mTmpColors = new ColorExtractor.GradientColors();
    private float mViewAlpha = 1.0f;
    private Drawable mDrawable;
    private PorterDuffColorFilter mColorFilter;
    private String mScrimName;
    private int mTintColor;
    private boolean mBlendWithMainColor = true;
    private Executor mExecutor;
    private Looper mExecutorLooper;
    @Nullable
    private Rect mDrawableBounds;

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

        setFocusable(false);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        mDrawable = new ScrimDrawable();
        mDrawable.setCallback(this);
        mColors = new ColorExtractor.GradientColors();
        mExecutorLooper = Looper.myLooper();
        mExecutor = Runnable::run;
        executeOnExecutor(() -> {
            updateColorWithTint(false);
        });
    }

    /**
     * Needed for WM Shell, which has its own thread structure.
     */
    public void setExecutor(Executor executor, Looper looper) {
        mExecutor = executor;
        mExecutorLooper = looper;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mDrawable.getAlpha() > 0) {
            Resources res = getResources();
            // Scrim behind notification shade has sharp (not rounded) corners on large screens
            // which scrim itself cannot know, so we set it here.
            if (mDrawable instanceof ScrimDrawable) {
                ((ScrimDrawable) mDrawable).setShouldUseLargeScreenSize(
                        LargeScreenUtils.shouldUseLargeScreenShadeHeader(res));
            }
            mDrawable.draw(canvas);
        }
    }

    @VisibleForTesting
    void setDrawable(Drawable drawable) {
        executeOnExecutor(() -> {
            mDrawable = drawable;
            mDrawable.setCallback(this);
            mDrawable.setBounds(getLeft(), getTop(), getRight(), getBottom());
            mDrawable.setAlpha((int) (255 * mViewAlpha));
            invalidate();
        });
    }

    @Override
    public void invalidateDrawable(@NonNull Drawable drawable) {
        super.invalidateDrawable(drawable);
        if (drawable == mDrawable) {
            invalidate();
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (mDrawableBounds != null) {
            mDrawable.setBounds(mDrawableBounds);
        } else if (changed) {
            mDrawable.setBounds(left, top, right, bottom);
            invalidate();
        }
    }

    @Override
    public void setClickable(boolean clickable) {
        executeOnExecutor(() -> {
            super.setClickable(clickable);
        });
    }

    /**
     * Sets the color of the scrim, without animating them.
     */
    public void setColors(@NonNull ColorExtractor.GradientColors colors) {
        setColors(colors, false);
    }

    /**
     * Sets the scrim colors, optionally animating them.
     * @param colors The colors.
     * @param animated If we should animate the transition.
     */
    public void setColors(@NonNull ColorExtractor.GradientColors colors, boolean animated) {
        if (colors == null) {
            throw new IllegalArgumentException("Colors cannot be null");
        }
        executeOnExecutor(() -> {
            synchronized (mColorLock) {
                if (mColors.equals(colors)) {
                    return;
                }
                mColors.set(colors);
            }
            updateColorWithTint(animated);
        });
    }

    /**
     * Set corner radius of the bottom edge of the Notification scrim.
     */
    public void setBottomEdgeRadius(float radius) {
        if (mDrawable instanceof ScrimDrawable) {
            ((ScrimDrawable) mDrawable).setBottomEdgeRadius(radius);
        }
    }

    @VisibleForTesting
    Drawable getDrawable() {
        return mDrawable;
    }

    /**
     * Returns current scrim colors.
     */
    public ColorExtractor.GradientColors getColors() {
        synchronized (mColorLock) {
            mTmpColors.set(mColors);
        }
        return mTmpColors;
    }

    /**
     * Applies tint to this view, without animations.
     */
    public void setTint(int color) {
        setTint(color, false);
    }

    /**
     * The call to {@link #setTint} will blend with the main color, with the amount
     * determined by the alpha of the tint. Set to false to avoid this blend.
     */
    public void setBlendWithMainColor(boolean blend) {
        mBlendWithMainColor = blend;
    }

    /** @return true if blending tint color with main color */
    public boolean shouldBlendWithMainColor() {
        return mBlendWithMainColor;
    }

    /**
     * Tints this view, optionally animating it.
     * @param color The color.
     * @param animated If we should animate.
     */
    public void setTint(int color, boolean animated) {
        executeOnExecutor(() -> {
            if (mTintColor == color) {
                return;
            }
            mTintColor = color;
            updateColorWithTint(animated);
        });
    }

    private void updateColorWithTint(boolean animated) {
        if (mDrawable instanceof ScrimDrawable) {
            // Optimization to blend colors and avoid a color filter
            ScrimDrawable drawable = (ScrimDrawable) mDrawable;
            float tintAmount = Color.alpha(mTintColor) / 255f;

            int mainTinted = mTintColor;
            if (mBlendWithMainColor) {
                mainTinted = ColorUtils.blendARGB(mColors.getMainColor(), mTintColor, tintAmount);
            }
            drawable.setColor(mainTinted, animated);
        } else {
            boolean hasAlpha = Color.alpha(mTintColor) != 0;
            if (hasAlpha) {
                PorterDuff.Mode targetMode = mColorFilter == null
                        ? Mode.SRC_OVER : mColorFilter.getMode();
                if (mColorFilter == null || mColorFilter.getColor() != mTintColor) {
                    mColorFilter = new PorterDuffColorFilter(mTintColor, targetMode);
                }
            } else {
                mColorFilter = null;
            }

            mDrawable.setColorFilter(mColorFilter);
            mDrawable.invalidateSelf();
        }

    }

    public int getTint() {
        return mTintColor;
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    /**
     * It might look counterintuitive to have another method to set the alpha instead of
     * only using {@link #setAlpha(float)}. In this case we're in a hardware layer
     * optimizing blend modes, so it makes sense.
     *
     * @param alpha Gradient alpha from 0 to 1.
     */
    public void setViewAlpha(float alpha) {
        if (isNaN(alpha)) {
            throw new IllegalArgumentException("alpha cannot be NaN: " + alpha);
        }
        executeOnExecutor(() -> {
            if (alpha != mViewAlpha) {
                mViewAlpha = alpha;

                mDrawable.setAlpha((int) (255 * alpha));
            }
        });
    }

    public float getViewAlpha() {
        return mViewAlpha;
    }

    @Override
    protected boolean canReceivePointerEvents() {
        return false;
    }

    private void executeOnExecutor(Runnable r) {
        if (mExecutor == null || Looper.myLooper() == mExecutorLooper) {
            r.run();
        } else {
            mExecutor.execute(r);
        }
    }

    /**
     * Make bottom edge concave so overlap between layers is not visible for alphas between 0 and 1
     */
    public void enableBottomEdgeConcave(boolean clipScrim) {
        if (mDrawable instanceof ScrimDrawable) {
            ((ScrimDrawable) mDrawable).setBottomEdgeConcave(clipScrim);
        }
    }

    public void setScrimName(String scrimName) {
        mScrimName = scrimName;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        return TouchLogger.logDispatchTouch(mScrimName, ev, super.dispatchTouchEvent(ev));
    }

    /**
     * The position of the bottom of the scrim, used for clipping.
     * @see #enableBottomEdgeConcave(boolean)
     */
    public void setBottomEdgePosition(int y) {
        if (mDrawable instanceof ScrimDrawable) {
            ((ScrimDrawable) mDrawable).setBottomEdgePosition(y);
        }
    }

    /**
     * Enable view to have rounded corners.
     */
    public void enableRoundedCorners(boolean enabled) {
        if (mDrawable instanceof ScrimDrawable) {
            ((ScrimDrawable) mDrawable).setRoundedCornersEnabled(enabled);
        }
    }

    /**
     * Set bounds for the view, all coordinates are absolute
     */
    public void setDrawableBounds(float left, float top, float right, float bottom) {
        if (mDrawableBounds == null) {
            mDrawableBounds = new Rect();
        }
        mDrawableBounds.set((int) left, (int) top, (int) right, (int) bottom);
        mDrawable.setBounds(mDrawableBounds);
    }

    /**
     * Corner radius of both concave or convex corners.
     * @see #enableRoundedCorners(boolean)
     * @see #enableBottomEdgeConcave(boolean)
     */
    public void setCornerRadius(int radius) {
        if (mDrawable instanceof ScrimDrawable) {
            ((ScrimDrawable) mDrawable).setRoundedCorners(radius);
        }
    }
}

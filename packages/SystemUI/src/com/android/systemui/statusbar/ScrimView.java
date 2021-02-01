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

import static java.lang.Float.isNaN;

import android.annotation.NonNull;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuff.Mode;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.colorextraction.ColorExtractor;
import com.android.internal.colorextraction.drawable.ScrimDrawable;

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
    private int mTintColor;
    private Runnable mChangeRunnable;
    private Executor mChangeRunnableExecutor;
    private Executor mExecutor;
    private Looper mExecutorLooper;

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

        mDrawable = new ScrimDrawable();
        mDrawable.setCallback(this);
        mColors = new ColorExtractor.GradientColors();
        mExecutorLooper = Looper.myLooper();
        mExecutor = Runnable::run;
        executeOnExecutor(() -> {
            updateColorWithTint(false);
        });
    }

    public void setExecutor(Executor executor, Looper looper) {
        mExecutor = executor;
        mExecutorLooper = looper;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mDrawable.getAlpha() > 0) {
            mDrawable.draw(canvas);
        }
    }

    public void setDrawable(Drawable drawable) {
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
        if (changed) {
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

    public void setColors(@NonNull ColorExtractor.GradientColors colors) {
        setColors(colors, false);
    }

    public void setColors(@NonNull ColorExtractor.GradientColors colors, boolean animated) {
        if (colors == null) {
            throw new IllegalArgumentException("Colors cannot be null");
        }
        executeOnExecutor(() -> {
            synchronized(mColorLock) {
                if (mColors.equals(colors)) {
                    return;
                }
                mColors.set(colors);
            }
            updateColorWithTint(animated);
        });
    }

    @VisibleForTesting
    Drawable getDrawable() {
        return mDrawable;
    }

    public ColorExtractor.GradientColors getColors() {
        synchronized(mColorLock) {
            mTmpColors.set(mColors);
        }
        return mTmpColors;
    }

    public void setTint(int color) {
        setTint(color, false);
    }

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
            int mainTinted = ColorUtils.blendARGB(mColors.getMainColor(), mTintColor,
                    tintAmount);
            drawable.setColor(mainTinted, animated);
        } else {
            boolean hasAlpha = Color.alpha(mTintColor) != 0;
            if (hasAlpha) {
                PorterDuff.Mode targetMode = mColorFilter == null ? Mode.SRC_OVER :
                    mColorFilter.getMode();
                if (mColorFilter == null || mColorFilter.getColor() != mTintColor) {
                    mColorFilter = new PorterDuffColorFilter(mTintColor, targetMode);
                }
            } else {
                mColorFilter = null;
            }

            mDrawable.setColorFilter(mColorFilter);
            mDrawable.invalidateSelf();
        }

        if (mChangeRunnable != null) {
            mChangeRunnableExecutor.execute(mChangeRunnable);
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
                if (mChangeRunnable != null) {
                    mChangeRunnableExecutor.execute(mChangeRunnable);
                }
            }
        });
    }

    public float getViewAlpha() {
        return mViewAlpha;
    }

    public void setChangeRunnable(Runnable changeRunnable, Executor changeRunnableExecutor) {
        mChangeRunnable = changeRunnable;
        mChangeRunnableExecutor = changeRunnableExecutor;
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
}

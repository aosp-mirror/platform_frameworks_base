/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.draganddrop;

import android.animation.ObjectAnimator;
import android.animation.RectEvaluator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.IntProperty;
import android.util.Property;
import android.view.animation.Interpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.graphics.ColorUtils;
import com.android.internal.policy.ScreenDecorationsUtils;
import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.protolog.ShellProtoLogGroup;
import com.android.wm.shell.R;

/**
 * Drawable to draw the region that the target will have once it is dropped.
 */
public class DropOutlineDrawable extends Drawable {

    private static final int BOUNDS_DURATION = 200;
    private static final int ALPHA_DURATION = 135;

    private final IntProperty<DropOutlineDrawable> ALPHA =
            new IntProperty<DropOutlineDrawable>("alpha") {
        @Override
        public void setValue(DropOutlineDrawable d, int alpha) {
            d.setAlpha(alpha);
        }

        @Override
        public Integer get(DropOutlineDrawable d) {
            return d.getAlpha();
        }
    };

    private final Property<DropOutlineDrawable, Rect> BOUNDS =
            new Property<DropOutlineDrawable, Rect>(Rect.class, "bounds") {
        @Override
        public void set(DropOutlineDrawable d, Rect bounds) {
            d.setRegionBounds(bounds);
        }

        @Override
        public Rect get(DropOutlineDrawable d) {
            return d.getRegionBounds();
        }
    };

    private final RectEvaluator mRectEvaluator = new RectEvaluator(new Rect());
    private ObjectAnimator mBoundsAnimator;
    private ObjectAnimator mAlphaAnimator;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect mBounds = new Rect();
    private final float mCornerRadius;
    private final int mMaxAlpha;
    private int mColor;

    public DropOutlineDrawable(Context context) {
        super();
        // TODO(b/169894807): Use corner specific radii and maybe lower radius for non-edge corners
        mCornerRadius = ScreenDecorationsUtils.getWindowCornerRadius(context);
        mColor = context.getColor(R.color.drop_outline_background);
        mMaxAlpha = Color.alpha(mColor);
        // Initialize as hidden
        ALPHA.set(this, 0);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        // Do nothing
    }

    @Override
    public void setAlpha(int alpha) {
        mColor = ColorUtils.setAlphaComponent(mColor, alpha);
        mPaint.setColor(mColor);
        invalidateSelf();
    }

    @Override
    public int getAlpha() {
        return Color.alpha(mColor);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        invalidateSelf();
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        canvas.drawRoundRect(mBounds.left, mBounds.top, mBounds.right, mBounds.bottom,
                mCornerRadius, mCornerRadius, mPaint);
    }

    public void setRegionBounds(Rect bounds) {
        mBounds.set(bounds);
        invalidateSelf();
    }

    public Rect getRegionBounds() {
        return mBounds;
    }

    ObjectAnimator startBoundsAnimation(Rect toBounds, Interpolator interpolator) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_DRAG_AND_DROP, "Animate bounds: from=%s to=%s",
                mBounds, toBounds);
        if (mBoundsAnimator != null) {
            mBoundsAnimator.cancel();
        }
        mBoundsAnimator = ObjectAnimator.ofObject(this, BOUNDS, mRectEvaluator,
                mBounds, toBounds);
        mBoundsAnimator.setDuration(BOUNDS_DURATION);
        mBoundsAnimator.setInterpolator(interpolator);
        mBoundsAnimator.start();
        return mBoundsAnimator;
    }

    ObjectAnimator startVisibilityAnimation(boolean visible, Interpolator interpolator) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_DRAG_AND_DROP, "Animate alpha: from=%d to=%d",
                Color.alpha(mColor), visible ? mMaxAlpha : 0);
        if (mAlphaAnimator != null) {
            mAlphaAnimator.cancel();
        }
        mAlphaAnimator = ObjectAnimator.ofInt(this, ALPHA, Color.alpha(mColor),
                visible ? mMaxAlpha : 0);
        mAlphaAnimator.setDuration(ALPHA_DURATION);
        mAlphaAnimator.setInterpolator(interpolator);
        mAlphaAnimator.start();
        return mAlphaAnimator;
    }
}

/*
 * Copyright 2018 The Android Open Source Project
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
package android.graphics.drawable;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.util.MathUtils;

/**
 * A Drawable that manages a {@link ColorDrawable} to make it stateful and backed by a
 * {@link ColorStateList}.
 */
public class ColorStateListDrawable extends Drawable implements Drawable.Callback {
    private ColorDrawable mColorDrawable;
    private ColorStateListDrawableState mState;
    private boolean mMutated = false;

    public ColorStateListDrawable() {
        mState = new ColorStateListDrawableState();
        initializeColorDrawable();
    }

    public ColorStateListDrawable(@NonNull ColorStateList colorStateList) {
        mState = new ColorStateListDrawableState();
        initializeColorDrawable();
        setColorStateList(colorStateList);
    }

    private ColorStateListDrawable(@NonNull ColorStateListDrawableState state) {
        mState = state;
        initializeColorDrawable();
        onStateChange(getState());
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        mColorDrawable.draw(canvas);
    }

    @Override
    @IntRange(from = 0, to = 255)
    public int getAlpha() {
        return mColorDrawable.getAlpha();
    }

    @Override
    public boolean isStateful() {
        return mState.isStateful();
    }

    @Override
    public boolean hasFocusStateSpecified() {
        return mState.hasFocusStateSpecified();
    }

    @Override
    public @NonNull Drawable getCurrent() {
        return mColorDrawable;
    }

    @Override
    public void applyTheme(@NonNull Resources.Theme t) {
        super.applyTheme(t);

        if (mState.mColor != null) {
            setColorStateList(mState.mColor.obtainForTheme(t));
        }

        if (mState.mTint != null) {
            setTintList(mState.mTint.obtainForTheme(t));
        }
    }

    @Override
    public boolean canApplyTheme() {
        return super.canApplyTheme() || mState.canApplyTheme();
    }

    @Override
    public void setAlpha(@IntRange(from = 0, to = 255) int alpha) {
        mState.mAlpha = alpha;
        onStateChange(getState());
    }

    /**
     * Remove the alpha override, reverting to the alpha defined on each color in the
     * {@link ColorStateList}.
     */
    public void clearAlpha() {
        mState.mAlpha = -1;
        onStateChange(getState());
    }

    @Override
    public void setTintList(@Nullable ColorStateList tint) {
        mState.mTint = tint;
        mColorDrawable.setTintList(tint);
        onStateChange(getState());
    }

    @Override
    public void setTintBlendMode(@NonNull BlendMode blendMode) {
        mState.mBlendMode = blendMode;
        mColorDrawable.setTintBlendMode(blendMode);
        onStateChange(getState());
    }

    @Override
    public @Nullable ColorFilter getColorFilter() {
        return mColorDrawable.getColorFilter();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        mColorDrawable.setColorFilter(colorFilter);
    }

    @Override
    public @PixelFormat.Opacity int getOpacity() {
        return mColorDrawable.getOpacity();
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        mColorDrawable.setBounds(bounds);
    }

    @Override
    protected boolean onStateChange(int[] state) {
        if (mState.mColor != null) {
            int color = mState.mColor.getColorForState(state, mState.mColor.getDefaultColor());

            if (mState.mAlpha != -1) {
                color = (color & 0xFFFFFF) | MathUtils.constrain(mState.mAlpha, 0, 255) << 24;
            }

            if (color != mColorDrawable.getColor()) {
                mColorDrawable.setColor(color);
                mColorDrawable.setState(state);
                return true;
            } else {
                return mColorDrawable.setState(state);
            }
        } else {
            return false;
        }
    }

    @Override
    public void invalidateDrawable(@NonNull Drawable who) {
        if (who == mColorDrawable && getCallback() != null) {
            getCallback().invalidateDrawable(this);
        }
    }

    @Override
    public void scheduleDrawable(@NonNull Drawable who, @NonNull Runnable what, long when) {
        if (who == mColorDrawable && getCallback() != null) {
            getCallback().scheduleDrawable(this, what, when);
        }
    }

    @Override
    public void unscheduleDrawable(@NonNull Drawable who, @NonNull Runnable what) {
        if (who == mColorDrawable && getCallback() != null) {
            getCallback().unscheduleDrawable(this, what);
        }
    }

    @Override
    public @NonNull ConstantState getConstantState() {
        mState.mChangingConfigurations = mState.mChangingConfigurations
                | (getChangingConfigurations() & ~mState.getChangingConfigurations());
        return mState;
    }

    /**
     * Returns the ColorStateList backing this Drawable, or a new ColorStateList of the default
     * ColorDrawable color if one hasn't been defined yet.
     *
     * @return a ColorStateList
     */
    public @NonNull ColorStateList getColorStateList() {
        if (mState.mColor == null) {
            return ColorStateList.valueOf(mColorDrawable.getColor());
        } else {
            return mState.mColor;
        }
    }

    @Override
    public int getChangingConfigurations() {
        return super.getChangingConfigurations() | mState.getChangingConfigurations();
    }

    @Override
    public @NonNull Drawable mutate() {
        if (!mMutated && super.mutate() == this) {
            mState = new ColorStateListDrawableState(mState);
            mMutated = true;
        }
        return this;
    }

    /**
     * @hide
     */
    @Override
    public void clearMutated() {
        super.clearMutated();
        mMutated = false;
    }

    /**
     * Replace this Drawable's ColorStateList. It is not copied, so changes will propagate on the
     * next call to {@link #setState(int[])}.
     *
     * @param colorStateList A color state list to attach.
     */
    public void setColorStateList(@NonNull ColorStateList colorStateList) {
        mState.mColor = colorStateList;
        onStateChange(getState());
    }

    static final class ColorStateListDrawableState extends ConstantState {
        ColorStateList mColor = null;
        ColorStateList mTint = null;
        int mAlpha = -1;
        BlendMode mBlendMode = DEFAULT_BLEND_MODE;
        @ActivityInfo.Config int mChangingConfigurations = 0;

        ColorStateListDrawableState() {
        }

        ColorStateListDrawableState(ColorStateListDrawableState state) {
            mColor = state.mColor;
            mTint = state.mTint;
            mAlpha = state.mAlpha;
            mBlendMode = state.mBlendMode;
            mChangingConfigurations = state.mChangingConfigurations;
        }

        @Override
        public Drawable newDrawable() {
            return new ColorStateListDrawable(this);
        }

        @Override
        public @ActivityInfo.Config int getChangingConfigurations() {
            return mChangingConfigurations
                    | (mColor != null ? mColor.getChangingConfigurations() : 0)
                    | (mTint != null ? mTint.getChangingConfigurations() : 0);
        }

        public boolean isStateful() {
            return (mColor != null && mColor.isStateful())
                    || (mTint != null && mTint.isStateful());
        }

        public boolean hasFocusStateSpecified() {
            return (mColor != null && mColor.hasFocusStateSpecified())
                    || (mTint != null && mTint.hasFocusStateSpecified());
        }

        @Override
        public boolean canApplyTheme() {
            return (mColor != null && mColor.canApplyTheme())
                    || (mTint != null && mTint.canApplyTheme());
        }
    }

    private void initializeColorDrawable() {
        mColorDrawable = new ColorDrawable();
        mColorDrawable.setCallback(this);

        if (mState.mTint != null) {
            mColorDrawable.setTintList(mState.mTint);
        }

        if (mState.mBlendMode != DEFAULT_BLEND_MODE) {
            mColorDrawable.setTintBlendMode(mState.mBlendMode);
        }
    }
}

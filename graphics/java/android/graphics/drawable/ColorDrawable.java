/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.annotation.ColorInt;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.pm.ActivityInfo.Config;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.graphics.BlendMode;
import android.graphics.BlendModeColorFilter;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Xfermode;
import android.os.Build;
import android.util.AttributeSet;
import android.view.ViewDebug;

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/**
 * A specialized Drawable that fills the Canvas with a specified color.
 * Note that a ColorDrawable ignores the ColorFilter.
 *
 * <p>It can be defined in an XML file with the <code>&lt;color></code> element.</p>
 *
 * @attr ref android.R.styleable#ColorDrawable_color
 */
public class ColorDrawable extends Drawable {
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    @ViewDebug.ExportedProperty(deepExport = true, prefix = "state_")
    private ColorState mColorState;
    private BlendModeColorFilter mBlendModeColorFilter;

    private boolean mMutated;

    /**
     * Creates a new black ColorDrawable.
     */
    public ColorDrawable() {
        mColorState = new ColorState();
    }

    /**
     * Creates a new ColorDrawable with the specified color.
     *
     * @param color The color to draw.
     */
    public ColorDrawable(@ColorInt int color) {
        mColorState = new ColorState();

        setColor(color);
    }

    @Override
    public @Config int getChangingConfigurations() {
        return super.getChangingConfigurations() | mColorState.getChangingConfigurations();
    }

    /**
     * A mutable BitmapDrawable still shares its Bitmap with any other Drawable
     * that comes from the same resource.
     *
     * @return This drawable.
     */
    @Override
    public Drawable mutate() {
        if (!mMutated && super.mutate() == this) {
            mColorState = new ColorState(mColorState);
            mMutated = true;
        }
        return this;
    }

    /**
     * @hide
     */
    public void clearMutated() {
        super.clearMutated();
        mMutated = false;
    }

    @Override
    public void draw(Canvas canvas) {
        final ColorFilter colorFilter = mPaint.getColorFilter();
        if ((mColorState.mUseColor >>> 24) != 0 || colorFilter != null
                || mBlendModeColorFilter != null) {
            if (colorFilter == null) {
                mPaint.setColorFilter(mBlendModeColorFilter);
            }

            mPaint.setColor(mColorState.mUseColor);
            canvas.drawRect(getBounds(), mPaint);

            // Restore original color filter.
            mPaint.setColorFilter(colorFilter);
        }
    }

    /**
     * Gets the drawable's color value.
     *
     * @return int The color to draw.
     */
    @ColorInt
    public int getColor() {
        return mColorState.mUseColor;
    }

    /**
     * Sets the drawable's color value. This action will clobber the results of
     * prior calls to {@link #setAlpha(int)} on this object, which side-affected
     * the underlying color.
     *
     * @param color The color to draw.
     */
    public void setColor(@ColorInt int color) {
        if (mColorState.mBaseColor != color || mColorState.mUseColor != color) {
            mColorState.mBaseColor = mColorState.mUseColor = color;
            invalidateSelf();
        }
    }

    /**
     * Returns the alpha value of this drawable's color. Note this may not be the same alpha value
     * provided in {@link Drawable#setAlpha(int)}. Instead this will return the alpha of the color
     * combined with the alpha provided by setAlpha
     *
     * @return A value between 0 and 255.
     *
     * @see ColorDrawable#setAlpha(int)
     */
    @Override
    public int getAlpha() {
        return mColorState.mUseColor >>> 24;
    }

    /**
     * Applies the given alpha to the underlying color. Note if the color already has
     * an alpha applied to it, this will apply this alpha to the existing value instead of
     * overwriting it.
     *
     * @param alpha The alpha value to set, between 0 and 255.
     */
    @Override
    public void setAlpha(int alpha) {
        alpha += alpha >> 7;   // make it 0..256
        final int baseAlpha = mColorState.mBaseColor >>> 24;
        final int useAlpha = baseAlpha * alpha >> 8;
        final int useColor = (mColorState.mBaseColor << 8 >>> 8) | (useAlpha << 24);
        if (mColorState.mUseColor != useColor) {
            mColorState.mUseColor = useColor;
            invalidateSelf();
        }
    }

    /**
     * Sets the color filter applied to this color.
     * <p>
     * Only supported on version {@link android.os.Build.VERSION_CODES#LOLLIPOP} and
     * above. Calling this method has no effect on earlier versions.
     *
     * @see android.graphics.drawable.Drawable#setColorFilter(ColorFilter)
     */
    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        mPaint.setColorFilter(colorFilter);
    }

    /**
     * Returns the color filter applied to this color configured by
     * {@link #setColorFilter(ColorFilter)}
     *
     * @see android.graphics.drawable.Drawable#getColorFilter()
     */
    @Override
    public @Nullable ColorFilter getColorFilter() {
        return mPaint.getColorFilter();
    }

    @Override
    public void setTintList(ColorStateList tint) {
        mColorState.mTint = tint;
        mBlendModeColorFilter = updateBlendModeFilter(mBlendModeColorFilter, tint,
                mColorState.mBlendMode);
        invalidateSelf();
    }

    @Override
    public void setTintBlendMode(@NonNull BlendMode blendMode) {
        mColorState.mBlendMode = blendMode;
        mBlendModeColorFilter = updateBlendModeFilter(mBlendModeColorFilter, mColorState.mTint,
                blendMode);
        invalidateSelf();
    }

    @Override
    protected boolean onStateChange(int[] stateSet) {
        final ColorState state = mColorState;
        if (state.mTint != null && state.mBlendMode != null) {
            mBlendModeColorFilter = updateBlendModeFilter(mBlendModeColorFilter, state.mTint,
                    state.mBlendMode);
            return true;
        }
        return false;
    }

    @Override
    public boolean isStateful() {
        return mColorState.mTint != null && mColorState.mTint.isStateful();
    }

    @Override
    public boolean hasFocusStateSpecified() {
        return mColorState.mTint != null && mColorState.mTint.hasFocusStateSpecified();
    }

    /**
     * @hide
     * @param mode new transfer mode
     */
    @Override
    public void setXfermode(@Nullable Xfermode mode) {
        mPaint.setXfermode(mode);
        invalidateSelf();
    }

    /**
     * @hide
     * @return current transfer mode
     */
    @TestApi
    public Xfermode getXfermode() {
        return mPaint.getXfermode();
    }

    @Override
    public int getOpacity() {
        if (mBlendModeColorFilter != null || mPaint.getColorFilter() != null) {
            return PixelFormat.TRANSLUCENT;
        }

        switch (mColorState.mUseColor >>> 24) {
            case 255:
                return PixelFormat.OPAQUE;
            case 0:
                return PixelFormat.TRANSPARENT;
        }
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public void getOutline(@NonNull Outline outline) {
        outline.setRect(getBounds());
        outline.setAlpha(getAlpha() / 255.0f);
    }

    @Override
    public void inflate(Resources r, XmlPullParser parser, AttributeSet attrs, Theme theme)
            throws XmlPullParserException, IOException {
        super.inflate(r, parser, attrs, theme);

        final TypedArray a = obtainAttributes(r, theme, attrs, R.styleable.ColorDrawable);
        updateStateFromTypedArray(a);
        a.recycle();

        updateLocalState(r);
    }

    /**
     * Updates the constant state from the values in the typed array.
     */
    private void updateStateFromTypedArray(TypedArray a) {
        final ColorState state = mColorState;

        // Account for any configuration changes.
        state.mChangingConfigurations |= a.getChangingConfigurations();

        // Extract the theme attributes, if any.
        state.mThemeAttrs = a.extractThemeAttrs();

        state.mBaseColor = a.getColor(R.styleable.ColorDrawable_color, state.mBaseColor);
        state.mUseColor = state.mBaseColor;
    }

    @Override
    public boolean canApplyTheme() {
        return mColorState.canApplyTheme() || super.canApplyTheme();
    }

    @Override
    public void applyTheme(Theme t) {
        super.applyTheme(t);

        final ColorState state = mColorState;
        if (state == null) {
            return;
        }

        if (state.mThemeAttrs != null) {
            final TypedArray a = t.resolveAttributes(state.mThemeAttrs, R.styleable.ColorDrawable);
            updateStateFromTypedArray(a);
            a.recycle();
        }

        if (state.mTint != null && state.mTint.canApplyTheme()) {
            state.mTint = state.mTint.obtainForTheme(t);
        }

        updateLocalState(t.getResources());
    }

    @Override
    public ConstantState getConstantState() {
        return mColorState;
    }

    final static class ColorState extends ConstantState {
        int[] mThemeAttrs;
        int mBaseColor; // base color, independent of setAlpha()
        @ViewDebug.ExportedProperty
        @UnsupportedAppUsage
        int mUseColor;  // basecolor modulated by setAlpha()
        @Config int mChangingConfigurations;
        ColorStateList mTint = null;
        BlendMode mBlendMode = DEFAULT_BLEND_MODE;

        ColorState() {
            // Empty constructor.
        }

        ColorState(ColorState state) {
            mThemeAttrs = state.mThemeAttrs;
            mBaseColor = state.mBaseColor;
            mUseColor = state.mUseColor;
            mChangingConfigurations = state.mChangingConfigurations;
            mTint = state.mTint;
            mBlendMode = state.mBlendMode;
        }

        @Override
        public boolean canApplyTheme() {
            return mThemeAttrs != null
                    || (mTint != null && mTint.canApplyTheme());
        }

        @Override
        public Drawable newDrawable() {
            return new ColorDrawable(this, null);
        }

        @Override
        public Drawable newDrawable(Resources res) {
            return new ColorDrawable(this, res);
        }

        @Override
        public @Config int getChangingConfigurations() {
            return mChangingConfigurations
                    | (mTint != null ? mTint.getChangingConfigurations() : 0);
        }
    }

    private ColorDrawable(ColorState state, Resources res) {
        mColorState = state;

        updateLocalState(res);
    }

    /**
     * Initializes local dynamic properties from state. This should be called
     * after significant state changes, e.g. from the One True Constructor and
     * after inflating or applying a theme.
     */
    private void updateLocalState(Resources r) {
        mBlendModeColorFilter = updateBlendModeFilter(mBlendModeColorFilter, mColorState.mTint,
                mColorState.mBlendMode);
    }
}

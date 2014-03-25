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

import android.graphics.*;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
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
    @ViewDebug.ExportedProperty(deepExport = true, prefix = "state_")
    private ColorState mColorState;
    private final Paint mPaint = new Paint();
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
    public ColorDrawable(int color) {
        mColorState = new ColorState();

        setColor(color);
    }

    @Override
    public int getChangingConfigurations() {
        return super.getChangingConfigurations() | mColorState.mChangingConfigurations;
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

    @Override
    public void draw(Canvas canvas) {
        if ((mColorState.mUseColor >>> 24) != 0) {
            mPaint.setColor(mColorState.mUseColor);
            canvas.drawRect(getBounds(), mPaint);
        }
    }

    /**
     * Gets the drawable's color value.
     *
     * @return int The color to draw.
     */
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
    public void setColor(int color) {
        if (mColorState.mBaseColor != color || mColorState.mUseColor != color) {
            mColorState.mBaseColor = mColorState.mUseColor = color;
            invalidateSelf();
        }
    }

    /**
     * Returns the alpha value of this drawable's color.
     *
     * @return A value between 0 and 255.
     */
    @Override
    public int getAlpha() {
        return mColorState.mUseColor >>> 24;
    }

    /**
     * Sets the color's alpha value.
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
     * Setting a color filter on a ColorDrawable has no effect.
     *
     * @param colorFilter Ignore.
     */
    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        switch (mColorState.mUseColor >>> 24) {
            case 255:
                return PixelFormat.OPAQUE;
            case 0:
                return PixelFormat.TRANSPARENT;
        }
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public void inflate(Resources r, XmlPullParser parser, AttributeSet attrs, Theme theme)
            throws XmlPullParserException, IOException {
        super.inflate(r, parser, attrs, theme);

        final TypedArray a = obtainAttributes(
                r, theme, attrs, R.styleable.ColorDrawable);
        inflateStateFromTypedArray(a);
        a.recycle();
    }

    /**
     * Initializes the constant state from the values in the typed array.
     */
    private void inflateStateFromTypedArray(TypedArray a) {
        final ColorState state = mColorState;

        // Extract the theme attributes, if any.
        final int[] themeAttrs = a.extractThemeAttrs();
        state.mThemeAttrs = themeAttrs;

        if (themeAttrs == null || themeAttrs[R.styleable.ColorDrawable_color] == 0) {
            final int color = a.getColor(R.styleable.ColorDrawable_color, 0);
            state.mBaseColor = color;
            state.mUseColor = color;
        }
    }

    @Override
    public void applyTheme(Theme t) {
        super.applyTheme(t);

        final ColorState state = mColorState;
        if (state == null) {
            throw new RuntimeException("Can't apply theme to <color> with no constant state");
        }

        final int[] themeAttrs = state.mThemeAttrs;
        if (themeAttrs != null) {
            final TypedArray a = t.resolveAttributes(themeAttrs, R.styleable.ColorDrawable, 0, 0);
            updateStateFromTypedArray(a);
            a.recycle();
        }
    }

    /**
     * Updates the constant state from the values in the typed array.
     */
    private void updateStateFromTypedArray(TypedArray a) {
        final ColorState state = mColorState;

        if (a.hasValue(R.styleable.ColorDrawable_color)) {
            final int color = a.getColor(R.styleable.ColorDrawable_color, 0);
            state.mBaseColor = color;
            state.mUseColor = color;
        }
    }

    @Override
    public ConstantState getConstantState() {
        mColorState.mChangingConfigurations = getChangingConfigurations();
        return mColorState;
    }

    final static class ColorState extends ConstantState {
        int mBaseColor; // base color, independent of setAlpha()
        @ViewDebug.ExportedProperty
        int mUseColor;  // basecolor modulated by setAlpha()
        int mChangingConfigurations;
        int[] mThemeAttrs;

        ColorState() {
            // Empty constructor.
        }

        ColorState(ColorState state) {
            mBaseColor = state.mBaseColor;
            mUseColor = state.mUseColor;
            mChangingConfigurations = state.mChangingConfigurations;
            mThemeAttrs = state.mThemeAttrs;
        }

        @Override
        public boolean canApplyTheme() {
            return mThemeAttrs != null;
        }

        @Override
        public Drawable newDrawable() {
            return new ColorDrawable(this, null, null);
        }

        @Override
        public Drawable newDrawable(Resources res) {
            return new ColorDrawable(this, res, null);
        }

        @Override
        public Drawable newDrawable(Resources res, Theme theme) {
            return new ColorDrawable(this, res, theme);
        }

        @Override
        public int getChangingConfigurations() {
            return mChangingConfigurations;
        }
    }

    private ColorDrawable(ColorState state, Resources res, Theme theme) {
        if (theme != null && state.canApplyTheme()) {
            mColorState = new ColorState(state);
            applyTheme(theme);
        } else {
            mColorState = state;
        }

        // No local properties to initialize.
    }
}

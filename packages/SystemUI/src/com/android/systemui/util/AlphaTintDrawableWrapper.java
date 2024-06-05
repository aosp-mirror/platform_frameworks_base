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

package com.android.systemui.util;

import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.InsetDrawable;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.systemui.res.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/**
 * An extension of {@link DrawableWrapper} that supports alpha and tint XML properties.
 *
 * {@link DrawableWrapper} supports setting these properties programmatically, but doesn't expose
 * corresponding XML properties for some reason. This class allows to set these values in the XML,
 * supporting theming.
 *
 * This class should only be used in XML.
 *
 * @attr ref android.R.styleable#DrawableWrapper_drawable
 * @attr ref R.styleable#AlphaTintDrawableWrapper_tint
 * @attr ref R.styleable#AlphaTintDrawableWrapper_alpha
 */
public class AlphaTintDrawableWrapper extends InsetDrawable {
    private ColorStateList mTint;
    private int[] mThemeAttrs;

    /** No-arg constructor used by drawable inflation. */
    public AlphaTintDrawableWrapper() {
        super(null, 0);
    }

    AlphaTintDrawableWrapper(Drawable drawable, int[] themeAttrs) {
        super(drawable, 0);
        mThemeAttrs = themeAttrs;
    }

    @Override
    public void inflate(@NonNull Resources r, @NonNull XmlPullParser parser,
            @NonNull AttributeSet attrs, @Nullable Theme theme)
            throws XmlPullParserException, IOException {
        final TypedArray a = obtainAttributes(r, theme, attrs,
                R.styleable.AlphaTintDrawableWrapper);

        super.inflate(r, parser, attrs, theme);

        mThemeAttrs = a.extractThemeAttrs();
        updateStateFromTypedArray(a);
        a.recycle();

        applyTint();
    }

    @Override
    public void applyTheme(Theme t) {
        super.applyTheme(t);

        if (mThemeAttrs != null && t != null) {
            final TypedArray a = t.resolveAttributes(mThemeAttrs,
                    R.styleable.AlphaTintDrawableWrapper);
            updateStateFromTypedArray(a);
            a.recycle();
        }

        // Ensure tint is reapplied after applying the theme to ensure this drawables'
        // tint overrides the underlying drawables' tint.
        applyTint();
    }

    @Override
    public boolean canApplyTheme() {
        return (mThemeAttrs != null && mThemeAttrs.length > 0) || super.canApplyTheme();
    }

    private void updateStateFromTypedArray(@NonNull TypedArray a) {
        if (a.hasValue(R.styleable.AlphaTintDrawableWrapper_android_tint)) {
            mTint = a.getColorStateList(R.styleable.AlphaTintDrawableWrapper_android_tint);
        }
        if (a.hasValue(R.styleable.AlphaTintDrawableWrapper_android_alpha)) {
            float alpha = a.getFloat(R.styleable.AlphaTintDrawableWrapper_android_alpha, 1);
            setAlpha(Math.round(alpha * 255));
        }
    }

    @Override
    public void setTintList(ColorStateList tint) {
        super.setTintList(tint);
        mTint = tint;
    }

    private void applyTint() {
        if (getDrawable() != null && mTint != null) {
            getDrawable().mutate().setTintList(mTint);
        }
    }

    @Nullable
    @Override
    public ConstantState getConstantState() {
        return new AlphaTintState(super.getConstantState(), mThemeAttrs, getAlpha(), mTint);
    }

    static class AlphaTintState extends Drawable.ConstantState {

        private ConstantState mWrappedState;
        private int[] mThemeAttrs;
        private int mAlpha;
        private ColorStateList mColorStateList;

        AlphaTintState(
                ConstantState wrappedState,
                int[] themeAttrs,
                int alpha,
                ColorStateList colorStateList
        ) {
            mWrappedState = wrappedState;
            mThemeAttrs = themeAttrs;
            mAlpha = alpha;
            mColorStateList = colorStateList;
        }

        @NonNull
        @Override
        public Drawable newDrawable() {
            return newDrawable(null, null);
        }

        @NonNull
        @Override
        public Drawable newDrawable(Resources res, Theme theme) {
            DrawableWrapper wrapper = (DrawableWrapper) mWrappedState.newDrawable(res, theme);
            AlphaTintDrawableWrapper alphaTintDrawableWrapper =
                    new AlphaTintDrawableWrapper(wrapper.getDrawable(), mThemeAttrs);
            alphaTintDrawableWrapper.setTintList(mColorStateList);
            alphaTintDrawableWrapper.setAlpha(mAlpha);
            return alphaTintDrawableWrapper;
        }

        @Override
        public boolean canApplyTheme() {
            return true;
        }

        @Override
        public int getChangingConfigurations() {
            return mWrappedState.getChangingConfigurations();
        }
    }
}

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

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.DisplayMetrics;

import java.io.IOException;

/**
 * A Drawable that insets another Drawable by a specified distance.
 * This is used when a View needs a background that is smaller than
 * the View's actual bounds.
 *
 * <p>It can be defined in an XML file with the <code>&lt;inset></code> element. For more
 * information, see the guide to <a
 * href="{@docRoot}guide/topics/resources/drawable-resource.html">Drawable Resources</a>.</p>
 *
 * @attr ref android.R.styleable#InsetDrawable_visible
 * @attr ref android.R.styleable#InsetDrawable_drawable
 * @attr ref android.R.styleable#InsetDrawable_insetLeft
 * @attr ref android.R.styleable#InsetDrawable_insetRight
 * @attr ref android.R.styleable#InsetDrawable_insetTop
 * @attr ref android.R.styleable#InsetDrawable_insetBottom
 */
public class InsetDrawable extends DrawableWrapper {
    private final Rect mTmpRect = new Rect();

    private InsetState mState;

    /**
     * No-arg constructor used by drawable inflation.
     */
    InsetDrawable() {
        this(new InsetState(null, null), null);
    }

    /**
     * Creates a new inset drawable with the specified inset.
     *
     * @param drawable The drawable to inset.
     * @param inset Inset in pixels around the drawable.
     */
    public InsetDrawable(@Nullable Drawable drawable, int inset) {
        this(drawable, inset, inset, inset, inset);
    }

    /**
     * Creates a new inset drawable with the specified insets.
     *
     * @param drawable The drawable to inset.
     * @param insetLeft Left inset in pixels.
     * @param insetTop Top inset in pixels.
     * @param insetRight Right inset in pixels.
     * @param insetBottom Bottom inset in pixels.
     */
    public InsetDrawable(@Nullable Drawable drawable, int insetLeft, int insetTop,
            int insetRight, int insetBottom) {
        this(new InsetState(null, null), null);

        mState.mInsetLeft = insetLeft;
        mState.mInsetTop = insetTop;
        mState.mInsetRight = insetRight;
        mState.mInsetBottom = insetBottom;

        setDrawable(drawable);
    }

    @Override
    public void inflate(@NonNull Resources r, @NonNull XmlPullParser parser,
            @NonNull AttributeSet attrs, @Nullable Theme theme)
            throws XmlPullParserException, IOException {
        final TypedArray a = obtainAttributes(r, theme, attrs, R.styleable.InsetDrawable);

        // Inflation will advance the XmlPullParser and AttributeSet.
        super.inflate(r, parser, attrs, theme);

        updateStateFromTypedArray(a);
        verifyRequiredAttributes(a);
        a.recycle();
    }

    @Override
    public void applyTheme(@NonNull Theme t) {
        super.applyTheme(t);

        final InsetState state = mState;
        if (state == null) {
            return;
        }

        if (state.mThemeAttrs != null) {
            final TypedArray a = t.resolveAttributes(state.mThemeAttrs, R.styleable.InsetDrawable);
            try {
                updateStateFromTypedArray(a);
                verifyRequiredAttributes(a);
            } catch (XmlPullParserException e) {
                rethrowAsRuntimeException(e);
            } finally {
                a.recycle();
            }
        }
    }

    private void verifyRequiredAttributes(@NonNull TypedArray a) throws XmlPullParserException {
        // If we're not waiting on a theme, verify required attributes.
        if (getDrawable() == null && (mState.mThemeAttrs == null
                || mState.mThemeAttrs[R.styleable.InsetDrawable_drawable] == 0)) {
            throw new XmlPullParserException(a.getPositionDescription()
                    + ": <inset> tag requires a 'drawable' attribute or "
                    + "child tag defining a drawable");
        }
    }

    private void updateStateFromTypedArray(@NonNull TypedArray a) {
        final InsetState state = mState;
        if (state == null) {
            return;
        }

        // Account for any configuration changes.
        state.mChangingConfigurations |= a.getChangingConfigurations();

        // Extract the theme attributes, if any.
        state.mThemeAttrs = a.extractThemeAttrs();

        // Inset attribute may be overridden by more specific attributes.
        if (a.hasValue(R.styleable.InsetDrawable_inset)) {
            final int inset = a.getDimensionPixelOffset(R.styleable.InsetDrawable_inset, 0);
            state.mInsetLeft = inset;
            state.mInsetTop = inset;
            state.mInsetRight = inset;
            state.mInsetBottom = inset;
        }

        state.mInsetLeft = a.getDimensionPixelOffset(
                R.styleable.InsetDrawable_insetLeft, state.mInsetLeft);
        state.mInsetRight = a.getDimensionPixelOffset(
                R.styleable.InsetDrawable_insetRight, state.mInsetRight);
        state.mInsetTop = a.getDimensionPixelOffset(
                R.styleable.InsetDrawable_insetTop, state.mInsetTop);
        state.mInsetBottom = a.getDimensionPixelOffset(
                R.styleable.InsetDrawable_insetBottom, state.mInsetBottom);
    }

    @Override
    public boolean getPadding(Rect padding) {
        final boolean pad = super.getPadding(padding);

        padding.left += mState.mInsetLeft;
        padding.right += mState.mInsetRight;
        padding.top += mState.mInsetTop;
        padding.bottom += mState.mInsetBottom;

        return pad || (mState.mInsetLeft | mState.mInsetRight
                | mState.mInsetTop | mState.mInsetBottom) != 0;
    }

    /** @hide */
    @Override
    public Insets getOpticalInsets() {
        final Insets contentInsets = super.getOpticalInsets();
        return Insets.of(contentInsets.left + mState.mInsetLeft,
                contentInsets.top + mState.mInsetTop,
                contentInsets.right + mState.mInsetRight,
                contentInsets.bottom + mState.mInsetBottom);
    }

    @Override
    public int getOpacity() {
        final InsetState state = mState;
        final int opacity = getDrawable().getOpacity();
        if (opacity == PixelFormat.OPAQUE && (state.mInsetLeft > 0 || state.mInsetTop > 0
                || state.mInsetRight > 0 || state.mInsetBottom > 0)) {
            return PixelFormat.TRANSLUCENT;
        }
        return opacity;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        final Rect r = mTmpRect;
        r.set(bounds);

        r.left += mState.mInsetLeft;
        r.top += mState.mInsetTop;
        r.right -= mState.mInsetRight;
        r.bottom -= mState.mInsetBottom;

        // Apply inset bounds to the wrapped drawable.
        super.onBoundsChange(r);
    }

    @Override
    public int getIntrinsicWidth() {
        final int childWidth = getDrawable().getIntrinsicWidth();
        if (childWidth < 0) {
            return -1;
        }
        return childWidth + mState.mInsetLeft + mState.mInsetRight;
    }

    @Override
    public int getIntrinsicHeight() {
        final int childHeight = getDrawable().getIntrinsicHeight();
        if (childHeight < 0) {
            return -1;
        }
        return childHeight + mState.mInsetTop + mState.mInsetBottom;
    }

    @Override
    public void getOutline(@NonNull Outline outline) {
        getDrawable().getOutline(outline);
    }

    @Override
    DrawableWrapperState mutateConstantState() {
        mState = new InsetState(mState, null);
        return mState;
    }

    static final class InsetState extends DrawableWrapper.DrawableWrapperState {
        private int[] mThemeAttrs;

        int mInsetLeft = 0;
        int mInsetTop = 0;
        int mInsetRight = 0;
        int mInsetBottom = 0;

        InsetState(@Nullable InsetState orig, @Nullable Resources res) {
            super(orig, res);

            if (orig != null) {
                mInsetLeft = orig.mInsetLeft;
                mInsetTop = orig.mInsetTop;
                mInsetRight = orig.mInsetRight;
                mInsetBottom = orig.mInsetBottom;

                if (orig.mDensity != mDensity) {
                    applyDensityScaling(orig.mDensity, mDensity);
                }
            }
        }

        @Override
        void onDensityChanged(int sourceDensity, int targetDensity) {
            super.onDensityChanged(sourceDensity, targetDensity);

            applyDensityScaling(sourceDensity, targetDensity);
        }

        /**
         * Called when the constant state density changes to scale
         * density-dependent properties specific to insets.
         *
         * @param sourceDensity the previous constant state density
         * @param targetDensity the new constant state density
         */
        private void applyDensityScaling(int sourceDensity, int targetDensity) {
            mInsetLeft = Bitmap.scaleFromDensity(mInsetLeft, sourceDensity, targetDensity);
            mInsetTop = Bitmap.scaleFromDensity(mInsetTop, sourceDensity, targetDensity);
            mInsetRight = Bitmap.scaleFromDensity(mInsetRight, sourceDensity, targetDensity);
            mInsetBottom = Bitmap.scaleFromDensity(mInsetBottom, sourceDensity, targetDensity);
        }

        @Override
        public Drawable newDrawable(@Nullable Resources res) {
            // If this drawable is being created for a different density,
            // just create a new constant state and call it a day.
            final InsetState state;
            if (res != null) {
                final int densityDpi = res.getDisplayMetrics().densityDpi;
                final int density = densityDpi == 0 ? DisplayMetrics.DENSITY_DEFAULT : densityDpi;
                if (density != mDensity) {
                    state = new InsetState(this, res);
                } else {
                    state = this;
                }
            } else {
                state = this;
            }

            return new InsetDrawable(state, res);
        }
    }

    /**
     * The one constructor to rule them all. This is called by all public
     * constructors to set the state and initialize local properties.
     */
    private InsetDrawable(@NonNull InsetState state, @Nullable Resources res) {
        super(state, res);

        mState = state;
    }
}


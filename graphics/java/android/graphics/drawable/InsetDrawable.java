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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.TypedValue;

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/**
 * A Drawable that insets another Drawable by a specified distance or fraction of the content bounds.
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
    private final Rect mTmpInsetRect = new Rect();

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
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
     * Creates a new inset drawable with the specified inset.
     *
     * @param drawable The drawable to inset.
     * @param inset Inset in fraction (range: [0, 1)) of the inset content bounds.
     */
    public InsetDrawable(@Nullable Drawable drawable, float inset) {
        this(drawable, inset, inset, inset, inset);
    }

    /**
     * Creates a new inset drawable with the specified insets in pixels.
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

        mState.mInsetLeft = new InsetValue(0f, insetLeft);
        mState.mInsetTop = new InsetValue(0f, insetTop);
        mState.mInsetRight = new InsetValue(0f, insetRight);
        mState.mInsetBottom = new InsetValue(0f, insetBottom);

        setDrawable(drawable);
    }

    /**
     * Creates a new inset drawable with the specified insets in fraction of the view bounds.
     *
     * @param drawable The drawable to inset.
     * @param insetLeftFraction Left inset in fraction (range: [0, 1)) of the inset content bounds.
     * @param insetTopFraction Top inset in fraction (range: [0, 1)) of the inset content bounds.
     * @param insetRightFraction Right inset in fraction (range: [0, 1)) of the inset content bounds.
     * @param insetBottomFraction Bottom inset in fraction (range: [0, 1)) of the inset content bounds.
     */
    public InsetDrawable(@Nullable Drawable drawable, float insetLeftFraction,
        float insetTopFraction, float insetRightFraction, float insetBottomFraction) {
        this(new InsetState(null, null), null);

        mState.mInsetLeft = new InsetValue(insetLeftFraction, 0);
        mState.mInsetTop = new InsetValue(insetTopFraction, 0);
        mState.mInsetRight = new InsetValue(insetRightFraction, 0);
        mState.mInsetBottom = new InsetValue(insetBottomFraction, 0);

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
            final InsetValue inset = getInset(a, R.styleable.InsetDrawable_inset, new InsetValue());
            state.mInsetLeft = inset;
            state.mInsetTop = inset;
            state.mInsetRight = inset;
            state.mInsetBottom = inset;
        }
        state.mInsetLeft = getInset(a, R.styleable.InsetDrawable_insetLeft, state.mInsetLeft);
        state.mInsetTop = getInset(a, R.styleable.InsetDrawable_insetTop, state.mInsetTop);
        state.mInsetRight = getInset(a, R.styleable.InsetDrawable_insetRight, state.mInsetRight);
        state.mInsetBottom = getInset(a, R.styleable.InsetDrawable_insetBottom, state.mInsetBottom);
    }

    private InsetValue getInset(@NonNull TypedArray a, int index, InsetValue defaultValue) {
        if (a.hasValue(index)) {
            TypedValue tv = a.peekValue(index);
            if (tv.type == TypedValue.TYPE_FRACTION) {
                float f = tv.getFraction(1.0f, 1.0f);
                if (f >= 1f) {
                    throw new IllegalStateException("Fraction cannot be larger than 1");
                }
                return new InsetValue(f, 0);
            } else {
                int dimension = a.getDimensionPixelOffset(index, 0);
                if (dimension != 0) {
                    return new InsetValue(0, dimension);
                }
            }
        }
        return defaultValue;
    }

    private void getInsets(Rect out) {
        final Rect b = getBounds();
        out.left = mState.mInsetLeft.getDimension(b.width());
        out.right = mState.mInsetRight.getDimension(b.width());
        out.top = mState.mInsetTop.getDimension(b.height());
        out.bottom = mState.mInsetBottom.getDimension(b.height());
    }

    @Override
    public boolean getPadding(Rect padding) {
        final boolean pad = super.getPadding(padding);
        getInsets(mTmpInsetRect);
        padding.left += mTmpInsetRect.left;
        padding.right += mTmpInsetRect.right;
        padding.top += mTmpInsetRect.top;
        padding.bottom += mTmpInsetRect.bottom;

        return pad || (mTmpInsetRect.left | mTmpInsetRect.right
                | mTmpInsetRect.top | mTmpInsetRect.bottom) != 0;
    }

    @Override
    public Insets getOpticalInsets() {
        final Insets contentInsets = super.getOpticalInsets();
        getInsets(mTmpInsetRect);
        return Insets.of(
                contentInsets.left + mTmpInsetRect.left,
                contentInsets.top + mTmpInsetRect.top,
                contentInsets.right + mTmpInsetRect.right,
                contentInsets.bottom + mTmpInsetRect.bottom);
    }

    @Override
    public int getOpacity() {
        final InsetState state = mState;
        final int opacity = getDrawable().getOpacity();
        getInsets(mTmpInsetRect);
        if (opacity == PixelFormat.OPAQUE &&
            (mTmpInsetRect.left > 0 || mTmpInsetRect.top > 0 || mTmpInsetRect.right > 0
                || mTmpInsetRect.bottom > 0)) {
            return PixelFormat.TRANSLUCENT;
        }
        return opacity;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        final Rect r = mTmpRect;
        r.set(bounds);

        r.left += mState.mInsetLeft.getDimension(bounds.width());
        r.top += mState.mInsetTop.getDimension(bounds.height());
        r.right -= mState.mInsetRight.getDimension(bounds.width());
        r.bottom -= mState.mInsetBottom.getDimension(bounds.height());

        // Apply inset bounds to the wrapped drawable.
        super.onBoundsChange(r);
    }

    @Override
    public int getIntrinsicWidth() {
        final int childWidth = getDrawable().getIntrinsicWidth();
        final float fraction = mState.mInsetLeft.mFraction + mState.mInsetRight.mFraction;
        if (childWidth < 0 || fraction >= 1) {
            return -1;
        }
        return (int) (childWidth / (1 - fraction)) + mState.mInsetLeft.mDimension
            + mState.mInsetRight.mDimension;
    }

    @Override
    public int getIntrinsicHeight() {
        final int childHeight = getDrawable().getIntrinsicHeight();
        final float fraction = mState.mInsetTop.mFraction + mState.mInsetBottom.mFraction;
        if (childHeight < 0 || fraction >= 1) {
            return -1;
        }
        return (int) (childHeight / (1 - fraction)) + mState.mInsetTop.mDimension
            + mState.mInsetBottom.mDimension;
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

        InsetValue mInsetLeft;
        InsetValue mInsetTop;
        InsetValue mInsetRight;
        InsetValue mInsetBottom;

        InsetState(@Nullable InsetState orig, @Nullable Resources res) {
            super(orig, res);

            if (orig != null) {
                mInsetLeft = orig.mInsetLeft.clone();
                mInsetTop = orig.mInsetTop.clone();
                mInsetRight = orig.mInsetRight.clone();
                mInsetBottom = orig.mInsetBottom.clone();

                if (orig.mDensity != mDensity) {
                    applyDensityScaling(orig.mDensity, mDensity);
                }
            } else {
                mInsetLeft = new InsetValue();
                mInsetTop = new InsetValue();
                mInsetRight = new InsetValue();
                mInsetBottom = new InsetValue();
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
            mInsetLeft.scaleFromDensity(sourceDensity, targetDensity);
            mInsetTop.scaleFromDensity(sourceDensity, targetDensity);
            mInsetRight.scaleFromDensity(sourceDensity, targetDensity);
            mInsetBottom.scaleFromDensity(sourceDensity, targetDensity);
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

    static final class InsetValue implements Cloneable {
        final float mFraction;
        int mDimension;

        public InsetValue() {
            this(0f, 0);
        }

        public InsetValue(float fraction, int dimension) {
            mFraction = fraction;
            mDimension = dimension;
        }
        int getDimension(int boundSize) {
            return (int) (boundSize * mFraction) + mDimension;
        }

        void scaleFromDensity(int sourceDensity, int targetDensity) {
            if (mDimension != 0) {
                mDimension = Bitmap.scaleFromDensity(mDimension, sourceDensity, targetDensity);
            }
        }

        @Override
        public InsetValue clone() {
            return new InsetValue(mFraction, mDimension);
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


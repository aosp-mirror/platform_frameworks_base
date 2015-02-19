/*
 * Copyright (C) 2006 The Android Open Source Project
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
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Insets;
import android.graphics.NinePatch;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuff.Mode;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.Region;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.LayoutDirection;
import android.util.TypedValue;

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;

/**
 *
 * A resizeable bitmap, with stretchable areas that you define. This type of image
 * is defined in a .png file with a special format.
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about how to use a NinePatchDrawable, read the
 * <a href="{@docRoot}guide/topics/graphics/2d-graphics.html#nine-patch">
 * Canvas and Drawables</a> developer guide. For information about creating a NinePatch image
 * file using the draw9patch tool, see the
 * <a href="{@docRoot}guide/developing/tools/draw9patch.html">Draw 9-patch</a> tool guide.</p></div>
 */
public class NinePatchDrawable extends Drawable {
    // dithering helps a lot, and is pretty cheap, so default is true
    private static final boolean DEFAULT_DITHER = false;
    private NinePatchState mNinePatchState;
    private NinePatch mNinePatch;
    private PorterDuffColorFilter mTintFilter;
    private Rect mPadding;
    private Insets mOpticalInsets = Insets.NONE;
    private Paint mPaint;
    private boolean mMutated;

    private int mTargetDensity = DisplayMetrics.DENSITY_DEFAULT;

    // These are scaled to match the target density.
    private int mBitmapWidth = -1;
    private int mBitmapHeight = -1;

    NinePatchDrawable() {
        mNinePatchState = new NinePatchState();
    }

    /**
     * Create drawable from raw nine-patch data, not dealing with density.
     * @deprecated Use {@link #NinePatchDrawable(Resources, Bitmap, byte[], Rect, String)}
     * to ensure that the drawable has correctly set its target density.
     */
    @Deprecated
    public NinePatchDrawable(Bitmap bitmap, byte[] chunk, Rect padding, String srcName) {
        this(new NinePatchState(new NinePatch(bitmap, chunk, srcName), padding), null);
    }

    /**
     * Create drawable from raw nine-patch data, setting initial target density
     * based on the display metrics of the resources.
     */
    public NinePatchDrawable(Resources res, Bitmap bitmap, byte[] chunk,
            Rect padding, String srcName) {
        this(new NinePatchState(new NinePatch(bitmap, chunk, srcName), padding), res);
        mNinePatchState.mTargetDensity = mTargetDensity;
    }

    /**
     * Create drawable from raw nine-patch data, setting initial target density
     * based on the display metrics of the resources.
     *
     * @hide
     */
    public NinePatchDrawable(Resources res, Bitmap bitmap, byte[] chunk,
            Rect padding, Rect opticalInsets, String srcName) {
        this(new NinePatchState(new NinePatch(bitmap, chunk, srcName), padding, opticalInsets),
                res);
        mNinePatchState.mTargetDensity = mTargetDensity;
    }

    /**
     * Create drawable from existing nine-patch, not dealing with density.
     * @deprecated Use {@link #NinePatchDrawable(Resources, NinePatch)}
     * to ensure that the drawable has correctly set its target density.
     */
    @Deprecated
    public NinePatchDrawable(NinePatch patch) {
        this(new NinePatchState(patch, new Rect()), null);
    }

    /**
     * Create drawable from existing nine-patch, setting initial target density
     * based on the display metrics of the resources.
     */
    public NinePatchDrawable(Resources res, NinePatch patch) {
        this(new NinePatchState(patch, new Rect()), res);
        mNinePatchState.mTargetDensity = mTargetDensity;
    }

    /**
     * Set the density scale at which this drawable will be rendered. This
     * method assumes the drawable will be rendered at the same density as the
     * specified canvas.
     *
     * @param canvas The Canvas from which the density scale must be obtained.
     *
     * @see android.graphics.Bitmap#setDensity(int)
     * @see android.graphics.Bitmap#getDensity()
     */
    public void setTargetDensity(Canvas canvas) {
        setTargetDensity(canvas.getDensity());
    }

    /**
     * Set the density scale at which this drawable will be rendered.
     *
     * @param metrics The DisplayMetrics indicating the density scale for this drawable.
     *
     * @see android.graphics.Bitmap#setDensity(int)
     * @see android.graphics.Bitmap#getDensity()
     */
    public void setTargetDensity(DisplayMetrics metrics) {
        setTargetDensity(metrics.densityDpi);
    }

    /**
     * Set the density at which this drawable will be rendered.
     *
     * @param density The density scale for this drawable.
     *
     * @see android.graphics.Bitmap#setDensity(int)
     * @see android.graphics.Bitmap#getDensity()
     */
    public void setTargetDensity(int density) {
        if (density != mTargetDensity) {
            mTargetDensity = density == 0 ? DisplayMetrics.DENSITY_DEFAULT : density;
            if (mNinePatch != null) {
                computeBitmapSize();
            }
            invalidateSelf();
        }
    }

    private static Insets scaleFromDensity(Insets insets, int sdensity, int tdensity) {
        int left = Bitmap.scaleFromDensity(insets.left, sdensity, tdensity);
        int top = Bitmap.scaleFromDensity(insets.top, sdensity, tdensity);
        int right = Bitmap.scaleFromDensity(insets.right, sdensity, tdensity);
        int bottom = Bitmap.scaleFromDensity(insets.bottom, sdensity, tdensity);
        return Insets.of(left, top, right, bottom);
    }

    private void computeBitmapSize() {
        final int sdensity = mNinePatch.getDensity();
        final int tdensity = mTargetDensity;
        if (sdensity == tdensity) {
            mBitmapWidth = mNinePatch.getWidth();
            mBitmapHeight = mNinePatch.getHeight();
            mOpticalInsets = mNinePatchState.mOpticalInsets;
        } else {
            mBitmapWidth = Bitmap.scaleFromDensity(mNinePatch.getWidth(), sdensity, tdensity);
            mBitmapHeight = Bitmap.scaleFromDensity(mNinePatch.getHeight(), sdensity, tdensity);
            if (mNinePatchState.mPadding != null && mPadding != null) {
                Rect dest = mPadding;
                Rect src = mNinePatchState.mPadding;
                if (dest == src) {
                    mPadding = dest = new Rect(src);
                }
                dest.left = Bitmap.scaleFromDensity(src.left, sdensity, tdensity);
                dest.top = Bitmap.scaleFromDensity(src.top, sdensity, tdensity);
                dest.right = Bitmap.scaleFromDensity(src.right, sdensity, tdensity);
                dest.bottom = Bitmap.scaleFromDensity(src.bottom, sdensity, tdensity);
            }
            mOpticalInsets = scaleFromDensity(mNinePatchState.mOpticalInsets, sdensity, tdensity);
        }
    }

    private void setNinePatch(NinePatch ninePatch) {
        if (mNinePatch != ninePatch) {
            mNinePatch = ninePatch;
            if (ninePatch != null) {
                computeBitmapSize();
            } else {
                mBitmapWidth = mBitmapHeight = -1;
                mOpticalInsets = Insets.NONE;
            }
            invalidateSelf();
        }
    }

    @Override
    public void draw(Canvas canvas) {
        final Rect bounds = getBounds();

        final boolean clearColorFilter;
        if (mTintFilter != null && getPaint().getColorFilter() == null) {
            mPaint.setColorFilter(mTintFilter);
            clearColorFilter = true;
        } else {
            clearColorFilter = false;
        }

        final boolean needsMirroring = needsMirroring();
        if (needsMirroring) {
            // Mirror the 9patch
            canvas.translate(bounds.right - bounds.left, 0);
            canvas.scale(-1.0f, 1.0f);
        }

        final int restoreAlpha;
        if (mNinePatchState.mBaseAlpha != 1.0f) {
            restoreAlpha = mPaint.getAlpha();
            mPaint.setAlpha((int) (restoreAlpha * mNinePatchState.mBaseAlpha + 0.5f));
        } else {
            restoreAlpha = -1;
        }

        mNinePatch.draw(canvas, bounds, mPaint);

        if (clearColorFilter) {
            mPaint.setColorFilter(null);
        }

        if (restoreAlpha >= 0) {
            mPaint.setAlpha(restoreAlpha);
        }
    }

    @Override
    public int getChangingConfigurations() {
        return super.getChangingConfigurations() | mNinePatchState.mChangingConfigurations;
    }

    @Override
    public boolean getPadding(Rect padding) {
        final Rect scaledPadding = mPadding;
        if (scaledPadding != null) {
            if (needsMirroring()) {
                padding.set(scaledPadding.right, scaledPadding.top,
                        scaledPadding.left, scaledPadding.bottom);
            } else {
                padding.set(scaledPadding);
            }
            return (padding.left | padding.top | padding.right | padding.bottom) != 0;
        }
        return false;
    }

    @Override
    public void getOutline(@NonNull Outline outline) {
        final Rect bounds = getBounds();
        if (bounds.isEmpty()) return;

        if (mNinePatchState != null) {
            NinePatch.InsetStruct insets = mNinePatchState.mNinePatch.getBitmap().getNinePatchInsets();
            if (insets != null) {
                final Rect outlineInsets = insets.outlineRect;
                outline.setRoundRect(bounds.left + outlineInsets.left,
                        bounds.top + outlineInsets.top,
                        bounds.right - outlineInsets.right,
                        bounds.bottom - outlineInsets.bottom,
                        insets.outlineRadius);
                outline.setAlpha(insets.outlineAlpha * (getAlpha() / 255.0f));
                return;
            }
        }
        super.getOutline(outline);
    }

    /**
     * @hide
     */
    @Override
    public Insets getOpticalInsets() {
        if (needsMirroring()) {
            return Insets.of(mOpticalInsets.right, mOpticalInsets.top,
                    mOpticalInsets.left, mOpticalInsets.bottom);
        } else {
            return mOpticalInsets;
        }
    }

    @Override
    public void setAlpha(int alpha) {
        if (mPaint == null && alpha == 0xFF) {
            // Fast common case -- leave at normal alpha.
            return;
        }
        getPaint().setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public int getAlpha() {
        if (mPaint == null) {
            // Fast common case -- normal alpha.
            return 0xFF;
        }
        return getPaint().getAlpha();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        if (mPaint == null && cf == null) {
            // Fast common case -- leave at no color filter.
            return;
        }
        getPaint().setColorFilter(cf);
        invalidateSelf();
    }

    @Override
    public void setTintList(ColorStateList tint) {
        mNinePatchState.mTint = tint;
        mTintFilter = updateTintFilter(mTintFilter, tint, mNinePatchState.mTintMode);
        invalidateSelf();
    }

    @Override
    public void setTintMode(PorterDuff.Mode tintMode) {
        mNinePatchState.mTintMode = tintMode;
        mTintFilter = updateTintFilter(mTintFilter, mNinePatchState.mTint, tintMode);
        invalidateSelf();
    }

    @Override
    public void setDither(boolean dither) {
        //noinspection PointlessBooleanExpression
        if (mPaint == null && dither == DEFAULT_DITHER) {
            // Fast common case -- leave at default dither.
            return;
        }

        getPaint().setDither(dither);
        invalidateSelf();
    }

    @Override
    public void setAutoMirrored(boolean mirrored) {
        mNinePatchState.mAutoMirrored = mirrored;
    }

    private boolean needsMirroring() {
        return isAutoMirrored() && getLayoutDirection() == LayoutDirection.RTL;
    }

    @Override
    public boolean isAutoMirrored() {
        return mNinePatchState.mAutoMirrored;
    }

    @Override
    public void setFilterBitmap(boolean filter) {
        getPaint().setFilterBitmap(filter);
        invalidateSelf();
    }

    @Override
    public void inflate(Resources r, XmlPullParser parser, AttributeSet attrs, Theme theme)
            throws XmlPullParserException, IOException {
        super.inflate(r, parser, attrs, theme);

        final TypedArray a = obtainAttributes(r, theme, attrs, R.styleable.NinePatchDrawable);
        updateStateFromTypedArray(a);
        a.recycle();
    }

    /**
     * Updates the constant state from the values in the typed array.
     */
    private void updateStateFromTypedArray(TypedArray a) throws XmlPullParserException {
        final Resources r = a.getResources();
        final NinePatchState state = mNinePatchState;

        // Account for any configuration changes.
        state.mChangingConfigurations |= a.getChangingConfigurations();

        // Extract the theme attributes, if any.
        state.mThemeAttrs = a.extractThemeAttrs();

        state.mDither = a.getBoolean(R.styleable.NinePatchDrawable_dither, state.mDither);

        final int srcResId = a.getResourceId(R.styleable.NinePatchDrawable_src, 0);
        if (srcResId != 0) {
            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inDither = !state.mDither;
            options.inScreenDensity = r.getDisplayMetrics().noncompatDensityDpi;

            final Rect padding = new Rect();
            final Rect opticalInsets = new Rect();
            Bitmap bitmap = null;

            try {
                final TypedValue value = new TypedValue();
                final InputStream is = r.openRawResource(srcResId, value);

                bitmap = BitmapFactory.decodeResourceStream(r, value, is, padding, options);

                is.close();
            } catch (IOException e) {
                // Ignore
            }

            if (bitmap == null) {
                throw new XmlPullParserException(a.getPositionDescription() +
                        ": <nine-patch> requires a valid src attribute");
            } else if (bitmap.getNinePatchChunk() == null) {
                throw new XmlPullParserException(a.getPositionDescription() +
                        ": <nine-patch> requires a valid 9-patch source image");
            }

            bitmap.getOpticalInsets(opticalInsets);

            state.mNinePatch = new NinePatch(bitmap, bitmap.getNinePatchChunk());
            state.mPadding = padding;
            state.mOpticalInsets = Insets.of(opticalInsets);
        }

        state.mAutoMirrored = a.getBoolean(
                R.styleable.NinePatchDrawable_autoMirrored, state.mAutoMirrored);
        state.mBaseAlpha = a.getFloat(R.styleable.NinePatchDrawable_alpha, state.mBaseAlpha);

        final int tintMode = a.getInt(R.styleable.NinePatchDrawable_tintMode, -1);
        if (tintMode != -1) {
            state.mTintMode = Drawable.parseTintMode(tintMode, Mode.SRC_IN);
        }

        final ColorStateList tint = a.getColorStateList(R.styleable.NinePatchDrawable_tint);
        if (tint != null) {
            state.mTint = tint;
        }

        // Update local properties.
        initializeWithState(state, r);

        // Push density applied by setNinePatchState into state.
        state.mTargetDensity = mTargetDensity;
    }

    @Override
    public void applyTheme(Theme t) {
        super.applyTheme(t);

        final NinePatchState state = mNinePatchState;
        if (state == null || state.mThemeAttrs == null) {
            return;
        }

        final TypedArray a = t.resolveAttributes(state.mThemeAttrs, R.styleable.NinePatchDrawable);
        try {
            updateStateFromTypedArray(a);
        } catch (XmlPullParserException e) {
            throw new RuntimeException(e);
        } finally {
            a.recycle();
        }
    }

    @Override
    public boolean canApplyTheme() {
        return mNinePatchState != null && mNinePatchState.mThemeAttrs != null;
    }

    public Paint getPaint() {
        if (mPaint == null) {
            mPaint = new Paint();
            mPaint.setDither(DEFAULT_DITHER);
        }
        return mPaint;
    }

    /**
     * Retrieves the width of the source .png file (before resizing).
     */
    @Override
    public int getIntrinsicWidth() {
        return mBitmapWidth;
    }

    /**
     * Retrieves the height of the source .png file (before resizing).
     */
    @Override
    public int getIntrinsicHeight() {
        return mBitmapHeight;
    }

    @Override
    public int getMinimumWidth() {
        return mBitmapWidth;
    }

    @Override
    public int getMinimumHeight() {
        return mBitmapHeight;
    }

    /**
     * Returns a {@link android.graphics.PixelFormat graphics.PixelFormat}
     * value of OPAQUE or TRANSLUCENT.
     */
    @Override
    public int getOpacity() {
        return mNinePatch.hasAlpha() || (mPaint != null && mPaint.getAlpha() < 255) ?
                PixelFormat.TRANSLUCENT : PixelFormat.OPAQUE;
    }

    @Override
    public Region getTransparentRegion() {
        return mNinePatch.getTransparentRegion(getBounds());
    }

    @Override
    public ConstantState getConstantState() {
        mNinePatchState.mChangingConfigurations = getChangingConfigurations();
        return mNinePatchState;
    }

    @Override
    public Drawable mutate() {
        if (!mMutated && super.mutate() == this) {
            mNinePatchState = new NinePatchState(mNinePatchState);
            mNinePatch = mNinePatchState.mNinePatch;
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
    protected boolean onStateChange(int[] stateSet) {
        final NinePatchState state = mNinePatchState;
        if (state.mTint != null && state.mTintMode != null) {
            mTintFilter = updateTintFilter(mTintFilter, state.mTint, state.mTintMode);
            return true;
        }

        return false;
    }

    @Override
    public boolean isStateful() {
        final NinePatchState s = mNinePatchState;
        return super.isStateful() || (s.mTint != null && s.mTint.isStateful());
    }

    final static class NinePatchState extends ConstantState {
        // Values loaded during inflation.
        int[] mThemeAttrs = null;
        NinePatch mNinePatch = null;
        ColorStateList mTint = null;
        Mode mTintMode = DEFAULT_TINT_MODE;
        Rect mPadding = null;
        Insets mOpticalInsets = Insets.NONE;
        float mBaseAlpha = 1.0f;
        boolean mDither = DEFAULT_DITHER;
        int mTargetDensity = DisplayMetrics.DENSITY_DEFAULT;
        boolean mAutoMirrored = false;

        int mChangingConfigurations;

        NinePatchState() {
            // Empty constructor.
        }

        NinePatchState(@NonNull NinePatch ninePatch, @Nullable Rect padding) {
            this(ninePatch, padding, null, DEFAULT_DITHER, false);
        }

        NinePatchState(@NonNull NinePatch ninePatch, @Nullable Rect padding,
                @Nullable Rect opticalInsets) {
            this(ninePatch, padding, opticalInsets, DEFAULT_DITHER, false);
        }

        NinePatchState(@NonNull NinePatch ninePatch, @Nullable Rect padding,
                @Nullable Rect opticalInsets, boolean dither, boolean autoMirror) {
            mNinePatch = ninePatch;
            mPadding = padding;
            mOpticalInsets = Insets.of(opticalInsets);
            mDither = dither;
            mAutoMirrored = autoMirror;
        }

        // Copy constructor

        NinePatchState(@NonNull NinePatchState state) {
            // We don't deep-copy any fields because they are all immutable.
            mNinePatch = state.mNinePatch;
            mTint = state.mTint;
            mTintMode = state.mTintMode;
            mThemeAttrs = state.mThemeAttrs;
            mPadding = state.mPadding;
            mOpticalInsets = state.mOpticalInsets;
            mBaseAlpha = state.mBaseAlpha;
            mDither = state.mDither;
            mChangingConfigurations = state.mChangingConfigurations;
            mTargetDensity = state.mTargetDensity;
            mAutoMirrored = state.mAutoMirrored;
        }

        @Override
        public boolean canApplyTheme() {
            return mThemeAttrs != null;
        }

        @Override
        public int addAtlasableBitmaps(Collection<Bitmap> atlasList) {
            final Bitmap bitmap = mNinePatch.getBitmap();
            if (isAtlasable(bitmap) && atlasList.add(bitmap)) {
                return bitmap.getWidth() * bitmap.getHeight();
            }
            return 0;
        }

        @Override
        public Drawable newDrawable() {
            return new NinePatchDrawable(this, null);
        }

        @Override
        public Drawable newDrawable(Resources res) {
            return new NinePatchDrawable(this, res);
        }

        @Override
        public int getChangingConfigurations() {
            return mChangingConfigurations;
        }
    }

    /**
     * The one constructor to rule them all. This is called by all public
     * constructors to set the state and initialize local properties.
     */
    private NinePatchDrawable(NinePatchState state, Resources res) {
        mNinePatchState = state;

        initializeWithState(mNinePatchState, res);
    }

    /**
     * Initializes local dynamic properties from state.
     */
    private void initializeWithState(NinePatchState state, Resources res) {
        if (res != null) {
            mTargetDensity = res.getDisplayMetrics().densityDpi;
        } else {
            mTargetDensity = state.mTargetDensity;
        }

        // If we can, avoid calling any methods that initialize Paint.
        if (state.mDither != DEFAULT_DITHER) {
            setDither(state.mDither);
        }

        // Make a local copy of the padding.
        if (state.mPadding != null) {
            mPadding = new Rect(state.mPadding);
        }

        mTintFilter = updateTintFilter(mTintFilter, state.mTint, state.mTintMode);
        setNinePatch(state.mNinePatch);
    }
}

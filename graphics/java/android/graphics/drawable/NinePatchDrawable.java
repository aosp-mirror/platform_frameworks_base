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
import android.content.pm.ActivityInfo.Config;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ImageDecoder;
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

    /** Temporary rect used for density scaling. */
    private Rect mTempRect;

    private NinePatchState mNinePatchState;
    private PorterDuffColorFilter mTintFilter;
    private Rect mPadding;
    private Insets mOpticalInsets = Insets.NONE;
    private Rect mOutlineInsets;
    private float mOutlineRadius;
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
     *
     * @deprecated Use {@link #NinePatchDrawable(Resources, Bitmap, byte[], Rect, String)}
     *             to ensure that the drawable has correctly set its target density.
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
    }

    /**
     * Create drawable from existing nine-patch, not dealing with density.
     *
     * @deprecated Use {@link #NinePatchDrawable(Resources, NinePatch)}
     *             to ensure that the drawable has correctly set its target
     *             density.
     */
    @Deprecated
    public NinePatchDrawable(@NonNull NinePatch patch) {
        this(new NinePatchState(patch, new Rect()), null);
    }

    /**
     * Create drawable from existing nine-patch, setting initial target density
     * based on the display metrics of the resources.
     */
    public NinePatchDrawable(@Nullable Resources res, @NonNull NinePatch patch) {
        this(new NinePatchState(patch, new Rect()), res);
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
    public void setTargetDensity(@NonNull Canvas canvas) {
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
    public void setTargetDensity(@NonNull DisplayMetrics metrics) {
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
        if (density == 0) {
            density = DisplayMetrics.DENSITY_DEFAULT;
        }

        if (mTargetDensity != density) {
            mTargetDensity = density;

            computeBitmapSize();
            invalidateSelf();
        }
    }

    @Override
    public void draw(Canvas canvas) {
        final NinePatchState state = mNinePatchState;

        Rect bounds = getBounds();
        int restoreToCount = -1;

        final boolean clearColorFilter;
        if (mTintFilter != null && getPaint().getColorFilter() == null) {
            mPaint.setColorFilter(mTintFilter);
            clearColorFilter = true;
        } else {
            clearColorFilter = false;
        }

        final int restoreAlpha;
        if (state.mBaseAlpha != 1.0f) {
            restoreAlpha = getPaint().getAlpha();
            mPaint.setAlpha((int) (restoreAlpha * state.mBaseAlpha + 0.5f));
        } else {
            restoreAlpha = -1;
        }

        final boolean needsDensityScaling = canvas.getDensity() == 0
                && Bitmap.DENSITY_NONE != state.mNinePatch.getDensity();
        if (needsDensityScaling) {
            restoreToCount = restoreToCount >= 0 ? restoreToCount : canvas.save();

            // Apply density scaling.
            final float scale = mTargetDensity / (float) state.mNinePatch.getDensity();
            final float px = bounds.left;
            final float py = bounds.top;
            canvas.scale(scale, scale, px, py);

            if (mTempRect == null) {
                mTempRect = new Rect();
            }

            // Scale the bounds to match.
            final Rect scaledBounds = mTempRect;
            scaledBounds.left = bounds.left;
            scaledBounds.top = bounds.top;
            scaledBounds.right = bounds.left + Math.round(bounds.width() / scale);
            scaledBounds.bottom = bounds.top + Math.round(bounds.height() / scale);
            bounds = scaledBounds;
        }

        final boolean needsMirroring = needsMirroring();
        if (needsMirroring) {
            restoreToCount = restoreToCount >= 0 ? restoreToCount : canvas.save();

            // Mirror the 9patch.
            final float cx = (bounds.left + bounds.right) / 2.0f;
            final float cy = (bounds.top + bounds.bottom) / 2.0f;
            canvas.scale(-1.0f, 1.0f, cx, cy);
        }

        state.mNinePatch.draw(canvas, bounds, mPaint);

        if (restoreToCount >= 0) {
            canvas.restoreToCount(restoreToCount);
        }

        if (clearColorFilter) {
            mPaint.setColorFilter(null);
        }

        if (restoreAlpha >= 0) {
            mPaint.setAlpha(restoreAlpha);
        }
    }

    @Override
    public @Config int getChangingConfigurations() {
        return super.getChangingConfigurations() | mNinePatchState.getChangingConfigurations();
    }

    @Override
    public boolean getPadding(@NonNull Rect padding) {
        if (mPadding != null) {
            padding.set(mPadding);
            return (padding.left | padding.top | padding.right | padding.bottom) != 0;
        } else {
            return super.getPadding(padding);
        }
    }

    @Override
    public void getOutline(@NonNull Outline outline) {
        final Rect bounds = getBounds();
        if (bounds.isEmpty()) {
            return;
        }

        if (mNinePatchState != null && mOutlineInsets != null) {
            final NinePatch.InsetStruct insets =
                    mNinePatchState.mNinePatch.getBitmap().getNinePatchInsets();
            if (insets != null) {
                outline.setRoundRect(bounds.left + mOutlineInsets.left,
                        bounds.top + mOutlineInsets.top,
                        bounds.right - mOutlineInsets.right,
                        bounds.bottom - mOutlineInsets.bottom,
                        mOutlineRadius);
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
        final Insets opticalInsets = mOpticalInsets;
        if (needsMirroring()) {
            return Insets.of(opticalInsets.right, opticalInsets.top,
                    opticalInsets.left, opticalInsets.bottom);
        } else {
            return opticalInsets;
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
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        if (mPaint == null && colorFilter == null) {
            // Fast common case -- leave at no color filter.
            return;
        }
        getPaint().setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public void setTintList(@Nullable ColorStateList tint) {
        mNinePatchState.mTint = tint;
        mTintFilter = updateTintFilter(mTintFilter, tint, mNinePatchState.mTintMode);
        invalidateSelf();
    }

    @Override
    public void setTintMode(@Nullable PorterDuff.Mode tintMode) {
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
    public boolean isFilterBitmap() {
        return mPaint != null && getPaint().isFilterBitmap();
    }

    @Override
    public void inflate(Resources r, XmlPullParser parser, AttributeSet attrs, Theme theme)
            throws XmlPullParserException, IOException {
        super.inflate(r, parser, attrs, theme);

        final TypedArray a = obtainAttributes(r, theme, attrs, R.styleable.NinePatchDrawable);
        updateStateFromTypedArray(a);
        a.recycle();

        updateLocalState(r);
    }

    /**
     * Updates the constant state from the values in the typed array.
     */
    private void updateStateFromTypedArray(@NonNull TypedArray a) throws XmlPullParserException {
        final Resources r = a.getResources();
        final NinePatchState state = mNinePatchState;

        // Account for any configuration changes.
        state.mChangingConfigurations |= a.getChangingConfigurations();

        // Extract the theme attributes, if any.
        state.mThemeAttrs = a.extractThemeAttrs();

        state.mDither = a.getBoolean(R.styleable.NinePatchDrawable_dither, state.mDither);

        final int srcResId = a.getResourceId(R.styleable.NinePatchDrawable_src, 0);
        if (srcResId != 0) {
            final Rect padding = new Rect();
            final Rect opticalInsets = new Rect();
            Bitmap bitmap = null;

            try {
                final TypedValue value = new TypedValue();
                final InputStream is = r.openRawResource(srcResId, value);

                int density = Bitmap.DENSITY_NONE;
                if (value.density == TypedValue.DENSITY_DEFAULT) {
                    density = DisplayMetrics.DENSITY_DEFAULT;
                } else if (value.density != TypedValue.DENSITY_NONE) {
                    density = value.density;
                }
                ImageDecoder.Source source = ImageDecoder.createSource(r, is, density);
                bitmap = ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                    decoder.setOutPaddingRect(padding);
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });

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
    }

    @Override
    public void applyTheme(@NonNull Theme t) {
        super.applyTheme(t);

        final NinePatchState state = mNinePatchState;
        if (state == null) {
            return;
        }

        if (state.mThemeAttrs != null) {
            final TypedArray a = t.resolveAttributes(
                    state.mThemeAttrs, R.styleable.NinePatchDrawable);
            try {
                updateStateFromTypedArray(a);
            } catch (XmlPullParserException e) {
                rethrowAsRuntimeException(e);
            } finally {
                a.recycle();
            }
        }

        if (state.mTint != null && state.mTint.canApplyTheme()) {
            state.mTint = state.mTint.obtainForTheme(t);
        }

        updateLocalState(t.getResources());
    }

    @Override
    public boolean canApplyTheme() {
        return mNinePatchState != null && mNinePatchState.canApplyTheme();
    }

    @NonNull
    public Paint getPaint() {
        if (mPaint == null) {
            mPaint = new Paint();
            mPaint.setDither(DEFAULT_DITHER);
        }
        return mPaint;
    }

    @Override
    public int getIntrinsicWidth() {
        return mBitmapWidth;
    }

    @Override
    public int getIntrinsicHeight() {
        return mBitmapHeight;
    }

    @Override
    public int getOpacity() {
        return mNinePatchState.mNinePatch.hasAlpha()
                || (mPaint != null && mPaint.getAlpha() < 255) ?
                        PixelFormat.TRANSLUCENT : PixelFormat.OPAQUE;
    }

    @Override
    public Region getTransparentRegion() {
        return mNinePatchState.mNinePatch.getTransparentRegion(getBounds());
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

    /** @hide */
    @Override
    public boolean hasFocusStateSpecified() {
        return mNinePatchState.mTint != null && mNinePatchState.mTint.hasFocusStateSpecified();
    }

    final static class NinePatchState extends ConstantState {
        @Config int mChangingConfigurations;

        // Values loaded during inflation.
        NinePatch mNinePatch = null;
        ColorStateList mTint = null;
        Mode mTintMode = DEFAULT_TINT_MODE;
        Rect mPadding = null;
        Insets mOpticalInsets = Insets.NONE;
        float mBaseAlpha = 1.0f;
        boolean mDither = DEFAULT_DITHER;
        boolean mAutoMirrored = false;

        int[] mThemeAttrs;

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

        NinePatchState(@NonNull NinePatchState orig) {
            mChangingConfigurations = orig.mChangingConfigurations;
            mNinePatch = orig.mNinePatch;
            mTint = orig.mTint;
            mTintMode = orig.mTintMode;
            mPadding = orig.mPadding;
            mOpticalInsets = orig.mOpticalInsets;
            mBaseAlpha = orig.mBaseAlpha;
            mDither = orig.mDither;
            mAutoMirrored = orig.mAutoMirrored;
            mThemeAttrs = orig.mThemeAttrs;
        }

        @Override
        public boolean canApplyTheme() {
            return mThemeAttrs != null
                    || (mTint != null && mTint.canApplyTheme())
                    || super.canApplyTheme();
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
        public @Config int getChangingConfigurations() {
            return mChangingConfigurations
                    | (mTint != null ? mTint.getChangingConfigurations() : 0);
        }
    }

    private void computeBitmapSize() {
        final NinePatch ninePatch = mNinePatchState.mNinePatch;
        if (ninePatch == null) {
            return;
        }

        final int targetDensity = mTargetDensity;
        final int sourceDensity = ninePatch.getDensity() == Bitmap.DENSITY_NONE ?
            targetDensity : ninePatch.getDensity();

        final Insets sourceOpticalInsets = mNinePatchState.mOpticalInsets;
        if (sourceOpticalInsets != Insets.NONE) {
            final int left = Drawable.scaleFromDensity(
                    sourceOpticalInsets.left, sourceDensity, targetDensity, true);
            final int top = Drawable.scaleFromDensity(
                    sourceOpticalInsets.top, sourceDensity, targetDensity, true);
            final int right = Drawable.scaleFromDensity(
                    sourceOpticalInsets.right, sourceDensity, targetDensity, true);
            final int bottom = Drawable.scaleFromDensity(
                    sourceOpticalInsets.bottom, sourceDensity, targetDensity, true);
            mOpticalInsets = Insets.of(left, top, right, bottom);
        } else {
            mOpticalInsets = Insets.NONE;
        }

        final Rect sourcePadding = mNinePatchState.mPadding;
        if (sourcePadding != null) {
            if (mPadding == null) {
                mPadding = new Rect();
            }
            mPadding.left = Drawable.scaleFromDensity(
                    sourcePadding.left, sourceDensity, targetDensity, true);
            mPadding.top = Drawable.scaleFromDensity(
                    sourcePadding.top, sourceDensity, targetDensity, true);
            mPadding.right = Drawable.scaleFromDensity(
                    sourcePadding.right, sourceDensity, targetDensity, true);
            mPadding.bottom = Drawable.scaleFromDensity(
                    sourcePadding.bottom, sourceDensity, targetDensity, true);
        } else {
            mPadding = null;
        }

        mBitmapHeight = Drawable.scaleFromDensity(
                ninePatch.getHeight(), sourceDensity, targetDensity, true);
        mBitmapWidth = Drawable.scaleFromDensity(
                ninePatch.getWidth(), sourceDensity, targetDensity, true);

        final NinePatch.InsetStruct insets = ninePatch.getBitmap().getNinePatchInsets();
        if (insets != null) {
            Rect outlineRect = insets.outlineRect;
            mOutlineInsets = NinePatch.InsetStruct.scaleInsets(outlineRect.left, outlineRect.top,
                    outlineRect.right, outlineRect.bottom, targetDensity / (float) sourceDensity);
            mOutlineRadius = Drawable.scaleFromDensity(
                    insets.outlineRadius, sourceDensity, targetDensity);
        } else {
            mOutlineInsets = null;
        }
    }

    /**
     * The one constructor to rule them all. This is called by all public
     * constructors to set the state and initialize local properties.
     *
     * @param state constant state to assign to the new drawable
     */
    private NinePatchDrawable(@NonNull NinePatchState state, @Nullable Resources res) {
        mNinePatchState = state;

        updateLocalState(res);
    }

    /**
     * Initializes local dynamic properties from state.
     */
    private void updateLocalState(@Nullable Resources res) {
        final NinePatchState state = mNinePatchState;

        // If we can, avoid calling any methods that initialize Paint.
        if (state.mDither != DEFAULT_DITHER) {
            setDither(state.mDither);
        }

        // The nine-patch may have been created without a Resources object, in
        // which case we should try to match the density of the nine patch (if
        // available).
        if (res == null && state.mNinePatch != null) {
            mTargetDensity = state.mNinePatch.getDensity();
        } else {
            mTargetDensity = Drawable.resolveDensity(res, mTargetDensity);
        }
        mTintFilter = updateTintFilter(mTintFilter, state.mTint, state.mTintMode);
        computeBitmapSize();
    }
}

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
import android.compat.annotation.UnsupportedAppUsage;
import android.content.pm.ActivityInfo.Config;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlendMode;
import android.graphics.BlendModeColorFilter;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ImageDecoder;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff.Mode;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Xfermode;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.LayoutDirection;
import android.util.TypedValue;
import android.view.Gravity;

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * A Drawable that wraps a bitmap and can be tiled, stretched, or aligned. You can create a
 * BitmapDrawable from a file path, an input stream, through XML inflation, or from
 * a {@link android.graphics.Bitmap} object.
 * <p>It can be defined in an XML file with the <code>&lt;bitmap></code> element.  For more
 * information, see the guide to <a
 * href="{@docRoot}guide/topics/resources/drawable-resource.html">Drawable Resources</a>.</p>
 * <p>
 * Also see the {@link android.graphics.Bitmap} class, which handles the management and
 * transformation of raw bitmap graphics, and should be used when drawing to a
 * {@link android.graphics.Canvas}.
 * </p>
 *
 * @attr ref android.R.styleable#BitmapDrawable_src
 * @attr ref android.R.styleable#BitmapDrawable_antialias
 * @attr ref android.R.styleable#BitmapDrawable_filter
 * @attr ref android.R.styleable#BitmapDrawable_dither
 * @attr ref android.R.styleable#BitmapDrawable_gravity
 * @attr ref android.R.styleable#BitmapDrawable_mipMap
 * @attr ref android.R.styleable#BitmapDrawable_tileMode
 */
public class BitmapDrawable extends Drawable {
    private static final int DEFAULT_PAINT_FLAGS =
            Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG;

    // Constants for {@link android.R.styleable#BitmapDrawable_tileMode}.
    private static final int TILE_MODE_UNDEFINED = -2;
    private static final int TILE_MODE_DISABLED = -1;
    private static final int TILE_MODE_CLAMP = 0;
    private static final int TILE_MODE_REPEAT = 1;
    private static final int TILE_MODE_MIRROR = 2;

    private final Rect mDstRect = new Rect();   // #updateDstRectAndInsetsIfDirty() sets this

    @UnsupportedAppUsage
    private BitmapState mBitmapState;
    private BlendModeColorFilter mBlendModeFilter;

    @UnsupportedAppUsage
    private int mTargetDensity = DisplayMetrics.DENSITY_DEFAULT;

    private boolean mDstRectAndInsetsDirty = true;
    private boolean mMutated;

     // These are scaled to match the target density.
    private int mBitmapWidth;
    private int mBitmapHeight;

    /** Optical insets due to gravity. */
    private Insets mOpticalInsets = Insets.NONE;

    // Mirroring matrix for using with Shaders
    private Matrix mMirrorMatrix;

    /**
     * Create an empty drawable, not dealing with density.
     * @deprecated Use {@link #BitmapDrawable(android.content.res.Resources, android.graphics.Bitmap)}
     * instead to specify a bitmap to draw with and ensure the correct density is set.
     */
    @Deprecated
    public BitmapDrawable() {
        init(new BitmapState((Bitmap) null), null);
    }

    /**
     * Create an empty drawable, setting initial target density based on
     * the display metrics of the resources.
     *
     * @deprecated Use {@link #BitmapDrawable(android.content.res.Resources, android.graphics.Bitmap)}
     * instead to specify a bitmap to draw with.
     */
    @SuppressWarnings("unused")
    @Deprecated
    public BitmapDrawable(Resources res) {
        init(new BitmapState((Bitmap) null), res);
    }

    /**
     * Create drawable from a bitmap, not dealing with density.
     * @deprecated Use {@link #BitmapDrawable(Resources, Bitmap)} to ensure
     * that the drawable has correctly set its target density.
     */
    @Deprecated
    public BitmapDrawable(Bitmap bitmap) {
        init(new BitmapState(bitmap), null);
    }

    /**
     * Create drawable from a bitmap, setting initial target density based on
     * the display metrics of the resources.
     */
    public BitmapDrawable(Resources res, Bitmap bitmap) {
        init(new BitmapState(bitmap), res);
    }

    /**
     * Create a drawable by opening a given file path and decoding the bitmap.
     * @deprecated Use {@link #BitmapDrawable(Resources, String)} to ensure
     * that the drawable has correctly set its target density.
     */
    @Deprecated
    public BitmapDrawable(String filepath) {
        this(null, filepath);
    }

    /**
     * Create a drawable by opening a given file path and decoding the bitmap.
     */
    @SuppressWarnings({ "unused", "ChainingConstructorIgnoresParameter" })
    public BitmapDrawable(Resources res, String filepath) {
        Bitmap bitmap = null;
        try (FileInputStream stream = new FileInputStream(filepath)) {
            bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(res, stream),
                    (decoder, info, src) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
        } catch (Exception e) {
            /*  do nothing. This matches the behavior of BitmapFactory.decodeFile()
                If the exception happened on decode, mBitmapState.mBitmap will be null.
            */
        } finally {
            init(new BitmapState(bitmap), res);
            if (mBitmapState.mBitmap == null) {
                android.util.Log.w("BitmapDrawable", "BitmapDrawable cannot decode " + filepath);
            }
        }
    }

    /**
     * Create a drawable by decoding a bitmap from the given input stream.
     * @deprecated Use {@link #BitmapDrawable(Resources, java.io.InputStream)} to ensure
     * that the drawable has correctly set its target density.
     */
    @Deprecated
    public BitmapDrawable(java.io.InputStream is) {
        this(null, is);
    }

    /**
     * Create a drawable by decoding a bitmap from the given input stream.
     */
    @SuppressWarnings({ "unused", "ChainingConstructorIgnoresParameter" })
    public BitmapDrawable(Resources res, java.io.InputStream is) {
        Bitmap bitmap = null;
        try {
            bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(res, is),
                    (decoder, info, src) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
        } catch (Exception e) {
            /*  do nothing. This matches the behavior of BitmapFactory.decodeStream()
                If the exception happened on decode, mBitmapState.mBitmap will be null.
            */
        } finally {
            init(new BitmapState(bitmap), res);
            if (mBitmapState.mBitmap == null) {
                android.util.Log.w("BitmapDrawable", "BitmapDrawable cannot decode " + is);
            }
        }
    }

    /**
     * Returns the paint used to render this drawable.
     */
    public final Paint getPaint() {
        return mBitmapState.mPaint;
    }

    /**
     * Returns the bitmap used by this drawable to render. May be null.
     */
    public final Bitmap getBitmap() {
        return mBitmapState.mBitmap;
    }

    private void computeBitmapSize() {
        final Bitmap bitmap = mBitmapState.mBitmap;
        if (bitmap != null) {
            mBitmapWidth = bitmap.getScaledWidth(mTargetDensity);
            mBitmapHeight = bitmap.getScaledHeight(mTargetDensity);
        } else {
            mBitmapWidth = mBitmapHeight = -1;
        }
    }

    /** @hide */
    @UnsupportedAppUsage
    public void setBitmap(Bitmap bitmap) {
        if (mBitmapState.mBitmap != bitmap) {
            mBitmapState.mBitmap = bitmap;
            computeBitmapSize();
            invalidateSelf();
        }
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
        if (mTargetDensity != density) {
            mTargetDensity = density == 0 ? DisplayMetrics.DENSITY_DEFAULT : density;
            if (mBitmapState.mBitmap != null) {
                computeBitmapSize();
            }
            invalidateSelf();
        }
    }

    /** Get the gravity used to position/stretch the bitmap within its bounds.
     * See android.view.Gravity
     * @return the gravity applied to the bitmap
     */
    public int getGravity() {
        return mBitmapState.mGravity;
    }

    /** Set the gravity used to position/stretch the bitmap within its bounds.
        See android.view.Gravity
     * @param gravity the gravity
     */
    public void setGravity(int gravity) {
        if (mBitmapState.mGravity != gravity) {
            mBitmapState.mGravity = gravity;
            mDstRectAndInsetsDirty = true;
            invalidateSelf();
        }
    }

    /**
     * Enables or disables the mipmap hint for this drawable's bitmap.
     * See {@link Bitmap#setHasMipMap(boolean)} for more information.
     *
     * If the bitmap is null calling this method has no effect.
     *
     * @param mipMap True if the bitmap should use mipmaps, false otherwise.
     *
     * @see #hasMipMap()
     */
    public void setMipMap(boolean mipMap) {
        if (mBitmapState.mBitmap != null) {
            mBitmapState.mBitmap.setHasMipMap(mipMap);
            invalidateSelf();
        }
    }

    /**
     * Indicates whether the mipmap hint is enabled on this drawable's bitmap.
     *
     * @return True if the mipmap hint is set, false otherwise. If the bitmap
     *         is null, this method always returns false.
     *
     * @see #setMipMap(boolean)
     * @attr ref android.R.styleable#BitmapDrawable_mipMap
     */
    public boolean hasMipMap() {
        return mBitmapState.mBitmap != null && mBitmapState.mBitmap.hasMipMap();
    }

    /**
     * Enables or disables anti-aliasing for this drawable. Anti-aliasing affects
     * the edges of the bitmap only so it applies only when the drawable is rotated.
     *
     * @param aa True if the bitmap should be anti-aliased, false otherwise.
     *
     * @see #hasAntiAlias()
     */
    public void setAntiAlias(boolean aa) {
        mBitmapState.mPaint.setAntiAlias(aa);
        invalidateSelf();
    }

    /**
     * Indicates whether anti-aliasing is enabled for this drawable.
     *
     * @return True if anti-aliasing is enabled, false otherwise.
     *
     * @see #setAntiAlias(boolean)
     */
    public boolean hasAntiAlias() {
        return mBitmapState.mPaint.isAntiAlias();
    }

    @Override
    public void setFilterBitmap(boolean filter) {
        mBitmapState.mPaint.setFilterBitmap(filter);
        invalidateSelf();
    }

    @Override
    public boolean isFilterBitmap() {
        return mBitmapState.mPaint.isFilterBitmap();
    }

    @Override
    public void setDither(boolean dither) {
        mBitmapState.mPaint.setDither(dither);
        invalidateSelf();
    }

    /**
     * Indicates the repeat behavior of this drawable on the X axis.
     *
     * @return {@link android.graphics.Shader.TileMode#CLAMP} if the bitmap does not repeat,
     *         {@link android.graphics.Shader.TileMode#REPEAT} or
     *         {@link android.graphics.Shader.TileMode#MIRROR} otherwise.
     */
    public Shader.TileMode getTileModeX() {
        return mBitmapState.mTileModeX;
    }

    /**
     * Indicates the repeat behavior of this drawable on the Y axis.
     *
     * @return {@link android.graphics.Shader.TileMode#CLAMP} if the bitmap does not repeat,
     *         {@link android.graphics.Shader.TileMode#REPEAT} or
     *         {@link android.graphics.Shader.TileMode#MIRROR} otherwise.
     */
    public Shader.TileMode getTileModeY() {
        return mBitmapState.mTileModeY;
    }

    /**
     * Sets the repeat behavior of this drawable on the X axis. By default, the drawable
     * does not repeat its bitmap. Using {@link android.graphics.Shader.TileMode#REPEAT} or
     * {@link android.graphics.Shader.TileMode#MIRROR} the bitmap can be repeated (or tiled)
     * if the bitmap is smaller than this drawable.
     *
     * @param mode The repeat mode for this drawable.
     *
     * @see #setTileModeY(android.graphics.Shader.TileMode)
     * @see #setTileModeXY(android.graphics.Shader.TileMode, android.graphics.Shader.TileMode)
     * @attr ref android.R.styleable#BitmapDrawable_tileModeX
     */
    public void setTileModeX(Shader.TileMode mode) {
        setTileModeXY(mode, mBitmapState.mTileModeY);
    }

    /**
     * Sets the repeat behavior of this drawable on the Y axis. By default, the drawable
     * does not repeat its bitmap. Using {@link android.graphics.Shader.TileMode#REPEAT} or
     * {@link android.graphics.Shader.TileMode#MIRROR} the bitmap can be repeated (or tiled)
     * if the bitmap is smaller than this drawable.
     *
     * @param mode The repeat mode for this drawable.
     *
     * @see #setTileModeX(android.graphics.Shader.TileMode)
     * @see #setTileModeXY(android.graphics.Shader.TileMode, android.graphics.Shader.TileMode)
     * @attr ref android.R.styleable#BitmapDrawable_tileModeY
     */
    public final void setTileModeY(Shader.TileMode mode) {
        setTileModeXY(mBitmapState.mTileModeX, mode);
    }

    /**
     * Sets the repeat behavior of this drawable on both axis. By default, the drawable
     * does not repeat its bitmap. Using {@link android.graphics.Shader.TileMode#REPEAT} or
     * {@link android.graphics.Shader.TileMode#MIRROR} the bitmap can be repeated (or tiled)
     * if the bitmap is smaller than this drawable.
     *
     * @param xmode The X repeat mode for this drawable.
     * @param ymode The Y repeat mode for this drawable.
     *
     * @see #setTileModeX(android.graphics.Shader.TileMode)
     * @see #setTileModeY(android.graphics.Shader.TileMode)
     */
    public void setTileModeXY(Shader.TileMode xmode, Shader.TileMode ymode) {
        final BitmapState state = mBitmapState;
        if (state.mTileModeX != xmode || state.mTileModeY != ymode) {
            state.mTileModeX = xmode;
            state.mTileModeY = ymode;
            state.mRebuildShader = true;
            mDstRectAndInsetsDirty = true;
            invalidateSelf();
        }
    }

    @Override
    public void setAutoMirrored(boolean mirrored) {
        if (mBitmapState.mAutoMirrored != mirrored) {
            mBitmapState.mAutoMirrored = mirrored;
            invalidateSelf();
        }
    }

    @Override
    public final boolean isAutoMirrored() {
        return mBitmapState.mAutoMirrored;
    }

    @Override
    public @Config int getChangingConfigurations() {
        return super.getChangingConfigurations() | mBitmapState.getChangingConfigurations();
    }

    private boolean needMirroring() {
        return isAutoMirrored() && getLayoutDirection() == LayoutDirection.RTL;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        mDstRectAndInsetsDirty = true;

        final Bitmap bitmap = mBitmapState.mBitmap;
        final Shader shader = mBitmapState.mPaint.getShader();
        if (bitmap != null && shader != null) {
            updateShaderMatrix(bitmap, mBitmapState.mPaint, shader, needMirroring());
        }
    }

    @Override
    public void draw(Canvas canvas) {
        final Bitmap bitmap = mBitmapState.mBitmap;
        if (bitmap == null) {
            return;
        }

        final BitmapState state = mBitmapState;
        final Paint paint = state.mPaint;
        if (state.mRebuildShader) {
            final Shader.TileMode tmx = state.mTileModeX;
            final Shader.TileMode tmy = state.mTileModeY;
            if (tmx == null && tmy == null) {
                paint.setShader(null);
            } else {
                paint.setShader(new BitmapShader(bitmap,
                        tmx == null ? Shader.TileMode.CLAMP : tmx,
                        tmy == null ? Shader.TileMode.CLAMP : tmy));
            }

            state.mRebuildShader = false;
        }

        final int restoreAlpha;
        if (state.mBaseAlpha != 1.0f) {
            final Paint p = getPaint();
            restoreAlpha = p.getAlpha();
            p.setAlpha((int) (restoreAlpha * state.mBaseAlpha + 0.5f));
        } else {
            restoreAlpha = -1;
        }

        final boolean clearColorFilter;
        if (mBlendModeFilter != null && paint.getColorFilter() == null) {
            paint.setColorFilter(mBlendModeFilter);
            clearColorFilter = true;
        } else {
            clearColorFilter = false;
        }

        updateDstRectAndInsetsIfDirty();
        final Shader shader = paint.getShader();
        final boolean needMirroring = needMirroring();
        if (shader == null) {
            if (needMirroring) {
                canvas.save();
                // Mirror the bitmap
                canvas.translate(mDstRect.right - mDstRect.left, 0);
                canvas.scale(-1.0f, 1.0f);
            }

            canvas.drawBitmap(bitmap, null, mDstRect, paint);

            if (needMirroring) {
                canvas.restore();
            }
        } else {
            updateShaderMatrix(bitmap, paint, shader, needMirroring);
            canvas.drawRect(mDstRect, paint);
        }

        if (clearColorFilter) {
            paint.setColorFilter(null);
        }

        if (restoreAlpha >= 0) {
            paint.setAlpha(restoreAlpha);
        }
    }

    /**
     * Updates the {@code paint}'s shader matrix to be consistent with the
     * destination size and layout direction.
     *
     * @param bitmap the bitmap to be drawn
     * @param paint the paint used to draw the bitmap
     * @param shader the shader to set on the paint
     * @param needMirroring whether the bitmap should be mirrored
     */
    private void updateShaderMatrix(@NonNull Bitmap bitmap, @NonNull Paint paint,
            @NonNull Shader shader, boolean needMirroring) {
        final int sourceDensity = bitmap.getDensity();
        final int targetDensity = mTargetDensity;
        final boolean needScaling = sourceDensity != 0 && sourceDensity != targetDensity;
        if (needScaling || needMirroring) {
            final Matrix matrix = getOrCreateMirrorMatrix();
            matrix.reset();

            if (needMirroring) {
                final int dx = mDstRect.right - mDstRect.left;
                matrix.setTranslate(dx, 0);
                matrix.setScale(-1, 1);
            }

            if (needScaling) {
                final float densityScale = targetDensity / (float) sourceDensity;
                matrix.postScale(densityScale, densityScale);
            }

            shader.setLocalMatrix(matrix);
        } else {
            mMirrorMatrix = null;
            shader.setLocalMatrix(Matrix.IDENTITY_MATRIX);
        }

        paint.setShader(shader);
    }

    private Matrix getOrCreateMirrorMatrix() {
        if (mMirrorMatrix == null) {
            mMirrorMatrix = new Matrix();
        }
        return mMirrorMatrix;
    }

    private void updateDstRectAndInsetsIfDirty() {
        if (mDstRectAndInsetsDirty) {
            if (mBitmapState.mTileModeX == null && mBitmapState.mTileModeY == null) {
                final Rect bounds = getBounds();
                final int layoutDirection = getLayoutDirection();
                Gravity.apply(mBitmapState.mGravity, mBitmapWidth, mBitmapHeight,
                        bounds, mDstRect, layoutDirection);

                final int left = mDstRect.left - bounds.left;
                final int top = mDstRect.top - bounds.top;
                final int right = bounds.right - mDstRect.right;
                final int bottom = bounds.bottom - mDstRect.bottom;
                mOpticalInsets = Insets.of(left, top, right, bottom);
            } else {
                copyBounds(mDstRect);
                mOpticalInsets = Insets.NONE;
            }
        }
        mDstRectAndInsetsDirty = false;
    }

    @Override
    public Insets getOpticalInsets() {
        updateDstRectAndInsetsIfDirty();
        return mOpticalInsets;
    }

    @Override
    public void getOutline(@NonNull Outline outline) {
        updateDstRectAndInsetsIfDirty();
        outline.setRect(mDstRect);

        // Only opaque Bitmaps can report a non-0 alpha,
        // since only they are guaranteed to fill their bounds
        boolean opaqueOverShape = mBitmapState.mBitmap != null
                && !mBitmapState.mBitmap.hasAlpha();
        outline.setAlpha(opaqueOverShape ? getAlpha() / 255.0f : 0.0f);
    }

    @Override
    public void setAlpha(int alpha) {
        final int oldAlpha = mBitmapState.mPaint.getAlpha();
        if (alpha != oldAlpha) {
            mBitmapState.mPaint.setAlpha(alpha);
            invalidateSelf();
        }
    }

    @Override
    public int getAlpha() {
        return mBitmapState.mPaint.getAlpha();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        mBitmapState.mPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public ColorFilter getColorFilter() {
        return mBitmapState.mPaint.getColorFilter();
    }

    @Override
    public void setTintList(ColorStateList tint) {
        final BitmapState state = mBitmapState;
        if (state.mTint != tint) {
            state.mTint = tint;
            mBlendModeFilter = updateBlendModeFilter(mBlendModeFilter, tint,
                      mBitmapState.mBlendMode);
            invalidateSelf();
        }
    }

    @Override
    public void setTintBlendMode(@NonNull BlendMode blendMode) {
        final BitmapState state = mBitmapState;
        if (state.mBlendMode != blendMode) {
            state.mBlendMode = blendMode;
            mBlendModeFilter = updateBlendModeFilter(mBlendModeFilter, mBitmapState.mTint,
                    blendMode);
            invalidateSelf();
        }
    }

    /**
     * @hide only needed by a hack within ProgressBar
     */
    @UnsupportedAppUsage
    public ColorStateList getTint() {
        return mBitmapState.mTint;
    }

    /**
     * @hide only needed by a hack within ProgressBar
     */
    @UnsupportedAppUsage
    public Mode getTintMode() {
        return BlendMode.blendModeToPorterDuffMode(mBitmapState.mBlendMode);
    }

    /**
     * @hide Candidate for future API inclusion
     */
    @Override
    public void setXfermode(Xfermode xfermode) {
        mBitmapState.mPaint.setXfermode(xfermode);
        invalidateSelf();
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
            mBitmapState = new BitmapState(mBitmapState);
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
        final BitmapState state = mBitmapState;
        if (state.mTint != null && state.mBlendMode != null) {
            mBlendModeFilter = updateBlendModeFilter(mBlendModeFilter, state.mTint,
                    state.mBlendMode);
            return true;
        }
        return false;
    }

    @Override
    public boolean isStateful() {
        return (mBitmapState.mTint != null && mBitmapState.mTint.isStateful())
                || super.isStateful();
    }

    /** @hide */
    @Override
    public boolean hasFocusStateSpecified() {
        return mBitmapState.mTint != null && mBitmapState.mTint.hasFocusStateSpecified();
    }

    @Override
    public void inflate(Resources r, XmlPullParser parser, AttributeSet attrs, Theme theme)
            throws XmlPullParserException, IOException {
        super.inflate(r, parser, attrs, theme);

        final TypedArray a = obtainAttributes(r, theme, attrs, R.styleable.BitmapDrawable);
        updateStateFromTypedArray(a, mSrcDensityOverride);
        verifyRequiredAttributes(a);
        a.recycle();

        // Update local properties.
        updateLocalState(r);
    }

    /**
     * Ensures all required attributes are set.
     *
     * @throws XmlPullParserException if any required attributes are missing
     */
    private void verifyRequiredAttributes(TypedArray a) throws XmlPullParserException {
        // If we're not waiting on a theme, verify required attributes.
        final BitmapState state = mBitmapState;
        if (state.mBitmap == null && (state.mThemeAttrs == null
                || state.mThemeAttrs[R.styleable.BitmapDrawable_src] == 0)) {
            throw new XmlPullParserException(a.getPositionDescription() +
                    ": <bitmap> requires a valid 'src' attribute");
        }
    }

    /**
     * Updates the constant state from the values in the typed array.
     */
    private void updateStateFromTypedArray(TypedArray a, int srcDensityOverride)
            throws XmlPullParserException {
        final Resources r = a.getResources();
        final BitmapState state = mBitmapState;

        // Account for any configuration changes.
        state.mChangingConfigurations |= a.getChangingConfigurations();

        // Extract the theme attributes, if any.
        state.mThemeAttrs = a.extractThemeAttrs();

        state.mSrcDensityOverride = srcDensityOverride;

        state.mTargetDensity = Drawable.resolveDensity(r, 0);

        final int srcResId = a.getResourceId(R.styleable.BitmapDrawable_src, 0);
        if (srcResId != 0) {
            final TypedValue value = new TypedValue();
            r.getValueForDensity(srcResId, srcDensityOverride, value, true);

            // Pretend the requested density is actually the display density. If
            // the drawable returned is not the requested density, then force it
            // to be scaled later by dividing its density by the ratio of
            // requested density to actual device density. Drawables that have
            // undefined density or no density don't need to be handled here.
            if (srcDensityOverride > 0 && value.density > 0
                    && value.density != TypedValue.DENSITY_NONE) {
                if (value.density == srcDensityOverride) {
                    value.density = r.getDisplayMetrics().densityDpi;
                } else {
                    value.density =
                            (value.density * r.getDisplayMetrics().densityDpi) / srcDensityOverride;
                }
            }

            int density = Bitmap.DENSITY_NONE;
            if (value.density == TypedValue.DENSITY_DEFAULT) {
                density = DisplayMetrics.DENSITY_DEFAULT;
            } else if (value.density != TypedValue.DENSITY_NONE) {
                density = value.density;
            }

            Bitmap bitmap = null;
            try (InputStream is = r.openRawResource(srcResId, value)) {
                ImageDecoder.Source source = ImageDecoder.createSource(r, is, density);
                bitmap = ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
            } catch (Exception e) {
                // Do nothing and pick up the error below.
            }

            if (bitmap == null) {
                throw new XmlPullParserException(a.getPositionDescription() +
                        ": <bitmap> requires a valid 'src' attribute");
            }

            state.mBitmap = bitmap;
        }

        final boolean defMipMap = state.mBitmap != null ? state.mBitmap.hasMipMap() : false;
        setMipMap(a.getBoolean(R.styleable.BitmapDrawable_mipMap, defMipMap));

        state.mAutoMirrored = a.getBoolean(
                R.styleable.BitmapDrawable_autoMirrored, state.mAutoMirrored);
        state.mBaseAlpha = a.getFloat(R.styleable.BitmapDrawable_alpha, state.mBaseAlpha);

        final int tintMode = a.getInt(R.styleable.BitmapDrawable_tintMode, -1);
        if (tintMode != -1) {
            state.mBlendMode = Drawable.parseBlendMode(tintMode, BlendMode.SRC_IN);
        }

        final ColorStateList tint = a.getColorStateList(R.styleable.BitmapDrawable_tint);
        if (tint != null) {
            state.mTint = tint;
        }

        final Paint paint = mBitmapState.mPaint;
        paint.setAntiAlias(a.getBoolean(
                R.styleable.BitmapDrawable_antialias, paint.isAntiAlias()));
        paint.setFilterBitmap(a.getBoolean(
                R.styleable.BitmapDrawable_filter, paint.isFilterBitmap()));
        paint.setDither(a.getBoolean(R.styleable.BitmapDrawable_dither, paint.isDither()));

        setGravity(a.getInt(R.styleable.BitmapDrawable_gravity, state.mGravity));

        final int tileMode = a.getInt(R.styleable.BitmapDrawable_tileMode, TILE_MODE_UNDEFINED);
        if (tileMode != TILE_MODE_UNDEFINED) {
            final Shader.TileMode mode = parseTileMode(tileMode);
            setTileModeXY(mode, mode);
        }

        final int tileModeX = a.getInt(R.styleable.BitmapDrawable_tileModeX, TILE_MODE_UNDEFINED);
        if (tileModeX != TILE_MODE_UNDEFINED) {
            setTileModeX(parseTileMode(tileModeX));
        }

        final int tileModeY = a.getInt(R.styleable.BitmapDrawable_tileModeY, TILE_MODE_UNDEFINED);
        if (tileModeY != TILE_MODE_UNDEFINED) {
            setTileModeY(parseTileMode(tileModeY));
        }
    }

    @Override
    public void applyTheme(Theme t) {
        super.applyTheme(t);

        final BitmapState state = mBitmapState;
        if (state == null) {
            return;
        }

        if (state.mThemeAttrs != null) {
            final TypedArray a = t.resolveAttributes(state.mThemeAttrs, R.styleable.BitmapDrawable);
            try {
                updateStateFromTypedArray(a, state.mSrcDensityOverride);
            } catch (XmlPullParserException e) {
                rethrowAsRuntimeException(e);
            } finally {
                a.recycle();
            }
        }

        // Apply theme to contained color state list.
        if (state.mTint != null && state.mTint.canApplyTheme()) {
            state.mTint = state.mTint.obtainForTheme(t);
        }

        // Update local properties.
        updateLocalState(t.getResources());
    }

    private static Shader.TileMode parseTileMode(int tileMode) {
        switch (tileMode) {
            case TILE_MODE_CLAMP:
                return Shader.TileMode.CLAMP;
            case TILE_MODE_REPEAT:
                return Shader.TileMode.REPEAT;
            case TILE_MODE_MIRROR:
                return Shader.TileMode.MIRROR;
            default:
                return null;
        }
    }

    @Override
    public boolean canApplyTheme() {
        return mBitmapState != null && mBitmapState.canApplyTheme();
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
        if (mBitmapState.mGravity != Gravity.FILL) {
            return PixelFormat.TRANSLUCENT;
        }

        final Bitmap bitmap = mBitmapState.mBitmap;
        return (bitmap == null || bitmap.hasAlpha() || mBitmapState.mPaint.getAlpha() < 255) ?
                PixelFormat.TRANSLUCENT : PixelFormat.OPAQUE;
    }

    @Override
    public final ConstantState getConstantState() {
        mBitmapState.mChangingConfigurations |= getChangingConfigurations();
        return mBitmapState;
    }

    final static class BitmapState extends ConstantState {
        final Paint mPaint;

        // Values loaded during inflation.
        int[] mThemeAttrs = null;
        Bitmap mBitmap = null;
        ColorStateList mTint = null;
        BlendMode mBlendMode = DEFAULT_BLEND_MODE;

        int mGravity = Gravity.FILL;
        float mBaseAlpha = 1.0f;
        Shader.TileMode mTileModeX = null;
        Shader.TileMode mTileModeY = null;

        // The density to use when looking up the bitmap in Resources. A value of 0 means use
        // the system's density.
        int mSrcDensityOverride = 0;

        // The density at which to render the bitmap.
        int mTargetDensity = DisplayMetrics.DENSITY_DEFAULT;

        boolean mAutoMirrored = false;

        @Config int mChangingConfigurations;
        boolean mRebuildShader;

        BitmapState(Bitmap bitmap) {
            mBitmap = bitmap;
            mPaint = new Paint(DEFAULT_PAINT_FLAGS);
        }

        BitmapState(BitmapState bitmapState) {
            mBitmap = bitmapState.mBitmap;
            mTint = bitmapState.mTint;
            mBlendMode = bitmapState.mBlendMode;
            mThemeAttrs = bitmapState.mThemeAttrs;
            mChangingConfigurations = bitmapState.mChangingConfigurations;
            mGravity = bitmapState.mGravity;
            mTileModeX = bitmapState.mTileModeX;
            mTileModeY = bitmapState.mTileModeY;
            mSrcDensityOverride = bitmapState.mSrcDensityOverride;
            mTargetDensity = bitmapState.mTargetDensity;
            mBaseAlpha = bitmapState.mBaseAlpha;
            mPaint = new Paint(bitmapState.mPaint);
            mRebuildShader = bitmapState.mRebuildShader;
            mAutoMirrored = bitmapState.mAutoMirrored;
        }

        @Override
        public boolean canApplyTheme() {
            return mThemeAttrs != null || mTint != null && mTint.canApplyTheme();
        }

        @Override
        public Drawable newDrawable() {
            return new BitmapDrawable(this, null);
        }

        @Override
        public Drawable newDrawable(Resources res) {
            return new BitmapDrawable(this, res);
        }

        @Override
        public @Config int getChangingConfigurations() {
            return mChangingConfigurations
                    | (mTint != null ? mTint.getChangingConfigurations() : 0);
        }
    }

    private BitmapDrawable(BitmapState state, Resources res) {
        init(state, res);
    }

    /**
     * The one helper to rule them all. This is called by all public & private
     * constructors to set the state and initialize local properties.
     */
    private void init(BitmapState state, Resources res) {
        mBitmapState = state;
        updateLocalState(res);

        if (mBitmapState != null && res != null) {
            mBitmapState.mTargetDensity = mTargetDensity;
        }
    }

    /**
     * Initializes local dynamic properties from state. This should be called
     * after significant state changes, e.g. from the One True Constructor and
     * after inflating or applying a theme.
     */
    private void updateLocalState(Resources res) {
        mTargetDensity = resolveDensity(res, mBitmapState.mTargetDensity);
        mBlendModeFilter = updateBlendModeFilter(mBlendModeFilter, mBitmapState.mTint,
                mBitmapState.mBlendMode);
        computeBitmapSize();
    }
}

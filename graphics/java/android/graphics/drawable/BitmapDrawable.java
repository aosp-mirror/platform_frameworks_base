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

import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Xfermode;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.LayoutDirection;
import android.view.Gravity;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

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
    private BitmapState mBitmapState;
    private Bitmap mBitmap;
    private int mTargetDensity;

    private final Rect mDstRect = new Rect();   // Gravity.apply() sets this

    private boolean mApplyGravity;
    private boolean mMutated;
    
     // These are scaled to match the target density.
    private int mBitmapWidth;
    private int mBitmapHeight;

    // Mirroring matrix for using with Shaders
    private Matrix mMirrorMatrix;

    /**
     * Create an empty drawable, not dealing with density.
     * @deprecated Use {@link #BitmapDrawable(android.content.res.Resources, android.graphics.Bitmap)}
     * instead to specify a bitmap to draw with and ensure the correct density is set.
     */
    @Deprecated
    public BitmapDrawable() {
        mBitmapState = new BitmapState((Bitmap) null);
    }

    /**
     * Create an empty drawable, setting initial target density based on
     * the display metrics of the resources.
     * @deprecated Use {@link #BitmapDrawable(android.content.res.Resources, android.graphics.Bitmap)}
     * instead to specify a bitmap to draw with.
     */
    @Deprecated
    @SuppressWarnings({"UnusedParameters"})
    public BitmapDrawable(Resources res) {
        mBitmapState = new BitmapState((Bitmap) null);
        mBitmapState.mTargetDensity = mTargetDensity;
    }

    /**
     * Create drawable from a bitmap, not dealing with density.
     * @deprecated Use {@link #BitmapDrawable(Resources, Bitmap)} to ensure
     * that the drawable has correctly set its target density.
     */
    @Deprecated
    public BitmapDrawable(Bitmap bitmap) {
        this(new BitmapState(bitmap), null);
    }

    /**
     * Create drawable from a bitmap, setting initial target density based on
     * the display metrics of the resources.
     */
    public BitmapDrawable(Resources res, Bitmap bitmap) {
        this(new BitmapState(bitmap), res);
        mBitmapState.mTargetDensity = mTargetDensity;
    }

    /**
     * Create a drawable by opening a given file path and decoding the bitmap.
     * @deprecated Use {@link #BitmapDrawable(Resources, String)} to ensure
     * that the drawable has correctly set its target density.
     */
    @Deprecated
    public BitmapDrawable(String filepath) {
        this(new BitmapState(BitmapFactory.decodeFile(filepath)), null);
        if (mBitmap == null) {
            android.util.Log.w("BitmapDrawable", "BitmapDrawable cannot decode " + filepath);
        }
    }

    /**
     * Create a drawable by opening a given file path and decoding the bitmap.
     */
    @SuppressWarnings({"UnusedParameters"})
    public BitmapDrawable(Resources res, String filepath) {
        this(new BitmapState(BitmapFactory.decodeFile(filepath)), null);
        mBitmapState.mTargetDensity = mTargetDensity;
        if (mBitmap == null) {
            android.util.Log.w("BitmapDrawable", "BitmapDrawable cannot decode " + filepath);
        }
    }

    /**
     * Create a drawable by decoding a bitmap from the given input stream.
     * @deprecated Use {@link #BitmapDrawable(Resources, java.io.InputStream)} to ensure
     * that the drawable has correctly set its target density.
     */
    @Deprecated
    public BitmapDrawable(java.io.InputStream is) {
        this(new BitmapState(BitmapFactory.decodeStream(is)), null);
        if (mBitmap == null) {
            android.util.Log.w("BitmapDrawable", "BitmapDrawable cannot decode " + is);
        }
    }

    /**
     * Create a drawable by decoding a bitmap from the given input stream.
     */
    @SuppressWarnings({"UnusedParameters"})
    public BitmapDrawable(Resources res, java.io.InputStream is) {
        this(new BitmapState(BitmapFactory.decodeStream(is)), null);
        mBitmapState.mTargetDensity = mTargetDensity;
        if (mBitmap == null) {
            android.util.Log.w("BitmapDrawable", "BitmapDrawable cannot decode " + is);
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
        return mBitmap;
    }

    private void computeBitmapSize() {
        mBitmapWidth = mBitmap.getScaledWidth(mTargetDensity);
        mBitmapHeight = mBitmap.getScaledHeight(mTargetDensity);
    }
    
    private void setBitmap(Bitmap bitmap) {
        if (bitmap != mBitmap) {
            mBitmap = bitmap;
            if (bitmap != null) {
                computeBitmapSize();
            } else {
                mBitmapWidth = mBitmapHeight = -1;
            }
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
            if (mBitmap != null) {
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
            mApplyGravity = true;
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
    public void setDither(boolean dither) {
        mBitmapState.mPaint.setDither(dither);
        invalidateSelf();
    }

    /**
     * Indicates the repeat behavior of this drawable on the X axis.
     * 
     * @return {@link Shader.TileMode#CLAMP} if the bitmap does not repeat,
     *         {@link Shader.TileMode#REPEAT} or {@link Shader.TileMode#MIRROR} otherwise.
     */
    public Shader.TileMode getTileModeX() {
        return mBitmapState.mTileModeX;
    }

    /**
     * Indicates the repeat behavior of this drawable on the Y axis.
     * 
     * @return {@link Shader.TileMode#CLAMP} if the bitmap does not repeat,
     *         {@link Shader.TileMode#REPEAT} or {@link Shader.TileMode#MIRROR} otherwise.
     */    
    public Shader.TileMode getTileModeY() {
        return mBitmapState.mTileModeY;
    }

    /**
     * Sets the repeat behavior of this drawable on the X axis. By default, the drawable
     * does not repeat its bitmap. Using {@link Shader.TileMode#REPEAT} or
     * {@link Shader.TileMode#MIRROR} the bitmap can be repeated (or tiled) if the bitmap
     * is smaller than this drawable.
     * 
     * @param mode The repeat mode for this drawable.
     * 
     * @see #setTileModeY(android.graphics.Shader.TileMode) 
     * @see #setTileModeXY(android.graphics.Shader.TileMode, android.graphics.Shader.TileMode) 
     */
    public void setTileModeX(Shader.TileMode mode) {
        setTileModeXY(mode, mBitmapState.mTileModeY);
    }

    /**
     * Sets the repeat behavior of this drawable on the Y axis. By default, the drawable
     * does not repeat its bitmap. Using {@link Shader.TileMode#REPEAT} or
     * {@link Shader.TileMode#MIRROR} the bitmap can be repeated (or tiled) if the bitmap
     * is smaller than this drawable.
     * 
     * @param mode The repeat mode for this drawable.
     * 
     * @see #setTileModeX(android.graphics.Shader.TileMode) 
     * @see #setTileModeXY(android.graphics.Shader.TileMode, android.graphics.Shader.TileMode) 
     */    
    public final void setTileModeY(Shader.TileMode mode) {
        setTileModeXY(mBitmapState.mTileModeX, mode);
    }

    /**
     * Sets the repeat behavior of this drawable on both axis. By default, the drawable
     * does not repeat its bitmap. Using {@link Shader.TileMode#REPEAT} or
     * {@link Shader.TileMode#MIRROR} the bitmap can be repeated (or tiled) if the bitmap
     * is smaller than this drawable.
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
    public int getChangingConfigurations() {
        return super.getChangingConfigurations() | mBitmapState.mChangingConfigurations;
    }

    private boolean needMirroring() {
        return isAutoMirrored() && getLayoutDirection() == LayoutDirection.RTL;
    }

    private void updateMirrorMatrix(float dx) {
        if (mMirrorMatrix == null) {
            mMirrorMatrix = new Matrix();
        }
        mMirrorMatrix.setTranslate(dx, 0);
        mMirrorMatrix.preScale(-1.0f, 1.0f);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        mApplyGravity = true;
        Shader shader = mBitmapState.mPaint.getShader();
        if (shader != null) {
            if (needMirroring()) {
                updateMirrorMatrix(bounds.right - bounds.left);
                shader.setLocalMatrix(mMirrorMatrix);
            } else {
                if (mMirrorMatrix != null) {
                    mMirrorMatrix = null;
                    shader.setLocalMatrix(Matrix.IDENTITY_MATRIX);
                }
            }
        }
    }

    @Override
    public void draw(Canvas canvas) {
        Bitmap bitmap = mBitmap;
        if (bitmap != null) {
            final BitmapState state = mBitmapState;
            if (state.mRebuildShader) {
                Shader.TileMode tmx = state.mTileModeX;
                Shader.TileMode tmy = state.mTileModeY;

                if (tmx == null && tmy == null) {
                    state.mPaint.setShader(null);
                } else {
                    state.mPaint.setShader(new BitmapShader(bitmap,
                            tmx == null ? Shader.TileMode.CLAMP : tmx,
                            tmy == null ? Shader.TileMode.CLAMP : tmy));
                }
                state.mRebuildShader = false;
                copyBounds(mDstRect);
            }

            Shader shader = state.mPaint.getShader();
            final boolean needMirroring = needMirroring();
            if (shader == null) {
                if (mApplyGravity) {
                    final int layoutDirection = getLayoutDirection();
                    Gravity.apply(state.mGravity, mBitmapWidth, mBitmapHeight,
                            getBounds(), mDstRect, layoutDirection);
                    mApplyGravity = false;
                }
                if (needMirroring) {
                    canvas.save();
                    // Mirror the bitmap
                    canvas.translate(mDstRect.right - mDstRect.left, 0);
                    canvas.scale(-1.0f, 1.0f);
                }
                canvas.drawBitmap(bitmap, null, mDstRect, state.mPaint);
                if (needMirroring) {
                    canvas.restore();
                }
            } else {
                if (mApplyGravity) {
                    copyBounds(mDstRect);
                    mApplyGravity = false;
                }
                if (needMirroring) {
                    // Mirror the bitmap
                    updateMirrorMatrix(mDstRect.right - mDstRect.left);
                    shader.setLocalMatrix(mMirrorMatrix);
                } else {
                    if (mMirrorMatrix != null) {
                        mMirrorMatrix = null;
                        shader.setLocalMatrix(Matrix.IDENTITY_MATRIX);
                    }
                }
                canvas.drawRect(mDstRect, state.mPaint);
            }
        }
    }

    @Override
    public void setAlpha(int alpha) {
        int oldAlpha = mBitmapState.mPaint.getAlpha();
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
    public void setColorFilter(ColorFilter cf) {
        mBitmapState.mPaint.setColorFilter(cf);
        invalidateSelf();
    }

    /**
     * @hide Candidate for future API inclusion
     */
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

    @Override
    public void inflate(Resources r, XmlPullParser parser, AttributeSet attrs)
            throws XmlPullParserException, IOException {
        super.inflate(r, parser, attrs);

        TypedArray a = r.obtainAttributes(attrs, com.android.internal.R.styleable.BitmapDrawable);

        final int id = a.getResourceId(com.android.internal.R.styleable.BitmapDrawable_src, 0);
        if (id == 0) {
            throw new XmlPullParserException(parser.getPositionDescription() +
                    ": <bitmap> requires a valid src attribute");
        }
        final Bitmap bitmap = BitmapFactory.decodeResource(r, id);
        if (bitmap == null) {
            throw new XmlPullParserException(parser.getPositionDescription() +
                    ": <bitmap> requires a valid src attribute");
        }
        mBitmapState.mBitmap = bitmap;
        setBitmap(bitmap);
        setTargetDensity(r.getDisplayMetrics());
        setMipMap(a.getBoolean(com.android.internal.R.styleable.BitmapDrawable_mipMap,
                bitmap.hasMipMap()));
        setAutoMirrored(a.getBoolean(com.android.internal.R.styleable.BitmapDrawable_autoMirrored,
                false));

        final Paint paint = mBitmapState.mPaint;
        paint.setAntiAlias(a.getBoolean(com.android.internal.R.styleable.BitmapDrawable_antialias,
                paint.isAntiAlias()));
        paint.setFilterBitmap(a.getBoolean(com.android.internal.R.styleable.BitmapDrawable_filter,
                paint.isFilterBitmap()));
        paint.setDither(a.getBoolean(com.android.internal.R.styleable.BitmapDrawable_dither,
                paint.isDither()));
        setGravity(a.getInt(com.android.internal.R.styleable.BitmapDrawable_gravity, Gravity.FILL));
        int tileMode = a.getInt(com.android.internal.R.styleable.BitmapDrawable_tileMode, -1);
        if (tileMode != -1) {
            switch (tileMode) {
                case 0:
                    setTileModeXY(Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    break;
                case 1:
                    setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
                    break;
                case 2:
                    setTileModeXY(Shader.TileMode.MIRROR, Shader.TileMode.MIRROR);
                    break;
            }
        }

        a.recycle();
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
        Bitmap bm = mBitmap;
        return (bm == null || bm.hasAlpha() || mBitmapState.mPaint.getAlpha() < 255) ?
                PixelFormat.TRANSLUCENT : PixelFormat.OPAQUE;
    }

    @Override
    public final ConstantState getConstantState() {
        mBitmapState.mChangingConfigurations = getChangingConfigurations();
        return mBitmapState;
    }

    final static class BitmapState extends ConstantState {
        Bitmap mBitmap;
        int mChangingConfigurations;
        int mGravity = Gravity.FILL;
        Paint mPaint = new Paint(DEFAULT_PAINT_FLAGS);
        Shader.TileMode mTileModeX = null;
        Shader.TileMode mTileModeY = null;
        int mTargetDensity = DisplayMetrics.DENSITY_DEFAULT;
        boolean mRebuildShader;
        boolean mAutoMirrored;

        BitmapState(Bitmap bitmap) {
            mBitmap = bitmap;
        }

        BitmapState(BitmapState bitmapState) {
            this(bitmapState.mBitmap);
            mChangingConfigurations = bitmapState.mChangingConfigurations;
            mGravity = bitmapState.mGravity;
            mTileModeX = bitmapState.mTileModeX;
            mTileModeY = bitmapState.mTileModeY;
            mTargetDensity = bitmapState.mTargetDensity;
            mPaint = new Paint(bitmapState.mPaint);
            mRebuildShader = bitmapState.mRebuildShader;
            mAutoMirrored = bitmapState.mAutoMirrored;
        }

        @Override
        public Bitmap getBitmap() {
            return mBitmap;
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
        public int getChangingConfigurations() {
            return mChangingConfigurations;
        }
    }

    private BitmapDrawable(BitmapState state, Resources res) {
        mBitmapState = state;
        if (res != null) {
            mTargetDensity = res.getDisplayMetrics().densityDpi;
        } else {
            mTargetDensity = state.mTargetDensity;
        }
        setBitmap(state != null ? state.mBitmap : null);
    }
}

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
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Insets;
import android.graphics.NinePatch;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.LayoutDirection;
import android.util.TypedValue;
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
    private NinePatchState mNinePatchState;
    private NinePatch mNinePatch;
    private Rect mPadding;
    private Insets mOpticalInsets = Insets.NONE;
    private Paint mPaint;
    private boolean mMutated;

    private int mTargetDensity = DisplayMetrics.DENSITY_DEFAULT;

    // These are scaled to match the target density.
    private int mBitmapWidth;
    private int mBitmapHeight;

    NinePatchDrawable() {
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
        this(new NinePatchState(new NinePatch(bitmap, chunk, srcName), padding, opticalInsets), res);
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

    private void setNinePatchState(NinePatchState state, Resources res) {
        mNinePatchState = state;
        mNinePatch = state.mNinePatch;
        mPadding = state.mPadding;
        mTargetDensity = res != null ? res.getDisplayMetrics().densityDpi
                : state.mTargetDensity;
        //noinspection PointlessBooleanExpression
        if (state.mDither != DEFAULT_DITHER) {
            // avoid calling the setter unless we need to, since it does a
            // lazy allocation of a paint
            setDither(state.mDither);
        }
        setAutoMirrored(state.mAutoMirrored);
        if (mNinePatch != null) {
            computeBitmapSize();
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

    @Override
    public void draw(Canvas canvas) {
        final Rect bounds = getBounds();
        final boolean needsMirroring = needsMirroring();
        if (needsMirroring) {
            canvas.save();
            // Mirror the 9patch
            canvas.translate(bounds.right - bounds.left, 0);
            canvas.scale(-1.0f, 1.0f);
        }
        mNinePatch.draw(canvas, bounds, mPaint);
        if (needsMirroring) {
            canvas.restore();
        }
    }

    @Override
    public int getChangingConfigurations() {
        return super.getChangingConfigurations() | mNinePatchState.mChangingConfigurations;
    }

    @Override
    public boolean getPadding(Rect padding) {
        if (needsMirroring()) {
            padding.set(mPadding.right, mPadding.top, mPadding.left, mPadding.bottom);
        } else {
            padding.set(mPadding);
        }
        return (padding.left | padding.top | padding.right | padding.bottom) != 0;
    }

    /**
     * @hide
     */
    @Override
    public Insets getOpticalInsets() {
        if (needsMirroring()) {
            return Insets.of(mOpticalInsets.right, mOpticalInsets.top, mOpticalInsets.right,
                    mOpticalInsets.bottom);
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
    public void inflate(Resources r, XmlPullParser parser, AttributeSet attrs)
            throws XmlPullParserException, IOException {
        super.inflate(r, parser, attrs);

        TypedArray a = r.obtainAttributes(attrs, com.android.internal.R.styleable.NinePatchDrawable);

        final int id = a.getResourceId(com.android.internal.R.styleable.NinePatchDrawable_src, 0);
        if (id == 0) {
            throw new XmlPullParserException(parser.getPositionDescription() +
                    ": <nine-patch> requires a valid src attribute");
        }

        final boolean dither = a.getBoolean(
                com.android.internal.R.styleable.NinePatchDrawable_dither, DEFAULT_DITHER);
        final BitmapFactory.Options options = new BitmapFactory.Options();
        if (dither) {
            options.inDither = false;
        }
        options.inScreenDensity = r.getDisplayMetrics().noncompatDensityDpi;

        final Rect padding = new Rect();
        final Rect opticalInsets = new Rect();
        Bitmap bitmap = null;

        try {
            final TypedValue value = new TypedValue();
            final InputStream is = r.openRawResource(id, value);

            bitmap = BitmapFactory.decodeResourceStream(r, value, is, padding, options);

            is.close();
        } catch (IOException e) {
            // Ignore
        }

        if (bitmap == null) {
            throw new XmlPullParserException(parser.getPositionDescription() +
                    ": <nine-patch> requires a valid src attribute");
        } else if (bitmap.getNinePatchChunk() == null) {
            throw new XmlPullParserException(parser.getPositionDescription() +
                    ": <nine-patch> requires a valid 9-patch source image");
        }

        final boolean automirrored = a.getBoolean(
                com.android.internal.R.styleable.NinePatchDrawable_autoMirrored, false);

        setNinePatchState(new NinePatchState(new NinePatch(bitmap, bitmap.getNinePatchChunk()),
                padding, opticalInsets, dither, automirrored), r);
        mNinePatchState.mTargetDensity = mTargetDensity;

        a.recycle();
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

    final static class NinePatchState extends ConstantState {
        final NinePatch mNinePatch;
        final Rect mPadding;
        final Insets mOpticalInsets;
        final boolean mDither;
        int mChangingConfigurations;
        int mTargetDensity = DisplayMetrics.DENSITY_DEFAULT;
        boolean mAutoMirrored;

        NinePatchState(NinePatch ninePatch, Rect padding) {
            this(ninePatch, padding, new Rect(), DEFAULT_DITHER, false);
        }

        NinePatchState(NinePatch ninePatch, Rect padding, Rect opticalInsets) {
            this(ninePatch, padding, opticalInsets, DEFAULT_DITHER, false);
        }

        NinePatchState(NinePatch ninePatch, Rect rect, Rect opticalInsets, boolean dither,
                       boolean autoMirror) {
            mNinePatch = ninePatch;
            mPadding = rect;
            mOpticalInsets = Insets.of(opticalInsets);
            mDither = dither;
            mAutoMirrored = autoMirror;
        }

        // Copy constructor

        NinePatchState(NinePatchState state) {
            // Note we don't copy the nine patch because it is immutable.
            mNinePatch = state.mNinePatch;
            // Note we don't copy the padding because it is immutable.
            mPadding = state.mPadding;
            mOpticalInsets = state.mOpticalInsets;
            mDither = state.mDither;
            mChangingConfigurations = state.mChangingConfigurations;
            mTargetDensity = state.mTargetDensity;
            mAutoMirrored = state.mAutoMirrored;
        }

        @Override
        public Bitmap getBitmap() {
            return mNinePatch.getBitmap();
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

    private NinePatchDrawable(NinePatchState state, Resources res) {
        setNinePatchState(state, res);
    }
}

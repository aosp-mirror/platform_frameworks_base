/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.graphics;

import android.annotation.IntDef;
import android.annotation.NonNull;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Shader used to draw a bitmap as a texture. The bitmap can be repeated or
 * mirrored by setting the tiling mode.
 */
public class BitmapShader extends Shader {
    /**
     * Prevent garbage collection.
     */
    /*package*/ Bitmap mBitmap;

    private int mTileX;
    private int mTileY;

    /** @hide */
    @IntDef(prefix = {"FILTER_MODE"}, value = {
            FILTER_MODE_DEFAULT,
            FILTER_MODE_NEAREST,
            FILTER_MODE_LINEAR
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface FilterMode {}

    /**
     * This FilterMode value will respect the value of the Paint#isFilterBitmap flag while the
     * shader is attached to the Paint.
     *
     * <p>The exception to this rule is when a Shader is attached as input to a RuntimeShader. In
     *    that case this mode will default to FILTER_MODE_NEAREST.</p>
     *
     * @see #setFilterMode(int)
     */
    public static final int FILTER_MODE_DEFAULT = 0;
    /**
     * This FilterMode value will cause the shader to sample from the nearest pixel to the requested
     * sample point.
     *
     * <p>This value will override the effect of Paint#isFilterBitmap.</p>
     *
     * @see #setFilterMode(int)
     */
    public static final int FILTER_MODE_NEAREST = 1;
    /**
     * This FilterMode value will cause the shader to interpolate the output of the shader from a
     * 2x2 grid of pixels nearest to the sample point (i.e. bilinear interpolation).
     *
     * <p>This value will override the effect of Paint#isFilterBitmap.</p>
     *
     * @see #setFilterMode(int)
     */
    public static final int FILTER_MODE_LINEAR = 2;

    @FilterMode
    private int mFilterMode;

    /*
     *  This is cache of the last value from the Paint of bitmap-filtering.
     *  In the future, BitmapShaders will carry their own (expanded) data for this
     *  (e.g. including mipmap options, or bicubic weights)
     *
     *  When that happens, this bool will become those extended values, and we will
     *  need to track whether this Shader was created with those new constructors,
     *  or from the current "legacy" constructor, which (for compatibility) will
     *  still need to know the Paint's setting.
     *
     *  When the filter Paint setting is finally gone, we will be able to remove
     *  the filterFromPaint parameter currently being passed to createNativeInstance()
     *  and shouldDiscardNativeInstance(), as shaders will always know their filter
     *  settings.
     */
    private boolean mFilterFromPaint;

    /**
     *  Stores whether or not the contents of this shader's bitmap will be sampled
     *  without modification or if the bitmap's properties, like colorspace and
     *  premultiplied alpha, will be respected when sampling from the bitmap's buffer.
     */
    private boolean mIsDirectSampled;

    private boolean mRequestDirectSampling;

    /**
     * Call this to create a new shader that will draw with a bitmap.
     *
     * @param bitmap The bitmap to use inside the shader
     * @param tileX The tiling mode for x to draw the bitmap in.
     * @param tileY The tiling mode for y to draw the bitmap in.
     */
    public BitmapShader(@NonNull Bitmap bitmap, @NonNull TileMode tileX, @NonNull TileMode tileY) {
        this(bitmap, tileX.nativeInt, tileY.nativeInt);
    }

    private BitmapShader(Bitmap bitmap, int tileX, int tileY) {
        if (bitmap == null) {
            throw new IllegalArgumentException("Bitmap must be non-null");
        }
        mBitmap = bitmap;
        mTileX = tileX;
        mTileY = tileY;
        mFilterMode = FILTER_MODE_DEFAULT;
        mFilterFromPaint = false;
        mIsDirectSampled = false;
        mRequestDirectSampling = false;
    }

    /**
     * Returns the filter mode used when sampling from this shader
     */
    @FilterMode
    public int getFilterMode() {
        return mFilterMode;
    }

    /**
     * Set the filter mode to be used when sampling from this shader
     */
    public void setFilterMode(@FilterMode int mode) {
        if (mode != mFilterMode) {
            mFilterMode = mode;
            discardNativeInstance();
        }
    }

    /** @hide */
    /* package */ synchronized long getNativeInstanceWithDirectSampling() {
        mRequestDirectSampling = true;
        return getNativeInstance();
    }

    /** @hide */
    @Override
    protected long createNativeInstance(long nativeMatrix, boolean filterFromPaint) {
        boolean enableLinearFilter = mFilterMode == FILTER_MODE_LINEAR;
        if (mFilterMode == FILTER_MODE_DEFAULT) {
            mFilterFromPaint = filterFromPaint;
            enableLinearFilter = mFilterFromPaint;
        }

        mIsDirectSampled = mRequestDirectSampling;
        mRequestDirectSampling = false;

        return nativeCreate(nativeMatrix, mBitmap.getNativeInstance(), mTileX, mTileY,
                            enableLinearFilter, mIsDirectSampled);
    }

    /** @hide */
    @Override
    protected boolean shouldDiscardNativeInstance(boolean filterFromPaint) {
        return mIsDirectSampled != mRequestDirectSampling
                || (mFilterMode == FILTER_MODE_DEFAULT && mFilterFromPaint != filterFromPaint);
    }

    private static native long nativeCreate(long nativeMatrix, long bitmapHandle,
            int shaderTileModeX, int shaderTileModeY, boolean filter, boolean isDirectSampled);
}


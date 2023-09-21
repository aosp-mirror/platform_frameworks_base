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

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.graphics.hwui.flags.Flags;

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
    private Gainmap mOverrideGainmap;

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

    private int mMaxAniso = 0;

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
        bitmap.checkRecycled("Cannot create BitmapShader for recycled bitmap");
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
     * Set the filter mode to be used when sampling from this shader. If this is configured
     * then the anisotropic filtering value specified in any previous call to
     * {@link #setMaxAnisotropy(int)} is ignored.
     */
    public void setFilterMode(@FilterMode int mode) {
        if (mode != mFilterMode) {
            mFilterMode = mode;
            mMaxAniso = 0;
            discardNativeInstance();
        }
    }

    /**
     * Enables and configures the max anisotropy sampling value. If this value is configured,
     * {@link #setFilterMode(int)} is ignored.
     *
     * Anisotropic filtering can enhance visual quality by removing aliasing effects of images
     * that are at oblique viewing angles. This value is typically consumed as a power of 2 and
     * anisotropic values of the next power of 2 typically provide twice the quality improvement
     * as the previous value. For example, a sampling value of 4 would provide twice the improvement
     * of a sampling value of 2. It is important to note that higher sampling values reach
     * diminishing returns as the improvements between 8 and 16 can be slight.
     *
     * @param maxAnisotropy The Anisotropy value to use for filtering. Must be greater than 0.
     */
    public void setMaxAnisotropy(@IntRange(from = 1) int maxAnisotropy) {
        if (mMaxAniso != maxAnisotropy && maxAnisotropy > 0) {
            mMaxAniso = maxAnisotropy;
            mFilterMode = FILTER_MODE_DEFAULT;
            discardNativeInstance();
        }
    }

    /**
     * Draws the BitmapShader with a copy of the given gainmap instead of the gainmap on the Bitmap
     * the shader was constructed from
     *
     * @param overrideGainmap The gainmap to draw instead, null to use any gainmap on the Bitmap
     */
    @FlaggedApi(Flags.FLAG_GAINMAP_ANIMATIONS)
    public void setOverrideGainmap(@Nullable Gainmap overrideGainmap) {
        if (!Flags.gainmapAnimations()) throw new IllegalStateException("API not available");

        if (overrideGainmap == null) {
            mOverrideGainmap = null;
        } else {
            mOverrideGainmap = new Gainmap(overrideGainmap, overrideGainmap.getGainmapContents());
        }
        discardNativeInstance();
    }

    /**
     * Returns the current max anisotropic filtering value configured by
     * {@link #setFilterMode(int)}. If {@link #setFilterMode(int)} is invoked this returns zero.
     */
    public int getMaxAnisotropy() {
        return mMaxAniso;
    }

    /** @hide */
    /* package */ synchronized long getNativeInstanceWithDirectSampling() {
        mRequestDirectSampling = true;
        return getNativeInstance();
    }

    /** @hide */
    @Override
    protected long createNativeInstance(long nativeMatrix, boolean filterFromPaint) {
        mBitmap.checkRecycled("BitmapShader's bitmap has been recycled");

        boolean enableLinearFilter = mFilterMode == FILTER_MODE_LINEAR;
        if (mFilterMode == FILTER_MODE_DEFAULT) {
            mFilterFromPaint = filterFromPaint;
            enableLinearFilter = mFilterFromPaint;
        }

        mIsDirectSampled = mRequestDirectSampling;
        mRequestDirectSampling = false;
        return nativeCreate(nativeMatrix, mBitmap.getNativeInstance(), mTileX,
                mTileY, mMaxAniso, enableLinearFilter, mIsDirectSampled,
                mOverrideGainmap != null ? mOverrideGainmap.mNativePtr : 0);
    }

    /** @hide */
    @Override
    protected boolean shouldDiscardNativeInstance(boolean filterFromPaint) {
        return mIsDirectSampled != mRequestDirectSampling
                || (mFilterMode == FILTER_MODE_DEFAULT && mFilterFromPaint != filterFromPaint);
    }

    private static native long nativeCreate(long nativeMatrix, long bitmapHandle,
            int shaderTileModeX, int shaderTileModeY, int maxAniso, boolean filter,
            boolean isDirectSampled, long overrideGainmapHandle);
}


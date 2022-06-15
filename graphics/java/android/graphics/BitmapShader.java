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

import android.annotation.NonNull;

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
        mFilterFromPaint = false;
    }

    /** @hide */
    @Override
    protected long createNativeInstance(long nativeMatrix, boolean filterFromPaint) {
        mFilterFromPaint = filterFromPaint;
        return nativeCreate(nativeMatrix, mBitmap.getNativeInstance(), mTileX, mTileY,
                            mFilterFromPaint);
    }

    /** @hide */
    @Override
    protected boolean shouldDiscardNativeInstance(boolean filterFromPaint) {
        return mFilterFromPaint != filterFromPaint;
    }

    private static native long nativeCreate(long nativeMatrix, long bitmapHandle,
            int shaderTileModeX, int shaderTileModeY, boolean filter);
}


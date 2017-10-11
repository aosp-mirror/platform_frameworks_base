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
     * @hide
     */
    @SuppressWarnings({"FieldCanBeLocal", "UnusedDeclaration"})
    public Bitmap mBitmap;

    private int mTileX;
    private int mTileY;

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
        if (bitmap == mBitmap && tileX == mTileX && tileY == mTileY) {
            return;
        }
        mBitmap = bitmap;
        mTileX = tileX;
        mTileY = tileY;
    }

    @Override
    long createNativeInstance(long nativeMatrix) {
        return nativeCreate(nativeMatrix, mBitmap, mTileX, mTileY);
    }

    /**
     * @hide
     */
    @Override
    protected Shader copy() {
        final BitmapShader copy = new BitmapShader(mBitmap, mTileX, mTileY);
        copyLocalMatrix(copy);
        return copy;
    }

    private static native long nativeCreate(long nativeMatrix, Bitmap bitmap,
            int shaderTileModeX, int shaderTileModeY);
}

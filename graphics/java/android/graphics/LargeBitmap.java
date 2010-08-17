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

package android.graphics;

import android.os.Parcel;
import android.os.Parcelable;
import android.util.DisplayMetrics;

import java.io.OutputStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

/**
 * LargeBitmap can be used to decode a rectangle region from an image.
 * LargeBimap is particularly useful when an original image is large and
 * you only need parts of the image.
 *
 * To create a LargeBitmap, call BitmapFactory.createLargeBitmap().
 * Given a LargeBitmap, users can call decodeRegion() repeatedly
 * to get a decoded Bitmap of the specified region.
 * @hide
 */
public final class LargeBitmap {
    private int mNativeLargeBitmap;
    private boolean mRecycled;

    /*  Private constructor that must received an already allocated native
        large bitmap int (pointer).

        This can be called from JNI code.
    */
    private LargeBitmap(int lbm) {
        mNativeLargeBitmap = lbm;
        mRecycled = false;
    }

    /**
     * Decodes a rectangle region in the image specified by rect.
     *
     * @param rect The rectangle that specified the region to be decode.
     * @param opts null-ok; Options that control downsampling.
     *             inPurgeable is not supported.
     * @return The decoded bitmap, or null if the image data could not be
     *         decoded.
     */
    public Bitmap decodeRegion(Rect rect, BitmapFactory.Options options) {
        checkRecycled("decodeRegion called on recycled large bitmap");
        if (rect.left < 0 || rect.top < 0 || rect.right > getWidth() || rect.bottom > getHeight())
            throw new IllegalArgumentException("rectangle is not inside the image");
        return nativeDecodeRegion(mNativeLargeBitmap, rect.left, rect.top,
                rect.right - rect.left, rect.bottom - rect.top, options);
    }

    /** Returns the original image's width */
    public int getWidth() {
        checkRecycled("getWidth called on recycled large bitmap");
        return nativeGetWidth(mNativeLargeBitmap);
    }

    /** Returns the original image's height */
    public int getHeight() {
        checkRecycled("getHeight called on recycled large bitmap");
        return nativeGetHeight(mNativeLargeBitmap);
    }

    /**
     * Frees up the memory associated with this large bitmap, and mark the
     * large bitmap as "dead", meaning it will throw an exception if decodeRegion(),
     * getWidth() or getHeight() is called.
     * This operation cannot be reversed, so it should only be called if you are
     * sure there are no further uses for the large bitmap. This is an advanced call,
     * and normally need not be called, since the normal GC process will free up this
     * memory when there are no more references to this bitmap.
     */
    public void recycle() {
        if (!mRecycled) {
            nativeClean(mNativeLargeBitmap);
            mRecycled = true;
        }
    }

    /**
     * Returns true if this large bitmap has been recycled.
     * If so, then it is an error to try use its method.
     *
     * @return true if the large bitmap has been recycled
     */
    public final boolean isRecycled() {
        return mRecycled;
    }

    /**
     * Called by methods that want to throw an exception if the bitmap
     * has already been recycled.
     */
    private void checkRecycled(String errorMessage) {
        if (mRecycled) {
            throw new IllegalStateException(errorMessage);
        }
    }

    protected void finalize() {
        recycle();
    }

    private static native Bitmap nativeDecodeRegion(int lbm,
            int start_x, int start_y, int width, int height,
            BitmapFactory.Options options);
    private static native int nativeGetWidth(int lbm);
    private static native int nativeGetHeight(int lbm);
    private static native void nativeClean(int lbm);
}

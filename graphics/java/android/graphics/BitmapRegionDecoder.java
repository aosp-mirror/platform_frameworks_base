/* Copyright (C) 2010 The Android Open Source Project
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
import android.compat.annotation.UnsupportedAppUsage;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * BitmapRegionDecoder can be used to decode a rectangle region from an image.
 * BitmapRegionDecoder is particularly useful when an original image is large and
 * you only need parts of the image.
 *
 * <p>To create a BitmapRegionDecoder, call newInstance(...).
 * Given a BitmapRegionDecoder, users can call decodeRegion() repeatedly
 * to get a decoded Bitmap of the specified region.
 *
 */
public final class BitmapRegionDecoder {
    private long mNativeBitmapRegionDecoder;
    private boolean mRecycled;
    // ensures that the native decoder object exists and that only one decode can
    // occur at a time.
    private final Object mNativeLock = new Object();

    /**
     * Create a BitmapRegionDecoder from the specified byte array.
     * Currently only the JPEG, PNG, WebP and HEIF formats are supported.
     *
     * @param data byte array of compressed image data.
     * @param offset offset into data for where the decoder should begin
     *               parsing.
     * @param length the number of bytes, beginning at offset, to parse
     * @param isShareable This field has been ignored since
     *                    {@link Build.VERSION_CODES#GINGERBREAD}.
     * @throws IOException if the image format is not supported or can not be decoded.
     * @deprecated In favor of {@link #newInstance(byte[], int, int)}
     */
    @Deprecated
    @NonNull
    public static BitmapRegionDecoder newInstance(@NonNull byte[] data,
            int offset, int length, boolean isShareable) throws IOException {
        return newInstance(data, offset, length);
    }

    /**
     * Create a BitmapRegionDecoder from the specified byte array.
     * Currently only the JPEG, PNG, WebP and HEIF formats are supported.
     *
     * @param data byte array of compressed image data.
     * @param offset offset into data for where the decoder should begin
     *               parsing.
     * @param length the number of bytes, beginning at offset, to parse
     * @throws IOException if the image format is not supported or can not be decoded.
     */
    @NonNull
    public static BitmapRegionDecoder newInstance(@NonNull byte[] data,
            int offset, int length) throws IOException {
        if ((offset | length) < 0 || data.length < offset + length) {
            throw new ArrayIndexOutOfBoundsException();
        }
        return nativeNewInstance(data, offset, length);
    }

    /**
     * Create a BitmapRegionDecoder from the file descriptor.
     * The position within the descriptor will not be changed when
     * this returns, so the descriptor can be used again as is.
     * Currently only the JPEG, PNG, WebP and HEIF formats are supported.
     *
     * @param fd The file descriptor containing the data to decode
     * @param isShareable This field has been ignored since
     *                    {@link Build.VERSION_CODES#KITKAT}.
     * @throws IOException if the image format is not supported or can not be decoded.
     * @deprecated In favor of {@link #newInstance(ParcelFileDescriptor)}
     */
    @Deprecated
    @NonNull
    public static BitmapRegionDecoder newInstance(
            @NonNull FileDescriptor fd, boolean isShareable) throws IOException {
        return nativeNewInstance(fd);
    }

    /**
     * Create a BitmapRegionDecoder from the file descriptor.
     * The position within the descriptor will not be changed when
     * this returns, so the descriptor can be used again as is.
     * Currently only the JPEG, PNG, WebP and HEIF formats are supported.
     *
     * @param pfd The parcel file descriptor containing the data to decode
     * @throws IOException if the image format is not supported or can not be decoded.
     */
    @NonNull
    public static BitmapRegionDecoder newInstance(
            @NonNull ParcelFileDescriptor pfd) throws IOException {
        return nativeNewInstance(pfd.getFileDescriptor());
    }

    /**
     * Create a BitmapRegionDecoder from an input stream.
     * The stream's position will be where ever it was after the encoded data
     * was read.
     * Currently only the JPEG, PNG, WebP and HEIF formats are supported.
     *
     * @param is The input stream that holds the raw data to be decoded into a
     *           BitmapRegionDecoder.
     * @param isShareable This field has always been ignored.
     * @throws IOException if the image format is not supported or can not be decoded.
     * @deprecated In favor of {@link #newInstance(InputStream)}
     *
     * <p class="note">Prior to {@link Build.VERSION_CODES#KITKAT},
     * if {@link InputStream#markSupported is.markSupported()} returns true,
     * <code>is.mark(1024)</code> would be called. As of
     * {@link Build.VERSION_CODES#KITKAT}, this is no longer the case.</p>
     */
    @Deprecated
    @NonNull
    public static BitmapRegionDecoder newInstance(@NonNull InputStream is,
            boolean isShareable) throws IOException {
        return newInstance(is);
    }

    /**
     * Create a BitmapRegionDecoder from an input stream.
     * The stream's position will be where ever it was after the encoded data
     * was read.
     * Currently only the JPEG, PNG, WebP and HEIF formats are supported.
     *
     * @param is The input stream that holds the raw data to be decoded into a
     *           BitmapRegionDecoder.
     * @throws IOException if the image format is not supported or can not be decoded.
     */
    @NonNull
    public static BitmapRegionDecoder newInstance(@NonNull InputStream is) throws IOException {
        if (is instanceof AssetManager.AssetInputStream) {
            return nativeNewInstance(
                    ((AssetManager.AssetInputStream) is).getNativeAsset());
        } else {
            // pass some temp storage down to the native code. 1024 is made up,
            // but should be large enough to avoid too many small calls back
            // into is.read(...).
            byte [] tempStorage = new byte[16 * 1024];
            return nativeNewInstance(is, tempStorage);
        }
    }

    /**
     * Create a BitmapRegionDecoder from a file path.
     * Currently only the JPEG, PNG, WebP and HEIF formats are supported.
     *
     * @param pathName complete path name for the file to be decoded.
     * @param isShareable This field has always been ignored.
     * @throws IOException if the image format is not supported or can not be decoded.
     * @deprecated In favor of {@link #newInstance(String)}
     */
    @Deprecated
    @NonNull
    public static BitmapRegionDecoder newInstance(@NonNull String pathName,
            boolean isShareable) throws IOException {
        return newInstance(pathName);
    }

    /**
     * Create a BitmapRegionDecoder from a file path.
     * Currently only the JPEG, PNG, WebP and HEIF formats are supported.
     *
     * @param pathName complete path name for the file to be decoded.
     * @throws IOException if the image format is not supported or can not be decoded.
     */
    @NonNull
    public static BitmapRegionDecoder newInstance(@NonNull String pathName) throws IOException {
        BitmapRegionDecoder decoder = null;
        InputStream stream = null;

        try {
            stream = new FileInputStream(pathName);
            decoder = newInstance(stream);
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException e) {
                    // do nothing here
                }
            }
        }
        return decoder;
    }

    /*  Private constructor that must receive an already allocated native
        region decoder int (pointer).

        This can be called from JNI code.
    */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private BitmapRegionDecoder(long decoder) {
        mNativeBitmapRegionDecoder = decoder;
        mRecycled = false;
    }

    /**
     * Decodes a rectangle region in the image specified by rect.
     *
     * @param rect The rectangle that specified the region to be decode.
     * @param options null-ok; Options that control downsampling.
     *             inPurgeable is not supported.
     * @return The decoded bitmap, or null if the image data could not be
     *         decoded.
     * @throws IllegalArgumentException if {@link BitmapFactory.Options#inPreferredConfig}
     *         is {@link android.graphics.Bitmap.Config#HARDWARE}
     *         and {@link BitmapFactory.Options#inMutable} is set, if the specified color space
     *         is not {@link ColorSpace.Model#RGB RGB}, or if the specified color space's transfer
     *         function is not an {@link ColorSpace.Rgb.TransferParameters ICC parametric curve}
     */
    public Bitmap decodeRegion(Rect rect, BitmapFactory.Options options) {
        BitmapFactory.Options.validate(options);
        synchronized (mNativeLock) {
            checkRecycled("decodeRegion called on recycled region decoder");
            if (rect.right <= 0 || rect.bottom <= 0 || rect.left >= getWidth()
                    || rect.top >= getHeight())
                throw new IllegalArgumentException("rectangle is outside the image");
            return nativeDecodeRegion(mNativeBitmapRegionDecoder, rect.left, rect.top,
                    rect.right - rect.left, rect.bottom - rect.top, options,
                    BitmapFactory.Options.nativeInBitmap(options),
                    BitmapFactory.Options.nativeColorSpace(options));
        }
    }

    /** Returns the original image's width */
    public int getWidth() {
        synchronized (mNativeLock) {
            checkRecycled("getWidth called on recycled region decoder");
            return nativeGetWidth(mNativeBitmapRegionDecoder);
        }
    }

    /** Returns the original image's height */
    public int getHeight() {
        synchronized (mNativeLock) {
            checkRecycled("getHeight called on recycled region decoder");
            return nativeGetHeight(mNativeBitmapRegionDecoder);
        }
    }

    /**
     * Frees up the memory associated with this region decoder, and mark the
     * region decoder as "dead", meaning it will throw an exception if decodeRegion(),
     * getWidth() or getHeight() is called.
     *
     * <p>This operation cannot be reversed, so it should only be called if you are
     * sure there are no further uses for the region decoder. This is an advanced call,
     * and normally need not be called, since the normal GC process will free up this
     * memory when there are no more references to this region decoder.
     */
    public void recycle() {
        synchronized (mNativeLock) {
            if (!mRecycled) {
                nativeClean(mNativeBitmapRegionDecoder);
                mRecycled = true;
            }
        }
    }

    /**
     * Returns true if this region decoder has been recycled.
     * If so, then it is an error to try use its method.
     *
     * @return true if the region decoder has been recycled
     */
    public final boolean isRecycled() {
        return mRecycled;
    }

    /**
     * Called by methods that want to throw an exception if the region decoder
     * has already been recycled.
     */
    private void checkRecycled(String errorMessage) {
        if (mRecycled) {
            throw new IllegalStateException(errorMessage);
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            recycle();
        } finally {
            super.finalize();
        }
    }

    private static native Bitmap nativeDecodeRegion(long lbm,
            int start_x, int start_y, int width, int height,
            BitmapFactory.Options options, long inBitmapHandle,
            long colorSpaceHandle);
    private static native int nativeGetWidth(long lbm);
    private static native int nativeGetHeight(long lbm);
    private static native void nativeClean(long lbm);

    @UnsupportedAppUsage
    private static native BitmapRegionDecoder nativeNewInstance(
            byte[] data, int offset, int length);
    private static native BitmapRegionDecoder nativeNewInstance(
            FileDescriptor fd);
    private static native BitmapRegionDecoder nativeNewInstance(
            InputStream is, byte[] storage);
    private static native BitmapRegionDecoder nativeNewInstance(
            long asset);
}

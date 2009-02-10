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

import android.os.Parcelable;
import android.os.Parcel;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.nio.IntBuffer;
import java.io.OutputStream;

public final class Bitmap implements Parcelable {
    /**
     * Indicates that the bitmap was created for an unknown pixel density.
     *
     * @see Bitmap#getDensityScale()
     * @see Bitmap#setDensityScale(float)
     *
     * @hide pending API council approval 
     */
    public static final float DENSITY_SCALE_UNKNOWN = -1.0f;

    // Note:  mNativeBitmap is used by FaceDetector_jni.cpp
    // Don't change/rename without updating FaceDetector_jni.cpp
    private final int mNativeBitmap;
    
    private final boolean mIsMutable;
    private byte[] mNinePatchChunk;   // may be null
    private int mWidth = -1;
    private int mHeight = -1;
    private boolean mRecycled;

    private static volatile Matrix sScaleMatrix;

    private float mDensityScale = DENSITY_SCALE_UNKNOWN;
    private boolean mAutoScaling;

    /**
     * @noinspection UnusedDeclaration
     */
    /*  Private constructor that must received an already allocated native
        bitmap int (pointer).

        This can be called from JNI code.
    */
    private Bitmap(int nativeBitmap, boolean isMutable, byte[] ninePatchChunk) {
        if (nativeBitmap == 0) {
            throw new RuntimeException("internal error: native bitmap is 0");
        }
        
        // we delete this in our finalizer
        mNativeBitmap = nativeBitmap;
        mIsMutable = isMutable;
        mNinePatchChunk = ninePatchChunk;
    }

    /**
     * <p>Returns the density scale for this bitmap, expressed as a factor of
     * the default density (160.) For instance, a bitmap designed for
     * displays with a density of 240 will have a density scale of 1.5 whereas a bitmap
     * designed for a density of 160 will have a density scale of 1.0.</p>
     *
     * <p>The default density scale is {@link #DENSITY_SCALE_UNKNOWN}.</p>
     *
     * @return A scaling factor of the default density (160) or {@link #DENSITY_SCALE_UNKNOWN}
     *         if the scaling factor is unknown.
     *
     * @see #setDensityScale(float)
     * @see #isAutoScalingEnabled()
     * @see #setAutoScalingEnabled(boolean) 
     * @see android.util.DisplayMetrics#DEFAULT_DENSITY
     * @see android.util.DisplayMetrics#density
     * @see #DENSITY_SCALE_UNKNOWN
     *
     * @hide pending API council approval
     */
    public float getDensityScale() {
        return mDensityScale;
    }

    /**
     * <p>Specifies the density scale for this bitmap, expressed as a factor of
     * the default density (160.) For instance, a bitmap designed for
     * displays with a density of 240 will have a density scale of 1.5 whereas a bitmap
     * designed for a density of 160 will have a density scale of 1.0.</p>
     *
     * @param densityScale The density scaling factor to use with this bitmap or
     *        {@link #DENSITY_SCALE_UNKNOWN} if the factor is unknown.
     *
     * @see #getDensityScale()
     * @see #isAutoScalingEnabled()
     * @see #setAutoScalingEnabled(boolean) 
     * @see android.util.DisplayMetrics#DEFAULT_DENSITY
     * @see android.util.DisplayMetrics#density
     * @see #DENSITY_SCALE_UNKNOWN
     *
     * @hide pending API council approval
     */
    public void setDensityScale(float densityScale) {
        mDensityScale = densityScale;
    }

    /**
     * </p>Indicates whether this bitmap will be automatically be scaled at the
     * target's density at drawing time. If auto scaling is enabled, this bitmap
     * will be drawn with the following scale factor:</p>
     *
     * <pre>scale = (bitmap density scale factor) / (target density scale factor)</pre>
     *
     * <p>Auto scaling is turned off by default. If auto scaling is enabled but the
     * bitmap has an unknown density scale, then the bitmap will never be automatically
     * scaled at drawing time.</p>
     * 
     * @return True if the bitmap must be scaled at drawing time, false otherwise.
     *
     * @see #setAutoScalingEnabled(boolean)
     * @see #getDensityScale()
     * @see #setDensityScale(float)
     *
     * @hide pending API council approval
     */
    public boolean isAutoScalingEnabled() {
        return mAutoScaling;
    }

    /**
     * <p>Enables or disables auto scaling for this bitmap. When auto scaling is enabled,
     * the bitmap will be scaled at drawing time to accomodate the drawing target's pixel
     * density. The final scale factor for this bitmap is thus defined:</p>
     *
     * <pre>scale = (bitmap density scale factor) / (target density scale factor)</pre>
     *
     * <p>If auto scaling is enabled but the bitmap has an unknown density scale, then
     * the bitmap will never be automatically scaled at drawing time.</p>
     *
     * @param autoScalingEnabled True to scale the bitmap at drawing time, false otherwise.
     *
     * @hide pending API council approval
     */
    public void setAutoScalingEnabled(boolean autoScalingEnabled) {
        mAutoScaling = autoScalingEnabled;
    }

    /**
     * Sets the nine patch chunk.
     *
     * @param chunk The definition of the nine patch
     *
     * @hide
     */
    public void setNinePatchChunk(byte[] chunk) {
        mNinePatchChunk = chunk;
    }
    
    /**
     * Free up the memory associated with this bitmap's pixels, and mark the
     * bitmap as "dead", meaning it will throw an exception if getPixels() or
     * setPixels() is called, and will draw nothing. This operation cannot be
     * reversed, so it should only be called if you are sure there are no
     * further uses for the bitmap. This is an advanced call, and normally need
     * not be called, since the normal GC process will free up this memory when
     * there are no more references to this bitmap.
     */
    public void recycle() {
        if (!mRecycled) {
            nativeRecycle(mNativeBitmap);
            mNinePatchChunk = null;
            mRecycled = true;
        }
    }

    /**
     * Returns true if this bitmap has been recycled. If so, then it is an error
     * to try to access its pixels, and the bitmap will not draw.
     *
     * @return true if the bitmap has been recycled
     */
    public final boolean isRecycled() {
        return mRecycled;
    }
    
    /**
     * This is called by methods that want to throw an exception if the bitmap
     * has already been recycled.
     */
    private void checkRecycled(String errorMessage) {
        if (mRecycled) {
            throw new IllegalStateException(errorMessage);
        }
    }
    
    /**
     * Common code for checking that x and y are >= 0
     *
     * @param x x coordinate to ensure is >= 0
     * @param y y coordinate to ensure is >= 0
     */
    private static void checkXYSign(int x, int y) {
        if (x < 0) {
            throw new IllegalArgumentException("x must be >= 0");
        }
        if (y < 0) {
            throw new IllegalArgumentException("y must be >= 0");
        }
    }

    /**
     * Common code for checking that width and height are > 0
     *
     * @param width  width to ensure is > 0
     * @param height height to ensure is > 0
     */
    private static void checkWidthHeight(int width, int height) {
        if (width <= 0) {
            throw new IllegalArgumentException("width must be > 0");
        }
        if (height <= 0) {
            throw new IllegalArgumentException("height must be > 0");
        }
    }

    public enum Config {
        // these native values must match up with the enum in SkBitmap.h
        ALPHA_8     (2),
        RGB_565     (4),
        ARGB_4444   (5),
        ARGB_8888   (6);

        Config(int ni) {
            this.nativeInt = ni;
        }
        final int nativeInt;
        
        /* package */ static Config nativeToConfig(int ni) {
            return sConfigs[ni];
        }
        
        private static Config sConfigs[] = {
            null, null, ALPHA_8, null, RGB_565, ARGB_4444, ARGB_8888
        };
    }
    
    /**
     * Copy the bitmap's pixels into the specified buffer (allocated by the
     * caller). An exception is thrown if the buffer is not large enough to
     * hold all of the pixels (taking into account the number of bytes per
     * pixel) or if the Buffer subclass is not one of the support types
     * (ByteBuffer, ShortBuffer, IntBuffer).
     */
    public void copyPixelsToBuffer(Buffer dst) {
        int elements = dst.remaining();
        int shift;
        if (dst instanceof ByteBuffer) {
            shift = 0;
        } else if (dst instanceof ShortBuffer) {
            shift = 1;
        } else if (dst instanceof IntBuffer) {
            shift = 2;
        } else {
            throw new RuntimeException("unsupported Buffer subclass");
        }
        
        long bufferSize = (long)elements << shift;
        long pixelSize = (long)getRowBytes() * getHeight();
        
        if (bufferSize < pixelSize) {
            throw new RuntimeException("Buffer not large enough for pixels");
        }
        
        nativeCopyPixelsToBuffer(mNativeBitmap, dst);
        
        // now update the buffer's position
        int position = dst.position();
        position += pixelSize >> shift;
        dst.position(position);
    }

    /**
     * Copy the pixels from the buffer, beginning at the current position,
     * overwriting the bitmap's pixels. The data in the buffer is not changed
     * in any way (unlike setPixels(), which converts from unpremultipled 32bit
     * to whatever the bitmap's native format is.
     */
    public void copyPixelsFromBuffer(Buffer src) {
        checkRecycled("copyPixelsFromBuffer called on recycled bitmap");
        
        int elements = src.remaining();
        int shift;
        if (src instanceof ByteBuffer) {
            shift = 0;
        } else if (src instanceof ShortBuffer) {
            shift = 1;
        } else if (src instanceof IntBuffer) {
            shift = 2;
        } else {
            throw new RuntimeException("unsupported Buffer subclass");
        }
        
        long bufferBytes = (long)elements << shift;
        long bitmapBytes = (long)getRowBytes() * getHeight();
        
        if (bufferBytes < bitmapBytes) {
            throw new RuntimeException("Buffer not large enough for pixels");
        }
        
        nativeCopyPixelsFromBuffer(mNativeBitmap, src);
    }
        
    /**
     * Tries to make a new bitmap based on the dimensions of this bitmap,
     * setting the new bitmap's config to the one specified, and then copying
     * this bitmap's pixels into the new bitmap. If the conversion is not
     * supported, or the allocator fails, then this returns NULL.
     *
     * @param config    The desired config for the resulting bitmap
     * @param isMutable True if the resulting bitmap should be mutable (i.e.
     *                  its pixels can be modified)
     * @return the new bitmap, or null if the copy could not be made.
     */
    public Bitmap copy(Config config, boolean isMutable) {
        checkRecycled("Can't copy a recycled bitmap");
        return nativeCopy(mNativeBitmap, config.nativeInt, isMutable);
    }

    public static Bitmap createScaledBitmap(Bitmap src, int dstWidth,
            int dstHeight, boolean filter) {
        Matrix m;
        synchronized (Bitmap.class) {
            // small pool of just 1 matrix
            m = sScaleMatrix;
            sScaleMatrix = null;
        }

        if (m == null) {
            m = new Matrix();
        }
        
        final int width = src.getWidth();
        final int height = src.getHeight();
        final float sx = dstWidth  / (float)width;
        final float sy = dstHeight / (float)height;
        m.setScale(sx, sy);
        Bitmap b = Bitmap.createBitmap(src, 0, 0, width, height, m, filter);

        synchronized (Bitmap.class) {
            // do we need to check for null? why not just assign everytime?
            if (sScaleMatrix == null) {
                sScaleMatrix = m;
            }
        }

        return b; 
    }
    
    /**
     * Returns an immutable bitmap from the source bitmap. The new bitmap may
     * be the same object as source, or a copy may have been made.
     */
    public static Bitmap createBitmap(Bitmap src) {
        return createBitmap(src, 0, 0, src.getWidth(), src.getHeight());
    }

    /**
     * Returns an immutable bitmap from the specified subset of the source
     * bitmap. The new bitmap may be the same object as source, or a copy may
     * have been made.
     *
     * @param source   The bitmap we are subsetting
     * @param x        The x coordinate of the first pixel in source
     * @param y        The y coordinate of the first pixel in source
     * @param width    The number of pixels in each row
     * @param height   The number of rows
     */
    public static Bitmap createBitmap(Bitmap source, int x, int y, int width, int height) {
        return createBitmap(source, x, y, width, height, null, false);
    }
    
    /**
     * Returns an immutable bitmap from subset of the source bitmap,
     * transformed by the optional matrix.
     *
     * @param source   The bitmap we are subsetting
     * @param x        The x coordinate of the first pixel in source
     * @param y        The y coordinate of the first pixel in source
     * @param width    The number of pixels in each row
     * @param height   The number of rows
     * @param m        Option matrix to be applied to the pixels
     * @param filter   true if the source should be filtered.
     *                   Only applies if the matrix contains more than just
     *                   translation.
     * @return A bitmap that represents the specified subset of source
     * @throws IllegalArgumentException if the x, y, width, height values are
     *         outside of the dimensions of the source bitmap.
     */
    public static Bitmap createBitmap(Bitmap source, int x, int y, int width, int height,
            Matrix m, boolean filter) {

        checkXYSign(x, y);
        checkWidthHeight(width, height);
        if (x + width > source.getWidth()) {
            throw new IllegalArgumentException("x + width must be <= bitmap.width()");
        }
        if (y + height > source.getHeight()) {
            throw new IllegalArgumentException("y + height must be <= bitmap.height()");
        }

        // check if we can just return our argument unchanged
        if (!source.isMutable() && x == 0 && y == 0 && width == source.getWidth() &&
                height == source.getHeight() && (m == null || m.isIdentity())) {
            return source;
        }
        
        int neww = width;
        int newh = height;
        Canvas canvas = new Canvas();
        Bitmap bitmap;
        Paint paint;

        Rect srcR = new Rect(x, y, x + width, y + height);
        RectF dstR = new RectF(0, 0, width, height);

        if (m == null || m.isIdentity()) {
            bitmap = createBitmap(neww, newh,
                    source.hasAlpha() ? Config.ARGB_8888 : Config.RGB_565);
            paint = null;   // not needed
        } else {
            /*  the dst should have alpha if the src does, or if our matrix
                doesn't preserve rectness
            */
            boolean hasAlpha = source.hasAlpha() || !m.rectStaysRect();
            RectF deviceR = new RectF();
            m.mapRect(deviceR, dstR);
            neww = Math.round(deviceR.width());
            newh = Math.round(deviceR.height());
            bitmap = createBitmap(neww, newh, hasAlpha ? Config.ARGB_8888 : Config.RGB_565);
            if (hasAlpha) {
                bitmap.eraseColor(0);
            }
            canvas.translate(-deviceR.left, -deviceR.top);
            canvas.concat(m);
            paint = new Paint();
            paint.setFilterBitmap(filter);
            if (!m.rectStaysRect()) {
                paint.setAntiAlias(true);
            }
        }
        canvas.setBitmap(bitmap);
        canvas.drawBitmap(source, srcR, dstR, paint);

        // The new bitmap was created from a known bitmap source so assume that
        // they use the same density scale
        bitmap.setDensityScale(source.getDensityScale());
        bitmap.setAutoScalingEnabled(source.isAutoScalingEnabled());

        return bitmap;
    }
    
    /**
     * Returns a mutable bitmap with the specified width and height.
     *
     * @param width    The width of the bitmap
     * @param height   The height of the bitmap
     * @param config   The bitmap config to create.
     * @throws IllegalArgumentException if the width or height are <= 0
     */
    public static Bitmap createBitmap(int width, int height, Config config) {
        Bitmap bm = nativeCreate(null, 0, width, width, height, config.nativeInt, true);
        bm.eraseColor(0);    // start with black/transparent pixels
        return bm;
    }
    
    /**
     * Returns a immutable bitmap with the specified width and height, with each
     * pixel value set to the corresponding value in the colors array.
     *
     * @param colors   Array of {@link Color} used to initialize the pixels.
     * @param offset   Number of values to skip before the first color in the
     *                 array of colors.
     * @param stride   Number of colors in the array between rows (must be >=
     *                 width or <= -width).
     * @param width    The width of the bitmap
     * @param height   The height of the bitmap
     * @param config   The bitmap config to create. If the config does not
     *                 support per-pixel alpha (e.g. RGB_565), then the alpha
     *                 bytes in the colors[] will be ignored (assumed to be FF)
     * @throws IllegalArgumentException if the width or height are <= 0, or if
     *         the color array's length is less than the number of pixels.
     */
    public static Bitmap createBitmap(int colors[], int offset, int stride,
            int width, int height, Config config) {

        checkWidthHeight(width, height);
        if (Math.abs(stride) < width) {
            throw new IllegalArgumentException("abs(stride) must be >= width");
        }
        int lastScanline = offset + (height - 1) * stride;
        int length = colors.length;
        if (offset < 0 || (offset + width > length) || lastScanline < 0 ||
                (lastScanline + width > length)) {
            throw new ArrayIndexOutOfBoundsException();
        }
        return nativeCreate(colors, offset, stride, width, height,
                            config.nativeInt, false);
    }

    /**
     * Returns a immutable bitmap with the specified width and height, with each
     * pixel value set to the corresponding value in the colors array.
     *
     * @param colors   Array of {@link Color} used to initialize the pixels.
     *                 This array must be at least as large as width * height.
     * @param width    The width of the bitmap
     * @param height   The height of the bitmap
     * @param config   The bitmap config to create. If the config does not
     *                 support per-pixel alpha (e.g. RGB_565), then the alpha
     *                 bytes in the colors[] will be ignored (assumed to be FF)
     * @throws IllegalArgumentException if the width or height are <= 0, or if
     *         the color array's length is less than the number of pixels.
     */
    public static Bitmap createBitmap(int colors[], int width, int height, Config config) {
        return createBitmap(colors, 0, width, width, height, config);
    }

    /**
     * Returns an optional array of private data, used by the UI system for
     * some bitmaps. Not intended to be called by applications.
     */
    public byte[] getNinePatchChunk() {
        return mNinePatchChunk;
    }

    /**
     * Specifies the known formats a bitmap can be compressed into
     */
    public enum CompressFormat {
        JPEG    (0),
        PNG     (1);

        CompressFormat(int nativeInt) {
            this.nativeInt = nativeInt;
        }
        final int nativeInt;
    }

    /**
     * Number of bytes of temp storage we use for communicating between the
     * native compressor and the java OutputStream.
     */
    private final static int WORKING_COMPRESS_STORAGE = 4096;

    /**
     * Write a compressed version of the bitmap to the specified outputstream.
     * If this returns true, the bitmap can be reconstructed by passing a
     * corresponding inputstream to BitmapFactory.decodeStream(). Note: not
     * all Formats support all bitmap configs directly, so it is possible that
     * the returned bitmap from BitmapFactory could be in a different bitdepth,
     * and/or may have lost per-pixel alpha (e.g. JPEG only supports opaque
     * pixels).
     *
     * @param format   The format of the compressed image
     * @param quality  Hint to the compressor, 0-100. 0 meaning compress for
     *                 small size, 100 meaning compress for max quality. Some
     *                 formats, like PNG which is lossless, will ignore the
     *                 quality setting
     * @param stream   The outputstream to write the compressed data.
     * @return true if successfully compressed to the specified stream.
     */
    public boolean compress(CompressFormat format, int quality, OutputStream stream) {
        checkRecycled("Can't compress a recycled bitmap");
        // do explicit check before calling the native method
        if (stream == null) {
            throw new NullPointerException();
        }
        if (quality < 0 || quality > 100) {
            throw new IllegalArgumentException("quality must be 0..100");
        }
        return nativeCompress(mNativeBitmap, format.nativeInt, quality,
                              stream, new byte[WORKING_COMPRESS_STORAGE]);
    }
    
    /**
     * Returns true if the bitmap is marked as mutable (i.e. can be drawn into)
     */
    public final boolean isMutable() {
        return mIsMutable;
    }

    /** Returns the bitmap's width */
    public final int getWidth() {
        return mWidth == -1 ? mWidth = nativeWidth(mNativeBitmap) : mWidth;
    }

    /** Returns the bitmap's height */
    public final int getHeight() {
        return mHeight == -1 ? mHeight = nativeHeight(mNativeBitmap) : mHeight;
    }
    
    /**
     * Convenience method that returns the width of this bitmap divided
     * by the density scale factor.
     *
     * @return The scaled width of this bitmap, according to the density scale factor.
     *
     * @hide pending API council approval
     */
    public int getScaledWidth() {
        final float scale = getDensityScale();
        return scale == DENSITY_SCALE_UNKNOWN ? getWidth() : (int) (getWidth() / scale);
    }

    /**
     * Convenience method that returns the height of this bitmap divided
     * by the density scale factor.
     *
     * @return The scaled height of this bitmap, according to the density scale factor.
     *
     * @hide pending API council approval
     */
    public int getScaledHeight() {
        final float scale = getDensityScale();
        return scale == DENSITY_SCALE_UNKNOWN ? getWidth() : (int) (getHeight() / scale);
    }

    /**
     * Return the number of bytes between rows in the bitmap's pixels. Note that
     * this refers to the pixels as stored natively by the bitmap. If you call
     * getPixels() or setPixels(), then the pixels are uniformly treated as
     * 32bit values, packed according to the Color class.
     *
     * @return number of bytes between rows of the native bitmap pixels.
     */
    public final int getRowBytes() {
        return nativeRowBytes(mNativeBitmap);
    }
    
    /**
     * If the bitmap's internal config is in one of the public formats, return
     * that config, otherwise return null.
     */
    public final Config getConfig() {
        return Config.nativeToConfig(nativeConfig(mNativeBitmap));
    }

    /** Returns true if the bitmap's pixels support levels of alpha */
    public final boolean hasAlpha() {
        return nativeHasAlpha(mNativeBitmap);
    }

    /**
     * Fills the bitmap's pixels with the specified {@link Color}.
     *
     * @throws IllegalStateException if the bitmap is not mutable.
     */
    public void eraseColor(int c) {
        checkRecycled("Can't erase a recycled bitmap");
        if (!isMutable()) {
            throw new IllegalStateException("cannot erase immutable bitmaps");
        }
        nativeErase(mNativeBitmap, c);
    }

    /**
     * Returns the {@link Color} at the specified location. Throws an exception
     * if x or y are out of bounds (negative or >= to the width or height
     * respectively).
     *
     * @param x    The x coordinate (0...width-1) of the pixel to return
     * @param y    The y coordinate (0...height-1) of the pixel to return
     * @return     The argb {@link Color} at the specified coordinate
     * @throws IllegalArgumentException if x, y exceed the bitmap's bounds
     */
    public int getPixel(int x, int y) {
        checkRecycled("Can't call getPixel() on a recycled bitmap");
        checkPixelAccess(x, y);
        return nativeGetPixel(mNativeBitmap, x, y);
    }
    
    /**
     * Returns in pixels[] a copy of the data in the bitmap. Each value is
     * a packed int representing a {@link Color}. The stride parameter allows
     * the caller to allow for gaps in the returned pixels array between
     * rows. For normal packed results, just pass width for the stride value.
     *
     * @param pixels   The array to receive the bitmap's colors
     * @param offset   The first index to write into pixels[]
     * @param stride   The number of entries in pixels[] to skip between
     *                 rows (must be >= bitmap's width). Can be negative.
     * @param x        The x coordinate of the first pixel to read from
     *                 the bitmap
     * @param y        The y coordinate of the first pixel to read from
     *                 the bitmap
     * @param width    The number of pixels to read from each row
     * @param height   The number of rows to read
     * @throws IllegalArgumentException if x, y, width, height exceed the
     *         bounds of the bitmap, or if abs(stride) < width.
     * @throws ArrayIndexOutOfBoundsException if the pixels array is too small
     *         to receive the specified number of pixels.
     */
    public void getPixels(int[] pixels, int offset, int stride,
                          int x, int y, int width, int height) {
        checkRecycled("Can't call getPixels() on a recycled bitmap");
        if (width == 0 || height == 0) {
            return; // nothing to do
        }
        checkPixelsAccess(x, y, width, height, offset, stride, pixels);
        nativeGetPixels(mNativeBitmap, pixels, offset, stride,
                        x, y, width, height);
    }
    
    /**
     * Shared code to check for illegal arguments passed to getPixel()
     * or setPixel()
     * @param x x coordinate of the pixel
     * @param y y coordinate of the pixel
     */
    private void checkPixelAccess(int x, int y) {
        checkXYSign(x, y);
        if (x >= getWidth()) {
            throw new IllegalArgumentException("x must be < bitmap.width()");
        }
        if (y >= getHeight()) {
            throw new IllegalArgumentException("y must be < bitmap.height()");
        }
    }

    /**
     * Shared code to check for illegal arguments passed to getPixels()
     * or setPixels()
     *
     * @param x left edge of the area of pixels to access
     * @param y top edge of the area of pixels to access
     * @param width width of the area of pixels to access
     * @param height height of the area of pixels to access
     * @param offset offset into pixels[] array
     * @param stride number of elements in pixels[] between each logical row
     * @param pixels array to hold the area of pixels being accessed
    */
    private void checkPixelsAccess(int x, int y, int width, int height,
                                   int offset, int stride, int pixels[]) {
        checkXYSign(x, y);
        if (width < 0) {
            throw new IllegalArgumentException("width must be >= 0");
        }
        if (height < 0) {
            throw new IllegalArgumentException("height must be >= 0");
        }
        if (x + width > getWidth()) {
            throw new IllegalArgumentException(
                    "x + width must be <= bitmap.width()");
        }
        if (y + height > getHeight()) {
            throw new IllegalArgumentException(
                    "y + height must be <= bitmap.height()");
        }
        if (Math.abs(stride) < width) {
            throw new IllegalArgumentException("abs(stride) must be >= width");
        }
        int lastScanline = offset + (height - 1) * stride;
        int length = pixels.length;
        if (offset < 0 || (offset + width > length)
                || lastScanline < 0
                || (lastScanline + width > length)) {
            throw new ArrayIndexOutOfBoundsException();
        }
    }
    
    /**
     * Write the specified {@link Color} into the bitmap (assuming it is
     * mutable) at the x,y coordinate.
     *
     * @param x     The x coordinate of the pixel to replace (0...width-1)
     * @param y     The y coordinate of the pixel to replace (0...height-1)
     * @param color The {@link Color} to write into the bitmap
     * @throws IllegalStateException if the bitmap is not mutable
     * @throws IllegalArgumentException if x, y are outside of the bitmap's
     *         bounds.
     */
    public void setPixel(int x, int y, int color) {
        checkRecycled("Can't call setPixel() on a recycled bitmap");
        if (!isMutable()) {
            throw new IllegalStateException();
        }
        checkPixelAccess(x, y);
        nativeSetPixel(mNativeBitmap, x, y, color);
    }
    
    /**
     * Replace pixels in the bitmap with the colors in the array. Each element
     * in the array is a packed int prepresenting a {@link Color} 
     *
     * @param pixels   The colors to write to the bitmap
     * @param offset   The index of the first color to read from pixels[]
     * @param stride   The number of colors in pixels[] to skip between rows.
     *                 Normally this value will be the same as the width of
     *                 the bitmap, but it can be larger (or negative).
     * @param x        The x coordinate of the first pixel to write to in
     *                 the bitmap.
     * @param y        The y coordinate of the first pixel to write to in
     *                 the bitmap.
     * @param width    The number of colors to copy from pixels[] per row
     * @param height   The number of rows to write to the bitmap
     * @throws IllegalStateException if the bitmap is not mutable
     * @throws IllegalArgumentException if x, y, width, height are outside of
     *         the bitmap's bounds.
     * @throws ArrayIndexOutOfBoundsException if the pixels array is too small
     *         to receive the specified number of pixels.
     */
    public void setPixels(int[] pixels, int offset, int stride,
                          int x, int y, int width, int height) {
        checkRecycled("Can't call setPixels() on a recycled bitmap");
        if (!isMutable()) {
            throw new IllegalStateException();
        }
        if (width == 0 || height == 0) {
            return; // nothing to do
        }
        checkPixelsAccess(x, y, width, height, offset, stride, pixels);
        nativeSetPixels(mNativeBitmap, pixels, offset, stride,
                        x, y, width, height);
    }
    
    public static final Parcelable.Creator<Bitmap> CREATOR
            = new Parcelable.Creator<Bitmap>() {
        /**
         * Rebuilds a bitmap previously stored with writeToParcel().
         *
         * @param p    Parcel object to read the bitmap from
         * @return a new bitmap created from the data in the parcel
         */
        public Bitmap createFromParcel(Parcel p) {
            Bitmap bm = nativeCreateFromParcel(p);
            if (bm == null) {
                throw new RuntimeException("Failed to unparcel Bitmap");
            }
            return bm;
        }
        public Bitmap[] newArray(int size) {
            return new Bitmap[size];
        }
    };

    /**
     * No special parcel contents.
     */
    public int describeContents() {
        return 0;
    }

    /**
     * Write the bitmap and its pixels to the parcel. The bitmap can be
     * rebuilt from the parcel by calling CREATOR.createFromParcel().
     * @param p    Parcel object to write the bitmap data into
     */
    public void writeToParcel(Parcel p, int flags) {
        checkRecycled("Can't parcel a recycled bitmap");
        if (!nativeWriteToParcel(mNativeBitmap, mIsMutable, p)) {
            throw new RuntimeException("native writeToParcel failed");
        }
    }

    /**
     * Returns a new bitmap that captures the alpha values of the original.
     * This may be drawn with Canvas.drawBitmap(), where the color(s) will be
     * taken from the paint that is passed to the draw call.
     *
     * @return new bitmap containing the alpha channel of the original bitmap.
     */
    public Bitmap extractAlpha() {
        return extractAlpha(null, null);
    }
    
    /**
     * Returns a new bitmap that captures the alpha values of the original.
     * These values may be affected by the optional Paint parameter, which
     * can contain its own alpha, and may also contain a MaskFilter which
     * could change the actual dimensions of the resulting bitmap (e.g.
     * a blur maskfilter might enlarge the resulting bitmap). If offsetXY
     * is not null, it returns the amount to offset the returned bitmap so
     * that it will logically align with the original. For example, if the
     * paint contains a blur of radius 2, then offsetXY[] would contains
     * -2, -2, so that drawing the alpha bitmap offset by (-2, -2) and then
     * drawing the original would result in the blur visually aligning with
     * the original.
     * @param paint Optional paint used to modify the alpha values in the
     *              resulting bitmap. Pass null for default behavior.
     * @param offsetXY Optional array that returns the X (index 0) and Y
     *                 (index 1) offset needed to position the returned bitmap
     *                 so that it visually lines up with the original.
     * @return new bitmap containing the (optionally modified by paint) alpha
     *         channel of the original bitmap. This may be drawn with
     *         Canvas.drawBitmap(), where the color(s) will be taken from the
     *         paint that is passed to the draw call.
     */
    public Bitmap extractAlpha(Paint paint, int[] offsetXY) {
        checkRecycled("Can't extractAlpha on a recycled bitmap");
        int nativePaint = paint != null ? paint.mNativePaint : 0;
        Bitmap bm = nativeExtractAlpha(mNativeBitmap, nativePaint, offsetXY);
        if (bm == null) {
            throw new RuntimeException("Failed to extractAlpha on Bitmap");
        }
        return bm;
    }

    protected void finalize() throws Throwable {
        try {
            nativeDestructor(mNativeBitmap);
        } finally {
            super.finalize();
        }
    }
    
    //////////// native methods

    private static native Bitmap nativeCreate(int[] colors, int offset,
                                              int stride, int width, int height,
                                            int nativeConfig, boolean mutable);
    private static native Bitmap nativeCopy(int srcBitmap, int nativeConfig,
                                            boolean isMutable);
    private static native void nativeDestructor(int nativeBitmap);
    private static native void nativeRecycle(int nativeBitmap);

    private static native boolean nativeCompress(int nativeBitmap, int format,
                                            int quality, OutputStream stream,
                                            byte[] tempStorage);
    private static native void nativeErase(int nativeBitmap, int color);
    private static native int nativeWidth(int nativeBitmap);
    private static native int nativeHeight(int nativeBitmap);
    private static native int nativeRowBytes(int nativeBitmap);
    private static native int nativeConfig(int nativeBitmap);
    private static native boolean nativeHasAlpha(int nativeBitmap);
    
    private static native int nativeGetPixel(int nativeBitmap, int x, int y);
    private static native void nativeGetPixels(int nativeBitmap, int[] pixels,
                                               int offset, int stride, int x,
                                               int y, int width, int height);
    
    private static native void nativeSetPixel(int nativeBitmap, int x, int y,
                                              int color);
    private static native void nativeSetPixels(int nativeBitmap, int[] colors,
                                               int offset, int stride, int x,
                                               int y, int width, int height);
    private static native void nativeCopyPixelsToBuffer(int nativeBitmap,
                                                        Buffer dst);
    private static native void nativeCopyPixelsFromBuffer(int nb, Buffer src);

    private static native Bitmap nativeCreateFromParcel(Parcel p);
    // returns true on success
    private static native boolean nativeWriteToParcel(int nativeBitmap,
                                                      boolean isMutable,
                                                      Parcel p);
    // returns a new bitmap built from the native bitmap's alpha, and the paint
    private static native Bitmap nativeExtractAlpha(int nativeBitmap,
                                                    int nativePaint,
                                                    int[] offsetXY);

    /* package */ final int ni() {
        return mNativeBitmap;
    }
}

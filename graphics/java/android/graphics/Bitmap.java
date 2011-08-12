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

public final class Bitmap implements Parcelable {

    /**
     * Indicates that the bitmap was created for an unknown pixel density.
     *
     * @see Bitmap#getDensity()
     * @see Bitmap#setDensity(int)
     */
    public static final int DENSITY_NONE = 0;
    
    /**
     * Note:  mNativeBitmap is used by FaceDetector_jni.cpp
     * Don't change/rename without updating FaceDetector_jni.cpp
     * 
     * @hide
     */
    public final int mNativeBitmap;

    /**
     * Backing buffer for the Bitmap.
     * Made public for quick access from drawing methods -- do NOT modify
     * from outside this class.
     *
     * @hide
     */
    public byte[] mBuffer;

    @SuppressWarnings({"FieldCanBeLocal", "UnusedDeclaration"}) // Keep to finalize native resources
    private final BitmapFinalizer mFinalizer;

    private final boolean mIsMutable;
    private byte[] mNinePatchChunk;   // may be null
    private int mWidth = -1;
    private int mHeight = -1;
    private boolean mRecycled;

    // Package-scoped for fast access.
    /*package*/ int mDensity = sDefaultDensity = getDefaultDensity();

    private static volatile Matrix sScaleMatrix;

    private static volatile int sDefaultDensity = -1;

    /**
     * For backwards compatibility, allows the app layer to change the default
     * density when running old apps.
     * @hide
     */
    public static void setDefaultDensity(int density) {
        sDefaultDensity = density;
    }
    
    /*package*/ static int getDefaultDensity() {
        if (sDefaultDensity >= 0) {
            return sDefaultDensity;
        }
        sDefaultDensity = DisplayMetrics.DENSITY_DEVICE;
        return sDefaultDensity;
    }
    
    /**
     * @noinspection UnusedDeclaration
     */
    /*  Private constructor that must received an already allocated native
        bitmap int (pointer).

        This can be called from JNI code.
    */
    /*package*/ Bitmap(int nativeBitmap, byte[] buffer, boolean isMutable, byte[] ninePatchChunk,
            int density) {
        if (nativeBitmap == 0) {
            throw new RuntimeException("internal error: native bitmap is 0");
        }

        mBuffer = buffer;
        // we delete this in our finalizer
        mNativeBitmap = nativeBitmap;
        mFinalizer = new BitmapFinalizer(nativeBitmap);

        mIsMutable = isMutable;
        mNinePatchChunk = ninePatchChunk;
        if (density >= 0) {
            mDensity = density;
        }
    }

    /**
     * <p>Returns the density for this bitmap.</p>
     *
     * <p>The default density is the same density as the current display,
     * unless the current application does not support different screen
     * densities in which case it is
     * {@link android.util.DisplayMetrics#DENSITY_DEFAULT}.  Note that
     * compatibility mode is determined by the application that was initially
     * loaded into a process -- applications that share the same process should
     * all have the same compatibility, or ensure they explicitly set the
     * density of their bitmaps appropriately.</p>
     *
     * @return A scaling factor of the default density or {@link #DENSITY_NONE}
     *         if the scaling factor is unknown.
     *
     * @see #setDensity(int)
     * @see android.util.DisplayMetrics#DENSITY_DEFAULT
     * @see android.util.DisplayMetrics#densityDpi
     * @see #DENSITY_NONE
     */
    public int getDensity() {
        return mDensity;
    }

    /**
     * <p>Specifies the density for this bitmap.  When the bitmap is
     * drawn to a Canvas that also has a density, it will be scaled
     * appropriately.</p>
     *
     * @param density The density scaling factor to use with this bitmap or
     *        {@link #DENSITY_NONE} if the density is unknown.
     *
     * @see #getDensity()
     * @see android.util.DisplayMetrics#DENSITY_DEFAULT
     * @see android.util.DisplayMetrics#densityDpi
     * @see #DENSITY_NONE
     */
    public void setDensity(int density) {
        mDensity = density;
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
     * Free the native object associated with this bitmap, and clear the
     * reference to the pixel data. This will not free the pixel data synchronously;
     * it simply allows it to be garbage collected if there are no other references.
     * The bitmap is marked as "dead", meaning it will throw an exception if
     * getPixels() or setPixels() is called, and will draw nothing. This operation
     * cannot be reversed, so it should only be called if you are sure there are no
     * further uses for the bitmap. This is an advanced call, and normally need
     * not be called, since the normal GC process will free up this memory when
     * there are no more references to this bitmap.
     */
    public void recycle() {
        if (!mRecycled) {
            mBuffer = null;
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
     * Returns the generation ID of this bitmap. The generation ID changes
     * whenever the bitmap is modified. This can be used as an efficient way to
     * check if a bitmap has changed.
     * 
     * @return The current generation ID for this bitmap.
     */
    public int getGenerationId() {
        return nativeGenerationId(mNativeBitmap);
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

    /**
     * Possible bitmap configurations. A bitmap configuration describes
     * how pixels are stored. This affects the quality (color depth) as
     * well as the ability to display transparent/translucent colors.
     */
    public enum Config {
        // these native values must match up with the enum in SkBitmap.h

        /**
         * Each pixel is stored as a single translucency (alpha) channel.
         * This is very useful to efficiently store masks for instance.
         * No color information is stored.
         * With this configuration, each pixel requires 1 byte of memory.
         */
        ALPHA_8     (2),

        /**
         * Each pixel is stored on 2 bytes and only the RGB channels are
         * encoded: red is stored with 5 bits of precision (32 possible
         * values), green is stored with 6 bits of precision (64 possible
         * values) and blue is stored with 5 bits of precision.
         * 
         * This configuration can produce slight visual artifacts depending
         * on the configuration of the source. For instance, without
         * dithering, the result might show a greenish tint. To get better
         * results dithering should be applied.
         * 
         * This configuration may be useful when using opaque bitmaps
         * that do not require high color fidelity.
         */
        RGB_565     (4),

        /**
         * Each pixel is stored on 2 bytes. The three RGB color channels
         * and the alpha channel (translucency) are stored with a 4 bits
         * precision (16 possible values.)
         * 
         * This configuration is mostly useful if the application needs
         * to store translucency information but also needs to save
         * memory.
         * 
         * It is recommended to use {@link #ARGB_8888} instead of this
         * configuration.
         * 
         * @deprecated Because of the poor quality of this configuration,
         *             it is advised to use {@link #ARGB_8888} instead.
         */
        @Deprecated
        ARGB_4444   (5),

        /**
         * Each pixel is stored on 4 bytes. Each channel (RGB and alpha
         * for translucency) is stored with 8 bits of precision (256
         * possible values.)
         * 
         * This configuration is very flexible and offers the best
         * quality. It should be used whenever possible.
         */
        ARGB_8888   (6);

        final int nativeInt;

        @SuppressWarnings({"deprecation"})
        private static Config sConfigs[] = {
            null, null, ALPHA_8, null, RGB_565, ARGB_4444, ARGB_8888
        };
        
        Config(int ni) {
            this.nativeInt = ni;
        }

        static Config nativeToConfig(int ni) {
            return sConfigs[ni];
        }
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
        long pixelSize = getByteCount();

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
        long bitmapBytes = getByteCount();

        if (bufferBytes < bitmapBytes) {
            throw new RuntimeException("Buffer not large enough for pixels");
        }

        nativeCopyPixelsFromBuffer(mNativeBitmap, src);
    }

    /**
     * Tries to make a new bitmap based on the dimensions of this bitmap,
     * setting the new bitmap's config to the one specified, and then copying
     * this bitmap's pixels into the new bitmap. If the conversion is not
     * supported, or the allocator fails, then this returns NULL.  The returned
     * bitmap initially has the same density as the original.
     *
     * @param config    The desired config for the resulting bitmap
     * @param isMutable True if the resulting bitmap should be mutable (i.e.
     *                  its pixels can be modified)
     * @return the new bitmap, or null if the copy could not be made.
     */
    public Bitmap copy(Config config, boolean isMutable) {
        checkRecycled("Can't copy a recycled bitmap");
        Bitmap b = nativeCopy(mNativeBitmap, config.nativeInt, isMutable);
        if (b != null) {
            b.mDensity = mDensity;
        }
        return b;
    }

    /**
     * Creates a new bitmap, scaled from an existing bitmap.
     *
     * @param src       The source bitmap.
     * @param dstWidth  The new bitmap's desired width.
     * @param dstHeight The new bitmap's desired height.
     * @param filter    true if the source should be filtered.
     * @return the new scaled bitmap.
     */
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
     * be the same object as source, or a copy may have been made.  It is
     * initialized with the same density as the original bitmap.
     */
    public static Bitmap createBitmap(Bitmap src) {
        return createBitmap(src, 0, 0, src.getWidth(), src.getHeight());
    }

    /**
     * Returns an immutable bitmap from the specified subset of the source
     * bitmap. The new bitmap may be the same object as source, or a copy may
     * have been made.  It is
     * initialized with the same density as the original bitmap.
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
     * transformed by the optional matrix.  It is
     * initialized with the same density as the original bitmap.
     *
     * @param source   The bitmap we are subsetting
     * @param x        The x coordinate of the first pixel in source
     * @param y        The y coordinate of the first pixel in source
     * @param width    The number of pixels in each row
     * @param height   The number of rows
     * @param m        Optional matrix to be applied to the pixels
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

        Config newConfig = Config.ARGB_8888;
        final Config config = source.getConfig();
        // GIF files generate null configs, assume ARGB_8888
        if (config != null) {
            switch (config) {
                case RGB_565:
                    newConfig = Config.RGB_565;
                    break;
                case ALPHA_8:
                    newConfig = Config.ALPHA_8;
                    break;
                //noinspection deprecation
                case ARGB_4444:
                case ARGB_8888:
                default:
                    newConfig = Config.ARGB_8888;
                    break;
            }
        }

        if (m == null || m.isIdentity()) {
            bitmap = createBitmap(neww, newh, newConfig, source.hasAlpha());
            paint = null;   // not needed
        } else {
            final boolean transformed = !m.rectStaysRect();

            RectF deviceR = new RectF();
            m.mapRect(deviceR, dstR);

            neww = Math.round(deviceR.width());
            newh = Math.round(deviceR.height());

            bitmap = createBitmap(neww, newh, transformed ? Config.ARGB_8888 : newConfig,
                    transformed || source.hasAlpha());

            canvas.translate(-deviceR.left, -deviceR.top);
            canvas.concat(m);

            paint = new Paint();
            paint.setFilterBitmap(filter);
            if (transformed) {
                paint.setAntiAlias(true);
            }
        }
        
        // The new bitmap was created from a known bitmap source so assume that
        // they use the same density
        bitmap.mDensity = source.mDensity;
        
        canvas.setBitmap(bitmap);
        canvas.drawBitmap(source, srcR, dstR, paint);
        canvas.setBitmap(null);

        return bitmap;
    }

    /**
     * Returns a mutable bitmap with the specified width and height.  Its
     * initial density is as per {@link #getDensity}.
     *
     * @param width    The width of the bitmap
     * @param height   The height of the bitmap
     * @param config   The bitmap config to create.
     * @throws IllegalArgumentException if the width or height are <= 0
     */
    public static Bitmap createBitmap(int width, int height, Config config) {
        return createBitmap(width, height, config, true);
    }

    /**
     * Returns a mutable bitmap with the specified width and height.  Its
     * initial density is as per {@link #getDensity}.
     *
     * @param width    The width of the bitmap
     * @param height   The height of the bitmap
     * @param config   The bitmap config to create.
     * @param hasAlpha If the bitmap is ARGB_8888 this flag can be used to mark the
     *                 bitmap as opaque. Doing so will clear the bitmap in black
     *                 instead of transparent.  
     * 
     * @throws IllegalArgumentException if the width or height are <= 0
     */
    private static Bitmap createBitmap(int width, int height, Config config, boolean hasAlpha) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width and height must be > 0");
        }
        Bitmap bm = nativeCreate(null, 0, width, width, height, config.nativeInt, true);
        if (config == Config.ARGB_8888 && !hasAlpha) {
            bm.eraseColor(0xff000000);
            nativeSetHasAlpha(bm.mNativeBitmap, hasAlpha);
        } else {
            bm.eraseColor(0);
        }
        return bm;
    }

    /**
     * Returns a immutable bitmap with the specified width and height, with each
     * pixel value set to the corresponding value in the colors array.  Its
     * initial density is as per {@link #getDensity}.
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
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width and height must be > 0");
        }
        return nativeCreate(colors, offset, stride, width, height,
                            config.nativeInt, false);
    }

    /**
     * Returns a immutable bitmap with the specified width and height, with each
     * pixel value set to the corresponding value in the colors array.  Its
     * initial density is as per {@link #getDensity}.
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
        PNG     (1),
        WEBP    (2);

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
     * Convenience for calling {@link #getScaledWidth(int)} with the target
     * density of the given {@link Canvas}.
     */
    public int getScaledWidth(Canvas canvas) {
        return scaleFromDensity(getWidth(), mDensity, canvas.mDensity);
    }

    /**
     * Convenience for calling {@link #getScaledHeight(int)} with the target
     * density of the given {@link Canvas}.
     */
    public int getScaledHeight(Canvas canvas) {
        return scaleFromDensity(getHeight(), mDensity, canvas.mDensity);
    }

    /**
     * Convenience for calling {@link #getScaledWidth(int)} with the target
     * density of the given {@link DisplayMetrics}.
     */
    public int getScaledWidth(DisplayMetrics metrics) {
        return scaleFromDensity(getWidth(), mDensity, metrics.densityDpi);
    }

    /**
     * Convenience for calling {@link #getScaledHeight(int)} with the target
     * density of the given {@link DisplayMetrics}.
     */
    public int getScaledHeight(DisplayMetrics metrics) {
        return scaleFromDensity(getHeight(), mDensity, metrics.densityDpi);
    }

    /**
     * Convenience method that returns the width of this bitmap divided
     * by the density scale factor.
     *
     * @param targetDensity The density of the target canvas of the bitmap.
     * @return The scaled width of this bitmap, according to the density scale factor.
     */
    public int getScaledWidth(int targetDensity) {
        return scaleFromDensity(getWidth(), mDensity, targetDensity);
    }

    /**
     * Convenience method that returns the height of this bitmap divided
     * by the density scale factor.
     *
     * @param targetDensity The density of the target canvas of the bitmap.
     * @return The scaled height of this bitmap, according to the density scale factor.
     */
    public int getScaledHeight(int targetDensity) {
        return scaleFromDensity(getHeight(), mDensity, targetDensity);
    }
    
    /**
     * @hide
     */
    static public int scaleFromDensity(int size, int sdensity, int tdensity) {
        if (sdensity == DENSITY_NONE || sdensity == tdensity) {
            return size;
        }
        
        // Scale by tdensity / sdensity, rounding up.
        return ((size * tdensity) + (sdensity >> 1)) / sdensity;
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
     * Returns the number of bytes used to store this bitmap's pixels.
     */
    public final int getByteCount() {
        // int result permits bitmaps up to 46,340 x 46,340
        return getRowBytes() * getHeight();
    }

    /**
     * If the bitmap's internal config is in one of the public formats, return
     * that config, otherwise return null.
     */
    public final Config getConfig() {
        return Config.nativeToConfig(nativeConfig(mNativeBitmap));
    }

    /** Returns true if the bitmap's config supports per-pixel alpha, and
     * if the pixels may contain non-opaque alpha values. For some configs,
     * this is always false (e.g. RGB_565), since they do not support per-pixel
     * alpha. However, for configs that do, the bitmap may be flagged to be
     * known that all of its pixels are opaque. In this case hasAlpha() will
     * also return false. If a config such as ARGB_8888 is not so flagged,
     * it will return true by default.
     */
    public final boolean hasAlpha() {
        return nativeHasAlpha(mNativeBitmap);
    }

    /**
     * Tell the bitmap if all of the pixels are known to be opaque (false)
     * or if some of the pixels may contain non-opaque alpha values (true).
     * Note, for some configs (e.g. RGB_565) this call is ignored, since it
     * does not support per-pixel alpha values.
     *
     * This is meant as a drawing hint, as in some cases a bitmap that is known
     * to be opaque can take a faster drawing case than one that may have
     * non-opaque per-pixel alpha values.
     */
    public void setHasAlpha(boolean hasAlpha) {
        nativeSetHasAlpha(mNativeBitmap, hasAlpha);
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
        if (!nativeWriteToParcel(mNativeBitmap, mIsMutable, mDensity, p)) {
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
     * 
     * <p>The initial density of the returned bitmap is the same as the original's.
     * 
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
        bm.mDensity = mDensity;
        return bm;
    }

    /**
     *  Given another bitmap, return true if it has the same dimensions, config,
     *  and pixel data as this bitmap. If any of those differ, return false.
     *  If other is null, return false.
     */
    public boolean sameAs(Bitmap other) {
        return this == other || (other != null && nativeSameAs(mNativeBitmap, other.mNativeBitmap));
    }

    /**
     * Rebuilds any caches associated with the bitmap that are used for
     * drawing it. In the case of purgeable bitmaps, this call will attempt to
     * ensure that the pixels have been decoded.
     * If this is called on more than one bitmap in sequence, the priority is
     * given in LRU order (i.e. the last bitmap called will be given highest
     * priority).
     *
     * For bitmaps with no associated caches, this call is effectively a no-op,
     * and therefore is harmless.
     */
    public void prepareToDraw() {
        nativePrepareToDraw(mNativeBitmap);
    }

    private static class BitmapFinalizer {
        private final int mNativeBitmap;

        BitmapFinalizer(int nativeBitmap) {
            mNativeBitmap = nativeBitmap;
        }

        @Override
        public void finalize() {
            try {
                super.finalize();
            } catch (Throwable t) {
                // Ignore
            } finally {
                nativeDestructor(mNativeBitmap);
            }
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
    private static native int nativeGenerationId(int nativeBitmap);

    private static native Bitmap nativeCreateFromParcel(Parcel p);
    // returns true on success
    private static native boolean nativeWriteToParcel(int nativeBitmap,
                                                      boolean isMutable,
                                                      int density,
                                                      Parcel p);
    // returns a new bitmap built from the native bitmap's alpha, and the paint
    private static native Bitmap nativeExtractAlpha(int nativeBitmap,
                                                    int nativePaint,
                                                    int[] offsetXY);

    private static native void nativePrepareToDraw(int nativeBitmap);
    private static native void nativeSetHasAlpha(int nBitmap, boolean hasAlpha);
    private static native boolean nativeSameAs(int nb0, int nb1);
    
    /* package */ final int ni() {
        return mNativeBitmap;
    }
}

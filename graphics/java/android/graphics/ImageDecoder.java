/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static android.system.OsConstants.SEEK_SET;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RawRes;
import android.content.ContentResolver;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.NinePatchDrawable;
import android.net.Uri;
import android.system.ErrnoException;
import android.system.Os;

import libcore.io.IoUtils;
import dalvik.system.CloseGuard;

import java.nio.ByteBuffer;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ArrayIndexOutOfBoundsException;
import java.lang.AutoCloseable;
import java.lang.NullPointerException;
import java.lang.RuntimeException;
import java.lang.annotation.Retention;
import static java.lang.annotation.RetentionPolicy.SOURCE;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 *  Class for decoding images as {@link Bitmap}s or {@link Drawable}s.
 *  @hide
 */
public final class ImageDecoder implements AutoCloseable {
    /**
     *  Source of the encoded image data.
     */
    public static abstract class Source {
        /* @hide */
        Resources getResources() { return null; }

        /* @hide */
        abstract ImageDecoder createImageDecoder() throws IOException;
    };

    private static class ByteArraySource extends Source {
        ByteArraySource(byte[] data, int offset, int length) {
            mData = data;
            mOffset = offset;
            mLength = length;
        };
        private final byte[] mData;
        private final int    mOffset;
        private final int    mLength;

        @Override
        public ImageDecoder createImageDecoder() throws IOException {
            return nCreate(mData, mOffset, mLength);
        }
    }

    private static class ByteBufferSource extends Source {
        ByteBufferSource(ByteBuffer buffer) {
            mBuffer = buffer;
        }
        private final ByteBuffer mBuffer;

        @Override
        public ImageDecoder createImageDecoder() throws IOException {
            if (!mBuffer.isDirect() && mBuffer.hasArray()) {
                int offset = mBuffer.arrayOffset() + mBuffer.position();
                int length = mBuffer.limit() - mBuffer.position();
                return nCreate(mBuffer.array(), offset, length);
            }
            return nCreate(mBuffer, mBuffer.position(), mBuffer.limit());
        }
    }

    private static class ContentResolverSource extends Source {
        ContentResolverSource(ContentResolver resolver, Uri uri) {
            mResolver = resolver;
            mUri = uri;
        }

        private final ContentResolver mResolver;
        private final Uri mUri;

        @Override
        public ImageDecoder createImageDecoder() throws IOException {
            AssetFileDescriptor assetFd = null;
            try {
                if (mUri.getScheme() == ContentResolver.SCHEME_CONTENT) {
                    assetFd = mResolver.openTypedAssetFileDescriptor(mUri,
                            "image/*", null);
                } else {
                    assetFd = mResolver.openAssetFileDescriptor(mUri, "r");
                }
            } catch (FileNotFoundException e) {
                // Some images cannot be opened as AssetFileDescriptors (e.g.
                // bmp, ico). Open them as InputStreams.
                InputStream is = mResolver.openInputStream(mUri);
                if (is == null) {
                    throw new FileNotFoundException(mUri.toString());
                }

                return createFromStream(is);
            }

            final FileDescriptor fd = assetFd.getFileDescriptor();
            final long offset = assetFd.getStartOffset();

            ImageDecoder decoder = null;
            try {
                try {
                    Os.lseek(fd, offset, SEEK_SET);
                    decoder = nCreate(fd);
                } catch (ErrnoException e) {
                    decoder = createFromStream(new FileInputStream(fd));
                }
            } finally {
                if (decoder == null) {
                    IoUtils.closeQuietly(assetFd);
                } else {
                    decoder.mAssetFd = assetFd;
                }
            }
            return decoder;
        }
    }

    private static ImageDecoder createFromStream(InputStream is) throws IOException {
        // Arbitrary size matches BitmapFactory.
        byte[] storage = new byte[16 * 1024];
        ImageDecoder decoder = null;
        try {
            decoder = nCreate(is, storage);
        } finally {
            if (decoder == null) {
                IoUtils.closeQuietly(is);
            } else {
                decoder.mInputStream = is;
                decoder.mTempStorage = storage;
            }
        }

        return decoder;
    }

    private static class ResourceSource extends Source {
        ResourceSource(Resources res, int resId) {
            mResources = res;
            mResId = resId;
        }

        final Resources mResources;
        final int       mResId;

        @Override
        public Resources getResources() { return mResources; }

        @Override
        public ImageDecoder createImageDecoder() throws IOException {
            // This is just used in order to access the underlying Asset and
            // keep it alive. FIXME: Can we skip creating this object?
            InputStream is = null;
            ImageDecoder decoder = null;
            try {
                is = mResources.openRawResource(mResId);
                if (!(is instanceof AssetManager.AssetInputStream)) {
                    // This should never happen.
                    throw new RuntimeException("Resource is not an asset?");
                }
                long asset = ((AssetManager.AssetInputStream) is).getNativeAsset();
                decoder = nCreate(asset);
            } finally {
                if (decoder == null) {
                    IoUtils.closeQuietly(is);
                } else {
                    decoder.mInputStream = is;
                }
            }
            return decoder;
        }
    }

    /**
     *  Contains information about the encoded image.
     */
    public static class ImageInfo {
        public final int width;
        public final int height;
        // TODO?: Add more info? mimetype, ninepatch etc?

        ImageInfo(int width, int height) {
            this.width = width;
            this.height = height;
        }
    };

    /**
     *  Supplied to onException if the provided data is incomplete.
     *
     *  Will never be thrown by ImageDecoder.
     *
     *  There may be a partial image to display.
     */
    public static class IncompleteException extends IOException {};

    /**
     *  Used if the provided data is corrupt.
     *
     *  May be thrown if there is nothing to display.
     *
     *  If supplied to onException, there may be a correct partial image to
     *  display.
     */
    public static class CorruptException extends IOException {};

    /**
     *  Optional listener supplied to {@link #decodeDrawable} or
     *  {@link #decodeBitmap}.
     */
    public static interface OnHeaderDecodedListener {
        /**
         *  Called when the header is decoded and the size is known.
         *
         *  @param info Information about the encoded image.
         *  @param decoder allows changing the default settings of the decode.
         */
        public void onHeaderDecoded(ImageInfo info, ImageDecoder decoder);

    };

    /**
     *  Optional listener supplied to the ImageDecoder.
     */
    public static interface OnExceptionListener {
        /**
         *  Called when there is a problem in the stream or in the data.
         *  FIXME: Report how much of the image has been decoded?
         *
         *  @param e IOException containing information about the error.
         *  @return True to create and return a {@link Drawable}/
         *      {@link Bitmap} with partial data. False to return
         *      {@code null}. True is the default.
         */
        public boolean onException(IOException e);
    };

    // Fields
    private long      mNativePtr;
    private final int mWidth;
    private final int mHeight;

    private int     mDesiredWidth;
    private int     mDesiredHeight;
    private int     mAllocator = DEFAULT_ALLOCATOR;
    private boolean mRequireUnpremultiplied = false;
    private boolean mMutable = false;
    private boolean mPreferRamOverQuality = false;
    private boolean mAsAlphaMask = false;
    private Rect    mCropRect;

    private PostProcess         mPostProcess;
    private OnExceptionListener mOnExceptionListener;

    // Objects for interacting with the input.
    private InputStream         mInputStream;
    private byte[]              mTempStorage;
    private AssetFileDescriptor mAssetFd;
    private final AtomicBoolean mClosed = new AtomicBoolean();
    private final CloseGuard    mCloseGuard = CloseGuard.get();

    /**
     * Private constructor called by JNI. {@link #close} must be
     * called after decoding to delete native resources.
     */
    @SuppressWarnings("unused")
    private ImageDecoder(long nativePtr, int width, int height) {
        mNativePtr = nativePtr;
        mWidth = width;
        mHeight = height;
        mDesiredWidth = width;
        mDesiredHeight = height;
        mCloseGuard.open("close");
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mCloseGuard != null) {
                mCloseGuard.warnIfOpen();
            }

            close();
        } finally {
            super.finalize();
        }
    }

    /**
     * Create a new {@link Source} from an asset.
     *
     * @param res the {@link Resources} object containing the image data.
     * @param resId resource ID of the image data.
     *      // FIXME: Can be an @DrawableRes?
     * @return a new Source object, which can be passed to
     *      {@link #decodeDrawable} or {@link #decodeBitmap}.
     */
    @NonNull
    public static Source createSource(@NonNull Resources res, @RawRes int resId)
    {
        return new ResourceSource(res, resId);
    }

    /**
     * Create a new {@link Source} from a {@link android.net.Uri}.
     *
     * @param cr to retrieve from.
     * @param uri of the image file.
     * @return a new Source object, which can be passed to
     *      {@link #decodeDrawable} or {@link #decodeBitmap}.
     */
    @NonNull
    public static Source createSource(@NonNull ContentResolver cr,
            @NonNull Uri uri) {
        return new ContentResolverSource(cr, uri);
    }

    /**
     * Create a new {@link Source} from a byte array.
     * @param data byte array of compressed image data.
     * @param offset offset into data for where the decoder should begin
     *      parsing.
     * @param length number of bytes, beginning at offset, to parse.
     * @throws NullPointerException if data is null.
     * @throws ArrayIndexOutOfBoundsException if offset and length are
     *      not within data.
     */
    public static Source createSource(@NonNull byte[] data, int offset,
            int length) throws ArrayIndexOutOfBoundsException {
        if (data == null) {
            throw new NullPointerException("null byte[] in createSource!");
        }
        if (offset < 0 || length < 0 || offset >= data.length ||
                offset + length > data.length) {
            throw new ArrayIndexOutOfBoundsException(
                    "invalid offset/length!");
        }
        return new ByteArraySource(data, offset, length);
    }

    /**
     * See {@link #createSource(byte[], int, int).
     */
    public static Source createSource(@NonNull byte[] data) {
        return createSource(data, 0, data.length);
    }

    /**
     * Create a new {@link Source} from a {@link java.nio.ByteBuffer}.
     *
     * The returned {@link Source} effectively takes ownership of the
     * {@link java.nio.ByteBuffer}; i.e. no other code should modify it after
     * this call.
     *
     * Decoding will start from {@link java.nio.ByteBuffer#position()}.
     */
    public static Source createSource(ByteBuffer buffer) {
        return new ByteBufferSource(buffer);
    }

    /**
     *  Return the width and height of a given sample size.
     *
     *  This takes an input that functions like
     *  {@link BitmapFactory.Options#inSampleSize}. It returns a width and
     *  height that can be acheived by sampling the encoded image. Other widths
     *  and heights may be supported, but will require an additional (internal)
     *  scaling step. Such internal scaling is *not* supported with
     *  {@link #requireUnpremultiplied}.
     *
     *  @param sampleSize Sampling rate of the encoded image.
     *  @return Point {@link Point#x} and {@link Point#y} correspond to the
     *      width and height after sampling.
     */
    public Point getSampledSize(int sampleSize) {
        if (sampleSize <= 0) {
            throw new IllegalArgumentException("sampleSize must be positive! "
                    + "provided " + sampleSize);
        }
        if (mNativePtr == 0) {
            throw new IllegalStateException("ImageDecoder is closed!");
        }

        return nGetSampledSize(mNativePtr, sampleSize);
    }

    // Modifiers
    /**
     *  Resize the output to have the following size.
     *
     *  @param width must be greater than 0.
     *  @param height must be greater than 0.
     */
    public void resize(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Dimensions must be positive! "
                    + "provided (" + width + ", " + height + ")");
        }

        mDesiredWidth = width;
        mDesiredHeight = height;
    }

    /**
     *  Resize based on a sample size.
     *
     *  This has the same effect as passing the result of
     *  {@link #getSampledSize} to {@link #resize(int, int)}.
     *
     *  @param sampleSize Sampling rate of the encoded image.
     */
    public void resize(int sampleSize) {
        Point dimensions = this.getSampledSize(sampleSize);
        this.resize(dimensions.x, dimensions.y);
    }

    // These need to stay in sync with ImageDecoder.cpp's Allocator enum.
    /**
     *  Use the default allocation for the pixel memory.
     *
     *  Will typically result in a {@link Bitmap.Config#HARDWARE}
     *  allocation, but may be software for small images. In addition, this will
     *  switch to software when HARDWARE is incompatible, e.g.
     *  {@link #setMutable}, {@link #setAsAlphaMask}.
     */
    public static final int DEFAULT_ALLOCATOR = 0;

    /**
     *  Use a software allocation for the pixel memory.
     *
     *  Useful for drawing to a software {@link Canvas} or for
     *  accessing the pixels on the final output.
     */
    public static final int SOFTWARE_ALLOCATOR = 1;

    /**
     *  Use shared memory for the pixel memory.
     *
     *  Useful for sharing across processes.
     */
    public static final int SHARED_MEMORY_ALLOCATOR = 2;

    /**
     *  Require a {@link Bitmap.Config#HARDWARE} {@link Bitmap}.
     *
     *  This will throw an {@link java.lang.IllegalStateException} when combined
     *  with incompatible options, like {@link #setMutable} or
     *  {@link #setAsAlphaMask}.
     */
    public static final int HARDWARE_ALLOCATOR = 3;

    /** @hide **/
    @Retention(SOURCE)
    @IntDef({ DEFAULT_ALLOCATOR, SOFTWARE_ALLOCATOR, SHARED_MEMORY_ALLOCATOR,
              HARDWARE_ALLOCATOR })
    public @interface Allocator {};

    /**
     *  Choose the backing for the pixel memory.
     *
     *  This is ignored for animated drawables.
     *
     *  TODO: Allow accessing the backing from the Bitmap.
     *
     *  @param allocator Type of allocator to use.
     */
    public void setAllocator(@Allocator int allocator) {
        if (allocator < DEFAULT_ALLOCATOR || allocator > HARDWARE_ALLOCATOR) {
            throw new IllegalArgumentException("invalid allocator " + allocator);
        }
        mAllocator = allocator;
    }

    /**
     *  Create a {@link Bitmap} with unpremultiplied pixels.
     *
     *  By default, ImageDecoder will create a {@link Bitmap} with
     *  premultiplied pixels, which is required for drawing with the
     *  {@link android.view.View} system (i.e. to a {@link Canvas}). Calling
     *  this method will result in {@link #decodeBitmap} returning a
     *  {@link Bitmap} with unpremultiplied pixels. See
     *  {@link Bitmap#isPremultiplied}. Incompatible with
     *  {@link #decodeDrawable}; attempting to decode an unpremultiplied
     *  {@link Drawable} will throw an {@link java.lang.IllegalStateException}.
     */
    public void requireUnpremultiplied() {
        mRequireUnpremultiplied = true;
    }

    /**
     *  Modify the image after decoding and scaling.
     *
     *  This allows adding effects prior to returning a {@link Drawable} or
     *  {@link Bitmap}. For a {@code Drawable} or an immutable {@code Bitmap},
     *  this is the only way to process the image after decoding.
     *
     *  If set on a nine-patch image, the nine-patch data is ignored.
     *
     *  For an animated image, the drawing commands drawn on the {@link Canvas}
     *  will be recorded immediately and then applied to each frame.
     */
    public void setPostProcess(PostProcess p) {
        mPostProcess = p;
    }

    /**
     *  Set (replace) the {@link OnExceptionListener} on this object.
     *
     *  Will be called if there is an error in the input. Without one, a
     *  partial {@link Bitmap} will be created.
     */
    public void setOnExceptionListener(OnExceptionListener l) {
        mOnExceptionListener = l;
    }

    /**
     *  Crop the output to {@code subset} of the (possibly) scaled image.
     *
     *  {@code subset} must be contained within the size set by {@link #resize}
     *  or the bounds of the image if resize was not called. Otherwise an
     *  {@link IllegalStateException} will be thrown.
     *
     *  NOT intended as a replacement for
     *  {@link BitmapRegionDecoder#decodeRegion}. This supports all formats,
     *  but merely crops the output.
     */
    public void crop(Rect subset) {
        mCropRect = subset;
    }

    /**
     *  Create a mutable {@link Bitmap}.
     *
     *  By default, a {@link Bitmap} created will be immutable, but that can be
     *  changed with this call.
     *
     *  Incompatible with {@link #HARDWARE_ALLOCATOR}, because
     *  {@link Bitmap.Config#HARDWARE} Bitmaps cannot be mutable. Attempting to
     *  combine them will throw an {@link java.lang.IllegalStateException}.
     *
     *  Incompatible with {@link #decodeDrawable}, which would require
     *  retrieving the Bitmap from the returned Drawable in order to modify.
     *  Attempting to decode a mutable {@link Drawable} will throw an
     *  {@link java.lang.IllegalStateException}
     */
    public void setMutable() {
        mMutable = true;
    }

    /**
     *  Potentially save RAM at the expense of quality.
     *
     *  This may result in a {@link Bitmap} with a denser {@link Bitmap.Config},
     *  depending on the image. For example, for an opaque {@link Bitmap}, this
     *  may result in a {@link Bitmap.Config} with no alpha information.
     */
    public void setPreferRamOverQuality() {
        mPreferRamOverQuality = true;
    }

    /**
     *  Potentially treat the output as an alpha mask.
     *
     *  If the image is encoded in a format with only one channel, treat that
     *  channel as alpha. Otherwise this call has no effect.
     *
     *  Incompatible with {@link #HARDWARE_ALLOCATOR}. Trying to combine them
     *  will throw an {@link java.lang.IllegalStateException}.
     */
    public void setAsAlphaMask() {
        mAsAlphaMask = true;
    }

    @Override
    public void close() {
        mCloseGuard.close();
        if (!mClosed.compareAndSet(false, true)) {
            return;
        }
        nClose(mNativePtr);
        mNativePtr = 0;

        IoUtils.closeQuietly(mInputStream);
        IoUtils.closeQuietly(mAssetFd);

        mInputStream = null;
        mAssetFd = null;
        mTempStorage = null;
    }

    private void checkState() {
        if (mNativePtr == 0) {
            throw new IllegalStateException("Cannot use closed ImageDecoder!");
        }

        checkSubset(mDesiredWidth, mDesiredHeight, mCropRect);

        if (mAllocator == HARDWARE_ALLOCATOR) {
            if (mMutable) {
                throw new IllegalStateException("Cannot make mutable HARDWARE Bitmap!");
            }
            if (mAsAlphaMask) {
                throw new IllegalStateException("Cannot make HARDWARE Alpha mask Bitmap!");
            }
        }

        if (mPostProcess != null && mRequireUnpremultiplied) {
            throw new IllegalStateException("Cannot draw to unpremultiplied pixels!");
        }
    }

    private static void checkSubset(int width, int height, Rect r) {
        if (r == null) {
            return;
        }
        if (r.left < 0 || r.top < 0 || r.right > width || r.bottom > height) {
            throw new IllegalStateException("Subset " + r + " not contained by "
                    + "scaled image bounds: (" + width + " x " + height + ")");
        }
    }

    /**
     *  Create a {@link Drawable} from a {@code Source}.
     *
     *  @param src representing the encoded image.
     *  @param listener for learning the {@link ImageInfo} and changing any
     *      default settings on the {@code ImageDecoder}. If not {@code null},
     *      this will be called on the same thread as {@code decodeDrawable}
     *      before that method returns.
     *  @return Drawable for displaying the image.
     *  @throws IOException if {@code src} is not found, is an unsupported
     *      format, or cannot be decoded for any reason.
     */
    @NonNull
    public static Drawable decodeDrawable(@NonNull Source src,
            @Nullable OnHeaderDecodedListener listener) throws IOException {
        try (ImageDecoder decoder = src.createImageDecoder()) {
            if (listener != null) {
                ImageInfo info = new ImageInfo(decoder.mWidth, decoder.mHeight);
                listener.onHeaderDecoded(info, decoder);
            }

            decoder.checkState();

            if (decoder.mRequireUnpremultiplied) {
                // Though this could be supported (ignored) for opaque images,
                // it seems better to always report this error.
                throw new IllegalStateException("Cannot decode a Drawable " +
                                                "with unpremultiplied pixels!");
            }

            if (decoder.mMutable) {
                throw new IllegalStateException("Cannot decode a mutable " +
                                                "Drawable!");
            }

            Bitmap bm = nDecodeBitmap(decoder.mNativePtr,
                                      decoder.mOnExceptionListener,
                                      decoder.mPostProcess,
                                      decoder.mDesiredWidth,
                                      decoder.mDesiredHeight,
                                      decoder.mCropRect,
                                      false,    // mMutable
                                      decoder.mAllocator,
                                      false,    // mRequireUnpremultiplied
                                      decoder.mPreferRamOverQuality,
                                      decoder.mAsAlphaMask);
            if (bm == null) {
                // FIXME: bm should never be null. Currently a return value
                // of false from onException will result in bm being null. What
                // is the right API to choose to discard partial Bitmaps?
                return null;
            }

            Resources res = src.getResources();
            if (res == null) {
                bm.setDensity(Bitmap.DENSITY_NONE);
            }

            byte[] np = bm.getNinePatchChunk();
            if (np != null && NinePatch.isNinePatchChunk(np)) {
                Rect opticalInsets = new Rect();
                bm.getOpticalInsets(opticalInsets);
                Rect padding = new Rect();
                nGetPadding(decoder.mNativePtr, padding);
                return new NinePatchDrawable(res, bm, np, padding,
                        opticalInsets, null);
            }

            // TODO: Handle animation.
            return new BitmapDrawable(res, bm);
        }
    }

    /**
     * See {@link #decodeDrawable(Source, OnHeaderDecodedListener)}.
     */
    @NonNull
    public static Drawable decodeDrawable(@NonNull Source src)
            throws IOException {
        return decodeDrawable(src, null);
    }

    /**
     *  Create a {@link Bitmap} from a {@code Source}.
     *
     *  @param src representing the encoded image.
     *  @param listener for learning the {@link ImageInfo} and changing any
     *      default settings on the {@code ImageDecoder}. If not {@code null},
     *      this will be called on the same thread as {@code decodeBitmap}
     *      before that method returns.
     *  @return Bitmap containing the image.
     *  @throws IOException if {@code src} is not found, is an unsupported
     *      format, or cannot be decoded for any reason.
     */
    @NonNull
    public static Bitmap decodeBitmap(@NonNull Source src,
            @Nullable OnHeaderDecodedListener listener) throws IOException {
        try (ImageDecoder decoder = src.createImageDecoder()) {
            if (listener != null) {
                ImageInfo info = new ImageInfo(decoder.mWidth, decoder.mHeight);
                listener.onHeaderDecoded(info, decoder);
            }

            decoder.checkState();

            return nDecodeBitmap(decoder.mNativePtr,
                                 decoder.mOnExceptionListener,
                                 decoder.mPostProcess,
                                 decoder.mDesiredWidth,
                                 decoder.mDesiredHeight,
                                 decoder.mCropRect,
                                 decoder.mMutable,
                                 decoder.mAllocator,
                                 decoder.mRequireUnpremultiplied,
                                 decoder.mPreferRamOverQuality,
                                 decoder.mAsAlphaMask);
        }
    }

    /**
     *  See {@link #decodeBitmap(Source, OnHeaderDecodedListener)}.
     */
    @NonNull
    public static Bitmap decodeBitmap(@NonNull Source src) throws IOException {
        return decodeBitmap(src, null);
    }

    private static native ImageDecoder nCreate(long asset) throws IOException;
    private static native ImageDecoder nCreate(ByteBuffer buffer,
                                               int position,
                                               int limit) throws IOException;
    private static native ImageDecoder nCreate(byte[] data, int offset,
                                               int length) throws IOException;
    private static native ImageDecoder nCreate(InputStream is, byte[] storage);
    private static native ImageDecoder nCreate(FileDescriptor fd) throws IOException;
    @NonNull
    private static native Bitmap nDecodeBitmap(long nativePtr,
            OnExceptionListener listener,
            PostProcess postProcess,
            int width, int height,
            Rect cropRect, boolean mutable,
            int allocator, boolean requireUnpremul,
            boolean preferRamOverQuality, boolean asAlphaMask)
        throws IOException;
    private static native Point nGetSampledSize(long nativePtr,
                                                int sampleSize);
    private static native void nGetPadding(long nativePtr, Rect outRect);
    private static native void nClose(long nativePtr);
}

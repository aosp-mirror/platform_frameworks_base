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

import static android.system.OsConstants.SEEK_CUR;
import static android.system.OsConstants.SEEK_SET;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RawRes;
import android.content.ContentResolver;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.NinePatchDrawable;
import android.net.Uri;
import android.util.Size;
import android.system.ErrnoException;
import android.system.Os;
import android.util.DisplayMetrics;
import android.util.TypedValue;

import libcore.io.IoUtils;
import dalvik.system.CloseGuard;

import java.nio.ByteBuffer;
import java.io.File;
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
 */
public final class ImageDecoder implements AutoCloseable {
    /**
     *  Source of the encoded image data.
     */
    public static abstract class Source {
        private Source() {}

        /* @hide */
        @Nullable
        Resources getResources() { return null; }

        /* @hide */
        int getDensity() { return Bitmap.DENSITY_NONE; }

        /* @hide */
        int computeDstDensity() {
            Resources res = getResources();
            if (res == null) {
                return Bitmap.getDefaultDensity();
            }

            return res.getDisplayMetrics().densityDpi;
        }

        /* @hide */
        @NonNull
        abstract ImageDecoder createImageDecoder() throws IOException;
    };

    private static class ByteArraySource extends Source {
        ByteArraySource(@NonNull byte[] data, int offset, int length) {
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
        ByteBufferSource(@NonNull ByteBuffer buffer) {
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
        ContentResolverSource(@NonNull ContentResolver resolver, @NonNull Uri uri) {
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

                return createFromStream(is, true);
            }

            final FileDescriptor fd = assetFd.getFileDescriptor();
            final long offset = assetFd.getStartOffset();

            ImageDecoder decoder = null;
            try {
                try {
                    Os.lseek(fd, offset, SEEK_SET);
                    decoder = nCreate(fd);
                } catch (ErrnoException e) {
                    decoder = createFromStream(new FileInputStream(fd), true);
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

    @NonNull
    private static ImageDecoder createFromFile(@NonNull File file) throws IOException {
        FileInputStream stream = new FileInputStream(file);
        FileDescriptor fd = stream.getFD();
        try {
            Os.lseek(fd, 0, SEEK_CUR);
        } catch (ErrnoException e) {
            return createFromStream(stream, true);
        }

        ImageDecoder decoder = null;
        try {
            decoder = nCreate(fd);
        } finally {
            if (decoder == null) {
                IoUtils.closeQuietly(stream);
            } else {
                decoder.mInputStream = stream;
                decoder.mOwnsInputStream = true;
            }
        }
        return decoder;
    }

    @NonNull
    private static ImageDecoder createFromStream(@NonNull InputStream is,
            boolean closeInputStream) throws IOException {
        // Arbitrary size matches BitmapFactory.
        byte[] storage = new byte[16 * 1024];
        ImageDecoder decoder = null;
        try {
            decoder = nCreate(is, storage);
        } finally {
            if (decoder == null) {
                if (closeInputStream) {
                    IoUtils.closeQuietly(is);
                }
            } else {
                decoder.mInputStream = is;
                decoder.mOwnsInputStream = closeInputStream;
                decoder.mTempStorage = storage;
            }
        }

        return decoder;
    }

    /**
     * For backwards compatibility, this does *not* close the InputStream.
     */
    private static class InputStreamSource extends Source {
        InputStreamSource(Resources res, InputStream is, int inputDensity) {
            if (is == null) {
                throw new IllegalArgumentException("The InputStream cannot be null");
            }
            mResources = res;
            mInputStream = is;
            mInputDensity = res != null ? inputDensity : Bitmap.DENSITY_NONE;
        }

        final Resources mResources;
        InputStream mInputStream;
        final int mInputDensity;

        @Override
        public Resources getResources() { return mResources; }

        @Override
        public int getDensity() { return mInputDensity; }

        @Override
        public ImageDecoder createImageDecoder() throws IOException {

            synchronized (this) {
                if (mInputStream == null) {
                    throw new IOException("Cannot reuse InputStreamSource");
                }
                InputStream is = mInputStream;
                mInputStream = null;
                return createFromStream(is, false);
            }
        }
    }

    private static class ResourceSource extends Source {
        ResourceSource(@NonNull Resources res, int resId) {
            mResources = res;
            mResId = resId;
            mResDensity = Bitmap.DENSITY_NONE;
        }

        final Resources mResources;
        final int       mResId;
        int             mResDensity;

        @Override
        public Resources getResources() { return mResources; }

        @Override
        public int getDensity() { return mResDensity; }

        @Override
        public ImageDecoder createImageDecoder() throws IOException {
            // This is just used in order to access the underlying Asset and
            // keep it alive. FIXME: Can we skip creating this object?
            InputStream is = null;
            ImageDecoder decoder = null;
            TypedValue value = new TypedValue();
            try {
                is = mResources.openRawResource(mResId, value);

                if (value.density == TypedValue.DENSITY_DEFAULT) {
                    mResDensity = DisplayMetrics.DENSITY_DEFAULT;
                } else if (value.density != TypedValue.DENSITY_NONE) {
                    mResDensity = value.density;
                }

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
                    decoder.mOwnsInputStream = true;
                }
            }
            return decoder;
        }
    }

    private static class FileSource extends Source {
        FileSource(@NonNull File file) {
            mFile = file;
        }

        private final File mFile;

        @Override
        public ImageDecoder createImageDecoder() throws IOException {
            return createFromFile(mFile);
        }
    }

    /**
     *  Contains information about the encoded image.
     */
    public static class ImageInfo {
        private final Size mSize;
        private ImageDecoder mDecoder;

        private ImageInfo(@NonNull ImageDecoder decoder) {
            mSize = new Size(decoder.mWidth, decoder.mHeight);
            mDecoder = decoder;
        }

        /**
         * Size of the image, without scaling or cropping.
         */
        @NonNull
        public Size getSize() {
            return mSize;
        }

        /**
         * The mimeType of the image.
         */
        @NonNull
        public String getMimeType() {
            return mDecoder.getMimeType();
        }

        /**
         * Whether the image is animated.
         *
         * <p>Calling {@link #decodeDrawable} will return an
         * {@link AnimatedImageDrawable}.</p>
         */
        public boolean isAnimated() {
            return mDecoder.mAnimated;
        }
    };

    /**
     *  Thrown if the provided data is incomplete.
     */
    public static class IncompleteException extends IOException {};

    /**
     *  Optional listener supplied to {@link #decodeDrawable} or
     *  {@link #decodeBitmap}.
     */
    public static interface OnHeaderDecodedListener {
        /**
         *  Called when the header is decoded and the size is known.
         *
         *  @param decoder allows changing the default settings of the decode.
         *  @param info Information about the encoded image.
         *  @param source that created the decoder.
         */
        public void onHeaderDecoded(@NonNull ImageDecoder decoder,
                @NonNull ImageInfo info, @NonNull Source source);

    };

    /**
     *  An Exception was thrown reading the {@link Source}.
     */
    public static final int ERROR_SOURCE_EXCEPTION  = 1;

    /**
     *  The encoded data was incomplete.
     */
    public static final int ERROR_SOURCE_INCOMPLETE = 2;

    /**
     *  The encoded data contained an error.
     */
    public static final int ERROR_SOURCE_ERROR      = 3;

    @Retention(SOURCE)
    @IntDef({ ERROR_SOURCE_EXCEPTION, ERROR_SOURCE_INCOMPLETE, ERROR_SOURCE_ERROR })
    public @interface Error {};

    /**
     *  Optional listener supplied to the ImageDecoder.
     *
     *  Without this listener, errors will throw {@link java.io.IOException}.
     */
    public static interface OnPartialImageListener {
        /**
         *  Called when there is only a partial image to display.
         *
         *  If decoding is interrupted after having decoded a partial image,
         *  this listener lets the client know that and allows them to
         *  optionally finish the rest of the decode/creation process to create
         *  a partial {@link Drawable}/{@link Bitmap}.
         *
         *  @param error indicating what interrupted the decode.
         *  @param source that had the error.
         *  @return True to create and return a {@link Drawable}/{@link Bitmap}
         *      with partial data. False (which is the default) to abort the
         *      decode and throw {@link java.io.IOException}.
         */
        public boolean onPartialImage(@Error int error, @NonNull Source source);
    };

    // Fields
    private long          mNativePtr;
    private final int     mWidth;
    private final int     mHeight;
    private final boolean mAnimated;

    private int     mDesiredWidth;
    private int     mDesiredHeight;
    private int     mAllocator = ALLOCATOR_DEFAULT;
    private boolean mRequireUnpremultiplied = false;
    private boolean mMutable = false;
    private boolean mPreferRamOverQuality = false;
    private boolean mAsAlphaMask = false;
    private Rect    mCropRect;
    private Rect    mOutPaddingRect;
    private Source  mSource;

    private PostProcessor          mPostProcessor;
    private OnPartialImageListener mOnPartialImageListener;

    // Objects for interacting with the input.
    private InputStream         mInputStream;
    private boolean             mOwnsInputStream;
    private byte[]              mTempStorage;
    private AssetFileDescriptor mAssetFd;
    private final AtomicBoolean mClosed = new AtomicBoolean();
    private final CloseGuard    mCloseGuard = CloseGuard.get();

    /**
     * Private constructor called by JNI. {@link #close} must be
     * called after decoding to delete native resources.
     */
    @SuppressWarnings("unused")
    private ImageDecoder(long nativePtr, int width, int height,
            boolean animated) {
        mNativePtr = nativePtr;
        mWidth = width;
        mHeight = height;
        mDesiredWidth = width;
        mDesiredHeight = height;
        mAnimated = animated;
        mCloseGuard.open("close");
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mCloseGuard != null) {
                mCloseGuard.warnIfOpen();
            }

            // Avoid closing these in finalizer.
            mInputStream = null;
            mAssetFd = null;

            close();
        } finally {
            super.finalize();
        }
    }

    /**
     * Create a new {@link Source} from an asset.
     * @hide
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
     *
     * @param data byte array of compressed image data.
     * @param offset offset into data for where the decoder should begin
     *      parsing.
     * @param length number of bytes, beginning at offset, to parse.
     * @throws NullPointerException if data is null.
     * @throws ArrayIndexOutOfBoundsException if offset and length are
     *      not within data.
     * @hide
     */
    @NonNull
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
     * @hide
     */
    @NonNull
    public static Source createSource(@NonNull byte[] data) {
        return createSource(data, 0, data.length);
    }

    /**
     * Create a new {@link Source} from a {@link java.nio.ByteBuffer}.
     *
     * <p>The returned {@link Source} effectively takes ownership of the
     * {@link java.nio.ByteBuffer}; i.e. no other code should modify it after
     * this call.</p>
     *
     * Decoding will start from {@link java.nio.ByteBuffer#position()}. The
     * position after decoding is undefined.
     */
    @NonNull
    public static Source createSource(@NonNull ByteBuffer buffer) {
        return new ByteBufferSource(buffer);
    }

    /**
     * Internal API used to generate bitmaps for use by Drawables (i.e. BitmapDrawable)
     * @hide
     */
    public static Source createSource(Resources res, InputStream is) {
        return new InputStreamSource(res, is, Bitmap.getDefaultDensity());
    }

    /**
     * Internal API used to generate bitmaps for use by Drawables (i.e. BitmapDrawable)
     * @hide
     */
    public static Source createSource(Resources res, InputStream is, int density) {
        return new InputStreamSource(res, is, density);
    }

    /**
     * Create a new {@link Source} from a {@link java.io.File}.
     */
    @NonNull
    public static Source createSource(@NonNull File file) {
        return new FileSource(file);
    }

    /**
     *  Return the width and height of a given sample size.
     *
     *  <p>This takes an input that functions like
     *  {@link BitmapFactory.Options#inSampleSize}. It returns a width and
     *  height that can be acheived by sampling the encoded image. Other widths
     *  and heights may be supported, but will require an additional (internal)
     *  scaling step. Such internal scaling is *not* supported with
     *  {@link #setRequireUnpremultiplied} set to {@code true}.</p>
     *
     *  @param sampleSize Sampling rate of the encoded image.
     *  @return {@link android.util.Size} of the width and height after
     *      sampling.
     */
    @NonNull
    public Size getSampledSize(int sampleSize) {
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
    public void setResize(int width, int height) {
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
     *  <p>This has the same effect as passing the result of
     *  {@link #getSampledSize} to {@link #setResize(int, int)}.</p>
     *
     *  @param sampleSize Sampling rate of the encoded image.
     */
    public void setResize(int sampleSize) {
        Size size = this.getSampledSize(sampleSize);
        this.setResize(size.getWidth(), size.getHeight());
    }

    private boolean requestedResize() {
        return mWidth != mDesiredWidth || mHeight != mDesiredHeight;
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
    public static final int ALLOCATOR_DEFAULT = 0;

    /**
     *  Use a software allocation for the pixel memory.
     *
     *  Useful for drawing to a software {@link Canvas} or for
     *  accessing the pixels on the final output.
     */
    public static final int ALLOCATOR_SOFTWARE = 1;

    /**
     *  Use shared memory for the pixel memory.
     *
     *  Useful for sharing across processes.
     */
    public static final int ALLOCATOR_SHARED_MEMORY = 2;

    /**
     *  Require a {@link Bitmap.Config#HARDWARE} {@link Bitmap}.
     *
     *  When this is combined with incompatible options, like
     *  {@link #setMutable} or {@link #setAsAlphaMask}, {@link #decodeDrawable}
     *  / {@link #decodeBitmap} will throw an
     *  {@link java.lang.IllegalStateException}.
     */
    public static final int ALLOCATOR_HARDWARE = 3;

    /** @hide **/
    @Retention(SOURCE)
    @IntDef({ ALLOCATOR_DEFAULT, ALLOCATOR_SOFTWARE, ALLOCATOR_SHARED_MEMORY,
              ALLOCATOR_HARDWARE })
    public @interface Allocator {};

    /**
     *  Choose the backing for the pixel memory.
     *
     *  This is ignored for animated drawables.
     *
     *  @param allocator Type of allocator to use.
     */
    public void setAllocator(@Allocator int allocator) {
        if (allocator < ALLOCATOR_DEFAULT || allocator > ALLOCATOR_HARDWARE) {
            throw new IllegalArgumentException("invalid allocator " + allocator);
        }
        mAllocator = allocator;
    }

    /**
     *  Specify whether the {@link Bitmap} should have unpremultiplied pixels.
     *
     *  By default, ImageDecoder will create a {@link Bitmap} with
     *  premultiplied pixels, which is required for drawing with the
     *  {@link android.view.View} system (i.e. to a {@link Canvas}). Calling
     *  this method with a value of {@code true} will result in
     *  {@link #decodeBitmap} returning a {@link Bitmap} with unpremultiplied
     *  pixels. See {@link Bitmap#isPremultiplied}. This is incompatible with
     *  {@link #decodeDrawable}; attempting to decode an unpremultiplied
     *  {@link Drawable} will throw an {@link java.lang.IllegalStateException}.
     */
    public void setRequireUnpremultiplied(boolean requireUnpremultiplied) {
        mRequireUnpremultiplied = requireUnpremultiplied;
    }

    /**
     *  Modify the image after decoding and scaling.
     *
     *  <p>This allows adding effects prior to returning a {@link Drawable} or
     *  {@link Bitmap}. For a {@code Drawable} or an immutable {@code Bitmap},
     *  this is the only way to process the image after decoding.</p>
     *
     *  <p>If set on a nine-patch image, the nine-patch data is ignored.</p>
     *
     *  <p>For an animated image, the drawing commands drawn on the
     *  {@link Canvas} will be recorded immediately and then applied to each
     *  frame.</p>
     */
    public void setPostProcessor(@Nullable PostProcessor p) {
        mPostProcessor = p;
    }

    /**
     *  Set (replace) the {@link OnPartialImageListener} on this object.
     *
     *  Will be called if there is an error in the input. Without one, a
     *  partial {@link Bitmap} will be created.
     */
    public void setOnPartialImageListener(@Nullable OnPartialImageListener l) {
        mOnPartialImageListener = l;
    }

    /**
     *  Crop the output to {@code subset} of the (possibly) scaled image.
     *
     *  <p>{@code subset} must be contained within the size set by
     *  {@link #setResize} or the bounds of the image if setResize was not
     *  called. Otherwise an {@link IllegalStateException} will be thrown by
     *  {@link #decodeDrawable}/{@link #decodeBitmap}.</p>
     *
     *  <p>NOT intended as a replacement for
     *  {@link BitmapRegionDecoder#decodeRegion}. This supports all formats,
     *  but merely crops the output.</p>
     */
    public void setCrop(@Nullable Rect subset) {
        mCropRect = subset;
    }

    /**
     *  Set a Rect for retrieving nine patch padding.
     *
     *  If the image is a nine patch, this Rect will be set to the padding
     *  rectangle during decode. Otherwise it will not be modified.
     *
     *  @hide
     */
    public void setOutPaddingRect(@NonNull Rect outPadding) {
        mOutPaddingRect = outPadding;
    }

    /**
     *  Specify whether the {@link Bitmap} should be mutable.
     *
     *  <p>By default, a {@link Bitmap} created will be immutable, but that can
     *  be changed with this call.</p>
     *
     *  <p>Mutable Bitmaps are incompatible with {@link #ALLOCATOR_HARDWARE},
     *  because {@link Bitmap.Config#HARDWARE} Bitmaps cannot be mutable.
     *  Attempting to combine them will throw an
     *  {@link java.lang.IllegalStateException}.</p>
     *
     *  <p>Mutable Bitmaps are also incompatible with {@link #decodeDrawable},
     *  which would require retrieving the Bitmap from the returned Drawable in
     *  order to modify. Attempting to decode a mutable {@link Drawable} will
     *  throw an {@link java.lang.IllegalStateException}.</p>
     */
    public void setMutable(boolean mutable) {
        mMutable = mutable;
    }

    /**
     *  Specify whether to potentially save RAM at the expense of quality.
     *
     *  Setting this to {@code true} may result in a {@link Bitmap} with a
     *  denser {@link Bitmap.Config}, depending on the image. For example, for
     *  an opaque {@link Bitmap}, this may result in a {@link Bitmap.Config}
     *  with no alpha information.
     */
    public void setPreferRamOverQuality(boolean preferRamOverQuality) {
        mPreferRamOverQuality = preferRamOverQuality;
    }

    /**
     *  Specify whether to potentially treat the output as an alpha mask.
     *
     *  <p>If this is set to {@code true} and the image is encoded in a format
     *  with only one channel, treat that channel as alpha. Otherwise this call has
     *  no effect.</p>
     *
     *  <p>setAsAlphaMask is incompatible with {@link #ALLOCATOR_HARDWARE}. Trying to
     *  combine them will result in {@link #decodeDrawable}/
     *  {@link #decodeBitmap} throwing an
     *  {@link java.lang.IllegalStateException}.</p>
     */
    public void setAsAlphaMask(boolean asAlphaMask) {
        mAsAlphaMask = asAlphaMask;
    }

    @Override
    public void close() {
        mCloseGuard.close();
        if (!mClosed.compareAndSet(false, true)) {
            return;
        }
        nClose(mNativePtr);
        mNativePtr = 0;

        if (mOwnsInputStream) {
            IoUtils.closeQuietly(mInputStream);
        }
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

        if (mAllocator == ALLOCATOR_HARDWARE) {
            if (mMutable) {
                throw new IllegalStateException("Cannot make mutable HARDWARE Bitmap!");
            }
            if (mAsAlphaMask) {
                throw new IllegalStateException("Cannot make HARDWARE Alpha mask Bitmap!");
            }
        }

        if (mPostProcessor != null && mRequireUnpremultiplied) {
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

    @NonNull
    private Bitmap decodeBitmap() throws IOException {
        checkState();
        // nDecodeBitmap calls onPartialImage only if mOnPartialImageListener
        // exists
        ImageDecoder partialImagePtr = mOnPartialImageListener == null ? null : this;
        // nDecodeBitmap calls postProcessAndRelease only if mPostProcessor
        // exists.
        ImageDecoder postProcessPtr = mPostProcessor == null ? null : this;
        return nDecodeBitmap(mNativePtr, partialImagePtr,
                postProcessPtr, mDesiredWidth, mDesiredHeight, mCropRect,
                mMutable, mAllocator, mRequireUnpremultiplied,
                mPreferRamOverQuality, mAsAlphaMask);
    }

    private void callHeaderDecoded(@Nullable OnHeaderDecodedListener listener,
            @NonNull Source src) {
        if (listener != null) {
            ImageInfo info = new ImageInfo(this);
            try {
                listener.onHeaderDecoded(this, info, src);
            } finally {
                info.mDecoder = null;
            }
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
            decoder.mSource = src;
            decoder.callHeaderDecoded(listener, src);

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

            // this call potentially manipulates the decoder so it must be performed prior to
            // decoding the bitmap and after decode set the density on the resulting bitmap
            final int srcDensity = computeDensity(src, decoder);
            if (decoder.mAnimated) {
                // AnimatedImageDrawable calls postProcessAndRelease only if
                // mPostProcessor exists.
                ImageDecoder postProcessPtr = decoder.mPostProcessor == null ?
                        null : decoder;
                Drawable d = new AnimatedImageDrawable(decoder.mNativePtr,
                        postProcessPtr, decoder.mDesiredWidth,
                        decoder.mDesiredHeight, srcDensity,
                        src.computeDstDensity(), decoder.mCropRect,
                        decoder.mInputStream, decoder.mAssetFd);
                // d has taken ownership of these objects.
                decoder.mInputStream = null;
                decoder.mAssetFd = null;
                return d;
            }

            Bitmap bm = decoder.decodeBitmap();
            bm.setDensity(srcDensity);

            Resources res = src.getResources();
            byte[] np = bm.getNinePatchChunk();
            if (np != null && NinePatch.isNinePatchChunk(np)) {
                Rect opticalInsets = new Rect();
                bm.getOpticalInsets(opticalInsets);
                Rect padding = decoder.mOutPaddingRect;
                if (padding == null) {
                    padding = new Rect();
                }
                nGetPadding(decoder.mNativePtr, padding);
                return new NinePatchDrawable(res, bm, np, padding,
                        opticalInsets, null);
            }

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
            decoder.mSource = src;
            decoder.callHeaderDecoded(listener, src);

            // this call potentially manipulates the decoder so it must be performed prior to
            // decoding the bitmap
            final int srcDensity = computeDensity(src, decoder);
            Bitmap bm = decoder.decodeBitmap();
            bm.setDensity(srcDensity);

            Rect padding = decoder.mOutPaddingRect;
            if (padding != null) {
                byte[] np = bm.getNinePatchChunk();
                if (np != null && NinePatch.isNinePatchChunk(np)) {
                    nGetPadding(decoder.mNativePtr, padding);
                }
            }

            return bm;
        }
    }

    // This method may modify the decoder so it must be called prior to performing the decode
    private static int computeDensity(@NonNull Source src, @NonNull ImageDecoder decoder) {
        // if the caller changed the size then we treat the density as unknown
        if (decoder.requestedResize()) {
            return Bitmap.DENSITY_NONE;
        }

        // Special stuff for compatibility mode: if the target density is not
        // the same as the display density, but the resource -is- the same as
        // the display density, then don't scale it down to the target density.
        // This allows us to load the system's density-correct resources into
        // an application in compatibility mode, without scaling those down
        // to the compatibility density only to have them scaled back up when
        // drawn to the screen.
        Resources res = src.getResources();
        final int srcDensity = src.getDensity();
        if (res != null && res.getDisplayMetrics().noncompatDensityDpi == srcDensity) {
            return srcDensity;
        }

        // downscale the bitmap if the asset has a higher density than the default
        final int dstDensity = src.computeDstDensity();
        if (srcDensity != Bitmap.DENSITY_NONE && srcDensity > dstDensity) {
            float scale = (float) dstDensity / srcDensity;
            int scaledWidth = (int) (decoder.mWidth * scale + 0.5f);
            int scaledHeight = (int) (decoder.mHeight * scale + 0.5f);
            decoder.setResize(scaledWidth, scaledHeight);
            return dstDensity;
        }

        return srcDensity;
    }

    @NonNull
    private String getMimeType() {
        return nGetMimeType(mNativePtr);
    }

    /**
     *  See {@link #decodeBitmap(Source, OnHeaderDecodedListener)}.
     */
    @NonNull
    public static Bitmap decodeBitmap(@NonNull Source src) throws IOException {
        return decodeBitmap(src, null);
    }

    /**
     * Private method called by JNI.
     */
    @SuppressWarnings("unused")
    private int postProcessAndRelease(@NonNull Canvas canvas) {
        try {
            return mPostProcessor.onPostProcess(canvas);
        } finally {
            canvas.release();
        }
    }

    /**
     * Private method called by JNI.
     */
    @SuppressWarnings("unused")
    private boolean onPartialImage(@Error int error) {
        return mOnPartialImageListener.onPartialImage(error, mSource);
    }

    private static native ImageDecoder nCreate(long asset) throws IOException;
    private static native ImageDecoder nCreate(ByteBuffer buffer,
                                               int position,
                                               int limit) throws IOException;
    private static native ImageDecoder nCreate(byte[] data, int offset,
                                               int length) throws IOException;
    private static native ImageDecoder nCreate(InputStream is, byte[] storage);
    // The fd must be seekable.
    private static native ImageDecoder nCreate(FileDescriptor fd) throws IOException;
    @NonNull
    private static native Bitmap nDecodeBitmap(long nativePtr,
            @Nullable ImageDecoder partialImageListener,
            @Nullable ImageDecoder postProcessor,
            int width, int height,
            @Nullable Rect cropRect, boolean mutable,
            int allocator, boolean requireUnpremul,
            boolean preferRamOverQuality, boolean asAlphaMask)
        throws IOException;
    private static native Size nGetSampledSize(long nativePtr,
                                               int sampleSize);
    private static native void nGetPadding(long nativePtr, @NonNull Rect outRect);
    private static native void nClose(long nativePtr);
    private static native String nGetMimeType(long nativePtr);
}

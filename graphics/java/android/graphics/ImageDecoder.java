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

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.AnyThread;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.annotation.WorkerThread;
import android.content.ContentResolver;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.content.res.AssetManager.AssetInputStream;
import android.content.res.Resources;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.NinePatchDrawable;
import android.net.Uri;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.util.DisplayMetrics;
import android.util.Size;
import android.util.TypedValue;

import dalvik.system.CloseGuard;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Retention;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 *  Class for decoding images as {@link Bitmap}s or {@link Drawable}s.
 */
public final class ImageDecoder implements AutoCloseable {
    /** @hide **/
    public static int sApiLevel;

    /**
     *  Source of the encoded image data.
     *
     *  <p>This object references the data that will be used to decode a
     *  Drawable or Bitmap in {@link #decodeDrawable} or {@link #decodeBitmap}.
     *  Constructing a {@code Source} (with one of the overloads of
     *  {@code createSource}) can be done on any thread because the construction
     *  simply captures values. The real work is done in decodeDrawable or
     *  decodeBitmap.</p>
     *
     *  <p>Further, a Source object can be reused with different settings, or
     *  even used simultaneously in multiple threads.</p>
     */
    public static abstract class Source {
        private Source() {}

        /* @hide */
        @Nullable
        Resources getResources() { return null; }

        /* @hide */
        int getDensity() { return Bitmap.DENSITY_NONE; }

        /* @hide */
        final int computeDstDensity() {
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
            return nCreate(mData, mOffset, mLength, this);
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
                return nCreate(mBuffer.array(), offset, length, this);
            }
            ByteBuffer buffer = mBuffer.slice();
            return nCreate(buffer, buffer.position(), buffer.limit(), this);
        }
    }

    private static class ContentResolverSource extends Source {
        ContentResolverSource(@NonNull ContentResolver resolver, @NonNull Uri uri,
                @Nullable Resources res) {
            mResolver = resolver;
            mUri = uri;
            mResources = res;
        }

        private final ContentResolver mResolver;
        private final Uri mUri;
        private final Resources mResources;

        @Nullable
        Resources getResources() { return mResources; }

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

                return createFromStream(is, true, this);
            }

            final FileDescriptor fd = assetFd.getFileDescriptor();
            final long offset = assetFd.getStartOffset();

            ImageDecoder decoder = null;
            try {
                try {
                    Os.lseek(fd, offset, SEEK_SET);
                    decoder = nCreate(fd, this);
                } catch (ErrnoException e) {
                    decoder = createFromStream(new FileInputStream(fd), true, this);
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
    private static ImageDecoder createFromFile(@NonNull File file,
            @NonNull Source source) throws IOException {
        FileInputStream stream = new FileInputStream(file);
        FileDescriptor fd = stream.getFD();
        try {
            Os.lseek(fd, 0, SEEK_CUR);
        } catch (ErrnoException e) {
            return createFromStream(stream, true, source);
        }

        ImageDecoder decoder = null;
        try {
            decoder = nCreate(fd, source);
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
            boolean closeInputStream, Source source) throws IOException {
        // Arbitrary size matches BitmapFactory.
        byte[] storage = new byte[16 * 1024];
        ImageDecoder decoder = null;
        try {
            decoder = nCreate(is, storage, source);
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
     *
     * Further, unlike other Sources, this one is not reusable.
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
                return createFromStream(is, false, this);
            }
        }
    }

    /**
     * Takes ownership of the AssetInputStream.
     *
     * @hide
     */
    public static class AssetInputStreamSource extends Source {
        public AssetInputStreamSource(@NonNull AssetInputStream ais,
                @NonNull Resources res, @NonNull TypedValue value) {
            mAssetInputStream = ais;
            mResources = res;

            if (value.density == TypedValue.DENSITY_DEFAULT) {
                mDensity = DisplayMetrics.DENSITY_DEFAULT;
            } else if (value.density != TypedValue.DENSITY_NONE) {
                mDensity = value.density;
            } else {
                mDensity = Bitmap.DENSITY_NONE;
            }
        }

        private AssetInputStream mAssetInputStream;
        private final Resources  mResources;
        private final int        mDensity;

        @Override
        public Resources getResources() { return mResources; }

        @Override
        public int getDensity() {
            return mDensity;
        }

        @Override
        public ImageDecoder createImageDecoder() throws IOException {
            synchronized (this) {
                if (mAssetInputStream == null) {
                    throw new IOException("Cannot reuse AssetInputStreamSource");
                }
                AssetInputStream ais = mAssetInputStream;
                mAssetInputStream = null;
                return createFromAsset(ais, this);
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
        private Object  mLock = new Object();

        @Override
        public Resources getResources() { return mResources; }

        @Override
        public int getDensity() {
            synchronized (mLock) {
                return mResDensity;
            }
        }

        @Override
        public ImageDecoder createImageDecoder() throws IOException {
            TypedValue value = new TypedValue();
            // This is just used in order to access the underlying Asset and
            // keep it alive.
            InputStream is = mResources.openRawResource(mResId, value);

            synchronized (mLock) {
                if (value.density == TypedValue.DENSITY_DEFAULT) {
                    mResDensity = DisplayMetrics.DENSITY_DEFAULT;
                } else if (value.density != TypedValue.DENSITY_NONE) {
                    mResDensity = value.density;
                }
            }

            return createFromAsset((AssetInputStream) is, this);
        }
    }

    /**
     *  ImageDecoder will own the AssetInputStream.
     */
    private static ImageDecoder createFromAsset(AssetInputStream ais,
            Source source) throws IOException {
        ImageDecoder decoder = null;
        try {
            long asset = ais.getNativeAsset();
            decoder = nCreate(asset, source);
        } finally {
            if (decoder == null) {
                IoUtils.closeQuietly(ais);
            } else {
                decoder.mInputStream = ais;
                decoder.mOwnsInputStream = true;
            }
        }
        return decoder;
    }

    private static class AssetSource extends Source {
        AssetSource(@NonNull AssetManager assets, @NonNull String fileName) {
            mAssets = assets;
            mFileName = fileName;
        }

        private final AssetManager mAssets;
        private final String mFileName;

        @Override
        public ImageDecoder createImageDecoder() throws IOException {
            InputStream is = mAssets.open(mFileName);
            return createFromAsset((AssetInputStream) is, this);
        }
    }

    private static class FileSource extends Source {
        FileSource(@NonNull File file) {
            mFile = file;
        }

        private final File mFile;

        @Override
        public ImageDecoder createImageDecoder() throws IOException {
            return createFromFile(mFile, this);
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

        /**
         * If known, the color space the decoded bitmap will have. Note that the
         * output color space is not guaranteed to be the color space the bitmap
         * is encoded with. If not known (when the config is
         * {@link Bitmap.Config#ALPHA_8} for instance), or there is an error,
         * it is set to null.
         */
        @Nullable
        public ColorSpace getColorSpace() {
            return mDecoder.getColorSpace();
        }
    };

    /** @removed
     * @deprecated Subsumed by {@link #DecodeException}.
     */
    @java.lang.Deprecated
    public static class IncompleteException extends IOException {};

    /**
     *  Optional listener supplied to {@link #decodeDrawable} or
     *  {@link #decodeBitmap}.
     *
     *  <p>This is necessary in order to change the default settings of the
     *  decode.</p>
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

    /** @removed
     * @deprecated Replaced by {@link #DecodeException#SOURCE_EXCEPTION}.
     */
    @java.lang.Deprecated
    public static final int ERROR_SOURCE_EXCEPTION  = 1;

    /** @removed
     * @deprecated Replaced by {@link #DecodeException#SOURCE_INCOMPLETE}.
     */
    @java.lang.Deprecated
    public static final int ERROR_SOURCE_INCOMPLETE = 2;

    /** @removed
     * @deprecated Replaced by {@link #DecodeException#SOURCE_MALFORMED_DATA}.
     */
    @java.lang.Deprecated
    public static final int ERROR_SOURCE_ERROR      = 3;

    /**
     *  Information about an interrupted decode.
     */
    public static final class DecodeException extends IOException {
        /**
         *  An Exception was thrown reading the {@link Source}.
         */
        public static final int SOURCE_EXCEPTION  = 1;

        /**
         *  The encoded data was incomplete.
         */
        public static final int SOURCE_INCOMPLETE = 2;

        /**
         *  The encoded data contained an error.
         */
        public static final int SOURCE_MALFORMED_DATA      = 3;

        /** @hide **/
        @Retention(SOURCE)
        @IntDef(value = { SOURCE_EXCEPTION, SOURCE_INCOMPLETE, SOURCE_MALFORMED_DATA },
                prefix = {"SOURCE_"})
        public @interface Error {};

        @Error final int mError;
        @NonNull final Source mSource;

        DecodeException(@Error int error, @Nullable Throwable cause, @NonNull Source source) {
            super(errorMessage(error, cause), cause);
            mError = error;
            mSource = source;
        }

        /**
         * Private method called by JNI.
         */
        @SuppressWarnings("unused")
        DecodeException(@Error int error, @Nullable String msg, @Nullable Throwable cause,
                @NonNull Source source) {
            super(msg + errorMessage(error, cause), cause);
            mError = error;
            mSource = source;
        }

        /**
         *  Retrieve the reason that decoding was interrupted.
         *
         *  <p>If the error is {@link #SOURCE_EXCEPTION}, the underlying
         *  {@link java.lang.Throwable} can be retrieved with
         *  {@link java.lang.Throwable#getCause}.</p>
         */
        @Error
        public int getError() {
            return mError;
        }

        /**
         *  Retrieve the {@link Source} that was interrupted.
         *
         *  <p>This can be used for equality checking to find the Source which
         *  failed to completely decode.</p>
         */
        @NonNull
        public Source getSource() {
            return mSource;
        }

        private static String errorMessage(@Error int error, @Nullable Throwable cause) {
            switch (error) {
                case SOURCE_EXCEPTION:
                    return "Exception in input: " + cause;
                case SOURCE_INCOMPLETE:
                    return "Input was incomplete.";
                case SOURCE_MALFORMED_DATA:
                    return "Input contained an error.";
                default:
                    return "";
            }
        }
    }

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
         *  @param e containing information about the decode interruption.
         *  @return True to create and return a {@link Drawable}/{@link Bitmap}
         *      with partial data. False (which is the default) to abort the
         *      decode and throw {@code e}.
         */
        boolean onPartialImage(@NonNull DecodeException e);
    };

    // Fields
    private long          mNativePtr;
    private final int     mWidth;
    private final int     mHeight;
    private final boolean mAnimated;

    private int        mDesiredWidth;
    private int        mDesiredHeight;
    private int        mAllocator = ALLOCATOR_DEFAULT;
    private boolean    mUnpremultipliedRequired = false;
    private boolean    mMutable = false;
    private boolean    mConserveMemory = false;
    private boolean    mDecodeAsAlphaMask = false;
    private ColorSpace mDesiredColorSpace = null;
    private Rect       mCropRect;
    private Rect       mOutPaddingRect;
    private Source     mSource;

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
     * Create a new {@link Source} from a resource.
     *
     * @param res the {@link Resources} object containing the image data.
     * @param resId resource ID of the image data.
     * @return a new Source object, which can be passed to
     *      {@link #decodeDrawable} or {@link #decodeBitmap}.
     */
    @AnyThread
    @NonNull
    public static Source createSource(@NonNull Resources res, int resId)
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
    @AnyThread
    @NonNull
    public static Source createSource(@NonNull ContentResolver cr,
            @NonNull Uri uri) {
        return new ContentResolverSource(cr, uri, null);
    }

    /**
     * Provide Resources for density scaling.
     *
     * @hide
     */
    @AnyThread
    @NonNull
    public static Source createSource(@NonNull ContentResolver cr,
            @NonNull Uri uri, @Nullable Resources res) {
        return new ContentResolverSource(cr, uri, res);
    }

    /**
     * Create a new {@link Source} from a file in the "assets" directory.
     */
    @AnyThread
    @NonNull
    public static Source createSource(@NonNull AssetManager assets, @NonNull String fileName) {
        return new AssetSource(assets, fileName);
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
    @AnyThread
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
    @AnyThread
    @NonNull
    public static Source createSource(@NonNull byte[] data) {
        return createSource(data, 0, data.length);
    }

    /**
     * Create a new {@link Source} from a {@link java.nio.ByteBuffer}.
     *
     * <p>Decoding will start from {@link java.nio.ByteBuffer#position()}. The
     * position of {@code buffer} will not be affected.</p>
     *
     * <p>Note: If this {@code Source} is passed to {@link #decodeDrawable}, and
     * the encoded image is animated, the returned {@link AnimatedImageDrawable}
     * will continue reading from the {@code buffer}, so its contents must not
     * be modified, even after the {@code AnimatedImageDrawable} is returned.
     * {@code buffer}'s contents should never be modified during decode.</p>
     */
    @AnyThread
    @NonNull
    public static Source createSource(@NonNull ByteBuffer buffer) {
        return new ByteBufferSource(buffer);
    }

    /**
     * Internal API used to generate bitmaps for use by Drawables (i.e. BitmapDrawable)
     *
     * <p>Unlike other Sources, this one cannot be reused.</p>
     *
     * @hide
     */
    @AnyThread
    @NonNull
    public static Source createSource(Resources res, InputStream is) {
        return new InputStreamSource(res, is, Bitmap.getDefaultDensity());
    }

    /**
     * Internal API used to generate bitmaps for use by Drawables (i.e. BitmapDrawable)
     *
     * <p>Unlike other Sources, this one cannot be reused.</p>
     *
     * @hide
     */
    @AnyThread
    @TestApi
    @NonNull
    public static Source createSource(Resources res, InputStream is, int density) {
        return new InputStreamSource(res, is, density);
    }

    /**
     * Create a new {@link Source} from a {@link java.io.File}.
     */
    @AnyThread
    @NonNull
    public static Source createSource(@NonNull File file) {
        return new FileSource(file);
    }

    /**
     *  Return the width and height of a given sample size.
     *
     *  <p>This takes an input that functions like
     *  {@link BitmapFactory.Options#inSampleSize}. It returns a width and
     *  height that can be achieved by sampling the encoded image. Other widths
     *  and heights may be supported, but will require an additional (internal)
     *  scaling step. Such internal scaling is *not* supported with
     *  {@link #setUnpremultipliedRequired} set to {@code true}.</p>
     *
     *  @param sampleSize Sampling rate of the encoded image.
     *  @return {@link android.util.Size} of the width and height after
     *      sampling.
     *
     *  @hide
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
    /** @removed
     * @deprecated Renamed to {@link #setTargetSize}.
     */
    @java.lang.Deprecated
    public ImageDecoder setResize(int width, int height) {
        this.setTargetSize(width, height);
        return this;
    }

    /**
     *  Specify the size of the output {@link Drawable} or {@link Bitmap}.
     *
     *  <p>By default, the output size will match the size of the encoded
     *  image, which can be retrieved from the {@link ImageInfo} in
     *  {@link OnHeaderDecodedListener#onHeaderDecoded}.</p>
     *
     *  <p>Only the last call to this or {@link #setTargetSampleSize} is
     *  respected.</p>
     *
     *  <p>Like all setters on ImageDecoder, this must be called inside
     *  {@link OnHeaderDecodedListener#onHeaderDecoded}.</p>
     *
     *  @param width must be greater than 0.
     *  @param height must be greater than 0.
     */
    public void setTargetSize(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Dimensions must be positive! "
                    + "provided (" + width + ", " + height + ")");
        }

        mDesiredWidth = width;
        mDesiredHeight = height;
    }

    /** @removed
     * @deprecated Renamed to {@link #setTargetSampleSize}.
     */
    @java.lang.Deprecated
    public ImageDecoder setResize(int sampleSize) {
        this.setTargetSampleSize(sampleSize);
        return this;
    }

    private int getTargetDimension(int original, int sampleSize, int computed) {
        // Sampling will never result in a smaller size than 1.
        if (sampleSize >= original) {
            return 1;
        }

        // Use integer divide to find the desired size. If that is what
        // getSampledSize computed, that is the size to use.
        int target = original / sampleSize;
        if (computed == target) {
            return computed;
        }

        // If sampleSize does not divide evenly into original, the decoder
        // may round in either direction. It just needs to get a result that
        // is close.
        int reverse = computed * sampleSize;
        if (Math.abs(reverse - original) < sampleSize) {
            // This is the size that can be decoded most efficiently.
            return computed;
        }

        // The decoder could not get close (e.g. it is a DNG image).
        return target;
    }

    /**
     *  Set the target size with a sampleSize.
     *
     *  <p>By default, the output size will match the size of the encoded
     *  image, which can be retrieved from the {@link ImageInfo} in
     *  {@link OnHeaderDecodedListener#onHeaderDecoded}.</p>
     *
     *  <p>Requests the decoder to subsample the original image, returning a
     *  smaller image to save memory. The sample size is the number of pixels
     *  in either dimension that correspond to a single pixel in the output.
     *  For example, sampleSize == 4 returns an image that is 1/4 the
     *  width/height of the original, and 1/16 the number of pixels.</p>
     *
     *  <p>Must be greater than or equal to 1.</p>
     *
     *  <p>Only the last call to this or {@link #setTargetSize} is respected.</p>
     *
     *  <p>Like all setters on ImageDecoder, this must be called inside
     *  {@link OnHeaderDecodedListener#onHeaderDecoded}.</p>
     *
     *  @param sampleSize Sampling rate of the encoded image.
     */
    public void setTargetSampleSize(int sampleSize) {
        Size size = this.getSampledSize(sampleSize);
        int targetWidth = getTargetDimension(mWidth, sampleSize, size.getWidth());
        int targetHeight = getTargetDimension(mHeight, sampleSize, size.getHeight());
        this.setTargetSize(targetWidth, targetHeight);
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
     *  {@link #setMutableRequired}, {@link #setDecodeAsAlphaMaskEnabled}.
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
     *  {@link #setMutableRequired} or {@link #setDecodeAsAlphaMaskEnabled},
     *  {@link #decodeDrawable} / {@link #decodeBitmap} will throw an
     *  {@link java.lang.IllegalStateException}.
     */
    public static final int ALLOCATOR_HARDWARE = 3;

    /** @hide **/
    @Retention(SOURCE)
    @IntDef(value = { ALLOCATOR_DEFAULT, ALLOCATOR_SOFTWARE,
              ALLOCATOR_SHARED_MEMORY, ALLOCATOR_HARDWARE },
              prefix = {"ALLOCATOR_"})
    public @interface Allocator {};

    /**
     *  Choose the backing for the pixel memory.
     *
     *  <p>This is ignored for animated drawables.</p>
     *
     *  <p>Like all setters on ImageDecoder, this must be called inside
     *  {@link OnHeaderDecodedListener#onHeaderDecoded}.</p>
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
     *  Return the allocator for the pixel memory.
     */
    @Allocator
    public int getAllocator() {
        return mAllocator;
    }

    /**
     *  Specify whether the {@link Bitmap} should have unpremultiplied pixels.
     *
     *  <p>By default, ImageDecoder will create a {@link Bitmap} with
     *  premultiplied pixels, which is required for drawing with the
     *  {@link android.view.View} system (i.e. to a {@link Canvas}). Calling
     *  this method with a value of {@code true} will result in
     *  {@link #decodeBitmap} returning a {@link Bitmap} with unpremultiplied
     *  pixels. See {@link Bitmap#isPremultiplied}. This is incompatible with
     *  {@link #decodeDrawable}; attempting to decode an unpremultiplied
     *  {@link Drawable} will throw an {@link java.lang.IllegalStateException}.
     *  </p>
     *
     *  <p>Like all setters on ImageDecoder, this must be called inside
     *  {@link OnHeaderDecodedListener#onHeaderDecoded}.</p>
     */
    public void setUnpremultipliedRequired(boolean unpremultipliedRequired) {
        mUnpremultipliedRequired = unpremultipliedRequired;
    }

    /** @removed
     * @deprecated Renamed to {@link #setUnpremultipliedRequired}.
     */
    @java.lang.Deprecated
    public ImageDecoder setRequireUnpremultiplied(boolean unpremultipliedRequired) {
        this.setUnpremultipliedRequired(unpremultipliedRequired);
        return this;
    }

    /**
     *  Return whether the {@link Bitmap} will have unpremultiplied pixels.
     */
    public boolean isUnpremultipliedRequired() {
        return mUnpremultipliedRequired;
    }

    /** @removed
     * @deprecated Renamed to {@link #isUnpremultipliedRequired}.
     */
    @java.lang.Deprecated
    public boolean getRequireUnpremultiplied() {
        return this.isUnpremultipliedRequired();
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
     *
     *  <p>Like all setters on ImageDecoder, this must be called inside
     *  {@link OnHeaderDecodedListener#onHeaderDecoded}.</p>
     *
     */
    public void setPostProcessor(@Nullable PostProcessor p) {
        mPostProcessor = p;
    }

    /**
     *  Return the {@link PostProcessor} currently set.
     */
    @Nullable
    public PostProcessor getPostProcessor() {
        return mPostProcessor;
    }

    /**
     *  Set (replace) the {@link OnPartialImageListener} on this object.
     *
     *  <p>Will be called if there is an error in the input. Without one, an
     *  error will result in an Exception being thrown.</p>
     *
     *  <p>Like all setters on ImageDecoder, this must be called inside
     *  {@link OnHeaderDecodedListener#onHeaderDecoded}.</p>
     *
     */
    public void setOnPartialImageListener(@Nullable OnPartialImageListener l) {
        mOnPartialImageListener = l;
    }

    /**
     *  Return the {@link OnPartialImageListener} currently set.
     */
    @Nullable
    public OnPartialImageListener getOnPartialImageListener() {
        return mOnPartialImageListener;
    }

    /**
     *  Crop the output to {@code subset} of the (possibly) scaled image.
     *
     *  <p>{@code subset} must be contained within the size set by
     *  {@link #setTargetSize} or the bounds of the image if setTargetSize was
     *  not called. Otherwise an {@link IllegalStateException} will be thrown by
     *  {@link #decodeDrawable}/{@link #decodeBitmap}.</p>
     *
     *  <p>NOT intended as a replacement for
     *  {@link BitmapRegionDecoder#decodeRegion}. This supports all formats,
     *  but merely crops the output.</p>
     *
     *  <p>Like all setters on ImageDecoder, this must be called inside
     *  {@link OnHeaderDecodedListener#onHeaderDecoded}.</p>
     *
     */
    public void setCrop(@Nullable Rect subset) {
        mCropRect = subset;
    }

    /**
     *  Return the cropping rectangle, if set.
     */
    @Nullable
    public Rect getCrop() {
        return mCropRect;
    }

    /**
     *  Set a Rect for retrieving nine patch padding.
     *
     *  If the image is a nine patch, this Rect will be set to the padding
     *  rectangle during decode. Otherwise it will not be modified.
     *
     *  <p>Like all setters on ImageDecoder, this must be called inside
     *  {@link OnHeaderDecodedListener#onHeaderDecoded}.</p>
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
     *
     *  <p>Like all setters on ImageDecoder, this must be called inside
     *  {@link OnHeaderDecodedListener#onHeaderDecoded}.</p>
     */
    public void setMutableRequired(boolean mutable) {
        mMutable = mutable;
    }

    /** @removed
     * @deprecated Renamed to {@link #setMutableRequired}.
     */
    @java.lang.Deprecated
    public ImageDecoder setMutable(boolean mutable) {
        this.setMutableRequired(mutable);
        return this;
    }

    /**
     *  Return whether the {@link Bitmap} will be mutable.
     */
    public boolean isMutableRequired() {
        return mMutable;
    }

    /** @removed
     * @deprecated Renamed to {@link #isMutableRequired}.
     */
    @java.lang.Deprecated
    public boolean getMutable() {
        return this.isMutableRequired();
    }

    /**
     *  Specify whether to potentially save RAM at the expense of quality.
     *
     *  <p>Setting this to {@code true} may result in a {@link Bitmap} with a
     *  denser {@link Bitmap.Config}, depending on the image. For example, an
     *  opaque {@link Bitmap} with 8 bits or precision for each of its red,
     *  green and blue components would decode to
     *  {@link Bitmap.Config#ARGB_8888} by default, but setting this to
     *  {@code true} will result in decoding to {@link Bitmap.Config#RGB_565}.
     *  This necessarily lowers the quality of the output, but saves half
     *  the memory used.</p>
     *
     *  <p>Like all setters on ImageDecoder, this must be called inside
     *  {@link OnHeaderDecodedListener#onHeaderDecoded}.</p>
     */
    public void setConserveMemory(boolean conserveMemory) {
        mConserveMemory = conserveMemory;
    }

    /**
     *  Return whether this object will try to save RAM at the expense of quality.
     *
     *  <p>This returns whether {@link #setConserveMemory} was set to {@code true}.
     *  It may still return {@code true} even if the {@code ImageDecoder} does not
     *  have a way to save RAM at the expense of quality for this image.</p>
     */
    public boolean getConserveMemory() {
        return mConserveMemory;
    }

    /**
     *  Specify whether to potentially treat the output as an alpha mask.
     *
     *  <p>If this is set to {@code true} and the image is encoded in a format
     *  with only one channel, treat that channel as alpha. Otherwise this call has
     *  no effect.</p>
     *
     *  <p>This is incompatible with {@link #ALLOCATOR_HARDWARE}. Trying to
     *  combine them will result in {@link #decodeDrawable}/
     *  {@link #decodeBitmap} throwing an
     *  {@link java.lang.IllegalStateException}.</p>
     *
     *  <p>Like all setters on ImageDecoder, this must be called inside
     *  {@link OnHeaderDecodedListener#onHeaderDecoded}.</p>
     */
    public void setDecodeAsAlphaMaskEnabled(boolean enabled) {
        mDecodeAsAlphaMask = enabled;
    }

    /** @removed
     * @deprecated Renamed to {@link #setDecodeAsAlphaMaskEnabled}.
     */
    @java.lang.Deprecated
    public ImageDecoder setDecodeAsAlphaMask(boolean enabled) {
        this.setDecodeAsAlphaMaskEnabled(enabled);
        return this;
    }

    /** @removed
     * @deprecated Renamed to {@link #setDecodeAsAlphaMaskEnabled}.
     */
    @java.lang.Deprecated
    public ImageDecoder setAsAlphaMask(boolean asAlphaMask) {
        this.setDecodeAsAlphaMask(asAlphaMask);
        return this;
    }

    /**
     *  Return whether to treat single channel input as alpha.
     *
     *  <p>This returns whether {@link #setDecodeAsAlphaMaskEnabled} was set to
     *  {@code true}. It may still return {@code true} even if the image has
     *  more than one channel and therefore will not be treated as an alpha
     *  mask.</p>
     */
    public boolean isDecodeAsAlphaMaskEnabled() {
        return mDecodeAsAlphaMask;
    }

    /** @removed
     * @deprecated Renamed to {@link #isDecodeAsAlphaMaskEnabled}.
     */
    @java.lang.Deprecated
    public boolean getDecodeAsAlphaMask() {
        return mDecodeAsAlphaMask;
    }

    /** @removed
     * @deprecated Renamed to {@link #isDecodeAsAlphaMaskEnabled}.
     */
    @java.lang.Deprecated
    public boolean getAsAlphaMask() {
        return this.getDecodeAsAlphaMask();
    }

    /**
     * Specify the desired {@link ColorSpace} for the output.
     *
     * <p>If non-null, the decoder will try to decode into this
     * color space. If it is null, which is the default, or the request cannot
     * be met, the decoder will pick either the color space embedded in the
     * image or the color space best suited for the requested image
     * configuration (for instance {@link ColorSpace.Named#SRGB sRGB} for
     * the {@link Bitmap.Config#ARGB_8888} configuration).</p>
     *
     * <p>{@link Bitmap.Config#RGBA_F16} always uses the
     * {@link ColorSpace.Named#LINEAR_EXTENDED_SRGB scRGB} color space).
     * Bitmaps in other configurations without an embedded color space are
     * assumed to be in the {@link ColorSpace.Named#SRGB sRGB} color space.</p>
     *
     * <p class="note">Only {@link ColorSpace.Model#RGB} color spaces are
     * currently supported. An <code>IllegalArgumentException</code> will
     * be thrown by the decode methods when setting a non-RGB color space
     * such as {@link ColorSpace.Named#CIE_LAB Lab}.</p>
     *
     * <p class="note">The specified color space's transfer function must be
     * an {@link ColorSpace.Rgb.TransferParameters ICC parametric curve}. An
     * <code>IllegalArgumentException</code> will be thrown by the decode methods
     * if calling {@link ColorSpace.Rgb#getTransferParameters()} on the
     * specified color space returns null.</p>
     *
     * <p>Like all setters on ImageDecoder, this must be called inside
     * {@link OnHeaderDecodedListener#onHeaderDecoded}.</p>
     */
    public void setTargetColorSpace(ColorSpace colorSpace) {
        mDesiredColorSpace = colorSpace;
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
            if (mDecodeAsAlphaMask) {
                throw new IllegalStateException("Cannot make HARDWARE Alpha mask Bitmap!");
            }
        }

        if (mPostProcessor != null && mUnpremultipliedRequired) {
            throw new IllegalStateException("Cannot draw to unpremultiplied pixels!");
        }

        if (mDesiredColorSpace != null) {
            if (!(mDesiredColorSpace instanceof ColorSpace.Rgb)) {
                throw new IllegalArgumentException("The target color space must use the "
                            + "RGB color model - provided: " + mDesiredColorSpace);
            }
            if (((ColorSpace.Rgb) mDesiredColorSpace).getTransferParameters() == null) {
                throw new IllegalArgumentException("The target color space must use an "
                            + "ICC parametric transfer function - provided: " + mDesiredColorSpace);
            }
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

    @WorkerThread
    @NonNull
    private Bitmap decodeBitmapInternal() throws IOException {
        checkState();
        return nDecodeBitmap(mNativePtr, this, mPostProcessor != null,
                mDesiredWidth, mDesiredHeight, mCropRect,
                mMutable, mAllocator, mUnpremultipliedRequired,
                mConserveMemory, mDecodeAsAlphaMask, mDesiredColorSpace);
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
     *      default settings on the {@code ImageDecoder}. This will be called on
     *      the same thread as {@code decodeDrawable} before that method returns.
     *      This is required in order to change any of the default settings.
     *  @return Drawable for displaying the image.
     *  @throws IOException if {@code src} is not found, is an unsupported
     *      format, or cannot be decoded for any reason.
     */
    @WorkerThread
    @NonNull
    public static Drawable decodeDrawable(@NonNull Source src,
            @NonNull OnHeaderDecodedListener listener) throws IOException {
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null! "
                    + "Use decodeDrawable(Source) to not have a listener");
        }
        return decodeDrawableImpl(src, listener);
    }

    @WorkerThread
    @NonNull
    private static Drawable decodeDrawableImpl(@NonNull Source src,
            @Nullable OnHeaderDecodedListener listener) throws IOException {
        try (ImageDecoder decoder = src.createImageDecoder()) {
            decoder.mSource = src;
            decoder.callHeaderDecoded(listener, src);

            if (decoder.mUnpremultipliedRequired) {
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

            Bitmap bm = decoder.decodeBitmapInternal();
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
     *  Create a {@link Drawable} from a {@code Source}.
     *
     *  <p>Since there is no {@link OnHeaderDecodedListener}, the default
     *  settings will be used. In order to change any settings, call
     *  {@link #decodeDrawable(Source, OnHeaderDecodedListener)} instead.</p>
     *
     *  @param src representing the encoded image.
     *  @return Drawable for displaying the image.
     *  @throws IOException if {@code src} is not found, is an unsupported
     *      format, or cannot be decoded for any reason.
     */
    @WorkerThread
    @NonNull
    public static Drawable decodeDrawable(@NonNull Source src)
            throws IOException {
        return decodeDrawableImpl(src, null);
    }

    /**
     *  Create a {@link Bitmap} from a {@code Source}.
     *
     *  @param src representing the encoded image.
     *  @param listener for learning the {@link ImageInfo} and changing any
     *      default settings on the {@code ImageDecoder}. This will be called on
     *      the same thread as {@code decodeBitmap} before that method returns.
     *      This is required in order to change any of the default settings.
     *  @return Bitmap containing the image.
     *  @throws IOException if {@code src} is not found, is an unsupported
     *      format, or cannot be decoded for any reason.
     */
    @WorkerThread
    @NonNull
    public static Bitmap decodeBitmap(@NonNull Source src,
            @NonNull OnHeaderDecodedListener listener) throws IOException {
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null! "
                    + "Use decodeBitmap(Source) to not have a listener");
        }
        return decodeBitmapImpl(src, listener);
    }

    @WorkerThread
    @NonNull
    private static Bitmap decodeBitmapImpl(@NonNull Source src,
            @Nullable OnHeaderDecodedListener listener) throws IOException {
        try (ImageDecoder decoder = src.createImageDecoder()) {
            decoder.mSource = src;
            decoder.callHeaderDecoded(listener, src);

            // this call potentially manipulates the decoder so it must be performed prior to
            // decoding the bitmap
            final int srcDensity = computeDensity(src, decoder);
            Bitmap bm = decoder.decodeBitmapInternal();
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

        // For P and above, only resize if it would be a downscale. Scale up prior
        // to P in case the app relies on the Bitmap's size without considering density.
        final int dstDensity = src.computeDstDensity();
        if (srcDensity == Bitmap.DENSITY_NONE || srcDensity == dstDensity
                || (srcDensity < dstDensity && sApiLevel >= Build.VERSION_CODES.P)) {
            return srcDensity;
        }

        float scale = (float) dstDensity / srcDensity;
        int scaledWidth = (int) (decoder.mWidth * scale + 0.5f);
        int scaledHeight = (int) (decoder.mHeight * scale + 0.5f);
        decoder.setTargetSize(scaledWidth, scaledHeight);
        return dstDensity;
    }

    @NonNull
    private String getMimeType() {
        return nGetMimeType(mNativePtr);
    }

    @Nullable
    private ColorSpace getColorSpace() {
        return nGetColorSpace(mNativePtr);
    }

    /**
     *  Create a {@link Bitmap} from a {@code Source}.
     *
     *  <p>Since there is no {@link OnHeaderDecodedListener}, the default
     *  settings will be used. In order to change any settings, call
     *  {@link #decodeBitmap(Source, OnHeaderDecodedListener)} instead.</p>
     *
     *  @param src representing the encoded image.
     *  @return Bitmap containing the image.
     *  @throws IOException if {@code src} is not found, is an unsupported
     *      format, or cannot be decoded for any reason.
     */
    @WorkerThread
    @NonNull
    public static Bitmap decodeBitmap(@NonNull Source src) throws IOException {
        return decodeBitmapImpl(src, null);
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
    private void onPartialImage(@DecodeException.Error int error, @Nullable Throwable cause)
            throws DecodeException {
        DecodeException exception = new DecodeException(error, cause, mSource);
        if (mOnPartialImageListener == null
                || !mOnPartialImageListener.onPartialImage(exception)) {
            throw exception;
        }
    }

    private static native ImageDecoder nCreate(long asset, Source src) throws IOException;
    private static native ImageDecoder nCreate(ByteBuffer buffer, int position,
                                               int limit, Source src) throws IOException;
    private static native ImageDecoder nCreate(byte[] data, int offset, int length,
                                               Source src) throws IOException;
    private static native ImageDecoder nCreate(InputStream is, byte[] storage,
                                               Source src) throws IOException;
    // The fd must be seekable.
    private static native ImageDecoder nCreate(FileDescriptor fd, Source src) throws IOException;
    @NonNull
    private static native Bitmap nDecodeBitmap(long nativePtr,
            @NonNull ImageDecoder decoder,
            boolean doPostProcess,
            int width, int height,
            @Nullable Rect cropRect, boolean mutable,
            int allocator, boolean unpremulRequired,
            boolean conserveMemory, boolean decodeAsAlphaMask,
            @Nullable ColorSpace desiredColorSpace)
        throws IOException;
    private static native Size nGetSampledSize(long nativePtr,
                                               int sampleSize);
    private static native void nGetPadding(long nativePtr, @NonNull Rect outRect);
    private static native void nClose(long nativePtr);
    private static native String nGetMimeType(long nativePtr);
    private static native ColorSpace nGetColorSpace(long nativePtr);
}

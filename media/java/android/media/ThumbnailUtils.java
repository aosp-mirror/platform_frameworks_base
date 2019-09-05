/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.media;

import static android.media.MediaMetadataRetriever.METADATA_KEY_DURATION;
import static android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT;
import static android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH;
import static android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC;
import static android.os.Environment.MEDIA_UNKNOWN;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UnsupportedAppUsage;
import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.ImageDecoder.ImageInfo;
import android.graphics.ImageDecoder.Source;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore.ThumbnailConstants;
import android.util.Log;
import android.util.Size;

import com.android.internal.util.ArrayUtils;

import libcore.io.IoUtils;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import java.util.function.ToIntFunction;

/**
 * Utilities for generating visual thumbnails from files.
 */
public class ThumbnailUtils {
    private static final String TAG = "ThumbnailUtils";

    /** @hide */
    @Deprecated
    @UnsupportedAppUsage
    public static final int TARGET_SIZE_MICRO_THUMBNAIL = 96;

    /* Options used internally. */
    private static final int OPTIONS_NONE = 0x0;
    private static final int OPTIONS_SCALE_UP = 0x1;

    /**
     * Constant used to indicate we should recycle the input in
     * {@link #extractThumbnail(Bitmap, int, int, int)} unless the output is the input.
     */
    public static final int OPTIONS_RECYCLE_INPUT = 0x2;

    private static Size convertKind(int kind) {
        if (kind == ThumbnailConstants.MICRO_KIND) {
            return Point.convert(ThumbnailConstants.MICRO_SIZE);
        } else if (kind == ThumbnailConstants.FULL_SCREEN_KIND) {
            return Point.convert(ThumbnailConstants.FULL_SCREEN_SIZE);
        } else if (kind == ThumbnailConstants.MINI_KIND) {
            return Point.convert(ThumbnailConstants.MINI_SIZE);
        } else {
            throw new IllegalArgumentException("Unsupported kind: " + kind);
        }
    }

    private static class Resizer implements ImageDecoder.OnHeaderDecodedListener {
        private final Size size;
        private final CancellationSignal signal;

        public Resizer(Size size, CancellationSignal signal) {
            this.size = size;
            this.signal = signal;
        }

        @Override
        public void onHeaderDecoded(ImageDecoder decoder, ImageInfo info, Source source) {
            // One last-ditch check to see if we've been canceled.
            if (signal != null) signal.throwIfCanceled();

            // We don't know how clients will use the decoded data, so we have
            // to default to the more flexible "software" option.
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);

            // We requested a rough thumbnail size, but the remote size may have
            // returned something giant, so defensively scale down as needed.
            final int widthSample = info.getSize().getWidth() / size.getWidth();
            final int heightSample = info.getSize().getHeight() / size.getHeight();
            final int sample = Math.max(widthSample, heightSample);
            if (sample > 1) {
                decoder.setTargetSampleSize(sample);
            }
        }
    }

    /**
     * Create a thumbnail for given audio file.
     *
     * @param filePath The audio file.
     * @param kind The desired thumbnail kind, such as
     *            {@link android.provider.MediaStore.Images.Thumbnails#MINI_KIND}.
     * @deprecated Callers should migrate to using
     *             {@link #createAudioThumbnail(File, Size, CancellationSignal)},
     *             as it offers more control over resizing and cancellation.
     */
    @Deprecated
    public static @Nullable Bitmap createAudioThumbnail(@NonNull String filePath, int kind) {
        try {
            return createAudioThumbnail(new File(filePath), convertKind(kind), null);
        } catch (IOException e) {
            Log.w(TAG, e);
            return null;
        }
    }

    /**
     * Create a thumbnail for given audio file.
     *
     * @param file The audio file.
     * @param size The desired thumbnail size.
     * @throws IOException If any trouble was encountered while generating or
     *             loading the thumbnail, or if
     *             {@link CancellationSignal#cancel()} was invoked.
     */
    public static @NonNull Bitmap createAudioThumbnail(@NonNull File file, @NonNull Size size,
            @Nullable CancellationSignal signal) throws IOException {
        // Checkpoint before going deeper
        if (signal != null) signal.throwIfCanceled();

        final Resizer resizer = new Resizer(size, signal);
        try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
            retriever.setDataSource(file.getAbsolutePath());
            final byte[] raw = retriever.getEmbeddedPicture();
            if (raw != null) {
                return ImageDecoder.decodeBitmap(ImageDecoder.createSource(raw), resizer);
            }
        } catch (RuntimeException e) {
            throw new IOException("Failed to create thumbnail", e);
        }

        // Only poke around for files on external storage
        if (MEDIA_UNKNOWN.equals(Environment.getExternalStorageState(file))) {
            throw new IOException("No embedded album art found");
        }

        // Ignore "Downloads" or top-level directories
        final File parent = file.getParentFile();
        final File grandParent = parent != null ? parent.getParentFile() : null;
        if (parent != null
                && parent.getName().equals(Environment.DIRECTORY_DOWNLOADS)) {
            throw new IOException("No thumbnails in Downloads directories");
        }
        if (grandParent != null
                && MEDIA_UNKNOWN.equals(Environment.getExternalStorageState(grandParent))) {
            throw new IOException("No thumbnails in top-level directories");
        }

        // If no embedded image found, look around for best standalone file
        final File[] found = ArrayUtils
                .defeatNullable(file.getParentFile().listFiles((dir, name) -> {
                    final String lower = name.toLowerCase();
                    return (lower.endsWith(".jpg") || lower.endsWith(".png"));
                }));

        final ToIntFunction<File> score = (f) -> {
            final String lower = f.getName().toLowerCase();
            if (lower.equals("albumart.jpg")) return 4;
            if (lower.startsWith("albumart") && lower.endsWith(".jpg")) return 3;
            if (lower.contains("albumart") && lower.endsWith(".jpg")) return 2;
            if (lower.endsWith(".jpg")) return 1;
            return 0;
        };
        final Comparator<File> bestScore = (a, b) -> {
            return score.applyAsInt(a) - score.applyAsInt(b);
        };

        final File bestFile = Arrays.asList(found).stream().max(bestScore).orElse(null);
        if (bestFile == null) {
            throw new IOException("No album art found");
        }

        // Checkpoint before going deeper
        if (signal != null) signal.throwIfCanceled();

        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(bestFile), resizer);
    }

    /**
     * Create a thumbnail for given image file.
     *
     * @param filePath The image file.
     * @param kind The desired thumbnail kind, such as
     *            {@link android.provider.MediaStore.Images.Thumbnails#MINI_KIND}.
     * @deprecated Callers should migrate to using
     *             {@link #createImageThumbnail(File, Size, CancellationSignal)},
     *             as it offers more control over resizing and cancellation.
     */
    @Deprecated
    public static @Nullable Bitmap createImageThumbnail(@NonNull String filePath, int kind) {
        try {
            return createImageThumbnail(new File(filePath), convertKind(kind), null);
        } catch (IOException e) {
            Log.w(TAG, e);
            return null;
        }
    }

    /**
     * Create a thumbnail for given image file.
     *
     * @param file The audio file.
     * @param size The desired thumbnail size.
     * @throws IOException If any trouble was encountered while generating or
     *             loading the thumbnail, or if
     *             {@link CancellationSignal#cancel()} was invoked.
     */
    public static @NonNull Bitmap createImageThumbnail(@NonNull File file, @NonNull Size size,
            @Nullable CancellationSignal signal) throws IOException {
        // Checkpoint before going deeper
        if (signal != null) signal.throwIfCanceled();

        final Resizer resizer = new Resizer(size, signal);
        final String mimeType = MediaFile.getMimeTypeForFile(file.getName());
        Bitmap bitmap = null;
        ExifInterface exif = null;
        int orientation = 0;

        // get orientation
        if (MediaFile.isExifMimeType(mimeType)) {
            exif = new ExifInterface(file);
            switch (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    orientation = 90;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    orientation = 180;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    orientation = 270;
                    break;
            }
        }

        if (mimeType.equals("image/heif")
                || mimeType.equals("image/heif-sequence")
                || mimeType.equals("image/heic")
                || mimeType.equals("image/heic-sequence")) {
            try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
                retriever.setDataSource(file.getAbsolutePath());
                bitmap = retriever.getThumbnailImageAtIndex(-1,
                        new MediaMetadataRetriever.BitmapParams(), size.getWidth(),
                        size.getWidth() * size.getHeight());
            } catch (RuntimeException e) {
                throw new IOException("Failed to create thumbnail", e);
            }
        }

        if (bitmap == null && exif != null) {
            final byte[] raw = exif.getThumbnailBytes();
            if (raw != null) {
                try {
                    bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(raw), resizer);
                } catch (ImageDecoder.DecodeException e) {
                    Log.w(TAG, e);
                }
            }
        }

        // Checkpoint before going deeper
        if (signal != null) signal.throwIfCanceled();

        if (bitmap == null) {
            bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file), resizer);
            // Use ImageDecoder to do full file decoding, we don't need to handle the orientation
            return bitmap;
        }

        // Transform the bitmap if the orientation of the image is not 0.
        if (orientation != 0 && bitmap != null) {
            final int width = bitmap.getWidth();
            final int height = bitmap.getHeight();

            final Matrix m = new Matrix();
            m.setRotate(orientation, width / 2, height / 2);
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height, m, false);
        }

        return bitmap;
    }

    /**
     * Create a thumbnail for given video file.
     *
     * @param filePath The video file.
     * @param kind The desired thumbnail kind, such as
     *            {@link android.provider.MediaStore.Images.Thumbnails#MINI_KIND}.
     * @deprecated Callers should migrate to using
     *             {@link #createVideoThumbnail(File, Size, CancellationSignal)},
     *             as it offers more control over resizing and cancellation.
     */
    @Deprecated
    public static @Nullable Bitmap createVideoThumbnail(@NonNull String filePath, int kind) {
        try {
            return createVideoThumbnail(new File(filePath), convertKind(kind), null);
        } catch (IOException e) {
            Log.w(TAG, e);
            return null;
        }
    }

    /**
     * Create a thumbnail for given video file.
     *
     * @param file The video file.
     * @param size The desired thumbnail size.
     * @throws IOException If any trouble was encountered while generating or
     *             loading the thumbnail, or if
     *             {@link CancellationSignal#cancel()} was invoked.
     */
    public static @NonNull Bitmap createVideoThumbnail(@NonNull File file, @NonNull Size size,
            @Nullable CancellationSignal signal) throws IOException {
        // Checkpoint before going deeper
        if (signal != null) signal.throwIfCanceled();

        final Resizer resizer = new Resizer(size, signal);
        try (MediaMetadataRetriever mmr = new MediaMetadataRetriever()) {
            mmr.setDataSource(file.getAbsolutePath());

            // Try to retrieve thumbnail from metadata
            final byte[] raw = mmr.getEmbeddedPicture();
            if (raw != null) {
                return ImageDecoder.decodeBitmap(ImageDecoder.createSource(raw), resizer);
            }

            // Fall back to middle of video
            final int width = Integer.parseInt(mmr.extractMetadata(METADATA_KEY_VIDEO_WIDTH));
            final int height = Integer.parseInt(mmr.extractMetadata(METADATA_KEY_VIDEO_HEIGHT));
            final long duration = Long.parseLong(mmr.extractMetadata(METADATA_KEY_DURATION));

            // If we're okay with something larger than native format, just
            // return a frame without up-scaling it
            if (size.getWidth() > width && size.getHeight() > height) {
                return Objects.requireNonNull(
                        mmr.getFrameAtTime(duration / 2, OPTION_CLOSEST_SYNC));
            } else {
                return Objects.requireNonNull(
                        mmr.getScaledFrameAtTime(duration / 2, OPTION_CLOSEST_SYNC,
                        size.getWidth(), size.getHeight()));
            }
        } catch (RuntimeException e) {
            throw new IOException("Failed to create thumbnail", e);
        }
    }

    /**
     * Creates a centered bitmap of the desired size.
     *
     * @param source original bitmap source
     * @param width targeted width
     * @param height targeted height
     */
    public static Bitmap extractThumbnail(
            Bitmap source, int width, int height) {
        return extractThumbnail(source, width, height, OPTIONS_NONE);
    }

    /**
     * Creates a centered bitmap of the desired size.
     *
     * @param source original bitmap source
     * @param width targeted width
     * @param height targeted height
     * @param options options used during thumbnail extraction
     */
    public static Bitmap extractThumbnail(
            Bitmap source, int width, int height, int options) {
        if (source == null) {
            return null;
        }

        float scale;
        if (source.getWidth() < source.getHeight()) {
            scale = width / (float) source.getWidth();
        } else {
            scale = height / (float) source.getHeight();
        }
        Matrix matrix = new Matrix();
        matrix.setScale(scale, scale);
        Bitmap thumbnail = transform(matrix, source, width, height,
                OPTIONS_SCALE_UP | options);
        return thumbnail;
    }

    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private static int computeSampleSize(BitmapFactory.Options options,
            int minSideLength, int maxNumOfPixels) {
        return 1;
    }

    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private static int computeInitialSampleSize(BitmapFactory.Options options,
            int minSideLength, int maxNumOfPixels) {
        return 1;
    }

    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private static void closeSilently(ParcelFileDescriptor c) {
        IoUtils.closeQuietly(c);
    }

    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private static ParcelFileDescriptor makeInputStream(
            Uri uri, ContentResolver cr) {
        try {
            return cr.openFileDescriptor(uri, "r");
        } catch (IOException ex) {
            return null;
        }
    }

    /**
     * Transform source Bitmap to targeted width and height.
     */
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private static Bitmap transform(Matrix scaler,
            Bitmap source,
            int targetWidth,
            int targetHeight,
            int options) {
        boolean scaleUp = (options & OPTIONS_SCALE_UP) != 0;
        boolean recycle = (options & OPTIONS_RECYCLE_INPUT) != 0;

        int deltaX = source.getWidth() - targetWidth;
        int deltaY = source.getHeight() - targetHeight;
        if (!scaleUp && (deltaX < 0 || deltaY < 0)) {
            /*
            * In this case the bitmap is smaller, at least in one dimension,
            * than the target.  Transform it by placing as much of the image
            * as possible into the target and leaving the top/bottom or
            * left/right (or both) black.
            */
            Bitmap b2 = Bitmap.createBitmap(targetWidth, targetHeight,
            Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b2);

            int deltaXHalf = Math.max(0, deltaX / 2);
            int deltaYHalf = Math.max(0, deltaY / 2);
            Rect src = new Rect(
            deltaXHalf,
            deltaYHalf,
            deltaXHalf + Math.min(targetWidth, source.getWidth()),
            deltaYHalf + Math.min(targetHeight, source.getHeight()));
            int dstX = (targetWidth  - src.width())  / 2;
            int dstY = (targetHeight - src.height()) / 2;
            Rect dst = new Rect(
                    dstX,
                    dstY,
                    targetWidth - dstX,
                    targetHeight - dstY);
            c.drawBitmap(source, src, dst, null);
            if (recycle) {
                source.recycle();
            }
            c.setBitmap(null);
            return b2;
        }
        float bitmapWidthF = source.getWidth();
        float bitmapHeightF = source.getHeight();

        float bitmapAspect = bitmapWidthF / bitmapHeightF;
        float viewAspect   = (float) targetWidth / targetHeight;

        if (bitmapAspect > viewAspect) {
            float scale = targetHeight / bitmapHeightF;
            if (scale < .9F || scale > 1F) {
                scaler.setScale(scale, scale);
            } else {
                scaler = null;
            }
        } else {
            float scale = targetWidth / bitmapWidthF;
            if (scale < .9F || scale > 1F) {
                scaler.setScale(scale, scale);
            } else {
                scaler = null;
            }
        }

        Bitmap b1;
        if (scaler != null) {
            // this is used for minithumb and crop, so we want to filter here.
            b1 = Bitmap.createBitmap(source, 0, 0,
            source.getWidth(), source.getHeight(), scaler, true);
        } else {
            b1 = source;
        }

        if (recycle && b1 != source) {
            source.recycle();
        }

        int dx1 = Math.max(0, b1.getWidth() - targetWidth);
        int dy1 = Math.max(0, b1.getHeight() - targetHeight);

        Bitmap b2 = Bitmap.createBitmap(
                b1,
                dx1 / 2,
                dy1 / 2,
                targetWidth,
                targetHeight);

        if (b2 != b1) {
            if (recycle || b1 != source) {
                b1.recycle();
            }
        }

        return b2;
    }

    @Deprecated
    private static class SizedThumbnailBitmap {
        public byte[] mThumbnailData;
        public Bitmap mBitmap;
        public int mThumbnailWidth;
        public int mThumbnailHeight;
    }

    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private static void createThumbnailFromEXIF(String filePath, int targetSize,
            int maxPixels, SizedThumbnailBitmap sizedThumbBitmap) {
    }
}

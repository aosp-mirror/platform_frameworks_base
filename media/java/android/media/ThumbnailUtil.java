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

import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.BaseColumns;
import android.provider.MediaStore.Images;
import android.provider.MediaStore.Images.Thumbnails;
import android.util.Log;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.media.MediaMetadataRetriever;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Thumbnail generation routines for media provider. This class should only be used internaly.
 * {@hide} THIS IS NOT FOR PUBLIC API.
 */

public class ThumbnailUtil {
    private static final String TAG = "ThumbnailUtil";
    //Whether we should recycle the input (unless the output is the input).
    public static final boolean RECYCLE_INPUT = true;
    public static final boolean NO_RECYCLE_INPUT = false;
    public static final boolean ROTATE_AS_NEEDED = true;
    public static final boolean NO_ROTATE = false;
    public static final boolean USE_NATIVE = true;
    public static final boolean NO_NATIVE = false;

    public static final int THUMBNAIL_TARGET_SIZE = 320;
    public static final int MINI_THUMB_TARGET_SIZE = 96;
    public static final int THUMBNAIL_MAX_NUM_PIXELS = 512 * 384;
    public static final int MINI_THUMB_MAX_NUM_PIXELS = 128 * 128;
    public static final int UNCONSTRAINED = -1;

    // Returns Options that set the native alloc flag for Bitmap decode.
    public static BitmapFactory.Options createNativeAllocOptions() {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inNativeAlloc = true;
        return options;
    }
    /**
     * Make a bitmap from a given Uri.
     *
     * @param uri
     */
    public static Bitmap makeBitmap(int minSideLength, int maxNumOfPixels,
            Uri uri, ContentResolver cr) {
        return makeBitmap(minSideLength, maxNumOfPixels, uri, cr,
                NO_NATIVE);
    }

    /*
     * Compute the sample size as a function of minSideLength
     * and maxNumOfPixels.
     * minSideLength is used to specify that minimal width or height of a
     * bitmap.
     * maxNumOfPixels is used to specify the maximal size in pixels that is
     * tolerable in terms of memory usage.
     *
     * The function returns a sample size based on the constraints.
     * Both size and minSideLength can be passed in as IImage.UNCONSTRAINED,
     * which indicates no care of the corresponding constraint.
     * The functions prefers returning a sample size that
     * generates a smaller bitmap, unless minSideLength = IImage.UNCONSTRAINED.
     *
     * Also, the function rounds up the sample size to a power of 2 or multiple
     * of 8 because BitmapFactory only honors sample size this way.
     * For example, BitmapFactory downsamples an image by 2 even though the
     * request is 3. So we round up the sample size to avoid OOM.
     */
    public static int computeSampleSize(BitmapFactory.Options options,
            int minSideLength, int maxNumOfPixels) {
        int initialSize = computeInitialSampleSize(options, minSideLength,
                maxNumOfPixels);

        int roundedSize;
        if (initialSize <= 8 ) {
            roundedSize = 1;
            while (roundedSize < initialSize) {
                roundedSize <<= 1;
            }
        } else {
            roundedSize = (initialSize + 7) / 8 * 8;
        }

        return roundedSize;
    }

    private static int computeInitialSampleSize(BitmapFactory.Options options,
            int minSideLength, int maxNumOfPixels) {
        double w = options.outWidth;
        double h = options.outHeight;

        int lowerBound = (maxNumOfPixels == UNCONSTRAINED) ? 1 :
                (int) Math.ceil(Math.sqrt(w * h / maxNumOfPixels));
        int upperBound = (minSideLength == UNCONSTRAINED) ? 128 :
                (int) Math.min(Math.floor(w / minSideLength),
                Math.floor(h / minSideLength));

        if (upperBound < lowerBound) {
            // return the larger one when there is no overlapping zone.
            return lowerBound;
        }

        if ((maxNumOfPixels == UNCONSTRAINED) &&
                (minSideLength == UNCONSTRAINED)) {
            return 1;
        } else if (minSideLength == UNCONSTRAINED) {
            return lowerBound;
        } else {
            return upperBound;
        }
    }

    public static Bitmap makeBitmap(int minSideLength, int maxNumOfPixels,
            Uri uri, ContentResolver cr, boolean useNative) {
        ParcelFileDescriptor input = null;
        try {
            input = cr.openFileDescriptor(uri, "r");
            BitmapFactory.Options options = null;
            if (useNative) {
                options = createNativeAllocOptions();
            }
            return makeBitmap(minSideLength, maxNumOfPixels, uri, cr, input,
                    options);
        } catch (IOException ex) {
            Log.e(TAG, "", ex);
            return null;
        } finally {
            closeSilently(input);
        }
    }

    // Rotates the bitmap by the specified degree.
    // If a new bitmap is created, the original bitmap is recycled.
    public static Bitmap rotate(Bitmap b, int degrees) {
        if (degrees != 0 && b != null) {
            Matrix m = new Matrix();
            m.setRotate(degrees,
                    (float) b.getWidth() / 2, (float) b.getHeight() / 2);
            try {
                Bitmap b2 = Bitmap.createBitmap(
                        b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                if (b != b2) {
                    b.recycle();
                    b = b2;
                }
            } catch (OutOfMemoryError ex) {
                // We have no memory to rotate. Return the original bitmap.
            }
        }
        return b;
    }

    private static void closeSilently(ParcelFileDescriptor c) {
      if (c == null) return;
      try {
          c.close();
      } catch (Throwable t) {
          // do nothing
      }
    }

    private static ParcelFileDescriptor makeInputStream(
            Uri uri, ContentResolver cr) {
        try {
            return cr.openFileDescriptor(uri, "r");
        } catch (IOException ex) {
            return null;
        }
    }

    public static Bitmap makeBitmap(int minSideLength, int maxNumOfPixels,
        Uri uri, ContentResolver cr, ParcelFileDescriptor pfd,
        BitmapFactory.Options options) {
        Bitmap b = null;
        try {
            if (pfd == null) pfd = makeInputStream(uri, cr);
            if (pfd == null) return null;
            if (options == null) options = new BitmapFactory.Options();

            FileDescriptor fd = pfd.getFileDescriptor();
            options.inSampleSize = 1;
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFileDescriptor(fd, null, options);
            if (options.mCancel || options.outWidth == -1
                    || options.outHeight == -1) {
                return null;
            }
            options.inSampleSize = computeSampleSize(
                    options, minSideLength, maxNumOfPixels);
            options.inJustDecodeBounds = false;

            options.inDither = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            b = BitmapFactory.decodeFileDescriptor(fd, null, options);
        } catch (OutOfMemoryError ex) {
            Log.e(TAG, "Got oom exception ", ex);
            return null;
        } finally {
            closeSilently(pfd);
        }
        return b;
    }

    /**
     * Creates a centered bitmap of the desired size.
     * @param source
     * @param recycle whether we want to recycle the input
     */
    public static Bitmap extractMiniThumb(
            Bitmap source, int width, int height, boolean recycle) {
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
        Bitmap miniThumbnail = transform(matrix, source, width, height, true, recycle);
        return miniThumbnail;
    }

    /**
     * Create a video thumbnail for a video. May return null if the video is
     * corrupt.
     *
     * @param filePath
     */
    public static Bitmap createVideoThumbnail(String filePath) {
        Bitmap bitmap = null;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setMode(MediaMetadataRetriever.MODE_CAPTURE_FRAME_ONLY);
            retriever.setDataSource(filePath);
            bitmap = retriever.captureFrame();
        } catch (IllegalArgumentException ex) {
            // Assume this is a corrupt video file
        } catch (RuntimeException ex) {
            // Assume this is a corrupt video file.
        } finally {
            try {
                retriever.release();
            } catch (RuntimeException ex) {
                // Ignore failures while cleaning up.
            }
        }
        return bitmap;
    }

    /**
     * This method first examines if the thumbnail embedded in EXIF is bigger than our target
     * size. If not, then it'll create a thumbnail from original image. Due to efficiency
     * consideration, we want to let MediaThumbRequest avoid calling this method twice for
     * both kinds, so it only requests for MICRO_KIND and set saveImage to true.
     *
     * This method always returns a "square thumbnail" for MICRO_KIND thumbnail.
     *
     * @param cr ContentResolver
     * @param filePath file path needed by EXIF interface
     * @param uri URI of original image
     * @param origId image id
     * @param kind either MINI_KIND or MICRO_KIND
     * @param saveImage Whether to save MINI_KIND thumbnail obtained in this method.
     * @return Bitmap
     */
    public static Bitmap createImageThumbnail(ContentResolver cr, String filePath, Uri uri,
            long origId, int kind, boolean saveMini) {
        boolean wantMini = (kind == Images.Thumbnails.MINI_KIND || saveMini);
        int targetSize = wantMini ?
                ThumbnailUtil.THUMBNAIL_TARGET_SIZE : ThumbnailUtil.MINI_THUMB_TARGET_SIZE;
        int maxPixels = wantMini ?
                ThumbnailUtil.THUMBNAIL_MAX_NUM_PIXELS : ThumbnailUtil.MINI_THUMB_MAX_NUM_PIXELS;
        byte[] thumbData = createThumbnailFromEXIF(filePath, targetSize);
        Bitmap bitmap = null;

        if (thumbData != null) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = computeSampleSize(options, targetSize, maxPixels);
            options.inDither = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inJustDecodeBounds = false;
            bitmap = BitmapFactory.decodeByteArray(thumbData, 0, thumbData.length, options);
        }

        if (bitmap == null) {
            bitmap = ThumbnailUtil.makeBitmap(targetSize, maxPixels, uri, cr);
        }

        if (bitmap == null) {
            return null;
        }

        if (saveMini) {
            if (thumbData != null) {
                ThumbnailUtil.storeThumbnail(cr, origId, thumbData, bitmap.getWidth(),
                        bitmap.getHeight());
            } else {
                ThumbnailUtil.storeThumbnail(cr, origId, bitmap);
            }
        }

        if (kind == Images.Thumbnails.MICRO_KIND) {
            // now we make it a "square thumbnail" for MICRO_KIND thumbnail
            bitmap = ThumbnailUtil.extractMiniThumb(bitmap,
                    ThumbnailUtil.MINI_THUMB_TARGET_SIZE,
                    ThumbnailUtil.MINI_THUMB_TARGET_SIZE, ThumbnailUtil.RECYCLE_INPUT);
        }
        return bitmap;
    }

    public static Bitmap transform(Matrix scaler,
            Bitmap source,
            int targetWidth,
            int targetHeight,
            boolean scaleUp,
            boolean recycle) {

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

    private static final String[] THUMB_PROJECTION = new String[] {
        BaseColumns._ID // 0
    };

    /**
     * Look up thumbnail uri by given imageId, it will be automatically created if it's not created
     * yet. Most of the time imageId is identical to thumbId, but it's not always true.
     * @param req
     * @param width
     * @param height
     * @return Uri Thumbnail uri
     */
    private static Uri getImageThumbnailUri(ContentResolver cr, long origId, int width, int height) {
        Uri thumbUri = Images.Thumbnails.EXTERNAL_CONTENT_URI;
        Cursor c = cr.query(thumbUri, THUMB_PROJECTION,
              Thumbnails.IMAGE_ID + "=?",
              new String[]{String.valueOf(origId)}, null);
        try {
            if (c.moveToNext()) {
                return ContentUris.withAppendedId(thumbUri, c.getLong(0));
            }
        } finally {
            if (c != null) c.close();
        }

        ContentValues values = new ContentValues(4);
        values.put(Thumbnails.KIND, Thumbnails.MINI_KIND);
        values.put(Thumbnails.IMAGE_ID, origId);
        values.put(Thumbnails.HEIGHT, height);
        values.put(Thumbnails.WIDTH, width);
        try {
            return cr.insert(thumbUri, values);
        } catch (Exception ex) {
            Log.w(TAG, ex);
            return null;
        }
    }

    /**
     * Store a given thumbnail in the database. (Bitmap)
     */
    private static boolean storeThumbnail(ContentResolver cr, long origId, Bitmap thumb) {
        if (thumb == null) return false;
        try {
            Uri uri = getImageThumbnailUri(cr, origId, thumb.getWidth(), thumb.getHeight());
            OutputStream thumbOut = cr.openOutputStream(uri);
            thumb.compress(Bitmap.CompressFormat.JPEG, 85, thumbOut);
            thumbOut.close();
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Unable to store thumbnail", t);
            return false;
        }
    }

    /**
     * Store a given thumbnail in the database. (byte array)
     */
    private static boolean storeThumbnail(ContentResolver cr, long origId, byte[] jpegThumbnail,
            int width, int height) {
        if (jpegThumbnail == null) return false;

        Uri uri = getImageThumbnailUri(cr, origId, width, height);
        if (uri == null) {
            return false;
        }
        try {
            OutputStream thumbOut = cr.openOutputStream(uri);
            thumbOut.write(jpegThumbnail);
            thumbOut.close();
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Unable to store thumbnail", t);
            return false;
        }
    }

    // Extract thumbnail in image that meets the targetSize criteria.
    static byte[] createThumbnailFromEXIF(String filePath, int targetSize) {
        if (filePath == null) return null;

        try {
            ExifInterface exif = new ExifInterface(filePath);
            if (exif == null) return null;
            byte [] thumbData = exif.getThumbnail();
            if (thumbData == null) return null;
            // Sniff the size of the EXIF thumbnail before decoding it. Photos
            // from the device will pass, but images that are side loaded from
            // other cameras may not.
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(thumbData, 0, thumbData.length, options);

            int width = options.outWidth;
            int height = options.outHeight;

            if (width >= targetSize && height >= targetSize) {
                return thumbData;
            }
        } catch (IOException ex) {
            Log.w(TAG, ex);
        }
        return null;
    }
}

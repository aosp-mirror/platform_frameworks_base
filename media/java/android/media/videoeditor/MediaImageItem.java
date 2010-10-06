/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.media.videoeditor;

import java.io.IOException;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.Log;
import android.util.Pair;

/**
 * This class represents an image item on the storyboard. Note that images are
 * scaled down to the maximum supported resolution by preserving the native
 * aspect ratio. To learn the scaled image dimensions use
 * {@link #getScaledWidth()} and {@link #getScaledHeight()} respectively.
 *
 * {@hide}
 */
public class MediaImageItem extends MediaItem {
    // Logging
    private static final String TAG = "MediaImageItem";

    // The resize paint
    private static final Paint sResizePaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    // Instance variables
    private final int mWidth;
    private final int mHeight;
    private final int mAspectRatio;
    private long mDurationMs;
    private int mScaledWidth, mScaledHeight;

    /**
     * This class cannot be instantiated by using the default constructor
     */
    @SuppressWarnings("unused")
    private MediaImageItem() throws IOException {
        this(null, null, 0, RENDERING_MODE_BLACK_BORDER);
    }

    /**
     * Constructor
     *
     * @param mediaItemId The media item id
     * @param filename The image file name
     * @param durationMs The duration of the image on the storyboard
     * @param renderingMode The rendering mode
     *
     * @throws IOException
     */
    public MediaImageItem(String mediaItemId, String filename, long durationMs, int renderingMode)
            throws IOException {
        super(mediaItemId, filename, renderingMode);

        // Determine the dimensions of the image
        final BitmapFactory.Options dbo = new BitmapFactory.Options();
        dbo.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(filename, dbo);

        mWidth = dbo.outWidth;
        mHeight = dbo.outHeight;
        mDurationMs = durationMs;

        // TODO: Determine the aspect ratio from the width and height
        mAspectRatio = MediaProperties.ASPECT_RATIO_4_3;

        // Images are stored in memory scaled to the maximum resolution to
        // save memory.
        final Pair<Integer, Integer>[] resolutions =
            MediaProperties.getSupportedResolutions(mAspectRatio);
        // Get the highest resolution
        final Pair<Integer, Integer> maxResolution = resolutions[resolutions.length - 1];
        if (mHeight > maxResolution.second) {
            // We need to scale the image
            scaleImage(filename, maxResolution.first, maxResolution.second);
            mScaledWidth = maxResolution.first;
            mScaledHeight = maxResolution.second;
        } else {
            mScaledWidth = mWidth;
            mScaledHeight = mHeight;
        }
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public int getFileType() {
        if (mFilename.endsWith(".jpg") || mFilename.endsWith(".jpeg")) {
            return MediaProperties.FILE_JPEG;
        } else if (mFilename.endsWith(".png")) {
            return MediaProperties.FILE_PNG;
        } else {
            return MediaProperties.FILE_UNSUPPORTED;
        }
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public int getWidth() {
        return mWidth;
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public int getHeight() {
        return mHeight;
    }

    /**
     * @return The scaled width of the image.
     */
    public int getScaledWidth() {
        return mScaledWidth;
    }

    /**
     * @return The scaled height of the image.
     */
    public int getScaledHeight() {
        return mScaledHeight;
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public int getAspectRatio() {
        return mAspectRatio;
    }

    /**
     * This method will adjust the duration of bounding transitions, effects
     * and overlays if the current duration of the transactions become greater
     * than the maximum allowable duration.
     *
     * @param durationMs The duration of the image in the storyboard timeline
     */
    public void setDuration(long durationMs) {
        mDurationMs = durationMs;

        adjustElementsDuration();
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public long getTimelineDuration() {
        return mDurationMs;
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public Bitmap getThumbnail(int width, int height, long timeMs) throws IOException {
        return scaleImage(mFilename, width, height);
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public Bitmap[] getThumbnailList(int width, int height, long startMs, long endMs,
            int thumbnailCount) throws IOException {
        final Bitmap thumbnail = scaleImage(mFilename, width, height);
        final Bitmap[] thumbnailArray = new Bitmap[thumbnailCount];
        for (int i = 0; i < thumbnailCount; i++) {
            thumbnailArray[i] = thumbnail;
        }
        return thumbnailArray;
    }

    /**
     * Resize a bitmap to the specified width and height
     *
     * @param filename The filename
     * @param width The thumbnail width
     * @param height The thumbnail height
     *
     * @return The resized bitmap
     */
    private Bitmap scaleImage(String filename, int width, int height)
            throws IOException {
        final BitmapFactory.Options dbo = new BitmapFactory.Options();
        dbo.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(filename, dbo);

        final int nativeWidth = dbo.outWidth;
        final int nativeHeight = dbo.outHeight;
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "generateThumbnail: Input: " + nativeWidth + "x" + nativeHeight
                    + ", resize to: " + width + "x" + height);
        }

        final Bitmap srcBitmap;
        float bitmapWidth, bitmapHeight;
        if (nativeWidth > width || nativeHeight > height) {
            float dx = ((float)nativeWidth) / ((float)width);
            float dy = ((float)nativeHeight) / ((float)height);
            if (dx > dy) {
                bitmapWidth = width;
                bitmapHeight = nativeHeight / dx;
            } else {
                bitmapWidth = nativeWidth / dy;
                bitmapHeight = height;
            }
            // Create the bitmap from file
            if (nativeWidth / bitmapWidth > 1) {
                final BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = nativeWidth / (int)bitmapWidth;
                srcBitmap = BitmapFactory.decodeFile(filename, options);
            } else {
                srcBitmap = BitmapFactory.decodeFile(filename);
            }
        } else {
            bitmapWidth = width;
            bitmapHeight = height;
            srcBitmap = BitmapFactory.decodeFile(filename);
        }

        if (srcBitmap == null) {
            Log.e(TAG, "generateThumbnail: Cannot decode image bytes");
            throw new IOException("Cannot decode file: " + mFilename);
        }

        // Create the canvas bitmap
        final Bitmap bitmap = Bitmap.createBitmap((int)bitmapWidth, (int)bitmapHeight,
                Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        canvas.drawBitmap(srcBitmap, new Rect(0, 0, srcBitmap.getWidth(), srcBitmap.getHeight()),
                new Rect(0, 0, (int)bitmapWidth, (int)bitmapHeight), sResizePaint);
        // Release the source bitmap
        srcBitmap.recycle();
        return bitmap;
    }
}

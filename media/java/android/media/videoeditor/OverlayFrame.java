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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Bitmap.CompressFormat;

import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * This class is used to overlay an image on top of a media item.
 * {@hide}
 */
public class OverlayFrame extends Overlay {
    /**
     *  Instance variables
     */
    private Bitmap mBitmap;
    private String mFilename;
    private String mBitmapFileName;

    private int mOFWidth;
    private int mOFHeight;

    /**
     * An object of this type cannot be instantiated by using the default
     * constructor
     */
    @SuppressWarnings("unused")
    private OverlayFrame() {
        this(null, null, (String)null, 0, 0);
    }

    /**
     * Constructor for an OverlayFrame
     *
     * @param mediaItem The media item owner
     * @param overlayId The overlay id
     * @param bitmap The bitmap to be used as an overlay. The size of the
     *      bitmap must equal to the size of the media item to which it is
     *      added. The bitmap is typically a decoded PNG file.
     * @param startTimeMs The overlay start time in milliseconds
     * @param durationMs The overlay duration in milliseconds
     *
     * @throws IllegalArgumentException if the file type is not PNG or the
     *      startTimeMs and durationMs are incorrect.
     */
    public OverlayFrame(MediaItem mediaItem, String overlayId, Bitmap bitmap,
                        long startTimeMs,long durationMs) {
        super(mediaItem, overlayId, startTimeMs, durationMs);
        mBitmap = bitmap;
        mFilename = null;
        mBitmapFileName = null;
    }

    /**
     * Constructor for an OverlayFrame. This constructor can be used to
     * restore the overlay after it was saved internally by the video editor.
     *
     * @param mediaItem The media item owner
     * @param overlayId The overlay id
     * @param filename The file name that contains the overlay.
     * @param startTimeMs The overlay start time in milliseconds
     * @param durationMs The overlay duration in milliseconds
     *
     * @throws IllegalArgumentException if the file type is not PNG or the
     *      startTimeMs and durationMs are incorrect.
     */
    OverlayFrame(MediaItem mediaItem, String overlayId, String filename,
                 long startTimeMs,long durationMs) {
        super(mediaItem, overlayId, startTimeMs, durationMs);
        mBitmapFileName = filename;
        mBitmap = BitmapFactory.decodeFile(mBitmapFileName);
        mFilename = null;
    }

    /**
     * Get the overlay bitmap.
     *
     * @return Get the overlay bitmap
     */
    public Bitmap getBitmap() {
        return mBitmap;
    }

    /**
     * Get the overlay bitmap.
     *
     * @return Get the overlay bitmap as png file.
     */
    String getBitmapImageFileName() {
        return mBitmapFileName;
    }
    /**
     * Set the overlay bitmap.
     *
     * @param bitmap The overlay bitmap.
     */
    public void setBitmap(Bitmap bitmap) {
        getMediaItem().getNativeContext().setGeneratePreview(true);

        invalidate();

        mBitmap = bitmap;
        if (mFilename != null) {
            /**
             *  Delete the file
             */
            new File(mFilename).delete();
            /**
             *  Invalidate the filename
             */
            mFilename = null;
        }

        /**
         *  Invalidate the transitions if necessary
         */
        getMediaItem().invalidateTransitions(mStartTimeMs, mDurationMs);
    }

    /**
     * Get the file name of this overlay
     */
    String getFilename() {
        return mFilename;
    }

    /*
     * Set the file name of this overlay
     */
    void setFilename(String filename) {
        mFilename = filename;
    }
    /**
     * Save the overlay to the project folder
     *
     * @param path The path where the overlay will be saved
     *
     * @return The filename
     * @throws FileNotFoundException if the bitmap cannot be saved
     * @throws IOException if the bitmap file cannot be saved
     */
    String save(String path) throws FileNotFoundException, IOException {
        if (mFilename != null) {
            return mFilename;
        }

        // Create the compressed PNG file
        mBitmapFileName = path + "/" + "Overlay" + getId() + ".png";
        if (!(new File(mBitmapFileName).exists())) {
            final FileOutputStream out = new FileOutputStream (mBitmapFileName);
            mBitmap.compress(CompressFormat.PNG, 100, out);
            out.flush();
            out.close();
        }

        mOFWidth = mBitmap.getWidth();
        mOFHeight = mBitmap.getHeight();

        mFilename = path + "/" + "Overlay" + getId() + ".rgb";
        if (!(new File(mFilename).exists())) {
            /**
             * Save the image to a file ; as a rgb
             */
            final FileOutputStream fl = new FileOutputStream(mFilename);
            final DataOutputStream dos = new DataOutputStream(fl);

            /**
             * populate the rgb file with bitmap data
             */
            final int [] framingBuffer = new int[mOFWidth];
            ByteBuffer byteBuffer = ByteBuffer.allocate(framingBuffer.length * 4);
            IntBuffer intBuffer;

            byte[] array = byteBuffer.array();
            int tmp = 0;
            while(tmp < mOFHeight) {
                mBitmap.getPixels(framingBuffer,0,mOFWidth,0,tmp,mOFWidth,1);
                intBuffer = byteBuffer.asIntBuffer();
                intBuffer.put(framingBuffer,0,mOFWidth);
                dos.write(array);
                tmp += 1;
            }
            fl.flush();
            fl.close();
        }
        return mFilename;
    }

    /**
     * Get the OverlayFrame Height
     */
     int getOverlayFrameHeight() {
         return mOFHeight;
     }

     /**
     * Get the OverlayFrame Width
     */
     int getOverlayFrameWidth() {
         return mOFWidth;
     }

    /*
     * Set the OverlayFrame Height
     */
     void setOverlayFrameHeight(int height) {
         mOFHeight = height;
     }

    /*
     * Set the OverlayFrame Width
     */
     void setOverlayFrameWidth(int width) {
         mOFWidth = width;
     }
    /**
     * Delete the overlay files
     */
    void invalidate() {
        if (mBitmap != null) {
            mBitmap.recycle();
            mBitmap = null;
        }

        if (mFilename != null) {
            new File(mFilename).delete();
            mFilename = null;
        }

        if (mBitmapFileName != null) {
            new File(mBitmapFileName).delete();
            mBitmapFileName = null;
        }
    }
}

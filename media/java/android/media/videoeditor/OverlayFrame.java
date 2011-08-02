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
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Bitmap.CompressFormat;
import android.util.Pair;


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
     * resized RGB Image dimensions
     */
    private int mResizedRGBWidth;
    private int mResizedRGBHeight;

    /**
     *  The resize paint
     */
    private static final Paint sResizePaint = new Paint(Paint.FILTER_BITMAP_FLAG);

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
        mResizedRGBWidth = 0;
        mResizedRGBHeight = 0;
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
        mResizedRGBWidth = 0;
        mResizedRGBHeight = 0;
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

        /* resize and save rgb as per project aspect ratio */
        MediaArtistNativeHelper nativeHelper = (super.getMediaItem()).getNativeContext();

        /* get height and width for story board aspect ratio */
        final Pair<Integer, Integer> maxResolution;
        final Pair<Integer, Integer>[] resolutions;
        resolutions = MediaProperties.getSupportedResolutions(nativeHelper.nativeHelperGetAspectRatio());

        // Get the highest resolution
        maxResolution = resolutions[resolutions.length - 1];

        /* Generate the rgb file with rendering mode */
        generateOverlayWithRenderingMode (super.getMediaItem(), this,
                maxResolution.second /* max Height */ ,
                maxResolution.first /* max Width */);

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

    /*
     * Set the resized RGB widht and height
     */
     void setResizedRGBSize(int width, int height) {
        mResizedRGBWidth = width;
        mResizedRGBHeight = height;
     }

    /*
     * Get the resized RGB Height
     */
     int getResizedRGBSizeHeight() {
         return mResizedRGBHeight;
     }

    /*
     * Get the resized RGB Width
     */
     int getResizedRGBSizeWidth() {
         return mResizedRGBWidth;
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

     /**
     * Delete the overlay related files
     */
    void invalidateGeneratedFiles() {
        if (mFilename != null) {
            new File(mFilename).delete();
            mFilename = null;
        }

        if (mBitmapFileName != null) {
            new File(mBitmapFileName).delete();
            mBitmapFileName = null;
        }
    }

    void generateOverlayWithRenderingMode (MediaItem mediaItemsList, OverlayFrame overlay, int height , int width)
        throws FileNotFoundException, IOException {

        final MediaItem t = mediaItemsList;

        /* get the rendering mode */
        int renderMode = t.getRenderingMode();

        Bitmap overlayBitmap = ((OverlayFrame)overlay).getBitmap();

        /*
         * Check if the resize of Overlay is needed with rendering mode applied
         * because of change in export dimensions
         */
        int resizedRGBFileHeight = ((OverlayFrame)overlay).getResizedRGBSizeHeight();
        int resizedRGBFileWidth = ((OverlayFrame)overlay).getResizedRGBSizeWidth();

        /* Get original bitmap width if it is not resized */
        if(resizedRGBFileWidth == 0) {
            resizedRGBFileWidth = overlayBitmap.getWidth();
        }
        /* Get original bitmap height if it is not resized */
        if(resizedRGBFileHeight == 0) {
            resizedRGBFileHeight = overlayBitmap.getHeight();
        }

        if (resizedRGBFileWidth != width || resizedRGBFileHeight != height
            || (!(new File(((OverlayFrame)overlay).getFilename()).exists()))) {
            /*
             *  Create the canvas bitmap
             */
            final Bitmap destBitmap = Bitmap.createBitmap((int)width,
                                                      (int)height,
                                                      Bitmap.Config.ARGB_8888);
            final Canvas overlayCanvas = new Canvas(destBitmap);
            final Rect destRect;
            final Rect srcRect;

            switch (renderMode) {
                case MediaItem.RENDERING_MODE_STRETCH: {
                    destRect = new Rect(0, 0, overlayCanvas.getWidth(),
                                             overlayCanvas.getHeight());
                    srcRect = new Rect(0, 0, overlayBitmap.getWidth(),
                                             overlayBitmap.getHeight());
                    break;
                }

                case MediaItem.RENDERING_MODE_BLACK_BORDER: {
                    int left, right, top, bottom;
                    float aROverlayImage, aRCanvas;
                    aROverlayImage = (float)(overlayBitmap.getWidth()) /
                                     (float)(overlayBitmap.getHeight());

                    aRCanvas = (float)(overlayCanvas.getWidth()) /
                                     (float)(overlayCanvas.getHeight());

                    if (aROverlayImage > aRCanvas) {
                        int newHeight = ((overlayCanvas.getWidth() * overlayBitmap.getHeight())
                                         / overlayBitmap.getWidth());
                        left = 0;
                        top  = (overlayCanvas.getHeight() - newHeight) / 2;
                        right = overlayCanvas.getWidth();
                        bottom = top + newHeight;
                    } else {
                        int newWidth = ((overlayCanvas.getHeight() * overlayBitmap.getWidth())
                                            / overlayBitmap.getHeight());
                        left = (overlayCanvas.getWidth() - newWidth) / 2;
                        top  = 0;
                        right = left + newWidth;
                        bottom = overlayCanvas.getHeight();
                    }

                    destRect = new Rect(left, top, right, bottom);
                    srcRect = new Rect(0, 0, overlayBitmap.getWidth(), overlayBitmap.getHeight());
                    break;
                }

                case MediaItem.RENDERING_MODE_CROPPING: {
                    // Calculate the source rect
                    int left, right, top, bottom;
                    float aROverlayImage, aRCanvas;
                    aROverlayImage = (float)(overlayBitmap.getWidth()) /
                                     (float)(overlayBitmap.getHeight());
                    aRCanvas = (float)(overlayCanvas.getWidth()) /
                                    (float)(overlayCanvas.getHeight());
                    if (aROverlayImage < aRCanvas) {
                        int newHeight = ((overlayBitmap.getWidth() * overlayCanvas.getHeight())
                                   / overlayCanvas.getWidth());

                        left = 0;
                        top  = (overlayBitmap.getHeight() - newHeight) / 2;
                        right = overlayBitmap.getWidth();
                        bottom = top + newHeight;
                    } else {
                        int newWidth = ((overlayBitmap.getHeight() * overlayCanvas.getWidth())
                                    / overlayCanvas.getHeight());
                        left = (overlayBitmap.getWidth() - newWidth) / 2;
                        top  = 0;
                        right = left + newWidth;
                        bottom = overlayBitmap.getHeight();
                    }

                    srcRect = new Rect(left, top, right, bottom);
                    destRect = new Rect(0, 0, overlayCanvas.getWidth(), overlayCanvas.getHeight());
                    break;
                }

                default: {
                    throw new IllegalStateException("Rendering mode: " + renderMode);
                }
            }

            overlayCanvas.drawBitmap(overlayBitmap, srcRect, destRect, sResizePaint);
            overlayCanvas.setBitmap(null);

            /*
             * Write to the dest file
             */
            String outFileName = ((OverlayFrame)overlay).getFilename();

            /*
             * Save the image to same rgb file
             */
            if (outFileName != null) {
                new File(outFileName).delete();
            }

            final FileOutputStream fl = new FileOutputStream(outFileName);
            final DataOutputStream dos = new DataOutputStream(fl);

            /*
             * Populate the rgb file with bitmap data
             */
            final int [] framingBuffer = new int[width];
            ByteBuffer byteBuffer = ByteBuffer.allocate(framingBuffer.length * 4);
            IntBuffer intBuffer;

            byte[] array = byteBuffer.array();
            int tmp = 0;
            while(tmp < height) {
                destBitmap.getPixels(framingBuffer,0,width,0,tmp,width,1);
                intBuffer = byteBuffer.asIntBuffer();
                intBuffer.put(framingBuffer,0,width);
                dos.write(array);
                tmp += 1;
            }
            fl.flush();
            fl.close();

            /*
             * Set the resized RGB width and height
             */
            ((OverlayFrame)overlay).setResizedRGBSize(width, height);
        }
    }
}

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


/**
 * This class is used to overlay an image on top of a media item.
 * {@hide}
 */
public class OverlayFrame extends Overlay {
    // Instance variables
    private Bitmap mBitmap;
    private String mFilename;

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
    public OverlayFrame(MediaItem mediaItem, String overlayId, Bitmap bitmap, long startTimeMs,
            long durationMs) {
        super(mediaItem, overlayId, startTimeMs, durationMs);
        mBitmap = bitmap;
        mFilename = null;
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
    OverlayFrame(MediaItem mediaItem, String overlayId, String filename, long startTimeMs,
            long durationMs) {
        super(mediaItem, overlayId, startTimeMs, durationMs);
        mFilename = filename;
        mBitmap = BitmapFactory.decodeFile(mFilename);
    }

    /**
     * @return Get the overlay bitmap
     */
    public Bitmap getBitmap() {
        return mBitmap;
    }

    /**
     * @param bitmap The overlay bitmap
     */
    public void setBitmap(Bitmap bitmap) {
        mBitmap = bitmap;
        if (mFilename != null) {
            // Delete the file
            new File(mFilename).delete();
            // Invalidate the filename
            mFilename = null;
        }

        // Invalidate the transitions if necessary
        getMediaItem().invalidateTransitions(this);
    }

    /**
     * Get the file name of this overlay
     */
    String getFilename() {
        return mFilename;
    }

    /**
     * Save the overlay to the project folder
     *
     * @param editor The video editor
     *
     * @return
     * @throws FileNotFoundException if the bitmap cannot be saved
     * @throws IOException if the bitmap file cannot be saved
     */
    String save(VideoEditor editor) throws FileNotFoundException, IOException {
        if (mFilename != null) {
            return mFilename;
        }

        mFilename = editor.getPath() + "/" + getId() + ".png";
        // Save the image to a local file
        final FileOutputStream out = new FileOutputStream(mFilename);
        mBitmap.compress(CompressFormat.PNG, 100, out);
        out.flush();
        out.close();
        return mFilename;
    }

    /**
     * Delete the overlay file
     */
    void invalidate() {
        if (mFilename != null) {
            new File(mFilename).delete();
        }
    }
}

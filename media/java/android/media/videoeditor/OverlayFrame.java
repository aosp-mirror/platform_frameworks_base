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


/**
 * This class is used to overlay an image on top of a media item. This class
 * does not manage deletion of the overlay file so application may use
 * {@link #getFilename()} for this purpose.
 * {@hide}
 */
public class OverlayFrame extends Overlay {
    // Instance variables
    private final String mFilename;

    /**
     * An object of this type cannot be instantiated by using the default
     * constructor
     */
    @SuppressWarnings("unused")
    private OverlayFrame() {
        this(null, null, 0, 0);
    }

    /**
     * Constructor for an OverlayFrame
     *
     * @param overlayId The overlay id
     * @param filename The file name that contains the overlay. Only PNG
     *            supported.
     * @param startTimeMs The overlay start time in milliseconds
     * @param durationMs The overlay duration in milliseconds
     *
     * @throws IllegalArgumentException if the file type is not PNG or the
     *      startTimeMs and durationMs are incorrect.
     */
    public OverlayFrame(String overlayId, String filename, long startTimeMs, long durationMs) {
        super(overlayId, startTimeMs, durationMs);
        mFilename = filename;
    }

    /**
     * Get the file name of this overlay
     */
    public String getFilename() {
        return mFilename;
    }
}

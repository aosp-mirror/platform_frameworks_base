/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.graphics.Bitmap;

/**
 * MediaMetadataRetriever class provides a unified interface for retrieving
 * frame and meta data from an input media file.
 * {@hide}
 */
public class MediaMetadataRetriever
{
    static {
        System.loadLibrary("media_jni");
    }

    public MediaMetadataRetriever() {
        native_setup();
    }

    /**
     * Call this method before setDataSource() so that the mode becomes
     * effective for subsequent operations. This method can be called only once
     * at the beginning if the intended mode of operation for a
     * MediaMetadataRetriever object remains the same for its whole lifetime,
     * and thus it is unnecessary to call this method each time setDataSource()
     * is called. If this is not never called (which is allowed), by default the
     * intended mode of operation is to both capture frame and retrieve meta
     * data (i.e., MODE_GET_METADATA_ONLY | MODE_CAPTURE_FRAME_ONLY).
     * Often, this may not be what one wants, since doing this has negative
     * performance impact on execution time of a call to setDataSource(), since
     * both types of operations may be time consuming.
     * 
     * @param mode The intended mode of operation. Can be any combination of 
     * MODE_GET_METADATA_ONLY and MODE_CAPTURE_FRAME_ONLY:
     * 1. MODE_GET_METADATA_ONLY & MODE_CAPTURE_FRAME_ONLY: 
     *    For neither frame capture nor meta data retrieval
     * 2. MODE_GET_METADATA_ONLY: For meta data retrieval only
     * 3. MODE_CAPTURE_FRAME_ONLY: For frame capture only
     * 4. MODE_GET_METADATA_ONLY | MODE_CAPTURE_FRAME_ONLY: 
     *    For both frame capture and meta data retrieval
     */
    public native void setMode(int mode);

    /**
     * Call this method before the rest. This method may be time-consuming.
     * 
     * @param path The path of the input media file.
     * @throws IllegalArgumentException If the path is invalid.
     */
    public native void setDataSource(String path) throws IllegalArgumentException;

    /**
     * Call this method after setDataSource(). This method retrieves the 
     * meta data value associated with the keyCode.
     * 
     * The keyCode currently supported is listed below as METADATA_XXX
     * constants. With any other value, it returns a null pointer.
     * 
     * @param keyCode One of the constants listed below at the end of the class.
     * @return The meta data value associate with the given keyCode on success; 
     * null on failure.
     */
    public native String extractMetadata(int keyCode);

    /**
     * Call this method after setDataSource(). This method finds a
     * representative frame if successful and returns it as a bitmap. This is
     * useful for generating a thumbnail for an input media source.
     * 
     * @return A Bitmap containing a representative video frame, which 
     *         can be null, if such a frame cannot be retrieved.
     */
    public native Bitmap captureFrame();
    
    /**
     * Call this method after setDataSource(). This method finds the optional
     * graphic or album art associated (embedded or external url linked) the 
     * related data source.
     * 
     * @return null if no such graphic is found.
     */
    public native byte[] extractAlbumArt();

    /**
     * Call it when one is done with the object. This method releases the memory
     * allocated internally.
     */
    public native void release();
    private native void native_setup(); 

    private native final void native_finalize();

    @Override
    protected void finalize() throws Throwable {
        try {
            native_finalize();
        } finally {
            super.finalize();
        }
    }

    public static final int MODE_GET_METADATA_ONLY  = 0x01;
    public static final int MODE_CAPTURE_FRAME_ONLY = 0x02;

    /*
     * Do not change these values without updating their counterparts
     * in include/media/mediametadataretriever.h!
     */
    public static final int METADATA_KEY_CD_TRACK_NUMBER = 0;
    public static final int METADATA_KEY_ALBUM           = 1;
    public static final int METADATA_KEY_ARTIST          = 2;
    public static final int METADATA_KEY_AUTHOR          = 3;
    public static final int METADATA_KEY_COMPOSER        = 4;
    public static final int METADATA_KEY_DATE            = 5;
    public static final int METADATA_KEY_GENRE           = 6;
    public static final int METADATA_KEY_TITLE           = 7;
    public static final int METADATA_KEY_YEAR            = 8;
    public static final int METADATA_KEY_DURATION        = 9;
    public static final int METADATA_KEY_NUM_TRACKS     = 10;
}

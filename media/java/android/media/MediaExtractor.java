/*
 * Copyright (C) 2012 The Android Open Source Project
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

import java.nio.ByteBuffer;
import java.util.Map;

/**
 * MediaExtractor
 * @hide
*/
public class MediaExtractor
{
    public MediaExtractor(String path) {
        native_setup(path);
    }

    @Override
    protected void finalize() {
        native_finalize();
    }

    // Make sure you call this when you're done to free up any resources
    // instead of relying on the garbage collector to do this for you at
    // some point in the future.
    public native final void release();

    public native int countTracks();
    public native Map<String, Object> getTrackFormat(int index);

    // Subsequent calls to "readSampleData", "getSampleTrackIndex" and
    // "getSampleTime" only retrieve information for the subset of tracks
    // selected by the call below.
    // Selecting the same track multiple times has no effect, the track
    // is only selected once.
    public native void selectTrack(int index);

    // All selected tracks seek near the requested time. The next sample
    // returned for each selected track will be a sync sample.
    public native void seekTo(long timeUs);

    public native boolean advance();

    // Retrieve the current encoded sample and store it in the byte buffer
    // starting at the given offset. Returns the sample size.
    public native int readSampleData(ByteBuffer byteBuf, int offset);

    // Returns the track index the current sample originates from.
    public native int getSampleTrackIndex();

    // Returns the current sample's presentation time in microseconds.
    public native long getSampleTime();

    // Keep these in sync with their equivalents in NuMediaExtractor.h
    public static final int SAMPLE_FLAG_SYNC      = 1;
    public static final int SAMPLE_FLAG_ENCRYPTED = 2;

    // Returns the current sample's flags.
    public native int getSampleFlags();

    private static native final void native_init();
    private native final void native_setup(String path);
    private native final void native_finalize();

    static {
        System.loadLibrary("media_jni");
        native_init();
    }

    private int mNativeContext;
}

/*
 * Copyright 2013 The Android Open Source Project
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
package androidx.media.filterfw.decoder;

import android.annotation.TargetApi;
import android.media.MediaFormat;
import android.util.Log;

import androidx.media.filterfw.FrameImage2D;

/**
 * Base class for all {@link TrackDecoder} classes that decode video.
 */
@TargetApi(16)
public abstract class VideoTrackDecoder extends TrackDecoder {

    private static final String LOG_TAG = "VideoTrackDecoder";

    protected final Object mFrameMonitor = new Object();
    protected volatile boolean mFrameAvailable; // Access guarded by mFrameMonitor.

    protected VideoTrackDecoder(int trackIndex, MediaFormat format, Listener listener) {
        super(trackIndex, format, listener);
        if (!DecoderUtil.isVideoFormat(format)) {
            throw new IllegalArgumentException(
                    "VideoTrackDecoder can only be used with video formats");
        }
    }

    public void grabFrame(FrameImage2D outputVideoFrame, int rotation) {
        synchronized (mFrameMonitor) {
            if (!mFrameAvailable) {
                Log.w(LOG_TAG, "frame is not ready - the caller has to wait for a corresponding " +
                        "onDecodedFrameAvailable() call");
                return;
            }

            copyFrameDataTo(outputVideoFrame, rotation);

            mFrameAvailable = false;
            mFrameMonitor.notifyAll();
        }
    }


    /**
     * Waits for the frame to be picked up by the MFF thread, i.e. blocks until the
     * {@link #grabFrame(FrameImage2D, int)}) method is called.
     */
    public boolean waitForFrameGrab() {
        synchronized (mFrameMonitor) {
            try {
                while (mFrameAvailable) {
                    mFrameMonitor.wait();
                }
                return true;
            } catch (InterruptedException e) {
                return false;
            }
        }
    }

    protected final void markFrameAvailable() {
        synchronized (mFrameMonitor) {
            mFrameAvailable = true;
            mFrameMonitor.notifyAll();
        }
    }

    /**
     * @return if the frame dimension needs to be swapped,
     *   i.e. (width,height) becomes (height, width)
     */
    protected static boolean needSwapDimension(int rotation) {
        switch(rotation) {
            case MediaDecoder.ROTATE_90_RIGHT:
            case MediaDecoder.ROTATE_90_LEFT:
                return true;
            case MediaDecoder.ROTATE_NONE:
            case MediaDecoder.ROTATE_180:
                return false;
            default:
                throw new IllegalArgumentException("Unsupported rotation angle.");
        }
    }

    /**
     * Subclasses must implement this to copy the video frame data to an MFF frame.
     *
     * @param outputVideoFrame The destination frame
     * @param rotation The desired rotation of the frame
     */
    protected abstract void copyFrameDataTo(FrameImage2D outputVideoFrame, int rotation);

}

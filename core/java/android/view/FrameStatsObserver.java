/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.view;

import android.annotation.NonNull;
import android.util.Log;
import android.os.Looper;
import android.os.MessageQueue;

import com.android.internal.util.VirtualRefBasePtr;

import java.lang.NullPointerException;
import java.lang.ref.WeakReference;
import java.lang.SuppressWarnings;

/**
 * Provides streaming access to frame stats information from the rendering
 * subsystem to apps.
 *
 * @hide
 */
public abstract class FrameStatsObserver {
    private static final String TAG = "FrameStatsObserver";

    private MessageQueue mMessageQueue;
    private long[] mBuffer;

    private FrameStats mFrameStats;

    /* package */ ThreadedRenderer mRenderer;
    /* package */ VirtualRefBasePtr mNative;

    /**
     * Containing class for frame statistics reported
     * by the rendering subsystem.
     */
    public static class FrameStats {
        /**
         * Precise timing data for various milestones in a frame
         * lifecycle.
         *
         * This data is exactly the same as what is returned by
         * `adb shell dumpsys gfxinfo <PACKAGE_NAME> framestats`
         *
         * The fields reported may change from release to release.
         *
         * @see {@link http://developer.android.com/training/testing/performance.html}
         * for a description of the fields present.
         */
        public long[] mTimingData;
    }

    /**
     * Creates a FrameStatsObserver
     *
     * @param looper the looper to use when invoking callbacks
     */
    public FrameStatsObserver(@NonNull Looper looper) {
        if (looper == null) {
            throw new NullPointerException("looper cannot be null");
        }

        mMessageQueue = looper.getQueue();
        if (mMessageQueue == null) {
            throw new IllegalStateException("invalid looper, null message queue\n");
        }

        mFrameStats = new FrameStats();
    }

    /**
     * Called on provided looper when frame stats data is available
     * for the previous frame.
     *
     * Clients of this class must do as little work as possible within
     * this callback, as the buffer is shared between the producer and consumer.
     *
     * If the consumer is still executing within this method when there is new
     * data available that data will be dropped. The producer cannot
     * wait on the consumer.
     *
     * @param data the newly available data
     */
    public abstract void onDataAvailable(FrameStats data);

    /**
     * Returns the number of reports dropped as a result of a slow
     * consumer.
     */
    public long getDroppedReportCount() {
        if (mRenderer == null) {
            return 0;
        }

        return mRenderer.getDroppedFrameReportCount();
    }

    public boolean isRegistered() {
        return mRenderer != null && mNative != null;
    }

    // === called by native === //
    @SuppressWarnings("unused")
    private void notifyDataAvailable() {
        mFrameStats.mTimingData = mBuffer;
        onDataAvailable(mFrameStats);
    }
}

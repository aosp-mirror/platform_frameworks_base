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
import android.annotation.UnsupportedAppUsage;
import android.os.Looper;
import android.os.MessageQueue;

import com.android.internal.util.VirtualRefBasePtr;

import java.lang.ref.WeakReference;

/**
 * Provides streaming access to frame stats information from the rendering
 * subsystem to apps.
 *
 * @hide
 */
public class FrameMetricsObserver {
    @UnsupportedAppUsage
    private MessageQueue mMessageQueue;

    private WeakReference<Window> mWindow;

    @UnsupportedAppUsage
    private FrameMetrics mFrameMetrics;

    /* pacage */ Window.OnFrameMetricsAvailableListener mListener;
    /** @hide */
    public VirtualRefBasePtr mNative;

    /**
     * Creates a FrameMetricsObserver
     *
     * @param looper the looper to use when invoking callbacks
     */
    FrameMetricsObserver(@NonNull Window window, @NonNull Looper looper,
            @NonNull Window.OnFrameMetricsAvailableListener listener) {
        if (looper == null) {
            throw new NullPointerException("looper cannot be null");
        }

        mMessageQueue = looper.getQueue();
        if (mMessageQueue == null) {
            throw new IllegalStateException("invalid looper, null message queue\n");
        }

        mFrameMetrics = new FrameMetrics();
        mWindow = new WeakReference<>(window);
        mListener = listener;
    }

    // Called by native on the provided Handler
    @SuppressWarnings("unused")
    @UnsupportedAppUsage
    private void notifyDataAvailable(int dropCount) {
        final Window window = mWindow.get();
        if (window != null) {
            mListener.onFrameMetricsAvailable(window, mFrameMetrics, dropCount);
        }
    }
}

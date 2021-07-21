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
import android.graphics.HardwareRendererObserver;
import android.os.Handler;

import java.lang.ref.WeakReference;

/**
 * Provides streaming access to frame stats information from the rendering
 * subsystem to apps.
 *
 * @hide
 */
public class FrameMetricsObserver
        implements HardwareRendererObserver.OnFrameMetricsAvailableListener {
    private final WeakReference<Window> mWindow;
    private final FrameMetrics mFrameMetrics;
    private final HardwareRendererObserver mObserver;
    /*package*/ final Window.OnFrameMetricsAvailableListener mListener;

    /**
     * Creates a FrameMetricsObserver
     *
     * @param handler the Handler to use when invoking callbacks
     */
    FrameMetricsObserver(@NonNull Window window, @NonNull Handler handler,
            @NonNull Window.OnFrameMetricsAvailableListener listener) {
        mWindow = new WeakReference<>(window);
        mListener = listener;
        mFrameMetrics = new FrameMetrics();
        mObserver = new HardwareRendererObserver(this,  mFrameMetrics.mTimingData, handler,
                false /*waitForPresentTime*/);
    }

    /**
     * Implementation of OnFrameMetricsAvailableListener
     * @param dropCountSinceLastInvocation the number of reports dropped since the last time
     * @Override
     */
    public void onFrameMetricsAvailable(int dropCountSinceLastInvocation) {
        if (mWindow.get() != null) {
            mListener.onFrameMetricsAvailable(mWindow.get(), mFrameMetrics,
                    dropCountSinceLastInvocation);
        }
    }

    /*package*/ HardwareRendererObserver getRendererObserver() {
        return mObserver;
    }
}

/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.graphics.Rect;
import android.os.IBinder;

import com.android.internal.util.Preconditions;

import java.util.concurrent.Executor;

/**
 * Listener for sampling the result of the screen composition.
 * {@hide}
 */
public abstract class CompositionSamplingListener {

    private final long mNativeListener;
    private final Executor mExecutor;

    public CompositionSamplingListener(Executor executor) {
        mExecutor = executor;
        mNativeListener = nativeCreate(this);
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mNativeListener != 0) {
                unregister(this);
                nativeDestroy(mNativeListener);
            }
        } finally {
            super.finalize();
        }
    }

    /**
     * Reports a luma sample from the registered region.
     */
    public abstract void onSampleCollected(float medianLuma);

    /**
     * Registers a sampling listener.
     */
    public static void register(CompositionSamplingListener listener,
            int displayId, IBinder stopLayer, Rect samplingArea) {
        Preconditions.checkArgument(displayId == Display.DEFAULT_DISPLAY,
                "default display only for now");
        nativeRegister(listener.mNativeListener, stopLayer, samplingArea.left, samplingArea.top,
                samplingArea.right, samplingArea.bottom);
    }

    /**
     * Unregisters a sampling listener.
     */
    public static void unregister(CompositionSamplingListener listener) {
        nativeUnregister(listener.mNativeListener);
    }

    /**
     * Dispatch the collected sample.
     *
     * Called from native code on a binder thread.
     */
    private static void dispatchOnSampleCollected(CompositionSamplingListener listener,
            float medianLuma) {
        listener.mExecutor.execute(() -> listener.onSampleCollected(medianLuma));
    }

    private static native long nativeCreate(CompositionSamplingListener thiz);
    private static native void nativeDestroy(long ptr);
    private static native void nativeRegister(long ptr, IBinder stopLayer,
            int samplingAreaLeft, int top, int right, int bottom);
    private static native void nativeUnregister(long ptr);
}

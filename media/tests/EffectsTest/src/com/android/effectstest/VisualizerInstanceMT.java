/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.effectstest;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

class VisualizerInstanceMT implements VisualizerInstance {

    private static final String TAG = "VisualizerInstanceMT";

    private final Object mLock = new Object();
    private final int mThreadCount;
    @GuardedBy("mLock")
    private Handler mVisualizerHandler;
    @GuardedBy("mLock")
    private VisualizerInstanceSync mVisualizer;

    VisualizerInstanceMT(int session, Handler uiHandler, int extraThreadCount) {
        Log.d(TAG, "Multi-threaded constructor");
        mThreadCount = 1 + extraThreadCount;
        Thread t = new Thread() {
            @Override public void run() {
                Looper.prepare();
                VisualizerInstanceSync v = new VisualizerInstanceSync(session, uiHandler);
                synchronized (mLock) {
                    mVisualizerHandler = new Handler();
                    mVisualizer = v;
                }
                Looper.loop();
            }
        };
        t.start();
    }

    private VisualizerInstance getVisualizer() {
        synchronized (mLock) {
            return mVisualizer != null ? new VisualizerInstanceSync(mVisualizer) : null;
        }
    }

    private interface VisualizerOperation {
        void run(VisualizerInstance v);
    }

    private void runOperationMt(VisualizerOperation op) {
        final VisualizerInstance v = getVisualizer();
        if (v == null) return;
        for (int i = 0; i < mThreadCount; ++i) {
            Thread t = new Thread() {
                @Override
                public void run() {
                    op.run(v);
                }
            };
            t.start();
        }
    }

    @Override
    public void enableDataCaptureListener(boolean enable) {
        runOperationMt(v -> v.enableDataCaptureListener(enable));
    }

    @Override
    public boolean getEnabled() {
        final VisualizerInstance v = getVisualizer();
        return v != null ? v.getEnabled() : false;
    }

    @Override
    public void release() {
        runOperationMt(v -> v.release());
        synchronized (mLock) {
            if (mVisualizerHandler == null) return;
            mVisualizerHandler.post(() -> {
                synchronized (mLock) {
                    mVisualizerHandler = null;
                    mVisualizer = null;
                    Looper.myLooper().quitSafely();
                }
                Log.d(TAG, "Exiting looper");
            });
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        runOperationMt(v -> v.setEnabled(enabled));
    }

    @Override
    public void startStopCapture(boolean start) {
        runOperationMt(v -> v.startStopCapture(start));
    }
}

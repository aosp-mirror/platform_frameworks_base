/*
 * Copyright 2021 The Android Open Source Project
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

/**
 * Listener for sampling the frames per second for a SurfaceControl and its children.
 * This should only be used by a system component that needs to listen to a SurfaceControl's
 * tree's FPS when it is not actively submitting transactions for that SurfaceControl.
 * Otherwise, ASurfaceTransaction_OnComplete callbacks should be used.
 *
 * @hide
 */
public abstract class SurfaceControlFpsListener {
    private long mNativeListener;

    public SurfaceControlFpsListener() {
        mNativeListener = nativeCreate(this);
    }

    protected void destroy() {
        if (mNativeListener == 0) {
            return;
        }
        unregister();
        nativeDestroy(mNativeListener);
        mNativeListener = 0;
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            destroy();
        } finally {
            super.finalize();
        }
    }

    /**
     * Reports the fps from the registered SurfaceControl
     */
    public abstract void onFpsReported(float fps);

    /**
     * Registers the sampling listener for a particular task ID
     */
    public void register(int taskId) {
        if (mNativeListener == 0) {
            return;
        }

        nativeRegister(mNativeListener, taskId);
    }

    /**
     * Unregisters the sampling listener.
     */
    public void unregister() {
        if (mNativeListener == 0) {
            return;
        }
        nativeUnregister(mNativeListener);
    }

    /**
     * Dispatch the collected sample.
     *
     * Called from native code on a binder thread.
     */
    private static void dispatchOnFpsReported(
            @NonNull SurfaceControlFpsListener listener, float fps) {
        listener.onFpsReported(fps);
    }

    private static native long nativeCreate(SurfaceControlFpsListener thiz);
    private static native void nativeDestroy(long ptr);
    private static native void nativeRegister(long ptr, int taskId);
    private static native void nativeUnregister(long ptr);
}

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

import com.android.internal.annotations.VisibleForTesting;

import java.util.concurrent.Executor;

/**
 * Listens for tunnel mode enabled/disabled events from SurfaceFlinger.
 * {@hide}
 */
public abstract class TunnelModeEnabledListener {

    private long mNativeListener;
    private final Executor mExecutor;

    public TunnelModeEnabledListener(Executor executor) {
        mExecutor = executor;
        mNativeListener = nativeCreate(this);
    }

    /**
     * Destroys the listener.
     */
    public void destroy() {
        if (mNativeListener == 0) {
            return;
        }
        unregister(this);
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
     * Reports when tunnel mode has been enabled/disabled.
     */
    public abstract void onTunnelModeEnabledChanged(boolean tunnelModeEnabled);

    /**
     * Registers a listener.
     */
    public static void register(TunnelModeEnabledListener listener) {
        if (listener.mNativeListener == 0) {
            return;
        }
        nativeRegister(listener.mNativeListener);
    }

    /**
     * Unregisters a listener.
     */
    public static void unregister(TunnelModeEnabledListener listener) {
        if (listener.mNativeListener == 0) {
            return;
        }
        nativeUnregister(listener.mNativeListener);
    }

    /**
     * Dispatch tunnel mode enabled.
     *
     * Called from native code on a binder thread.
     */
    @VisibleForTesting
    public static void dispatchOnTunnelModeEnabledChanged(TunnelModeEnabledListener listener,
            boolean tunnelModeEnabled) {
        listener.mExecutor.execute(() -> listener.onTunnelModeEnabledChanged(tunnelModeEnabled));
    }

    private static native long nativeCreate(TunnelModeEnabledListener thiz);
    private static native void nativeDestroy(long ptr);
    private static native void nativeRegister(long ptr);
    private static native void nativeUnregister(long ptr);
}

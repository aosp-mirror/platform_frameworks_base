/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.window;

import android.view.InputWindowHandle;

import libcore.util.NativeAllocationRegistry;

/**
 * Listener for getting {@link InputWindowHandle} updates from SurfaceFlinger.
 * @hide
 */
public abstract class WindowInfosListener {
    private final long mNativeListener;

    public WindowInfosListener() {
        NativeAllocationRegistry registry = NativeAllocationRegistry.createMalloced(
                WindowInfosListener.class.getClassLoader(), nativeGetFinalizer());

        mNativeListener = nativeCreate(this);
        registry.registerNativeAllocation(this, mNativeListener);
    }

    /**
     * Called when WindowInfos in SurfaceFlinger have changed.
     * @param windowHandles Reverse Z ordered array of window information that was on screen,
     *                      where the first value is the topmost window.
     */
    public abstract void onWindowInfosChanged(InputWindowHandle[] windowHandles);

    /**
     * Register the WindowInfosListener.
     */
    public void register() {
        nativeRegister(mNativeListener);
    }

    /**
     * Unregisters the WindowInfosListener.
     */
    public void unregister() {
        nativeUnregister(mNativeListener);
    }

    private static native long nativeCreate(WindowInfosListener thiz);
    private static native void nativeRegister(long ptr);
    private static native void nativeUnregister(long ptr);
    private static native long nativeGetFinalizer();
}

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

import android.graphics.Matrix;
import android.util.Pair;
import android.util.Size;
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
    public abstract void onWindowInfosChanged(InputWindowHandle[] windowHandles,
            DisplayInfo[] displayInfos);

    /**
     * Register the WindowInfosListener.
     *
     * @return The cached values for InputWindowHandles and DisplayInfos. This is the last updated
     * value that was sent from SurfaceFlinger to this particular process. If there was nothing
     * registered previously, then the data can be empty.
     */
    public Pair<InputWindowHandle[], DisplayInfo[]> register() {
        return nativeRegister(mNativeListener);
    }

    /**
     * Unregisters the WindowInfosListener.
     */
    public void unregister() {
        nativeUnregister(mNativeListener);
    }

    private static native long nativeCreate(WindowInfosListener thiz);
    private static native Pair<InputWindowHandle[], DisplayInfo[]> nativeRegister(long ptr);
    private static native void nativeUnregister(long ptr);
    private static native long nativeGetFinalizer();

    /**
     * Describes information about a display that can have windows in it.
     */
    public static final class DisplayInfo {
        public final int mDisplayId;

        /**
         * Logical display dimensions.
         */
        public final Size mLogicalSize;

        /**
         * The display transform. This takes display coordinates to logical display coordinates.
         */
        public final Matrix mTransform;

        private DisplayInfo(int displayId, int logicalWidth, int logicalHeight, Matrix transform) {
            mDisplayId = displayId;
            mLogicalSize = new Size(logicalWidth, logicalHeight);
            mTransform = transform;
        }

        @Override
        public String toString() {
            return "displayId=" + mDisplayId
                    + ", mLogicalSize=" + mLogicalSize
                    + ", mTransform=" + mTransform;
        }
    }
}

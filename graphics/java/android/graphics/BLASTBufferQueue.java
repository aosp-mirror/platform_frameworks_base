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

package android.graphics;

import android.view.Surface;
import android.view.SurfaceControl;

/**
 * @hide
 */
public final class BLASTBufferQueue {
    // Note: This field is accessed by native code.
    private long mNativeObject; // BLASTBufferQueue*

    private static native long nativeCreate(long surfaceControl, long width, long height,
            boolean tripleBufferingEnabled);
    private static native void nativeDestroy(long ptr);
    private static native Surface nativeGetSurface(long ptr);
    private static native void nativeSetNextTransaction(long ptr, long transactionPtr);
    private static native void nativeUpdate(long ptr, long surfaceControl, long width, long height);

    /** Create a new connection with the surface flinger. */
    public BLASTBufferQueue(SurfaceControl sc, int width, int height,
            boolean tripleBufferingEnabled) {
        mNativeObject = nativeCreate(sc.mNativeObject, width, height, tripleBufferingEnabled);
    }

    public void destroy() {
        nativeDestroy(mNativeObject);
    }

    public Surface getSurface() {
        return nativeGetSurface(mNativeObject);
    }

    public void setNextTransaction(SurfaceControl.Transaction t) {
        nativeSetNextTransaction(mNativeObject, t.mNativeObject);
    }

    public void update(SurfaceControl sc, int width, int height) {
        nativeUpdate(mNativeObject, sc.mNativeObject, width, height);
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mNativeObject != 0) {
                nativeDestroy(mNativeObject);
            }
        } finally {
            super.finalize();
        }
    }
}

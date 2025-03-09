/*
 * Copyright 2024 The Android Open Source Project
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

import android.annotation.RequiresPermission;

import libcore.util.NativeAllocationRegistry;

/**
 * Allows for the monitoring of visible layers that are using picture processing.
 * @hide
 */
public abstract class SurfaceControlActivePictureListener {
    private static final NativeAllocationRegistry sRegistry =
            NativeAllocationRegistry.createMalloced(
                    SurfaceControlActivePictureListener.class.getClassLoader(),
                    nativeGetDestructor());

    /**
      * Callback when there are changes in the visible layers that are using picture processing.
      *
      * @param activePictures The visible layers that are using picture processing.
      */
    public abstract void onActivePicturesChanged(SurfaceControlActivePicture[] activePictures);

    /**
     * Start listening to changes in active pictures.
     */
    @RequiresPermission(android.Manifest.permission.OBSERVE_PICTURE_PROFILES)
    public void startListening() {
        synchronized (this) {
            long nativePtr = nativeMakeAndStartListening();
            mDestructor = sRegistry.registerNativeAllocation(this, nativePtr);
        }
    }

    /**
     * Stop listening to changes in active pictures.
     */
    @RequiresPermission(android.Manifest.permission.OBSERVE_PICTURE_PROFILES)
    public void stopListening() {
        final Runnable destructor;
        synchronized (this) {
            destructor = mDestructor;
        }
        if (destructor != null) {
            destructor.run();
        }
    }

    private native long nativeMakeAndStartListening();
    private static native long nativeGetDestructor();

    private Runnable mDestructor;
}

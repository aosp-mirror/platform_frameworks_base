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

import android.annotation.RequiresPermission;
import android.os.IBinder;
import android.util.ArrayMap;

import libcore.util.NativeAllocationRegistry;

import java.util.Objects;

/**
 * Allows for the monitoring of layers with HDR content
 *
 * @hide */
public abstract class SurfaceControlHdrLayerInfoListener {
    private static final NativeAllocationRegistry sRegistry =
            NativeAllocationRegistry.createMalloced(
                    SurfaceControlHdrLayerInfoListener.class.getClassLoader(), nGetDestructor());

    /**
     * Callback when the HDR information about the given display has changed
     *
     * @param displayToken The display this callback is about
     * @param numberOfHdrLayers How many HDR layers are visible on the display
     * @param maxW The width of the HDR layer with the largest area
     * @param maxH The height of the HDR layer with the largest area
     * @param flags Additional metadata flags, currently always 0
     *              TODO(b/182312559): Add some flags
     *
     * @hide */
    public abstract void onHdrInfoChanged(IBinder displayToken, int numberOfHdrLayers,
            int maxW, int maxH, int flags);

    /**
     * Registers this as an HDR info listener on the provided display
     * @param displayToken
     */
    @RequiresPermission(android.Manifest.permission.CONTROL_DISPLAY_BRIGHTNESS)
    public void register(IBinder displayToken) {
        Objects.requireNonNull(displayToken);
        synchronized (this) {
            if (mRegisteredListeners.containsKey(displayToken)) {
                return;
            }
            long nativePtr = nRegister(displayToken);
            Runnable destructor = sRegistry.registerNativeAllocation(this, nativePtr);
            mRegisteredListeners.put(displayToken, destructor);
        }
    }

    /**
     * Unregisters this as an HDR info listener on the provided display
     * @param displayToken
     */
    @RequiresPermission(android.Manifest.permission.CONTROL_DISPLAY_BRIGHTNESS)
    public void unregister(IBinder displayToken) {
        Objects.requireNonNull(displayToken);
        final Runnable destructor;
        synchronized (this) {
            destructor = mRegisteredListeners.remove(displayToken);
        }
        if (destructor != null) {
            destructor.run();
        }
    }

    /**
     * Unregisters this on all previously registered displays
     */
    @RequiresPermission(android.Manifest.permission.CONTROL_DISPLAY_BRIGHTNESS)
    public void unregisterAll() {
        final ArrayMap<IBinder, Runnable> toDestroy;
        synchronized (this) {
            toDestroy = mRegisteredListeners;
            mRegisteredListeners = new ArrayMap<>();
        }
        for (Runnable destructor : toDestroy.values()) {
            destructor.run();
        }
    }

    private ArrayMap<IBinder, Runnable> mRegisteredListeners = new ArrayMap<>();

    private static native long nGetDestructor();
    private native long nRegister(IBinder displayToken);
}

/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.display;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.os.IBinder;
import android.view.Display;

import java.util.Objects;

/**
 * Calls into SurfaceFlinger for Display creation and deletion.
 */
public class DisplayControl {
    private static native IBinder nativeCreateVirtualDisplay(String name, boolean secure,
            String uniqueId, float requestedRefreshRate);
    private static native void nativeDestroyVirtualDisplay(IBinder displayToken);
    private static native void nativeOverrideHdrTypes(IBinder displayToken, int[] modes);
    private static native long[] nativeGetPhysicalDisplayIds();
    private static native IBinder nativeGetPhysicalDisplayToken(long physicalDisplayId);
    private static native int nativeSetHdrConversionMode(int conversionMode,
            int preferredHdrOutputType, int[] autoHdrTypes, int autoHdrTypesLength);
    private static native int[] nativeGetSupportedHdrOutputTypes();
    private static native int[] nativeGetHdrOutputTypesWithLatency();
    private static native boolean nativeGetHdrOutputConversionSupport();

    /**
     * Create a virtual display in SurfaceFlinger.
     *
     * @param name The name of the virtual display.
     * @param secure Whether this display is secure.
     * @return The token reference for the display in SurfaceFlinger.
     */
    public static IBinder createVirtualDisplay(String name, boolean secure) {
        Objects.requireNonNull(name, "name must not be null");
        return nativeCreateVirtualDisplay(name, secure, "", 0.0f);
    }

    /**
     * Create a virtual display in SurfaceFlinger.
     *
     * @param name The name of the virtual display.
     * @param secure Whether this display is secure.
     * @param uniqueId The unique ID for the display.
     * @param requestedRefreshRate The requested refresh rate in frames per second.
     * For best results, specify a divisor of the physical refresh rate, e.g., 30 or 60 on
     * 120hz display. If an arbitrary refresh rate is specified, the rate will be rounded
     * up or down to a divisor of the physical display. If 0 is specified, the virtual
     * display is refreshed at the physical display refresh rate.
     * @return The token reference for the display in SurfaceFlinger.
     */
    public static IBinder createVirtualDisplay(String name, boolean secure,
            String uniqueId, float requestedRefreshRate) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(uniqueId, "uniqueId must not be null");
        return nativeCreateVirtualDisplay(name, secure, uniqueId, requestedRefreshRate);
    }

    /**
     * Destroy a virtual display in SurfaceFlinger.
     *
     * @param displayToken The display token for the virtual display to be destroyed.
     */
    public static void destroyVirtualDisplay(IBinder displayToken) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        nativeDestroyVirtualDisplay(displayToken);
    }

    /**
     * Overrides HDR modes for a display device.
     */
    @RequiresPermission(Manifest.permission.ACCESS_SURFACE_FLINGER)
    public static void overrideHdrTypes(@NonNull IBinder displayToken, @NonNull int[] modes) {
        nativeOverrideHdrTypes(displayToken, modes);
    }

    /**
     * Gets all the physical display ids.
     */
    public static long[] getPhysicalDisplayIds() {
        return nativeGetPhysicalDisplayIds();
    }

    /**
     * Gets the display's token from the physical display id
     */
    public static IBinder getPhysicalDisplayToken(long physicalDisplayId) {
        return nativeGetPhysicalDisplayToken(physicalDisplayId);
    }

    /**
     * Sets the HDR conversion mode for the device.
     *
     * Returns the system preferred Hdr output type nn case when HDR conversion mode is
     * {@link android.hardware.display.HdrConversionMode#HDR_CONVERSION_SYSTEM}.
     * Returns Hdr::INVALID in other cases.
     * @hide
     */
    public static int setHdrConversionMode(int conversionMode, int preferredHdrOutputType,
            int[] autoHdrTypes) {
        int length = autoHdrTypes != null ? autoHdrTypes.length : 0;
        return nativeSetHdrConversionMode(
                conversionMode, preferredHdrOutputType, autoHdrTypes, length);
    }

    /**
     * Returns the HDR output types supported by the device.
     * @hide
     */
    public static @Display.HdrCapabilities.HdrType int[] getSupportedHdrOutputTypes() {
        return nativeGetSupportedHdrOutputTypes();
    }

    /**
     * Returns the HDR output types which introduces latency on conversion to them.
     * @hide
     */
    public static @Display.HdrCapabilities.HdrType int[] getHdrOutputTypesWithLatency() {
        return nativeGetHdrOutputTypesWithLatency();
    }

    /**
     * Returns whether the HDR output conversion is supported by the device.
     * @hide
     */
    public static boolean getHdrOutputConversionSupport() {
        return nativeGetHdrOutputConversionSupport();
    }
}

/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.annotation.NonNull;

import libcore.util.NativeAllocationRegistry;

/**
 * Gainmap represents a mechanism for augmenting an SDR image to produce an HDR one with variable
 * display adjustment capability.
 *
 * It is a combination of a set of metadata describing the gainmap, as well as either a 1 or 3
 * channel Bitmap that represents the gainmap data itself.
 *
 * @hide
 */
public class Gainmap {
    private final long mNativePtr;
    private final Bitmap mGainmapImage;

    // called from JNI and Bitmap_Delegate.
    private Gainmap(Bitmap gainmapImage, long nativeGainmap, int allocationByteCount,
            boolean fromMalloc) {
        if (nativeGainmap == 0) {
            throw new RuntimeException("internal error: native gainmap is 0");
        }

        mGainmapImage = gainmapImage;
        mNativePtr = nativeGainmap;

        final NativeAllocationRegistry registry;
        if (fromMalloc) {
            registry = NativeAllocationRegistry.createMalloced(
                    Bitmap.class.getClassLoader(), nGetFinalizer(), allocationByteCount);
        } else {
            registry = NativeAllocationRegistry.createNonmalloced(
                    Bitmap.class.getClassLoader(), nGetFinalizer(), allocationByteCount);
        }
        registry.registerNativeAllocation(this, nativeGainmap);
    }

    /**
     * Returns the image data of the gainmap represented as a Bitmap
     * @return
     */
    @NonNull
    public Bitmap getGainmapImage() {
        return mGainmapImage;
    }

    /**
     * Sets the gainmap max metadata. For single-plane gainmaps, r, g, and b should be the same.
     */
    @NonNull
    public void setGainmapMax(float r, float g, float b) {
        nSetGainmapMax(mNativePtr, r, g, b);
    }

    /**
     * Gets the gainmap max metadata. For single-plane gainmaps, all 3 components should be the
     * same. The components are in r, g, b order.
     */
    @NonNull
    public float[] getGainmapMax() {
        float[] ret = new float[3];
        nGetGainmapMax(mNativePtr, ret);
        return ret;
    }

    /**
     * Sets the maximum HDR ratio for the gainmap
     */
    @NonNull
    public void setHdrRatioMax(float max) {
        nSetHdrRatioMax(mNativePtr, max);
    }

    /**
     * Gets the maximum HDR ratio for the gainmap
     */
    @NonNull
    public float getHdrRatioMax() {
        return nGetHdrRatioMax(mNativePtr);
    }

    /**
     * Sets the maximum HDR ratio for the gainmap
     */
    @NonNull
    public void setHdrRatioMin(float min) {
        nSetHdrRatioMin(mNativePtr, min);
    }

    /**
     * Gets the maximum HDR ratio for the gainmap
     */
    @NonNull
    public float getHdrRatioMin() {
        return nGetHdrRatioMin(mNativePtr);
    }

    private static native long nGetFinalizer();

    private static native void nSetGainmapMax(long ptr, float r, float g, float b);
    private static native void nGetGainmapMax(long ptr, float[] components);

    private static native void nSetHdrRatioMax(long ptr, float max);
    private static native float nGetHdrRatioMax(long ptr);

    private static native void nSetHdrRatioMin(long ptr, float min);
    private static native float nGetHdrRatioMin(long ptr);
}

/**
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

package android.hardware;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.hardware.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;

import libcore.util.NativeAllocationRegistry;

/**
 * Provides supported overlay properties of the device.
 *
 * <p>
 * Hardware overlay is a technique to composite different buffers directly
 * to the screen using display hardware rather than the GPU.
 * The system compositor is able to assign any content managed by a
 * {@link android.view.SurfaceControl} onto a hardware overlay if possible.
 * Applications may be interested in the display hardware capabilities exposed
 * by this class as a hint to determine if their {@link android.view.SurfaceControl}
 * tree is power-efficient and performant.
 * </p>
 */
@FlaggedApi(Flags.FLAG_OVERLAYPROPERTIES_CLASS_API)
public final class OverlayProperties implements Parcelable {

    private static final NativeAllocationRegistry sRegistry =
            NativeAllocationRegistry.createMalloced(OverlayProperties.class.getClassLoader(),
            nGetDestructor());

    private long mNativeObject;
    // only for virtual displays
    private static OverlayProperties sDefaultOverlayProperties;
    // Invoked on destruction
    private Runnable mCloser;

    private OverlayProperties(long nativeObject) {
        if (nativeObject != 0) {
            mCloser = sRegistry.registerNativeAllocation(this, nativeObject);
        }
        mNativeObject = nativeObject;
    }

    /**
     * For virtual displays, we provide an overlay properties object
     * with RGBA 8888 only, sRGB only, true for mixed color spaces.
     * @hide
     */
    public static OverlayProperties getDefault() {
        if (sDefaultOverlayProperties == null) {
            sDefaultOverlayProperties = new OverlayProperties(nCreateDefault());
        }
        return sDefaultOverlayProperties;
    }

    /**
     * @return True if the device can support fp16, false otherwise.
     * TODO: Move this to isCombinationSupported once the flag flips
     * @hide
     */
    public boolean isFp16SupportedForHdr() {
        if (mNativeObject == 0) {
            return false;
        }
        return nIsCombinationSupported(
                mNativeObject, DataSpace.DATASPACE_SCRGB, HardwareBuffer.RGBA_FP16);
    }

    /**
     * Indicates that hardware composition of a buffer encoded with the provided {@link DataSpace}
     * and {@link HardwareBuffer.Format} is supported on the device.
     *
     * @return True if the device can support efficiently compositing the content described by the
     *         dataspace and format. False if GPOU composition fallback is otherwise required.
     */
    @FlaggedApi(Flags.FLAG_OVERLAYPROPERTIES_CLASS_API)
    public boolean isCombinationSupported(@DataSpace.ColorDataSpace int dataspace,
            @HardwareBuffer.Format int format) {
        if (mNativeObject == 0) {
            return false;
        }

        return nIsCombinationSupported(mNativeObject, dataspace, format);
    }

    /**
     * Indicates that hardware composition of two or more overlays
     * with different colorspaces is supported on the device.
     *
     * @return True if the device can support mixed colorspaces efficiently,
     *         false if GPU composition fallback is otherwise required.
     */
    @FlaggedApi(Flags.FLAG_OVERLAYPROPERTIES_CLASS_API)
    public boolean isMixedColorSpacesSupported() {
        if (mNativeObject == 0) {
            return false;
        }
        return nSupportMixedColorSpaces(mNativeObject);
    }

    @FlaggedApi(Flags.FLAG_OVERLAYPROPERTIES_CLASS_API)
    @Override
    public int describeContents() {
        return 0;
    }

    @FlaggedApi(Flags.FLAG_OVERLAYPROPERTIES_CLASS_API)
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        if (mNativeObject == 0) {
            dest.writeInt(0);
            return;
        }
        dest.writeInt(1);
        nWriteOverlayPropertiesToParcel(mNativeObject, dest);
    }

    @FlaggedApi(Flags.FLAG_OVERLAYPROPERTIES_CLASS_API)
    public static final @NonNull Parcelable.Creator<OverlayProperties> CREATOR =
            new Parcelable.Creator<OverlayProperties>() {
        public OverlayProperties createFromParcel(Parcel in) {
            if (in.readInt() != 0) {
                return new OverlayProperties(nReadOverlayPropertiesFromParcel(in));
            }
            return null;
        }

        public OverlayProperties[] newArray(int size) {
            return new OverlayProperties[size];
        }
    };

    private static native long nGetDestructor();
    private static native long nCreateDefault();
    private static native boolean nSupportFp16ForHdr(long nativeObject);
    private static native boolean nSupportMixedColorSpaces(long nativeObject);
    private static native boolean nIsCombinationSupported(
            long nativeObject, int dataspace, int format);
    private static native void nWriteOverlayPropertiesToParcel(long nativeObject, Parcel dest);
    private static native long nReadOverlayPropertiesFromParcel(Parcel in);
}

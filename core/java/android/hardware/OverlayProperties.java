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

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import libcore.util.NativeAllocationRegistry;

/**
 * The class provides overlay properties of the device. OverlayProperties
 * exposes some capabilities from HWC e.g. if fp16 can be supported for HWUI.
 *
 * In the future, more capabilities can be added, e.g., whether or not
 * per-layer colorspaces are supported.
 *
 * @hide
 */
public final class OverlayProperties implements Parcelable {

    private static final NativeAllocationRegistry sRegistry =
            NativeAllocationRegistry.createMalloced(OverlayProperties.class.getClassLoader(),
            nGetDestructor());

    private long mNativeObject;
    // Invoked on destruction
    private Runnable mCloser;

    public OverlayProperties(long nativeObject) {
        if (nativeObject != 0) {
            mCloser = sRegistry.registerNativeAllocation(this, nativeObject);
        }
        mNativeObject = nativeObject;
    }

    /**
     * @return True if the device can support fp16, false otherwise.
     */
    public boolean supportFp16ForHdr() {
        if (mNativeObject == 0) {
            return false;
        }
        return nSupportFp16ForHdr(mNativeObject);
    }

    /**
     * @return True if the device can support mixed colorspaces, false otherwise.
     */
    public boolean supportMixedColorSpaces() {
        if (mNativeObject == 0) {
            return false;
        }
        return nSupportMixedColorSpaces(mNativeObject);
    }

    /**
     * Release the local reference.
     */
    public void release() {
        if (mNativeObject != 0) {
            mCloser.run();
            mNativeObject = 0;
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Flatten this object in to a Parcel.
     *
     * @param dest The Parcel in which the object should be written.
     * @param flags Additional flags about how the object should be written.
     *              May be 0 or {@link #PARCELABLE_WRITE_RETURN_VALUE}.
     */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        if (mNativeObject == 0) {
            dest.writeInt(0);
            return;
        }
        dest.writeInt(1);
        nWriteOverlayPropertiesToParcel(mNativeObject, dest);
    }

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
    private static native boolean nSupportFp16ForHdr(long nativeObject);
    private static native boolean nSupportMixedColorSpaces(long nativeObject);
    private static native void nWriteOverlayPropertiesToParcel(long nativeObject, Parcel dest);
    private static native long nReadOverlayPropertiesFromParcel(Parcel in);
}

/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.graphics.SurfaceTexture;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.Surface;

/**
 *
 * @hide Pending review by API council.
 */
public final class ParcelSurfaceTexture implements Parcelable {
    /**
     * This field is used by native code, do not access or modify.
     *
     * @hide
     */
    @SuppressWarnings({"UnusedDeclaration"})
    private int mISurfaceTexture;

    /**
     * Create a new ParcelSurfaceTexture from a Surface
     *
     * @param surface The Surface to create a ParcelSurfaceTexture from.
     *
     * @return Returns a new ParcelSurfaceTexture for the given Surface.
     */
    public static ParcelSurfaceTexture fromSurface(Surface surface) {
        return new ParcelSurfaceTexture(surface);
    }

    /**
     * Create a new ParcelSurfaceTexture from a SurfaceTexture
     *
     * @param surfaceTexture The SurfaceTexture to transport.
     *
     * @return Returns a new ParcelSurfaceTexture for the given SurfaceTexture.
     */
    public static ParcelSurfaceTexture fromSurfaceTexture(SurfaceTexture surfaceTexture) {
        return new ParcelSurfaceTexture(surfaceTexture);
    }

    /**
     * @see android.os.Parcelable#describeContents()
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * @see android.os.Parcelable#writeToParcel(android.os.Parcel, int)
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        nativeWriteToParcel(dest, flags);
    }

    public static final Parcelable.Creator<ParcelSurfaceTexture> CREATOR =
        new Parcelable.Creator<ParcelSurfaceTexture>() {
        @Override
        public ParcelSurfaceTexture createFromParcel(Parcel in) {
            return new ParcelSurfaceTexture(in);
        }
        @Override
        public ParcelSurfaceTexture[] newArray(int size) {
            return new ParcelSurfaceTexture[size];
        }
    };

    private ParcelSurfaceTexture(Parcel in) {
        nativeReadFromParcel(in);
    }
    private ParcelSurfaceTexture(Surface surface) {
        nativeInitFromSurface(surface);
    }
    private ParcelSurfaceTexture(SurfaceTexture surfaceTexture) {
        nativeInitFromSurfaceTexture(surfaceTexture);
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            nativeFinalize();
        } finally {
            super.finalize();
        }
    }

    private native void nativeInitFromSurface(Surface surface);
    private native void nativeInitFromSurfaceTexture(SurfaceTexture surfaceTexture);
    private native void nativeFinalize();
    private native void nativeWriteToParcel(Parcel dest, int flags);
    private native void nativeReadFromParcel(Parcel in);

    /*
     * We use a class initializer to allow the native code to cache some
     * field offsets.
     */
    private static native void nativeClassInit();
    static { nativeClassInit(); }
}

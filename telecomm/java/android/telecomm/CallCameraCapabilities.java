/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package android.telecomm;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Represents the camera capabilities important to a Video Telephony provider.
 * TODO: Add camera capabilities as required.
 */
public final class CallCameraCapabilities implements Parcelable {

    /**
     * Whether the camera supports zoom.
     */
    private final boolean mZoomSupported;

    /**
     * The maximum zoom supported by the camera.
     */
    private final float mMaxZoom;

    /**
     * Create a call camera capabilities instance.
     *
     * @param zoomSupported True when camera supports zoom.
     * @param maxZoom Maximum zoom supported by camera.
     */
    public CallCameraCapabilities(boolean zoomSupported, float maxZoom) {
        mZoomSupported = zoomSupported;
        mMaxZoom = maxZoom;
    }

    /**
     * Responsible for creating CallCameraCapabilities objects from deserialized Parcels.
     **/
    public static final Parcelable.Creator<CallCameraCapabilities> CREATOR =
            new Parcelable.Creator<CallCameraCapabilities> () {
                /**
                 * Creates a CallCameraCapabilities instances from a parcel.
                 *
                 * @param source The parcel.
                 * @return The CallCameraCapabilities.
                 */
                @Override
                public CallCameraCapabilities createFromParcel(Parcel source) {
                    boolean supportsZoom = source.readByte() != 0;
                    float maxZoom = source.readFloat();

                    return new CallCameraCapabilities(supportsZoom, maxZoom);
                }

                @Override
                public CallCameraCapabilities[] newArray(int size) {
                    return new CallCameraCapabilities[size];
                }
            };

    /**
     * Describe the kinds of special objects contained in this Parcelable's
     * marshalled representation.
     *
     * @return a bitmask indicating the set of special object types marshalled
     * by the Parcelable.
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Flatten this object in to a Parcel.
     *
     * @param dest  The Parcel in which the object should be written.
     * @param flags Additional flags about how the object should be written.
     *              May be 0 or {@link #PARCELABLE_WRITE_RETURN_VALUE}.
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByte((byte) (isZoomSupported() ? 1 : 0));
        dest.writeFloat(getMaxZoom());

    }

    /**
     * Whether the camera supports zoom.
     */
    public boolean isZoomSupported() {
        return mZoomSupported;
    }

    /**
     * The maximum zoom supported by the camera.
     */
    public float getMaxZoom() {
        return mMaxZoom;
    }
}

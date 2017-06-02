/*
 * Copyright (C) 2017 The Android Open Source Project
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
package android.net;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * This class is used to return an SPI and corresponding status from the IpSecService to an
 * IpSecManager.SecurityParameterIndex.
 *
 * @hide
 */
public final class IpSecSpiResponse implements Parcelable {
    private static final String TAG = "IpSecSpiResponse";

    public final int resourceId;
    public final int status;
    public final int spi;
    // Parcelable Methods

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(status);
        out.writeInt(resourceId);
        out.writeInt(spi);
    }

    public IpSecSpiResponse(int inStatus, int inResourceId, int inSpi) {
        status = inStatus;
        resourceId = inResourceId;
        spi = inSpi;
    }

    public IpSecSpiResponse(int inStatus) {
        if (inStatus == IpSecManager.Status.OK) {
            throw new IllegalArgumentException("Valid status implies other args must be provided");
        }
        status = inStatus;
        resourceId = IpSecManager.INVALID_RESOURCE_ID;
        spi = IpSecManager.INVALID_SECURITY_PARAMETER_INDEX;
    }

    private IpSecSpiResponse(Parcel in) {
        status = in.readInt();
        resourceId = in.readInt();
        spi = in.readInt();
    }

    public static final Parcelable.Creator<IpSecSpiResponse> CREATOR =
            new Parcelable.Creator<IpSecSpiResponse>() {
                public IpSecSpiResponse createFromParcel(Parcel in) {
                    return new IpSecSpiResponse(in);
                }

                public IpSecSpiResponse[] newArray(int size) {
                    return new IpSecSpiResponse[size];
                }
            };
}

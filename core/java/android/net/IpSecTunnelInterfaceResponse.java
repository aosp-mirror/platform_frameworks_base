/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * This class is used to return an IpSecTunnelInterface resource Id and and corresponding status
 * from the IpSecService to an IpSecTunnelInterface object.
 *
 * @hide
 */
public final class IpSecTunnelInterfaceResponse implements Parcelable {
    private static final String TAG = "IpSecTunnelInterfaceResponse";

    public final int resourceId;
    public final String interfaceName;
    public final int status;
    // Parcelable Methods

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(status);
        out.writeInt(resourceId);
        out.writeString(interfaceName);
    }

    public IpSecTunnelInterfaceResponse(int inStatus) {
        if (inStatus == IpSecManager.Status.OK) {
            throw new IllegalArgumentException("Valid status implies other args must be provided");
        }
        status = inStatus;
        resourceId = IpSecManager.INVALID_RESOURCE_ID;
        interfaceName = "";
    }

    public IpSecTunnelInterfaceResponse(int inStatus, int inResourceId, String inInterfaceName) {
        status = inStatus;
        resourceId = inResourceId;
        interfaceName = inInterfaceName;
    }

    private IpSecTunnelInterfaceResponse(Parcel in) {
        status = in.readInt();
        resourceId = in.readInt();
        interfaceName = in.readString();
    }

    public static final Parcelable.Creator<IpSecTunnelInterfaceResponse> CREATOR =
            new Parcelable.Creator<IpSecTunnelInterfaceResponse>() {
                public IpSecTunnelInterfaceResponse createFromParcel(Parcel in) {
                    return new IpSecTunnelInterfaceResponse(in);
                }

                public IpSecTunnelInterfaceResponse[] newArray(int size) {
                    return new IpSecTunnelInterfaceResponse[size];
                }
            };
}

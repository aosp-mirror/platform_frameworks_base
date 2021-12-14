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
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import java.io.FileDescriptor;
import java.io.IOException;

/**
 * This class is used to return a UDP Socket and corresponding status from the IpSecService to an
 * IpSecManager.UdpEncapsulationSocket.
 *
 * @hide
 */
public final class IpSecUdpEncapResponse implements Parcelable {
    private static final String TAG = "IpSecUdpEncapResponse";

    public final int resourceId;
    public final int port;
    public final int status;
    // There is a weird asymmetry with FileDescriptor: you can write a FileDescriptor
    // but you read a ParcelFileDescriptor. To circumvent this, when we receive a FD
    // from the user, we immediately create a ParcelFileDescriptor DUP, which we invalidate
    // on writeParcel() by setting the flag to do close-on-write.
    // TODO: tests to ensure this doesn't leak
    public final ParcelFileDescriptor fileDescriptor;

    // Parcelable Methods

    @Override
    public int describeContents() {
        return (fileDescriptor != null) ? Parcelable.CONTENTS_FILE_DESCRIPTOR : 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(status);
        out.writeInt(resourceId);
        out.writeInt(port);
        out.writeParcelable(fileDescriptor, Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
    }

    public IpSecUdpEncapResponse(int inStatus) {
        if (inStatus == IpSecManager.Status.OK) {
            throw new IllegalArgumentException("Valid status implies other args must be provided");
        }
        status = inStatus;
        resourceId = IpSecManager.INVALID_RESOURCE_ID;
        port = -1;
        fileDescriptor = null; // yes I know it's redundant, but readability
    }

    public IpSecUdpEncapResponse(int inStatus, int inResourceId, int inPort, FileDescriptor inFd)
            throws IOException {
        if (inStatus == IpSecManager.Status.OK && inFd == null) {
            throw new IllegalArgumentException("Valid status implies FD must be non-null");
        }
        status = inStatus;
        resourceId = inResourceId;
        port = inPort;
        fileDescriptor = (status == IpSecManager.Status.OK) ? ParcelFileDescriptor.dup(inFd) : null;
    }

    private IpSecUdpEncapResponse(Parcel in) {
        status = in.readInt();
        resourceId = in.readInt();
        port = in.readInt();
        fileDescriptor = in.readParcelable(ParcelFileDescriptor.class.getClassLoader());
    }

    public static final @android.annotation.NonNull Parcelable.Creator<IpSecUdpEncapResponse> CREATOR =
            new Parcelable.Creator<IpSecUdpEncapResponse>() {
                public IpSecUdpEncapResponse createFromParcel(Parcel in) {
                    return new IpSecUdpEncapResponse(in);
                }

                public IpSecUdpEncapResponse[] newArray(int size) {
                    return new IpSecUdpEncapResponse[size];
                }
            };
}

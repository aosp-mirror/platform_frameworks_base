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
 * This class is used to return an IpSecTransform resource Id and and corresponding status from the
 * IpSecService to an IpSecTransform object.
 *
 * @hide
 */
public final class IpSecTransformResponse implements Parcelable {
    private static final String TAG = "IpSecTransformResponse";

    public final int resourceId;
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
    }

    public IpSecTransformResponse(int inStatus) {
        if (inStatus == IpSecManager.Status.OK) {
            throw new IllegalArgumentException("Valid status implies other args must be provided");
        }
        status = inStatus;
        resourceId = IpSecManager.INVALID_RESOURCE_ID;
    }

    public IpSecTransformResponse(int inStatus, int inResourceId) {
        status = inStatus;
        resourceId = inResourceId;
    }

    private IpSecTransformResponse(Parcel in) {
        status = in.readInt();
        resourceId = in.readInt();
    }

    public static final @android.annotation.NonNull Parcelable.Creator<IpSecTransformResponse> CREATOR =
            new Parcelable.Creator<IpSecTransformResponse>() {
                public IpSecTransformResponse createFromParcel(Parcel in) {
                    return new IpSecTransformResponse(in);
                }

                public IpSecTransformResponse[] newArray(int size) {
                    return new IpSecTransformResponse[size];
                }
            };
}

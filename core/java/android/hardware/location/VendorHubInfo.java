/*
 * Copyright (C) 2024 The Android Open Source Project
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
package android.hardware.location;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.chre.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.ParcelableHolder;

/**
 * Information about a VendorHub. VendorHub is similar to ContextHub, but it does not run the
 * Context Hub Runtime Environment (or nano apps). It provides a unified endpoint messaging API
 * through the ContextHub V4 HAL.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_OFFLOAD_API)
public final class VendorHubInfo implements Parcelable {
    private final String mName;
    private final int mVersion;
    private final ParcelableHolder mExtendedInfo;

    /** @hide */
    public VendorHubInfo(android.hardware.contexthub.VendorHubInfo halHubInfo) {
        mName = halHubInfo.name;
        mVersion = halHubInfo.version;
        mExtendedInfo = halHubInfo.extendedInfo;
    }

    private VendorHubInfo(Parcel in) {
        mName = in.readString();
        mVersion = in.readInt();
        mExtendedInfo = ParcelableHolder.CREATOR.createFromParcel(in);
    }

    /** Get the hub name */
    @NonNull
    public String getName() {
        return mName;
    }

    /** Get the hub version */
    public int getVersion() {
        return mVersion;
    }

    /** Parcel implementation details */
    public int describeContents() {
        return mExtendedInfo.describeContents();
    }

    /** Parcel implementation details */
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeString(mName);
        out.writeInt(mVersion);
        mExtendedInfo.writeToParcel(out, flags);
    }

    @NonNull
    @Override
    public String toString() {
        StringBuilder out = new StringBuilder();
        out.append("VendorHub Name : ");
        out.append(mName);
        out.append(", Version : ");
        out.append(mVersion);
        return out.toString();
    }

    public static final @NonNull Creator<VendorHubInfo> CREATOR =
            new Creator<>() {
                public VendorHubInfo createFromParcel(Parcel in) {
                    return new VendorHubInfo(in);
                }

                public VendorHubInfo[] newArray(int size) {
                    return new VendorHubInfo[size];
                }
            };
}

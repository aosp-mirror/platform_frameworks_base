/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;

/**
 * A lightweight container used to carry information of the ongoing VPN.
 * Internal use only.
 *
 * @hide
 */
public class VpnInfo implements Parcelable {
    public final int ownerUid;
    @Nullable
    public final String vpnIface;
    @Nullable
    public final String[] underlyingIfaces;

    public VpnInfo(int ownerUid, @Nullable String vpnIface, @Nullable String[] underlyingIfaces) {
        this.ownerUid = ownerUid;
        this.vpnIface = vpnIface;
        this.underlyingIfaces = underlyingIfaces;
    }

    private VpnInfo(@NonNull Parcel in) {
        this.ownerUid = in.readInt();
        this.vpnIface = in.readString();
        this.underlyingIfaces = in.createStringArray();
    }

    @Override
    public String toString() {
        return "VpnInfo{"
                + "ownerUid=" + ownerUid
                + ", vpnIface='" + vpnIface + '\''
                + ", underlyingIfaces='" + Arrays.toString(underlyingIfaces) + '\''
                + '}';
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(ownerUid);
        dest.writeString(vpnIface);
        dest.writeStringArray(underlyingIfaces);
    }

    @NonNull
    public static final Parcelable.Creator<VpnInfo> CREATOR = new Parcelable.Creator<VpnInfo>() {
        @NonNull
        @Override
        public VpnInfo createFromParcel(@NonNull Parcel in) {
            return new VpnInfo(in);
        }

        @NonNull
        @Override
        public VpnInfo[] newArray(int size) {
            return new VpnInfo[size];
        }
    };
}

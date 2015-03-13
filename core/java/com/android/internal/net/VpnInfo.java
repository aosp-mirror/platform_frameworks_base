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
 * limitations under the License
 */

package com.android.internal.net;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * A lightweight container used to carry information of the ongoing VPN.
 * Internal use only..
 *
 * @hide
 */
public class VpnInfo implements Parcelable {
    public int ownerUid;
    public String vpnIface;
    public String primaryUnderlyingIface;

    @Override
    public String toString() {
        return "VpnInfo{" +
                "ownerUid=" + ownerUid +
                ", vpnIface='" + vpnIface + '\'' +
                ", primaryUnderlyingIface='" + primaryUnderlyingIface + '\'' +
                '}';
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(ownerUid);
        dest.writeString(vpnIface);
        dest.writeString(primaryUnderlyingIface);
    }

    public static final Parcelable.Creator<VpnInfo> CREATOR = new Parcelable.Creator<VpnInfo>() {
        @Override
        public VpnInfo createFromParcel(Parcel source) {
            VpnInfo info = new VpnInfo();
            info.ownerUid = source.readInt();
            info.vpnIface = source.readString();
            info.primaryUnderlyingIface = source.readString();
            return info;
        }

        @Override
        public VpnInfo[] newArray(int size) {
            return new VpnInfo[size];
        }
    };
}

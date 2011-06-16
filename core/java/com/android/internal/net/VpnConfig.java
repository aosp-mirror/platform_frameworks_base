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

package com.android.internal.net;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * A simple container used to carry information in VpnBuilder, VpnDialogs,
 * and com.android.server.connectivity.Vpn. Internal use only.
 *
 * @hide
 */
public class VpnConfig implements Parcelable {

    public String packageName;
    public String sessionName;
    public String interfaceName;
    public String configureActivity;
    public int mtu = -1;
    public String addresses;
    public String routes;
    public String dnsServers;
    public long startTime = -1;

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(packageName);
        out.writeString(sessionName);
        out.writeString(interfaceName);
        out.writeString(configureActivity);
        out.writeInt(mtu);
        out.writeString(addresses);
        out.writeString(routes);
        out.writeString(dnsServers);
        out.writeLong(startTime);
    }

    public static final Parcelable.Creator<VpnConfig> CREATOR =
            new Parcelable.Creator<VpnConfig>() {
        @Override
        public VpnConfig createFromParcel(Parcel in) {
            VpnConfig config = new VpnConfig();
            config.packageName = in.readString();
            config.sessionName = in.readString();
            config.interfaceName = in.readString();
            config.configureActivity = in.readString();
            config.mtu = in.readInt();
            config.addresses = in.readString();
            config.routes = in.readString();
            config.dnsServers = in.readString();
            config.startTime = in.readLong();
            return config;
        }

        @Override
        public VpnConfig[] newArray(int size) {
            return new VpnConfig[size];
        }
    };
}

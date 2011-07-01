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

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;

import java.util.List;

/**
 * A simple container used to carry information in VpnBuilder, VpnDialogs,
 * and com.android.server.connectivity.Vpn. Internal use only.
 *
 * @hide
 */
public class VpnConfig implements Parcelable {

    public static final String ACTION_VPN_REVOKED = "android.net.vpn.action.REVOKED";

    public static void enforceCallingPackage(String packageName) {
        if (!"com.android.vpndialogs".equals(packageName)) {
            throw new SecurityException("Unauthorized Caller");
        }
    }

    public static Intent getIntentForConfirmation() {
        Intent intent = new Intent();
        intent.setClassName("com.android.vpndialogs", "com.android.vpndialogs.ConfirmDialog");
        return intent;
    }

    public static PendingIntent getIntentForNotification(Context context, VpnConfig config) {
        config.startTime = SystemClock.elapsedRealtime();
        Intent intent = new Intent();
        intent.setClassName("com.android.vpndialogs", "com.android.vpndialogs.ManageDialog");
        intent.putExtra("config", config);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_HISTORY |
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT);
    }

    public String packageName;
    public String sessionName;
    public String interfaceName;
    public PendingIntent configureIntent;
    public int mtu = -1;
    public String addresses;
    public String routes;
    public List<String> dnsServers;
    public List<String> searchDomains;
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
        out.writeParcelable(configureIntent, flags);
        out.writeInt(mtu);
        out.writeString(addresses);
        out.writeString(routes);
        out.writeStringList(dnsServers);
        out.writeStringList(searchDomains);
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
            config.configureIntent = in.readParcelable(null);
            config.mtu = in.readInt();
            config.addresses = in.readString();
            config.routes = in.readString();
            config.dnsServers = in.createStringArrayList();
            config.searchDomains = in.createStringArrayList();
            config.startTime = in.readLong();
            return config;
        }

        @Override
        public VpnConfig[] newArray(int size) {
            return new VpnConfig[size];
        }
    };
}

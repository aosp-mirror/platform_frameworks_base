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
import android.text.TextUtils;
import android.util.Log;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/**
 * Parcel-like entity class for VPN profiles. To keep things simple, all
 * fields are package private. Methods are provided for serialization, so
 * storage can be implemented easily. Two rules are set for this class.
 * First, all fields must be kept non-null. Second, always make a copy
 * using clone() before modifying.
 *
 * @hide
 */
public class VpnProfile implements Cloneable, Parcelable {
    private static final String TAG = "VpnProfile";

    // Match these constants with R.array.vpn_types.
    public static final int TYPE_PPTP = 0;
    public static final int TYPE_L2TP_IPSEC_PSK = 1;
    public static final int TYPE_L2TP_IPSEC_RSA = 2;
    public static final int TYPE_IPSEC_XAUTH_PSK = 3;
    public static final int TYPE_IPSEC_XAUTH_RSA = 4;
    public static final int TYPE_IPSEC_HYBRID_RSA = 5;
    public static final int TYPE_MAX = 5;

    // Entity fields.
    public final String key;           // -1
    public String name = "";           // 0
    public int type = TYPE_PPTP;       // 1
    public String server = "";         // 2
    public String username = "";       // 3
    public String password = "";       // 4
    public String dnsServers = "";     // 5
    public String searchDomains = "";  // 6
    public String routes = "";         // 7
    public boolean mppe = true;        // 8
    public String l2tpSecret = "";     // 9
    public String ipsecIdentifier = "";// 10
    public String ipsecSecret = "";    // 11
    public String ipsecUserCert = "";  // 12
    public String ipsecCaCert = "";    // 13
    public String ipsecServerCert = "";// 14

    // Helper fields.
    public boolean saveLogin = false;

    public VpnProfile(String key) {
        this.key = key;
    }

    public VpnProfile(Parcel in) {
        key = in.readString();
        name = in.readString();
        type = in.readInt();
        server = in.readString();
        username = in.readString();
        password = in.readString();
        dnsServers = in.readString();
        searchDomains = in.readString();
        routes = in.readString();
        mppe = in.readInt() != 0;
        l2tpSecret = in.readString();
        ipsecIdentifier = in.readString();
        ipsecSecret = in.readString();
        ipsecUserCert = in.readString();
        ipsecCaCert = in.readString();
        ipsecServerCert = in.readString();
        saveLogin = in.readInt() != 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(key);
        out.writeString(name);
        out.writeInt(type);
        out.writeString(server);
        out.writeString(username);
        out.writeString(password);
        out.writeString(dnsServers);
        out.writeString(searchDomains);
        out.writeString(routes);
        out.writeInt(mppe ? 1 : 0);
        out.writeString(l2tpSecret);
        out.writeString(ipsecIdentifier);
        out.writeString(ipsecSecret);
        out.writeString(ipsecUserCert);
        out.writeString(ipsecCaCert);
        out.writeString(ipsecServerCert);
        out.writeInt(saveLogin ? 1 : 0);
    }

    public static VpnProfile decode(String key, byte[] value) {
        try {
            if (key == null) {
                return null;
            }

            String[] values = new String(value, StandardCharsets.UTF_8).split("\0", -1);
            // There can be 14 or 15 values in ICS MR1.
            if (values.length < 14 || values.length > 15) {
                return null;
            }

            VpnProfile profile = new VpnProfile(key);
            profile.name = values[0];
            profile.type = Integer.valueOf(values[1]);
            if (profile.type < 0 || profile.type > TYPE_MAX) {
                return null;
            }
            profile.server = values[2];
            profile.username = values[3];
            profile.password = values[4];
            profile.dnsServers = values[5];
            profile.searchDomains = values[6];
            profile.routes = values[7];
            profile.mppe = Boolean.valueOf(values[8]);
            profile.l2tpSecret = values[9];
            profile.ipsecIdentifier = values[10];
            profile.ipsecSecret = values[11];
            profile.ipsecUserCert = values[12];
            profile.ipsecCaCert = values[13];
            profile.ipsecServerCert = (values.length > 14) ? values[14] : "";

            profile.saveLogin = !profile.username.isEmpty() || !profile.password.isEmpty();
            return profile;
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    public byte[] encode() {
        StringBuilder builder = new StringBuilder(name);
        builder.append('\0').append(type);
        builder.append('\0').append(server);
        builder.append('\0').append(saveLogin ? username : "");
        builder.append('\0').append(saveLogin ? password : "");
        builder.append('\0').append(dnsServers);
        builder.append('\0').append(searchDomains);
        builder.append('\0').append(routes);
        builder.append('\0').append(mppe);
        builder.append('\0').append(l2tpSecret);
        builder.append('\0').append(ipsecIdentifier);
        builder.append('\0').append(ipsecSecret);
        builder.append('\0').append(ipsecUserCert);
        builder.append('\0').append(ipsecCaCert);
        builder.append('\0').append(ipsecServerCert);
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Test if profile is valid for lockdown, which requires IPv4 address for
     * both server and DNS. Server hostnames would require using DNS before
     * connection.
     */
    public boolean isValidLockdownProfile() {
        try {
            InetAddress.parseNumericAddress(server);

            for (String dnsServer : dnsServers.split(" +")) {
                InetAddress.parseNumericAddress(this.dnsServers);
            }
            if (TextUtils.isEmpty(dnsServers)) {
                Log.w(TAG, "DNS required");
                return false;
            }

            // Everything checked out above
            return true;

        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Invalid address", e);
            return false;
        }
    }

    public static final Creator<VpnProfile> CREATOR = new Creator<VpnProfile>() {
        @Override
        public VpnProfile createFromParcel(Parcel in) {
            return new VpnProfile(in);
        }

        @Override
        public VpnProfile[] newArray(int size) {
            return new VpnProfile[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }
}

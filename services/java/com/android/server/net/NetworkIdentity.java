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

package com.android.server.net;

import static android.net.ConnectivityManager.TYPE_WIFI;
import static android.net.ConnectivityManager.TYPE_WIMAX;
import static android.net.ConnectivityManager.isNetworkTypeMobile;
import static android.net.TrafficStats.TEMPLATE_MOBILE_3G_LOWER;
import static android.net.TrafficStats.TEMPLATE_MOBILE_4G;
import static android.net.TrafficStats.TEMPLATE_MOBILE_ALL;
import static android.net.TrafficStats.TEMPLATE_WIFI;
import static android.telephony.TelephonyManager.NETWORK_CLASS_2_G;
import static android.telephony.TelephonyManager.NETWORK_CLASS_3_G;
import static android.telephony.TelephonyManager.NETWORK_CLASS_4_G;
import static android.telephony.TelephonyManager.getNetworkClass;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.NetworkState;
import android.telephony.TelephonyManager;

import com.android.internal.util.Objects;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ProtocolException;

/**
 * Identity of a {@link NetworkInfo}, defined by network type and billing
 * relationship (such as IMSI).
 *
 * @hide
 */
public class NetworkIdentity {
    private static final int VERSION_CURRENT = 1;

    public final int type;
    public final int subType;
    public final String subscriberId;

    public NetworkIdentity(int type, int subType, String subscriberId) {
        this.type = type;
        this.subType = subType;
        this.subscriberId = subscriberId;
    }

    public NetworkIdentity(DataInputStream in) throws IOException {
        final int version = in.readInt();
        switch (version) {
            case VERSION_CURRENT: {
                type = in.readInt();
                subType = in.readInt();
                subscriberId = in.readUTF();
                break;
            }
            default: {
                throw new ProtocolException("unexpected version: " + version);
            }
        }
    }

    public void writeToStream(DataOutputStream out) throws IOException {
        out.writeInt(VERSION_CURRENT);
        out.writeInt(type);
        out.writeInt(subType);
        out.writeUTF(subscriberId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(type, subType, subscriberId);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof NetworkIdentity) {
            final NetworkIdentity ident = (NetworkIdentity) obj;
            return type == ident.type && subType == ident.subType
                    && Objects.equal(subscriberId, ident.subscriberId);
        }
        return false;
    }

    @Override
    public String toString() {
        final String typeName = ConnectivityManager.getNetworkTypeName(type);
        final String subTypeName;
        if (ConnectivityManager.isNetworkTypeMobile(type)) {
            subTypeName = TelephonyManager.getNetworkTypeName(subType);
        } else {
            subTypeName = Integer.toString(subType);
        }

        return "[type=" + typeName + ", subType=" + subTypeName + ", subId=" + subscriberId + "]";
    }

    /**
     * Test if this network matches the given template and IMEI.
     */
    public boolean matchesTemplate(int networkTemplate, String subscriberId) {
        switch (networkTemplate) {
            case TEMPLATE_MOBILE_ALL:
                return matchesMobile(subscriberId);
            case TEMPLATE_MOBILE_3G_LOWER:
                return matchesMobile3gLower(subscriberId);
            case TEMPLATE_MOBILE_4G:
                return matchesMobile4g(subscriberId);
            case TEMPLATE_WIFI:
                return matchesWifi();
            default:
                throw new IllegalArgumentException("unknown network template");
        }
    }

    /**
     * Check if mobile network with matching IMEI. Also matches
     * {@link #TYPE_WIMAX}.
     */
    private boolean matchesMobile(String subscriberId) {
        if (isNetworkTypeMobile(type) && Objects.equal(this.subscriberId, subscriberId)) {
            return true;
        } else if (type == TYPE_WIMAX) {
            return true;
        }
        return false;
    }

    /**
     * Check if mobile network classified 3G or lower with matching IMEI.
     */
    private boolean matchesMobile3gLower(String subscriberId) {
        if (isNetworkTypeMobile(type)
                && Objects.equal(this.subscriberId, subscriberId)) {
            switch (getNetworkClass(subType)) {
                case NETWORK_CLASS_2_G:
                case NETWORK_CLASS_3_G:
                    return true;
            }
        }
        return false;
    }

    /**
     * Check if mobile network classified 4G with matching IMEI. Also matches
     * {@link #TYPE_WIMAX}.
     */
    private boolean matchesMobile4g(String subscriberId) {
        if (isNetworkTypeMobile(type)
                && Objects.equal(this.subscriberId, subscriberId)) {
            switch (getNetworkClass(subType)) {
                case NETWORK_CLASS_4_G:
                    return true;
            }
        } else if (type == TYPE_WIMAX) {
            return true;
        }
        return false;
    }

    /**
     * Check if matches Wi-Fi network template.
     */
    private boolean matchesWifi() {
        if (type == TYPE_WIFI) {
            return true;
        }
        return false;
    }

    /**
     * Build a {@link NetworkIdentity} from the given {@link NetworkState},
     * assuming that any mobile networks are using the current IMSI.
     */
    public static NetworkIdentity buildNetworkIdentity(Context context, NetworkState state) {
        final int type = state.networkInfo.getType();
        final int subType = state.networkInfo.getSubtype();

        // TODO: consider moving subscriberId over to LinkCapabilities, so it
        // comes from an authoritative source.

        final String subscriberId;
        if (isNetworkTypeMobile(type)) {
            final TelephonyManager telephony = (TelephonyManager) context.getSystemService(
                    Context.TELEPHONY_SERVICE);
            subscriberId = telephony.getSubscriberId();
        } else {
            subscriberId = null;
        }
        return new NetworkIdentity(type, subType, subscriberId);
    }

}

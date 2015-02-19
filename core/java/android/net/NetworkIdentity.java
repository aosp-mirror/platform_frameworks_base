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

package android.net;

import static android.net.ConnectivityManager.TYPE_WIFI;
import static android.net.ConnectivityManager.getNetworkTypeName;
import static android.net.ConnectivityManager.isNetworkTypeMobile;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.telephony.TelephonyManager;
import android.util.Slog;

import java.util.Objects;

/**
 * Network definition that includes strong identity. Analogous to combining
 * {@link NetworkInfo} and an IMSI.
 *
 * @hide
 */
public class NetworkIdentity implements Comparable<NetworkIdentity> {
    private static final String TAG = "NetworkIdentity";

    /**
     * When enabled, combine all {@link #mSubType} together under
     * {@link #SUBTYPE_COMBINED}.
     *
     * @deprecated we no longer offer to collect statistics on a per-subtype
     *             basis; this is always disabled.
     */
    @Deprecated
    public static final boolean COMBINE_SUBTYPE_ENABLED = true;

    public static final int SUBTYPE_COMBINED = -1;

    final int mType;
    final int mSubType;
    final String mSubscriberId;
    final String mNetworkId;
    final boolean mRoaming;

    public NetworkIdentity(
            int type, int subType, String subscriberId, String networkId, boolean roaming) {
        mType = type;
        mSubType = COMBINE_SUBTYPE_ENABLED ? SUBTYPE_COMBINED : subType;
        mSubscriberId = subscriberId;
        mNetworkId = networkId;
        mRoaming = roaming;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mType, mSubType, mSubscriberId, mNetworkId, mRoaming);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof NetworkIdentity) {
            final NetworkIdentity ident = (NetworkIdentity) obj;
            return mType == ident.mType && mSubType == ident.mSubType && mRoaming == ident.mRoaming
                    && Objects.equals(mSubscriberId, ident.mSubscriberId)
                    && Objects.equals(mNetworkId, ident.mNetworkId);
        }
        return false;
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder("{");
        builder.append("type=").append(getNetworkTypeName(mType));
        builder.append(", subType=");
        if (COMBINE_SUBTYPE_ENABLED) {
            builder.append("COMBINED");
        } else if (ConnectivityManager.isNetworkTypeMobile(mType)) {
            builder.append(TelephonyManager.getNetworkTypeName(mSubType));
        } else {
            builder.append(mSubType);
        }
        if (mSubscriberId != null) {
            builder.append(", subscriberId=").append(scrubSubscriberId(mSubscriberId));
        }
        if (mNetworkId != null) {
            builder.append(", networkId=").append(mNetworkId);
        }
        if (mRoaming) {
            builder.append(", ROAMING");
        }
        return builder.append("}").toString();
    }

    public int getType() {
        return mType;
    }

    public int getSubType() {
        return mSubType;
    }

    public String getSubscriberId() {
        return mSubscriberId;
    }

    public String getNetworkId() {
        return mNetworkId;
    }

    public boolean getRoaming() {
        return mRoaming;
    }

    /**
     * Scrub given IMSI on production builds.
     */
    public static String scrubSubscriberId(String subscriberId) {
        if ("eng".equals(Build.TYPE)) {
            return subscriberId;
        } else if (subscriberId != null) {
            // TODO: parse this as MCC+MNC instead of hard-coding
            return subscriberId.substring(0, Math.min(6, subscriberId.length())) + "...";
        } else {
            return "null";
        }
    }

    /**
     * Scrub given IMSI on production builds.
     */
    public static String[] scrubSubscriberId(String[] subscriberId) {
        if (subscriberId == null) return null;
        final String[] res = new String[subscriberId.length];
        for (int i = 0; i < res.length; i++) {
            res[i] = NetworkIdentity.scrubSubscriberId(subscriberId[i]);
        }
        return res;
    }

    /**
     * Build a {@link NetworkIdentity} from the given {@link NetworkState},
     * assuming that any mobile networks are using the current IMSI.
     */
    public static NetworkIdentity buildNetworkIdentity(Context context, NetworkState state) {
        final int type = state.networkInfo.getType();
        final int subType = state.networkInfo.getSubtype();

        String subscriberId = null;
        String networkId = null;
        boolean roaming = false;

        if (isNetworkTypeMobile(type)) {
            if (state.subscriberId == null) {
                Slog.w(TAG, "Active mobile network without subscriber!");
            }

            subscriberId = state.subscriberId;
            roaming = state.networkInfo.isRoaming();

        } else if (type == TYPE_WIFI) {
            if (state.networkId != null) {
                networkId = state.networkId;
            } else {
                final WifiManager wifi = (WifiManager) context.getSystemService(
                        Context.WIFI_SERVICE);
                final WifiInfo info = wifi.getConnectionInfo();
                networkId = info != null ? info.getSSID() : null;
            }
        }

        return new NetworkIdentity(type, subType, subscriberId, networkId, roaming);
    }

    @Override
    public int compareTo(NetworkIdentity another) {
        int res = Integer.compare(mType, another.mType);
        if (res == 0) {
            res = Integer.compare(mSubType, another.mSubType);
        }
        if (res == 0 && mSubscriberId != null && another.mSubscriberId != null) {
            res = mSubscriberId.compareTo(another.mSubscriberId);
        }
        if (res == 0 && mNetworkId != null && another.mNetworkId != null) {
            res = mNetworkId.compareTo(another.mNetworkId);
        }
        if (res == 0) {
            res = Boolean.compare(mRoaming, another.mRoaming);
        }
        return res;
    }
}

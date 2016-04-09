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

import static android.net.ConnectivityManager.TYPE_BLUETOOTH;
import static android.net.ConnectivityManager.TYPE_ETHERNET;
import static android.net.ConnectivityManager.TYPE_PROXY;
import static android.net.ConnectivityManager.TYPE_MOBILE;
import static android.net.ConnectivityManager.TYPE_WIFI;
import static android.net.ConnectivityManager.TYPE_WIFI_P2P;
import static android.net.ConnectivityManager.TYPE_WIMAX;
import static android.net.NetworkIdentity.COMBINE_SUBTYPE_ENABLED;
import static android.net.wifi.WifiInfo.removeDoubleQuotes;
import static android.telephony.TelephonyManager.NETWORK_CLASS_2_G;
import static android.telephony.TelephonyManager.NETWORK_CLASS_3_G;
import static android.telephony.TelephonyManager.NETWORK_CLASS_4_G;
import static android.telephony.TelephonyManager.NETWORK_CLASS_UNKNOWN;
import static android.telephony.TelephonyManager.getNetworkClass;

import android.os.Parcel;
import android.os.Parcelable;
import android.util.BackupUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Template definition used to generically match {@link NetworkIdentity},
 * usually when collecting statistics.
 *
 * @hide
 */
public class NetworkTemplate implements Parcelable {
    /**
     * Current Version of the Backup Serializer.
     */
    private static final int BACKUP_VERSION = 1;

    public static final int MATCH_MOBILE_ALL = 1;
    @Deprecated
    public static final int MATCH_MOBILE_3G_LOWER = 2;
    @Deprecated
    public static final int MATCH_MOBILE_4G = 3;
    public static final int MATCH_WIFI = 4;
    public static final int MATCH_ETHERNET = 5;
    public static final int MATCH_MOBILE_WILDCARD = 6;
    public static final int MATCH_WIFI_WILDCARD = 7;
    public static final int MATCH_BLUETOOTH = 8;
    public static final int MATCH_PROXY = 9;

    private static boolean sForceAllNetworkTypes = false;

    @VisibleForTesting
    public static void forceAllNetworkTypes() {
        sForceAllNetworkTypes = true;
    }

    /**
     * Template to match {@link ConnectivityManager#TYPE_MOBILE} networks with
     * the given IMSI.
     */
    public static NetworkTemplate buildTemplateMobileAll(String subscriberId) {
        return new NetworkTemplate(MATCH_MOBILE_ALL, subscriberId, null);
    }

    /**
     * Template to match {@link ConnectivityManager#TYPE_MOBILE} networks with
     * the given IMSI that roughly meet a "3G" definition, or lower.
     */
    @Deprecated
    public static NetworkTemplate buildTemplateMobile3gLower(String subscriberId) {
        return new NetworkTemplate(MATCH_MOBILE_3G_LOWER, subscriberId, null);
    }

    /**
     * Template to match {@link ConnectivityManager#TYPE_MOBILE} networks with
     * the given IMSI that roughly meet a "4G" definition.
     */
    @Deprecated
    public static NetworkTemplate buildTemplateMobile4g(String subscriberId) {
        return new NetworkTemplate(MATCH_MOBILE_4G, subscriberId, null);
    }

    /**
     * Template to match {@link ConnectivityManager#TYPE_MOBILE} networks,
     * regardless of IMSI.
     */
    public static NetworkTemplate buildTemplateMobileWildcard() {
        return new NetworkTemplate(MATCH_MOBILE_WILDCARD, null, null);
    }

    /**
     * Template to match all {@link ConnectivityManager#TYPE_WIFI} networks,
     * regardless of SSID.
     */
    public static NetworkTemplate buildTemplateWifiWildcard() {
        return new NetworkTemplate(MATCH_WIFI_WILDCARD, null, null);
    }

    @Deprecated
    public static NetworkTemplate buildTemplateWifi() {
        return buildTemplateWifiWildcard();
    }

    /**
     * Template to match {@link ConnectivityManager#TYPE_WIFI} networks with the
     * given SSID.
     */
    public static NetworkTemplate buildTemplateWifi(String networkId) {
        return new NetworkTemplate(MATCH_WIFI, null, networkId);
    }

    /**
     * Template to combine all {@link ConnectivityManager#TYPE_ETHERNET} style
     * networks together.
     */
    public static NetworkTemplate buildTemplateEthernet() {
        return new NetworkTemplate(MATCH_ETHERNET, null, null);
    }

    /**
     * Template to combine all {@link ConnectivityManager#TYPE_BLUETOOTH} style
     * networks together.
     */
    public static NetworkTemplate buildTemplateBluetooth() {
        return new NetworkTemplate(MATCH_BLUETOOTH, null, null);
    }

    /**
     * Template to combine all {@link ConnectivityManager#TYPE_PROXY} style
     * networks together.
     */
    public static NetworkTemplate buildTemplateProxy() {
        return new NetworkTemplate(MATCH_PROXY, null, null);
    }

    private final int mMatchRule;
    private final String mSubscriberId;

    /**
     * Ugh, templates are designed to target a single subscriber, but we might
     * need to match several "merged" subscribers. These are the subscribers
     * that should be considered to match this template.
     * <p>
     * Since the merge set is dynamic, it should <em>not</em> be persisted or
     * used for determining equality.
     */
    private final String[] mMatchSubscriberIds;

    private final String mNetworkId;

    public NetworkTemplate(int matchRule, String subscriberId, String networkId) {
        this(matchRule, subscriberId, new String[] { subscriberId }, networkId);
    }

    public NetworkTemplate(int matchRule, String subscriberId, String[] matchSubscriberIds,
            String networkId) {
        mMatchRule = matchRule;
        mSubscriberId = subscriberId;
        mMatchSubscriberIds = matchSubscriberIds;
        mNetworkId = networkId;
    }

    private NetworkTemplate(Parcel in) {
        mMatchRule = in.readInt();
        mSubscriberId = in.readString();
        mMatchSubscriberIds = in.createStringArray();
        mNetworkId = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mMatchRule);
        dest.writeString(mSubscriberId);
        dest.writeStringArray(mMatchSubscriberIds);
        dest.writeString(mNetworkId);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder("NetworkTemplate: ");
        builder.append("matchRule=").append(getMatchRuleName(mMatchRule));
        if (mSubscriberId != null) {
            builder.append(", subscriberId=").append(
                    NetworkIdentity.scrubSubscriberId(mSubscriberId));
        }
        if (mMatchSubscriberIds != null) {
            builder.append(", matchSubscriberIds=").append(
                    Arrays.toString(NetworkIdentity.scrubSubscriberId(mMatchSubscriberIds)));
        }
        if (mNetworkId != null) {
            builder.append(", networkId=").append(mNetworkId);
        }
        return builder.toString();
    }

    @Override
    public int hashCode() {
        return Objects.hash(mMatchRule, mSubscriberId, mNetworkId);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof NetworkTemplate) {
            final NetworkTemplate other = (NetworkTemplate) obj;
            return mMatchRule == other.mMatchRule
                    && Objects.equals(mSubscriberId, other.mSubscriberId)
                    && Objects.equals(mNetworkId, other.mNetworkId);
        }
        return false;
    }

    public boolean isMatchRuleMobile() {
        switch (mMatchRule) {
            case MATCH_MOBILE_3G_LOWER:
            case MATCH_MOBILE_4G:
            case MATCH_MOBILE_ALL:
            case MATCH_MOBILE_WILDCARD:
                return true;
            default:
                return false;
        }
    }

    public boolean isPersistable() {
        switch (mMatchRule) {
            case MATCH_MOBILE_WILDCARD:
            case MATCH_WIFI_WILDCARD:
                return false;
            default:
                return true;
        }
    }

    public int getMatchRule() {
        return mMatchRule;
    }

    public String getSubscriberId() {
        return mSubscriberId;
    }

    public String getNetworkId() {
        return mNetworkId;
    }

    /**
     * Test if given {@link NetworkIdentity} matches this template.
     */
    public boolean matches(NetworkIdentity ident) {
        switch (mMatchRule) {
            case MATCH_MOBILE_ALL:
                return matchesMobile(ident);
            case MATCH_MOBILE_3G_LOWER:
                return matchesMobile3gLower(ident);
            case MATCH_MOBILE_4G:
                return matchesMobile4g(ident);
            case MATCH_WIFI:
                return matchesWifi(ident);
            case MATCH_ETHERNET:
                return matchesEthernet(ident);
            case MATCH_MOBILE_WILDCARD:
                return matchesMobileWildcard(ident);
            case MATCH_WIFI_WILDCARD:
                return matchesWifiWildcard(ident);
            case MATCH_BLUETOOTH:
                return matchesBluetooth(ident);
            case MATCH_PROXY:
                return matchesProxy(ident);
            default:
                throw new IllegalArgumentException("unknown network template");
        }
    }

    /**
     * Check if mobile network with matching IMSI.
     */
    private boolean matchesMobile(NetworkIdentity ident) {
        if (ident.mType == TYPE_WIMAX) {
            // TODO: consider matching against WiMAX subscriber identity
            return true;
        } else {
            return (sForceAllNetworkTypes || (ident.mType == TYPE_MOBILE && ident.mMetered))
                    && !ArrayUtils.isEmpty(mMatchSubscriberIds)
                    && ArrayUtils.contains(mMatchSubscriberIds, ident.mSubscriberId);
        }
    }

    /**
     * Check if mobile network classified 3G or lower with matching IMSI.
     */
    @Deprecated
    private boolean matchesMobile3gLower(NetworkIdentity ident) {
        ensureSubtypeAvailable();
        if (ident.mType == TYPE_WIMAX) {
            return false;
        } else if (matchesMobile(ident)) {
            switch (getNetworkClass(ident.mSubType)) {
                case NETWORK_CLASS_UNKNOWN:
                case NETWORK_CLASS_2_G:
                case NETWORK_CLASS_3_G:
                    return true;
            }
        }
        return false;
    }

    /**
     * Check if mobile network classified 4G with matching IMSI.
     */
    @Deprecated
    private boolean matchesMobile4g(NetworkIdentity ident) {
        ensureSubtypeAvailable();
        if (ident.mType == TYPE_WIMAX) {
            // TODO: consider matching against WiMAX subscriber identity
            return true;
        } else if (matchesMobile(ident)) {
            switch (getNetworkClass(ident.mSubType)) {
                case NETWORK_CLASS_4_G:
                    return true;
            }
        }
        return false;
    }

    /**
     * Check if matches Wi-Fi network template.
     */
    private boolean matchesWifi(NetworkIdentity ident) {
        switch (ident.mType) {
            case TYPE_WIFI:
                return Objects.equals(
                        removeDoubleQuotes(mNetworkId), removeDoubleQuotes(ident.mNetworkId));
            default:
                return false;
        }
    }

    /**
     * Check if matches Ethernet network template.
     */
    private boolean matchesEthernet(NetworkIdentity ident) {
        if (ident.mType == TYPE_ETHERNET) {
            return true;
        }
        return false;
    }

    private boolean matchesMobileWildcard(NetworkIdentity ident) {
        if (ident.mType == TYPE_WIMAX) {
            return true;
        } else {
            return sForceAllNetworkTypes || (ident.mType == TYPE_MOBILE && ident.mMetered);
        }
    }

    private boolean matchesWifiWildcard(NetworkIdentity ident) {
        switch (ident.mType) {
            case TYPE_WIFI:
            case TYPE_WIFI_P2P:
                return true;
            default:
                return false;
        }
    }

    /**
     * Check if matches Bluetooth network template.
     */
    private boolean matchesBluetooth(NetworkIdentity ident) {
        if (ident.mType == TYPE_BLUETOOTH) {
            return true;
        }
        return false;
    }

    /**
     * Check if matches Proxy network template.
     */
    private boolean matchesProxy(NetworkIdentity ident) {
        return ident.mType == TYPE_PROXY;
    }

    private static String getMatchRuleName(int matchRule) {
        switch (matchRule) {
            case MATCH_MOBILE_3G_LOWER:
                return "MOBILE_3G_LOWER";
            case MATCH_MOBILE_4G:
                return "MOBILE_4G";
            case MATCH_MOBILE_ALL:
                return "MOBILE_ALL";
            case MATCH_WIFI:
                return "WIFI";
            case MATCH_ETHERNET:
                return "ETHERNET";
            case MATCH_MOBILE_WILDCARD:
                return "MOBILE_WILDCARD";
            case MATCH_WIFI_WILDCARD:
                return "WIFI_WILDCARD";
            case MATCH_BLUETOOTH:
                return "BLUETOOTH";
            case MATCH_PROXY:
                return "PROXY";
            default:
                return "UNKNOWN";
        }
    }

    private static void ensureSubtypeAvailable() {
        if (COMBINE_SUBTYPE_ENABLED) {
            throw new IllegalArgumentException(
                    "Unable to enforce 3G_LOWER template on combined data.");
        }
    }

    /**
     * Examine the given template and normalize if it refers to a "merged"
     * mobile subscriber. We pick the "lowest" merged subscriber as the primary
     * for key purposes, and expand the template to match all other merged
     * subscribers.
     * <p>
     * For example, given an incoming template matching B, and the currently
     * active merge set [A,B], we'd return a new template that primarily matches
     * A, but also matches B.
     */
    public static NetworkTemplate normalize(NetworkTemplate template, String[] merged) {
        if (template.isMatchRuleMobile() && ArrayUtils.contains(merged, template.mSubscriberId)) {
            // Requested template subscriber is part of the merge group; return
            // a template that matches all merged subscribers.
            return new NetworkTemplate(template.mMatchRule, merged[0], merged,
                    template.mNetworkId);
        } else {
            return template;
        }
    }

    public static final Creator<NetworkTemplate> CREATOR = new Creator<NetworkTemplate>() {
        @Override
        public NetworkTemplate createFromParcel(Parcel in) {
            return new NetworkTemplate(in);
        }

        @Override
        public NetworkTemplate[] newArray(int size) {
            return new NetworkTemplate[size];
        }
    };

    public byte[] getBytesForBackup() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);

        out.writeInt(BACKUP_VERSION);

        out.writeInt(mMatchRule);
        BackupUtils.writeString(out, mSubscriberId);
        BackupUtils.writeString(out, mNetworkId);

        return baos.toByteArray();
    }

    public static NetworkTemplate getNetworkTemplateFromBackup(DataInputStream in)
            throws IOException, BackupUtils.BadVersionException {
        int version = in.readInt();
        if (version < 1 || version > BACKUP_VERSION) {
            throw new BackupUtils.BadVersionException("Unknown Backup Serialization Version");
        }

        int matchRule = in.readInt();
        String subscriberId = BackupUtils.readString(in);
        String networkId = BackupUtils.readString(in);

        return new NetworkTemplate(matchRule, subscriberId, networkId);
    }
}

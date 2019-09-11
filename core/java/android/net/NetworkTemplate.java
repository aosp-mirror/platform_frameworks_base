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
import static android.net.ConnectivityManager.TYPE_MOBILE;
import static android.net.ConnectivityManager.TYPE_PROXY;
import static android.net.ConnectivityManager.TYPE_WIFI;
import static android.net.ConnectivityManager.TYPE_WIFI_P2P;
import static android.net.ConnectivityManager.TYPE_WIMAX;
import static android.net.NetworkStats.DEFAULT_NETWORK_ALL;
import static android.net.NetworkStats.DEFAULT_NETWORK_NO;
import static android.net.NetworkStats.DEFAULT_NETWORK_YES;
import static android.net.NetworkStats.METERED_ALL;
import static android.net.NetworkStats.METERED_NO;
import static android.net.NetworkStats.METERED_YES;
import static android.net.NetworkStats.ROAMING_ALL;
import static android.net.NetworkStats.ROAMING_NO;
import static android.net.NetworkStats.ROAMING_YES;
import static android.net.wifi.WifiInfo.removeDoubleQuotes;

import android.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.BackupUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Predicate used to match {@link NetworkIdentity}, usually when collecting
 * statistics. (It should probably have been named {@code NetworkPredicate}.)
 *
 * @hide
 */
public class NetworkTemplate implements Parcelable {
    private static final String TAG = "NetworkTemplate";

    /**
     * Current Version of the Backup Serializer.
     */
    private static final int BACKUP_VERSION = 1;

    public static final int MATCH_MOBILE = 1;
    public static final int MATCH_WIFI = 4;
    public static final int MATCH_ETHERNET = 5;
    public static final int MATCH_MOBILE_WILDCARD = 6;
    public static final int MATCH_WIFI_WILDCARD = 7;
    public static final int MATCH_BLUETOOTH = 8;
    public static final int MATCH_PROXY = 9;

    private static boolean isKnownMatchRule(final int rule) {
        switch (rule) {
            case MATCH_MOBILE:
            case MATCH_WIFI:
            case MATCH_ETHERNET:
            case MATCH_MOBILE_WILDCARD:
            case MATCH_WIFI_WILDCARD:
            case MATCH_BLUETOOTH:
            case MATCH_PROXY:
                return true;

            default:
                return false;
        }
    }

    private static boolean sForceAllNetworkTypes = false;

    /**
     * Results in matching against all mobile network types.
     *
     * <p>See {@link #matchesMobile} and {@link matchesMobileWildcard}.
     */
    @VisibleForTesting
    public static void forceAllNetworkTypes() {
        sForceAllNetworkTypes = true;
    }

    /** Resets the affect of {@link #forceAllNetworkTypes}. */
    @VisibleForTesting
    public static void resetForceAllNetworkTypes() {
        sForceAllNetworkTypes = false;
    }

    /**
     * Template to match {@link ConnectivityManager#TYPE_MOBILE} networks with
     * the given IMSI.
     */
    @UnsupportedAppUsage
    public static NetworkTemplate buildTemplateMobileAll(String subscriberId) {
        return new NetworkTemplate(MATCH_MOBILE, subscriberId, null);
    }

    /**
     * Template to match {@link ConnectivityManager#TYPE_MOBILE} networks,
     * regardless of IMSI.
     */
    @UnsupportedAppUsage
    public static NetworkTemplate buildTemplateMobileWildcard() {
        return new NetworkTemplate(MATCH_MOBILE_WILDCARD, null, null);
    }

    /**
     * Template to match all {@link ConnectivityManager#TYPE_WIFI} networks,
     * regardless of SSID.
     */
    @UnsupportedAppUsage
    public static NetworkTemplate buildTemplateWifiWildcard() {
        return new NetworkTemplate(MATCH_WIFI_WILDCARD, null, null);
    }

    @Deprecated
    @UnsupportedAppUsage
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
    @UnsupportedAppUsage
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

    // Matches for the NetworkStats constants METERED_*, ROAMING_* and DEFAULT_NETWORK_*.
    private final int mMetered;
    private final int mRoaming;
    private final int mDefaultNetwork;

    @UnsupportedAppUsage
    public NetworkTemplate(int matchRule, String subscriberId, String networkId) {
        this(matchRule, subscriberId, new String[] { subscriberId }, networkId);
    }

    public NetworkTemplate(int matchRule, String subscriberId, String[] matchSubscriberIds,
            String networkId) {
        this(matchRule, subscriberId, matchSubscriberIds, networkId, METERED_ALL, ROAMING_ALL,
                DEFAULT_NETWORK_ALL);
    }

    public NetworkTemplate(int matchRule, String subscriberId, String[] matchSubscriberIds,
            String networkId, int metered, int roaming, int defaultNetwork) {
        mMatchRule = matchRule;
        mSubscriberId = subscriberId;
        mMatchSubscriberIds = matchSubscriberIds;
        mNetworkId = networkId;
        mMetered = metered;
        mRoaming = roaming;
        mDefaultNetwork = defaultNetwork;

        if (!isKnownMatchRule(matchRule)) {
            Log.e(TAG, "Unknown network template rule " + matchRule
                    + " will not match any identity.");
        }
    }

    private NetworkTemplate(Parcel in) {
        mMatchRule = in.readInt();
        mSubscriberId = in.readString();
        mMatchSubscriberIds = in.createStringArray();
        mNetworkId = in.readString();
        mMetered = in.readInt();
        mRoaming = in.readInt();
        mDefaultNetwork = in.readInt();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mMatchRule);
        dest.writeString(mSubscriberId);
        dest.writeStringArray(mMatchSubscriberIds);
        dest.writeString(mNetworkId);
        dest.writeInt(mMetered);
        dest.writeInt(mRoaming);
        dest.writeInt(mDefaultNetwork);
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
        if (mMetered != METERED_ALL) {
            builder.append(", metered=").append(NetworkStats.meteredToString(mMetered));
        }
        if (mRoaming != ROAMING_ALL) {
            builder.append(", roaming=").append(NetworkStats.roamingToString(mRoaming));
        }
        if (mDefaultNetwork != DEFAULT_NETWORK_ALL) {
            builder.append(", defaultNetwork=").append(NetworkStats.defaultNetworkToString(
                    mDefaultNetwork));
        }
        return builder.toString();
    }

    @Override
    public int hashCode() {
        return Objects.hash(mMatchRule, mSubscriberId, mNetworkId, mMetered, mRoaming,
                mDefaultNetwork);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof NetworkTemplate) {
            final NetworkTemplate other = (NetworkTemplate) obj;
            return mMatchRule == other.mMatchRule
                    && Objects.equals(mSubscriberId, other.mSubscriberId)
                    && Objects.equals(mNetworkId, other.mNetworkId)
                    && mMetered == other.mMetered
                    && mRoaming == other.mRoaming
                    && mDefaultNetwork == other.mDefaultNetwork;
        }
        return false;
    }

    public boolean isMatchRuleMobile() {
        switch (mMatchRule) {
            case MATCH_MOBILE:
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

    @UnsupportedAppUsage
    public int getMatchRule() {
        return mMatchRule;
    }

    @UnsupportedAppUsage
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
        if (!matchesMetered(ident)) return false;
        if (!matchesRoaming(ident)) return false;
        if (!matchesDefaultNetwork(ident)) return false;

        switch (mMatchRule) {
            case MATCH_MOBILE:
                return matchesMobile(ident);
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
                // We have no idea what kind of network template we are, so we
                // just claim not to match anything.
                return false;
        }
    }

    private boolean matchesMetered(NetworkIdentity ident) {
        return (mMetered == METERED_ALL)
            || (mMetered == METERED_YES && ident.mMetered)
            || (mMetered == METERED_NO && !ident.mMetered);
    }

    private boolean matchesRoaming(NetworkIdentity ident) {
        return (mRoaming == ROAMING_ALL)
            || (mRoaming == ROAMING_YES && ident.mRoaming)
            || (mRoaming == ROAMING_NO && !ident.mRoaming);
    }

    private boolean matchesDefaultNetwork(NetworkIdentity ident) {
        return (mDefaultNetwork == DEFAULT_NETWORK_ALL)
            || (mDefaultNetwork == DEFAULT_NETWORK_YES && ident.mDefaultNetwork)
            || (mDefaultNetwork == DEFAULT_NETWORK_NO && !ident.mDefaultNetwork);
    }

    public boolean matchesSubscriberId(String subscriberId) {
        return ArrayUtils.contains(mMatchSubscriberIds, subscriberId);
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
            case MATCH_MOBILE:
                return "MOBILE";
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
                return "UNKNOWN(" + matchRule + ")";
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
     * TODO: remove and use {@link #normalize(NetworkTemplate, List)}.
     */
    @UnsupportedAppUsage
    public static NetworkTemplate normalize(NetworkTemplate template, String[] merged) {
        return normalize(template, Arrays.<String[]>asList(merged));
    }

    /**
     * Examine the given template and normalize if it refers to a "merged"
     * mobile subscriber. We pick the "lowest" merged subscriber as the primary
     * for key purposes, and expand the template to match all other merged
     * subscribers.
     *
     * There can be multiple merged subscriberIds for multi-SIM devices.
     *
     * <p>
     * For example, given an incoming template matching B, and the currently
     * active merge set [A,B], we'd return a new template that primarily matches
     * A, but also matches B.
     */
    public static NetworkTemplate normalize(NetworkTemplate template, List<String[]> mergedList) {
        if (!template.isMatchRuleMobile()) return template;

        for (String[] merged : mergedList) {
            if (ArrayUtils.contains(merged, template.mSubscriberId)) {
                // Requested template subscriber is part of the merge group; return
                // a template that matches all merged subscribers.
                return new NetworkTemplate(template.mMatchRule, merged[0], merged,
                        template.mNetworkId);
            }
        }

        return template;
    }

    @UnsupportedAppUsage
    public static final @android.annotation.NonNull Creator<NetworkTemplate> CREATOR = new Creator<NetworkTemplate>() {
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

        if (!isKnownMatchRule(matchRule)) {
            throw new BackupUtils.BadVersionException(
                    "Restored network template contains unknown match rule " + matchRule);
        }

        return new NetworkTemplate(matchRule, subscriberId, networkId);
    }
}

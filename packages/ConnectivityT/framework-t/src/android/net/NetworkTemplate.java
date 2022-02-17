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

import static android.annotation.SystemApi.Client.MODULE_LIBRARIES;
import static android.net.ConnectivityManager.TYPE_BLUETOOTH;
import static android.net.ConnectivityManager.TYPE_ETHERNET;
import static android.net.ConnectivityManager.TYPE_MOBILE;
import static android.net.ConnectivityManager.TYPE_PROXY;
import static android.net.ConnectivityManager.TYPE_WIFI;
import static android.net.ConnectivityManager.TYPE_WIFI_P2P;
import static android.net.ConnectivityManager.TYPE_WIMAX;
import static android.net.NetworkIdentity.OEM_NONE;
import static android.net.NetworkIdentity.OEM_PAID;
import static android.net.NetworkIdentity.OEM_PRIVATE;
import static android.net.NetworkStats.DEFAULT_NETWORK_ALL;
import static android.net.NetworkStats.DEFAULT_NETWORK_NO;
import static android.net.NetworkStats.DEFAULT_NETWORK_YES;
import static android.net.NetworkStats.METERED_ALL;
import static android.net.NetworkStats.METERED_NO;
import static android.net.NetworkStats.METERED_YES;
import static android.net.NetworkStats.ROAMING_ALL;
import static android.net.NetworkStats.ROAMING_NO;
import static android.net.NetworkStats.ROAMING_YES;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.usage.NetworkStatsManager;
import android.compat.annotation.UnsupportedAppUsage;
import android.net.wifi.WifiInfo;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.ArraySet;

import com.android.net.module.util.CollectionUtils;
import com.android.net.module.util.NetworkIdentityUtils;
import com.android.net.module.util.NetworkStatsUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Predicate used to match {@link NetworkIdentity}, usually when collecting
 * statistics. (It should probably have been named {@code NetworkPredicate}.)
 *
 * @hide
 */
@SystemApi(client = MODULE_LIBRARIES)
public final class NetworkTemplate implements Parcelable {
    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "MATCH_" }, value = {
            MATCH_MOBILE,
            MATCH_WIFI,
            MATCH_ETHERNET,
            MATCH_BLUETOOTH,
            MATCH_PROXY,
            MATCH_CARRIER,
    })
    public @interface TemplateMatchRule{}

    /** Match rule to match cellular networks with given Subscriber Ids. */
    public static final int MATCH_MOBILE = 1;
    /** Match rule to match wifi networks. */
    public static final int MATCH_WIFI = 4;
    /** Match rule to match ethernet networks. */
    public static final int MATCH_ETHERNET = 5;
    /**
     * Match rule to match all cellular networks.
     *
     * @hide
     */
    public static final int MATCH_MOBILE_WILDCARD = 6;
    /**
     * Match rule to match all wifi networks.
     *
     * @hide
     */
    public static final int MATCH_WIFI_WILDCARD = 7;
    /** Match rule to match bluetooth networks. */
    public static final int MATCH_BLUETOOTH = 8;
    /**
     * Match rule to match networks with {@link ConnectivityManager#TYPE_PROXY} as the legacy
     * network type.
     */
    public static final int MATCH_PROXY = 9;
    /**
     * Match rule to match all networks with subscriberId inside the template. Some carriers
     * may offer non-cellular networks like WiFi, which will be matched by this rule.
     */
    public static final int MATCH_CARRIER = 10;

    // TODO: Remove this and replace all callers with WIFI_NETWORK_KEY_ALL.
    /** @hide */
    public static final String WIFI_NETWORKID_ALL = null;

    /**
     * Wi-Fi Network Key is never supposed to be null (if it is, it is a bug that
     * should be fixed), so it's not possible to want to match null vs
     * non-null. Therefore it's fine to use null as a sentinel for Wifi Network Key.
     *
     * @hide
     */
    public static final String WIFI_NETWORK_KEY_ALL = WIFI_NETWORKID_ALL;

    /**
     * Include all network types when filtering. This is meant to merge in with the
     * {@code TelephonyManager.NETWORK_TYPE_*} constants, and thus needs to stay in sync.
     */
    public static final int NETWORK_TYPE_ALL = -1;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "OEM_MANAGED_" }, value = {
            OEM_MANAGED_ALL,
            OEM_MANAGED_NO,
            OEM_MANAGED_YES,
            OEM_MANAGED_PAID,
            OEM_MANAGED_PRIVATE
    })
    public @interface OemManaged{}

    /**
     * Value to match both OEM managed and unmanaged networks (all networks).
     */
    public static final int OEM_MANAGED_ALL = -1;
    /**
     * Value to match networks which are not OEM managed.
     */
    public static final int OEM_MANAGED_NO = OEM_NONE;
    /**
     * Value to match any OEM managed network.
     */
    public static final int OEM_MANAGED_YES = -2;
    /**
     * Network has {@link NetworkCapabilities#NET_CAPABILITY_OEM_PAID}.
     */
    public static final int OEM_MANAGED_PAID = OEM_PAID;
    /**
     * Network has {@link NetworkCapabilities#NET_CAPABILITY_OEM_PRIVATE}.
     */
    public static final int OEM_MANAGED_PRIVATE = OEM_PRIVATE;

    private static boolean isKnownMatchRule(final int rule) {
        switch (rule) {
            case MATCH_MOBILE:
            case MATCH_WIFI:
            case MATCH_ETHERNET:
            case MATCH_MOBILE_WILDCARD:
            case MATCH_WIFI_WILDCARD:
            case MATCH_BLUETOOTH:
            case MATCH_PROXY:
            case MATCH_CARRIER:
                return true;

            default:
                return false;
        }
    }

    /**
     * Template to match {@link ConnectivityManager#TYPE_MOBILE} networks with
     * the given IMSI.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public static NetworkTemplate buildTemplateMobileAll(String subscriberId) {
        return new NetworkTemplate(MATCH_MOBILE, subscriberId, null);
    }

    /**
     * Template to match cellular networks with the given IMSI, {@code ratType} and
     * {@code metered}. Use {@link #NETWORK_TYPE_ALL} to include all network types when
     * filtering. See {@code TelephonyManager.NETWORK_TYPE_*}.
     *
     * @hide
     */
    public static NetworkTemplate buildTemplateMobileWithRatType(@Nullable String subscriberId,
            int ratType, int metered) {
        if (TextUtils.isEmpty(subscriberId)) {
            return new NetworkTemplate(MATCH_MOBILE_WILDCARD, null /* subscriberId */,
                    null /* matchSubscriberIds */,
                    new String[0] /* matchWifiNetworkKeys */, metered, ROAMING_ALL,
                    DEFAULT_NETWORK_ALL, ratType, OEM_MANAGED_ALL,
                    NetworkStatsUtils.SUBSCRIBER_ID_MATCH_RULE_EXACT);
        }
        return new NetworkTemplate(MATCH_MOBILE, subscriberId, new String[] { subscriberId },
                new String[0] /* matchWifiNetworkKeys */,
                metered, ROAMING_ALL, DEFAULT_NETWORK_ALL, ratType, OEM_MANAGED_ALL,
                NetworkStatsUtils.SUBSCRIBER_ID_MATCH_RULE_EXACT);
    }

    /**
     * Template to match metered {@link ConnectivityManager#TYPE_MOBILE} networks,
     * regardless of IMSI.
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static NetworkTemplate buildTemplateMobileWildcard() {
        return new NetworkTemplate(MATCH_MOBILE_WILDCARD, null, null);
    }

    /**
     * Template to match all metered {@link ConnectivityManager#TYPE_WIFI} networks,
     * regardless of key of the wifi network.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public static NetworkTemplate buildTemplateWifiWildcard() {
        // TODO: Consider replace this with MATCH_WIFI with NETWORK_ID_ALL
        // and SUBSCRIBER_ID_MATCH_RULE_ALL.
        return new NetworkTemplate(MATCH_WIFI_WILDCARD, null, null);
    }

    /** @hide */
    @Deprecated
    @UnsupportedAppUsage
    public static NetworkTemplate buildTemplateWifi() {
        return buildTemplateWifiWildcard();
    }

    /**
     * Template to match {@link ConnectivityManager#TYPE_WIFI} networks with the
     * given key of the wifi network.
     *
     * @param wifiNetworkKey key of the wifi network. see {@link WifiInfo#getNetworkKey()}
     *                  to know details about the key.
     * @hide
     */
    public static NetworkTemplate buildTemplateWifi(@NonNull String wifiNetworkKey) {
        Objects.requireNonNull(wifiNetworkKey);
        return new NetworkTemplate(MATCH_WIFI, null /* subscriberId */,
                new String[] { null } /* matchSubscriberIds */,
                new String[] { wifiNetworkKey }, METERED_ALL, ROAMING_ALL,
                DEFAULT_NETWORK_ALL, NETWORK_TYPE_ALL, OEM_MANAGED_ALL,
                NetworkStatsUtils.SUBSCRIBER_ID_MATCH_RULE_ALL);
    }

    /**
     * Template to match all {@link ConnectivityManager#TYPE_WIFI} networks with the given
     * key of the wifi network and IMSI.
     *
     * Call with {@link #WIFI_NETWORK_KEY_ALL} for {@code wifiNetworkKey} to get result regardless
     * of key of the wifi network.
     *
     * @param wifiNetworkKey key of the wifi network. see {@link WifiInfo#getNetworkKey()}
     *                  to know details about the key.
     * @param subscriberId the IMSI associated to this wifi network.
     *
     * @hide
     */
    public static NetworkTemplate buildTemplateWifi(@Nullable String wifiNetworkKey,
            @Nullable String subscriberId) {
        return new NetworkTemplate(MATCH_WIFI, subscriberId, new String[] { subscriberId },
                wifiNetworkKey != null
                        ? new String[] { wifiNetworkKey } : new String[0],
                METERED_ALL, ROAMING_ALL, DEFAULT_NETWORK_ALL, NETWORK_TYPE_ALL, OEM_MANAGED_ALL,
                NetworkStatsUtils.SUBSCRIBER_ID_MATCH_RULE_EXACT);
    }

    /**
     * Template to combine all {@link ConnectivityManager#TYPE_ETHERNET} style
     * networks together.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public static NetworkTemplate buildTemplateEthernet() {
        return new NetworkTemplate(MATCH_ETHERNET, null, null);
    }

    /**
     * Template to combine all {@link ConnectivityManager#TYPE_BLUETOOTH} style
     * networks together.
     *
     * @hide
     */
    public static NetworkTemplate buildTemplateBluetooth() {
        return new NetworkTemplate(MATCH_BLUETOOTH, null, null);
    }

    /**
     * Template to combine all {@link ConnectivityManager#TYPE_PROXY} style
     * networks together.
     *
     * @hide
     */
    public static NetworkTemplate buildTemplateProxy() {
        return new NetworkTemplate(MATCH_PROXY, null, null);
    }

    /**
     * Template to match all metered carrier networks with the given IMSI.
     *
     * @hide
     */
    public static NetworkTemplate buildTemplateCarrierMetered(@NonNull String subscriberId) {
        Objects.requireNonNull(subscriberId);
        return new NetworkTemplate(MATCH_CARRIER, subscriberId,
                new String[] { subscriberId },
                new String[0] /* matchWifiNetworkKeys */,
                METERED_YES, ROAMING_ALL,
                DEFAULT_NETWORK_ALL, NETWORK_TYPE_ALL, OEM_MANAGED_ALL,
                NetworkStatsUtils.SUBSCRIBER_ID_MATCH_RULE_EXACT);
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

    @NonNull
    private final String[] mMatchWifiNetworkKeys;

    // Matches for the NetworkStats constants METERED_*, ROAMING_* and DEFAULT_NETWORK_*.
    private final int mMetered;
    private final int mRoaming;
    private final int mDefaultNetwork;
    private final int mRatType;
    /**
     * The subscriber Id match rule defines how the template should match networks with
     * specific subscriberId(s). See NetworkTemplate#SUBSCRIBER_ID_MATCH_RULE_* for more detail.
     */
    private final int mSubscriberIdMatchRule;

    // Bitfield containing OEM network properties{@code NetworkIdentity#OEM_*}.
    private final int mOemManaged;

    private static void checkValidSubscriberIdMatchRule(int matchRule, int subscriberIdMatchRule) {
        switch (matchRule) {
            case MATCH_MOBILE:
            case MATCH_CARRIER:
                // MOBILE and CARRIER templates must always specify a subscriber ID.
                if (subscriberIdMatchRule == NetworkStatsUtils.SUBSCRIBER_ID_MATCH_RULE_ALL) {
                    throw new IllegalArgumentException("Invalid SubscriberIdMatchRule "
                            + "on match rule: " + getMatchRuleName(matchRule));
                }
                return;
            default:
                return;
        }
    }

    /** @hide */
    // TODO: Deprecate this constructor, mark it @UnsupportedAppUsage(maxTargetSdk = S)
    @UnsupportedAppUsage
    public NetworkTemplate(int matchRule, String subscriberId, String wifiNetworkKey) {
        this(matchRule, subscriberId, new String[] { subscriberId }, wifiNetworkKey);
    }

    /** @hide */
    public NetworkTemplate(int matchRule, String subscriberId, String[] matchSubscriberIds,
            String wifiNetworkKey) {
        // Older versions used to only match MATCH_MOBILE and MATCH_MOBILE_WILDCARD templates
        // to metered networks. It is now possible to match mobile with any meteredness, but
        // in order to preserve backward compatibility of @UnsupportedAppUsage methods, this
        //constructor passes METERED_YES for these types.
        this(matchRule, subscriberId, matchSubscriberIds,
                wifiNetworkKey != null ? new String[] { wifiNetworkKey } : new String[0],
                (matchRule == MATCH_MOBILE || matchRule == MATCH_MOBILE_WILDCARD) ? METERED_YES
                : METERED_ALL , ROAMING_ALL, DEFAULT_NETWORK_ALL, NETWORK_TYPE_ALL,
                OEM_MANAGED_ALL, NetworkStatsUtils.SUBSCRIBER_ID_MATCH_RULE_EXACT);
    }

    /** @hide */
    // TODO: Remove it after updating all of the caller.
    public NetworkTemplate(int matchRule, String subscriberId, String[] matchSubscriberIds,
            String wifiNetworkKey, int metered, int roaming, int defaultNetwork, int ratType,
            int oemManaged) {
        this(matchRule, subscriberId, matchSubscriberIds,
                wifiNetworkKey != null ? new String[] { wifiNetworkKey } : new String[0],
                metered, roaming, defaultNetwork, ratType, oemManaged,
                NetworkStatsUtils.SUBSCRIBER_ID_MATCH_RULE_EXACT);
    }

    /** @hide */
    public NetworkTemplate(int matchRule, String subscriberId, String[] matchSubscriberIds,
            String[] matchWifiNetworkKeys, int metered, int roaming,
            int defaultNetwork, int ratType, int oemManaged, int subscriberIdMatchRule) {
        Objects.requireNonNull(matchWifiNetworkKeys);
        mMatchRule = matchRule;
        mSubscriberId = subscriberId;
        // TODO: Check whether mMatchSubscriberIds = null or mMatchSubscriberIds = {null} when
        // mSubscriberId is null
        mMatchSubscriberIds = matchSubscriberIds;
        mMatchWifiNetworkKeys = matchWifiNetworkKeys;
        mMetered = metered;
        mRoaming = roaming;
        mDefaultNetwork = defaultNetwork;
        mRatType = ratType;
        mOemManaged = oemManaged;
        mSubscriberIdMatchRule = subscriberIdMatchRule;
        checkValidSubscriberIdMatchRule(matchRule, subscriberIdMatchRule);
        if (!isKnownMatchRule(matchRule)) {
            throw new IllegalArgumentException("Unknown network template rule " + matchRule
                    + " will not match any identity.");
        }
    }

    private NetworkTemplate(Parcel in) {
        mMatchRule = in.readInt();
        mSubscriberId = in.readString();
        mMatchSubscriberIds = in.createStringArray();
        mMatchWifiNetworkKeys = in.createStringArray();
        mMetered = in.readInt();
        mRoaming = in.readInt();
        mDefaultNetwork = in.readInt();
        mRatType = in.readInt();
        mOemManaged = in.readInt();
        mSubscriberIdMatchRule = in.readInt();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mMatchRule);
        dest.writeString(mSubscriberId);
        dest.writeStringArray(mMatchSubscriberIds);
        dest.writeStringArray(mMatchWifiNetworkKeys);
        dest.writeInt(mMetered);
        dest.writeInt(mRoaming);
        dest.writeInt(mDefaultNetwork);
        dest.writeInt(mRatType);
        dest.writeInt(mOemManaged);
        dest.writeInt(mSubscriberIdMatchRule);
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
                    NetworkIdentityUtils.scrubSubscriberId(mSubscriberId));
        }
        if (mMatchSubscriberIds != null) {
            builder.append(", matchSubscriberIds=").append(
                    Arrays.toString(NetworkIdentityUtils.scrubSubscriberIds(mMatchSubscriberIds)));
        }
        builder.append(", matchWifiNetworkKeys=").append(Arrays.toString(mMatchWifiNetworkKeys));
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
        if (mRatType != NETWORK_TYPE_ALL) {
            builder.append(", ratType=").append(mRatType);
        }
        if (mOemManaged != OEM_MANAGED_ALL) {
            builder.append(", oemManaged=").append(getOemManagedNames(mOemManaged));
        }
        builder.append(", subscriberIdMatchRule=")
                .append(subscriberIdMatchRuleToString(mSubscriberIdMatchRule));
        return builder.toString();
    }

    @Override
    public int hashCode() {
        return Objects.hash(mMatchRule, mSubscriberId, Arrays.hashCode(mMatchWifiNetworkKeys),
                mMetered, mRoaming, mDefaultNetwork, mRatType, mOemManaged, mSubscriberIdMatchRule);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof NetworkTemplate) {
            final NetworkTemplate other = (NetworkTemplate) obj;
            return mMatchRule == other.mMatchRule
                    && Objects.equals(mSubscriberId, other.mSubscriberId)
                    && mMetered == other.mMetered
                    && mRoaming == other.mRoaming
                    && mDefaultNetwork == other.mDefaultNetwork
                    && mRatType == other.mRatType
                    && mOemManaged == other.mOemManaged
                    && mSubscriberIdMatchRule == other.mSubscriberIdMatchRule
                    && Arrays.equals(mMatchWifiNetworkKeys, other.mMatchWifiNetworkKeys);
        }
        return false;
    }

    private static String subscriberIdMatchRuleToString(int rule) {
        switch (rule) {
            case NetworkStatsUtils.SUBSCRIBER_ID_MATCH_RULE_EXACT:
                return "EXACT_MATCH";
            case NetworkStatsUtils.SUBSCRIBER_ID_MATCH_RULE_ALL:
                return "ALL";
            default:
                return "Unknown rule " + rule;
        }
    }

    /** @hide */
    public boolean isMatchRuleMobile() {
        switch (mMatchRule) {
            case MATCH_MOBILE:
            case MATCH_MOBILE_WILDCARD:
                return true;
            default:
                return false;
        }
    }

    /**
     * Get match rule of the template. See {@code MATCH_*}.
     */
    @UnsupportedAppUsage
    public int getMatchRule() {
        // Wildcard rules are not exposed. For external callers, convert wildcard rules to
        // exposed rules before returning.
        switch (mMatchRule) {
            case MATCH_MOBILE_WILDCARD:
                return MATCH_MOBILE;
            case MATCH_WIFI_WILDCARD:
                return MATCH_WIFI;
            default:
                return mMatchRule;
        }
    }

    /**
     * Get subscriber Id of the template.
     * @hide
     */
    @Nullable
    @UnsupportedAppUsage
    public String getSubscriberId() {
        return mSubscriberId;
    }

    /**
     * Get set of subscriber Ids of the template.
     */
    @NonNull
    public Set<String> getSubscriberIds() {
        return new ArraySet<>(Arrays.asList(mMatchSubscriberIds));
    }

    /**
     * Get the set of Wifi Network Keys of the template.
     * See {@link WifiInfo#getNetworkKey()}.
     */
    @NonNull
    public Set<String> getWifiNetworkKeys() {
        return new ArraySet<>(Arrays.asList(mMatchWifiNetworkKeys));
    }

    /** @hide */
    // TODO: Remove this and replace all callers with {@link #getWifiNetworkKeys()}.
    @Nullable
    public String getNetworkId() {
        return getWifiNetworkKeys().isEmpty() ? null : getWifiNetworkKeys().iterator().next();
    }

    /**
     * Get meteredness filter of the template.
     */
    @NetworkStats.Meteredness
    public int getMeteredness() {
        return mMetered;
    }

    /**
     * Get roaming filter of the template.
     */
    @NetworkStats.Roaming
    public int getRoaming() {
        return mRoaming;
    }

    /**
     * Get the default network status filter of the template.
     */
    @NetworkStats.DefaultNetwork
    public int getDefaultNetworkStatus() {
        return mDefaultNetwork;
    }

    /**
     * Get the Radio Access Technology(RAT) type filter of the template.
     */
    public int getRatType() {
        return mRatType;
    }

    /**
     * Get the OEM managed filter of the template. See {@code OEM_MANAGED_*} or
     * {@code android.net.NetworkIdentity#OEM_*}.
     */
    @OemManaged
    public int getOemManaged() {
        return mOemManaged;
    }

    /**
     * Test if given {@link NetworkIdentity} matches this template.
     *
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    public boolean matches(@NonNull NetworkIdentity ident) {
        Objects.requireNonNull(ident);
        if (!matchesMetered(ident)) return false;
        if (!matchesRoaming(ident)) return false;
        if (!matchesDefaultNetwork(ident)) return false;
        if (!matchesOemNetwork(ident)) return false;

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
            case MATCH_CARRIER:
                return matchesCarrier(ident);
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

    private boolean matchesOemNetwork(NetworkIdentity ident) {
        return (mOemManaged == OEM_MANAGED_ALL)
            || (mOemManaged == OEM_MANAGED_YES
                    && ident.mOemManaged != OEM_NONE)
            || (mOemManaged == ident.mOemManaged);
    }

    private boolean matchesCollapsedRatType(NetworkIdentity ident) {
        return mRatType == NETWORK_TYPE_ALL
                || NetworkStatsManager.getCollapsedRatType(mRatType)
                == NetworkStatsManager.getCollapsedRatType(ident.mRatType);
    }

    /**
     * Check if this template matches {@code subscriberId}. Returns true if this
     * template was created with {@code SUBSCRIBER_ID_MATCH_RULE_ALL}, or with a
     * {@code mMatchSubscriberIds} array that contains {@code subscriberId}.
     *
     * @hide
     */
    public boolean matchesSubscriberId(@Nullable String subscriberId) {
        return mSubscriberIdMatchRule == NetworkStatsUtils.SUBSCRIBER_ID_MATCH_RULE_ALL
                || CollectionUtils.contains(mMatchSubscriberIds, subscriberId);
    }

    /**
     * Check if network matches key of the wifi network.
     * Returns true when the key matches, or when {@code mMatchWifiNetworkKeys} is
     * empty.
     *
     * @param wifiNetworkKey key of the wifi network. see {@link WifiInfo#getNetworkKey()}
     *                  to know details about the key.
     */
    private boolean matchesWifiNetworkKey(@NonNull String wifiNetworkKey) {
        Objects.requireNonNull(wifiNetworkKey);
        return CollectionUtils.isEmpty(mMatchWifiNetworkKeys)
                || CollectionUtils.contains(mMatchWifiNetworkKeys, wifiNetworkKey);
    }

    /**
     * Check if mobile network matches IMSI.
     */
    private boolean matchesMobile(NetworkIdentity ident) {
        if (ident.mType == TYPE_WIMAX) {
            // TODO: consider matching against WiMAX subscriber identity
            return true;
        } else {
            return ident.mType == TYPE_MOBILE && !CollectionUtils.isEmpty(mMatchSubscriberIds)
                    && CollectionUtils.contains(mMatchSubscriberIds, ident.mSubscriberId)
                    && matchesCollapsedRatType(ident);
        }
    }

    /**
     * Check if matches Wi-Fi network template.
     */
    private boolean matchesWifi(NetworkIdentity ident) {
        switch (ident.mType) {
            case TYPE_WIFI:
                return matchesSubscriberId(ident.mSubscriberId)
                        && matchesWifiNetworkKey(ident.mWifiNetworkKey);
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

    /**
     * Check if matches carrier network. The carrier networks means it includes the subscriberId.
     */
    private boolean matchesCarrier(NetworkIdentity ident) {
        return ident.mSubscriberId != null
                && !CollectionUtils.isEmpty(mMatchSubscriberIds)
                && CollectionUtils.contains(mMatchSubscriberIds, ident.mSubscriberId);
    }

    private boolean matchesMobileWildcard(NetworkIdentity ident) {
        if (ident.mType == TYPE_WIMAX) {
            return true;
        } else {
            return ident.mType == TYPE_MOBILE && matchesCollapsedRatType(ident);
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
            case MATCH_CARRIER:
                return "CARRIER";
            default:
                return "UNKNOWN(" + matchRule + ")";
        }
    }

    private static String getOemManagedNames(int oemManaged) {
        switch (oemManaged) {
            case OEM_MANAGED_ALL:
                return "OEM_MANAGED_ALL";
            case OEM_MANAGED_NO:
                return "OEM_MANAGED_NO";
            case OEM_MANAGED_YES:
                return "OEM_MANAGED_YES";
            default:
                return NetworkIdentity.getOemManagedNames(oemManaged);
        }
    }

    /**
     * Examine the given template and normalize it.
     * We pick the "lowest" merged subscriber as the primary
     * for key purposes, and expand the template to match all other merged
     * subscribers.
     * <p>
     * For example, given an incoming template matching B, and the currently
     * active merge set [A,B], we'd return a new template that primarily matches
     * A, but also matches B.
     * TODO: remove and use {@link #normalize(NetworkTemplate, List)}.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public static NetworkTemplate normalize(NetworkTemplate template, String[] merged) {
        return normalize(template, Arrays.<String[]>asList(merged));
    }

    /**
     * Examine the given template and normalize it.
     * We pick the "lowest" merged subscriber as the primary
     * for key purposes, and expand the template to match all other merged
     * subscribers.
     *
     * There can be multiple merged subscriberIds for multi-SIM devices.
     *
     * <p>
     * For example, given an incoming template matching B, and the currently
     * active merge set [A,B], we'd return a new template that primarily matches
     * A, but also matches B.
     *
     * @hide
     */
    // TODO: @SystemApi when ready.
    public static NetworkTemplate normalize(NetworkTemplate template, List<String[]> mergedList) {
        // Now there are several types of network which uses SubscriberId to store network
        // information. For instances:
        // The TYPE_WIFI with subscriberId means that it is a merged carrier wifi network.
        // The TYPE_CARRIER means that the network associate to specific carrier network.

        if (template.mSubscriberId == null) return template;

        for (String[] merged : mergedList) {
            if (CollectionUtils.contains(merged, template.mSubscriberId)) {
                // Requested template subscriber is part of the merge group; return
                // a template that matches all merged subscribers.
                final String[] matchWifiNetworkKeys = template.mMatchWifiNetworkKeys;
                return new NetworkTemplate(template.mMatchRule, merged[0], merged,
                        CollectionUtils.isEmpty(matchWifiNetworkKeys)
                                ? null : matchWifiNetworkKeys[0]);
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

    /**
     * Builder class for NetworkTemplate.
     */
    public static final class Builder {
        private final int mMatchRule;
        // Use a SortedSet to provide a deterministic order when fetching the first one.
        @NonNull
        private final SortedSet<String> mMatchSubscriberIds =
                new TreeSet<>(Comparator.nullsFirst(Comparator.naturalOrder()));
        @NonNull
        private final SortedSet<String> mMatchWifiNetworkKeys = new TreeSet<>();

        // Matches for the NetworkStats constants METERED_*, ROAMING_* and DEFAULT_NETWORK_*.
        private int mMetered;
        private int mRoaming;
        private int mDefaultNetwork;
        private int mRatType;

        // Bitfield containing OEM network properties {@code NetworkIdentity#OEM_*}.
        private int mOemManaged;

        /**
         * Creates a new Builder with given match rule to construct NetworkTemplate objects.
         *
         * @param matchRule the match rule of the template, see {@code MATCH_*}.
         */
        public Builder(@TemplateMatchRule final int matchRule) {
            assertRequestableMatchRule(matchRule);
            // Initialize members with default values.
            mMatchRule = matchRule;
            mMetered = METERED_ALL;
            mRoaming = ROAMING_ALL;
            mDefaultNetwork = DEFAULT_NETWORK_ALL;
            mRatType = NETWORK_TYPE_ALL;
            mOemManaged = OEM_MANAGED_ALL;
        }

        /**
         * Set the Subscriber Ids. Calling this function with an empty set represents
         * the intention of matching any Subscriber Ids.
         *
         * @param subscriberIds the list of Subscriber Ids.
         * @return this builder.
         */
        @NonNull
        public Builder setSubscriberIds(@NonNull Set<String> subscriberIds) {
            Objects.requireNonNull(subscriberIds);
            mMatchSubscriberIds.clear();
            mMatchSubscriberIds.addAll(subscriberIds);
            return this;
        }

        /**
         * Set the Wifi Network Keys. Calling this function with an empty set represents
         * the intention of matching any Wifi Network Key.
         *
         * @param wifiNetworkKeys the list of Wifi Network Key,
         *                        see {@link WifiInfo#getNetworkKey()}.
         *                        Or an empty list to match all networks.
         *                        Note that {@code getNetworkKey()} might get null key
         *                        when wifi disconnects. However, the caller should never invoke
         *                        this function with a null Wifi Network Key since such statistics
         *                        never exists.
         * @return this builder.
         */
        @NonNull
        public Builder setWifiNetworkKeys(@NonNull Set<String> wifiNetworkKeys) {
            Objects.requireNonNull(wifiNetworkKeys);
            for (String key : wifiNetworkKeys) {
                if (key == null) {
                    throw new IllegalArgumentException("Null is not a valid key");
                }
            }
            mMatchWifiNetworkKeys.clear();
            mMatchWifiNetworkKeys.addAll(wifiNetworkKeys);
            return this;
        }

        /**
         * Set the meteredness filter.
         *
         * @param metered the meteredness filter.
         * @return this builder.
         */
        @NonNull
        public Builder setMeteredness(@NetworkStats.Meteredness int metered) {
            mMetered = metered;
            return this;
        }

        /**
         * Set the roaming filter.
         *
         * @param roaming the roaming filter.
         * @return this builder.
         */
        @NonNull
        public Builder setRoaming(@NetworkStats.Roaming int roaming) {
            mRoaming = roaming;
            return this;
        }

        /**
         * Set the default network status filter.
         *
         * @param defaultNetwork the default network status filter.
         * @return this builder.
         */
        @NonNull
        public Builder setDefaultNetworkStatus(@NetworkStats.DefaultNetwork int defaultNetwork) {
            mDefaultNetwork = defaultNetwork;
            return this;
        }

        /**
         * Set the Radio Access Technology(RAT) type filter.
         *
         * @param ratType the Radio Access Technology(RAT) type filter. Use
         *                {@link #NETWORK_TYPE_ALL} to include all network types when filtering.
         *                See {@code TelephonyManager.NETWORK_TYPE_*}.
         * @return this builder.
         */
        @NonNull
        public Builder setRatType(int ratType) {
            // Input will be validated with the match rule when building the template.
            mRatType = ratType;
            return this;
        }

        /**
         * Set the OEM managed filter.
         *
         * @param oemManaged the match rule to match different type of OEM managed network or
         *                   unmanaged networks. See {@code OEM_MANAGED_*}.
         * @return this builder.
         */
        @NonNull
        public Builder setOemManaged(@OemManaged int oemManaged) {
            mOemManaged = oemManaged;
            return this;
        }

        /**
         * Check whether the match rule is requestable.
         *
         * @param matchRule the target match rule to be checked.
         */
        private static void assertRequestableMatchRule(final int matchRule) {
            if (!isKnownMatchRule(matchRule)
                    || matchRule == MATCH_PROXY
                    || matchRule == MATCH_MOBILE_WILDCARD
                    || matchRule == MATCH_WIFI_WILDCARD) {
                throw new IllegalArgumentException("Invalid match rule: "
                        + getMatchRuleName(matchRule));
            }
        }

        private void assertRequestableParameters() {
            validateWifiNetworkKeys();
            // TODO: Check all the input are legitimate.
        }

        private void validateWifiNetworkKeys() {
            if (mMatchRule != MATCH_WIFI && !mMatchWifiNetworkKeys.isEmpty()) {
                throw new IllegalArgumentException("Trying to build non wifi match rule: "
                        + mMatchRule + " with wifi network keys");
            }
        }

        /**
         * For backward compatibility, deduce match rule to a wildcard match rule
         * if the Subscriber Ids are empty.
         */
        private int getWildcardDeducedMatchRule() {
            if (mMatchRule == MATCH_MOBILE && mMatchSubscriberIds.isEmpty()) {
                return MATCH_MOBILE_WILDCARD;
            } else if (mMatchRule == MATCH_WIFI && mMatchSubscriberIds.isEmpty()
                    && mMatchWifiNetworkKeys.isEmpty()) {
                return MATCH_WIFI_WILDCARD;
            }
            return mMatchRule;
        }

        /**
         * Builds the instance of the NetworkTemplate.
         *
         * @return the built instance of NetworkTemplate.
         */
        @NonNull
        public NetworkTemplate build() {
            assertRequestableParameters();
            final int subscriberIdMatchRule = mMatchSubscriberIds.isEmpty()
                    ? NetworkStatsUtils.SUBSCRIBER_ID_MATCH_RULE_ALL
                    : NetworkStatsUtils.SUBSCRIBER_ID_MATCH_RULE_EXACT;
            return new NetworkTemplate(getWildcardDeducedMatchRule(),
                    mMatchSubscriberIds.isEmpty() ? null : mMatchSubscriberIds.iterator().next(),
                    mMatchSubscriberIds.toArray(new String[0]),
                    mMatchWifiNetworkKeys.toArray(new String[0]), mMetered, mRoaming,
                    mDefaultNetwork, mRatType, mOemManaged, subscriberIdMatchRule);
        }
    }
}

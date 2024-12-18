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

package com.android.settingslib.wifi;

import static android.net.wifi.WifiConfiguration.NetworkSelectionStatus.NETWORK_SELECTION_ENABLED;
import static android.net.wifi.WifiConfiguration.NetworkSelectionStatus.NETWORK_SELECTION_PERMANENTLY_DISABLED;

import android.annotation.IntDef;
import android.annotation.MainThread;
import android.app.ActivityManager;
import android.app.AppGlobals;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.NetworkInfo.State;
import android.net.NetworkKey;
import android.net.NetworkScoreManager;
import android.net.NetworkScorerAppData;
import android.net.ScoredNetwork;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkScoreCache;
import android.net.wifi.hotspot2.OsuProvider;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.net.wifi.hotspot2.ProvisioningCallback;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.CollectionUtils;
import com.android.settingslib.R;
import com.android.settingslib.utils.ThreadUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Represents a selectable Wifi Network for use in various wifi selection menus backed by
 * {@link WifiTracker}.
 *
 * <p>An AccessPoint, which would be more fittingly named "WifiNetwork", is an aggregation of
 * {@link ScanResult ScanResults} along with pertinent metadata (e.g. current connection info,
 * network scores) required to successfully render the network to the user.
 *
 * @deprecated WifiTracker/AccessPoint is no longer supported, and will be removed in a future
 * release. Clients that need a dynamic list of available wifi networks should migrate to one of the
 * newer tracker classes,
 * {@link com.android.wifitrackerlib.WifiPickerTracker},
 * {@link com.android.wifitrackerlib.SavedNetworkTracker},
 * {@link com.android.wifitrackerlib.NetworkDetailsTracker},
 * in conjunction with {@link com.android.wifitrackerlib.WifiEntry} to represent each wifi network.
 */
@Deprecated
public class AccessPoint implements Comparable<AccessPoint> {
    static final String TAG = "SettingsLib.AccessPoint";

    /**
     * Lower bound on the 2.4 GHz (802.11b/g/n) WLAN channels
     */
    public static final int LOWER_FREQ_24GHZ = 2400;

    /**
     * Upper bound on the 2.4 GHz (802.11b/g/n) WLAN channels
     */
    public static final int HIGHER_FREQ_24GHZ = 2500;

    /**
     * Lower bound on the 5.0 GHz (802.11a/h/j/n/ac) WLAN channels
     */
    public static final int LOWER_FREQ_5GHZ = 4900;

    /**
     * Upper bound on the 5.0 GHz (802.11a/h/j/n/ac) WLAN channels
     */
    public static final int HIGHER_FREQ_5GHZ = 5900;

    /**
     * Lower bound on the 60 GHz (802.11ad) WIGIG channels
     */
    public static final int LOWER_FREQ_60GHZ = 58320;

    /**
     * Upper bound on the 60 GHz (802.11ad) WIGIG channels
     */
    public static final int HIGHER_FREQ_60GHZ = 70200;

    /** The key which identifies this AccessPoint grouping. */
    private String mKey;

    /**
     * Synchronization lock for managing concurrency between main and worker threads.
     *
     * <p>This lock should be held for all modifications to {@link #mScanResults} and
     * {@link #mExtraScanResults}.
     */
    private final Object mLock = new Object();

    @IntDef({Speed.NONE, Speed.SLOW, Speed.MODERATE, Speed.FAST, Speed.VERY_FAST})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Speed {
        /**
         * Constant value representing an unlabeled / unscored network.
         */
        int NONE = 0;
        /**
         * Constant value representing a slow speed network connection.
         */
        int SLOW = 5;
        /**
         * Constant value representing a medium speed network connection.
         */
        int MODERATE = 10;
        /**
         * Constant value representing a fast speed network connection.
         */
        int FAST = 20;
        /**
         * Constant value representing a very fast speed network connection.
         */
        int VERY_FAST = 30;
    }

    @IntDef({PasspointConfigurationVersion.INVALID,
            PasspointConfigurationVersion.NO_OSU_PROVISIONED,
            PasspointConfigurationVersion.OSU_PROVISIONED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface PasspointConfigurationVersion {
        int INVALID = 0;
        int NO_OSU_PROVISIONED = 1; // R1.
        int OSU_PROVISIONED = 2;    // R2 or R3.
    }

    /** The underlying set of scan results comprising this AccessPoint. */
    @GuardedBy("mLock")
    private final ArraySet<ScanResult> mScanResults = new ArraySet<>();

    /**
     * Extra set of unused scan results corresponding to this AccessPoint for verbose logging
     * purposes, such as a set of Passpoint roaming scan results when home scans are available.
     */
    @GuardedBy("mLock")
    private final ArraySet<ScanResult> mExtraScanResults = new ArraySet<>();

    /**
     * Map of BSSIDs to scored networks for individual bssids.
     *
     * <p>This cache should not be evicted with scan results, as the values here are used to
     * generate a fallback in the absence of scores for the visible APs.
     */
    private final Map<String, TimestampedScoredNetwork> mScoredNetworkCache = new HashMap<>();

    static final String KEY_NETWORKINFO = "key_networkinfo";
    static final String KEY_WIFIINFO = "key_wifiinfo";
    static final String KEY_SSID = "key_ssid";
    static final String KEY_SECURITY = "key_security";
    static final String KEY_SPEED = "key_speed";
    static final String KEY_PSKTYPE = "key_psktype";
    static final String KEY_SCANRESULTS = "key_scanresults";
    static final String KEY_SCOREDNETWORKCACHE = "key_scorednetworkcache";
    static final String KEY_CONFIG = "key_config";
    static final String KEY_PASSPOINT_UNIQUE_ID = "key_passpoint_unique_id";
    static final String KEY_FQDN = "key_fqdn";
    static final String KEY_PROVIDER_FRIENDLY_NAME = "key_provider_friendly_name";
    static final String KEY_EAPTYPE = "eap_psktype";
    static final String KEY_SUBSCRIPTION_EXPIRATION_TIME_IN_MILLIS  =
            "key_subscription_expiration_time_in_millis";
    static final String KEY_PASSPOINT_CONFIGURATION_VERSION = "key_passpoint_configuration_version";
    static final String KEY_IS_PSK_SAE_TRANSITION_MODE = "key_is_psk_sae_transition_mode";
    static final String KEY_IS_OWE_TRANSITION_MODE = "key_is_owe_transition_mode";
    static final AtomicInteger sLastId = new AtomicInteger(0);

    /*
     * NOTE: These constants for security and PSK types are saved to the bundle in saveWifiState,
     * and sent across IPC. The numeric values should remain stable, otherwise the changes will need
     * to be synced with other unbundled users of this library.
     */
    public static final int SECURITY_NONE = 0;
    public static final int SECURITY_WEP = 1;
    public static final int SECURITY_PSK = 2;
    public static final int SECURITY_EAP = 3;
    public static final int SECURITY_OWE = 4;
    public static final int SECURITY_SAE = 5;
    public static final int SECURITY_EAP_SUITE_B = 6;
    public static final int SECURITY_EAP_WPA3_ENTERPRISE = 7;
    public static final int SECURITY_MAX_VAL = 8; // Has to be the last

    private static final int PSK_UNKNOWN = 0;
    private static final int PSK_WPA = 1;
    private static final int PSK_WPA2 = 2;
    private static final int PSK_WPA_WPA2 = 3;

    private static final int EAP_UNKNOWN = 0;
    private static final int EAP_WPA = 1; // WPA-EAP
    private static final int EAP_WPA2_WPA3 = 2; // RSN-EAP

    public static final int UNREACHABLE_RSSI = Integer.MIN_VALUE;

    public static final String KEY_PREFIX_AP = "AP:";
    public static final String KEY_PREFIX_PASSPOINT_UNIQUE_ID = "PASSPOINT:";
    public static final String KEY_PREFIX_OSU = "OSU:";

    private final Context mContext;

    private WifiManager mWifiManager;
    private WifiManager.ActionListener mConnectListener;

    private String ssid;
    private String bssid;
    private int security;
    private int networkId = WifiConfiguration.INVALID_NETWORK_ID;

    private int pskType = PSK_UNKNOWN;
    private int mEapType = EAP_UNKNOWN;

    private WifiConfiguration mConfig;

    private int mRssi = UNREACHABLE_RSSI;

    private WifiInfo mInfo;
    private NetworkInfo mNetworkInfo;
    AccessPointListener mAccessPointListener;

    private Object mTag;

    @Speed private int mSpeed = Speed.NONE;
    private boolean mIsScoredNetworkMetered = false;

    /**
     * Information associated with the {@link PasspointConfiguration}.  Only maintaining
     * the relevant info to preserve spaces.
     */
    private String mPasspointUniqueId;
    private String mFqdn;
    private String mProviderFriendlyName;
    private boolean mIsRoaming = false;
    private long mSubscriptionExpirationTimeInMillis;
    @PasspointConfigurationVersion private int mPasspointConfigurationVersion =
            PasspointConfigurationVersion.INVALID;

    private OsuProvider mOsuProvider;

    private String mOsuStatus;
    private String mOsuFailure;
    private boolean mOsuProvisioningComplete = false;

    private boolean mIsPskSaeTransitionMode = false;
    private boolean mIsOweTransitionMode = false;

    public AccessPoint(Context context, Bundle savedState) {
        mContext = context;

        if (savedState.containsKey(KEY_CONFIG)) {
            mConfig = savedState.getParcelable(KEY_CONFIG);
        }
        if (mConfig != null) {
            loadConfig(mConfig);
        }
        if (savedState.containsKey(KEY_SSID)) {
            ssid = savedState.getString(KEY_SSID);
        }
        if (savedState.containsKey(KEY_SECURITY)) {
            security = savedState.getInt(KEY_SECURITY);
        }
        if (savedState.containsKey(KEY_SPEED)) {
            mSpeed = savedState.getInt(KEY_SPEED);
        }
        if (savedState.containsKey(KEY_PSKTYPE)) {
            pskType = savedState.getInt(KEY_PSKTYPE);
        }
        if (savedState.containsKey(KEY_EAPTYPE)) {
            mEapType = savedState.getInt(KEY_EAPTYPE);
        }
        mInfo = savedState.getParcelable(KEY_WIFIINFO);
        if (savedState.containsKey(KEY_NETWORKINFO)) {
            mNetworkInfo = savedState.getParcelable(KEY_NETWORKINFO);
        }
        if (savedState.containsKey(KEY_SCANRESULTS)) {
            Parcelable[] scanResults = savedState.getParcelableArray(KEY_SCANRESULTS);
            mScanResults.clear();
            for (Parcelable result : scanResults) {
                mScanResults.add((ScanResult) result);
            }
        }
        if (savedState.containsKey(KEY_SCOREDNETWORKCACHE)) {
            ArrayList<TimestampedScoredNetwork> scoredNetworkArrayList =
                    savedState.getParcelableArrayList(KEY_SCOREDNETWORKCACHE);
            for (TimestampedScoredNetwork timedScore : scoredNetworkArrayList) {
                mScoredNetworkCache.put(timedScore.getScore().networkKey.wifiKey.bssid, timedScore);
            }
        }
        if (savedState.containsKey(KEY_PASSPOINT_UNIQUE_ID)) {
            mPasspointUniqueId = savedState.getString(KEY_PASSPOINT_UNIQUE_ID);
        }
        if (savedState.containsKey(KEY_FQDN)) {
            mFqdn = savedState.getString(KEY_FQDN);
        }
        if (savedState.containsKey(KEY_PROVIDER_FRIENDLY_NAME)) {
            mProviderFriendlyName = savedState.getString(KEY_PROVIDER_FRIENDLY_NAME);
        }
        if (savedState.containsKey(KEY_SUBSCRIPTION_EXPIRATION_TIME_IN_MILLIS)) {
            mSubscriptionExpirationTimeInMillis =
                    savedState.getLong(KEY_SUBSCRIPTION_EXPIRATION_TIME_IN_MILLIS);
        }
        if (savedState.containsKey(KEY_PASSPOINT_CONFIGURATION_VERSION)) {
            mPasspointConfigurationVersion = savedState.getInt(KEY_PASSPOINT_CONFIGURATION_VERSION);
        }
        if (savedState.containsKey(KEY_IS_PSK_SAE_TRANSITION_MODE)) {
            mIsPskSaeTransitionMode = savedState.getBoolean(KEY_IS_PSK_SAE_TRANSITION_MODE);
        }
        if (savedState.containsKey(KEY_IS_OWE_TRANSITION_MODE)) {
            mIsOweTransitionMode = savedState.getBoolean(KEY_IS_OWE_TRANSITION_MODE);
        }

        update(mConfig, mInfo, mNetworkInfo);

        // Calculate required fields
        updateKey();
        updateBestRssiInfo();
    }

    /**
     * Creates an AccessPoint with only a WifiConfiguration. This is used for the saved networks
     * page.
     */
    public AccessPoint(Context context, WifiConfiguration config) {
        mContext = context;
        loadConfig(config);
        updateKey();
    }

    /**
     * Initialize an AccessPoint object for a {@link PasspointConfiguration}.  This is mainly
     * used by "Saved Networks" page for managing the saved {@link PasspointConfiguration}.
     */
    public AccessPoint(Context context, PasspointConfiguration config) {
        mContext = context;
        mPasspointUniqueId = config.getUniqueId();
        mFqdn = config.getHomeSp().getFqdn();
        mProviderFriendlyName = config.getHomeSp().getFriendlyName();
        mSubscriptionExpirationTimeInMillis = config.getSubscriptionExpirationTimeMillis();
        if (config.isOsuProvisioned()) {
            mPasspointConfigurationVersion = PasspointConfigurationVersion.OSU_PROVISIONED;
        } else {
            mPasspointConfigurationVersion = PasspointConfigurationVersion.NO_OSU_PROVISIONED;
        }
        updateKey();
    }

    /**
     * Initialize an AccessPoint object for a Passpoint network.
     */
    public AccessPoint(@NonNull Context context, @NonNull WifiConfiguration config,
            @Nullable Collection<ScanResult> homeScans,
            @Nullable Collection<ScanResult> roamingScans) {
        mContext = context;
        networkId = config.networkId;
        mConfig = config;
        mPasspointUniqueId = config.getKey();
        mFqdn = config.FQDN;
        setScanResultsPasspoint(homeScans, roamingScans);
        updateKey();
    }

    /**
     * Initialize an AccessPoint object for a Passpoint OSU Provider.
     */
    public AccessPoint(@NonNull Context context, @NonNull OsuProvider provider,
            @NonNull Collection<ScanResult> results) {
        mContext = context;
        mOsuProvider = provider;
        setScanResults(results);
        updateKey();
    }

    AccessPoint(Context context, Collection<ScanResult> results) {
        mContext = context;
        setScanResults(results);
        updateKey();
    }

    @VisibleForTesting void loadConfig(WifiConfiguration config) {
        ssid = (config.SSID == null ? "" : removeDoubleQuotes(config.SSID));
        bssid = config.BSSID;
        security = getSecurity(config);
        networkId = config.networkId;
        mConfig = config;
    }

    /** Updates {@link #mKey} and should only called upon object creation/initialization. */
    private void updateKey() {
        // TODO(sghuman): Consolidate Key logic on ScanResultMatchInfo
        if (isPasspoint()) {
            mKey = getKey(mConfig);
        } else if (isPasspointConfig()) {
            mKey = getKey(mPasspointUniqueId);
        } else if (isOsuProvider()) {
            mKey = getKey(mOsuProvider);
        } else { // Non-Passpoint AP
            mKey = getKey(getSsidStr(), getBssid(), getSecurity());
        }
    }

    /**
    * Returns a negative integer, zero, or a positive integer if this AccessPoint is less than,
    * equal to, or greater than the other AccessPoint.
    *
    * Sort order rules for AccessPoints:
    *   1. Active before inactive
    *   2. Reachable before unreachable
    *   3. Saved before unsaved
    *   4. Network speed value
    *   5. Stronger signal before weaker signal
    *   6. SSID alphabetically
    *
    * Note that AccessPoints with a signal are usually also Reachable,
    * and will thus appear before unreachable saved AccessPoints.
    */
    @Override
    public int compareTo(@NonNull AccessPoint other) {
        // Active one goes first.
        if (isActive() && !other.isActive()) return -1;
        if (!isActive() && other.isActive()) return 1;

        // Reachable one goes before unreachable one.
        if (isReachable() && !other.isReachable()) return -1;
        if (!isReachable() && other.isReachable()) return 1;

        // Configured (saved) one goes before unconfigured one.
        if (isSaved() && !other.isSaved()) return -1;
        if (!isSaved() && other.isSaved()) return 1;

        // Faster speeds go before slower speeds - but only if visible change in speed label
        if (getSpeed() != other.getSpeed()) {
            return other.getSpeed() - getSpeed();
        }

        WifiManager wifiManager = getWifiManager();
        // Sort by signal strength, bucketed by level
        int difference = wifiManager.calculateSignalLevel(other.mRssi)
                - wifiManager.calculateSignalLevel(mRssi);
        if (difference != 0) {
            return difference;
        }

        // Sort by title.
        difference = getTitle().compareToIgnoreCase(other.getTitle());
        if (difference != 0) {
            return difference;
        }

        // Do a case sensitive comparison to distinguish SSIDs that differ in case only
        return getSsidStr().compareTo(other.getSsidStr());
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof AccessPoint)) return false;
        return (this.compareTo((AccessPoint) other) == 0);
    }

    @Override
    public int hashCode() {
        int result = 0;
        if (mInfo != null) result += 13 * mInfo.hashCode();
        result += 19 * mRssi;
        result += 23 * networkId;
        result += 29 * ssid.hashCode();
        return result;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder().append("AccessPoint(")
                .append(ssid);
        if (bssid != null) {
            builder.append(":").append(bssid);
        }
        if (isSaved()) {
            builder.append(',').append("saved");
        }
        if (isActive()) {
            builder.append(',').append("active");
        }
        if (isEphemeral()) {
            builder.append(',').append("ephemeral");
        }
        if (isConnectable()) {
            builder.append(',').append("connectable");
        }
        if ((security != SECURITY_NONE) && (security != SECURITY_OWE)) {
            builder.append(',').append(securityToString(security, pskType));
        }
        builder.append(",level=").append(getLevel());
        if (mSpeed != Speed.NONE) {
            builder.append(",speed=").append(mSpeed);
        }
        builder.append(",metered=").append(isMetered());

        if (isVerboseLoggingEnabled()) {
            builder.append(",rssi=").append(mRssi);
            synchronized (mLock) {
                builder.append(",scan cache size=").append(mScanResults.size()
                        + mExtraScanResults.size());
            }
        }

        return builder.append(')').toString();
    }

    /**
     * Updates the AccessPoint rankingScore, metering, and speed, returning true if the data has
     * changed.
     *
     * @param scoreCache The score cache to use to retrieve scores
     * @param scoringUiEnabled Whether to show scoring and badging UI
     * @param maxScoreCacheAgeMillis the maximum age in milliseconds of scores to consider when
     *         generating speed labels
     */
    boolean update(
            WifiNetworkScoreCache scoreCache,
            boolean scoringUiEnabled,
            long maxScoreCacheAgeMillis) {
        boolean scoreChanged = false;
        if (scoringUiEnabled) {
            scoreChanged = updateScores(scoreCache, maxScoreCacheAgeMillis);
        }
        return updateMetered(scoreCache) || scoreChanged;
    }

    /**
     * Updates the AccessPoint rankingScore and speed, returning true if the data has changed.
     *
     * <p>Any cached {@link TimestampedScoredNetwork} objects older than the given max age in millis
     * will be removed when this method is invoked.
     *
     * <p>Precondition: {@link #mRssi} is up to date before invoking this method.
     *
     * @param scoreCache The score cache to use to retrieve scores
     * @param maxScoreCacheAgeMillis the maximum age in milliseconds of scores to consider when
     *         generating speed labels
     *
     * @return true if the set speed has changed
     */
    private boolean updateScores(WifiNetworkScoreCache scoreCache, long maxScoreCacheAgeMillis) {
        long nowMillis = SystemClock.elapsedRealtime();
        synchronized (mLock) {
            for (ScanResult result : mScanResults) {
                ScoredNetwork score = scoreCache.getScoredNetwork(result);
                if (score == null) {
                    continue;
                }
                TimestampedScoredNetwork timedScore = mScoredNetworkCache.get(result.BSSID);
                if (timedScore == null) {
                    mScoredNetworkCache.put(
                            result.BSSID, new TimestampedScoredNetwork(score, nowMillis));
                } else {
                    // Update data since the has been seen in the score cache
                    timedScore.update(score, nowMillis);
                }
            }
        }

        // Remove old cached networks
        long evictionCutoff = nowMillis - maxScoreCacheAgeMillis;
        Iterator<TimestampedScoredNetwork> iterator = mScoredNetworkCache.values().iterator();
        iterator.forEachRemaining(timestampedScoredNetwork -> {
            if (timestampedScoredNetwork.getUpdatedTimestampMillis() < evictionCutoff) {
                iterator.remove();
            }
        });

        return updateSpeed();
    }

    /**
     * Updates the internal speed, returning true if the update resulted in a speed label change.
     */
    private boolean updateSpeed() {
        int oldSpeed = mSpeed;
        mSpeed = generateAverageSpeedForSsid();

        boolean changed = oldSpeed != mSpeed;
        if(isVerboseLoggingEnabled() && changed) {
            Log.i(TAG, String.format("%s: Set speed to %d", ssid, mSpeed));
        }
        return changed;
    }

    /** Creates a speed value for the current {@link #mRssi} by averaging all non zero badges. */
    @Speed private int generateAverageSpeedForSsid() {
        if (mScoredNetworkCache.isEmpty()) {
            return Speed.NONE;
        }

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, String.format("Generating fallbackspeed for %s using cache: %s",
                    getSsidStr(), mScoredNetworkCache));
        }

        // TODO(b/63073866): If flickering issues persist, consider mapping using getLevel rather
        // than specific rssi value so score doesn't change without a visible wifi bar change. This
        // issue is likely to be more evident for the active AP whose RSSI value is not half-lifed.

        int count = 0;
        int totalSpeed = 0;
        for (TimestampedScoredNetwork timedScore : mScoredNetworkCache.values()) {
            int speed = timedScore.getScore().calculateBadge(mRssi);
            if (speed != Speed.NONE) {
                count++;
                totalSpeed += speed;
            }
        }
        int speed = count == 0 ? Speed.NONE : totalSpeed / count;
        if (isVerboseLoggingEnabled()) {
            Log.i(TAG, String.format("%s generated fallback speed is: %d", getSsidStr(), speed));
        }
        return roundToClosestSpeedEnum(speed);
    }

    /**
     * Updates the AccessPoint's metering based on {@link ScoredNetwork#meteredHint}, returning
     * true if the metering changed.
     */
    private boolean updateMetered(WifiNetworkScoreCache scoreCache) {
        boolean oldMetering = mIsScoredNetworkMetered;
        mIsScoredNetworkMetered = false;

        if (isActive() && mInfo != null) {
            NetworkKey key = NetworkKey.createFromWifiInfo(mInfo);
            ScoredNetwork score = scoreCache.getScoredNetwork(key);
            if (score != null) {
                mIsScoredNetworkMetered |= score.meteredHint;
            }
        } else {
            synchronized (mLock) {
                for (ScanResult result : mScanResults) {
                    ScoredNetwork score = scoreCache.getScoredNetwork(result);
                    if (score == null) {
                        continue;
                    }
                    mIsScoredNetworkMetered |= score.meteredHint;
                }
            }
        }
        return oldMetering != mIsScoredNetworkMetered;
    }

    /**
     * Generates an AccessPoint key for a given scan result
     *
     * @param context
     * @param result Scan result
     * @return AccessPoint key
     */
    public static String getKey(Context context, ScanResult result) {
        return getKey(result.SSID, result.BSSID, getSecurity(context, result));
    }

    /**
     * Returns the AccessPoint key for a WifiConfiguration.
     * This will return a special Passpoint key if the config is for Passpoint.
     */
    public static String getKey(WifiConfiguration config) {
        if (config.isPasspoint()) {
            return getKey(config.getKey());
        } else {
            return getKey(removeDoubleQuotes(config.SSID), config.BSSID, getSecurity(config));
        }
    }

    /**
     * Returns the AccessPoint key corresponding to a Passpoint network by its unique identifier.
     */
    public static String getKey(String passpointUniqueId) {
        return new StringBuilder()
                .append(KEY_PREFIX_PASSPOINT_UNIQUE_ID)
                .append(passpointUniqueId).toString();
    }

    /**
     * Returns the AccessPoint key corresponding to the OsuProvider.
     */
    public static String getKey(OsuProvider provider) {
        return new StringBuilder()
                .append(KEY_PREFIX_OSU)
                .append(provider.getFriendlyName())
                .append(',')
                .append(provider.getServerUri()).toString();
    }

    /**
     * Returns the AccessPoint key for a normal non-Passpoint network by ssid/bssid and security.
     */
    private static String getKey(String ssid, String bssid, int security) {
        StringBuilder builder = new StringBuilder();
        builder.append(KEY_PREFIX_AP);
        if (TextUtils.isEmpty(ssid)) {
            builder.append(bssid);
        } else {
            builder.append(ssid);
        }
        builder.append(',').append(security);
        return builder.toString();
    }

    public String getKey() {
        return mKey;
    }

    /**
     * Determines if the other AccessPoint represents the same network as this AccessPoint
     */
    public boolean matches(AccessPoint other) {
        if (isPasspoint() || isPasspointConfig() || isOsuProvider()) {
            return getKey().equals(other.getKey());
        }

        if (!isSameSsidOrBssid(other)) {
            return false;
        }

        final int otherApSecurity = other.getSecurity();
        if (mIsPskSaeTransitionMode) {
            if (otherApSecurity == SECURITY_SAE && getWifiManager().isWpa3SaeSupported()) {
                return true;
            } else if (otherApSecurity == SECURITY_PSK) {
                return true;
            }
        } else {
            if ((security == SECURITY_SAE || security == SECURITY_PSK)
                    && other.isPskSaeTransitionMode()) {
                return true;
            }
        }

        if (mIsOweTransitionMode) {
            if (otherApSecurity == SECURITY_OWE && getWifiManager().isEnhancedOpenSupported()) {
                return true;
            } else if (otherApSecurity == SECURITY_NONE) {
                return true;
            }
        } else {
            if ((security == SECURITY_OWE || security == SECURITY_NONE)
                    && other.isOweTransitionMode()) {
                return true;
            }
        }

        return security == other.getSecurity();
    }

    public boolean matches(WifiConfiguration config) {
        if (config.isPasspoint()) {
            return (isPasspoint() && config.getKey().equals(mConfig.getKey()));
        }

        if (!ssid.equals(removeDoubleQuotes(config.SSID))
                || (mConfig != null && mConfig.shared != config.shared)) {
            return false;
        }

        final int configSecurity = getSecurity(config);
        if (mIsPskSaeTransitionMode) {
            if (configSecurity == SECURITY_SAE && getWifiManager().isWpa3SaeSupported()) {
                return true;
            } else if (configSecurity == SECURITY_PSK) {
                return true;
            }
        }

        if (mIsOweTransitionMode) {
            if (configSecurity == SECURITY_OWE && getWifiManager().isEnhancedOpenSupported()) {
                return true;
            } else if (configSecurity == SECURITY_NONE) {
                return true;
            }
        }

        return security == getSecurity(config);
    }

    private boolean matches(WifiConfiguration config, WifiInfo wifiInfo) {
        if (config == null || wifiInfo == null) {
            return false;
        }
        if (!config.isPasspoint() && !isSameSsidOrBssid(wifiInfo)) {
            return false;
        }
        return matches(config);
    }

    @VisibleForTesting
    boolean matches(ScanResult scanResult) {
        if (scanResult == null) {
            return false;
        }
        if (isPasspoint() || isOsuProvider()) {
            throw new IllegalStateException("Should not matches a Passpoint by ScanResult");
        }

        if (!isSameSsidOrBssid(scanResult)) {
            return false;
        }

        if (mIsPskSaeTransitionMode) {
            if (scanResult.capabilities.contains("SAE")
                    && getWifiManager().isWpa3SaeSupported()) {
                return true;
            } else if (scanResult.capabilities.contains("PSK")) {
                return true;
            }
        } else {
            if ((security == SECURITY_SAE || security == SECURITY_PSK)
                    && AccessPoint.isPskSaeTransitionMode(scanResult)) {
                return true;
            }
        }

        if (mIsOweTransitionMode) {
            final int scanResultSccurity = getSecurity(mContext, scanResult);
            if (scanResultSccurity == SECURITY_OWE && getWifiManager().isEnhancedOpenSupported()) {
                return true;
            } else if (scanResultSccurity == SECURITY_NONE) {
                return true;
            }
        } else {
            if ((security == SECURITY_OWE || security == SECURITY_NONE)
                    && AccessPoint.isOweTransitionMode(scanResult)) {
                return true;
            }
        }

        return security == getSecurity(mContext, scanResult);
    }

    public WifiConfiguration getConfig() {
        return mConfig;
    }

    public String getPasspointFqdn() {
        return mFqdn;
    }

    public void clearConfig() {
        mConfig = null;
        networkId = WifiConfiguration.INVALID_NETWORK_ID;
    }

    public WifiInfo getInfo() {
        return mInfo;
    }

    /**
     * Returns the number of levels to show for a Wifi icon, from 0 to
     * {@link WifiManager#getMaxSignalLevel()}.
     *
     * <p>Use {@link #isReachable()} to determine if an AccessPoint is in range, as this method will
     * always return at least 0.
     */
    public int getLevel() {
        return getWifiManager().calculateSignalLevel(mRssi);
    }

    public int getRssi() {
        return mRssi;
    }

    /**
     * Returns the underlying scan result set.
     *
     * <p>Callers should not modify this set.
     */
    public Set<ScanResult> getScanResults() {
        Set<ScanResult> allScans = new ArraySet<>();
        synchronized (mLock) {
            allScans.addAll(mScanResults);
            allScans.addAll(mExtraScanResults);
        }
        return allScans;
    }

    public Map<String, TimestampedScoredNetwork> getScoredNetworkCache() {
        return mScoredNetworkCache;
    }

    /**
     * Updates {@link #mRssi} and sets scan result information to that of the best RSSI scan result.
     *
     * <p>If the given connection is active, the existing value of {@link #mRssi} will be returned.
     * If the given AccessPoint is not active, a value will be calculated from previous scan
     * results, returning the best RSSI for all matching AccessPoints averaged with the previous
     * value. If the access point is not connected and there are no scan results, the rssi will be
     * set to {@link #UNREACHABLE_RSSI}.
     */
    private void updateBestRssiInfo() {
        if (this.isActive()) {
            return;
        }

        ScanResult bestResult = null;
        int bestRssi = UNREACHABLE_RSSI;
        synchronized (mLock) {
            for (ScanResult result : mScanResults) {
                if (result.level > bestRssi) {
                    bestRssi = result.level;
                    bestResult = result;
                }
            }
        }

        // Set the rssi to the average of the current rssi and the previous rssi.
        if (bestRssi != UNREACHABLE_RSSI && mRssi != UNREACHABLE_RSSI) {
            mRssi = (mRssi + bestRssi) / 2;
        } else {
            mRssi = bestRssi;
        }

        if (bestResult != null) {
            ssid = bestResult.SSID;
            bssid = bestResult.BSSID;
            security = getSecurity(mContext, bestResult);
            if (security == SECURITY_PSK || security == SECURITY_SAE) {
                pskType = getPskType(bestResult);
            }
            if (security == SECURITY_EAP) {
                mEapType = getEapType(bestResult);
            }

            mIsPskSaeTransitionMode = AccessPoint.isPskSaeTransitionMode(bestResult);
            mIsOweTransitionMode = AccessPoint.isOweTransitionMode(bestResult);
        }
        // Update the config SSID of a Passpoint network to that of the best RSSI
        if (isPasspoint()) {
            mConfig.SSID = convertToQuotedString(ssid);
        }
    }

    /**
     * Returns if the network should be considered metered.
     */
    public boolean isMetered() {
        return mIsScoredNetworkMetered
                || WifiConfiguration.isMetered(mConfig, mInfo);
    }

    public NetworkInfo getNetworkInfo() {
        return mNetworkInfo;
    }

    public int getSecurity() {
        return security;
    }

    public String getSecurityString(boolean concise) {
        Context context = mContext;
        if (isPasspoint() || isPasspointConfig()) {
            return concise ? context.getString(R.string.wifi_security_short_eap) :
                context.getString(R.string.wifi_security_eap);
        }

        if (mIsPskSaeTransitionMode) {
            return concise ? context.getString(R.string.wifi_security_short_psk_sae) :
                    context.getString(R.string.wifi_security_psk_sae);
        }
        if (mIsOweTransitionMode) {
            return concise ? context.getString(R.string.wifi_security_short_none_owe) :
                    context.getString(R.string.wifi_security_none_owe);
        }

        switch(security) {
            case SECURITY_EAP:
                switch (mEapType) {
                    case EAP_WPA:
                        return concise ? context.getString(R.string.wifi_security_short_eap_wpa) :
                                context.getString(R.string.wifi_security_eap_wpa);
                    case EAP_WPA2_WPA3:
                        return concise
                                ? context.getString(R.string.wifi_security_short_eap_wpa2_wpa3) :
                                context.getString(R.string.wifi_security_eap_wpa2_wpa3);
                    case EAP_UNKNOWN:
                    default:
                        return concise
                                ? context.getString(R.string.wifi_security_short_eap) :
                                context.getString(R.string.wifi_security_eap);
                }
            case SECURITY_EAP_SUITE_B:
                return concise ? context.getString(R.string.wifi_security_short_eap_suiteb) :
                        context.getString(R.string.wifi_security_eap_suiteb);
            case SECURITY_PSK:
                switch (pskType) {
                    case PSK_WPA:
                        return concise ? context.getString(R.string.wifi_security_short_wpa) :
                            context.getString(R.string.wifi_security_wpa);
                    case PSK_WPA2:
                        return concise ? context.getString(R.string.wifi_security_short_wpa2) :
                            context.getString(R.string.wifi_security_wpa2);
                    case PSK_WPA_WPA2:
                        return concise ? context.getString(R.string.wifi_security_short_wpa_wpa2) :
                            context.getString(R.string.wifi_security_wpa_wpa2);
                    case PSK_UNKNOWN:
                    default:
                        return concise ? context.getString(R.string.wifi_security_short_psk_generic)
                                : context.getString(R.string.wifi_security_psk_generic);
                }
            case SECURITY_WEP:
                return concise ? context.getString(R.string.wifi_security_short_wep) :
                    context.getString(R.string.wifi_security_wep);
            case SECURITY_SAE:
                return concise ? context.getString(R.string.wifi_security_short_sae) :
                        context.getString(R.string.wifi_security_sae);
            case SECURITY_OWE:
                return concise ? context.getString(R.string.wifi_security_short_owe) :
                    context.getString(R.string.wifi_security_owe);
            case SECURITY_NONE:
            default:
                return concise ? "" : context.getString(R.string.wifi_security_none);
        }
    }

    public String getSsidStr() {
        return ssid;
    }

    public String getBssid() {
        return bssid;
    }

    public CharSequence getSsid() {
        return ssid;
    }

    /**
     * Returns the name associated with the stored config.
     * @deprecated Please use {@link #getTitle()} instead to get the display name of an AccessPoint.
     */
    @Deprecated
    public String getConfigName() {
        if (mConfig != null && mConfig.isPasspoint()) {
            return mConfig.providerFriendlyName;
        } else if (mPasspointUniqueId != null) {
            return mProviderFriendlyName;
        } else {
            return ssid;
        }
    }

    public DetailedState getDetailedState() {
        if (mNetworkInfo != null) {
            return mNetworkInfo.getDetailedState();
        }
        Log.w(TAG, "NetworkInfo is null, cannot return detailed state");
        return null;
    }

    public String getSavedNetworkSummary() {
        WifiConfiguration config = mConfig;
        if (config != null) {
            PackageManager pm = mContext.getPackageManager();
            String systemName = pm.getNameForUid(android.os.Process.SYSTEM_UID);
            int userId = UserHandle.getUserId(config.creatorUid);
            ApplicationInfo appInfo = null;
            if (config.creatorName != null && config.creatorName.equals(systemName)) {
                appInfo = mContext.getApplicationInfo();
            } else {
                try {
                    IPackageManager ipm = AppGlobals.getPackageManager();
                    appInfo = ipm.getApplicationInfo(config.creatorName, 0 /* flags */, userId);
                } catch (RemoteException rex) {
                }
            }
            if (appInfo != null &&
                    !appInfo.packageName.equals(mContext.getString(R.string.settings_package)) &&
                    !appInfo.packageName.equals(
                    mContext.getString(R.string.certinstaller_package))) {
                return mContext.getString(R.string.saved_network, appInfo.loadLabel(pm));
            }
        }

        if (isPasspointConfigurationR1() && isExpired()) {
            return mContext.getString(R.string.wifi_passpoint_expired);
        }
        return "";
    }

    /**
     * Returns the display title for the AccessPoint, such as for an AccessPointPreference's title.
     */
    public String getTitle() {
        if (isPasspoint() && !TextUtils.isEmpty(mConfig.providerFriendlyName)) {
            return mConfig.providerFriendlyName;
        } else if (isPasspointConfig() && !TextUtils.isEmpty(mProviderFriendlyName)) {
            return mProviderFriendlyName;
        } else if (isOsuProvider() && !TextUtils.isEmpty(mOsuProvider.getFriendlyName())) {
            return mOsuProvider.getFriendlyName();
        } else if (!TextUtils.isEmpty(getSsidStr())) {
            return getSsidStr();
        } else {
            return "";
        }
    }

    public String getSummary() {
        return getSettingsSummary();
    }

    public String getSettingsSummary() {
        return getSettingsSummary(false /*convertSavedAsDisconnected*/);
    }

    /**
     * Returns the summary for the AccessPoint.
     */
    public String getSettingsSummary(boolean convertSavedAsDisconnected) {
        if (isPasspointConfigurationR1() && isExpired()) {
            return mContext.getString(R.string.wifi_passpoint_expired);
        }

        // Update to new summary
        StringBuilder summary = new StringBuilder();

        if (isOsuProvider()) {
            if (mOsuProvisioningComplete) {
                summary.append(mContext.getString(R.string.osu_sign_up_complete));
            } else if (mOsuFailure != null) {
                summary.append(mOsuFailure);
            } else if (mOsuStatus != null) {
                summary.append(mOsuStatus);
            } else {
                summary.append(mContext.getString(R.string.tap_to_sign_up));
            }
        } else if (isActive()) {
            summary.append(getSummary(mContext, /* ssid */ null, getDetailedState(),
                    mInfo != null && mInfo.isEphemeral(),
                    mInfo != null ? mInfo.getRequestingPackageName() : null));
        } else { // not active
            if (mConfig != null && mConfig.hasNoInternetAccess()) {
                int messageID =
                        mConfig.getNetworkSelectionStatus().getNetworkSelectionStatus()
                                == NETWORK_SELECTION_PERMANENTLY_DISABLED
                        ? R.string.wifi_no_internet_no_reconnect
                        : R.string.wifi_no_internet;
                summary.append(mContext.getString(messageID));
            } else if (mConfig != null
                    && (mConfig.getNetworkSelectionStatus().getNetworkSelectionStatus()
                            != NETWORK_SELECTION_ENABLED)) {
                WifiConfiguration.NetworkSelectionStatus networkStatus =
                        mConfig.getNetworkSelectionStatus();
                switch (networkStatus.getNetworkSelectionDisableReason()) {
                    case WifiConfiguration.NetworkSelectionStatus.DISABLED_AUTHENTICATION_FAILURE:
                        summary.append(mContext.getString(R.string.wifi_disabled_password_failure));
                        break;
                    case WifiConfiguration.NetworkSelectionStatus.DISABLED_BY_WRONG_PASSWORD:
                        summary.append(mContext.getString(R.string.wifi_check_password_try_again));
                        break;
                    case WifiConfiguration.NetworkSelectionStatus.DISABLED_DHCP_FAILURE:
                        summary.append(mContext.getString(R.string.wifi_disabled_network_failure));
                        break;
                    case WifiConfiguration.NetworkSelectionStatus.DISABLED_ASSOCIATION_REJECTION:
                        summary.append(mContext.getString(R.string.wifi_disabled_generic));
                        break;
                }
            } else if (!isReachable()) { // Wifi out of range
                summary.append(mContext.getString(R.string.wifi_not_in_range));
            } else { // In range, not disabled.
                if (mConfig != null) { // Is saved network
                    // Last attempt to connect to this failed. Show reason why
                    switch (mConfig.getRecentFailureReason()) {
                        case WifiConfiguration.RECENT_FAILURE_AP_UNABLE_TO_HANDLE_NEW_STA:
                            summary.append(mContext.getString(
                                    R.string.wifi_ap_unable_to_handle_new_sta));
                            break;
                        default:
                            if (convertSavedAsDisconnected) {
                                // Disconnected
                                summary.append(mContext.getString(R.string.wifi_disconnected));
                            } else {
                                // "Saved"
                                summary.append(mContext.getString(R.string.wifi_remembered));
                            }
                            break;
                    }
                }
            }
        }



        if (isVerboseLoggingEnabled()) {
            summary.append(WifiUtils.buildLoggingSummary(this, mConfig));
        }

        if (mConfig != null && (WifiUtils.isMeteredOverridden(mConfig) || mConfig.meteredHint)) {
            return mContext.getResources().getString(
                    R.string.preference_summary_default_combination,
                    WifiUtils.getMeteredLabel(mContext, mConfig),
                    summary.toString());
        }

        // If Speed label and summary are both present, use the preference combination to combine
        // the two, else return the non-null one.
        if (getSpeedLabel() != null && summary.length() != 0) {
            return mContext.getResources().getString(
                    R.string.preference_summary_default_combination,
                    getSpeedLabel(),
                    summary.toString());
        } else if (getSpeedLabel() != null) {
            return getSpeedLabel();
        } else {
            return summary.toString();
        }
    }

    /**
     * Return whether this is the active connection.
     * For ephemeral connections (networkId is invalid), this returns false if the network is
     * disconnected.
     */
    public boolean isActive() {
        return mNetworkInfo != null &&
                (networkId != WifiConfiguration.INVALID_NETWORK_ID ||
                 mNetworkInfo.getState() != State.DISCONNECTED);
    }

    public boolean isConnectable() {
        return getLevel() != -1 && getDetailedState() == null;
    }

    public boolean isEphemeral() {
        return mInfo != null && mInfo.isEphemeral() &&
                mNetworkInfo != null && mNetworkInfo.getState() != State.DISCONNECTED;
    }

    /**
     * Return true if this AccessPoint represents a Passpoint AP.
     */
    public boolean isPasspoint() {
        return mConfig != null && mConfig.isPasspoint();
    }

    /**
     * Return true if this AccessPoint represents a Passpoint provider configuration.
     */
    public boolean isPasspointConfig() {
        return mPasspointUniqueId != null && mConfig == null;
    }

    /**
     * Return true if this AccessPoint represents an OSU Provider.
     */
    public boolean isOsuProvider() {
        return mOsuProvider != null;
    }

    /**
     * Return true if this AccessPoint is expired.
     */
    public boolean isExpired() {
        if (mSubscriptionExpirationTimeInMillis <= 0) {
            // Expiration time not specified.
            return false;
        } else {
            return System.currentTimeMillis() >= mSubscriptionExpirationTimeInMillis;
        }
    }

    public boolean isPasspointConfigurationR1() {
        return mPasspointConfigurationVersion == PasspointConfigurationVersion.NO_OSU_PROVISIONED;
    }

    /**
     * Return true if {@link PasspointConfiguration#isOsuProvisioned} is true, this may refer to R2
     * or R3.
     */
    public boolean isPasspointConfigurationOsuProvisioned() {
        return mPasspointConfigurationVersion == PasspointConfigurationVersion.OSU_PROVISIONED;
    }

    /**
     * Starts the OSU Provisioning flow.
     */
    public void startOsuProvisioning(@Nullable WifiManager.ActionListener connectListener) {
        mConnectListener = connectListener;

        getWifiManager().startSubscriptionProvisioning(
                mOsuProvider,
                mContext.getMainExecutor(),
                new AccessPointProvisioningCallback()
        );
    }

    /**
     * Return whether the given {@link WifiInfo} is for this access point.
     * If the current AP does not have a network Id then the config is used to
     * match based on SSID and security.
     */
    private boolean isInfoForThisAccessPoint(WifiConfiguration config, WifiInfo info) {
        if (info.isOsuAp() || mOsuStatus != null) {
            return (info.isOsuAp() && mOsuStatus != null);
        } else if (info.isPasspointAp() || isPasspoint()) {
            // TODO: Use TextUtils.equals(info.getPasspointUniqueId(), mConfig.getKey()) when API
            //  is available
            return (info.isPasspointAp() && isPasspoint()
                    && TextUtils.equals(info.getPasspointFqdn(), mConfig.FQDN)
                    && TextUtils.equals(info.getPasspointProviderFriendlyName(),
                    mConfig.providerFriendlyName));
        }

        if (networkId != WifiConfiguration.INVALID_NETWORK_ID) {
            return networkId == info.getNetworkId();
        } else if (config != null) {
            return matches(config, info);
        } else {
            // Might be an ephemeral connection with no WifiConfiguration. Try matching on SSID.
            // (Note that we only do this if the WifiConfiguration explicitly equals INVALID).
            // TODO: Handle hex string SSIDs.
            return TextUtils.equals(removeDoubleQuotes(info.getSSID()), ssid);
        }
    }

    public boolean isSaved() {
        return mConfig != null;
    }

    public Object getTag() {
        return mTag;
    }

    public void setTag(Object tag) {
        mTag = tag;
    }

    /**
     * Generate and save a default wifiConfiguration with common values.
     * Can only be called for unsecured networks.
     */
    public void generateOpenNetworkConfig() {
        if (!isOpenNetwork()) {
            throw new IllegalStateException();
        }
        if (mConfig != null)
            return;
        mConfig = new WifiConfiguration();
        mConfig.SSID = AccessPoint.convertToQuotedString(ssid);

        if (security == SECURITY_NONE) {
            mConfig.allowedKeyManagement.set(KeyMgmt.NONE);
        } else {
            mConfig.allowedKeyManagement.set(KeyMgmt.OWE);
            mConfig.requirePmf = true;
        }
    }

    public void saveWifiState(Bundle savedState) {
        if (ssid != null) savedState.putString(KEY_SSID, getSsidStr());
        savedState.putInt(KEY_SECURITY, security);
        savedState.putInt(KEY_SPEED, mSpeed);
        savedState.putInt(KEY_PSKTYPE, pskType);
        savedState.putInt(KEY_EAPTYPE, mEapType);
        if (mConfig != null) savedState.putParcelable(KEY_CONFIG, mConfig);
        savedState.putParcelable(KEY_WIFIINFO, mInfo);
        synchronized (mLock) {
            savedState.putParcelableArray(KEY_SCANRESULTS,
                    mScanResults.toArray(new Parcelable[mScanResults.size()
                            + mExtraScanResults.size()]));
        }
        savedState.putParcelableArrayList(KEY_SCOREDNETWORKCACHE,
                new ArrayList<>(mScoredNetworkCache.values()));
        if (mNetworkInfo != null) {
            savedState.putParcelable(KEY_NETWORKINFO, mNetworkInfo);
        }
        if (mPasspointUniqueId != null) {
            savedState.putString(KEY_PASSPOINT_UNIQUE_ID, mPasspointUniqueId);
        }
        if (mFqdn != null) {
            savedState.putString(KEY_FQDN, mFqdn);
        }
        if (mProviderFriendlyName != null) {
            savedState.putString(KEY_PROVIDER_FRIENDLY_NAME, mProviderFriendlyName);
        }
        savedState.putLong(KEY_SUBSCRIPTION_EXPIRATION_TIME_IN_MILLIS,
                mSubscriptionExpirationTimeInMillis);
        savedState.putInt(KEY_PASSPOINT_CONFIGURATION_VERSION, mPasspointConfigurationVersion);
        savedState.putBoolean(KEY_IS_PSK_SAE_TRANSITION_MODE, mIsPskSaeTransitionMode);
        savedState.putBoolean(KEY_IS_OWE_TRANSITION_MODE, mIsOweTransitionMode);
    }

    public void setListener(AccessPointListener listener) {
        mAccessPointListener = listener;
    }

    /**
     * Sets {@link #mScanResults} to the given collection and updates info based on the best RSSI
     * scan result.
     *
     * @param scanResults a collection of scan results to add to the internal set
     */
    void setScanResults(Collection<ScanResult> scanResults) {
        if (CollectionUtils.isEmpty(scanResults)) {
            Log.d(TAG, "Cannot set scan results to empty list");
            return;
        }

        // Validate scan results are for current AP only by matching SSID/BSSID
        // Passpoint networks are not bound to a specific SSID/BSSID, so skip this for passpoint.
        if (mKey != null && !isPasspoint() && !isOsuProvider()) {
            for (ScanResult result : scanResults) {
                if (!matches(result)) {
                    Log.d(TAG, String.format(
                            "ScanResult %s\nkey of %s did not match current AP key %s",
                            result, getKey(mContext, result), mKey));
                    return;
                }
            }
        }

        int oldLevel = getLevel();
        synchronized (mLock) {
            mScanResults.clear();
            mScanResults.addAll(scanResults);
        }
        updateBestRssiInfo();
        int newLevel = getLevel();

        // If newLevel is 0, there will be no displayed Preference since the AP is unreachable
        if (newLevel > 0 && newLevel != oldLevel) {
            // Only update labels on visible rssi changes
            updateSpeed();
            ThreadUtils.postOnMainThread(() -> {
                if (mAccessPointListener != null) {
                    mAccessPointListener.onLevelChanged(this);
                }
            });

        }

        ThreadUtils.postOnMainThread(() -> {
            if (mAccessPointListener != null) {
                mAccessPointListener.onAccessPointChanged(this);
            }
        });
    }

    /**
     * Sets the internal scan result cache to the list of home scans.
     * If there are no home scans, then the roaming scan list is used, and the AccessPoint is
     * marked as roaming.
     */
    void setScanResultsPasspoint(
            @Nullable Collection<ScanResult> homeScans,
            @Nullable Collection<ScanResult> roamingScans) {
        synchronized (mLock) {
            mExtraScanResults.clear();
            if (!CollectionUtils.isEmpty(homeScans)) {
                mIsRoaming = false;
                if (!CollectionUtils.isEmpty(roamingScans)) {
                    mExtraScanResults.addAll(roamingScans);
                }
                setScanResults(homeScans);
            } else if (!CollectionUtils.isEmpty(roamingScans)) {
                mIsRoaming = true;
                setScanResults(roamingScans);
            }
        }
    }

    /**
     * Attempt to update the AccessPoint with the current connection info.
     * This is used to set an AccessPoint to the active one if the connection info matches, or
     * conversely to set an AccessPoint to inactive if the connection info does not match. The RSSI
     * is also updated upon a match. Listeners will be notified if an update occurred.
     *
     * This is called in {@link WifiTracker#updateAccessPoints} as well as in callbacks for handling
     * NETWORK_STATE_CHANGED_ACTION, RSSI_CHANGED_ACTION, and onCapabilitiesChanged in WifiTracker.
     *
     * Returns true if an update occurred.
     */
    public boolean update(
            @Nullable WifiConfiguration config, WifiInfo info, NetworkInfo networkInfo) {
        boolean updated = false;
        final int oldLevel = getLevel();
        if (info != null && isInfoForThisAccessPoint(config, info)) {
            updated = (mInfo == null);
            if (!isPasspoint() && mConfig != config) {
                // We do not set updated = true as we do not want to increase the amount of sorting
                // and copying performed in WifiTracker at this time. If issues involving refresh
                // are still seen, we will investigate further.
                update(config); // Notifies the AccessPointListener of the change
            }
            if (mRssi != info.getRssi() && info.getRssi() != WifiInfo.INVALID_RSSI) {
                mRssi = info.getRssi();
                updated = true;
            } else if (mNetworkInfo != null && networkInfo != null
                    && mNetworkInfo.getDetailedState() != networkInfo.getDetailedState()) {
                updated = true;
            }
            mInfo = info;
            mNetworkInfo = networkInfo;
        } else if (mInfo != null) {
            updated = true;
            mInfo = null;
            mNetworkInfo = null;
        }
        if (updated && mAccessPointListener != null) {
            ThreadUtils.postOnMainThread(() -> {
                if (mAccessPointListener != null) {
                    mAccessPointListener.onAccessPointChanged(this);
                }
            });

            if (oldLevel != getLevel() /* current level */) {
                ThreadUtils.postOnMainThread(() -> {
                    if (mAccessPointListener != null) {
                        mAccessPointListener.onLevelChanged(this);
                    }
                });
            }
        }

        return updated;
    }

    void update(@Nullable WifiConfiguration config) {
        mConfig = config;
        if (mConfig != null && !isPasspoint()) {
            ssid = removeDoubleQuotes(mConfig.SSID);
        }
        networkId = config != null ? config.networkId : WifiConfiguration.INVALID_NETWORK_ID;
        ThreadUtils.postOnMainThread(() -> {
            if (mAccessPointListener != null) {
                mAccessPointListener.onAccessPointChanged(this);
            }
        });
    }

    @VisibleForTesting
    void setRssi(int rssi) {
        mRssi = rssi;
    }

    /** Sets the rssi to {@link #UNREACHABLE_RSSI}. */
    void setUnreachable() {
        setRssi(AccessPoint.UNREACHABLE_RSSI);
    }

    int getSpeed() { return mSpeed;}

    @Nullable
    String getSpeedLabel() {
        return getSpeedLabel(mSpeed);
    }

    @Nullable
    @Speed
    private static int roundToClosestSpeedEnum(int speed) {
        if (speed < Speed.SLOW) {
            return Speed.NONE;
        } else if (speed < (Speed.SLOW + Speed.MODERATE) / 2) {
            return Speed.SLOW;
        } else if (speed < (Speed.MODERATE + Speed.FAST) / 2) {
            return Speed.MODERATE;
        } else if (speed < (Speed.FAST + Speed.VERY_FAST) / 2) {
            return Speed.FAST;
        } else {
            return Speed.VERY_FAST;
        }
    }

    @Nullable
    String getSpeedLabel(@Speed int speed) {
        return getSpeedLabel(mContext, speed);
    }

    private static String getSpeedLabel(Context context, int speed) {
        switch (speed) {
            case Speed.VERY_FAST:
                return context.getString(R.string.speed_label_very_fast);
            case Speed.FAST:
                return context.getString(R.string.speed_label_fast);
            case Speed.MODERATE:
                return context.getString(R.string.speed_label_okay);
            case Speed.SLOW:
                return context.getString(R.string.speed_label_slow);
            case Speed.NONE:
            default:
                return null;
        }
    }

    /** Return the speed label for a {@link ScoredNetwork} at the specified {@code rssi} level. */
    @Nullable
    public static String getSpeedLabel(Context context, ScoredNetwork scoredNetwork, int rssi) {
        return getSpeedLabel(context, roundToClosestSpeedEnum(scoredNetwork.calculateBadge(rssi)));
    }

    /** Return true if the current RSSI is reachable, and false otherwise. */
    public boolean isReachable() {
        return mRssi != UNREACHABLE_RSSI;
    }

    private static CharSequence getAppLabel(String packageName, PackageManager packageManager) {
        CharSequence appLabel = "";
        ApplicationInfo appInfo = null;
        try {
            int userId = ActivityManager.getCurrentUser();
            appInfo = packageManager.getApplicationInfoAsUser(packageName, 0 /* flags */, userId);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Failed to get app info", e);
            return appLabel;
        }
        if (appInfo != null) {
            appLabel = appInfo.loadLabel(packageManager);
        }
        return appLabel;
    }

    public static String getSummary(Context context, String ssid, DetailedState state,
            boolean isEphemeral, String suggestionOrSpecifierPackageName) {
        if (state == DetailedState.CONNECTED) {
            if (isEphemeral && !TextUtils.isEmpty(suggestionOrSpecifierPackageName)) {
                CharSequence appLabel =
                        getAppLabel(suggestionOrSpecifierPackageName, context.getPackageManager());
                return context.getString(R.string.connected_via_app, appLabel);
            } else if (isEphemeral) {
                // Special case for connected + ephemeral networks.
                final NetworkScoreManager networkScoreManager = context.getSystemService(
                        NetworkScoreManager.class);
                NetworkScorerAppData scorer = networkScoreManager.getActiveScorer();
                if (scorer != null && scorer.getRecommendationServiceLabel() != null) {
                    String format = context.getString(R.string.connected_via_network_scorer);
                    return String.format(format, scorer.getRecommendationServiceLabel());
                } else {
                    return context.getString(R.string.connected_via_network_scorer_default);
                }
            }
        }

        // Case when there is wifi connected without internet connectivity.
        final ConnectivityManager cm = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (state == DetailedState.CONNECTED) {
            WifiManager wifiManager = context.getSystemService(WifiManager.class);
            NetworkCapabilities nc = cm.getNetworkCapabilities(wifiManager.getCurrentNetwork());

            if (nc != null) {
                if (nc.hasCapability(nc.NET_CAPABILITY_CAPTIVE_PORTAL)) {
                    int id = context.getResources()
                            .getIdentifier("network_available_sign_in", "string", "android");
                    return context.getString(id);
                } else if (nc.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_PARTIAL_CONNECTIVITY)) {
                    return context.getString(R.string.wifi_limited_connection);
                } else if (!nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    final String mode = Settings.Global.getString(context.getContentResolver(),
                            Settings.Global.PRIVATE_DNS_MODE);
                    if (nc.isPrivateDnsBroken()) {
                        return context.getString(R.string.private_dns_broken);
                    } else {
                        return context.getString(R.string.wifi_connected_no_internet);
                    }
                }
            }
        }
        if (state == null) {
            Log.w(TAG, "state is null, returning empty summary");
            return "";
        }
        String[] formats = context.getResources().getStringArray((ssid == null)
                ? R.array.wifi_status : R.array.wifi_status_with_ssid);
        int index = state.ordinal();

        if (index >= formats.length || formats[index].length() == 0) {
            return "";
        }
        return String.format(formats[index], ssid);
    }

    public static String convertToQuotedString(String string) {
        return "\"" + string + "\"";
    }

    private static int getPskType(ScanResult result) {
        boolean wpa = result.capabilities.contains("WPA-PSK");
        boolean wpa2 = result.capabilities.contains("RSN-PSK");
        boolean wpa3 = result.capabilities.contains("RSN-SAE");
        if (wpa2 && wpa) {
            return PSK_WPA_WPA2;
        } else if (wpa2) {
            return PSK_WPA2;
        } else if (wpa) {
            return PSK_WPA;
        } else {
            if (!wpa3) {
                // Suppress warning for WPA3 only networks
                Log.w(TAG, "Received abnormal flag string: " + result.capabilities);
            }
            return PSK_UNKNOWN;
        }
    }

    private static int getEapType(ScanResult result) {
        // WPA2-Enterprise and WPA3-Enterprise (non 192-bit) advertise RSN-EAP-CCMP
        if (result.capabilities.contains("RSN-EAP")) {
            return EAP_WPA2_WPA3;
        }
        // WPA-Enterprise advertises WPA-EAP-TKIP
        if (result.capabilities.contains("WPA-EAP")) {
            return EAP_WPA;
        }
        return EAP_UNKNOWN;
    }

    private static int getSecurity(Context context, ScanResult result) {
        final boolean isWep = result.capabilities.contains("WEP");
        final boolean isSae = result.capabilities.contains("SAE");
        final boolean isPsk = result.capabilities.contains("PSK");
        final boolean isEapSuiteB192 = result.capabilities.contains("EAP_SUITE_B_192");
        final boolean isEap = result.capabilities.contains("EAP");
        final boolean isOwe = result.capabilities.contains("OWE");
        final boolean isOweTransition = result.capabilities.contains("OWE_TRANSITION");

        if (isSae && isPsk) {
            final WifiManager wifiManager = (WifiManager)
                    context.getSystemService(Context.WIFI_SERVICE);
            return wifiManager.isWpa3SaeSupported() ? SECURITY_SAE : SECURITY_PSK;
        }
        if (isOweTransition) {
            final WifiManager wifiManager = (WifiManager)
                    context.getSystemService(Context.WIFI_SERVICE);
            return wifiManager.isEnhancedOpenSupported() ? SECURITY_OWE : SECURITY_NONE;
        }

        if (isWep) {
            return SECURITY_WEP;
        } else if (isSae) {
            return SECURITY_SAE;
        } else if (isPsk) {
            return SECURITY_PSK;
        } else if (isEapSuiteB192) {
            return SECURITY_EAP_SUITE_B;
        } else if (isEap) {
            return SECURITY_EAP;
        } else if (isOwe) {
            return SECURITY_OWE;
        }
        return SECURITY_NONE;
    }

    static int getSecurity(WifiConfiguration config) {
        if (config.allowedKeyManagement.get(KeyMgmt.SAE)) {
            return SECURITY_SAE;
        }
        if (config.allowedKeyManagement.get(KeyMgmt.WPA_PSK)) {
            return SECURITY_PSK;
        }
        if (config.allowedKeyManagement.get(KeyMgmt.SUITE_B_192)) {
            return SECURITY_EAP_SUITE_B;
        }
        if (config.allowedKeyManagement.get(KeyMgmt.WPA_EAP) ||
                config.allowedKeyManagement.get(KeyMgmt.IEEE8021X)) {
            return SECURITY_EAP;
        }
        if (config.allowedKeyManagement.get(KeyMgmt.OWE)) {
            return SECURITY_OWE;
        }
        return (config.wepTxKeyIndex >= 0
                && config.wepTxKeyIndex < config.wepKeys.length
                && config.wepKeys[config.wepTxKeyIndex] != null)
                ? SECURITY_WEP : SECURITY_NONE;
    }

    public static String securityToString(int security, int pskType) {
        if (security == SECURITY_WEP) {
            return "WEP";
        } else if (security == SECURITY_PSK) {
            if (pskType == PSK_WPA) {
                return "WPA";
            } else if (pskType == PSK_WPA2) {
                return "WPA2";
            } else if (pskType == PSK_WPA_WPA2) {
                return "WPA_WPA2";
            }
            return "PSK";
        } else if (security == SECURITY_EAP) {
            return "EAP";
        } else if (security == SECURITY_SAE) {
            return "SAE";
        } else if (security == SECURITY_EAP_SUITE_B) {
            return "SUITE_B";
        } else if (security == SECURITY_OWE) {
            return "OWE";
        }
        return "NONE";
    }

    static String removeDoubleQuotes(String string) {
        if (TextUtils.isEmpty(string)) {
            return "";
        }
        int length = string.length();
        if ((length > 1) && (string.charAt(0) == '"')
                && (string.charAt(length - 1) == '"')) {
            return string.substring(1, length - 1);
        }
        return string;
    }

    private WifiManager getWifiManager() {
        if (mWifiManager == null) {
            mWifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        }
        return mWifiManager;
    }

    /**
     * Return true if this is an open network AccessPoint.
     */
    public boolean isOpenNetwork() {
        return security == SECURITY_NONE || security == SECURITY_OWE;
    }

    /**
     * Callbacks relaying changes to the AccessPoint representation.
     *
     * <p>All methods are invoked on the Main Thread.
     */
    public interface AccessPointListener {

        /**
         * Indicates a change to the externally visible state of the AccessPoint trigger by an
         * update of ScanResults, saved configuration state, connection state, or score
         * (labels/metered) state.
         *
         * <p>Clients should refresh their view of the AccessPoint to match the updated state when
         * this is invoked. Overall this method is extraneous if clients are listening to
         * {@link WifiTracker.WifiListener#onAccessPointsChanged()} callbacks.
         *
         * <p>Examples of changes include signal strength, connection state, speed label, and
         * generally anything that would impact the summary string.
         *
         * @param accessPoint The accessPoint object the listener was registered on which has
         *                    changed
         */
        @MainThread void onAccessPointChanged(AccessPoint accessPoint);
        /**
         * Indicates the "wifi pie signal level" has changed, retrieved via calls to
         * {@link AccessPoint#getLevel()}.
         *
         * <p>This call is a subset of {@link #onAccessPointChanged(AccessPoint)} , hence is also
         * extraneous if the client is already reacting to that or the
         * {@link WifiTracker.WifiListener#onAccessPointsChanged()} callbacks.
         *
         * @param accessPoint The accessPoint object the listener was registered on whose level has
         *                    changed
         */
        @MainThread void onLevelChanged(AccessPoint accessPoint);
    }

    private static boolean isVerboseLoggingEnabled() {
        return WifiTracker.sVerboseLogging || Log.isLoggable(TAG, Log.VERBOSE);
    }

    /**
     * Callbacks relaying changes to the OSU provisioning status started in startOsuProvisioning().
     *
     * All methods are invoked on the Main Thread
     */
    @VisibleForTesting
    class AccessPointProvisioningCallback extends ProvisioningCallback {
        @Override
        @MainThread public void onProvisioningFailure(int status) {
            if (TextUtils.equals(mOsuStatus, mContext.getString(R.string.osu_completing_sign_up))) {
                mOsuFailure = mContext.getString(R.string.osu_sign_up_failed);
            } else {
                mOsuFailure = mContext.getString(R.string.osu_connect_failed);
            }
            mOsuStatus = null;
            mOsuProvisioningComplete = false;
            ThreadUtils.postOnMainThread(() -> {
                if (mAccessPointListener != null) {
                    mAccessPointListener.onAccessPointChanged(AccessPoint.this);
                }
            });
        }

        @Override
        @MainThread public void onProvisioningStatus(int status) {
            String newStatus = null;
            switch (status) {
                case OSU_STATUS_AP_CONNECTING:
                case OSU_STATUS_AP_CONNECTED:
                case OSU_STATUS_SERVER_CONNECTING:
                case OSU_STATUS_SERVER_VALIDATED:
                case OSU_STATUS_SERVER_CONNECTED:
                case OSU_STATUS_INIT_SOAP_EXCHANGE:
                case OSU_STATUS_WAITING_FOR_REDIRECT_RESPONSE:
                    newStatus = String.format(mContext.getString(R.string.osu_opening_provider),
                            mOsuProvider.getFriendlyName());
                    break;
                case OSU_STATUS_REDIRECT_RESPONSE_RECEIVED:
                case OSU_STATUS_SECOND_SOAP_EXCHANGE:
                case OSU_STATUS_THIRD_SOAP_EXCHANGE:
                case OSU_STATUS_RETRIEVING_TRUST_ROOT_CERTS:
                    newStatus = mContext.getString(
                            R.string.osu_completing_sign_up);
                    break;
            }
            boolean updated = !TextUtils.equals(mOsuStatus, newStatus);
            mOsuStatus = newStatus;
            mOsuFailure = null;
            mOsuProvisioningComplete = false;
            if (updated) {
                ThreadUtils.postOnMainThread(() -> {
                    if (mAccessPointListener != null) {
                        mAccessPointListener.onAccessPointChanged(AccessPoint.this);
                    }
                });
            }
        }

        @Override
        @MainThread public void onProvisioningComplete() {
            mOsuProvisioningComplete = true;
            mOsuFailure = null;
            mOsuStatus = null;

            ThreadUtils.postOnMainThread(() -> {
                if (mAccessPointListener != null) {
                    mAccessPointListener.onAccessPointChanged(AccessPoint.this);
                }
            });

            // Connect to the freshly provisioned network.
            WifiManager wifiManager = getWifiManager();

            PasspointConfiguration passpointConfig = wifiManager
                    .getMatchingPasspointConfigsForOsuProviders(Collections.singleton(mOsuProvider))
                    .get(mOsuProvider);
            if (passpointConfig == null) {
                Log.e(TAG, "Missing PasspointConfiguration for newly provisioned network!");
                if (mConnectListener != null) {
                    mConnectListener.onFailure(0);
                }
                return;
            }

            String uniqueId = passpointConfig.getUniqueId();
            for (Pair<WifiConfiguration, Map<Integer, List<ScanResult>>> pairing :
                    wifiManager.getAllMatchingWifiConfigs(wifiManager.getScanResults())) {
                WifiConfiguration config = pairing.first;
                if (TextUtils.equals(config.getKey(), uniqueId)) {
                    List<ScanResult> homeScans =
                            pairing.second.get(WifiManager.PASSPOINT_HOME_NETWORK);
                    List<ScanResult> roamingScans =
                            pairing.second.get(WifiManager.PASSPOINT_ROAMING_NETWORK);

                    AccessPoint connectionAp =
                            new AccessPoint(mContext, config, homeScans, roamingScans);
                    wifiManager.connect(connectionAp.getConfig(), mConnectListener);
                    return;
                }
            }
            if (mConnectListener != null) {
                mConnectListener.onFailure(0);
            }
        }
    }

    public boolean isPskSaeTransitionMode() {
        return mIsPskSaeTransitionMode;
    }

    public boolean isOweTransitionMode() {
        return mIsOweTransitionMode;
    }

    private static boolean isPskSaeTransitionMode(ScanResult scanResult) {
        return scanResult.capabilities.contains("PSK")
                && scanResult.capabilities.contains("SAE");
    }

    private static boolean isOweTransitionMode(ScanResult scanResult) {
        return scanResult.capabilities.contains("OWE_TRANSITION");
    }

    private boolean isSameSsidOrBssid(ScanResult scanResult) {
        if (scanResult == null) {
            return false;
        }

        if (TextUtils.equals(ssid, scanResult.SSID)) {
            return true;
        } else if (scanResult.BSSID != null && TextUtils.equals(bssid, scanResult.BSSID)) {
            return true;
        }
        return false;
    }

    private boolean isSameSsidOrBssid(WifiInfo wifiInfo) {
        if (wifiInfo == null) {
            return false;
        }

        if (TextUtils.equals(ssid, removeDoubleQuotes(wifiInfo.getSSID()))) {
            return true;
        } else if (wifiInfo.getBSSID() != null && TextUtils.equals(bssid, wifiInfo.getBSSID())) {
            return true;
        }
        return false;
    }

    private boolean isSameSsidOrBssid(AccessPoint accessPoint) {
        if (accessPoint == null) {
            return false;
        }

        if (TextUtils.equals(ssid, accessPoint.getSsid())) {
            return true;
        } else if (accessPoint.getBssid() != null
                && TextUtils.equals(bssid, accessPoint.getBssid())) {
            return true;
        }
        return false;
    }
}

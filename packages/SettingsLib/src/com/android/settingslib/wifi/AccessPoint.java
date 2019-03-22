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

import android.annotation.IntDef;
import android.annotation.MainThread;
import android.annotation.Nullable;
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
import android.net.wifi.IWifiManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkScoreCache;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.TtsSpan;
import android.util.ArraySet;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.settingslib.R;
import com.android.settingslib.utils.ThreadUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
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
 */
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

    /** The key which identifies this AccessPoint grouping. */
    private String mKey;

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

    /** The underlying set of scan results comprising this AccessPoint. */
    private final ArraySet<ScanResult> mScanResults = new ArraySet<>();

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
    static final String KEY_FQDN = "key_fqdn";
    static final String KEY_PROVIDER_FRIENDLY_NAME = "key_provider_friendly_name";
    static final String KEY_IS_CARRIER_AP = "key_is_carrier_ap";
    static final String KEY_CARRIER_AP_EAP_TYPE = "key_carrier_ap_eap_type";
    static final String KEY_CARRIER_NAME = "key_carrier_name";
    static final AtomicInteger sLastId = new AtomicInteger(0);

    /**
     * These values are matched in string arrays -- changes must be kept in sync
     */
    public static final int SECURITY_NONE = 0;
    public static final int SECURITY_WEP = 1;
    public static final int SECURITY_PSK = 2;
    public static final int SECURITY_EAP = 3;

    private static final int PSK_UNKNOWN = 0;
    private static final int PSK_WPA = 1;
    private static final int PSK_WPA2 = 2;
    private static final int PSK_WPA_WPA2 = 3;

    /**
     * The number of distinct wifi levels.
     *
     * <p>Must keep in sync with {@link R.array.wifi_signal} and {@link WifiManager#RSSI_LEVELS}.
     */
    public static final int SIGNAL_LEVELS = 5;

    public static final int UNREACHABLE_RSSI = Integer.MIN_VALUE;

    private final Context mContext;

    private String ssid;
    private String bssid;
    private int security;
    private int networkId = WifiConfiguration.INVALID_NETWORK_ID;

    private int pskType = PSK_UNKNOWN;

    private WifiConfiguration mConfig;

    private int mRssi = UNREACHABLE_RSSI;

    private WifiInfo mInfo;
    private NetworkInfo mNetworkInfo;
    AccessPointListener mAccessPointListener;

    private Object mTag;

    @Speed private int mSpeed = Speed.NONE;
    private boolean mIsScoredNetworkMetered = false;

    // used to co-relate internal vs returned accesspoint.
    int mId;

    /**
     * Information associated with the {@link PasspointConfiguration}.  Only maintaining
     * the relevant info to preserve spaces.
     */
    private String mFqdn;
    private String mProviderFriendlyName;

    private boolean mIsCarrierAp = false;
    /**
     * The EAP type {@link WifiEnterpriseConfig.Eap} associated with this AP if it is a carrier AP.
     */
    private int mCarrierApEapType = WifiEnterpriseConfig.Eap.NONE;
    private String mCarrierName = null;

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
        if (savedState.containsKey(KEY_FQDN)) {
            mFqdn = savedState.getString(KEY_FQDN);
        }
        if (savedState.containsKey(KEY_PROVIDER_FRIENDLY_NAME)) {
            mProviderFriendlyName = savedState.getString(KEY_PROVIDER_FRIENDLY_NAME);
        }
        if (savedState.containsKey(KEY_IS_CARRIER_AP)) {
            mIsCarrierAp = savedState.getBoolean(KEY_IS_CARRIER_AP);
        }
        if (savedState.containsKey(KEY_CARRIER_AP_EAP_TYPE)) {
            mCarrierApEapType = savedState.getInt(KEY_CARRIER_AP_EAP_TYPE);
        }
        if (savedState.containsKey(KEY_CARRIER_NAME)) {
            mCarrierName = savedState.getString(KEY_CARRIER_NAME);
        }
        update(mConfig, mInfo, mNetworkInfo);

        // Calculate required fields
        updateKey();
        updateRssi();

        mId = sLastId.incrementAndGet();
    }

    public AccessPoint(Context context, WifiConfiguration config) {
        mContext = context;
        loadConfig(config);
        mId = sLastId.incrementAndGet();
    }

    /**
     * Initialize an AccessPoint object for a {@link PasspointConfiguration}.  This is mainly
     * used by "Saved Networks" page for managing the saved {@link PasspointConfiguration}.
     */
    public AccessPoint(Context context, PasspointConfiguration config) {
        mContext = context;
        mFqdn = config.getHomeSp().getFqdn();
        mProviderFriendlyName = config.getHomeSp().getFriendlyName();
        mId = sLastId.incrementAndGet();
    }

    AccessPoint(Context context, Collection<ScanResult> results) {
        mContext = context;

        if (results.isEmpty()) {
            throw new IllegalArgumentException("Cannot construct with an empty ScanResult list");
        }
        mScanResults.addAll(results);

        // Information derived from scan results
        ScanResult firstResult = results.iterator().next();
        ssid = firstResult.SSID;
        bssid = firstResult.BSSID;
        security = getSecurity(firstResult);
        if (security == SECURITY_PSK) {
            pskType = getPskType(firstResult);
        }
        updateKey();
        updateRssi();

        // Passpoint Info
        mIsCarrierAp = firstResult.isCarrierAp;
        mCarrierApEapType = firstResult.carrierApEapType;
        mCarrierName = firstResult.carrierName;

        mId = sLastId.incrementAndGet();
    }

    @VisibleForTesting void loadConfig(WifiConfiguration config) {
        ssid = (config.SSID == null ? "" : removeDoubleQuotes(config.SSID));
        bssid = config.BSSID;
        security = getSecurity(config);
        updateKey();
        networkId = config.networkId;
        mConfig = config;
    }

    /** Updates {@link #mKey} and should only called upon object creation/initialization. */
    private void updateKey() {
        // TODO(sghuman): Consolidate Key logic on ScanResultMatchInfo

        StringBuilder builder = new StringBuilder();

        if (TextUtils.isEmpty(getSsidStr())) {
            builder.append(getBssid());
        } else {
            builder.append(getSsidStr());
        }

        builder.append(',').append(getSecurity());
        mKey = builder.toString();
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

        // Sort by signal strength, bucketed by level
        int difference = WifiManager.calculateSignalLevel(other.mRssi, SIGNAL_LEVELS)
                - WifiManager.calculateSignalLevel(mRssi, SIGNAL_LEVELS);
        if (difference != 0) {
            return difference;
        }

        // Sort by ssid.
        difference = getSsidStr().compareToIgnoreCase(other.getSsidStr());
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
        if (security != SECURITY_NONE) {
            builder.append(',').append(securityToString(security, pskType));
        }
        builder.append(",level=").append(getLevel());
        if (mSpeed != Speed.NONE) {
            builder.append(",speed=").append(mSpeed);
        }
        builder.append(",metered=").append(isMetered());

        if (isVerboseLoggingEnabled()) {
            builder.append(",rssi=").append(mRssi);
            builder.append(",scan cache size=").append(mScanResults.size());
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
            for (ScanResult result : mScanResults) {
                ScoredNetwork score = scoreCache.getScoredNetwork(result);
                if (score == null) {
                    continue;
                }
                mIsScoredNetworkMetered |= score.meteredHint;
            }
        }
        return oldMetering == mIsScoredNetworkMetered;
    }

    public static String getKey(ScanResult result) {
        StringBuilder builder = new StringBuilder();

        if (TextUtils.isEmpty(result.SSID)) {
            builder.append(result.BSSID);
        } else {
            builder.append(result.SSID);
        }

        builder.append(',').append(getSecurity(result));
        return builder.toString();
    }

    public static String getKey(WifiConfiguration config) {
        StringBuilder builder = new StringBuilder();

        if (TextUtils.isEmpty(config.SSID)) {
            builder.append(config.BSSID);
        } else {
            builder.append(removeDoubleQuotes(config.SSID));
        }

        builder.append(',').append(getSecurity(config));
        return builder.toString();
    }

    public String getKey() {
        return mKey;
    }

    public boolean matches(WifiConfiguration config) {
        if (config.isPasspoint() && mConfig != null && mConfig.isPasspoint()) {
            return ssid.equals(removeDoubleQuotes(config.SSID)) && config.FQDN.equals(mConfig.FQDN);
        } else {
            return ssid.equals(removeDoubleQuotes(config.SSID))
                    && security == getSecurity(config)
                    && (mConfig == null || mConfig.shared == config.shared);
        }
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
     * Returns the number of levels to show for a Wifi icon, from 0 to {@link #SIGNAL_LEVELS}-1.
     *
     * <p>Use {@#isReachable()} to determine if an AccessPoint is in range, as this method will
     * always return at least 0.
     */
    public int getLevel() {
        return WifiManager.calculateSignalLevel(mRssi, SIGNAL_LEVELS);
    }

    public int getRssi() {
        return mRssi;
    }

    /**
     * Returns the underlying scan result set.
     *
     * <p>Callers should not modify this set.
     */
    public Set<ScanResult> getScanResults() { return mScanResults; }

    public Map<String, TimestampedScoredNetwork> getScoredNetworkCache() {
        return mScoredNetworkCache;
    }

    /**
     * Updates {@link #mRssi}.
     *
     * <p>If the given connection is active, the existing value of {@link #mRssi} will be returned.
     * If the given AccessPoint is not active, a value will be calculated from previous scan
     * results, returning the best RSSI for all matching AccessPoints averaged with the previous
     * value. If the access point is not connected and there are no scan results, the rssi will be
     * set to {@link #UNREACHABLE_RSSI}.
     */
    private void updateRssi() {
        if (this.isActive()) {
            return;
        }

        int rssi = UNREACHABLE_RSSI;
        for (ScanResult result : mScanResults) {
            if (result.level > rssi) {
                rssi = result.level;
            }
        }

        if (rssi != UNREACHABLE_RSSI && mRssi != UNREACHABLE_RSSI) {
            mRssi = (mRssi + rssi) / 2; // half-life previous value
        } else {
            mRssi = rssi;
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
        switch(security) {
            case SECURITY_EAP:
                return concise ? context.getString(R.string.wifi_security_short_eap) :
                    context.getString(R.string.wifi_security_eap);
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
        final SpannableString str = new SpannableString(ssid);
        str.setSpan(new TtsSpan.TelephoneBuilder(ssid).build(), 0, ssid.length(),
                Spannable.SPAN_INCLUSIVE_INCLUSIVE);
        return str;
    }

    public String getConfigName() {
        if (mConfig != null && mConfig.isPasspoint()) {
            return mConfig.providerFriendlyName;
        } else if (mFqdn != null) {
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

    public boolean isCarrierAp() {
        return mIsCarrierAp;
    }

    public int getCarrierApEapType() {
        return mCarrierApEapType;
    }

    public String getCarrierName() {
        return mCarrierName;
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
        return "";
    }

    public String getSummary() {
        return getSettingsSummary(mConfig);
    }

    public String getSettingsSummary() {
        return getSettingsSummary(mConfig);
    }

    private String getSettingsSummary(WifiConfiguration config) {
        // Update to new summary
        StringBuilder summary = new StringBuilder();

        if (isActive() && config != null && config.isPasspoint()) {
            // This is the active connection on passpoint
            summary.append(getSummary(mContext, getDetailedState(),
                    false, config.providerFriendlyName));
        } else if (isActive() && config != null && getDetailedState() == DetailedState.CONNECTED
                && mIsCarrierAp) {
            summary.append(String.format(mContext.getString(R.string.connected_via_carrier), mCarrierName));
        } else if (isActive()) {
            // This is the active connection on non-passpoint network
            summary.append(getSummary(mContext, getDetailedState(),
                    mInfo != null && mInfo.isEphemeral()));
        } else if (config != null && config.isPasspoint()
                && config.getNetworkSelectionStatus().isNetworkEnabled()) {
            String format = mContext.getString(R.string.available_via_passpoint);
            summary.append(String.format(format, config.providerFriendlyName));
        } else if (config != null && config.hasNoInternetAccess()) {
            int messageID = config.getNetworkSelectionStatus().isNetworkPermanentlyDisabled()
                    ? R.string.wifi_no_internet_no_reconnect
                    : R.string.wifi_no_internet;
            summary.append(mContext.getString(messageID));
        } else if (config != null && !config.getNetworkSelectionStatus().isNetworkEnabled()) {
            WifiConfiguration.NetworkSelectionStatus networkStatus =
                    config.getNetworkSelectionStatus();
            switch (networkStatus.getNetworkSelectionDisableReason()) {
                case WifiConfiguration.NetworkSelectionStatus.DISABLED_AUTHENTICATION_FAILURE:
                    summary.append(mContext.getString(R.string.wifi_disabled_password_failure));
                    break;
                case WifiConfiguration.NetworkSelectionStatus.DISABLED_BY_WRONG_PASSWORD:
                    summary.append(mContext.getString(R.string.wifi_check_password_try_again));
                    break;
                case WifiConfiguration.NetworkSelectionStatus.DISABLED_DHCP_FAILURE:
                case WifiConfiguration.NetworkSelectionStatus.DISABLED_DNS_FAILURE:
                    summary.append(mContext.getString(R.string.wifi_disabled_network_failure));
                    break;
                case WifiConfiguration.NetworkSelectionStatus.DISABLED_ASSOCIATION_REJECTION:
                    summary.append(mContext.getString(R.string.wifi_disabled_generic));
                    break;
            }
        } else if (config != null && config.getNetworkSelectionStatus().isNotRecommended()) {
            summary.append(mContext.getString(R.string.wifi_disabled_by_recommendation_provider));
        } else if (mIsCarrierAp) {
            summary.append(String.format(mContext.getString(R.string.available_via_carrier), mCarrierName));
        } else if (!isReachable()) { // Wifi out of range
            summary.append(mContext.getString(R.string.wifi_not_in_range));
        } else { // In range, not disabled.
            if (config != null) { // Is saved network
                // Last attempt to connect to this failed. Show reason why
                switch (config.recentFailure.getAssociationStatus()) {
                    case WifiConfiguration.RecentFailure.STATUS_AP_UNABLE_TO_HANDLE_NEW_STA:
                        summary.append(mContext.getString(
                                R.string.wifi_ap_unable_to_handle_new_sta));
                        break;
                    default:
                        // "Saved"
                        summary.append(mContext.getString(R.string.wifi_remembered));
                        break;
                }
            }
        }

        if (isVerboseLoggingEnabled()) {
            summary.append(WifiUtils.buildLoggingSummary(this, config));
        }

        if (config != null && (WifiUtils.isMeteredOverridden(config) || config.meteredHint)) {
            return mContext.getResources().getString(
                    R.string.preference_summary_default_combination,
                    WifiUtils.getMeteredLabel(mContext, config),
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
        return mFqdn != null;
    }

    /**
     * Return whether the given {@link WifiInfo} is for this access point.
     * If the current AP does not have a network Id then the config is used to
     * match based on SSID and security.
     */
    private boolean isInfoForThisAccessPoint(WifiConfiguration config, WifiInfo info) {
        if (isPasspoint() == false && networkId != WifiConfiguration.INVALID_NETWORK_ID) {
            return networkId == info.getNetworkId();
        } else if (config != null) {
            return matches(config);
        }
        else {
            // Might be an ephemeral connection with no WifiConfiguration. Try matching on SSID.
            // (Note that we only do this if the WifiConfiguration explicitly equals INVALID).
            // TODO: Handle hex string SSIDs.
            return ssid.equals(removeDoubleQuotes(info.getSSID()));
        }
    }

    public boolean isSaved() {
        return networkId != WifiConfiguration.INVALID_NETWORK_ID;
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
        if (security != SECURITY_NONE)
            throw new IllegalStateException();
        if (mConfig != null)
            return;
        mConfig = new WifiConfiguration();
        mConfig.SSID = AccessPoint.convertToQuotedString(ssid);
        mConfig.allowedKeyManagement.set(KeyMgmt.NONE);
    }

    public void saveWifiState(Bundle savedState) {
        if (ssid != null) savedState.putString(KEY_SSID, getSsidStr());
        savedState.putInt(KEY_SECURITY, security);
        savedState.putInt(KEY_SPEED, mSpeed);
        savedState.putInt(KEY_PSKTYPE, pskType);
        if (mConfig != null) savedState.putParcelable(KEY_CONFIG, mConfig);
        savedState.putParcelable(KEY_WIFIINFO, mInfo);
        savedState.putParcelableArray(KEY_SCANRESULTS,
                mScanResults.toArray(new Parcelable[mScanResults.size()]));
        savedState.putParcelableArrayList(KEY_SCOREDNETWORKCACHE,
                new ArrayList<>(mScoredNetworkCache.values()));
        if (mNetworkInfo != null) {
            savedState.putParcelable(KEY_NETWORKINFO, mNetworkInfo);
        }
        if (mFqdn != null) {
            savedState.putString(KEY_FQDN, mFqdn);
        }
        if (mProviderFriendlyName != null) {
            savedState.putString(KEY_PROVIDER_FRIENDLY_NAME, mProviderFriendlyName);
        }
        savedState.putBoolean(KEY_IS_CARRIER_AP, mIsCarrierAp);
        savedState.putInt(KEY_CARRIER_AP_EAP_TYPE, mCarrierApEapType);
        savedState.putString(KEY_CARRIER_NAME, mCarrierName);
    }

    public void setListener(AccessPointListener listener) {
        mAccessPointListener = listener;
    }

    /**
     * Sets {@link #mScanResults} to the given collection.
     *
     * @param scanResults a collection of scan results to add to the internal set
     * @throws IllegalArgumentException if any of the given ScanResults did not belong to this AP
     */
    void setScanResults(Collection<ScanResult> scanResults) {

        // Validate scan results are for current AP only
        String key = getKey();
        for (ScanResult result : scanResults) {
            String scanResultKey = AccessPoint.getKey(result);
            if (!mKey.equals(scanResultKey)) {
                throw new IllegalArgumentException(
                        String.format("ScanResult %s\nkey of %s did not match current AP key %s",
                                      result, scanResultKey, key));
            }
        }


        int oldLevel = getLevel();
        mScanResults.clear();
        mScanResults.addAll(scanResults);
        updateRssi();
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

        if (!scanResults.isEmpty()) {
            ScanResult result = scanResults.iterator().next();

            // This flag only comes from scans, is not easily saved in config
            if (security == SECURITY_PSK) {
                pskType = getPskType(result);
            }

            // The carrier info in the ScanResult is set by the platform based on the SSID and will
            // always be the same for all matching scan results.
            mIsCarrierAp = result.isCarrierAp;
            mCarrierApEapType = result.carrierApEapType;
            mCarrierName = result.carrierName;
        }
    }

    /** Attempt to update the AccessPoint and return true if an update occurred. */
    public boolean update(
            @Nullable WifiConfiguration config, WifiInfo info, NetworkInfo networkInfo) {

        boolean updated = false;
        final int oldLevel = getLevel();
        if (info != null && isInfoForThisAccessPoint(config, info)) {
            updated = (mInfo == null);
            if (mConfig != config) {
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

    public static String getSummary(Context context, String ssid, DetailedState state,
            boolean isEphemeral, String passpointProvider) {
        if (state == DetailedState.CONNECTED && ssid == null) {
            if (TextUtils.isEmpty(passpointProvider) == false) {
                // Special case for connected + passpoint networks.
                String format = context.getString(R.string.connected_via_passpoint);
                return String.format(format, passpointProvider);
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
            IWifiManager wifiManager = IWifiManager.Stub.asInterface(
                    ServiceManager.getService(Context.WIFI_SERVICE));
            NetworkCapabilities nc = null;

            try {
                nc = cm.getNetworkCapabilities(wifiManager.getCurrentNetwork());
            } catch (RemoteException e) {}

            if (nc != null) {
                if (nc.hasCapability(nc.NET_CAPABILITY_CAPTIVE_PORTAL)) {
                    int id = context.getResources()
                            .getIdentifier("network_available_sign_in", "string", "android");
                    return context.getString(id);
                } else if (nc.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_PARTIAL_CONNECTIVITY)) {
                    return context.getString(R.string.wifi_limited_connection);
                } else if (!nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    return context.getString(R.string.wifi_connected_no_internet);
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

    public static String getSummary(Context context, DetailedState state, boolean isEphemeral) {
        return getSummary(context, null, state, isEphemeral, null);
    }

    public static String getSummary(Context context, DetailedState state, boolean isEphemeral,
            String passpointProvider) {
        return getSummary(context, null, state, isEphemeral, passpointProvider);
    }

    public static String convertToQuotedString(String string) {
        return "\"" + string + "\"";
    }

    private static int getPskType(ScanResult result) {
        boolean wpa = result.capabilities.contains("WPA-PSK");
        boolean wpa2 = result.capabilities.contains("WPA2-PSK");
        if (wpa2 && wpa) {
            return PSK_WPA_WPA2;
        } else if (wpa2) {
            return PSK_WPA2;
        } else if (wpa) {
            return PSK_WPA;
        } else {
            Log.w(TAG, "Received abnormal flag string: " + result.capabilities);
            return PSK_UNKNOWN;
        }
    }

    private static int getSecurity(ScanResult result) {
        if (result.capabilities.contains("WEP")) {
            return SECURITY_WEP;
        } else if (result.capabilities.contains("PSK")) {
            return SECURITY_PSK;
        } else if (result.capabilities.contains("EAP")) {
            return SECURITY_EAP;
        }
        return SECURITY_NONE;
    }

    static int getSecurity(WifiConfiguration config) {
        if (config.allowedKeyManagement.get(KeyMgmt.WPA_PSK)) {
            return SECURITY_PSK;
        }
        if (config.allowedKeyManagement.get(KeyMgmt.WPA_EAP) ||
                config.allowedKeyManagement.get(KeyMgmt.IEEE8021X)) {
            return SECURITY_EAP;
        }
        return (config.wepKeys[0] != null) ? SECURITY_WEP : SECURITY_NONE;
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
}

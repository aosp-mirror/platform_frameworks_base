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
import android.net.WifiKey;
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
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.support.annotation.NonNull;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.TtsSpan;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.settingslib.R;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;


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

    /**
     * Experimental: we should be able to show the user the list of BSSIDs and bands
     *  for that SSID.
     *  For now this data is used only with Verbose Logging so as to show the band and number
     *  of BSSIDs on which that network is seen.
     */
    private final ConcurrentHashMap<String, ScanResult> mScanResultCache =
            new ConcurrentHashMap<String, ScanResult>(32);

    /**
     * Map of BSSIDs to scored networks for individual bssids.
     *
     * <p>This cache should not be evicted with scan results, as the values here are used to
     * generate a fallback in the absence of scores for the visible APs.
     */
    private final Map<String, TimestampedScoredNetwork> mScoredNetworkCache = new HashMap<>();

    /** Maximum age of scan results to hold onto while actively scanning. **/
    private static final long MAX_SCAN_RESULT_AGE_MILLIS = 25000;

    static final String KEY_NETWORKINFO = "key_networkinfo";
    static final String KEY_WIFIINFO = "key_wifiinfo";
    static final String KEY_SCANRESULT = "key_scanresult";
    static final String KEY_SSID = "key_ssid";
    static final String KEY_SECURITY = "key_security";
    static final String KEY_SPEED = "key_speed";
    static final String KEY_PSKTYPE = "key_psktype";
    static final String KEY_SCANRESULTCACHE = "key_scanresultcache";
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
    private long mSeen = 0;

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
        mConfig = savedState.getParcelable(KEY_CONFIG);
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
        if (savedState.containsKey(KEY_SCANRESULTCACHE)) {
            ArrayList<ScanResult> scanResultArrayList =
                    savedState.getParcelableArrayList(KEY_SCANRESULTCACHE);
            mScanResultCache.clear();
            for (ScanResult result : scanResultArrayList) {
                mScanResultCache.put(result.BSSID, result);
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

        // Do not evict old scan results on initial creation
        updateRssi();
        updateSeen();
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

    AccessPoint(Context context, AccessPoint other) {
        mContext = context;
        copyFrom(other);
    }

    AccessPoint(Context context, ScanResult result) {
        mContext = context;
        initWithScanResult(result);
        mId = sLastId.incrementAndGet();
    }

    /**
     * Copy accesspoint information. NOTE: We do not copy tag information because that is never
     * set on the internal copy.
     * @param that
     */
    void copyFrom(AccessPoint that) {
        that.evictOldScanResults();
        this.ssid = that.ssid;
        this.bssid = that.bssid;
        this.security = that.security;
        this.networkId = that.networkId;
        this.pskType = that.pskType;
        this.mConfig = that.mConfig; //TODO: Watch out, this object is mutated.
        this.mRssi = that.mRssi;
        this.mSeen = that.mSeen;
        this.mInfo = that.mInfo;
        this.mNetworkInfo = that.mNetworkInfo;
        this.mScanResultCache.clear();
        this.mScanResultCache.putAll(that.mScanResultCache);
        this.mScoredNetworkCache.clear();
        this.mScoredNetworkCache.putAll(that.mScoredNetworkCache);
        this.mId = that.mId;
        this.mSpeed = that.mSpeed;
        this.mIsScoredNetworkMetered = that.mIsScoredNetworkMetered;
        this.mIsCarrierAp = that.mIsCarrierAp;
        this.mCarrierApEapType = that.mCarrierApEapType;
        this.mCarrierName = that.mCarrierName;
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

        if (WifiTracker.sVerboseLogging) {
            builder.append(",rssi=").append(mRssi);
            builder.append(",scan cache size=").append(mScanResultCache.size());
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
        for (ScanResult result : mScanResultCache.values()) {
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
        if(WifiTracker.sVerboseLogging && changed) {
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
        if (WifiTracker.sVerboseLogging) {
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
            NetworkKey key = new NetworkKey(new WifiKey(
                    AccessPoint.convertToQuotedString(ssid), mInfo.getBSSID()));
            ScoredNetwork score = scoreCache.getScoredNetwork(key);
            if (score != null) {
                mIsScoredNetworkMetered |= score.meteredHint;
            }
        } else {
            for (ScanResult result : mScanResultCache.values()) {
                ScoredNetwork score = scoreCache.getScoredNetwork(result);
                if (score == null) {
                    continue;
                }
                mIsScoredNetworkMetered |= score.meteredHint;
            }
        }
        return oldMetering == mIsScoredNetworkMetered;
    }

    private void evictOldScanResults() {
        long nowMs = SystemClock.elapsedRealtime();
        for (Iterator<ScanResult> iter = mScanResultCache.values().iterator(); iter.hasNext(); ) {
            ScanResult result = iter.next();
            // result timestamp is in microseconds
            if (nowMs - result.timestamp / 1000 > MAX_SCAN_RESULT_AGE_MILLIS) {
                iter.remove();
            }
        }
    }

    public boolean matches(ScanResult result) {
        return ssid.equals(result.SSID) && security == getSecurity(result);
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
        for (ScanResult result : mScanResultCache.values()) {
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

    /** Updates {@link #mSeen} based on the scan result cache. */
    private void updateSeen() {
        long seen = 0;
        for (ScanResult result : mScanResultCache.values()) {
            if (result.timestamp > seen) {
                seen = result.timestamp;
            }
        }

        // Only replace the previous value if we have a recent scan result to use
        if (seen != 0) {
            mSeen = seen;
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

        if (WifiTracker.sVerboseLogging) {
            // Add RSSI/band information for this config, what was seen up to 6 seconds ago
            // verbose WiFi Logging is only turned on thru developers settings
            if (isActive() && mInfo != null) {
                summary.append(" f=" + Integer.toString(mInfo.getFrequency()));
            }
            summary.append(" " + getVisibilityStatus());
            if (config != null && !config.getNetworkSelectionStatus().isNetworkEnabled()) {
                summary.append(" (" + config.getNetworkSelectionStatus().getNetworkStatusString());
                if (config.getNetworkSelectionStatus().getDisableTime() > 0) {
                    long now = System.currentTimeMillis();
                    long diff = (now - config.getNetworkSelectionStatus().getDisableTime()) / 1000;
                    long sec = diff%60; //seconds
                    long min = (diff/60)%60; //minutes
                    long hour = (min/60)%60; //hours
                    summary.append(", ");
                    if (hour > 0) summary.append(Long.toString(hour) + "h ");
                    summary.append( Long.toString(min) + "m ");
                    summary.append( Long.toString(sec) + "s ");
                }
                summary.append(")");
            }

            if (config != null) {
                WifiConfiguration.NetworkSelectionStatus networkStatus =
                        config.getNetworkSelectionStatus();
                for (int index = WifiConfiguration.NetworkSelectionStatus.NETWORK_SELECTION_ENABLE;
                        index < WifiConfiguration.NetworkSelectionStatus
                        .NETWORK_SELECTION_DISABLED_MAX; index++) {
                    if (networkStatus.getDisableReasonCounter(index) != 0) {
                        summary.append(" " + WifiConfiguration.NetworkSelectionStatus
                                .getNetworkDisableReasonString(index) + "="
                                + networkStatus.getDisableReasonCounter(index));
                    }
                }
            }
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
     * Returns the visibility status of the WifiConfiguration.
     *
     * @return autojoin debugging information
     * TODO: use a string formatter
     * ["rssi 5Ghz", "num results on 5GHz" / "rssi 5Ghz", "num results on 5GHz"]
     * For instance [-40,5/-30,2]
     */
    private String getVisibilityStatus() {
        StringBuilder visibility = new StringBuilder();
        StringBuilder scans24GHz = new StringBuilder();
        StringBuilder scans5GHz = new StringBuilder();
        String bssid = null;

        long now = System.currentTimeMillis();

        if (isActive() && mInfo != null) {
            bssid = mInfo.getBSSID();
            if (bssid != null) {
                visibility.append(" ").append(bssid);
            }
            visibility.append(" rssi=").append(mInfo.getRssi());
            visibility.append(" ");
            visibility.append(" score=").append(mInfo.score);
            if (mSpeed != Speed.NONE) {
                visibility.append(" speed=").append(getSpeedLabel());
            }
            visibility.append(String.format(" tx=%.1f,", mInfo.txSuccessRate));
            visibility.append(String.format("%.1f,", mInfo.txRetriesRate));
            visibility.append(String.format("%.1f ", mInfo.txBadRate));
            visibility.append(String.format("rx=%.1f", mInfo.rxSuccessRate));
        }

        int maxRssi5 = WifiConfiguration.INVALID_RSSI;
        int maxRssi24 = WifiConfiguration.INVALID_RSSI;
        final int maxDisplayedScans = 4;
        int num5 = 0; // number of scanned BSSID on 5GHz band
        int num24 = 0; // number of scanned BSSID on 2.4Ghz band
        int numBlackListed = 0;
        evictOldScanResults();

        // TODO: sort list by RSSI or age
        long nowMs = SystemClock.elapsedRealtime();
        for (ScanResult result : mScanResultCache.values()) {
            if (result.frequency >= LOWER_FREQ_5GHZ
                    && result.frequency <= HIGHER_FREQ_5GHZ) {
                // Strictly speaking: [4915, 5825]
                num5++;

                if (result.level > maxRssi5) {
                    maxRssi5 = result.level;
                }
                if (num5 <= maxDisplayedScans) {
                    scans5GHz.append(verboseScanResultSummary(result, bssid, nowMs));
                }
            } else if (result.frequency >= LOWER_FREQ_24GHZ
                    && result.frequency <= HIGHER_FREQ_24GHZ) {
                // Strictly speaking: [2412, 2482]
                num24++;

                if (result.level > maxRssi24) {
                    maxRssi24 = result.level;
                }
                if (num24 <= maxDisplayedScans) {
                    scans24GHz.append(verboseScanResultSummary(result, bssid, nowMs));
                }
            }
        }
        visibility.append(" [");
        if (num24 > 0) {
            visibility.append("(").append(num24).append(")");
            if (num24 > maxDisplayedScans) {
                visibility.append("max=").append(maxRssi24).append(",");
            }
            visibility.append(scans24GHz.toString());
        }
        visibility.append(";");
        if (num5 > 0) {
            visibility.append("(").append(num5).append(")");
            if (num5 > maxDisplayedScans) {
                visibility.append("max=").append(maxRssi5).append(",");
            }
            visibility.append(scans5GHz.toString());
        }
        if (numBlackListed > 0)
            visibility.append("!").append(numBlackListed);
        visibility.append("]");

        return visibility.toString();
    }

    @VisibleForTesting
    /* package */ String verboseScanResultSummary(ScanResult result, String bssid, long nowMs) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(" \n{").append(result.BSSID);
        if (result.BSSID.equals(bssid)) {
            stringBuilder.append("*");
        }
        stringBuilder.append("=").append(result.frequency);
        stringBuilder.append(",").append(result.level);
        int speed = getSpecificApSpeed(result);
        if (speed != Speed.NONE) {
            stringBuilder.append(",")
                    .append(getSpeedLabel(speed));
        }
        int ageSeconds = (int) (nowMs - result.timestamp / 1000) / 1000;
        stringBuilder.append(",").append(ageSeconds).append("s");
        stringBuilder.append("}");
        return stringBuilder.toString();
    }

    @Speed private int getSpecificApSpeed(ScanResult result) {
        TimestampedScoredNetwork timedScore = mScoredNetworkCache.get(result.BSSID);
        if (timedScore == null) {
            return Speed.NONE;
        }
        // For debugging purposes we may want to use mRssi rather than result.level as the average
        // speed wil be determined by mRssi
        return timedScore.getScore().calculateBadge(result.level);
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

    void loadConfig(WifiConfiguration config) {
        ssid = (config.SSID == null ? "" : removeDoubleQuotes(config.SSID));
        bssid = config.BSSID;
        security = getSecurity(config);
        networkId = config.networkId;
        mConfig = config;
    }

    private void initWithScanResult(ScanResult result) {
        ssid = result.SSID;
        bssid = result.BSSID;
        security = getSecurity(result);
        if (security == SECURITY_PSK)
            pskType = getPskType(result);

        mScanResultCache.put(result.BSSID, result);
        updateRssi();
        mSeen = result.timestamp; // even if the timestamp is old it is still valid
        mIsCarrierAp = result.isCarrierAp;
        mCarrierApEapType = result.carrierApEapType;
        mCarrierName = result.carrierName;
    }

    public void saveWifiState(Bundle savedState) {
        if (ssid != null) savedState.putString(KEY_SSID, getSsidStr());
        savedState.putInt(KEY_SECURITY, security);
        savedState.putInt(KEY_SPEED, mSpeed);
        savedState.putInt(KEY_PSKTYPE, pskType);
        if (mConfig != null) savedState.putParcelable(KEY_CONFIG, mConfig);
        savedState.putParcelable(KEY_WIFIINFO, mInfo);
        evictOldScanResults();
        savedState.putParcelableArrayList(KEY_SCANRESULTCACHE,
                new ArrayList<ScanResult>(mScanResultCache.values()));
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
     * Update the AP with the given scan result.
     *
     * @param result the ScanResult to add to the AccessPoint scan cache
     * @param evictOldScanResults whether stale scan results should be removed
     *         from the cache during this update process
     * @return true if the scan result update caused a change in state which would impact ranking
     *     or AccessPoint rendering (e.g. wifi level, security)
     */
    boolean update(ScanResult result, boolean evictOldScanResults) {
        if (matches(result)) {
            int oldLevel = getLevel();

            /* Add or update the scan result for the BSSID */
            mScanResultCache.put(result.BSSID, result);
            if (evictOldScanResults) evictOldScanResults();
            updateSeen();
            updateRssi();
            int newLevel = getLevel();

            if (newLevel > 0 && newLevel != oldLevel) {
                // Only update labels on visible rssi changes
                updateSpeed();
                if (mAccessPointListener != null) {
                    mAccessPointListener.onLevelChanged(this);
                }
            }
            // This flag only comes from scans, is not easily saved in config
            if (security == SECURITY_PSK) {
                pskType = getPskType(result);
            }

            if (mAccessPointListener != null) {
                mAccessPointListener.onAccessPointChanged(this);
            }

            // The carrier info in the ScanResult is set by the platform based on the SSID and will
            // always be the same for all matching scan results.
            mIsCarrierAp = result.isCarrierAp;
            mCarrierApEapType = result.carrierApEapType;
            mCarrierName = result.carrierName;

            return true;
        }
        return false;
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
            mAccessPointListener.onAccessPointChanged(this);

            if (oldLevel != getLevel() /* current level */) {
                mAccessPointListener.onLevelChanged(this);
            }
        }

        return updated;
    }

    void update(@Nullable WifiConfiguration config) {
        mConfig = config;
        networkId = config != null ? config.networkId : WifiConfiguration.INVALID_NETWORK_ID;
        if (mAccessPointListener != null) {
            mAccessPointListener.onAccessPointChanged(this);
        }
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
    private int roundToClosestSpeedEnum(int speed) {
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
    private String getSpeedLabel(@Speed int speed) {
        switch (speed) {
            case Speed.VERY_FAST:
                return mContext.getString(R.string.speed_label_very_fast);
            case Speed.FAST:
                return mContext.getString(R.string.speed_label_fast);
            case Speed.MODERATE:
                return mContext.getString(R.string.speed_label_okay);
            case Speed.SLOW:
                return mContext.getString(R.string.speed_label_slow);
            case Speed.NONE:
            default:
                return null;
        }
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

    public interface AccessPointListener {
        void onAccessPointChanged(AccessPoint accessPoint);
        void onLevelChanged(AccessPoint accessPoint);
    }
}

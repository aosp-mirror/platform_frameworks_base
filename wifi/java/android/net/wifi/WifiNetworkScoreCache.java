/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.net.wifi;

import android.Manifest.permission;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.INetworkScoreCache;
import android.net.NetworkKey;
import android.net.ScoredNetwork;
import android.os.Handler;
import android.os.Process;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link INetworkScoreCache} implementation for Wifi Networks.
 *
 * @hide
 */
public class WifiNetworkScoreCache extends INetworkScoreCache.Stub {
    private static final String TAG = "WifiNetworkScoreCache";
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);

    // A Network scorer returns a score in the range [-128, +127]
    // We treat the lowest possible score as though there were no score, effectively allowing the
    // scorer to provide an RSSI threshold below which a network should not be used.
    public static final int INVALID_NETWORK_SCORE = Byte.MIN_VALUE;

    // See {@link #CacheListener}.
    @Nullable
    @GuardedBy("mCacheLock")
    private CacheListener mListener;

    private final Context mContext;
    private final Object mCacheLock = new Object();

    // The key is of the form "<ssid>"<bssid>
    // TODO: What about SSIDs that can't be encoded as UTF-8?
    private final Map<String, ScoredNetwork> mNetworkCache;


    public WifiNetworkScoreCache(Context context) {
        this(context, null /* listener */);
    }

    /**
     * Instantiates a WifiNetworkScoreCache.
     *
     * @param context Application context
     * @param listener CacheListener for cache updates
     */
    public WifiNetworkScoreCache(Context context, @Nullable CacheListener listener) {
        mContext = context.getApplicationContext();
        mListener = listener;
        mNetworkCache = new HashMap<>();
    }

    @Override public final void updateScores(List<ScoredNetwork> networks) {
        if (networks == null || networks.isEmpty()) {
           return;
        }
        if (DBG) {
            Log.d(TAG, "updateScores list size=" + networks.size());
        }

        boolean changed = false;

        synchronized(mNetworkCache) {
            for (ScoredNetwork network : networks) {
                String networkKey = buildNetworkKey(network);
                if (networkKey == null) {
                    if (DBG) {
                        Log.d(TAG, "Failed to build network key for ScoredNetwork" + network);
                    }
                    continue;
                }
                mNetworkCache.put(networkKey, network);
                changed = true;
            }
        }

        synchronized (mCacheLock) {
            if (mListener != null && changed) {
                mListener.post(networks);
            }
        }
    }

    @Override public final void clearScores() {
        synchronized (mNetworkCache) {
            mNetworkCache.clear();
        }
    }

    /**
     * Returns whether there is any score info for the given ScanResult.
     *
     * This includes null-score info, so it should only be used when determining whether to request
     * scores from the network scorer.
     */
    public boolean isScoredNetwork(ScanResult result) {
        return getScoredNetwork(result) != null;
    }

    /**
     * Returns whether there is a non-null score curve for the given ScanResult.
     *
     * A null score curve has special meaning - we should never connect to an ephemeral network if
     * the score curve is null.
     */
    public boolean hasScoreCurve(ScanResult result) {
        ScoredNetwork network = getScoredNetwork(result);
        return network != null && network.rssiCurve != null;
    }

    public int getNetworkScore(ScanResult result) {

        int score = INVALID_NETWORK_SCORE;

        ScoredNetwork network = getScoredNetwork(result);
        if (network != null && network.rssiCurve != null) {
            score = network.rssiCurve.lookupScore(result.level);
            if (DBG) {
                Log.d(TAG, "getNetworkScore found scored network " + network.networkKey
                        + " score " + Integer.toString(score)
                        + " RSSI " + result.level);
            }
        }
        return score;
    }

    /**
     * Returns the ScoredNetwork metered hint for a given ScanResult.
     *
     * If there is no ScoredNetwork associated with the ScanResult then false will be returned.
     */
    public boolean getMeteredHint(ScanResult result) {
        ScoredNetwork network = getScoredNetwork(result);
        return network != null && network.meteredHint;
    }

    public int getNetworkScore(ScanResult result, boolean isActiveNetwork) {

        int score = INVALID_NETWORK_SCORE;

        ScoredNetwork network = getScoredNetwork(result);
        if (network != null && network.rssiCurve != null) {
            score = network.rssiCurve.lookupScore(result.level, isActiveNetwork);
            if (DBG) {
                Log.d(TAG, "getNetworkScore found scored network " + network.networkKey
                        + " score " + Integer.toString(score)
                        + " RSSI " + result.level
                        + " isActiveNetwork " + isActiveNetwork);
            }
        }
        return score;
    }

    @Nullable
    public ScoredNetwork getScoredNetwork(ScanResult result) {
        String key = buildNetworkKey(result);
        if (key == null) return null;

        synchronized(mNetworkCache) {
            ScoredNetwork network = mNetworkCache.get(key);
            return network;
        }
    }

    /** Returns the ScoredNetwork for the given key. */
    @Nullable
    public ScoredNetwork getScoredNetwork(NetworkKey networkKey) {
        String key = buildNetworkKey(networkKey);
        if (key == null) {
            if (DBG) {
                Log.d(TAG, "Could not build key string for Network Key: " + networkKey);
            }
            return null;
        }
        synchronized (mNetworkCache) {
            return mNetworkCache.get(key);
        }
    }

    private String buildNetworkKey(ScoredNetwork network) {
        if (network == null) {
            return null;
        }
        return buildNetworkKey(network.networkKey);
    }

    private String buildNetworkKey(NetworkKey networkKey) {
        if (networkKey == null) {
            return null;
        }
        if (networkKey.wifiKey == null) return null;
        if (networkKey.type == NetworkKey.TYPE_WIFI) {
            String key = networkKey.wifiKey.ssid;
            if (key == null) return null;
            if (networkKey.wifiKey.bssid != null) {
                key = key + networkKey.wifiKey.bssid;
            }
            return key;
        }
        return null;
    }

    private String buildNetworkKey(ScanResult result) {
        if (result == null || result.SSID == null) {
            return null;
        }
        StringBuilder key = new StringBuilder("\"");
        key.append(result.SSID);
        key.append("\"");
        if (result.BSSID != null) {
            key.append(result.BSSID);
        }
        return key.toString();
    }

    @Override protected final void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        mContext.enforceCallingOrSelfPermission(permission.DUMP, TAG);
        String header = String.format("WifiNetworkScoreCache (%s/%d)",
                mContext.getPackageName(), Process.myUid());
        writer.println(header);
        writer.println("  All score curves:");
        for (ScoredNetwork score : mNetworkCache.values()) {
            writer.println("    " + score);
        }
        writer.println("  Current network scores:");
        WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        for (ScanResult scanResult : wifiManager.getScanResults()) {
            writer.println("    " + buildNetworkKey(scanResult) + ": " + getNetworkScore(scanResult));
        }
    }

    /** Registers a CacheListener instance, replacing the previous listener if it existed. */
    public void registerListener(CacheListener listener) {
        synchronized (mCacheLock) {
            mListener = listener;
        }
    }

    /** Removes the registered CacheListener. */
    public void unregisterListener() {
        synchronized (mCacheLock) {
            mListener = null;
        }
    }

    /** Listener for updates to the cache inside WifiNetworkScoreCache. */
    public abstract static class CacheListener {

        private Handler mHandler;

        /**
         * Constructor for CacheListener.
         *
         * @param handler the Handler on which to invoke the {@link #networkCacheUpdated} method.
         *          This cannot be null.
         */
        public CacheListener(@NonNull Handler handler) {
            Preconditions.checkNotNull(handler);
            mHandler = handler;
        }

        /** Invokes the {@link #networkCacheUpdated(List<ScoredNetwork>)} method on the handler. */
        void post(List<ScoredNetwork> updatedNetworks) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    networkCacheUpdated(updatedNetworks);
                }
            });
        }

        /**
         * Invoked whenever the cache is updated.
         *
         * <p>Clearing the cache does not invoke this method.
         *
         * @param updatedNetworks the networks that were updated
         */
        public abstract void networkCacheUpdated(List<ScoredNetwork> updatedNetworks);
    }
}

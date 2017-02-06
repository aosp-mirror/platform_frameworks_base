/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package android.net;


import android.annotation.SystemApi;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.annotations.VisibleForTesting;

/**
 * A request for a network recommendation.
 *
 * @see {@link NetworkScoreManager#requestRecommendation(RecommendationRequest)}.
 * @hide
 */
@SystemApi
public final class RecommendationRequest implements Parcelable {
    private final ScanResult[] mScanResults;
    private final WifiConfiguration mDefaultConfig;
    private WifiConfiguration mConnectedConfig;
    private WifiConfiguration[] mConnectableConfigs;
    private final int mLastSelectedNetworkId;
    private final long mLastSelectedNetworkTimestamp;

    /**
     * Builder class for constructing {@link RecommendationRequest} instances.
     * @hide
     */
    @SystemApi
    public static final class Builder {
        private ScanResult[] mScanResults;
        private WifiConfiguration mDefaultConfig;
        private WifiConfiguration mConnectedConfig;
        private WifiConfiguration[] mConnectableConfigs;
        private int mLastSelectedNetworkId = -1;
        private long mLastSelectedTimestamp;

        public Builder setScanResults(ScanResult[] scanResults) {
            mScanResults = scanResults;
            return this;
        }

        /**
         * @param config the {@link WifiConfiguration} to return if no recommendation is available.
         * @return this
         */
        public Builder setDefaultWifiConfig(WifiConfiguration config) {
            this.mDefaultConfig = config;
            return this;
        }

        /**
         * @param config the {@link WifiConfiguration} of the connected network at the time the
         *               this request was made.
         * @return this
         */
        public Builder setConnectedWifiConfig(WifiConfiguration config) {
            this.mConnectedConfig = config;
            return this;
        }

        /**
         * @param connectableConfigs the set of saved {@link WifiConfiguration}s that can be
         *                           connected to based on the current set of {@link ScanResult}s.
         * @return this
         */
        public Builder setConnectableConfigs(WifiConfiguration[] connectableConfigs) {
            this.mConnectableConfigs = connectableConfigs;
            return this;
        }

        /**
         * @param networkId The {@link WifiConfiguration#networkId} of the last user selected
         *                  network.
         * @param timestamp The {@link android.os.SystemClock#elapsedRealtime()} when the user
         *                  selected {@code networkId}.
         * @return this
         */
        public Builder setLastSelectedNetwork(int networkId, long timestamp) {
            this.mLastSelectedNetworkId = networkId;
            this.mLastSelectedTimestamp = timestamp;
            return this;
        }

        /**
         * @return a new {@link RecommendationRequest} instance
         */
        public RecommendationRequest build() {
            return new RecommendationRequest(mScanResults, mDefaultConfig, mConnectedConfig,
                    mConnectableConfigs, mLastSelectedNetworkId, mLastSelectedTimestamp);
        }
    }

    /**
     * @return the array of {@link ScanResult}s the recommendation must be constrained to i.e. if a
     *         non-null wifi config recommendation is returned then it must be able to connect to
     *         one of the networks in the results list.
     *
     *         If the array is {@code null} or empty then there is no constraint.
     */
    public ScanResult[] getScanResults() {
        return mScanResults;
    }

    /**
     * @return the {@link WifiConfiguration} to return if no recommendation is available.
     */
    public WifiConfiguration getDefaultWifiConfig() {
        return mDefaultConfig;
    }

    /**
     * @return the {@link WifiConfiguration} of the connected network at the time the this request
     *         was made.
     */
    public WifiConfiguration getConnectedConfig() {
        return mConnectedConfig;
    }

    /**
     * @return the set of saved {@link WifiConfiguration}s that can be connected to based on the
     *         current set of {@link ScanResult}s.
     */
    public WifiConfiguration[] getConnectableConfigs() {
        return mConnectableConfigs;
    }

    /**
     * @param connectedConfig the {@link WifiConfiguration} of the connected network at the time
     *                        the this request was made.
     */
    public void setConnectedConfig(WifiConfiguration connectedConfig) {
        mConnectedConfig = connectedConfig;
    }

    /**
     * @param connectableConfigs the set of saved {@link WifiConfiguration}s that can be connected
     *                           to based on the current set of {@link ScanResult}s.
     */
    public void setConnectableConfigs(WifiConfiguration[] connectableConfigs) {
        mConnectableConfigs = connectableConfigs;
    }

    /**
     * @return The {@link WifiConfiguration#networkId} of the last user selected network.
     *         {@code -1} if not set.
     */
    public int getLastSelectedNetworkId() {
        return mLastSelectedNetworkId;
    }

    /**
     * @return The {@link android.os.SystemClock#elapsedRealtime()} when the user selected
     *         {@link #getLastSelectedNetworkId()}. {@code 0} if not set.
     */
    public long getLastSelectedNetworkTimestamp() {
        return mLastSelectedNetworkTimestamp;
    }

    @VisibleForTesting
    RecommendationRequest(ScanResult[] scanResults,
            WifiConfiguration defaultWifiConfig,
            WifiConfiguration connectedWifiConfig,
            WifiConfiguration[] connectableConfigs,
            int lastSelectedNetworkId,
            long lastSelectedNetworkTimestamp) {
        mScanResults = scanResults;
        mDefaultConfig = defaultWifiConfig;
        mConnectedConfig = connectedWifiConfig;
        mConnectableConfigs = connectableConfigs;
        mLastSelectedNetworkId = lastSelectedNetworkId;
        mLastSelectedNetworkTimestamp = lastSelectedNetworkTimestamp;
    }

    protected RecommendationRequest(Parcel in) {
        final int resultCount = in.readInt();
        if (resultCount > 0) {
            mScanResults = new ScanResult[resultCount];
            final ClassLoader classLoader = ScanResult.class.getClassLoader();
            for (int i = 0; i < resultCount; i++) {
                mScanResults[i] = in.readParcelable(classLoader);
            }
        } else {
            mScanResults = null;
        }

        mDefaultConfig = in.readParcelable(WifiConfiguration.class.getClassLoader());
        mConnectedConfig = in.readParcelable(WifiConfiguration.class.getClassLoader());

        final int configCount = in.readInt();
        if (configCount > 0) {
            mConnectableConfigs = new WifiConfiguration[configCount];
            final ClassLoader classLoader = WifiConfiguration.class.getClassLoader();
            for (int i = 0; i < configCount; i++) {
                mConnectableConfigs[i] = in.readParcelable(classLoader);
            }
        } else {
            mConnectableConfigs = null;
        }

        mLastSelectedNetworkId = in.readInt();
        mLastSelectedNetworkTimestamp = in.readLong();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (mScanResults != null) {
            dest.writeInt(mScanResults.length);
            for (int i = 0; i < mScanResults.length; i++) {
                dest.writeParcelable(mScanResults[i], flags);
            }
        } else {
            dest.writeInt(0);
        }

        dest.writeParcelable(mDefaultConfig, flags);
        dest.writeParcelable(mConnectedConfig, flags);

        if (mConnectableConfigs != null) {
            dest.writeInt(mConnectableConfigs.length);
            for (int i = 0; i < mConnectableConfigs.length; i++) {
                dest.writeParcelable(mConnectableConfigs[i], flags);
            }
        } else {
            dest.writeInt(0);
        }

        dest.writeInt(mLastSelectedNetworkId);
        dest.writeLong(mLastSelectedNetworkTimestamp);
    }

    public static final Creator<RecommendationRequest> CREATOR =
            new Creator<RecommendationRequest>() {
                @Override
                public RecommendationRequest createFromParcel(Parcel in) {
                    return new RecommendationRequest(in);
                }

                @Override
                public RecommendationRequest[] newArray(int size) {
                    return new RecommendationRequest[size];
                }
            };
}

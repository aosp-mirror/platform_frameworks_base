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
    private final WifiConfiguration mCurrentSelectedConfig;
    private final NetworkCapabilities mRequiredCapabilities;

    /**
     * Builder class for constructing {@link RecommendationRequest} instances.
     * @hide
     */
    @SystemApi
    public static final class Builder {
        private ScanResult[] mScanResults;
        private WifiConfiguration mCurrentConfig;
        private NetworkCapabilities mNetworkCapabilities;

        public Builder setScanResults(ScanResult[] scanResults) {
            mScanResults = scanResults;
            return this;
        }

        public Builder setCurrentRecommendedWifiConfig(WifiConfiguration config) {
            this.mCurrentConfig = config;
            return this;
        }

        public Builder setNetworkCapabilities(NetworkCapabilities capabilities) {
            mNetworkCapabilities = capabilities;
            return this;
        }

        public RecommendationRequest build() {
            return new RecommendationRequest(mScanResults, mCurrentConfig, mNetworkCapabilities);
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
     * @return The best recommendation at the time this {@code RecommendationRequest} instance
     *         was created. This may be null which indicates that no recommendation is available.
     */
    public WifiConfiguration getCurrentSelectedConfig() {
        return mCurrentSelectedConfig;
    }

    /**
     *
     * @return The set of {@link NetworkCapabilities} the recommendation must be constrained to.
     *         This may be {@code null} which indicates that there are no constraints on the
     *         capabilities of the recommended network.
     */
    public NetworkCapabilities getRequiredCapabilities() {
        return mRequiredCapabilities;
    }

    @VisibleForTesting
    RecommendationRequest(ScanResult[] scanResults,
            WifiConfiguration currentSelectedConfig,
            NetworkCapabilities requiredCapabilities) {
        mScanResults = scanResults;
        mCurrentSelectedConfig = currentSelectedConfig;
        mRequiredCapabilities = requiredCapabilities;
    }

    protected RecommendationRequest(Parcel in) {
        final int resultCount = in.readInt();
        if (resultCount > 0) {
            mScanResults = new ScanResult[resultCount];
            for (int i = 0; i < resultCount; i++) {
                mScanResults[i] = in.readParcelable(ScanResult.class.getClassLoader());
            }
        } else {
            mScanResults = null;
        }

        mCurrentSelectedConfig = in.readParcelable(WifiConfiguration.class.getClassLoader());
        mRequiredCapabilities = in.readParcelable(NetworkCapabilities.class.getClassLoader());
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
        dest.writeParcelable(mCurrentSelectedConfig, flags);
        dest.writeParcelable(mRequiredCapabilities, flags);
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

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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.net.wifi.WifiConfiguration;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

/**
 * The result of a network recommendation.
 *
 * @see {@link NetworkScoreManager#requestRecommendation(RecommendationRequest)}.
 * @hide
 */
@SystemApi
public final class RecommendationResult implements Parcelable {
    private final WifiConfiguration mWifiConfiguration;

    /**
     * Create a {@link RecommendationResult} that indicates that no network connection should be
     * attempted at this time.
     *
     * @return a {@link RecommendationResult}
     */
    public static RecommendationResult createDoNotConnectRecommendation() {
        return new RecommendationResult((WifiConfiguration) null);
    }

    /**
     * Create a {@link RecommendationResult} that indicates that a connection attempt should be
     * made for the given Wi-Fi network.
     *
     * @param wifiConfiguration {@link WifiConfiguration} with at least SSID and BSSID set.
     * @return a {@link RecommendationResult}
     */
    public static RecommendationResult createConnectRecommendation(
            @NonNull WifiConfiguration wifiConfiguration) {
        Preconditions.checkNotNull(wifiConfiguration, "wifiConfiguration must not be null");
        Preconditions.checkNotNull(wifiConfiguration.SSID, "SSID must not be null");
        Preconditions.checkNotNull(wifiConfiguration.BSSID, "BSSID must not be null");
        return new RecommendationResult(wifiConfiguration);
    }

    private RecommendationResult(@Nullable WifiConfiguration wifiConfiguration) {
        mWifiConfiguration = wifiConfiguration;
    }

    private RecommendationResult(Parcel in) {
        mWifiConfiguration = in.readParcelable(WifiConfiguration.class.getClassLoader());
    }

    /**
     * @return {@code true} if a network recommendation exists. {@code false} indicates that
     *         no connection should be attempted at this time.
     */
    public boolean hasRecommendation() {
        return mWifiConfiguration != null;
    }

    /**
     * @return The recommended {@link WifiConfiguration} to connect to. A {@code null} value
     *         is returned if {@link #hasRecommendation} returns {@code false}.
     */
    @Nullable public WifiConfiguration getWifiConfiguration() {
        return mWifiConfiguration;
    }

    @Override
    public String toString() {
      return "RecommendationResult{" +
          "mWifiConfiguration=" + mWifiConfiguration +
          "}";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeParcelable(mWifiConfiguration, flags);
    }

    public static final Creator<RecommendationResult> CREATOR =
            new Creator<RecommendationResult>() {
                @Override
                public RecommendationResult createFromParcel(Parcel in) {
                    return new RecommendationResult(in);
                }

                @Override
                public RecommendationResult[] newArray(int size) {
                    return new RecommendationResult[size];
                }
            };
}

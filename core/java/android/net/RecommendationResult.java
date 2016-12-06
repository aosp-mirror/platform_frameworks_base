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

import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.net.wifi.WifiConfiguration;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.annotations.VisibleForTesting;

/**
 * The result of a network recommendation.
 *
 * @see {@link NetworkScoreManager#requestRecommendation(RecommendationRequest)}.
 * @hide
 */
@SystemApi
public final class RecommendationResult implements Parcelable {
    private final WifiConfiguration mWifiConfiguration;

    public RecommendationResult(@Nullable WifiConfiguration wifiConfiguration) {
        mWifiConfiguration = wifiConfiguration;
    }

    private RecommendationResult(Parcel in) {
        mWifiConfiguration = in.readParcelable(WifiConfiguration.class.getClassLoader());
    }

    /**
     * @return The recommended {@link WifiConfiguration} to connect to. A {@code null} value
     *         indicates that no WiFi connection should be attempted at this time.
     */
    public WifiConfiguration getWifiConfiguration() {
        return mWifiConfiguration;
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

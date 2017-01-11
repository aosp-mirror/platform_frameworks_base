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
 * limitations under the License.
 */

package android.net.wifi.aware;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * The characteristics of the Wi-Fi Aware implementation.
 */
public final class Characteristics implements Parcelable {
    /** @hide */
    public static final String KEY_MAX_SERVICE_NAME_LENGTH = "key_max_service_name_length";
    /** @hide */
    public static final String KEY_MAX_SERVICE_SPECIFIC_INFO_LENGTH =
            "key_max_service_specific_info_length";
    /** @hide */
    public static final String KEY_MAX_MATCH_FILTER_LENGTH = "key_max_match_filter_length";

    private Bundle mCharacteristics = new Bundle();

    /** @hide : should not be created by apps */
    public Characteristics(Bundle characteristics) {
        mCharacteristics = characteristics;
    }

    /**
     * Returns the maximum string length that can be used to specify a Aware service name. Restricts
     * the parameters of the {@link PublishConfig.Builder#setServiceName(String)} and
     * {@link SubscribeConfig.Builder#setServiceName(String)}.
     *
     * @return A positive integer, maximum string length of Aware service name.
     */
    public int getMaxServiceNameLength() {
        return mCharacteristics.getInt(KEY_MAX_SERVICE_NAME_LENGTH);
    }

    /**
     * Returns the maximum length of byte array that can be used to specify a Aware service specific
     * information field: the arbitrary load used in discovery or the message length of Aware
     * message exchange. Restricts the parameters of the
     * {@link PublishConfig.Builder#setServiceSpecificInfo(byte[])},
     * {@link SubscribeConfig.Builder#setServiceSpecificInfo(byte[])}, and
     * {@link DiscoverySession#sendMessage(PeerHandle, int, byte[])}
     * variants.
     *
     * @return A positive integer, maximum length of byte array for Aware messaging.
     */
    public int getMaxServiceSpecificInfoLength() {
        return mCharacteristics.getInt(KEY_MAX_SERVICE_SPECIFIC_INFO_LENGTH);
    }

    /**
     * Returns the maximum length of byte array that can be used to specify a Aware match filter.
     * Restricts the parameters of the
     * {@link PublishConfig.Builder#setMatchFilter(java.util.List)} and
     * {@link SubscribeConfig.Builder#setMatchFilter(java.util.List)}.
     *
     * @return A positive integer, maximum legngth of byte array for Aware discovery match filter.
     */
    public int getMaxMatchFilterLength() {
        return mCharacteristics.getInt(KEY_MAX_MATCH_FILTER_LENGTH);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeBundle(mCharacteristics);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<Characteristics> CREATOR =
            new Creator<Characteristics>() {
                @Override
                public Characteristics createFromParcel(Parcel in) {
                    Characteristics c = new Characteristics(in.readBundle());
                    return c;
                }

                @Override
                public Characteristics[] newArray(int size) {
                    return new Characteristics[size];
                }
            };
}

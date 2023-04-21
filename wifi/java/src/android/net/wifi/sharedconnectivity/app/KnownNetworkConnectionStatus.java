/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.net.wifi.sharedconnectivity.app;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * The status of a connection to a known network after the client called
 * {@link SharedConnectivityManager#connectKnownNetwork}.
 *
 * @hide
 */
@SystemApi
public final class KnownNetworkConnectionStatus implements Parcelable {

    /**
     * Connection status is unknown.
     */
    public static final int CONNECTION_STATUS_UNKNOWN  = 0;

    /**
     * The connection's data was saved successfully in the Wi-Fi configuration.
     */
    public static final int CONNECTION_STATUS_SAVED  = 1;

    /**
     * Failed to save the connection's data in the Wi-Fi configuration.
     */
    public static final int CONNECTION_STATUS_SAVE_FAILED = 2;

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            CONNECTION_STATUS_UNKNOWN,
            CONNECTION_STATUS_SAVED,
            CONNECTION_STATUS_SAVE_FAILED,
    })
    public @interface ConnectionStatus {}

    @ConnectionStatus private final int mStatus;
    private final KnownNetwork mKnownNetwork;
    private final Bundle mExtras;

    /**
     * Builder class for {@link KnownNetworkConnectionStatus}.
     */
    public static final class Builder {
        @ConnectionStatus private int mStatus;
        private KnownNetwork mKnownNetwork;
        private Bundle mExtras = Bundle.EMPTY;

        public Builder() {}

        /**
         * Sets the status of the connection
         *
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder setStatus(@ConnectionStatus int status) {
            mStatus = status;
            return this;
        }

        /**
         * Sets the {@link KnownNetwork} object of the connection.
         *
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder setKnownNetwork(@NonNull KnownNetwork knownNetwork) {
            mKnownNetwork = knownNetwork;
            return this;
        }

        /**
         * Sets the extras bundle
         *
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder setExtras(@NonNull Bundle extras) {
            mExtras = extras;
            return this;
        }

        /**
         * Builds the {@link KnownNetworkConnectionStatus} object.
         *
         * @return Returns the built {@link KnownNetworkConnectionStatus} object.
         */
        @NonNull
        public KnownNetworkConnectionStatus build() {
            return new KnownNetworkConnectionStatus(mStatus, mKnownNetwork, mExtras);
        }
    }

    private static void validate(@ConnectionStatus int status) {
        if (status != CONNECTION_STATUS_UNKNOWN && status != CONNECTION_STATUS_SAVED
                && status != CONNECTION_STATUS_SAVE_FAILED) {
            throw new IllegalArgumentException("Illegal connection status");
        }
    }

    private KnownNetworkConnectionStatus(@ConnectionStatus int status, KnownNetwork knownNetwork,
            @NonNull Bundle extras) {
        validate(status);
        mStatus = status;
        mKnownNetwork = knownNetwork;
        mExtras = extras;
    }

    /**
     * Gets the status of the connection
     *
     * @return Returns true for enabled, false otherwise.
     */
    @ConnectionStatus
    public int getStatus() {
        return mStatus;
    }

    /**
     * Gets the {@link KnownNetwork} object of the connection.
     *
     * @return Returns a KnownNetwork object.
     */
    @NonNull
    public KnownNetwork getKnownNetwork() {
        return mKnownNetwork;
    }

    /**
     * Gets the extras Bundle.
     *
     * @return Returns a Bundle object.
     */
    @NonNull
    public Bundle getExtras() {
        return mExtras;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof KnownNetworkConnectionStatus)) return false;
        KnownNetworkConnectionStatus other = (KnownNetworkConnectionStatus) obj;
        return mStatus == other.getStatus()
                && Objects.equals(mKnownNetwork, other.getKnownNetwork());
    }

    @Override
    public int hashCode() {
        return Objects.hash(mStatus, mKnownNetwork);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mStatus);
        mKnownNetwork.writeToParcel(dest, flags);
        dest.writeBundle(mExtras);
    }

    /**
     * Creates a {@link KnownNetworkConnectionStatus} object from a parcel.
     *
     * @hide
     */
    @NonNull
    public static KnownNetworkConnectionStatus readFromParcel(@NonNull Parcel in) {
        return new KnownNetworkConnectionStatus(in.readInt(),
                KnownNetwork.readFromParcel(in),
                in.readBundle());
    }

    @NonNull
    public static final Creator<KnownNetworkConnectionStatus> CREATOR = new Creator<>() {
                @Override
                public KnownNetworkConnectionStatus createFromParcel(Parcel in) {
                    return readFromParcel(in);
                }

                @Override
                public KnownNetworkConnectionStatus[] newArray(int size) {
                    return new KnownNetworkConnectionStatus[size];
                }
            };

    @Override
    public String toString() {
        return new StringBuilder("KnownNetworkConnectionStatus[")
                .append("status=").append(mStatus)
                .append("known network=").append(mKnownNetwork.toString())
                .append("extras=").append(mExtras.toString())
                .append("]").toString();
    }
}

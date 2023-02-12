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
 * The status of a connection to an instant tether network after the client called
 * {@link SharedConnectivityManager#connectTetherNetwork}.
 *
 * @hide
 */
@SystemApi
public final class TetherNetworkConnectionStatus implements Parcelable {

    /**
     * Connection status is unknown.
     */
    public static final int CONNECTION_STATUS_UNKNOWN  = 0;

    /**
     * The connection is being initiated.
     */
    public static final int CONNECTION_STATUS_ENABLING_HOTSPOT  = 1;

    /**
     * Device providing the hotspot failed to initiate it.
     */
    public static final int CONNECTION_STATUS_UNKNOWN_ERROR = 2;

    /**
     * Failed to provision tethering.
     */
    public static final int CONNECTION_STATUS_PROVISIONING_FAILED = 3;

    /**
     * Timeout while trying to provision tethering.
     */
    public static final int CONNECTION_STATUS_TETHERING_TIMEOUT = 4;

    /**
     * Device doesn't support tethering.
     */
    public static final int CONNECTION_STATUS_TETHERING_UNSUPPORTED = 5;

    /**
     * Device has no cell data.
     */
    public static final int CONNECTION_STATUS_NO_CELL_DATA = 6;

    /**
     * Device failed to enable hotspot
     */
    public static final int CONNECTION_STATUS_ENABLING_HOTSPOT_FAILED = 7;

    /**
     * Timeout while trying to enable hotspot
     */
    public static final int CONNECTION_STATUS_ENABLING_HOTSPOT_TIMEOUT = 8;

    /**
     * Failed to connect to hotspot
     */
    public static final int CONNECTION_STATUS_CONNECT_TO_HOTSPOT_FAILED = 9;

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            CONNECTION_STATUS_UNKNOWN,
            CONNECTION_STATUS_ENABLING_HOTSPOT,
            CONNECTION_STATUS_UNKNOWN_ERROR,
            CONNECTION_STATUS_PROVISIONING_FAILED,
            CONNECTION_STATUS_TETHERING_TIMEOUT,
            CONNECTION_STATUS_TETHERING_UNSUPPORTED,
            CONNECTION_STATUS_NO_CELL_DATA,
            CONNECTION_STATUS_ENABLING_HOTSPOT_FAILED,
            CONNECTION_STATUS_ENABLING_HOTSPOT_TIMEOUT,
            CONNECTION_STATUS_CONNECT_TO_HOTSPOT_FAILED,
    })
    public @interface ConnectionStatus {}

    @ConnectionStatus private final int mStatus;
    private final Bundle mExtras;

    /**
     * Builder class for {@link TetherNetworkConnectionStatus}.
     */
    public static final class Builder {
        @ConnectionStatus private int mStatus;
        private Bundle mExtras;

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
         * Builds the {@link TetherNetworkConnectionStatus} object.
         *
         * @return Returns the built {@link TetherNetworkConnectionStatus} object.
         */
        @NonNull
        public TetherNetworkConnectionStatus build() {
            return new TetherNetworkConnectionStatus(mStatus, mExtras);
        }
    }

    private TetherNetworkConnectionStatus(@ConnectionStatus int status, Bundle extras) {
        mStatus = status;
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
        if (!(obj instanceof TetherNetworkConnectionStatus)) return false;
        TetherNetworkConnectionStatus other = (TetherNetworkConnectionStatus) obj;
        return mStatus == other.getStatus();
    }

    @Override
    public int hashCode() {
        return Objects.hash(mStatus);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mStatus);
        dest.writeBundle(mExtras);
    }

    @NonNull
    public static final Creator<TetherNetworkConnectionStatus> CREATOR = new Creator<>() {
                @Override
                public TetherNetworkConnectionStatus createFromParcel(Parcel in) {
                    return new TetherNetworkConnectionStatus(in.readInt(),
                            in.readBundle(getClass().getClassLoader()));
                }

                @Override
                public TetherNetworkConnectionStatus[] newArray(int size) {
                    return new TetherNetworkConnectionStatus[size];
                }
            };

    @Override
    public String toString() {
        return new StringBuilder("TetherNetworkConnectionStatus[")
                .append("status=").append(mStatus)
                .append("extras=").append(mExtras.toString())
                .append("]").toString();
    }
}

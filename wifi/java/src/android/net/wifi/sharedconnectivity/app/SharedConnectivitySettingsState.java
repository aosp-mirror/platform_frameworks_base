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

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;


/**
 * A data class representing the shared connectivity settings state.
 *
 * This class represents a snapshot of the settings and can be out of date if the settings changed
 * after receiving an object of this class.
 *
 * @hide
 */
@SystemApi
public final class SharedConnectivitySettingsState implements Parcelable {

    private final boolean mInstantTetherEnabled;
    private final Bundle mExtras;

    /**
     * Builder class for {@link SharedConnectivitySettingsState}.
     */
    public static final class Builder {
        private boolean mInstantTetherEnabled;
        private Bundle mExtras;

        public Builder() {}

        /**
         * Sets the state of Instant Tether in settings
         *
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder setInstantTetherEnabled(boolean instantTetherEnabled) {
            mInstantTetherEnabled = instantTetherEnabled;
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
         * Builds the {@link SharedConnectivitySettingsState} object.
         *
         * @return Returns the built {@link SharedConnectivitySettingsState} object.
         */
        @NonNull
        public SharedConnectivitySettingsState build() {
            return new SharedConnectivitySettingsState(mInstantTetherEnabled, mExtras);
        }
    }

    private SharedConnectivitySettingsState(boolean instantTetherEnabled, Bundle extras) {
        mInstantTetherEnabled = instantTetherEnabled;
        mExtras = extras;
    }

    /**
     * Gets the state of Instant Tether in settings
     *
     * @return Returns true for enabled, false otherwise.
     */
    public boolean isInstantTetherEnabled() {
        return mInstantTetherEnabled;
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
        if (!(obj instanceof SharedConnectivitySettingsState)) return false;
        SharedConnectivitySettingsState other = (SharedConnectivitySettingsState) obj;
        return mInstantTetherEnabled == other.isInstantTetherEnabled();
    }

    @Override
    public int hashCode() {
        return Objects.hash(mInstantTetherEnabled);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeBoolean(mInstantTetherEnabled);
        dest.writeBundle(mExtras);
    }

    @NonNull
    public static final Creator<SharedConnectivitySettingsState> CREATOR = new Creator<>() {
                @Override
                public SharedConnectivitySettingsState createFromParcel(Parcel in) {
                    return new SharedConnectivitySettingsState(in.readBoolean(),
                            in.readBundle(getClass().getClassLoader()));
                }

                @Override
                public SharedConnectivitySettingsState[] newArray(int size) {
                    return new SharedConnectivitySettingsState[size];
                }
            };

    @Override
    public String toString() {
        return new StringBuilder("SharedConnectivitySettingsState[")
                .append("instantTetherEnabled=").append(mInstantTetherEnabled)
                .append("extras=").append(mExtras.toString())
                .append("]").toString();
    }
}

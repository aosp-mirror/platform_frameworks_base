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
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.PendingIntent;
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
    private final PendingIntent mInstantTetherSettingsPendingIntent;
    private final Bundle mExtras;

    /**
     * Builder class for {@link SharedConnectivitySettingsState}.
     */
    public static final class Builder {
        private boolean mInstantTetherEnabled;
        private PendingIntent mInstantTetherSettingsPendingIntent;
        private Bundle mExtras = Bundle.EMPTY;

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
         * Sets the {@link PendingIntent} that will open the Instant Tether settings page.
         * The pending intent must be set as {@link PendingIntent#FLAG_IMMUTABLE}.
         *
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder setInstantTetherSettingsPendingIntent(@NonNull PendingIntent pendingIntent) {
            mInstantTetherSettingsPendingIntent = pendingIntent;
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
            return new SharedConnectivitySettingsState(mInstantTetherEnabled,
                    mInstantTetherSettingsPendingIntent, mExtras);

        }
    }

    private static void validate(PendingIntent pendingIntent) {
        if (pendingIntent != null && !pendingIntent.isImmutable()) {
            throw new IllegalArgumentException("Pending intent must be immutable");
        }
    }

    private SharedConnectivitySettingsState(boolean instantTetherEnabled,
            PendingIntent pendingIntent, @NonNull Bundle extras) {
        validate(pendingIntent);
        mInstantTetherEnabled = instantTetherEnabled;
        mInstantTetherSettingsPendingIntent = pendingIntent;
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
     * Gets the pending intent to open Instant Tether settings page.
     *
     * @return Returns the pending intent that opens the settings page, null if none.
     */
    @Nullable
    public PendingIntent getInstantTetherSettingsPendingIntent() {
        return mInstantTetherSettingsPendingIntent;
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
        return mInstantTetherEnabled == other.isInstantTetherEnabled()
                && Objects.equals(mInstantTetherSettingsPendingIntent,
                other.getInstantTetherSettingsPendingIntent());
    }

    @Override
    public int hashCode() {
        return Objects.hash(mInstantTetherEnabled, mInstantTetherSettingsPendingIntent);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        PendingIntent.writePendingIntentOrNullToParcel(mInstantTetherSettingsPendingIntent, dest);
        dest.writeBoolean(mInstantTetherEnabled);
        dest.writeBundle(mExtras);
    }

    /**
     * Creates a {@link SharedConnectivitySettingsState} object from a parcel.
     *
     * @hide
     */
    @NonNull
    public static SharedConnectivitySettingsState readFromParcel(@NonNull Parcel in) {
        PendingIntent pendingIntent = PendingIntent.readPendingIntentOrNullFromParcel(in);
        boolean instantTetherEnabled = in.readBoolean();
        Bundle extras = in.readBundle();
        return new SharedConnectivitySettingsState(instantTetherEnabled, pendingIntent, extras);
    }

    @NonNull
    public static final Creator<SharedConnectivitySettingsState> CREATOR = new Creator<>() {
                @Override
                public SharedConnectivitySettingsState createFromParcel(Parcel in) {
                    return readFromParcel(in);
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
                .append("PendingIntent=").append(mInstantTetherSettingsPendingIntent)
                .append("extras=").append(mExtras.toString())
                .append("]").toString();
    }
}

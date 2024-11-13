/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.settingslib.bluetooth.devicesettings;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

/** A data class representing a device setting state. */
public class DeviceSettingState implements Parcelable {
    @DeviceSettingId private final int mSettingId;
    private final DeviceSettingPreferenceState mPreferenceState;
    private final Bundle mExtras;

    DeviceSettingState(
            @DeviceSettingId int settingId,
            @NonNull DeviceSettingPreferenceState preferenceState,
            @NonNull Bundle extras) {
        validate(preferenceState);
        mSettingId = settingId;
        mPreferenceState = preferenceState;
        mExtras = extras;
    }

    private static void validate(DeviceSettingPreferenceState preferenceState) {
        if (Objects.isNull(preferenceState)) {
            throw new IllegalArgumentException("PreferenceState must be set");
        }
    }

    /** Reads a {@link DeviceSettingState} from {@link Parcel}. */
    @NonNull
    public static DeviceSettingState readFromParcel(@NonNull Parcel in) {
        int settingId = in.readInt();
        Bundle extra = in.readBundle(Bundle.class.getClassLoader());
        DeviceSettingPreferenceState preferenceState =
                DeviceSettingPreferenceState.readFromParcel(in);
        return new DeviceSettingState(settingId, preferenceState, extra);
    }

    public static final Creator<DeviceSettingState> CREATOR =
            new Creator<>() {
                @Override
                @NonNull
                public DeviceSettingState createFromParcel(@NonNull Parcel in) {
                    return readFromParcel(in);
                }

                @Override
                @NonNull
                public DeviceSettingState[] newArray(int size) {
                    return new DeviceSettingState[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    /** Writes the instance to {@link Parcel}. */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mSettingId);
        dest.writeBundle(mExtras);
        mPreferenceState.writeToParcel(dest, flags);
    }

    /** Builder class for {@link DeviceSettingState}. */
    public static final class Builder {
        private int mSettingId;
        private DeviceSettingPreferenceState mSettingPreferenceState;
        private Bundle mExtras = Bundle.EMPTY;

        public Builder() {}

        /**
         * Sets the setting ID, as defined by IntDef {@link DeviceSettingId}.
         *
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder setSettingId(@DeviceSettingId int settingId) {
            mSettingId = settingId;
            return this;
        }

        /**
         * Sets the setting preference state.
         *
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder setPreferenceState(
                @NonNull DeviceSettingPreferenceState settingPreferenceState) {
            mSettingPreferenceState = settingPreferenceState;
            return this;
        }

        /**
         * Sets the extras bundle.
         *
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder setExtras(@NonNull Bundle extras) {
            mExtras = extras;
            return this;
        }

        /** Build the object. */
        @NonNull
        public DeviceSettingState build() {
            return new DeviceSettingState(mSettingId, mSettingPreferenceState, mExtras);
        }
    }

    /**
     * Gets the setting ID, as defined by IntDef {@link DeviceSettingId}.
     *
     * @return the setting ID.
     */
    @DeviceSettingId
    public int getSettingId() {
        return mSettingId;
    }

    /**
     * Gets the preference state of the setting.
     *
     * @return the setting preference state.
     */
    @NonNull
    public DeviceSettingPreferenceState getPreferenceState() {
        return mPreferenceState;
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
    public boolean equals(@Nullable Object obj) {
        if (!(obj instanceof DeviceSettingState other)) return false;
        return mSettingId == other.mSettingId
                && Objects.equals(mPreferenceState, other.mPreferenceState);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mSettingId, mPreferenceState);
    }
}

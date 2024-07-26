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

/** A data class representing the state of an action/switch preference. */
public class ActionSwitchPreferenceState extends DeviceSettingPreferenceState
        implements Parcelable {
    private final boolean mChecked;
    private final Bundle mExtras;

    ActionSwitchPreferenceState(boolean checked, @NonNull Bundle extras) {
        super(DeviceSettingType.DEVICE_SETTING_TYPE_ACTION_SWITCH);
        mChecked = checked;
        mExtras = extras;
    }

    /**
     * Reads an {@link ActionSwitchPreferenceState} instance from {@link Parcel}
     * @param in The parcel to read from
     * @return The instance read
     */
    @NonNull
    public static ActionSwitchPreferenceState readFromParcel(@NonNull Parcel in) {
        boolean checked = in.readBoolean();
        Bundle extras = in.readBundle(Bundle.class.getClassLoader());
        return new ActionSwitchPreferenceState(checked, extras);
    }

    public static final Creator<ActionSwitchPreferenceState> CREATOR =
            new Creator<>() {
                @Override
                @NonNull
                public ActionSwitchPreferenceState createFromParcel(@NonNull Parcel in) {
                    in.readInt();
                    return readFromParcel(in);
                }

                @Override
                @NonNull
                public ActionSwitchPreferenceState[] newArray(int size) {
                    return new ActionSwitchPreferenceState[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeBoolean(mChecked);
        dest.writeBundle(mExtras);
    }

    /** Builder class for {@link ActionSwitchPreferenceState}. */
    public static final class Builder {
        private boolean mChecked;
        private Bundle mExtras = Bundle.EMPTY;

        public Builder() {}

        /**
         * Sets the state of the preference.
         *
         * @param checked Whether the switch is checked.
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder setChecked(boolean checked) {
            mChecked = checked;
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

        /**
         * Builds the object.
         *
         * @return Returns the built object.
         */
        @NonNull
        public ActionSwitchPreferenceState build() {
            return new ActionSwitchPreferenceState(mChecked, mExtras);
        }
    }

    /**
     * Whether the switch is checked.
     *
     * @return Whether the switch is checked.
     */
    public boolean getChecked() {
        return mChecked;
    }

    /**
     * Gets the extras bundle.
     *
     * @return The extra bundle.
     */
    @NonNull
    public Bundle getExtras() {
        return mExtras;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (!(obj instanceof ActionSwitchPreferenceState other)) return false;
        return mChecked == other.mChecked;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mChecked);
    }
}

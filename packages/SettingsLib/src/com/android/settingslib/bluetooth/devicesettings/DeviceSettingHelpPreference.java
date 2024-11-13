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

import android.content.Intent;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

/** A data class representing a help button displayed on the top right corner of the page. */
public class DeviceSettingHelpPreference extends DeviceSettingPreference implements Parcelable {

    private final Intent mIntent;
    private final Bundle mExtras;

    DeviceSettingHelpPreference(@NonNull Intent intent, Bundle extras) {
        super(DeviceSettingType.DEVICE_SETTING_TYPE_HELP);
        mIntent = intent;
        mExtras = extras;
    }

    /** Read a {@link DeviceSettingHelpPreference} from {@link Parcel}. */
    @NonNull
    public static DeviceSettingHelpPreference readFromParcel(@NonNull Parcel in) {
        Intent intent = in.readParcelable(Intent.class.getClassLoader());
        Bundle extras = in.readBundle(Bundle.class.getClassLoader());
        return new DeviceSettingHelpPreference(intent, extras);
    }

    public static final Creator<DeviceSettingHelpPreference> CREATOR =
            new Creator<>() {
                @Override
                @NonNull
                public DeviceSettingHelpPreference createFromParcel(@NonNull Parcel in) {
                    in.readInt();
                    return readFromParcel(in);
                }

                @Override
                @NonNull
                public DeviceSettingHelpPreference[] newArray(int size) {
                    return new DeviceSettingHelpPreference[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeParcelable(mIntent, flags);
        dest.writeBundle(mExtras);
    }

    /** Builder class for {@link DeviceSettingHelpPreference}. */
    public static final class Builder {
        private Intent mIntent;
        private Bundle mExtras = Bundle.EMPTY;

        /**
         * Sets the intent of the preference, should be an activity intent.
         *
         * @param intent The intent to launch when clicked.
         * @return Returns the Builder object.
         */
        @NonNull
        public DeviceSettingHelpPreference.Builder setIntent(@NonNull Intent intent) {
            mIntent = intent;
            return this;
        }

        /**
         * Sets the extras bundle.
         *
         * @return Returns the Builder object.
         */
        @NonNull
        public DeviceSettingHelpPreference.Builder setExtras(@NonNull Bundle extras) {
            mExtras = extras;
            return this;
        }

        /**
         * Builds the {@link DeviceSettingHelpPreference} object.
         *
         * @return Returns the built {@link DeviceSettingHelpPreference} object.
         */
        @NonNull
        public DeviceSettingHelpPreference build() {
            return new DeviceSettingHelpPreference(mIntent, mExtras);
        }
    }

    /**
     * Gets the intent to launch when clicked.
     *
     * @return The intent.
     */
    @NonNull
    public Intent getIntent() {
        return mIntent;
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
}

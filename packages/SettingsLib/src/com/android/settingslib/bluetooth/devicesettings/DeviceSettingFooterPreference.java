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

/** A data class representing a footer preference. */
public class DeviceSettingFooterPreference extends DeviceSettingPreference implements Parcelable {

    private final String mFooterText;
    private final Bundle mExtras;

    DeviceSettingFooterPreference(
            @NonNull String footerText,
            Bundle extras) {
        super(DeviceSettingType.DEVICE_SETTING_TYPE_FOOTER);
        mFooterText = footerText;
        mExtras = extras;
    }

    /** Read a {@link DeviceSettingFooterPreference} from {@link Parcel}. */
    @NonNull
    public static DeviceSettingFooterPreference readFromParcel(@NonNull Parcel in) {
        String footerText = in.readString();
        Bundle extras = in.readBundle(Bundle.class.getClassLoader());
        return new DeviceSettingFooterPreference(footerText, extras);
    }

    public static final Creator<DeviceSettingFooterPreference> CREATOR =
            new Creator<>() {
                @Override
                @NonNull
                public DeviceSettingFooterPreference createFromParcel(@NonNull Parcel in) {
                    in.readInt();
                    return readFromParcel(in);
                }

                @Override
                @NonNull
                public DeviceSettingFooterPreference[] newArray(int size) {
                    return new DeviceSettingFooterPreference[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeString(mFooterText);
        dest.writeBundle(mExtras);
    }

    /** Builder class for {@link DeviceSettingFooterPreference}. */
    public static final class Builder {
        private String mFooterText = "";
        private Bundle mExtras = Bundle.EMPTY;

        /**
         * Sets the footer text of the preference.
         *
         * @param footerText The footer text of the preference.
         * @return Returns the Builder object.
         */
        @NonNull
        public DeviceSettingFooterPreference.Builder setFooterText(@NonNull String footerText) {
            mFooterText = footerText;
            return this;
        }

        /**
         * Sets the extras bundle.
         *
         * @return Returns the Builder object.
         */
        @NonNull
        public DeviceSettingFooterPreference.Builder setExtras(@NonNull Bundle extras) {
            mExtras = extras;
            return this;
        }

        /**
         * Builds the {@link DeviceSettingFooterPreference} object.
         *
         * @return Returns the built {@link DeviceSettingFooterPreference} object.
         */
        @NonNull
        public DeviceSettingFooterPreference build() {
            return new DeviceSettingFooterPreference(
                    mFooterText, mExtras);
        }
    }

    /**
     * Gets the footer text of the preference.
     *
     * @return The footer text.
     */
    @NonNull
    public String getFooterText() {
        return mFooterText;
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

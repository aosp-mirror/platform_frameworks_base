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

import java.util.Objects;

/** An abstract class representing a device setting action. */
public class DeviceSettingIntentAction extends DeviceSettingAction implements Parcelable {
    private final Intent mIntent;
    private final Bundle mExtras;

    DeviceSettingIntentAction(@NonNull Intent intent, @NonNull Bundle extras) {
        super(DeviceSettingActionType.DEVICE_SETTING_ACTION_TYPE_INTENT);
        validate(intent);
        mIntent = intent;
        mExtras = extras;
    }

    private static void validate(Intent intent) {
        if (Objects.isNull(intent)) {
            throw new IllegalArgumentException("Intent must be set");
        }
    }

    /** Read a {@link DeviceSettingIntentAction} instance from {@link Parcel} */
    @NonNull
    public static DeviceSettingIntentAction readFromParcel(@NonNull Parcel in) {
        Intent intent = in.readParcelable(Intent.class.getClassLoader());
        Bundle extras = in.readBundle(Bundle.class.getClassLoader());
        return new DeviceSettingIntentAction(intent, extras);
    }

    public static final Creator<DeviceSettingIntentAction> CREATOR =
            new Creator<>() {
                @Override
                @NonNull
                public DeviceSettingIntentAction createFromParcel(@NonNull Parcel in) {
                    in.readInt();
                    return readFromParcel(in);
                }

                @Override
                @NonNull
                public DeviceSettingIntentAction[] newArray(int size) {
                    return new DeviceSettingIntentAction[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    /** Writes the instance to {@link Parcel}. */
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeParcelable(mIntent, flags);
        dest.writeBundle(mExtras);
    }

    /** Builder class for {@link DeviceSettingFooterPreference}. */
    public static final class Builder {
        private Intent mIntent = null;
        private Bundle mExtras = Bundle.EMPTY;

        /**
         * Sets the intent for the action.
         *
         * @param intent The intent.
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder setIntent(@NonNull Intent intent) {
            mIntent = intent;
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
         * Builds the {@link DeviceSettingIntentAction} object.
         *
         * @return Returns the built {@link DeviceSettingIntentAction} object.
         */
        @NonNull
        public DeviceSettingIntentAction build() {
            return new DeviceSettingIntentAction(mIntent, mExtras);
        }
    }

    /** Gets the intent. */
    @NonNull
    public Intent getIntent() {
        return mIntent;
    }

    /** Gets the extra bundle. */
    @NonNull
    public Bundle getExtras() {
        return mExtras;
    }
}

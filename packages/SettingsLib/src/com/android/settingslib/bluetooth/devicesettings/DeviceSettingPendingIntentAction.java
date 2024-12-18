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

import android.app.PendingIntent;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import java.util.Objects;

/** An abstract class representing a device setting action. */
public class DeviceSettingPendingIntentAction extends DeviceSettingAction implements Parcelable {
    private final PendingIntent mPendingIntent;
    private final Bundle mExtras;

    DeviceSettingPendingIntentAction(@NonNull PendingIntent pendingIntent, @NonNull Bundle extras) {
        super(DeviceSettingActionType.DEVICE_SETTING_ACTION_TYPE_PENDING_INTENT);
        validate(pendingIntent);
        mPendingIntent = pendingIntent;
        mExtras = extras;
    }

    private static void validate(PendingIntent pendingIntent) {
        if (Objects.isNull(pendingIntent)) {
            throw new IllegalArgumentException("PendingIntent must be set");
        }
    }

    /** Read a {@link DeviceSettingPendingIntentAction} instance from {@link Parcel} */
    @NonNull
    public static DeviceSettingPendingIntentAction readFromParcel(@NonNull Parcel in) {
        PendingIntent pendingIntent = in.readParcelable(PendingIntent.class.getClassLoader());
        Bundle extras = in.readBundle(Bundle.class.getClassLoader());
        return new DeviceSettingPendingIntentAction(pendingIntent, extras);
    }

    public static final Creator<DeviceSettingPendingIntentAction> CREATOR =
            new Creator<>() {
                @Override
                @NonNull
                public DeviceSettingPendingIntentAction createFromParcel(@NonNull Parcel in) {
                    in.readInt();
                    return readFromParcel(in);
                }

                @Override
                @NonNull
                public DeviceSettingPendingIntentAction[] newArray(int size) {
                    return new DeviceSettingPendingIntentAction[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    /** Writes the instance to {@link Parcel}. */
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeParcelable(mPendingIntent, flags);
        dest.writeBundle(mExtras);
    }

    /** Builder class for {@link DeviceSettingFooterPreference}. */
    public static final class Builder {
        private PendingIntent mPendingIntent = null;
        private Bundle mExtras = Bundle.EMPTY;

        /**
         * Sets the intent for the action.
         *
         * @param pendingIntent The pending intent.
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder setPendingIntent(@NonNull PendingIntent pendingIntent) {
            mPendingIntent = pendingIntent;
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
         * Builds the {@link DeviceSettingPendingIntentAction} object.
         *
         * @return Returns the built {@link DeviceSettingPendingIntentAction} object.
         */
        @NonNull
        public DeviceSettingPendingIntentAction build() {
            return new DeviceSettingPendingIntentAction(mPendingIntent, mExtras);
        }
    }

    /** Gets the pending intent. */
    @NonNull
    public PendingIntent getPendingIntent() {
        return mPendingIntent;
    }

    /** Gets the extra bundle. */
    @NonNull
    public Bundle getExtras() {
        return mExtras;
    }
}

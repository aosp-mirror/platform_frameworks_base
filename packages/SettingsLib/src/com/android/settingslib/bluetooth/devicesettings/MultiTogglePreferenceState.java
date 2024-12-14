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

/** A data class representing a multi-toggle preference state. */
public class MultiTogglePreferenceState extends DeviceSettingPreferenceState implements Parcelable {
    private final int mState;
    private final Bundle mExtras;

    MultiTogglePreferenceState(int state, @NonNull Bundle extras) {
        super(DeviceSettingType.DEVICE_SETTING_TYPE_MULTI_TOGGLE);
        mState = state;
        mExtras = extras;
    }

    /** Reads a {@link MultiTogglePreferenceState} from {@link Parcel}. */
    @NonNull
    public static MultiTogglePreferenceState readFromParcel(@NonNull Parcel in) {
        int state = in.readInt();
        Bundle extras = in.readBundle(Bundle.class.getClassLoader());
        return new MultiTogglePreferenceState(state, extras);
    }

    public static final Creator<MultiTogglePreferenceState> CREATOR =
            new Creator<>() {
                @Override
                @NonNull
                public MultiTogglePreferenceState createFromParcel(@NonNull Parcel in) {
                    in.readInt();
                    return readFromParcel(in);
                }

                @Override
                @NonNull
                public MultiTogglePreferenceState[] newArray(int size) {
                    return new MultiTogglePreferenceState[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeInt(mState);
        dest.writeBundle(mExtras);
    }

    /** Builder class for {@link MultiTogglePreferenceState}. */
    public static final class Builder {
        private int mState;
        private Bundle mExtras = Bundle.EMPTY;

        public Builder() {}

        /**
         * Sets the state of {@link MultiTogglePreference}.
         *
         * @return Returns the index of enabled toggle.
         */
        @NonNull
        public Builder setState(int state) {
            mState = state;
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

        /** Builds the object. */
        @NonNull
        public MultiTogglePreferenceState build() {
            return new MultiTogglePreferenceState(mState, mExtras);
        }
    }

    /**
     * Gets the state of the {@link MultiTogglePreference}.
     *
     * @return Returns the index of the enabled toggle.
     */
    public int getState() {
        return mState;
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
        if (!(obj instanceof MultiTogglePreferenceState other)) return false;
        return mState == other.mState;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mState);
    }
}

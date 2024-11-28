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

package android.service.settings.preferences;

import android.annotation.FlaggedApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.android.settingslib.flags.Flags;

import java.util.Objects;

/**
 * Request parameters to set the current value to a Settings Preference.
 * <p>This object passed to {@link SettingsPreferenceService#onSetPreferenceValue} will result in a
 * {@link SetValueResult}.
 * <ul>
 *   <li>{@link #getScreenKey} is a parameter to distinguish the container screen
 *   of a preference as a preference key may not be unique within its application.
 *   <li>{@link #getPreferenceKey} is a parameter to identify the preference for which the value is
 *   being requested. These keys will be unique with their Preference Screen, but may not be unique
 *   within their application, so it is required to pair this with {@link #getScreenKey} to
 *   ensure this request matches the intended target.
 *   <li>{@link #getPreferenceValue} is a parameter to specify the value that this request aims to
 *   set. If this value is invalid (malformed or does not match the type of the preference) then
 *   this request will fail.
 * </ul>
 */
@FlaggedApi(Flags.FLAG_SETTINGS_CATALYST)
public final class SetValueRequest implements Parcelable {

    @NonNull
    private final String mScreenKey;
    @NonNull
    private final String mPreferenceKey;
    @NonNull
    private final SettingsPreferenceValue mPreferenceValue;

    /**
     * Returns the screen key of requested Preference.
     */
    @NonNull
    public String getScreenKey() {
        return mScreenKey;
    }

    /**
     * Returns the key of requested Preference.
     */
    @NonNull
    public String getPreferenceKey() {
        return mPreferenceKey;
    }

    /**
     * Returns the value of requested Preference.
     */
    @NonNull
    public SettingsPreferenceValue getPreferenceValue() {
        return mPreferenceValue;
    }

    private SetValueRequest(@NonNull Builder builder) {
        mScreenKey = builder.mScreenKey;
        mPreferenceKey = builder.mPreferenceKey;
        mPreferenceValue = builder.mPreferenceValue;
    }

    private SetValueRequest(@NonNull Parcel in) {
        mScreenKey = Objects.requireNonNull(in.readString8());
        mPreferenceKey = Objects.requireNonNull(in.readString8());
        mPreferenceValue = Objects.requireNonNull(in.readParcelable(
                SettingsPreferenceValue.class.getClassLoader(), SettingsPreferenceValue.class));
    }

    /** @hide */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(mScreenKey);
        dest.writeString8(mPreferenceKey);
        dest.writeParcelable(mPreferenceValue, flags);
    }

    /** @hide */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Parcelable Creator for {@link SetValueRequest}.
     */
    @NonNull
    public static final Creator<SetValueRequest> CREATOR = new Creator<SetValueRequest>() {
        @Override
        public SetValueRequest createFromParcel(@NonNull Parcel in) {
            return new SetValueRequest(in);
        }

        @Override
        public SetValueRequest[] newArray(int size) {
            return new SetValueRequest[size];
        }
    };

    /**
     * Builder to construct {@link SetValueRequest}.
     */
    public static final class Builder {
        private final String mScreenKey;
        private final String mPreferenceKey;
        private final SettingsPreferenceValue mPreferenceValue;

        /**
         * Create Builder instance.
         * @param screenKey required to be not empty
         * @param preferenceKey required to be not empty
         * @param value value to set to requested Preference
         */
        public Builder(@NonNull String screenKey, @NonNull String preferenceKey,
                       @NonNull SettingsPreferenceValue value) {
            if (TextUtils.isEmpty(screenKey)) {
                throw new IllegalArgumentException("screenKey cannot be empty");
            }
            if (TextUtils.isEmpty(preferenceKey)) {
                throw new IllegalArgumentException("preferenceKey cannot be empty");
            }
            mScreenKey = screenKey;
            mPreferenceKey = preferenceKey;
            mPreferenceValue = value;
        }

        /**
         * Constructs an immutable {@link SetValueRequest} object.
         */
        @NonNull
        public SetValueRequest build() {
            return new SetValueRequest(this);
        }
    }
}

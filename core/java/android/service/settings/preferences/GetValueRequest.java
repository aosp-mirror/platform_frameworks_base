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
 * Request parameters to retrieve the current value of a Settings Preference.
 *
 * <p>This object passed to {@link SettingsPreferenceService#onGetPreferenceValue} will result
 * in a {@link GetValueResult}.
 *
 * <ul>
 *   <li>{@link #getScreenKey} is a parameter to distinguish the container screen
 *   of a preference as a preference key may not be unique within its application.
 *   <li>{@link #getPreferenceKey} is a parameter to identify the preference for which the value is
 *   being requested. These keys will be unique with their Preference Screen, but may not be unique
 *   within their application, so it is required to pair this with {@link #getScreenKey} to
 *   ensure this request matches the intended target.
 * </ul>
 */
@FlaggedApi(Flags.FLAG_SETTINGS_CATALYST)
public final class GetValueRequest implements Parcelable {

    @NonNull
    private final String mScreenKey;
    @NonNull
    private final String mPreferenceKey;

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

    private GetValueRequest(@NonNull Builder builder) {
        mScreenKey = builder.mScreenKey;
        mPreferenceKey = builder.mPreferenceKey;
    }

    private GetValueRequest(@NonNull Parcel in) {
        mScreenKey = Objects.requireNonNull(in.readString8());
        mPreferenceKey = Objects.requireNonNull(in.readString8());
    }

    /** @hide */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(mScreenKey);
        dest.writeString8(mPreferenceKey);
    }

    /** @hide */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Parcelable Creator for {@link GetValueRequest}.
     */
    @NonNull
    public static final Creator<GetValueRequest> CREATOR = new Creator<GetValueRequest>() {
        @Override
        public GetValueRequest createFromParcel(@NonNull Parcel in) {
            return new GetValueRequest(in);
        }

        @Override
        public GetValueRequest[] newArray(int size) {
            return new GetValueRequest[size];
        }
    };

    /**
     * Builder to construct {@link GetValueRequest}.
     */
    public static final class Builder {
        private final String mScreenKey;
        private final String mPreferenceKey;

        /**
         * Create Builder instance.
         * @param screenKey required to be not empty
         * @param preferenceKey required to be not empty
         */
        public Builder(@NonNull String screenKey, @NonNull String preferenceKey) {
            if (TextUtils.isEmpty(screenKey)) {
                throw new IllegalArgumentException("screenKey cannot be empty");
            }
            if (TextUtils.isEmpty(preferenceKey)) {
                throw new IllegalArgumentException("preferenceKey cannot be empty");
            }
            mScreenKey = screenKey;
            mPreferenceKey = preferenceKey;
        }

        /**
         * Constructs an immutable {@link GetValueRequest} object.
         */
        @NonNull
        public GetValueRequest build() {
            return new GetValueRequest(this);
        }
    }
}

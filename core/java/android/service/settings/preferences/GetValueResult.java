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
import android.annotation.IntDef;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.settingslib.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Result object given a corresponding {@link GetValueRequest}.
 * <ul>
 *   <li>If the request was successful, {@link #getResultCode} will be {@link #RESULT_OK},
 *   {@link #getValue} will be populated with the settings preference value and
 *   {@link #getMetadata} will be populated with its metadata.
 *   <li>If the request is unsuccessful, {@link #getResultCode} be a value other than
 *   {@link #RESULT_OK} - see documentation for those possibilities to understand the cause
 *   of the failure.
 * </ul>
 */
@FlaggedApi(Flags.FLAG_SETTINGS_CATALYST)
public final class GetValueResult implements Parcelable {

    @ResultCode
    private final int mResultCode;
    @Nullable
    private final SettingsPreferenceValue mValue;
    @Nullable
    private final SettingsPreferenceMetadata mMetadata;

    /**
     * Returns the result code indicating status of the request.
     */
    @ResultCode
    public int getResultCode() {
        return mResultCode;
    }

    /**
     * Returns the value of requested Preference if request successful.
     */
    @Nullable
    public SettingsPreferenceValue getValue() {
        return mValue;
    }

    /**
     * Returns the metadata of requested Preference if request successful.
     */
    @Nullable
    public SettingsPreferenceMetadata getMetadata() {
        return mMetadata;
    }

    /** @hide */
    @IntDef(prefix = { "RESULT_" }, value = {
            RESULT_OK,
            RESULT_UNSUPPORTED,
            RESULT_UNAVAILABLE,
            RESULT_REQUIRE_APP_PERMISSION,
            RESULT_DISALLOW,
            RESULT_INVALID_REQUEST,
            RESULT_INTERNAL_ERROR,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ResultCode {
    }

    /** Request is successful. */
    public static final int RESULT_OK = 0;
    /**
     * Requested preference is not supported by this API.
     * <p>Retry not advised.
     */
    public static final int RESULT_UNSUPPORTED = 1;
    /**
     * Preference is currently not available, likely due to device state or the state of
     * a dependency.
     * <p>Retry may succeed if underlying conditions change.
     */
    public static final int RESULT_UNAVAILABLE = 2;
    /**
     * Requested preference requires permissions not held by the calling application.
     * <p>Retry may succeed if necessary permissions are obtained.
     */
    public static final int RESULT_REQUIRE_APP_PERMISSION = 3;
    /**
     * Requested preference is not allowed for access in this API under the current device policy.
     * <p>Retry may succeed if underlying conditions change.
     */
    public static final int RESULT_DISALLOW = 4;
    /**
     * Request object is not valid.
     * <p>Retry not advised with current parameters.
     */
    public static final int RESULT_INVALID_REQUEST = 5;
    /**
     * API call failed due to an issue with the service binding.
     * <p>Retry may succeed.
     */
    public static final int RESULT_INTERNAL_ERROR = 6;


    private GetValueResult(@NonNull Builder builder) {
        mResultCode = builder.mResultCode;
        mValue = builder.mValue;
        mMetadata = builder.mMetadata;
    }

    private GetValueResult(@NonNull Parcel in) {
        mResultCode = in.readInt();
        mValue = in.readParcelable(SettingsPreferenceValue.class.getClassLoader(),
                SettingsPreferenceValue.class);
        mMetadata = in.readParcelable(SettingsPreferenceMetadata.class.getClassLoader(),
                SettingsPreferenceMetadata.class);
    }

    /** @hide */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mResultCode);
        dest.writeParcelable(mValue, flags);
        dest.writeParcelable(mMetadata, flags);
    }

    /** @hide */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Parcelable Creator for {@link GetValueResult}.
     */
    @NonNull
    public static final Creator<GetValueResult> CREATOR = new Creator<>() {
        @Override
        public GetValueResult createFromParcel(@NonNull Parcel in) {
            return new GetValueResult(in);
        }

        @Override
        public GetValueResult[] newArray(int size) {
            return new GetValueResult[size];
        }
    };

    /**
     * Builder to construct {@link GetValueResult}.
     */
    public static final class Builder {
        @ResultCode
        private final int mResultCode;
        private SettingsPreferenceValue mValue;
        private SettingsPreferenceMetadata mMetadata;

        /**
         * Create Builder instance.
         * @param resultCode indicates status of the request
         */
        public Builder(@ResultCode int resultCode) {
            mResultCode = resultCode;
        }

        /**
         * Sets the preference value on the result.
         */
        @NonNull
        public Builder setValue(@Nullable SettingsPreferenceValue value) {
            mValue = value;
            return this;
        }

        /**
         * Sets the metadata on the result.
         */
        @NonNull
        public Builder setMetadata(@Nullable SettingsPreferenceMetadata metadata) {
            mMetadata = metadata;
            return this;
        }

        /**
         * Constructs an immutable {@link GetValueResult} object.
         */
        @NonNull
        public GetValueResult build() {
            return new GetValueResult(this);
        }
    }
}

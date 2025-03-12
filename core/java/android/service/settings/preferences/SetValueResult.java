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

import com.android.settingslib.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Result object given a corresponding {@link SetValueRequest}.
 * <ul>
 *   <li>If the request was successful, {@link #getResultCode} will be {@link #RESULT_OK}.
 *   <li>If the request is unsuccessful, {@link #getResultCode} be a value other than
 *   {@link #RESULT_OK} - see documentation for those possibilities to understand the cause
 *   of the failure.
 * </ul>
 */
@FlaggedApi(Flags.FLAG_SETTINGS_CATALYST)
public final class SetValueResult implements Parcelable {

    @ResultCode
    private final int mResultCode;

    /**
     * Returns the result code indicating status of the request.
     */
    @ResultCode
    public int getResultCode() {
        return mResultCode;
    }

    /** @hide */
    @IntDef(prefix = { "RESULT_" }, value = {
            RESULT_OK,
            RESULT_UNSUPPORTED,
            RESULT_DISABLED,
            RESULT_RESTRICTED,
            RESULT_UNAVAILABLE,
            RESULT_REQUIRE_APP_PERMISSION,
            RESULT_REQUIRE_USER_CONSENT,
            RESULT_DISALLOW,
            RESULT_INVALID_REQUEST,
            RESULT_INTERNAL_ERROR
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ResultCode {
    }

    /** Request is successful and the value was set. */
    public static final int RESULT_OK = 0;
    /**
     * Requested preference is not supported by this API.
     * <p>Retry not advised.
     */
    public static final int RESULT_UNSUPPORTED = 1;
    /**
     * Requested preference is disabled, thus unable to be set in this state.
     * <p>Retry may succeed if underlying conditions change.
     */
    public static final int RESULT_DISABLED = 2;
    /**
     * Requested preference is restricted, thus unable to be set under this policy.
     * <p>Retry may succeed if underlying conditions change.
     */
    public static final int RESULT_RESTRICTED = 3;
    /**
     * Preference is currently not available, likely due to device state or the state of
     * a dependency.
     * <p>Retry may succeed if underlying conditions change.
     */
    public static final int RESULT_UNAVAILABLE = 4;
    /**
     * Requested preference requires permissions not held by the calling application.
     * <p>Retry may succeed if necessary permissions are obtained.
     */
    public static final int RESULT_REQUIRE_APP_PERMISSION = 5;
    /**
     * User consent was not approved for this operation.
     * <p>Retry may succeed if user provides consent.
     */
    public static final int RESULT_REQUIRE_USER_CONSENT = 6;
    /**
     * Requested preference is not allowed for access in this API under the current device policy.
     * <p>Retry may succeed if underlying conditions change.
     */
    public static final int RESULT_DISALLOW = 7;
    /**
     * Request object is not valid.
     * <p>Retry not advised with current parameters.
     */
    public static final int RESULT_INVALID_REQUEST = 8;
    /**
     * API call failed due to an issue with the service binding.
     * <p>Retry may succeed.
     */
    public static final int RESULT_INTERNAL_ERROR = 9;

    private SetValueResult(@NonNull Builder builder) {
        mResultCode = builder.mResultCode;
    }

    private SetValueResult(@NonNull Parcel in) {
        mResultCode = in.readInt();
    }

    /** @hide */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mResultCode);
    }

    /** @hide */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Parcelable Creator for {@link SetValueResult}.
     */
    @NonNull
    public static final Creator<SetValueResult> CREATOR = new Creator<>() {
        @Override
        public SetValueResult createFromParcel(@NonNull Parcel in) {
            return new SetValueResult(in);
        }

        @Override
        public SetValueResult[] newArray(int size) {
            return new SetValueResult[size];
        }
    };

    /**
     * Builder to construct {@link SetValueResult}.
     */
    public static final class Builder {
        @ResultCode
        private final int mResultCode;

        /**
         * Create Builder instance.
         * @param resultCode indicates status of the request
         */
        public Builder(@ResultCode int resultCode) {
            mResultCode = resultCode;
        }

        /**
         * Constructs an immutable {@link SetValueResult} object.
         */
        @NonNull
        public SetValueResult build() {
            return new SetValueResult(this);
        }
    }
}

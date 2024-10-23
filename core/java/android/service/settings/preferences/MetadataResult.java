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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result object given a corresponding {@link MetadataRequest}.
 * <ul>
 *   <li>If the request was successful, {@link #getResultCode} will be {@link #RESULT_OK} and
 *   {@link #getMetadataList} will be populated with metadata for all available preferences within
 *   this application.
 *   <li>If the request is unsuccessful, {@link #getResultCode} be a value other than
 *   {@link #RESULT_OK} - see documentation for those possibilities to understand the cause
 *   of the failure.
 * </ul>
 */
@FlaggedApi(Flags.FLAG_SETTINGS_CATALYST)
public final class MetadataResult implements Parcelable {

    @ResultCode
    private final int mResultCode;
    @NonNull
    private final List<SettingsPreferenceMetadata> mMetadataList;

    /**
     * Returns the result code indicating status of the request.
     */
    @ResultCode
    public int getResultCode() {
        return mResultCode;
    }

    /**
     * Returns the list of available Preference Metadata.
     * <p>This instance is shared so this list should not be modified.
     */
    @NonNull
    public List<SettingsPreferenceMetadata> getMetadataList() {
        return mMetadataList;
    }

    /** @hide */
    @IntDef(prefix = { "RESULT_" }, value = {
            RESULT_OK,
            RESULT_UNSUPPORTED,
            RESULT_INTERNAL_ERROR
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ResultCode {
    }

    /** Request is successful. */
    public static final int RESULT_OK = 0;
    /**
     * No preferences in this application support this API.
     * <p>Retry not advised.
     */
    public static final int RESULT_UNSUPPORTED = 1;
    /**
     * API call failed due to an issue with the service binding.
     * <p>Retry may succeed.
     */
    public static final int RESULT_INTERNAL_ERROR = 2;

    private MetadataResult(@NonNull Builder builder) {
        mResultCode = builder.mResultCode;
        mMetadataList = builder.mMetadataList;
    }
    private MetadataResult(@NonNull Parcel in) {
        mResultCode = in.readInt();
        mMetadataList = new ArrayList<>();
        in.readTypedList(mMetadataList, SettingsPreferenceMetadata.CREATOR);
    }

    /** @hide */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mResultCode);
        dest.writeTypedList(mMetadataList, flags);
    }

    /** @hide */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Parcelable Creator for {@link MetadataResult}.
     */
    @NonNull
    public static final Creator<MetadataResult> CREATOR = new Creator<>() {
        @Override
        public MetadataResult createFromParcel(@NonNull Parcel in) {
            return new MetadataResult(in);
        }

        @Override
        public MetadataResult[] newArray(int size) {
            return new MetadataResult[size];
        }
    };

    /**
     * Builder to construct {@link MetadataResult}.
     */
    public static final class Builder {
        @ResultCode
        private final int mResultCode;
        private List<SettingsPreferenceMetadata> mMetadataList = Collections.emptyList();

        /**
         * Create Builder instance.
         * @param resultCode indicates status of the request
         */
        public Builder(@ResultCode int resultCode) {
            mResultCode = resultCode;
        }

        /**
         * Sets the metadata list on the result.
         */
        @NonNull
        public Builder setMetadataList(@NonNull List<SettingsPreferenceMetadata> metadataList) {
            mMetadataList = metadataList;
            return this;
        }

        /**
         * Constructs an immutable {@link MetadataResult} object.
         */
        @NonNull
        public MetadataResult build() {
            return new MetadataResult(this);
        }
    }
}

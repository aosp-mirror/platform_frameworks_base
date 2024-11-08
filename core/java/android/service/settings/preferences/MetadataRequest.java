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

import androidx.annotation.NonNull;

import com.android.settingslib.flags.Flags;

/**
 * Request parameters to retrieve all metadata for all available settings preferences within this
 * application.
 *
 * <p>This object passed to {@link SettingsPreferenceService#onGetAllPreferenceMetadata} will result
 * in a {@link MetadataResult}.
 */
@FlaggedApi(Flags.FLAG_SETTINGS_CATALYST)
public final class MetadataRequest implements Parcelable {
    private MetadataRequest() {}

    /** @hide */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
    }

    /** @hide */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Parcelable Creator for {@link MetadataRequest}.
     */
    @NonNull
    public static final Creator<MetadataRequest> CREATOR = new Creator<>() {
        @Override
        public MetadataRequest createFromParcel(@NonNull Parcel in) {
            return new MetadataRequest();
        }

        @Override
        public MetadataRequest[] newArray(int size) {
            return new MetadataRequest[size];
        }
    };

    /**
     * Builder to construct {@link MetadataRequest}.
     */
    public static final class Builder {
        /** Constructs an immutable {@link MetadataRequest} object. */
        @NonNull
        public MetadataRequest build() {
            return new MetadataRequest();
        }
    }
}

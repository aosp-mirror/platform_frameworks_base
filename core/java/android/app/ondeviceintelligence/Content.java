/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.app.ondeviceintelligence;

import static android.app.ondeviceintelligence.flags.Flags.FLAG_ENABLE_ON_DEVICE_INTELLIGENCE;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Represents content sent to and received from the on-device inference service.
 * Can contain a collection of text, image, and binary parts or any combination of these.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(FLAG_ENABLE_ON_DEVICE_INTELLIGENCE)
public final class Content implements Parcelable {
    //TODO: Improve javadoc after adding validation logic.
    private static final String TAG = "Content";
    private final Bundle mData;

    /**
     * Create a content object using a Bundle of only known types that are read-only.
     */
    public Content(@NonNull Bundle data) {
        Objects.requireNonNull(data);
        validateBundleData(data);
        this.mData = data;
    }

    /**
     * Returns the Content's data represented as a Bundle.
     */
    @NonNull
    public Bundle getData() {
        return mData;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeBundle(mData);
    }

    @Override
    public int describeContents() {
        int mask = 0;
        mask |= mData.describeContents();
        return mask;
    }

    @NonNull
    public static final Creator<Content> CREATOR = new Creator<>() {
        @Override
        @NonNull
        public Content createFromParcel(@NonNull Parcel in) {
            return new Content(in.readBundle(getClass().getClassLoader()));
        }

        @Override
        @NonNull
        public Content[] newArray(int size) {
            return new Content[size];
        }
    };

    private void validateBundleData(Bundle unused) {
        // TODO: Validate there are only known types.
    }
}

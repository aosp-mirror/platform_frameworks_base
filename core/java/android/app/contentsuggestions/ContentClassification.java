/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.app.contentsuggestions;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Represents the classification of a content suggestion. The result of a
 * {@link ClassificationsRequest} to {@link ContentSuggestionsManager}.
 *
 * @hide
 */
@SystemApi
public final class ContentClassification implements Parcelable {
    @NonNull
    private final String mClassificationId;
    @NonNull
    private final Bundle mExtras;

    /**
     * Default constructor.
     *
     * @param classificationId implementation specific id for the selection /  classification.
     * @param extras containing the classification data.
     */
    public ContentClassification(@NonNull String classificationId, @NonNull Bundle extras) {
        mClassificationId = classificationId;
        mExtras = extras;
    }

    /**
     * Return the classification id.
     */
    public @NonNull String getId() {
        return mClassificationId;
    }

    /**
     * Return the classification extras.
     */
    public @NonNull Bundle getExtras() {
        return mExtras;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mClassificationId);
        dest.writeBundle(mExtras);
    }

    public static final Creator<ContentClassification> CREATOR =
            new Creator<ContentClassification>() {
        @Override
        public ContentClassification createFromParcel(Parcel source) {
            return new ContentClassification(
                    source.readString(),
                    source.readBundle());
        }

        @Override
        public ContentClassification[] newArray(int size) {
            return new ContentClassification[size];
        }
    };
}

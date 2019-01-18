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
 * Represents a suggested selection within a set of on screen content. The result of a
 * {@link SelectionsRequest} to {@link ContentSuggestionsManager}.
 *
 * @hide
 */
@SystemApi
public final class ContentSelection implements Parcelable {
    @NonNull
    private final String mSelectionId;
    @NonNull
    private final Bundle mExtras;

    /**
     * Default constructor.
     *
     * @param selectionId implementation specific id for the selection.
     * @param extras containing the data that represents the selection.
     */
    public ContentSelection(@NonNull String selectionId, @NonNull Bundle extras) {
        mSelectionId = selectionId;
        mExtras = extras;
    }

    /**
     * Return the selection id.
     */
    public @NonNull String getId() {
        return mSelectionId;
    }

    /**
     * Return the selection extras.
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
        dest.writeString(mSelectionId);
        dest.writeBundle(mExtras);
    }

    public static final Creator<ContentSelection> CREATOR =
            new Creator<ContentSelection>() {
        @Override
        public ContentSelection createFromParcel(Parcel source) {
            return new ContentSelection(
                    source.readString(),
                    source.readBundle());
        }

        @Override
        public ContentSelection[] newArray(int size) {
            return new ContentSelection[size];
        }
    };
}

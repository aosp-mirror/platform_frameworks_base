/*
 * Copyright (C) 2020 The Android Open Source Project
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
package android.app.search;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * @hide
 */
@SystemApi
public final class SearchContext implements Parcelable {

    /**
     * Result types the client UI is expecting the service to return.
     */
    private final int mResultTypes;

    /**
     * Timeout constraint imposed from the client UI for the first search result.
     */
    private final int mTimeoutMillis;

    /**
     * Send other client UI configurations in extras.
     */
    @Nullable
    private final Bundle mExtras;

    /**
     * Package name of the client.
     */
    @Nullable
    private String mPackageName;

    public SearchContext(int resultTypes,
            int queryTimeoutMillis,
            @Nullable Bundle extras) {
        mResultTypes = resultTypes;
        mTimeoutMillis = queryTimeoutMillis;
        mExtras = extras;
    }

    private SearchContext(Parcel parcel) {
        mResultTypes = parcel.readInt();
        mTimeoutMillis = parcel.readInt();
        mPackageName = parcel.readString();
        mExtras = parcel.readBundle();
    }

    @Nullable
    public String getPackageName() {
        return mPackageName;
    }

    /**
     * @hide
     */
    public void setPackageName(@Nullable String packageName) {
        mPackageName = packageName;
    }

    @NonNull
    public int getTimeoutMillis() {
        return mTimeoutMillis;
    }

    @Nullable
    public Bundle getExtras() {
        return mExtras;
    }

    @NonNull
    public int getResultTypes() {
        return mResultTypes;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mResultTypes);
        dest.writeInt(mTimeoutMillis);
        dest.writeString(mPackageName);
        dest.writeBundle(mExtras);
    }

    /**
     * @see Parcelable.Creator
     */
    @NonNull
    public static final Parcelable.Creator<SearchContext> CREATOR =
            new Parcelable.Creator<SearchContext>() {
                public SearchContext createFromParcel(Parcel parcel) {
                    return new SearchContext(parcel);
                }

                public SearchContext[] newArray(int size) {
                    return new SearchContext[size];
                }
            };
}

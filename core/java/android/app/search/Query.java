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
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * @hide
 */
@SystemApi
public final class Query implements Parcelable {

    /**
     * Query string typed from the client.
     */
    @NonNull
    private final String mInput;

    /**
     * The timestamp that the query string was typed. If this object was created for the
     * {@link SearchSession#query}, the client expects first consumer to be returned
     * within mTimestamp + {@link SearchContext#mTimeoutMillis}
     */
    private final long mTimestamp;

    @Nullable
    private final Bundle mExtras;

    public Query(@NonNull String input,
            long timestamp,
            @SuppressLint("NullableCollection")
            @Nullable Bundle extras) {
        mInput = input;
        mTimestamp = timestamp;
        mExtras = extras;
    }

    private Query(Parcel parcel) {
        mInput = parcel.readString();
        mTimestamp = parcel.readLong();
        mExtras = parcel.readBundle();
    }

    @NonNull
    public String getInput() {
        return mInput;
    }

    @NonNull
    public long getTimestamp() {
        return mTimestamp;
    }

    @Nullable
    @SuppressLint("NullableCollection")
    public Bundle getExtras() {
        return mExtras;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mInput);
        dest.writeLong(mTimestamp);
        dest.writeBundle(mExtras);
    }

    /**
     * @see Creator
     */
    @NonNull
    public static final Creator<Query> CREATOR =
            new Creator<Query>() {
                public Query createFromParcel(Parcel parcel) {
                    return new Query(parcel);
                }

                public Query[] newArray(int size) {
                    return new Query[size];
                }
            };
}

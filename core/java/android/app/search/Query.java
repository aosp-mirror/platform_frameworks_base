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
import android.annotation.SystemApi;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Query object is sent over from client to the service.
 *
 * Inside the query object, there is a timestamp that trackes when the query string was typed.
 *
 * If this object was created for the {@link SearchSession#query},
 * the client expects first consumer to be returned
 * within {@link #getTimestampMillis()} + {@link SearchContext#getTimeoutMillis()}
 * Base of the timestamp should be SystemClock.elasedRealTime()
 *
 * @hide
 */
@SystemApi
public final class Query implements Parcelable {

    /**
     * The lookup key for a integer that indicates what the height of the soft keyboard
     * (e.g., IME, also known as Input Method Editor) was on the client window
     * in dp (density-independent pixels). This information is to be used by the consumer
     * of the API in estimating how many search results will be visible above the keyboard.
     */
    public static final String EXTRA_IME_HEIGHT = "android.app.search.extra.IME_HEIGHT";

    /**
     * string typed from the client.
     */
    @NonNull
    private final String mInput;

    private final long mTimestampMillis;

    /**
     * Contains other client UI constraints related data (e.g., {@link #EXTRA_IME_HEIGHT}.
     */
    @NonNull
    private final Bundle mExtras;

    /**
     * Query object used to pass search box input from client to service.
     *
     * @param input string typed from the client
     * @param timestampMillis timestamp that query string was typed.
     * @param extras bundle that contains other client UI constraints data
     */
    public Query(@NonNull String input,
            long timestampMillis,
            @NonNull Bundle extras) {
        mInput = input;
        mTimestampMillis = timestampMillis;
        mExtras = extras != null ? extras : new Bundle();
    }

    /**
     * Query object used to pass search box input from client to service.
     *
     * @param input string typed from the client
     * @param timestampMillis timestamp that query string was typed
     */
    public Query(@NonNull String input, long timestampMillis) {
        this(input, timestampMillis, new Bundle());
    }

    private Query(Parcel parcel) {
        mInput = parcel.readString();
        mTimestampMillis = parcel.readLong();
        mExtras = parcel.readBundle();
    }

    /**
     * @return string typed from the client
     */
    @NonNull
    public String getInput() {
        return mInput;
    }

    /**
     * @deprecated Will be replaced by {@link #getTimestampMillis()} as soon as
     * new SDK is adopted.
     *
     * @removed
     */
    @Deprecated
    @NonNull
    public long getTimestamp() {
        return mTimestampMillis;
    }

    /**
     * Base of the timestamp should be SystemClock.elasedRealTime()
     *
     * @return timestamp that query string was typed
     */
    public long getTimestampMillis() {
        return mTimestampMillis;
    }

    /**
     * @return bundle that contains other client constraints related to the query
     */
    @NonNull
    public Bundle getExtras() {
        if (mExtras == null) {
            return new Bundle();
        }
        return mExtras;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mInput);
        dest.writeLong(mTimestampMillis);
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

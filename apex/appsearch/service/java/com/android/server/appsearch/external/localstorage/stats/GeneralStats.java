/*
 * Copyright 2021 The Android Open Source Project
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

package com.android.server.appsearch.external.localstorage.stats;

import android.annotation.NonNull;
import android.app.appsearch.AppSearchResult;

import com.android.internal.util.Preconditions;

/**
 * A class for holding general logging information.
 *
 * <p>This class cannot be logged by {@link
 * com.android.server.appsearch.external.localstorage.AppSearchLogger} directly. It is used for
 * defining general logging information that is shared across different stats classes.
 *
 * @see PutDocumentStats
 * @see CallStats
 * @hide
 */
public final class GeneralStats {
    /** Package name of the application. */
    @NonNull private final String mPackageName;

    /** Database name within AppSearch. */
    @NonNull private final String mDatabase;

    /**
     * The status code returned by {@link AppSearchResult#getResultCode()} for the call or internal
     * state.
     */
    @AppSearchResult.ResultCode private final int mStatusCode;

    private final int mTotalLatencyMillis;

    GeneralStats(@NonNull Builder builder) {
        Preconditions.checkNotNull(builder);
        mPackageName = Preconditions.checkNotNull(builder.mPackageName);
        mDatabase = Preconditions.checkNotNull(builder.mDatabase);
        mStatusCode = builder.mStatusCode;
        mTotalLatencyMillis = builder.mTotalLatencyMillis;
    }

    /** Returns package name. */
    @NonNull
    public String getPackageName() {
        return mPackageName;
    }

    /** Returns database name. */
    @NonNull
    public String getDatabase() {
        return mDatabase;
    }

    /** Returns result code from {@link AppSearchResult#getResultCode()} */
    @AppSearchResult.ResultCode
    public int getStatusCode() {
        return mStatusCode;
    }

    /** Returns total latency, in milliseconds. */
    public int getTotalLatencyMillis() {
        return mTotalLatencyMillis;
    }

    /** Builder for {@link GeneralStats}. */
    public static class Builder {
        @NonNull final String mPackageName;
        @NonNull final String mDatabase;
        @AppSearchResult.ResultCode int mStatusCode = AppSearchResult.RESULT_UNKNOWN_ERROR;
        int mTotalLatencyMillis;

        /**
         * Constructor
         *
         * @param packageName name of the package logging stats
         * @param database name of the database logging stats
         */
        public Builder(@NonNull String packageName, @NonNull String database) {
            mPackageName = Preconditions.checkNotNull(packageName);
            mDatabase = Preconditions.checkNotNull(database);
        }

        /** Sets status code returned from {@link AppSearchResult#getResultCode()} */
        @NonNull
        public Builder setStatusCode(@AppSearchResult.ResultCode int statusCode) {
            mStatusCode = statusCode;
            return this;
        }

        /** Sets total latency, in milliseconds. */
        @NonNull
        public Builder setTotalLatencyMillis(int totalLatencyMillis) {
            mTotalLatencyMillis = totalLatencyMillis;
            return this;
        }

        /**
         * Creates a new {@link GeneralStats} object from the contents of this {@link Builder}
         * instance.
         */
        @NonNull
        public GeneralStats build() {
            return new GeneralStats(/* builder= */ this);
        }
    }
}

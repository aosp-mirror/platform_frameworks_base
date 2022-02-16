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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.RemoveByDocumentIdRequest;
import android.app.appsearch.SearchSpec;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Class holds detailed stats for {@link
 * android.app.appsearch.AppSearchSession#remove(RemoveByDocumentIdRequest)} and {@link
 * android.app.appsearch.AppSearchSession#remove(String, SearchSpec)}
 *
 * @hide
 */
public final class RemoveStats {
    @IntDef(
            value = {
                // It needs to be sync with DeleteType.Code in
                // external/icing/proto/icing/proto/logging.proto#DeleteStatsProto
                UNKNOWN,
                SINGLE,
                QUERY,
                NAMESPACE,
                SCHEMA_TYPE,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DeleteType {}

    /** Default. Should never be used. */
    public static final int UNKNOWN = 0;
    /** Delete by namespace + id. */
    public static final int SINGLE = 1;
    /** Delete by query. */
    public static final int QUERY = 2;
    /** Delete by namespace. */
    public static final int NAMESPACE = 3;
    /** Delete by schema type. */
    public static final int SCHEMA_TYPE = 4;

    @NonNull private final String mPackageName;
    @NonNull private final String mDatabase;
    /**
     * The status code returned by {@link AppSearchResult#getResultCode()} for the call or internal
     * state.
     */
    @AppSearchResult.ResultCode private final int mStatusCode;

    private final int mTotalLatencyMillis;
    private final int mNativeLatencyMillis;
    @DeleteType private final int mNativeDeleteType;
    private final int mNativeNumDocumentsDeleted;

    RemoveStats(@NonNull Builder builder) {
        Objects.requireNonNull(builder);
        mPackageName = builder.mPackageName;
        mDatabase = builder.mDatabase;
        mStatusCode = builder.mStatusCode;
        mTotalLatencyMillis = builder.mTotalLatencyMillis;
        mNativeLatencyMillis = builder.mNativeLatencyMillis;
        mNativeDeleteType = builder.mNativeDeleteType;
        mNativeNumDocumentsDeleted = builder.mNativeNumDocumentsDeleted;
    }

    /** Returns calling package name. */
    @NonNull
    public String getPackageName() {
        return mPackageName;
    }

    /** Returns calling database name. */
    @NonNull
    public String getDatabase() {
        return mDatabase;
    }

    /** Returns status code for this remove. */
    @AppSearchResult.ResultCode
    public int getStatusCode() {
        return mStatusCode;
    }

    /** Returns total latency of this remove in millis. */
    public int getTotalLatencyMillis() {
        return mTotalLatencyMillis;
    }

    /** Returns how much time in millis spent in the native code. */
    public int getNativeLatencyMillis() {
        return mNativeLatencyMillis;
    }

    /** Returns what type of delete for this remove call. */
    @DeleteType
    public int getDeleteType() {
        return mNativeDeleteType;
    }

    /** Returns how many documents get deleted in this call. */
    public int getDeletedDocumentCount() {
        return mNativeNumDocumentsDeleted;
    }

    /** Builder for {@link RemoveStats}. */
    public static class Builder {
        @NonNull final String mPackageName;
        @NonNull final String mDatabase;
        @AppSearchResult.ResultCode int mStatusCode;
        int mTotalLatencyMillis;
        int mNativeLatencyMillis;
        @DeleteType int mNativeDeleteType;
        int mNativeNumDocumentsDeleted;

        /** Constructor for the {@link Builder}. */
        public Builder(@NonNull String packageName, @NonNull String database) {
            mPackageName = Objects.requireNonNull(packageName);
            mDatabase = Objects.requireNonNull(database);
        }

        /** Sets the status code. */
        @NonNull
        public Builder setStatusCode(@AppSearchResult.ResultCode int statusCode) {
            mStatusCode = statusCode;
            return this;
        }

        /** Sets total latency in millis. */
        @NonNull
        public Builder setTotalLatencyMillis(int totalLatencyMillis) {
            mTotalLatencyMillis = totalLatencyMillis;
            return this;
        }

        /** Sets native latency in millis. */
        @NonNull
        public Builder setNativeLatencyMillis(int nativeLatencyMillis) {
            mNativeLatencyMillis = nativeLatencyMillis;
            return this;
        }

        /** Sets delete type for this call. */
        @NonNull
        public Builder setDeleteType(@DeleteType int nativeDeleteType) {
            mNativeDeleteType = nativeDeleteType;
            return this;
        }

        /** Sets how many documents get deleted for this call. */
        @NonNull
        public Builder setDeletedDocumentCount(int nativeNumDocumentsDeleted) {
            mNativeNumDocumentsDeleted = nativeNumDocumentsDeleted;
            return this;
        }

        /** Creates a {@link RemoveStats}. */
        @NonNull
        public RemoveStats build() {
            return new RemoveStats(/* builder= */ this);
        }
    }
}

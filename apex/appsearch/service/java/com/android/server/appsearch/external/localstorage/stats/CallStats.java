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
import android.annotation.Nullable;
import android.app.appsearch.AppSearchResult;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * A class for setting basic information to log for all function calls.
 *
 * <p>This class can set which stats to log for both batch and non-batch {@link
 * android.app.appsearch.AppSearchSession} calls.
 *
 * <p>Some function calls may have their own detailed stats class like {@link PutDocumentStats}.
 * However, {@link CallStats} can still be used along with the detailed stats class for easy
 * aggregation/analysis with other function calls.
 *
 * @hide
 */
public class CallStats {
    @IntDef(
            value = {
                CALL_TYPE_UNKNOWN,
                CALL_TYPE_INITIALIZE,
                CALL_TYPE_SET_SCHEMA,
                CALL_TYPE_PUT_DOCUMENTS,
                CALL_TYPE_GET_DOCUMENTS,
                CALL_TYPE_REMOVE_DOCUMENTS_BY_ID,
                CALL_TYPE_PUT_DOCUMENT,
                CALL_TYPE_GET_DOCUMENT,
                CALL_TYPE_REMOVE_DOCUMENT_BY_ID,
                CALL_TYPE_SEARCH,
                CALL_TYPE_OPTIMIZE,
                CALL_TYPE_FLUSH,
                CALL_TYPE_GLOBAL_SEARCH,
                CALL_TYPE_REMOVE_DOCUMENTS_BY_SEARCH,
                CALL_TYPE_REMOVE_DOCUMENT_BY_SEARCH,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CallType {}

    public static final int CALL_TYPE_UNKNOWN = 0;
    public static final int CALL_TYPE_INITIALIZE = 1;
    public static final int CALL_TYPE_SET_SCHEMA = 2;
    public static final int CALL_TYPE_PUT_DOCUMENTS = 3;
    public static final int CALL_TYPE_GET_DOCUMENTS = 4;
    public static final int CALL_TYPE_REMOVE_DOCUMENTS_BY_ID = 5;
    public static final int CALL_TYPE_PUT_DOCUMENT = 6;
    public static final int CALL_TYPE_GET_DOCUMENT = 7;
    public static final int CALL_TYPE_REMOVE_DOCUMENT_BY_ID = 8;
    public static final int CALL_TYPE_SEARCH = 9;
    public static final int CALL_TYPE_OPTIMIZE = 10;
    public static final int CALL_TYPE_FLUSH = 11;
    public static final int CALL_TYPE_GLOBAL_SEARCH = 12;
    public static final int CALL_TYPE_REMOVE_DOCUMENTS_BY_SEARCH = 13;
    public static final int CALL_TYPE_REMOVE_DOCUMENT_BY_SEARCH = 14;

    @Nullable private final String mPackageName;
    @Nullable private final String mDatabase;
    /**
     * The status code returned by {@link AppSearchResult#getResultCode()} for the call or internal
     * state.
     */
    @AppSearchResult.ResultCode private final int mStatusCode;

    private final int mTotalLatencyMillis;

    @CallType private final int mCallType;
    private final int mEstimatedBinderLatencyMillis;
    private final int mNumOperationsSucceeded;
    private final int mNumOperationsFailed;

    CallStats(@NonNull Builder builder) {
        Objects.requireNonNull(builder);
        mPackageName = builder.mPackageName;
        mDatabase = builder.mDatabase;
        mStatusCode = builder.mStatusCode;
        mTotalLatencyMillis = builder.mTotalLatencyMillis;
        mCallType = builder.mCallType;
        mEstimatedBinderLatencyMillis = builder.mEstimatedBinderLatencyMillis;
        mNumOperationsSucceeded = builder.mNumOperationsSucceeded;
        mNumOperationsFailed = builder.mNumOperationsFailed;
    }

    /** Returns calling package name. */
    @Nullable
    public String getPackageName() {
        return mPackageName;
    }

    /** Returns calling database name. */
    @Nullable
    public String getDatabase() {
        return mDatabase;
    }

    /** Returns status code for this api call. */
    @AppSearchResult.ResultCode
    public int getStatusCode() {
        return mStatusCode;
    }

    /** Returns total latency of this api call in millis. */
    public int getTotalLatencyMillis() {
        return mTotalLatencyMillis;
    }

    /** Returns type of the call. */
    @CallType
    public int getCallType() {
        return mCallType;
    }

    /** Returns estimated binder latency, in milliseconds */
    public int getEstimatedBinderLatencyMillis() {
        return mEstimatedBinderLatencyMillis;
    }

    /**
     * Returns number of operations succeeded.
     *
     * <p>For example, for {@link android.app.appsearch.AppSearchSession#put}, it is the total
     * number of individual successful put operations. In this case, how many documents are
     * successfully indexed.
     *
     * <p>For non-batch calls such as {@link android.app.appsearch.AppSearchSession#setSchema}, the
     * sum of {@link CallStats#getNumOperationsSucceeded()} and {@link
     * CallStats#getNumOperationsFailed()} is always 1 since there is only one operation.
     */
    public int getNumOperationsSucceeded() {
        return mNumOperationsSucceeded;
    }

    /**
     * Returns number of operations failed.
     *
     * <p>For example, for {@link android.app.appsearch.AppSearchSession#put}, it is the total
     * number of individual failed put operations. In this case, how many documents are failed to be
     * indexed.
     *
     * <p>For non-batch calls such as {@link android.app.appsearch.AppSearchSession#setSchema}, the
     * sum of {@link CallStats#getNumOperationsSucceeded()} and {@link
     * CallStats#getNumOperationsFailed()} is always 1 since there is only one operation.
     */
    public int getNumOperationsFailed() {
        return mNumOperationsFailed;
    }

    /** Builder for {@link CallStats}. */
    public static class Builder {
        @Nullable String mPackageName;
        @Nullable String mDatabase;
        @AppSearchResult.ResultCode int mStatusCode;
        int mTotalLatencyMillis;
        @CallType int mCallType;
        int mEstimatedBinderLatencyMillis;
        int mNumOperationsSucceeded;
        int mNumOperationsFailed;

        /** Sets the PackageName used by the session. */
        @NonNull
        public Builder setPackageName(@NonNull String packageName) {
            mPackageName = Objects.requireNonNull(packageName);
            return this;
        }

        /** Sets the database used by the session. */
        @NonNull
        public Builder setDatabase(@NonNull String database) {
            mDatabase = Objects.requireNonNull(database);
            return this;
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

        /** Sets type of the call. */
        @NonNull
        public Builder setCallType(@CallType int callType) {
            mCallType = callType;
            return this;
        }

        /** Sets estimated binder latency, in milliseconds. */
        @NonNull
        public Builder setEstimatedBinderLatencyMillis(int estimatedBinderLatencyMillis) {
            mEstimatedBinderLatencyMillis = estimatedBinderLatencyMillis;
            return this;
        }

        /**
         * Sets number of operations succeeded.
         *
         * <p>For example, for {@link android.app.appsearch.AppSearchSession#put}, it is the total
         * number of individual successful put operations. In this case, how many documents are
         * successfully indexed.
         *
         * <p>For non-batch calls such as {@link android.app.appsearch.AppSearchSession#setSchema},
         * the sum of {@link CallStats#getNumOperationsSucceeded()} and {@link
         * CallStats#getNumOperationsFailed()} is always 1 since there is only one operation.
         */
        @NonNull
        public Builder setNumOperationsSucceeded(int numOperationsSucceeded) {
            mNumOperationsSucceeded = numOperationsSucceeded;
            return this;
        }

        /**
         * Sets number of operations failed.
         *
         * <p>For example, for {@link android.app.appsearch.AppSearchSession#put}, it is the total
         * number of individual failed put operations. In this case, how many documents are failed
         * to be indexed.
         *
         * <p>For non-batch calls such as {@link android.app.appsearch.AppSearchSession#setSchema},
         * the sum of {@link CallStats#getNumOperationsSucceeded()} and {@link
         * CallStats#getNumOperationsFailed()} is always 1 since there is only one operation.
         */
        @NonNull
        public Builder setNumOperationsFailed(int numOperationsFailed) {
            mNumOperationsFailed = numOperationsFailed;
            return this;
        }

        /** Creates {@link CallStats} object from {@link Builder} instance. */
        @NonNull
        public CallStats build() {
            return new CallStats(/* builder= */ this);
        }
    }
}

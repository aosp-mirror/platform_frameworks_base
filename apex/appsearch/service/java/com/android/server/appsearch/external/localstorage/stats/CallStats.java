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

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A class for setting basic information to log for all function calls.
 *
 * <p>This class can set which stats to log for both batch and non-batch {@link
 * android.app.appsearch.AppSearchSession} calls.
 *
 * <p>Some function calls like {@link android.app.appsearch.AppSearchSession#setSchema} have their
 * own detailed stats class {@link placeholder}. However, {@link CallStats} can still be used along
 * with the detailed stats class for easy aggregation/analysis with other function calls.
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
                CALL_TYPE_REMOVE_DOCUMENTS,
                CALL_TYPE_PUT_DOCUMENT,
                CALL_TYPE_GET_DOCUMENT,
                CALL_TYPE_REMOVE_DOCUMENT,
                CALL_TYPE_QUERY,
                CALL_TYPE_OPTIMIZE,
                CALL_TYPE_FLUSH,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CallType {}

    public static final int CALL_TYPE_UNKNOWN = 0;
    public static final int CALL_TYPE_INITIALIZE = 1;
    public static final int CALL_TYPE_SET_SCHEMA = 2;
    public static final int CALL_TYPE_PUT_DOCUMENTS = 3;
    public static final int CALL_TYPE_GET_DOCUMENTS = 4;
    public static final int CALL_TYPE_REMOVE_DOCUMENTS = 5;
    public static final int CALL_TYPE_PUT_DOCUMENT = 6;
    public static final int CALL_TYPE_GET_DOCUMENT = 7;
    public static final int CALL_TYPE_REMOVE_DOCUMENT = 8;
    public static final int CALL_TYPE_QUERY = 9;
    public static final int CALL_TYPE_OPTIMIZE = 10;
    public static final int CALL_TYPE_FLUSH = 11;

    @NonNull private final GeneralStats mGeneralStats;
    @CallType private final int mCallType;
    private final int mEstimatedBinderLatencyMillis;
    private final int mNumOperationsSucceeded;
    private final int mNumOperationsFailed;

    CallStats(@NonNull Builder builder) {
        Preconditions.checkNotNull(builder);
        mGeneralStats = Preconditions.checkNotNull(builder.mGeneralStatsBuilder).build();
        mCallType = builder.mCallType;
        mEstimatedBinderLatencyMillis = builder.mEstimatedBinderLatencyMillis;
        mNumOperationsSucceeded = builder.mNumOperationsSucceeded;
        mNumOperationsFailed = builder.mNumOperationsFailed;
    }

    /** Returns general information for the call. */
    @NonNull
    public GeneralStats getGeneralStats() {
        return mGeneralStats;
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
        @NonNull final GeneralStats.Builder mGeneralStatsBuilder;
        @CallType int mCallType;
        int mEstimatedBinderLatencyMillis;
        int mNumOperationsSucceeded;
        int mNumOperationsFailed;

        /** Builder takes {@link GeneralStats.Builder}. */
        public Builder(@NonNull String packageName, @NonNull String database) {
            Preconditions.checkNotNull(packageName);
            Preconditions.checkNotNull(database);
            mGeneralStatsBuilder = new GeneralStats.Builder(packageName, database);
        }

        /** Returns {@link GeneralStats.Builder}. */
        @NonNull
        public GeneralStats.Builder getGeneralStatsBuilder() {
            return mGeneralStatsBuilder;
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

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

import java.util.Objects;

/**
 * A class for holding detailed stats to log for each individual document put by a {@link
 * android.app.appsearch.AppSearchSession#put} call.
 *
 * @hide
 */
public final class PutDocumentStats {
    @NonNull private final String mPackageName;
    @NonNull private final String mDatabase;
    /**
     * The status code returned by {@link AppSearchResult#getResultCode()} for the call or internal
     * state.
     */
    @AppSearchResult.ResultCode private final int mStatusCode;

    private final int mTotalLatencyMillis;

    /** Time used to generate a document proto from a Bundle. */
    private final int mGenerateDocumentProtoLatencyMillis;

    /** Time used to rewrite types and namespaces in the document. */
    private final int mRewriteDocumentTypesLatencyMillis;

    /** Overall time used for the native function call. */
    private final int mNativeLatencyMillis;

    /** Time used to store the document. */
    private final int mNativeDocumentStoreLatencyMillis;

    /** Time used to index the document. It doesn't include the time to merge indices. */
    private final int mNativeIndexLatencyMillis;

    /** Time used to merge the indices. */
    private final int mNativeIndexMergeLatencyMillis;

    /** Document size in bytes. */
    private final int mNativeDocumentSizeBytes;

    /** Number of tokens added to the index. */
    private final int mNativeNumTokensIndexed;

    /**
     * Whether the number of tokens to be indexed exceeded the max number of tokens per document.
     */
    private final boolean mNativeExceededMaxNumTokens;

    PutDocumentStats(@NonNull Builder builder) {
        Objects.requireNonNull(builder);
        mPackageName = builder.mPackageName;
        mDatabase = builder.mDatabase;
        mStatusCode = builder.mStatusCode;
        mTotalLatencyMillis = builder.mTotalLatencyMillis;
        mGenerateDocumentProtoLatencyMillis = builder.mGenerateDocumentProtoLatencyMillis;
        mRewriteDocumentTypesLatencyMillis = builder.mRewriteDocumentTypesLatencyMillis;
        mNativeLatencyMillis = builder.mNativeLatencyMillis;
        mNativeDocumentStoreLatencyMillis = builder.mNativeDocumentStoreLatencyMillis;
        mNativeIndexLatencyMillis = builder.mNativeIndexLatencyMillis;
        mNativeIndexMergeLatencyMillis = builder.mNativeIndexMergeLatencyMillis;
        mNativeDocumentSizeBytes = builder.mNativeDocumentSizeBytes;
        mNativeNumTokensIndexed = builder.mNativeNumTokensIndexed;
        mNativeExceededMaxNumTokens = builder.mNativeExceededMaxNumTokens;
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

    /** Returns status code for this putDocument. */
    @AppSearchResult.ResultCode
    public int getStatusCode() {
        return mStatusCode;
    }

    /** Returns total latency of this putDocument in millis. */
    public int getTotalLatencyMillis() {
        return mTotalLatencyMillis;
    }

    /** Returns time spent on generating document proto, in milliseconds. */
    public int getGenerateDocumentProtoLatencyMillis() {
        return mGenerateDocumentProtoLatencyMillis;
    }

    /** Returns time spent on rewriting types and namespaces in document, in milliseconds. */
    public int getRewriteDocumentTypesLatencyMillis() {
        return mRewriteDocumentTypesLatencyMillis;
    }

    /** Returns time spent in native, in milliseconds. */
    public int getNativeLatencyMillis() {
        return mNativeLatencyMillis;
    }

    /** Returns time spent on document store, in milliseconds. */
    public int getNativeDocumentStoreLatencyMillis() {
        return mNativeDocumentStoreLatencyMillis;
    }

    /** Returns time spent on indexing, in milliseconds. */
    public int getNativeIndexLatencyMillis() {
        return mNativeIndexLatencyMillis;
    }

    /** Returns time spent on merging indices, in milliseconds. */
    public int getNativeIndexMergeLatencyMillis() {
        return mNativeIndexMergeLatencyMillis;
    }

    /** Returns document size, in bytes. */
    public int getNativeDocumentSizeBytes() {
        return mNativeDocumentSizeBytes;
    }

    /** Returns number of tokens indexed. */
    public int getNativeNumTokensIndexed() {
        return mNativeNumTokensIndexed;
    }

    /**
     * Returns whether the number of tokens to be indexed exceeded the max number of tokens per
     * document.
     */
    public boolean getNativeExceededMaxNumTokens() {
        return mNativeExceededMaxNumTokens;
    }

    /** Builder for {@link PutDocumentStats}. */
    public static class Builder {
        @NonNull final String mPackageName;
        @NonNull final String mDatabase;
        @AppSearchResult.ResultCode int mStatusCode;
        int mTotalLatencyMillis;
        int mGenerateDocumentProtoLatencyMillis;
        int mRewriteDocumentTypesLatencyMillis;
        int mNativeLatencyMillis;
        int mNativeDocumentStoreLatencyMillis;
        int mNativeIndexLatencyMillis;
        int mNativeIndexMergeLatencyMillis;
        int mNativeDocumentSizeBytes;
        int mNativeNumTokensIndexed;
        boolean mNativeExceededMaxNumTokens;

        /** Builder for {@link PutDocumentStats} */
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

        /** Sets how much time we spend for generating document proto, in milliseconds. */
        @NonNull
        public Builder setGenerateDocumentProtoLatencyMillis(
                int generateDocumentProtoLatencyMillis) {
            mGenerateDocumentProtoLatencyMillis = generateDocumentProtoLatencyMillis;
            return this;
        }

        /**
         * Sets how much time we spend for rewriting types and namespaces in document, in
         * milliseconds.
         */
        @NonNull
        public Builder setRewriteDocumentTypesLatencyMillis(int rewriteDocumentTypesLatencyMillis) {
            mRewriteDocumentTypesLatencyMillis = rewriteDocumentTypesLatencyMillis;
            return this;
        }

        /** Sets the native latency, in milliseconds. */
        @NonNull
        public Builder setNativeLatencyMillis(int nativeLatencyMillis) {
            mNativeLatencyMillis = nativeLatencyMillis;
            return this;
        }

        /** Sets how much time we spend on document store, in milliseconds. */
        @NonNull
        public Builder setNativeDocumentStoreLatencyMillis(int nativeDocumentStoreLatencyMillis) {
            mNativeDocumentStoreLatencyMillis = nativeDocumentStoreLatencyMillis;
            return this;
        }

        /** Sets the native index latency, in milliseconds. */
        @NonNull
        public Builder setNativeIndexLatencyMillis(int nativeIndexLatencyMillis) {
            mNativeIndexLatencyMillis = nativeIndexLatencyMillis;
            return this;
        }

        /** Sets how much time we spend on merging indices, in milliseconds. */
        @NonNull
        public Builder setNativeIndexMergeLatencyMillis(int nativeIndexMergeLatencyMillis) {
            mNativeIndexMergeLatencyMillis = nativeIndexMergeLatencyMillis;
            return this;
        }

        /** Sets document size, in bytes. */
        @NonNull
        public Builder setNativeDocumentSizeBytes(int nativeDocumentSizeBytes) {
            mNativeDocumentSizeBytes = nativeDocumentSizeBytes;
            return this;
        }

        /** Sets number of tokens indexed in native. */
        @NonNull
        public Builder setNativeNumTokensIndexed(int nativeNumTokensIndexed) {
            mNativeNumTokensIndexed = nativeNumTokensIndexed;
            return this;
        }

        /**
         * Sets whether the number of tokens to be indexed exceeded the max number of tokens per
         * document.
         */
        @NonNull
        public Builder setNativeExceededMaxNumTokens(boolean nativeExceededMaxNumTokens) {
            mNativeExceededMaxNumTokens = nativeExceededMaxNumTokens;
            return this;
        }

        /**
         * Creates a new {@link PutDocumentStats} object from the contents of this {@link Builder}
         * instance.
         */
        @NonNull
        public PutDocumentStats build() {
            return new PutDocumentStats(/* builder= */ this);
        }
    }
}

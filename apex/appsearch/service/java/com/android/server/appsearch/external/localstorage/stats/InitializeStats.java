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

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Class holds detailed stats for initialization
 *
 * @hide
 */
public final class InitializeStats {
    /**
     * The cause of IcingSearchEngine recovering from a previous bad state during initialization.
     */
    @IntDef(
            value = {
                // It needs to be sync with RecoveryCause in
                // external/icing/proto/icing/proto/logging.proto#InitializeStatsProto
                RECOVERY_CAUSE_NONE,
                RECOVERY_CAUSE_DATA_LOSS,
                RECOVERY_CAUSE_INCONSISTENT_WITH_GROUND_TRUTH,
                RECOVERY_CAUSE_TOTAL_CHECKSUM_MISMATCH,
                RECOVERY_CAUSE_IO_ERROR,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RecoveryCause {}

    // No recovery happened.
    public static final int RECOVERY_CAUSE_NONE = 0;
    // Data loss in ground truth.
    public static final int RECOVERY_CAUSE_DATA_LOSS = 1;
    // Data in index is inconsistent with ground truth.
    public static final int RECOVERY_CAUSE_INCONSISTENT_WITH_GROUND_TRUTH = 2;
    // Total checksum of all the components does not match.
    public static final int RECOVERY_CAUSE_TOTAL_CHECKSUM_MISMATCH = 3;
    // Random I/O errors.
    public static final int RECOVERY_CAUSE_IO_ERROR = 4;

    /** Status regarding how much data is lost during the initialization. */
    @IntDef(
            value = {
                // It needs to be sync with DocumentStoreDataStatus in
                // external/icing/proto/icing/proto/logging.proto#InitializeStatsProto

                DOCUMENT_STORE_DATA_STATUS_NO_DATA_LOSS,
                DOCUMENT_STORE_DATA_STATUS_PARTIAL_LOSS,
                DOCUMENT_STORE_DATA_STATUS_COMPLETE_LOSS,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DocumentStoreDataStatus {}

    // Document store is successfully initialized or fully recovered.
    public static final int DOCUMENT_STORE_DATA_STATUS_NO_DATA_LOSS = 0;
    // Ground truth data is partially lost.
    public static final int DOCUMENT_STORE_DATA_STATUS_PARTIAL_LOSS = 1;
    // Ground truth data is completely lost.
    public static final int DOCUMENT_STORE_DATA_STATUS_COMPLETE_LOSS = 2;

    @AppSearchResult.ResultCode private final int mStatusCode;
    private final int mTotalLatencyMillis;
    /** Whether the initialize() detects deSync. */
    private final boolean mHasDeSync;
    /** Time used to read and process the schema and namespaces. */
    private final int mPrepareSchemaAndNamespacesLatencyMillis;
    /** Time used to read and process the visibility store. */
    private final int mPrepareVisibilityStoreLatencyMillis;
    /** Overall time used for the native function call. */
    private final int mNativeLatencyMillis;

    @RecoveryCause private final int mNativeDocumentStoreRecoveryCause;
    @RecoveryCause private final int mNativeIndexRestorationCause;
    @RecoveryCause private final int mNativeSchemaStoreRecoveryCause;
    /** Time used to recover the document store. */
    private final int mNativeDocumentStoreRecoveryLatencyMillis;
    /** Time used to restore the index. */
    private final int mNativeIndexRestorationLatencyMillis;
    /** Time used to recover the schema store. */
    private final int mNativeSchemaStoreRecoveryLatencyMillis;
    /** Status regarding how much data is lost during the initialization. */
    private final int mNativeDocumentStoreDataStatus;
    /**
     * Returns number of documents currently in document store. Those may include alive, deleted,
     * and expired documents.
     */
    private final int mNativeNumDocuments;
    /** Returns number of schema types currently in the schema store. */
    private final int mNativeNumSchemaTypes;
    /** Whether we had to reset the index, losing all data, during initialization. */
    private final boolean mHasReset;
    /** If we had to reset, contains the status code of the reset operation. */
    @AppSearchResult.ResultCode private final int mResetStatusCode;

    /** Returns the status of the initialization. */
    @AppSearchResult.ResultCode
    public int getStatusCode() {
        return mStatusCode;
    }

    /** Returns the total latency in milliseconds for the initialization. */
    public int getTotalLatencyMillis() {
        return mTotalLatencyMillis;
    }

    /**
     * Returns whether the initialize() detects deSync.
     *
     * <p>If there is a deSync, it means AppSearch and IcingSearchEngine have an inconsistent view
     * of what data should exist.
     */
    public boolean hasDeSync() {
        return mHasDeSync;
    }

    /** Returns time used to read and process the schema and namespaces. */
    public int getPrepareSchemaAndNamespacesLatencyMillis() {
        return mPrepareSchemaAndNamespacesLatencyMillis;
    }

    /** Returns time used to read and process the visibility file. */
    public int getPrepareVisibilityStoreLatencyMillis() {
        return mPrepareVisibilityStoreLatencyMillis;
    }

    /** Returns overall time used for the native function call. */
    public int getNativeLatencyMillis() {
        return mNativeLatencyMillis;
    }

    /**
     * Returns recovery cause for document store.
     *
     * <p>Possible recovery causes for document store:
     * <li>{@link InitializeStats#RECOVERY_CAUSE_DATA_LOSS}
     * <li>{@link InitializeStats#RECOVERY_CAUSE_TOTAL_CHECKSUM_MISMATCH}
     * <li>{@link InitializeStats#RECOVERY_CAUSE_IO_ERROR}
     */
    @RecoveryCause
    public int getDocumentStoreRecoveryCause() {
        return mNativeDocumentStoreRecoveryCause;
    }

    /**
     * Returns restoration cause for index store.
     *
     * <p>Possible causes:
     * <li>{@link InitializeStats#RECOVERY_CAUSE_INCONSISTENT_WITH_GROUND_TRUTH}
     * <li>{@link InitializeStats#RECOVERY_CAUSE_TOTAL_CHECKSUM_MISMATCH}
     * <li>{@link InitializeStats#RECOVERY_CAUSE_IO_ERROR}
     */
    @RecoveryCause
    public int getIndexRestorationCause() {
        return mNativeIndexRestorationCause;
    }

    /**
     * Returns recovery cause for schema store.
     *
     * <p>Possible causes:
     * <li>IO_ERROR
     */
    @RecoveryCause
    public int getSchemaStoreRecoveryCause() {
        return mNativeSchemaStoreRecoveryCause;
    }

    /** Returns time used to recover the document store. */
    public int getDocumentStoreRecoveryLatencyMillis() {
        return mNativeDocumentStoreRecoveryLatencyMillis;
    }

    /** Returns time used to restore the index. */
    public int getIndexRestorationLatencyMillis() {
        return mNativeIndexRestorationLatencyMillis;
    }

    /** Returns time used to recover the schema store. */
    public int getSchemaStoreRecoveryLatencyMillis() {
        return mNativeSchemaStoreRecoveryLatencyMillis;
    }

    /** Returns status about how much data is lost during the initialization. */
    @DocumentStoreDataStatus
    public int getDocumentStoreDataStatus() {
        return mNativeDocumentStoreDataStatus;
    }

    /**
     * Returns number of documents currently in document store. Those may include alive, deleted,
     * and expired documents.
     */
    public int getDocumentCount() {
        return mNativeNumDocuments;
    }

    /** Returns number of schema types currently in the schema store. */
    public int getSchemaTypeCount() {
        return mNativeNumSchemaTypes;
    }

    /** Returns whether we had to reset the index, losing all data, as part of initialization. */
    public boolean hasReset() {
        return mHasReset;
    }

    /**
     * Returns the status of the reset, if one was performed according to {@link #hasReset}.
     *
     * <p>If no value has been set, the default value is {@link AppSearchResult#RESULT_OK}.
     */
    @AppSearchResult.ResultCode
    public int getResetStatusCode() {
        return mResetStatusCode;
    }

    InitializeStats(@NonNull Builder builder) {
        Objects.requireNonNull(builder);
        mStatusCode = builder.mStatusCode;
        mTotalLatencyMillis = builder.mTotalLatencyMillis;
        mHasDeSync = builder.mHasDeSync;
        mPrepareSchemaAndNamespacesLatencyMillis = builder.mPrepareSchemaAndNamespacesLatencyMillis;
        mPrepareVisibilityStoreLatencyMillis = builder.mPrepareVisibilityStoreLatencyMillis;
        mNativeLatencyMillis = builder.mNativeLatencyMillis;
        mNativeDocumentStoreRecoveryCause = builder.mNativeDocumentStoreRecoveryCause;
        mNativeIndexRestorationCause = builder.mNativeIndexRestorationCause;
        mNativeSchemaStoreRecoveryCause = builder.mNativeSchemaStoreRecoveryCause;
        mNativeDocumentStoreRecoveryLatencyMillis =
                builder.mNativeDocumentStoreRecoveryLatencyMillis;
        mNativeIndexRestorationLatencyMillis = builder.mNativeIndexRestorationLatencyMillis;
        mNativeSchemaStoreRecoveryLatencyMillis = builder.mNativeSchemaStoreRecoveryLatencyMillis;
        mNativeDocumentStoreDataStatus = builder.mNativeDocumentStoreDataStatus;
        mNativeNumDocuments = builder.mNativeNumDocuments;
        mNativeNumSchemaTypes = builder.mNativeNumSchemaTypes;
        mHasReset = builder.mHasReset;
        mResetStatusCode = builder.mResetStatusCode;
    }

    /** Builder for {@link InitializeStats}. */
    public static class Builder {
        @AppSearchResult.ResultCode int mStatusCode;

        int mTotalLatencyMillis;
        boolean mHasDeSync;
        int mPrepareSchemaAndNamespacesLatencyMillis;
        int mPrepareVisibilityStoreLatencyMillis;
        int mNativeLatencyMillis;
        @RecoveryCause int mNativeDocumentStoreRecoveryCause;
        @RecoveryCause int mNativeIndexRestorationCause;
        @RecoveryCause int mNativeSchemaStoreRecoveryCause;
        int mNativeDocumentStoreRecoveryLatencyMillis;
        int mNativeIndexRestorationLatencyMillis;
        int mNativeSchemaStoreRecoveryLatencyMillis;
        @DocumentStoreDataStatus int mNativeDocumentStoreDataStatus;
        int mNativeNumDocuments;
        int mNativeNumSchemaTypes;
        boolean mHasReset;
        @AppSearchResult.ResultCode int mResetStatusCode;

        /** Sets the status of the initialization. */
        @NonNull
        public Builder setStatusCode(@AppSearchResult.ResultCode int statusCode) {
            mStatusCode = statusCode;
            return this;
        }

        /** Sets the total latency of the initialization in milliseconds. */
        @NonNull
        public Builder setTotalLatencyMillis(int totalLatencyMillis) {
            mTotalLatencyMillis = totalLatencyMillis;
            return this;
        }

        /**
         * Sets whether the initialize() detects deSync.
         *
         * <p>If there is a deSync, it means AppSearch and IcingSearchEngine have an inconsistent
         * view of what data should exist.
         */
        @NonNull
        public Builder setHasDeSync(boolean hasDeSync) {
            mHasDeSync = hasDeSync;
            return this;
        }

        /** Sets time used to read and process the schema and namespaces. */
        @NonNull
        public Builder setPrepareSchemaAndNamespacesLatencyMillis(
                int prepareSchemaAndNamespacesLatencyMillis) {
            mPrepareSchemaAndNamespacesLatencyMillis = prepareSchemaAndNamespacesLatencyMillis;
            return this;
        }

        /** Sets time used to read and process the visibility file. */
        @NonNull
        public Builder setPrepareVisibilityStoreLatencyMillis(
                int prepareVisibilityStoreLatencyMillis) {
            mPrepareVisibilityStoreLatencyMillis = prepareVisibilityStoreLatencyMillis;
            return this;
        }

        /** Sets overall time used for the native function call. */
        @NonNull
        public Builder setNativeLatencyMillis(int nativeLatencyMillis) {
            mNativeLatencyMillis = nativeLatencyMillis;
            return this;
        }

        /**
         * Sets recovery cause for document store.
         *
         * <p>Possible recovery causes for document store:
         * <li>{@link InitializeStats#RECOVERY_CAUSE_DATA_LOSS}
         * <li>{@link InitializeStats#RECOVERY_CAUSE_TOTAL_CHECKSUM_MISMATCH}
         * <li>{@link InitializeStats#RECOVERY_CAUSE_IO_ERROR}
         */
        @NonNull
        public Builder setDocumentStoreRecoveryCause(
                @RecoveryCause int documentStoreRecoveryCause) {
            mNativeDocumentStoreRecoveryCause = documentStoreRecoveryCause;
            return this;
        }

        /**
         * Sets restoration cause for index store.
         *
         * <p>Possible causes:
         * <li>{@link InitializeStats#DOCUMENT_STORE_DATA_STATUS_COMPLETE_LOSS}
         * <li>{@link InitializeStats#RECOVERY_CAUSE_TOTAL_CHECKSUM_MISMATCH}
         * <li>{@link InitializeStats#RECOVERY_CAUSE_IO_ERROR}
         */
        @NonNull
        public Builder setIndexRestorationCause(@RecoveryCause int indexRestorationCause) {
            mNativeIndexRestorationCause = indexRestorationCause;
            return this;
        }

        /**
         * Returns recovery cause for schema store.
         *
         * <p>Possible causes:
         * <li>{@link InitializeStats#RECOVERY_CAUSE_IO_ERROR}
         */
        @NonNull
        public Builder setSchemaStoreRecoveryCause(@RecoveryCause int schemaStoreRecoveryCause) {
            mNativeSchemaStoreRecoveryCause = schemaStoreRecoveryCause;
            return this;
        }

        /** Sets time used to recover the document store. */
        @NonNull
        public Builder setDocumentStoreRecoveryLatencyMillis(
                int documentStoreRecoveryLatencyMillis) {
            mNativeDocumentStoreRecoveryLatencyMillis = documentStoreRecoveryLatencyMillis;
            return this;
        }

        /** Sets time used to restore the index. */
        @NonNull
        public Builder setIndexRestorationLatencyMillis(int indexRestorationLatencyMillis) {
            mNativeIndexRestorationLatencyMillis = indexRestorationLatencyMillis;
            return this;
        }

        /** Sets time used to recover the schema store. */
        @NonNull
        public Builder setSchemaStoreRecoveryLatencyMillis(int schemaStoreRecoveryLatencyMillis) {
            mNativeSchemaStoreRecoveryLatencyMillis = schemaStoreRecoveryLatencyMillis;
            return this;
        }

        /**
         * Sets Native Document Store Data status. status is defined in
         * external/icing/proto/icing/proto/logging.proto
         */
        @NonNull
        public Builder setDocumentStoreDataStatus(
                @DocumentStoreDataStatus int documentStoreDataStatus) {
            mNativeDocumentStoreDataStatus = documentStoreDataStatus;
            return this;
        }

        /**
         * Sets number of documents currently in document store. Those may include alive, deleted,
         * and expired documents.
         */
        @NonNull
        public Builder setDocumentCount(int numDocuments) {
            mNativeNumDocuments = numDocuments;
            return this;
        }

        /** Sets number of schema types currently in the schema store. */
        @NonNull
        public Builder setSchemaTypeCount(int numSchemaTypes) {
            mNativeNumSchemaTypes = numSchemaTypes;
            return this;
        }

        /** Sets whether we had to reset the index, losing all data, as part of initialization. */
        @NonNull
        public Builder setHasReset(boolean hasReset) {
            mHasReset = hasReset;
            return this;
        }

        /** Sets the status of the reset, if one was performed according to {@link #setHasReset}. */
        @NonNull
        public Builder setResetStatusCode(@AppSearchResult.ResultCode int resetStatusCode) {
            mResetStatusCode = resetStatusCode;
            return this;
        }

        /**
         * Constructs a new {@link InitializeStats} from the contents of this {@link
         * InitializeStats.Builder}
         */
        @NonNull
        public InitializeStats build() {
            return new InitializeStats(/* builder= */ this);
        }
    }
}

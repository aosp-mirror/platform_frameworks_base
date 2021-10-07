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
import android.app.appsearch.SetSchemaRequest;

import java.util.Objects;

/**
 * Class holds detailed stats for Schema migration.
 *
 * @hide
 */
// TODO(b/173532925): Hides getter and setter functions for accessing {@code
//  mFirstSetSchemaLatencyMillis} and {@code mSecondSetSchemaLatencyMillis} field.

public final class SchemaMigrationStats {
    /** GetSchema latency in milliseconds. */
    private final int mGetSchemaLatencyMillis;

    /**
     * Latency of querying all documents that need to be migrated to new version and transforming
     * documents to new version in milliseconds.
     */
    private final int mQueryAndTransformLatencyMillis;

    private final int mFirstSetSchemaLatencyMillis;

    private final int mSecondSetSchemaLatencyMillis;

    /** Latency of putting migrated document to Icing lib in milliseconds. */
    private final int mSaveDocumentLatencyMillis;

    private final int mMigratedDocumentCount;

    private final int mSavedDocumentCount;

    SchemaMigrationStats(@NonNull Builder builder) {
        Objects.requireNonNull(builder);
        mGetSchemaLatencyMillis = builder.mGetSchemaLatencyMillis;
        mQueryAndTransformLatencyMillis = builder.mQueryAndTransformLatencyMillis;
        mFirstSetSchemaLatencyMillis = builder.mFirstSetSchemaLatencyMillis;
        mSecondSetSchemaLatencyMillis = builder.mSecondSetSchemaLatencyMillis;
        mSaveDocumentLatencyMillis = builder.mSaveDocumentLatencyMillis;
        mMigratedDocumentCount = builder.mMigratedDocumentCount;
        mSavedDocumentCount = builder.mSavedDocumentCount;
    }

    /** Returns GetSchema latency in milliseconds. */
    public int getGetSchemaLatencyMillis() {
        return mGetSchemaLatencyMillis;
    }

    /**
     * Returns latency of querying all documents that need to be migrated to new version and
     * transforming documents to new version in milliseconds.
     */
    public int getQueryAndTransformLatencyMillis() {
        return mQueryAndTransformLatencyMillis;
    }

    /**
     * Returns latency of first SetSchema action in milliseconds.
     *
     * <p>If all schema fields are backward compatible, the schema will be successful set to Icing.
     * Otherwise, we will retrieve incompatible types here.
     *
     * <p>Please see {@link SetSchemaRequest} for what is "incompatible".
     */
    public int getFirstSetSchemaLatencyMillis() {
        return mFirstSetSchemaLatencyMillis;
    }

    /**
     * Returns latency of second SetSchema action in milliseconds.
     *
     * <p>If all schema fields are backward compatible, the schema will be successful set to Icing
     * in the first setSchema action and this value will be 0. Otherwise, schema types will be set
     * to Icing by this action.
     */
    public int getSecondSetSchemaLatencyMillis() {
        return mSecondSetSchemaLatencyMillis;
    }

    /** Returns latency of putting migrated document to Icing lib in milliseconds. */
    public int getSaveDocumentLatencyMillis() {
        return mSaveDocumentLatencyMillis;
    }

    /** Returns number of migrated documents. */
    public int getMigratedDocumentCount() {
        return mMigratedDocumentCount;
    }

    /** Returns number of updated documents which are saved in Icing lib. */
    public int getSavedDocumentCount() {
        return mSavedDocumentCount;
    }

    /** Builder for {@link SchemaMigrationStats}. */
    public static class Builder {
        int mGetSchemaLatencyMillis;
        int mQueryAndTransformLatencyMillis;
        int mFirstSetSchemaLatencyMillis;
        int mSecondSetSchemaLatencyMillis;
        int mSaveDocumentLatencyMillis;
        int mMigratedDocumentCount;
        int mSavedDocumentCount;

        /** Sets latency for the GetSchema action in milliseconds. */
        @NonNull
        public SchemaMigrationStats.Builder setGetSchemaLatencyMillis(int getSchemaLatencyMillis) {
            mGetSchemaLatencyMillis = getSchemaLatencyMillis;
            return this;
        }

        /**
         * Sets latency for querying all documents that need to be migrated to new version and
         * transforming documents to new version in milliseconds.
         */
        @NonNull
        public SchemaMigrationStats.Builder setQueryAndTransformLatencyMillis(
                int queryAndTransformLatencyMillis) {
            mQueryAndTransformLatencyMillis = queryAndTransformLatencyMillis;
            return this;
        }

        /** Sets latency of first SetSchema action in milliseconds. */
        @NonNull
        public SchemaMigrationStats.Builder setFirstSetSchemaLatencyMillis(
                int firstSetSchemaLatencyMillis) {
            mFirstSetSchemaLatencyMillis = firstSetSchemaLatencyMillis;
            return this;
        }

        /** Sets latency of second SetSchema action in milliseconds. */
        @NonNull
        public SchemaMigrationStats.Builder setSecondSetSchemaLatencyMillis(
                int secondSetSchemaLatencyMillis) {
            mSecondSetSchemaLatencyMillis = secondSetSchemaLatencyMillis;
            return this;
        }

        /** Sets latency for putting migrated document to Icing lib in milliseconds. */
        @NonNull
        public SchemaMigrationStats.Builder setSaveDocumentLatencyMillis(
                int saveDocumentLatencyMillis) {
            mSaveDocumentLatencyMillis = saveDocumentLatencyMillis;
            return this;
        }

        /** Sets number of migrated documents. */
        @NonNull
        public SchemaMigrationStats.Builder setMigratedDocumentCount(int migratedDocumentCount) {
            mMigratedDocumentCount = migratedDocumentCount;
            return this;
        }

        /** Sets number of updated documents which are saved in Icing lib. */
        @NonNull
        public SchemaMigrationStats.Builder setSavedDocumentCount(int savedDocumentCount) {
            mSavedDocumentCount = savedDocumentCount;
            return this;
        }

        /**
         * Builds a new {@link SchemaMigrationStats} from the {@link SchemaMigrationStats.Builder}.
         */
        @NonNull
        public SchemaMigrationStats build() {
            return new SchemaMigrationStats(/* builder= */ this);
        }
    }
}

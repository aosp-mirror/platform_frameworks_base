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
import android.annotation.Nullable;
import android.app.appsearch.AppSearchResult;

import java.util.Objects;

/**
 * Class holds detailed stats for {@link
 * android.app.appsearch.AppSearchSession#setSchema(SetSchemaRequest)}.
 *
 * @hide
 */
public final class SetSchemaStats {
    @NonNull private final String mPackageName;

    @NonNull private final String mDatabase;

    /**
     * The status code returned by {@link AppSearchResult#getResultCode()} for the call or internal
     * state.
     */
    @AppSearchResult.ResultCode private final int mStatusCode;

    /**
     * Stores stats of SchemaMigration in SetSchema process. Is {@code null} if no schema migration
     * is needed.
     */
    @Nullable private final SchemaMigrationStats mSchemaMigrationStats;

    private final int mTotalLatencyMillis;

    /** Number of newly added schema types. */
    private final int mNewTypeCount;

    /** Number of deleted schema types. */
    private final int mDeletedTypeCount;

    /** Number of compatible schema type changes. */
    private final int mCompatibleTypeChangeCount;

    /** Number of index-incompatible schema type changes. */
    private final int mIndexIncompatibleTypeChangeCount;

    /** Number of backwards-incompatible schema type changes. */
    private final int mBackwardsIncompatibleTypeChangeCount;

    SetSchemaStats(@NonNull Builder builder) {
        Objects.requireNonNull(builder);
        mPackageName = builder.mPackageName;
        mDatabase = builder.mDatabase;
        mStatusCode = builder.mStatusCode;
        mSchemaMigrationStats = builder.mSchemaMigrationStats;
        mTotalLatencyMillis = builder.mTotalLatencyMillis;
        mNewTypeCount = builder.mNewTypeCount;
        mDeletedTypeCount = builder.mDeletedTypeCount;
        mCompatibleTypeChangeCount = builder.mCompatibleTypeChangeCount;
        mIndexIncompatibleTypeChangeCount = builder.mIndexIncompatibleTypeChangeCount;
        mBackwardsIncompatibleTypeChangeCount = builder.mBackwardsIncompatibleTypeChangeCount;
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

    /** Returns status of the SetSchema action. */
    @AppSearchResult.ResultCode
    public int getStatusCode() {
        return mStatusCode;
    }

    /**
     * Returns the status of schema migration, if migration is executed during the SetSchema
     * process. Otherwise, returns {@code null}.
     */
    @Nullable
    public SchemaMigrationStats getSchemaMigrationStats() {
        return mSchemaMigrationStats;
    }

    /** Returns the total latency of the SetSchema action. */
    public int getTotalLatencyMillis() {
        return mTotalLatencyMillis;
    }

    /** Returns number of newly added schema types. */
    public int getNewTypeCount() {
        return mNewTypeCount;
    }

    /** Returns number of deleted schema types. */
    public int getDeletedTypeCount() {
        return mDeletedTypeCount;
    }

    /** Returns number of compatible type changes. */
    public int getCompatibleTypeChangeCount() {
        return mCompatibleTypeChangeCount;
    }

    /**
     * Returns number of index-incompatible type change.
     *
     * <p>An index-incompatible type change is one that affects how pre-existing data should be
     * searched over, such as modifying the {@code IndexingType} of an existing property.
     */
    public int getIndexIncompatibleTypeChangeCount() {
        return mIndexIncompatibleTypeChangeCount;
    }

    /**
     * Returns number of backwards-incompatible type change.
     *
     * <p>For details on what constitutes a backward-incompatible type change, please see {@link
     * android.app.appsearch.SetSchemaRequest}.
     */
    public int getBackwardsIncompatibleTypeChangeCount() {
        return mBackwardsIncompatibleTypeChangeCount;
    }

    /** Builder for {@link SetSchemaStats}. */
    public static class Builder {
        @NonNull final String mPackageName;
        @NonNull final String mDatabase;
        @AppSearchResult.ResultCode int mStatusCode;
        @Nullable SchemaMigrationStats mSchemaMigrationStats;
        int mTotalLatencyMillis;
        int mNewTypeCount;
        int mDeletedTypeCount;
        int mCompatibleTypeChangeCount;
        int mIndexIncompatibleTypeChangeCount;
        int mBackwardsIncompatibleTypeChangeCount;

        /** Constructor for the {@link Builder}. */
        public Builder(@NonNull String packageName, @NonNull String database) {
            mPackageName = Objects.requireNonNull(packageName);
            mDatabase = Objects.requireNonNull(database);
        }

        /** Sets the status of the SetSchema action. */
        @NonNull
        public Builder setStatusCode(@AppSearchResult.ResultCode int statusCode) {
            mStatusCode = statusCode;
            return this;
        }

        /** Sets the status of schema migration. */
        @NonNull
        public Builder setSchemaMigrationStats(@NonNull SchemaMigrationStats schemaMigrationStats) {
            mSchemaMigrationStats = Objects.requireNonNull(schemaMigrationStats);
            return this;
        }

        /** Sets total latency for the SetSchema action in milliseconds. */
        @NonNull
        public Builder setTotalLatencyMillis(int totalLatencyMillis) {
            mTotalLatencyMillis = totalLatencyMillis;
            return this;
        }

        /** Sets number of new types. */
        @NonNull
        public Builder setNewTypeCount(int newTypeCount) {
            mNewTypeCount = newTypeCount;
            return this;
        }

        /** Sets number of deleted types. */
        @NonNull
        public Builder setDeletedTypeCount(int deletedTypeCount) {
            mDeletedTypeCount = deletedTypeCount;
            return this;
        }

        /** Sets number of compatible type changes. */
        @NonNull
        public Builder setCompatibleTypeChangeCount(int compatibleTypeChangeCount) {
            mCompatibleTypeChangeCount = compatibleTypeChangeCount;
            return this;
        }

        /** Sets number of index-incompatible type changes. */
        @NonNull
        public Builder setIndexIncompatibleTypeChangeCount(int indexIncompatibleTypeChangeCount) {
            mIndexIncompatibleTypeChangeCount = indexIncompatibleTypeChangeCount;
            return this;
        }

        /** Sets number of backwards-incompatible type changes. */
        @NonNull
        public Builder setBackwardsIncompatibleTypeChangeCount(
                int backwardsIncompatibleTypeChangeCount) {
            mBackwardsIncompatibleTypeChangeCount = backwardsIncompatibleTypeChangeCount;
            return this;
        }

        /** Builds a new {@link SetSchemaStats} from the {@link Builder}. */
        @NonNull
        public SetSchemaStats build() {
            return new SetSchemaStats(/* builder= */ this);
        }
    }
}

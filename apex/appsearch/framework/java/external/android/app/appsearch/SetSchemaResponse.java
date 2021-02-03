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

package android.app.appsearch;

import static android.app.appsearch.AppSearchResult.RESULT_OK;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.ArraySet;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** The response class of {@link AppSearchSession#setSchema} */
public class SetSchemaResponse {
    private final List<MigrationFailure> mMigrationFailures;
    private final Set<String> mDeletedTypes;
    private final Set<String> mMigratedTypes;
    private final Set<String> mIncompatibleTypes;
    private final @AppSearchResult.ResultCode int mResultCode;

    SetSchemaResponse(
            @NonNull List<MigrationFailure> migrationFailures,
            @NonNull Set<String> deletedTypes,
            @NonNull Set<String> migratedTypes,
            @NonNull Set<String> incompatibleTypes,
            @AppSearchResult.ResultCode int resultCode) {
        mMigrationFailures = Preconditions.checkNotNull(migrationFailures);
        mDeletedTypes = Preconditions.checkNotNull(deletedTypes);
        mMigratedTypes = Preconditions.checkNotNull(migratedTypes);
        mIncompatibleTypes = Preconditions.checkNotNull(incompatibleTypes);
        mResultCode = resultCode;
    }

    /**
     * Returns a {@link List} of all failed {@link MigrationFailure}.
     *
     * <p>A {@link MigrationFailure} will be generated if the system trying to save a post-migrated
     * {@link GenericDocument} but fail.
     *
     * <p>{@link MigrationFailure} contains the uri, namespace and schemaType of the post-migrated
     * {@link GenericDocument} and the error reason. Mostly it will be mismatch the schema it
     * migrated to.
     */
    @NonNull
    public List<MigrationFailure> getMigrationFailures() {
        return Collections.unmodifiableList(mMigrationFailures);
    }

    /**
     * Returns a {@link Set} of schema type that were deleted by the {@link
     * AppSearchSession#setSchema} call.
     */
    @NonNull
    public Set<String> getDeletedTypes() {
        return Collections.unmodifiableSet(mDeletedTypes);
    }

    /**
     * Returns a {@link Set} of schema type that were migrated by the {@link
     * AppSearchSession#setSchema} call.
     */
    @NonNull
    public Set<String> getMigratedTypes() {
        return Collections.unmodifiableSet(mMigratedTypes);
    }

    /**
     * Returns a {@link Set} of schema type whose new definitions set in the {@link
     * AppSearchSession#setSchema} call were incompatible with the pre-existing schema.
     *
     * <p>If a {@link android.app.appsearch.AppSearchSchema.Migrator} is provided for this type and
     * the migration is success triggered. The type will also appear in {@link #getMigratedTypes()}.
     *
     * @see AppSearchSession#setSchema
     * @see SetSchemaRequest.Builder#setForceOverride
     */
    @NonNull
    public Set<String> getIncompatibleTypes() {
        return Collections.unmodifiableSet(mIncompatibleTypes);
    }

    /** Returns {@code true} if all {@link AppSearchSchema}s are successful set to the system. */
    public boolean isSuccess() {
        return mResultCode == RESULT_OK;
    }

    @Override
    @NonNull
    public String toString() {
        return "{\n  Does setSchema success? : "
                + isSuccess()
                + "\n  failures: "
                + mMigrationFailures
                + "\n}";
    }

    /**
     * Builder for {@link SetSchemaResponse} objects.
     *
     * @hide
     */
    public static class Builder {
        private final List<MigrationFailure> mMigrationFailures = new ArrayList<>();
        private final Set<String> mDeletedTypes = new ArraySet<>();
        private final Set<String> mMigratedTypes = new ArraySet<>();
        private final Set<String> mIncompatibleTypes = new ArraySet<>();
        private @AppSearchResult.ResultCode int mResultCode = RESULT_OK;
        private boolean mBuilt = false;

        /** Adds a {@link MigrationFailure}. */
        @NonNull
        public Builder setFailure(
                @NonNull String schemaType,
                @NonNull String namespace,
                @NonNull String uri,
                @NonNull AppSearchResult<Void> failureResult) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            Preconditions.checkNotNull(schemaType);
            Preconditions.checkNotNull(namespace);
            Preconditions.checkNotNull(uri);
            Preconditions.checkNotNull(failureResult);
            Preconditions.checkState(!failureResult.isSuccess());
            mMigrationFailures.add(new MigrationFailure(schemaType, namespace, uri, failureResult));
            return this;
        }

        /** Adds a {@link MigrationFailure}. */
        @NonNull
        public Builder setFailure(
                @NonNull String schemaType,
                @NonNull String namespace,
                @NonNull String uri,
                @AppSearchResult.ResultCode int resultCode,
                @Nullable String errorMessage) {
            mMigrationFailures.add(
                    new MigrationFailure(
                            schemaType,
                            namespace,
                            uri,
                            AppSearchResult.newFailedResult(resultCode, errorMessage)));
            return this;
        }

        /** Adds deletedTypes to the list of deleted schema types. */
        @NonNull
        public Builder addDeletedTypes(@NonNull Collection<String> deletedTypes) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            mDeletedTypes.addAll(Preconditions.checkNotNull(deletedTypes));
            return this;
        }

        /** Adds incompatibleTypes to the list of incompatible schema types. */
        @NonNull
        public Builder addIncompatibleTypes(@NonNull Collection<String> incompatibleTypes) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            mIncompatibleTypes.addAll(Preconditions.checkNotNull(incompatibleTypes));
            return this;
        }

        /** Adds migratedTypes to the list of migrated schema types. */
        @NonNull
        public Builder addMigratedTypes(@NonNull Collection<String> migratedTypes) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            mMigratedTypes.addAll(Preconditions.checkNotNull(migratedTypes));
            return this;
        }

        /** Sets the {@link AppSearchResult.ResultCode} of the response. */
        @NonNull
        public Builder setResultCode(@AppSearchResult.ResultCode int resultCode) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            mResultCode = resultCode;
            return this;
        }

        /** Builds a {@link SetSchemaResponse} object. */
        @NonNull
        public SetSchemaResponse build() {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            mBuilt = true;
            return new SetSchemaResponse(
                    mMigrationFailures,
                    mDeletedTypes,
                    mMigratedTypes,
                    mIncompatibleTypes,
                    mResultCode);
        }
    }

    /**
     * The class represents a post-migrated {@link GenericDocument} that failed to be saved by
     * {@link AppSearchSession#setSchema}.
     */
    public static class MigrationFailure {
        private final String mSchemaType;
        private final String mNamespace;
        private final String mUri;
        AppSearchResult<Void> mFailureResult;

        MigrationFailure(
                @NonNull String schemaType,
                @NonNull String namespace,
                @NonNull String uri,
                @NonNull AppSearchResult<Void> result) {
            mSchemaType = schemaType;
            mNamespace = namespace;
            mUri = uri;
            mFailureResult = result;
        }

        /** Returns the schema type of the {@link GenericDocument} that fails to be migrated. */
        @NonNull
        public String getSchemaType() {
            return mSchemaType;
        }

        /** Returns the namespace of the {@link GenericDocument} that fails to be migrated. */
        @NonNull
        public String getNamespace() {
            return mNamespace;
        }

        /** Returns the uri of the {@link GenericDocument} that fails to be migrated. */
        @NonNull
        public String getUri() {
            return mUri;
        }

        /**
         * Returns the {@link AppSearchResult} that indicates why the post-migrated {@link
         * GenericDocument} fails to be saved.
         */
        @NonNull
        public AppSearchResult<Void> getAppSearchResult() {
            return mFailureResult;
        }
    }
}

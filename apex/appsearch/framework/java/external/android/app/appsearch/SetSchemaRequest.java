/*
 * Copyright 2020 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.internal.util.Preconditions;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Encapsulates a request to update the schema of an {@link AppSearchSession} database.
 *
 * <p>The schema is composed of a collection of {@link AppSearchSchema} objects, each of which
 * defines a unique type of data.
 *
 * <p>The first call to SetSchemaRequest will set the provided schema and store it within the {@link
 * AppSearchSession} database.
 *
 * <p>Subsequent calls will compare the provided schema to the previously saved schema, to determine
 * how to treat existing documents.
 *
 * <p>The following types of schema modifications are always safe and are made without deleting any
 * existing documents:
 *
 * <ul>
 *   <li>Addition of new {@link AppSearchSchema} types
 *   <li>Addition of new properties to an existing {@link AppSearchSchema} type
 *   <li>Changing the cardinality of a property to be less restrictive
 * </ul>
 *
 * <p>The following types of schema changes are not backwards compatible:
 *
 * <ul>
 *   <li>Removal of an existing {@link AppSearchSchema} type
 *   <li>Removal of a property from an existing {@link AppSearchSchema} type
 *   <li>Changing the data type of an existing property
 *   <li>Changing the cardinality of a property to be more restrictive
 * </ul>
 *
 * <p>Providing a schema with incompatible changes, will throw an {@link
 * android.app.appsearch.exceptions.AppSearchException}, with a message describing the
 * incompatibility. As a result, the previously set schema will remain unchanged.
 *
 * <p>Backward incompatible changes can be made by setting {@link
 * SetSchemaRequest.Builder#setForceOverride} method to {@code true}. This deletes all documents
 * that are incompatible with the new schema. The new schema is then saved and persisted to disk.
 *
 * @see AppSearchSession#setSchema
 */
public final class SetSchemaRequest {
    private final Set<AppSearchSchema> mSchemas;
    private final Set<String> mSchemasNotDisplayedBySystem;
    private final Map<String, Set<PackageIdentifier>> mSchemasVisibleToPackages;
    private final Map<String, AppSearchSchema.Migrator> mMigrators;
    private final boolean mForceOverride;

    SetSchemaRequest(
            @NonNull Set<AppSearchSchema> schemas,
            @NonNull Set<String> schemasNotDisplayedBySystem,
            @NonNull Map<String, Set<PackageIdentifier>> schemasVisibleToPackages,
            @NonNull Map<String, AppSearchSchema.Migrator> migrators,
            boolean forceOverride) {
        mSchemas = Preconditions.checkNotNull(schemas);
        mSchemasNotDisplayedBySystem = Preconditions.checkNotNull(schemasNotDisplayedBySystem);
        mSchemasVisibleToPackages = Preconditions.checkNotNull(schemasVisibleToPackages);
        mMigrators = Preconditions.checkNotNull(migrators);
        mForceOverride = forceOverride;
    }

    /** Returns the {@link AppSearchSchema} types that are part of this request. */
    @NonNull
    public Set<AppSearchSchema> getSchemas() {
        return Collections.unmodifiableSet(mSchemas);
    }

    /**
     * TODO(b/181887768): This method exists only for dogfooder transition and must be removed.
     * @deprecated This method exists only for dogfooder transition and must be removed.
     */
    @Deprecated
    @NonNull
    public Set<String> getSchemasNotVisibleToSystemUi() {
        return getSchemasNotDisplayedBySystem();
    }

    /**
     * Returns all the schema types that are opted out of being displayed and visible on any system
     * UI surface.
     */
    @NonNull
    public Set<String> getSchemasNotDisplayedBySystem() {
        return Collections.unmodifiableSet(mSchemasNotDisplayedBySystem);
    }

    /**
     * Returns a mapping of schema types to the set of packages that have access to that schema
     * type.
     *
     * <p>It’s inefficient to call this method repeatedly.
     */
    @NonNull
    public Map<String, Set<PackageIdentifier>> getSchemasVisibleToPackages() {
        Map<String, Set<PackageIdentifier>> copy = new ArrayMap<>();
        for (String key : mSchemasVisibleToPackages.keySet()) {
            copy.put(key, new ArraySet<>(mSchemasVisibleToPackages.get(key)));
        }
        return copy;
    }

    /** Returns the map of {@link android.app.appsearch.AppSearchSchema.Migrator}. */
    @NonNull
    public Map<String, AppSearchSchema.Migrator> getMigrators() {
        return Collections.unmodifiableMap(mMigrators);
    }

    /**
     * Returns a mapping of {@link AppSearchSchema} types to the set of packages that have access to
     * that schema type.
     *
     * <p>A more efficient version of {@link #getSchemasVisibleToPackages}, but it returns a
     * modifiable map. This is not meant to be unhidden and should only be used by internal classes.
     *
     * @hide
     */
    @NonNull
    public Map<String, Set<PackageIdentifier>> getSchemasVisibleToPackagesInternal() {
        return mSchemasVisibleToPackages;
    }

    /** Returns whether this request will force the schema to be overridden. */
    public boolean isForceOverride() {
        return mForceOverride;
    }

    /**
     * Builder for {@link SetSchemaRequest} objects.
     *
     * <p>Once {@link #build} is called, the instance can no longer be used.
     */
    public static final class Builder {
        private final Set<AppSearchSchema> mSchemas = new ArraySet<>();
        private final Set<String> mSchemasNotDisplayedBySystem = new ArraySet<>();
        private final Map<String, Set<PackageIdentifier>> mSchemasVisibleToPackages =
                new ArrayMap<>();
        private final Map<String, AppSearchSchema.Migrator> mMigrators = new ArrayMap<>();
        private boolean mForceOverride = false;
        private boolean mBuilt = false;

        /**
         * Adds one or more {@link AppSearchSchema} types to the schema.
         *
         * <p>An {@link AppSearchSchema} object represents one type of structured data.
         *
         * <p>Any documents of these types will be displayed on system UI surfaces by default.
         *
         * @throws IllegalStateException if the builder has already been used.
         */
        @NonNull
        public Builder addSchemas(@NonNull AppSearchSchema... schemas) {
            Preconditions.checkNotNull(schemas);
            return addSchemas(Arrays.asList(schemas));
        }

        /**
         * Adds a collection of {@link AppSearchSchema} objects to the schema.
         *
         * <p>An {@link AppSearchSchema} object represents one type of structured data.
         *
         * @throws IllegalStateException if the builder has already been used.
         */
        @NonNull
        public Builder addSchemas(@NonNull Collection<AppSearchSchema> schemas) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            Preconditions.checkNotNull(schemas);
            mSchemas.addAll(schemas);
            return this;
        }

        /**
         * TODO(b/181887768): This method exists only for dogfooder transition and must be removed.
         * @deprecated This method exists only for dogfooder transition and must be removed.
         */
        @Deprecated
        @NonNull
        public Builder setSchemaTypeVisibilityForSystemUi(
                @NonNull String schemaType, boolean displayed) {
            return setSchemaTypeDisplayedBySystem(schemaType, displayed);
        }

        /**
         * Sets whether or not documents from the provided {@code schemaType} will be displayed and
         * visible on any system UI surface.
         *
         * <p>This setting applies to the provided {@code schemaType} only, and does not persist
         * across {@link AppSearchSession#setSchema} calls.
         *
         * <p>The default behavior, if this method is not called, is to allow types to be displayed
         * on system UI surfaces.
         *
         * @param schemaType The name of an {@link AppSearchSchema} within the same {@link
         *     SetSchemaRequest}, which will be configured.
         * @param displayed Whether documents of this type will be displayed on system UI surfaces.
         * @throws IllegalStateException if the builder has already been used.
         */
        // Merged list available from getSchemasNotDisplayedBySystem
        @SuppressLint("MissingGetterMatchingBuilder")
        @NonNull
        public Builder setSchemaTypeDisplayedBySystem(
                @NonNull String schemaType, boolean displayed) {
            Preconditions.checkNotNull(schemaType);
            Preconditions.checkState(!mBuilt, "Builder has already been used");

            if (displayed) {
                mSchemasNotDisplayedBySystem.remove(schemaType);
            } else {
                mSchemasNotDisplayedBySystem.add(schemaType);
            }
            return this;
        }

        /**
         * Sets whether or not documents from the provided {@code schemaType} can be read by the
         * specified package.
         *
         * <p>Each package is represented by a {@link PackageIdentifier}, containing a package name
         * and a byte array of type {@link android.content.pm.PackageManager#CERT_INPUT_SHA256}.
         *
         * <p>To opt into one-way data sharing with another application, the developer will need to
         * explicitly grant the other application’s package name and certificate Read access to its
         * data.
         *
         * <p>For two-way data sharing, both applications need to explicitly grant Read access to
         * one another.
         *
         * <p>By default, data sharing between applications is disabled.
         *
         * @param schemaType The schema type to set visibility on.
         * @param visible Whether the {@code schemaType} will be visible or not.
         * @param packageIdentifier Represents the package that will be granted visibility.
         * @throws IllegalStateException if the builder has already been used.
         */
        // Merged list available from getSchemasVisibleToPackages
        @SuppressLint("MissingGetterMatchingBuilder")
        @NonNull
        public Builder setSchemaTypeVisibilityForPackage(
                @NonNull String schemaType,
                boolean visible,
                @NonNull PackageIdentifier packageIdentifier) {
            Preconditions.checkNotNull(schemaType);
            Preconditions.checkNotNull(packageIdentifier);
            Preconditions.checkState(!mBuilt, "Builder has already been used");

            Set<PackageIdentifier> packageIdentifiers = mSchemasVisibleToPackages.get(schemaType);
            if (visible) {
                if (packageIdentifiers == null) {
                    packageIdentifiers = new ArraySet<>();
                }
                packageIdentifiers.add(packageIdentifier);
                mSchemasVisibleToPackages.put(schemaType, packageIdentifiers);
            } else {
                if (packageIdentifiers == null) {
                    // Return early since there was nothing set to begin with.
                    return this;
                }
                packageIdentifiers.remove(packageIdentifier);
                if (packageIdentifiers.isEmpty()) {
                    // Remove the entire key so that we don't have empty sets as values.
                    mSchemasVisibleToPackages.remove(schemaType);
                }
            }

            return this;
        }

        /**
         * Sets the {@link android.app.appsearch.AppSearchSchema.Migrator}.
         *
         * @param schemaType The schema type to set migrator on.
         * @param migrator The migrator translate a document from it's old version to a new
         *     incompatible version.
         */
        @NonNull
        @SuppressLint("MissingGetterMatchingBuilder") // Getter return plural objects.
        public Builder setMigrator(
                @NonNull String schemaType, @NonNull AppSearchSchema.Migrator migrator) {
            Preconditions.checkNotNull(schemaType);
            Preconditions.checkNotNull(migrator);
            mMigrators.put(schemaType, migrator);
            return this;
        }

        /**
         * Sets whether or not to override the current schema in the {@link AppSearchSession}
         * database.
         *
         * <p>Call this method whenever backward incompatible changes need to be made by setting
         * {@code forceOverride} to {@code true}. As a result, during execution of the setSchema
         * operation, all documents that are incompatible with the new schema will be deleted and
         * the new schema will be saved and persisted.
         *
         * <p>By default, this is {@code false}.
         */
        @NonNull
        public Builder setForceOverride(boolean forceOverride) {
            mForceOverride = forceOverride;
            return this;
        }

        /**
         * Builds a new {@link SetSchemaRequest} object.
         *
         * @throws IllegalArgumentException if schema types were referenced, but the corresponding
         *     {@link AppSearchSchema} type was never added.
         * @throws IllegalStateException if the builder has already been used.
         */
        @NonNull
        public SetSchemaRequest build() {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            mBuilt = true;

            // Verify that any schema types with display or visibility settings refer to a real
            // schema.
            // Create a copy because we're going to remove from the set for verification purposes.
            Set<String> referencedSchemas = new ArraySet<>(mSchemasNotDisplayedBySystem);
            referencedSchemas.addAll(mSchemasVisibleToPackages.keySet());

            for (AppSearchSchema schema : mSchemas) {
                referencedSchemas.remove(schema.getSchemaType());
            }
            if (!referencedSchemas.isEmpty()) {
                // We still have schema types that weren't seen in our mSchemas set. This means
                // there wasn't a corresponding AppSearchSchema.
                throw new IllegalArgumentException(
                        "Schema types " + referencedSchemas + " referenced, but were not added.");
            }

            return new SetSchemaRequest(
                    mSchemas,
                    mSchemasNotDisplayedBySystem,
                    mSchemasVisibleToPackages,
                    mMigrators,
                    mForceOverride);
        }
    }
}

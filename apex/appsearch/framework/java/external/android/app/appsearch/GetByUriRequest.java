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
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Encapsulates a request to retrieve documents by namespace and URIs from the {@link
 * AppSearchSession} database.
 *
 * @see AppSearchSession#getByUri
 */
public final class GetByUriRequest {
    /**
     * Schema type to be used in {@link android.app.appsearch.GetByUriRequest.Builder#addProjection}
     * to apply property paths to all results, excepting any types that have had their own, specific
     * property paths set.
     */
    public static final String PROJECTION_SCHEMA_TYPE_WILDCARD = "*";

    private final String mNamespace;
    private final Set<String> mUris;
    private final Map<String, List<String>> mTypePropertyPathsMap;

    GetByUriRequest(
            @NonNull String namespace,
            @NonNull Set<String> uris,
            @NonNull Map<String, List<String>> typePropertyPathsMap) {
        mNamespace = Preconditions.checkNotNull(namespace);
        mUris = Preconditions.checkNotNull(uris);
        mTypePropertyPathsMap = Preconditions.checkNotNull(typePropertyPathsMap);
    }

    /** Returns the namespace attached to the request. */
    @NonNull
    public String getNamespace() {
        return mNamespace;
    }

    /** Returns the set of URIs attached to the request. */
    @NonNull
    public Set<String> getUris() {
        return Collections.unmodifiableSet(mUris);
    }

    /**
     * Returns a map from schema type to property paths to be used for projection.
     *
     * <p>If the map is empty, then all properties will be retrieved for all results.
     *
     * <p>Calling this function repeatedly is inefficient. Prefer to retain the Map returned by this
     * function, rather than calling it multiple times.
     */
    @NonNull
    public Map<String, List<String>> getProjections() {
        Map<String, List<String>> copy = new ArrayMap<>();
        for (String key : mTypePropertyPathsMap.keySet()) {
            copy.put(key, new ArrayList<>(mTypePropertyPathsMap.get(key)));
        }
        return copy;
    }

    /**
     * Returns a map from schema type to property paths to be used for projection.
     *
     * <p>If the map is empty, then all properties will be retrieved for all results.
     *
     * <p>A more efficient version of {@link #getProjections}, but it returns a modifiable map. This
     * is not meant to be unhidden and should only be used by internal classes.
     *
     * @hide
     */
    @NonNull
    public Map<String, List<String>> getProjectionsInternal() {
        return mTypePropertyPathsMap;
    }

    /**
     * Builder for {@link GetByUriRequest} objects.
     *
     * <p>Once {@link #build} is called, the instance can no longer be used.
     */
    public static final class Builder {
        private String mNamespace = GenericDocument.DEFAULT_NAMESPACE;
        private final Set<String> mUris = new ArraySet<>();
        private final Map<String, List<String>> mProjectionTypePropertyPaths = new ArrayMap<>();
        private boolean mBuilt = false;

        /**
         * Sets the namespace to retrieve documents for.
         *
         * <p>If this is not called, the namespace defaults to {@link
         * GenericDocument#DEFAULT_NAMESPACE}.
         *
         * @throws IllegalStateException if the builder has already been used.
         */
        @NonNull
        public Builder setNamespace(@NonNull String namespace) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            Preconditions.checkNotNull(namespace);
            mNamespace = namespace;
            return this;
        }

        /**
         * Adds one or more URIs to the request.
         *
         * @throws IllegalStateException if the builder has already been used.
         */
        @NonNull
        public Builder addUris(@NonNull String... uris) {
            Preconditions.checkNotNull(uris);
            return addUris(Arrays.asList(uris));
        }

        /**
         * Adds a collection of URIs to the request.
         *
         * @throws IllegalStateException if the builder has already been used.
         */
        @NonNull
        public Builder addUris(@NonNull Collection<String> uris) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            Preconditions.checkNotNull(uris);
            mUris.addAll(uris);
            return this;
        }

        /**
         * Adds property paths for the specified type to be used for projection. If property paths
         * are added for a type, then only the properties referred to will be retrieved for results
         * of that type. If a property path that is specified isn't present in a result, it will be
         * ignored for that result. Property paths cannot be null.
         *
         * <p>If no property paths are added for a particular type, then all properties of results
         * of that type will be retrieved.
         *
         * <p>If property path is added for the {@link
         * GetByUriRequest#PROJECTION_SCHEMA_TYPE_WILDCARD}, then those property paths will apply to
         * all results, excepting any types that have their own, specific property paths set.
         *
         * @throws IllegalStateException if the builder has already been used.
         * @see SearchSpec.Builder#addProjection
         */
        @NonNull
        public Builder addProjection(
                @NonNull String schemaType, @NonNull Collection<String> propertyPaths) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            Preconditions.checkNotNull(schemaType);
            Preconditions.checkNotNull(propertyPaths);
            List<String> propertyPathsList = new ArrayList<>(propertyPaths.size());
            for (String propertyPath : propertyPaths) {
                Preconditions.checkNotNull(propertyPath);
                propertyPathsList.add(propertyPath);
            }
            mProjectionTypePropertyPaths.put(schemaType, propertyPathsList);
            return this;
        }

        /**
         * Builds a new {@link GetByUriRequest}.
         *
         * @throws IllegalStateException if the builder has already been used.
         */
        @NonNull
        public GetByUriRequest build() {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            mBuilt = true;
            return new GetByUriRequest(mNamespace, mUris, mProjectionTypePropertyPaths);
        }
    }
}

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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Encapsulates a request to retrieve documents by namespace and IDs from the {@link
 * AppSearchSession} database.
 *
 * @see AppSearchSession#getByDocumentId
 */
public final class GetByDocumentIdRequest {
    /**
     * Schema type to be used in {@link GetByDocumentIdRequest.Builder#addProjection} to apply
     * property paths to all results, excepting any types that have had their own, specific property
     * paths set.
     */
    public static final String PROJECTION_SCHEMA_TYPE_WILDCARD = "*";

    private final String mNamespace;
    private final Set<String> mIds;
    private final Map<String, List<String>> mTypePropertyPathsMap;

    GetByDocumentIdRequest(
            @NonNull String namespace,
            @NonNull Set<String> ids,
            @NonNull Map<String, List<String>> typePropertyPathsMap) {
        mNamespace = Objects.requireNonNull(namespace);
        mIds = Objects.requireNonNull(ids);
        mTypePropertyPathsMap = Objects.requireNonNull(typePropertyPathsMap);
    }

    /** Returns the namespace attached to the request. */
    @NonNull
    public String getNamespace() {
        return mNamespace;
    }

    /** Returns the set of document IDs attached to the request. */
    @NonNull
    public Set<String> getIds() {
        return Collections.unmodifiableSet(mIds);
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
        for (Map.Entry<String, List<String>> entry : mTypePropertyPathsMap.entrySet()) {
            copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
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

    /** Builder for {@link GetByDocumentIdRequest} objects. */
    public static final class Builder {
        private final String mNamespace;
        private ArraySet<String> mIds = new ArraySet<>();
        private ArrayMap<String, List<String>> mProjectionTypePropertyPaths = new ArrayMap<>();
        private boolean mBuilt = false;

        /** Creates a {@link GetByDocumentIdRequest.Builder} instance. */
        public Builder(@NonNull String namespace) {
            mNamespace = Objects.requireNonNull(namespace);
        }

        /** Adds one or more document IDs to the request. */
        @NonNull
        public Builder addIds(@NonNull String... ids) {
            Objects.requireNonNull(ids);
            resetIfBuilt();
            return addIds(Arrays.asList(ids));
        }

        /** Adds a collection of IDs to the request. */
        @NonNull
        public Builder addIds(@NonNull Collection<String> ids) {
            Objects.requireNonNull(ids);
            resetIfBuilt();
            mIds.addAll(ids);
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
         * GetByDocumentIdRequest#PROJECTION_SCHEMA_TYPE_WILDCARD}, then those property paths will
         * apply to all results, excepting any types that have their own, specific property paths
         * set.
         *
         * @see SearchSpec.Builder#addProjection
         */
        @NonNull
        public Builder addProjection(
                @NonNull String schemaType, @NonNull Collection<String> propertyPaths) {
            Objects.requireNonNull(schemaType);
            Objects.requireNonNull(propertyPaths);
            resetIfBuilt();
            List<String> propertyPathsList = new ArrayList<>(propertyPaths.size());
            for (String propertyPath : propertyPaths) {
                Objects.requireNonNull(propertyPath);
                propertyPathsList.add(propertyPath);
            }
            mProjectionTypePropertyPaths.put(schemaType, propertyPathsList);
            return this;
        }

        /** Builds a new {@link GetByDocumentIdRequest}. */
        @NonNull
        public GetByDocumentIdRequest build() {
            mBuilt = true;
            return new GetByDocumentIdRequest(mNamespace, mIds, mProjectionTypePropertyPaths);
        }

        private void resetIfBuilt() {
            if (mBuilt) {
                mIds = new ArraySet<>(mIds);
                // No need to clone each propertyPathsList inside mProjectionTypePropertyPaths since
                // the builder only replaces it, never adds to it. So even if the builder is used
                // again, the previous one will remain with the object.
                mProjectionTypePropertyPaths = new ArrayMap<>(mProjectionTypePropertyPaths);
                mBuilt = false;
            }
        }
    }
}

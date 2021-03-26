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
import android.util.ArraySet;

import com.android.internal.util.Preconditions;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;

/**
 * Encapsulates a request to remove documents by namespace and URIs from the {@link
 * AppSearchSession} database.
 *
 * @see AppSearchSession#remove
 */
public final class RemoveByUriRequest {
    private final String mNamespace;
    private final Set<String> mUris;

    RemoveByUriRequest(String namespace, Set<String> uris) {
        mNamespace = namespace;
        mUris = uris;
    }

    /** Returns the namespace to remove documents from. */
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
     * Builder for {@link RemoveByUriRequest} objects.
     *
     * <p>Once {@link #build} is called, the instance can no longer be used.
     */
    public static final class Builder {
        private final String mNamespace;
        private final Set<String> mUris = new ArraySet<>();
        private boolean mBuilt = false;

        /** Creates a {@link RemoveByUriRequest.Builder} instance. */
        public Builder(@NonNull String namespace) {
            mNamespace = Preconditions.checkNotNull(namespace);
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
         * Builds a new {@link RemoveByUriRequest}.
         *
         * @throws IllegalStateException if the builder has already been used.
         */
        @NonNull
        public RemoveByUriRequest build() {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            mBuilt = true;
            return new RemoveByUriRequest(mNamespace, mUris);
        }
    }
}

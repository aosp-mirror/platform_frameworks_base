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

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * Encapsulates a request to remove documents by namespace and IDs from the {@link AppSearchSession}
 * database.
 *
 * @see AppSearchSession#remove
 */
public final class RemoveByDocumentIdRequest {
    private final String mNamespace;
    private final Set<String> mIds;

    RemoveByDocumentIdRequest(String namespace, Set<String> ids) {
        mNamespace = namespace;
        mIds = ids;
    }

    /** Returns the namespace to remove documents from. */
    @NonNull
    public String getNamespace() {
        return mNamespace;
    }

    /** Returns the set of document IDs attached to the request. */
    @NonNull
    public Set<String> getIds() {
        return Collections.unmodifiableSet(mIds);
    }

    /** Builder for {@link RemoveByDocumentIdRequest} objects. */
    public static final class Builder {
        private final String mNamespace;
        private ArraySet<String> mIds = new ArraySet<>();
        private boolean mBuilt = false;

        /** Creates a {@link RemoveByDocumentIdRequest.Builder} instance. */
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

        /** Builds a new {@link RemoveByDocumentIdRequest}. */
        @NonNull
        public RemoveByDocumentIdRequest build() {
            mBuilt = true;
            return new RemoveByDocumentIdRequest(mNamespace, mIds);
        }

        private void resetIfBuilt() {
            if (mBuilt) {
                mIds = new ArraySet<>(mIds);
                mBuilt = false;
            }
        }
    }
}

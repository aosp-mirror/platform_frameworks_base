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
import android.compat.annotation.UnsupportedAppUsage;
import android.util.ArraySet;

import com.android.internal.util.Preconditions;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * @deprecated TODO(b/181887768): Exists for dogfood transition; must be removed.
 * @hide
 */
@Deprecated
public final class RemoveByUriRequest {
    private final String mNamespace;
    private final Set<String> mIds;

    RemoveByUriRequest(String namespace, Set<String> ids) {
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
    public Set<String> getUris() {
        return Collections.unmodifiableSet(mIds);
    }

    /**
     * @deprecated TODO(b/181887768): Exists for dogfood transition; must be removed.
     * @hide
     */
    @Deprecated
    @NonNull
    public RemoveByDocumentIdRequest toRemoveByDocumentIdRequest() {
        return new RemoveByDocumentIdRequest.Builder(mNamespace).addIds(mIds).build();
    }

    /**
     * Builder for {@link RemoveByUriRequest} objects.
     *
     * <p>Once {@link #build} is called, the instance can no longer be used.
     */
    public static final class Builder {
        private final String mNamespace;
        private final Set<String> mIds = new ArraySet<>();
        private boolean mBuilt = false;

        /**
         * @deprecated TODO(b/181887768): Exists for dogfood transition; must be removed.
         * @hide
         */
        @Deprecated
        @UnsupportedAppUsage
        public Builder(@NonNull String namespace) {
            mNamespace = Objects.requireNonNull(namespace);
        }

        /**
         * Adds one or more document IDs to the request.
         *
         * @throws IllegalStateException if the builder has already been used.
         */
        @NonNull
        public Builder addUris(@NonNull String... ids) {
            Objects.requireNonNull(ids);
            return addUris(Arrays.asList(ids));
        }

        /**
         * @deprecated TODO(b/181887768): Exists for dogfood transition; must be removed.
         * @hide
         */
        @Deprecated
        @UnsupportedAppUsage
        @NonNull
        public Builder addUris(@NonNull Collection<String> ids) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            Objects.requireNonNull(ids);
            mIds.addAll(ids);
            return this;
        }

        /**
         * @deprecated TODO(b/181887768): Exists for dogfood transition; must be removed.
         * @hide
         */
        @Deprecated
        @UnsupportedAppUsage
        @NonNull
        public RemoveByUriRequest build() {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            mBuilt = true;
            return new RemoveByUriRequest(mNamespace, mIds);
        }
    }
}

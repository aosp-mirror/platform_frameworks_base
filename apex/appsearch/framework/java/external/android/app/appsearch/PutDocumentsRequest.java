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

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Encapsulates a request to index documents into an {@link AppSearchSession} database.
 *
 * @see AppSearchSession#put
 */
public final class PutDocumentsRequest {
    private final List<GenericDocument> mDocuments;

    PutDocumentsRequest(List<GenericDocument> documents) {
        mDocuments = documents;
    }

    /** Returns a list of {@link GenericDocument} objects that are part of this request. */
    @NonNull
    public List<GenericDocument> getGenericDocuments() {
        return Collections.unmodifiableList(mDocuments);
    }

    /**
     * Builder for {@link PutDocumentsRequest} objects.
     *
     * <p>Once {@link #build} is called, the instance can no longer be used.
     */
    public static final class Builder {
        private final List<GenericDocument> mDocuments = new ArrayList<>();
        private boolean mBuilt = false;

        /**
         * Adds one or more {@link GenericDocument} objects to the request.
         *
         * @throws IllegalStateException if the builder has already been used.
         */
        @NonNull
        public Builder addGenericDocuments(@NonNull GenericDocument... documents) {
            Objects.requireNonNull(documents);
            return addGenericDocuments(Arrays.asList(documents));
        }

        /**
         * Adds a collection of {@link GenericDocument} objects to the request.
         *
         * @throws IllegalStateException if the builder has already been used.
         */
        @NonNull
        public Builder addGenericDocuments(
                @NonNull Collection<? extends GenericDocument> documents) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            Objects.requireNonNull(documents);
            mDocuments.addAll(documents);
            return this;
        }

        /**
         * Creates a new {@link PutDocumentsRequest} object.
         *
         * @throws IllegalStateException if the builder has already been used.
         */
        @NonNull
        public PutDocumentsRequest build() {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            mBuilt = true;
            return new PutDocumentsRequest(mDocuments);
        }
    }
}

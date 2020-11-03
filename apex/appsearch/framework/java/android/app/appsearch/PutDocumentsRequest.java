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

import android.annotation.SuppressLint;

import android.annotation.NonNull;
import android.app.appsearch.exceptions.AppSearchException;
import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Encapsulates a request to index a document into an {@link AppSearchManager} database.
 *
 * @see AppSearchManager#putDocuments
 * @hide
 */
public final class PutDocumentsRequest {
    private final List<GenericDocument> mDocuments;

    PutDocumentsRequest(List<GenericDocument> documents) {
        mDocuments = documents;
    }

    /** Returns the documents that are part of this request. */
    @NonNull
    public List<GenericDocument> getDocuments() {
        return Collections.unmodifiableList(mDocuments);
    }

    /** Builder for {@link PutDocumentsRequest} objects. */
    public static final class Builder {
        private final List<GenericDocument> mDocuments = new ArrayList<>();
        private boolean mBuilt = false;

        /** Adds one or more documents to the request. */
        @SuppressLint("MissingGetterMatchingBuilder")  // Merged list available from getDocuments()
        @NonNull
        public Builder addGenericDocument(@NonNull GenericDocument... documents) {
            Preconditions.checkNotNull(documents);
            return addGenericDocument(Arrays.asList(documents));
        }

        /** Adds one or more documents to the request. */
        @SuppressLint("MissingGetterMatchingBuilder")  // Merged list available from getDocuments()
        @NonNull
        public Builder addGenericDocument(@NonNull Collection<GenericDocument> documents) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            Preconditions.checkNotNull(documents);
            mDocuments.addAll(documents);
            return this;
        }

        /** Builds a new {@link PutDocumentsRequest}. */
        @NonNull
        public PutDocumentsRequest build() {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            mBuilt = true;
            return new PutDocumentsRequest(mDocuments);
        }
    }
}

/*
 * Copyright (C) 2020 The Android Open Source Project
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

import com.google.android.icing.proto.SearchResultProto;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * SearchResults are a list of results that are returned from a query. Each result from this
 * list contains a document and may contain other fields like snippets based on request.
 * @hide
 */
public final class SearchResults {

    private final SearchResultProto mSearchResultProto;

    /** @hide */
    public SearchResults(SearchResultProto searchResultProto) {
        mSearchResultProto = searchResultProto;
    }

    /**
     * This class represents the result obtained from the query. It will contain the document which
     * which matched the specified query string and specifications.
     * @hide
     */
    public static final class Result {
        private final SearchResultProto.ResultProto mResultProto;

        private Result(SearchResultProto.ResultProto resultProto) {
            mResultProto = resultProto;
        }

        /**
         * Contains the matching {@link AppSearch.Document}.
         * @return Document object which matched the query.
         * @hide
         */
        // TODO(sidchhabra): Switch to Document constructor that takes proto.
        @NonNull
        public AppSearch.Document getDocument() {
            return AppSearch.Document.newBuilder(mResultProto.getDocument().getUri(),
                    mResultProto.getDocument().getSchema())
                    .setCreationTimestampSecs(mResultProto.getDocument().getCreationTimestampSecs())
                    .setScore(mResultProto.getDocument().getScore())
                    .build();
        }

        // TODO(sidchhabra): Add Getter for ResultReader for Snippet.
    }

    @Override
    public String toString() {
        return mSearchResultProto.toString();
    }

    /**
     * Returns a {@link Result} iterator. Returns Empty Iterator if there are no matching results.
     * @hide
     */
    @NonNull
    public Iterator<Result> getResults() {
        List<Result> results = new ArrayList<>();
        // TODO(sidchhabra): Pass results using a RemoteStream.
        for (SearchResultProto.ResultProto resultProto : mSearchResultProto.getResultsList()) {
            results.add(new Result(resultProto));
        }
        return results.iterator();
    }
}

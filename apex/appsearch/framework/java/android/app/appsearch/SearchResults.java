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
import android.annotation.Nullable;

import com.google.android.icing.proto.SearchResultProto;
import com.google.android.icing.proto.SnippetMatchProto;
import com.google.android.icing.proto.SnippetProto;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * SearchResults are a list of results that are returned from a query. Each result from this
 * list contains a document and may contain other fields like snippets based on request.
 * This iterator class is not thread safe.
 * @hide
 */
public final class SearchResults implements Iterator<SearchResults.Result> {

    private final SearchResultProto mSearchResultProto;
    private int mNextIdx;

    /** @hide */
    public SearchResults(SearchResultProto searchResultProto) {
        mSearchResultProto = searchResultProto;
    }

    @Override
    public boolean hasNext() {
        return mNextIdx < mSearchResultProto.getResultsCount();
    }

    @NonNull
    @Override
    public Result next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        Result result = new Result(mSearchResultProto.getResults(mNextIdx));
        mNextIdx++;
        return result;
    }



    /**
     * This class represents the result obtained from the query. It will contain the document which
     * which matched the specified query string and specifications.
     * @hide
     */
    public static final class Result {
        private final SearchResultProto.ResultProto mResultProto;

        @Nullable
        private AppSearchDocument mDocument;

        private Result(SearchResultProto.ResultProto resultProto) {
            mResultProto = resultProto;
        }

        /**
         * Contains the matching {@link AppSearchDocument}.
         * @return Document object which matched the query.
         * @hide
         */
        @NonNull
        public AppSearchDocument getDocument() {
            if (mDocument == null) {
                mDocument = new AppSearchDocument(mResultProto.getDocument());
            }
            return mDocument;
        }

        /**
         * Contains a list of Snippets that matched the request. Only populated when requested in
         * {@link SearchSpec.Builder#setMaxSnippetSize(int)}.
         * @return  List of matches based on {@link SearchSpec}, if snippeting is disabled and this
         * method is called it will return {@code null}. Users can also restrict snippet population
         * using {@link SearchSpec.Builder#setNumToSnippet} and
         * {@link SearchSpec.Builder#setNumMatchesPerProperty}, for all results after that value
         * this method will return {@code null}.
         * @hide
         */
        // TODO(sidchhabra): Replace Document with proper constructor.
        @Nullable
        public List<MatchInfo> getMatchInfo() {
            if (!mResultProto.hasSnippet()) {
                return null;
            }
            AppSearchDocument document = getDocument();
            List<MatchInfo> matchList = new ArrayList<>();
            for (Iterator entryProtoIterator = mResultProto.getSnippet()
                    .getEntriesList().iterator(); entryProtoIterator.hasNext(); ) {
                SnippetProto.EntryProto entry = (SnippetProto.EntryProto) entryProtoIterator.next();
                for (Iterator snippetMatchProtoIterator = entry.getSnippetMatchesList().iterator();
                        snippetMatchProtoIterator.hasNext(); ) {
                    matchList.add(new MatchInfo(entry.getPropertyName(),
                            (SnippetMatchProto) snippetMatchProtoIterator.next(), document));
                }
            }
            return matchList;
        }
    }

    @Override
    public String toString() {
        return mSearchResultProto.toString();
    }
}

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

import com.google.common.util.concurrent.ListenableFuture;

import java.io.Closeable;
import java.util.List;

/**
 * SearchResults are a returned object from a query API.
 *
 * <p>Each {@link SearchResult} contains a document and may contain other fields like snippets
 * based on request.
 *
 * <p>Should close this object after finish fetching results.
 *
 * <p>This class is not thread safe.
 */
public interface SearchResultsShim extends Closeable {
    /**
     * Gets a whole page of {@link SearchResult}s.
     *
     * <p>Re-call this method to get next page of {@link SearchResult}, until it returns an
     * empty list.
     *
     * <p>The page size is set by
     * {@link android.app.appsearch.SearchSpec.Builder#setResultCountPerPage}.
     *
     * @return The pending result of performing this operation.
     */
    @NonNull
    ListenableFuture<List<SearchResult>> getNextPage();

    @Override
    void close();
}

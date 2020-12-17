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

package com.android.server.appsearch.testing;

import android.annotation.NonNull;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.SearchResult;
import android.app.appsearch.SearchResults;

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import java.io.Closeable;
import java.util.List;

/**
 * This test class adapts the AppSearch Framework API to ListenableFuture, so it can be tested via
 * a consistent interface.
 * @hide
 */
public class SearchResultsShim implements Closeable {
    private final SearchResults mSearchResults;

    SearchResultsShim(@NonNull SearchResults searchResults) {
        mSearchResults = Preconditions.checkNotNull(searchResults);
    }

    @NonNull
    public ListenableFuture<AppSearchResult<List<SearchResult>>> getNextPage() {
        SettableFuture<AppSearchResult<List<SearchResult>>> future = SettableFuture.create();
        mSearchResults.getNextPage(future::set);
        return future;
    }

    @Override
    public void close() {
        mSearchResults.close();
    }
}

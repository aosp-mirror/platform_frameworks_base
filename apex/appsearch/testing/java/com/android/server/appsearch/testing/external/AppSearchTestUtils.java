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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.app.appsearch.AppSearchBatchResult;
import android.app.appsearch.AppSearchSessionShim;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.GetByUriRequest;
import android.app.appsearch.SearchResult;
import android.app.appsearch.SearchResultsShim;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;

public class AppSearchTestUtils {

    public static <K, V> AppSearchBatchResult<K, V> checkIsBatchResultSuccess(
            Future<AppSearchBatchResult<K, V>> future) throws Exception {
        AppSearchBatchResult<K, V> result = future.get();
        assertWithMessage("AppSearchBatchResult not successful: " + result)
                .that(result.isSuccess())
                .isTrue();
        return result;
    }

    public static List<GenericDocument> doGet(
            AppSearchSessionShim session, String namespace, String... uris) throws Exception {
        AppSearchBatchResult<String, GenericDocument> result =
                checkIsBatchResultSuccess(
                        session.getByUri(
                                new GetByUriRequest.Builder(namespace).addUris(uris).build()));
        assertThat(result.getSuccesses()).hasSize(uris.length);
        assertThat(result.getFailures()).isEmpty();
        List<GenericDocument> list = new ArrayList<>(uris.length);
        for (String uri : uris) {
            list.add(result.getSuccesses().get(uri));
        }
        return list;
    }

    public static List<GenericDocument> doGet(AppSearchSessionShim session, GetByUriRequest request)
            throws Exception {
        AppSearchBatchResult<String, GenericDocument> result =
                checkIsBatchResultSuccess(session.getByUri(request));
        Set<String> uris = request.getUris();
        assertThat(result.getSuccesses()).hasSize(uris.size());
        assertThat(result.getFailures()).isEmpty();
        List<GenericDocument> list = new ArrayList<>(uris.size());
        for (String uri : uris) {
            list.add(result.getSuccesses().get(uri));
        }
        return list;
    }

    public static List<GenericDocument> convertSearchResultsToDocuments(
            SearchResultsShim searchResults) throws Exception {
        List<SearchResult> results = retrieveAllSearchResults(searchResults);
        List<GenericDocument> documents = new ArrayList<>(results.size());
        for (SearchResult result : results) {
            documents.add(result.getGenericDocument());
        }
        return documents;
    }

    public static List<SearchResult> retrieveAllSearchResults(SearchResultsShim searchResults)
            throws Exception {
        List<SearchResult> page = searchResults.getNextPage().get();
        List<SearchResult> results = new ArrayList<>();
        while (!page.isEmpty()) {
            results.addAll(page);
            page = searchResults.getNextPage().get();
        }
        return results;
    }
}

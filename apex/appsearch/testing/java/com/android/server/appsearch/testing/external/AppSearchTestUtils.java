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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.appsearch.AppSearchBatchResult;
import android.app.appsearch.AppSearchSessionShim;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.GetByDocumentIdRequest;
import android.app.appsearch.SearchResult;
import android.app.appsearch.SearchResultsShim;

import com.android.server.appsearch.external.localstorage.AppSearchLogger;
import com.android.server.appsearch.external.localstorage.stats.CallStats;
import com.android.server.appsearch.external.localstorage.stats.InitializeStats;
import com.android.server.appsearch.external.localstorage.stats.OptimizeStats;
import com.android.server.appsearch.external.localstorage.stats.PutDocumentStats;
import com.android.server.appsearch.external.localstorage.stats.RemoveStats;
import com.android.server.appsearch.external.localstorage.stats.SearchStats;
import com.android.server.appsearch.external.localstorage.stats.SetSchemaStats;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;

public class AppSearchTestUtils {
    // Non-thread-safe logger implementation for testing
    public static class TestLogger implements AppSearchLogger {
        @Nullable public CallStats mCallStats;
        @Nullable public PutDocumentStats mPutDocumentStats;
        @Nullable public InitializeStats mInitializeStats;
        @Nullable public SearchStats mSearchStats;
        @Nullable public RemoveStats mRemoveStats;
        @Nullable public OptimizeStats mOptimizeStats;
        @Nullable public SetSchemaStats mSetSchemaStats;

        @Override
        public void logStats(@NonNull CallStats stats) {
            mCallStats = stats;
        }

        @Override
        public void logStats(@NonNull PutDocumentStats stats) {
            mPutDocumentStats = stats;
        }

        @Override
        public void logStats(@NonNull InitializeStats stats) {
            mInitializeStats = stats;
        }

        @Override
        public void logStats(@NonNull SearchStats stats) {
            mSearchStats = stats;
        }

        @Override
        public void logStats(@NonNull RemoveStats stats) {
            mRemoveStats = stats;
        }

        @Override
        public void logStats(@NonNull OptimizeStats stats) {
            mOptimizeStats = stats;
        }

        @Override
        public void logStats(@NonNull SetSchemaStats stats) {
            mSetSchemaStats = stats;
        }
    }

    public static <K, V> AppSearchBatchResult<K, V> checkIsBatchResultSuccess(
            Future<AppSearchBatchResult<K, V>> future) throws Exception {
        AppSearchBatchResult<K, V> result = future.get();
        assertWithMessage("AppSearchBatchResult not successful: " + result)
                .that(result.isSuccess())
                .isTrue();
        return result;
    }

    public static List<GenericDocument> doGet(
            AppSearchSessionShim session, String namespace, String... ids) throws Exception {
        AppSearchBatchResult<String, GenericDocument> result =
                checkIsBatchResultSuccess(
                        session.getByDocumentId(
                                new GetByDocumentIdRequest.Builder(namespace).addIds(ids).build()));
        assertThat(result.getSuccesses()).hasSize(ids.length);
        assertThat(result.getFailures()).isEmpty();
        List<GenericDocument> list = new ArrayList<>(ids.length);
        for (String id : ids) {
            list.add(result.getSuccesses().get(id));
        }
        return list;
    }

    public static List<GenericDocument> doGet(
            AppSearchSessionShim session, GetByDocumentIdRequest request) throws Exception {
        AppSearchBatchResult<String, GenericDocument> result =
                checkIsBatchResultSuccess(session.getByDocumentId(request));
        Set<String> ids = request.getIds();
        assertThat(result.getSuccesses()).hasSize(ids.size());
        assertThat(result.getFailures()).isEmpty();
        List<GenericDocument> list = new ArrayList<>(ids.size());
        for (String id : ids) {
            list.add(result.getSuccesses().get(id));
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

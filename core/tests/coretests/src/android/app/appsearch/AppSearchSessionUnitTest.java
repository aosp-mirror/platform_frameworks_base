/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.app.appsearch.SearchSpec.TERM_MATCH_PREFIX;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.expectThrows;

import android.app.appsearch.exceptions.AppSearchException;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.android.server.appsearch.testing.AppSearchEmail;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

public class AppSearchSessionUnitTest {
    private final Context mContext = ApplicationProvider.getApplicationContext();
    private final AppSearchManager mAppSearch = mContext.getSystemService(AppSearchManager.class);
    private final Executor mExecutor = mContext.getMainExecutor();
    private AppSearchSession mSearchSession;

    @Before
    public void setUp() throws Exception {
        // Remove all documents from any instances that may have been created in the tests.
        Objects.requireNonNull(mAppSearch);
        AppSearchManager.SearchContext searchContext =
                new AppSearchManager.SearchContext.Builder("testDb").build();
        CompletableFuture<AppSearchResult<AppSearchSession>> future = new CompletableFuture<>();
        mAppSearch.createSearchSession(searchContext, mExecutor, future::complete);
        mSearchSession = future.get().getResultValue();

        CompletableFuture<AppSearchResult<SetSchemaResponse>> schemaFuture =
                new CompletableFuture<>();
        mSearchSession.setSchema(
                new SetSchemaRequest.Builder().setForceOverride(true).build(), mExecutor, mExecutor,
                schemaFuture::complete);

        schemaFuture.get().getResultValue();
    }

    @Test
    public void testPutDocument_throwsNullException() throws Exception {
        // Create a document
        AppSearchEmail inEmail =
                new AppSearchEmail.Builder("namespace", "uri1")
                        .setFrom("from@example.com")
                        .setTo("to1@example.com", "to2@example.com")
                        .setSubject("testPut example")
                        .setBody("This is the body of the testPut email")
                        .build();

        // clear the document bundle to make our service crash and throw an NullPointerException.
        inEmail.getBundle().clear();
        CompletableFuture<AppSearchBatchResult<String, Void>> putDocumentsFuture =
                new CompletableFuture<>();

        // Index the broken document.
        mSearchSession.put(
                new PutDocumentsRequest.Builder().addGenericDocuments(inEmail).build(),
                mExecutor, new BatchResultCallback<String, Void>() {
                    @Override
                    public void onResult(AppSearchBatchResult<String, Void> result) {
                        putDocumentsFuture.complete(result);
                    }

                    @Override
                    public void onSystemError(Throwable throwable) {
                        putDocumentsFuture.completeExceptionally(throwable);
                    }
                });

        // Verify the NullPointException has been thrown.
        ExecutionException executionException =
                expectThrows(ExecutionException.class, putDocumentsFuture::get);
        assertThat(executionException.getCause()).isInstanceOf(AppSearchException.class);
        AppSearchException appSearchException = (AppSearchException) executionException.getCause();
        assertThat(appSearchException.getResultCode())
                .isEqualTo(AppSearchResult.RESULT_INTERNAL_ERROR);
        assertThat(appSearchException.getMessage()).startsWith("NullPointerException");
    }

    @Test
    public void testGetEmptyNextPage() throws Exception {
        // Set the schema.
        CompletableFuture<AppSearchResult<SetSchemaResponse>> schemaFuture =
                new CompletableFuture<>();
        mSearchSession.setSchema(
                new SetSchemaRequest.Builder()
                        .addSchemas(new AppSearchSchema.Builder("schema1").build())
                        .setForceOverride(true).build(),
                mExecutor, mExecutor, schemaFuture::complete);
        schemaFuture.get().getResultValue();

        // Create a document and index it.
        GenericDocument document1 = new GenericDocument.Builder<>("namespace", "id1",
                "schema1").build();
        CompletableFuture<AppSearchBatchResult<String, Void>> putDocumentsFuture =
                new CompletableFuture<>();
        mSearchSession.put(
                new PutDocumentsRequest.Builder().addGenericDocuments(document1).build(),
                mExecutor, new BatchResultCallback<String, Void>() {
                    @Override
                    public void onResult(AppSearchBatchResult<String, Void> result) {
                        putDocumentsFuture.complete(result);
                    }

                    @Override
                    public void onSystemError(Throwable throwable) {
                        putDocumentsFuture.completeExceptionally(throwable);
                    }
                });
        putDocumentsFuture.get();

        // Search and get the first page.
        SearchSpec searchSpec = new SearchSpec.Builder()
                .setTermMatch(TERM_MATCH_PREFIX)
                .setResultCountPerPage(1)
                .build();
        SearchResults searchResults = mSearchSession.search("", searchSpec);

        CompletableFuture<AppSearchResult<List<SearchResult>>> getNextPageFuture =
                new CompletableFuture<>();
        searchResults.getNextPage(mExecutor, getNextPageFuture::complete);
        List<SearchResult> results = getNextPageFuture.get().getResultValue();
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getGenericDocument()).isEqualTo(document1);

        // We get all documents, and it shouldn't fail if we keep calling getNextPage().
        getNextPageFuture = new CompletableFuture<>();
        searchResults.getNextPage(mExecutor, getNextPageFuture::complete);
        results = getNextPageFuture.get().getResultValue();
        assertThat(results).isEmpty();
    }

    @Test
    public void testGetEmptyNextPage_multiPages() throws Exception {
        // Set the schema.
        CompletableFuture<AppSearchResult<SetSchemaResponse>> schemaFuture =
                new CompletableFuture<>();
        mSearchSession.setSchema(
                new SetSchemaRequest.Builder()
                        .addSchemas(new AppSearchSchema.Builder("schema1").build())
                        .setForceOverride(true).build(),
                mExecutor, mExecutor, schemaFuture::complete);
        schemaFuture.get().getResultValue();

        // Create a document and insert 3 package1 documents
        GenericDocument document1 = new GenericDocument.Builder<>("namespace", "id1",
                "schema1").build();
        GenericDocument document2 = new GenericDocument.Builder<>("namespace", "id2",
                "schema1").build();
        GenericDocument document3 = new GenericDocument.Builder<>("namespace", "id3",
                "schema1").build();
        CompletableFuture<AppSearchBatchResult<String, Void>> putDocumentsFuture =
                new CompletableFuture<>();
        mSearchSession.put(
                new PutDocumentsRequest.Builder()
                        .addGenericDocuments(document1, document2, document3).build(),
                mExecutor, new BatchResultCallback<String, Void>() {
                    @Override
                    public void onResult(AppSearchBatchResult<String, Void> result) {
                        putDocumentsFuture.complete(result);
                    }

                    @Override
                    public void onSystemError(Throwable throwable) {
                        putDocumentsFuture.completeExceptionally(throwable);
                    }
                });
        putDocumentsFuture.get();

        // Search for only 2 result per page
        SearchSpec searchSpec = new SearchSpec.Builder()
                .setTermMatch(TERM_MATCH_PREFIX)
                .setResultCountPerPage(2)
                .build();
        SearchResults searchResults = mSearchSession.search("", searchSpec);

        // Get the first page, it contains 2 results.
        List<GenericDocument> outDocs = new ArrayList<>();
        CompletableFuture<AppSearchResult<List<SearchResult>>> getNextPageFuture =
                new CompletableFuture<>();
        searchResults.getNextPage(mExecutor, getNextPageFuture::complete);
        List<SearchResult> results = getNextPageFuture.get().getResultValue();
        assertThat(results).hasSize(2);
        outDocs.add(results.get(0).getGenericDocument());
        outDocs.add(results.get(1).getGenericDocument());

        // Get the second page, it contains only 1 result.
        getNextPageFuture = new CompletableFuture<>();
        searchResults.getNextPage(mExecutor, getNextPageFuture::complete);
        results = getNextPageFuture.get().getResultValue();
        assertThat(results).hasSize(1);
        outDocs.add(results.get(0).getGenericDocument());

        assertThat(outDocs).containsExactly(document1, document2, document3);

        // We get all documents, and it shouldn't fail if we keep calling getNextPage().
        getNextPageFuture = new CompletableFuture<>();
        searchResults.getNextPage(mExecutor, getNextPageFuture::complete);
        results = getNextPageFuture.get().getResultValue();
        assertThat(results).isEmpty();
    }
}

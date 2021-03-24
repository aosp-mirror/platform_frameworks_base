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

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.expectThrows;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;

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
        AppSearchManager.SearchContext searchContext = new AppSearchManager.SearchContext.Builder()
                .setDatabaseName("testDb").build();
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
        ExecutionException executionException = expectThrows(ExecutionException.class,
                putDocumentsFuture::get);
        assertThat(executionException.getCause()).isInstanceOf(NullPointerException.class);
    }
}

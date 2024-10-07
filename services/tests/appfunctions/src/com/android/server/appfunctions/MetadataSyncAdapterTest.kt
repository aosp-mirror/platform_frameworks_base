/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.server.appfunctions

import android.app.appfunctions.AppFunctionRuntimeMetadata
import android.app.appsearch.AppSearchBatchResult
import android.app.appsearch.AppSearchManager
import android.app.appsearch.AppSearchResult
import android.app.appsearch.AppSearchSchema
import android.app.appsearch.GenericDocument
import android.app.appsearch.GetByDocumentIdRequest
import android.app.appsearch.GetSchemaResponse
import android.app.appsearch.PutDocumentsRequest
import android.app.appsearch.RemoveByDocumentIdRequest
import android.app.appsearch.SearchResult
import android.app.appsearch.SearchSpec
import android.app.appsearch.SetSchemaRequest
import android.app.appsearch.SetSchemaResponse
import android.util.ArrayMap
import android.util.ArraySet
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.android.internal.infra.AndroidFuture
import com.android.server.appfunctions.FutureAppSearchSession.FutureSearchResults
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class MetadataSyncAdapterTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val appSearchManager = context.getSystemService(AppSearchManager::class.java)
    private val packageManager = context.packageManager

    @Test
    fun getPackageToFunctionIdMap() {
        val searchSession = FakeSearchSession()
        val functionRuntimeMetadata =
            AppFunctionRuntimeMetadata.Builder(TEST_TARGET_PKG_NAME, "testFunctionId").build()
        val putDocumentsRequest: PutDocumentsRequest =
            PutDocumentsRequest.Builder().addGenericDocuments(functionRuntimeMetadata).build()
        searchSession.put(putDocumentsRequest).get()

        val packageToFunctionIdMap =
            MetadataSyncAdapter.getPackageToFunctionIdMap(
                searchSession,
                "fakeSchema",
                AppFunctionRuntimeMetadata.PROPERTY_FUNCTION_ID,
                AppFunctionRuntimeMetadata.PROPERTY_PACKAGE_NAME,
            )

        assertThat(packageToFunctionIdMap).isNotNull()
        assertThat(packageToFunctionIdMap[TEST_TARGET_PKG_NAME]).containsExactly("testFunctionId")
    }

    @Test
    fun getPackageToFunctionIdMap_multipleDocuments() {
        val searchSession = FakeSearchSession()
        val functionRuntimeMetadata =
            AppFunctionRuntimeMetadata.Builder(TEST_TARGET_PKG_NAME, "testFunctionId").build()
        val functionRuntimeMetadata1 =
            AppFunctionRuntimeMetadata.Builder(TEST_TARGET_PKG_NAME, "testFunctionId1").build()
        val functionRuntimeMetadata2 =
            AppFunctionRuntimeMetadata.Builder(TEST_TARGET_PKG_NAME, "testFunctionId2").build()
        val functionRuntimeMetadata3 =
            AppFunctionRuntimeMetadata.Builder(TEST_TARGET_PKG_NAME, "testFunctionId3").build()
        val putDocumentsRequest: PutDocumentsRequest =
            PutDocumentsRequest.Builder()
                .addGenericDocuments(
                    functionRuntimeMetadata,
                    functionRuntimeMetadata1,
                    functionRuntimeMetadata2,
                    functionRuntimeMetadata3,
                )
                .build()
        searchSession.put(putDocumentsRequest).get()

        val packageToFunctionIdMap =
            MetadataSyncAdapter.getPackageToFunctionIdMap(
                searchSession,
                AppFunctionRuntimeMetadata.RUNTIME_SCHEMA_TYPE,
                AppFunctionRuntimeMetadata.PROPERTY_FUNCTION_ID,
                AppFunctionRuntimeMetadata.PROPERTY_PACKAGE_NAME,
            )

        assertThat(packageToFunctionIdMap).isNotNull()
        assertThat(packageToFunctionIdMap[TEST_TARGET_PKG_NAME])
            .containsExactly(
                "testFunctionId",
                "testFunctionId1",
                "testFunctionId2",
                "testFunctionId3",
            )
    }

    @Test
    fun getAddedFunctionsDiffMap_noDiff() {
        val staticPackageToFunctionMap: ArrayMap<String, ArraySet<String>> = ArrayMap()
        staticPackageToFunctionMap.putAll(
            mapOf(TEST_TARGET_PKG_NAME to ArraySet(setOf("testFunction1")))
        )
        val runtimePackageToFunctionMap: ArrayMap<String, ArraySet<String>> =
            ArrayMap(staticPackageToFunctionMap)

        val addedFunctionsDiffMap =
            MetadataSyncAdapter.getAddedFunctionsDiffMap(
                staticPackageToFunctionMap,
                runtimePackageToFunctionMap,
            )

        assertThat(addedFunctionsDiffMap.isEmpty()).isEqualTo(true)
    }

    @Test
    fun syncMetadata_noDiff() {
        val runtimeSearchSession = FakeSearchSession()
        val staticSearchSession = FakeSearchSession()
        val functionRuntimeMetadata =
            AppFunctionRuntimeMetadata.Builder(TEST_TARGET_PKG_NAME, "testFunctionId").build()
        val putDocumentsRequest: PutDocumentsRequest =
            PutDocumentsRequest.Builder().addGenericDocuments(functionRuntimeMetadata).build()
        runtimeSearchSession.put(putDocumentsRequest).get()
        staticSearchSession.put(putDocumentsRequest).get()
        val metadataSyncAdapter = MetadataSyncAdapter(packageManager, appSearchManager)

        val submitSyncRequest =
            metadataSyncAdapter.trySyncAppFunctionMetadataBlocking(
                staticSearchSession,
                runtimeSearchSession,
            )

        assertThat(submitSyncRequest).isInstanceOf(Unit::class.java)
    }

    @Test
    fun getAddedFunctionsDiffMap_addedFunction() {
        val staticPackageToFunctionMap: ArrayMap<String, ArraySet<String>> = ArrayMap()
        staticPackageToFunctionMap.putAll(
            mapOf(TEST_TARGET_PKG_NAME to ArraySet(setOf("testFunction1", "testFunction2")))
        )
        val runtimePackageToFunctionMap: ArrayMap<String, ArraySet<String>> = ArrayMap()
        runtimePackageToFunctionMap.putAll(
            mapOf(TEST_TARGET_PKG_NAME to ArraySet(setOf("testFunction1")))
        )

        val addedFunctionsDiffMap =
            MetadataSyncAdapter.getAddedFunctionsDiffMap(
                staticPackageToFunctionMap,
                runtimePackageToFunctionMap,
            )

        assertThat(addedFunctionsDiffMap.size).isEqualTo(1)
        assertThat(addedFunctionsDiffMap[TEST_TARGET_PKG_NAME]).containsExactly("testFunction2")
    }

    @Test
    fun syncMetadata_addedFunction() {
        val runtimeSearchSession = FakeSearchSession()
        val staticSearchSession = FakeSearchSession()
        val functionRuntimeMetadata =
            AppFunctionRuntimeMetadata.Builder(TEST_TARGET_PKG_NAME, "testFunctionId").build()
        val putDocumentsRequest: PutDocumentsRequest =
            PutDocumentsRequest.Builder().addGenericDocuments(functionRuntimeMetadata).build()
        staticSearchSession.put(putDocumentsRequest).get()
        val metadataSyncAdapter = MetadataSyncAdapter(packageManager, appSearchManager)

        val submitSyncRequest =
            metadataSyncAdapter.trySyncAppFunctionMetadataBlocking(
                staticSearchSession,
                runtimeSearchSession,
            )

        assertThat(submitSyncRequest).isInstanceOf(Unit::class.java)
    }

    @Test
    fun getAddedFunctionsDiffMap_addedFunctionNewPackage() {
        val staticPackageToFunctionMap: ArrayMap<String, ArraySet<String>> = ArrayMap()
        staticPackageToFunctionMap.putAll(
            mapOf(TEST_TARGET_PKG_NAME to ArraySet(setOf("testFunction1")))
        )
        val runtimePackageToFunctionMap: ArrayMap<String, ArraySet<String>> = ArrayMap()

        val addedFunctionsDiffMap =
            MetadataSyncAdapter.getAddedFunctionsDiffMap(
                staticPackageToFunctionMap,
                runtimePackageToFunctionMap,
            )

        assertThat(addedFunctionsDiffMap.size).isEqualTo(1)
        assertThat(addedFunctionsDiffMap[TEST_TARGET_PKG_NAME]).containsExactly("testFunction1")
    }

    @Test
    fun getAddedFunctionsDiffMap_removedFunction() {
        val staticPackageToFunctionMap: ArrayMap<String, ArraySet<String>> = ArrayMap()
        val runtimePackageToFunctionMap: ArrayMap<String, ArraySet<String>> = ArrayMap()
        runtimePackageToFunctionMap.putAll(
            mapOf(TEST_TARGET_PKG_NAME to ArraySet(setOf("testFunction1")))
        )

        val addedFunctionsDiffMap =
            MetadataSyncAdapter.getAddedFunctionsDiffMap(
                staticPackageToFunctionMap,
                runtimePackageToFunctionMap,
            )

        assertThat(addedFunctionsDiffMap.isEmpty()).isEqualTo(true)
    }

    @Test
    fun syncMetadata_removedFunction() {
        val runtimeSearchSession = FakeSearchSession()
        val staticSearchSession = FakeSearchSession()
        val functionRuntimeMetadata =
            AppFunctionRuntimeMetadata.Builder(TEST_TARGET_PKG_NAME, "testFunctionId").build()
        val putDocumentsRequest: PutDocumentsRequest =
            PutDocumentsRequest.Builder().addGenericDocuments(functionRuntimeMetadata).build()
        runtimeSearchSession.put(putDocumentsRequest).get()
        val metadataSyncAdapter = MetadataSyncAdapter(packageManager, appSearchManager)

        val submitSyncRequest =
            metadataSyncAdapter.trySyncAppFunctionMetadataBlocking(
                staticSearchSession,
                runtimeSearchSession,
            )

        assertThat(submitSyncRequest).isInstanceOf(Unit::class.java)
    }

    @Test
    fun getRemovedFunctionsDiffMap_noDiff() {
        val staticPackageToFunctionMap: ArrayMap<String, ArraySet<String>> = ArrayMap()
        staticPackageToFunctionMap.putAll(
            mapOf(TEST_TARGET_PKG_NAME to ArraySet(setOf("testFunction1")))
        )
        val runtimePackageToFunctionMap: ArrayMap<String, ArraySet<String>> =
            ArrayMap(staticPackageToFunctionMap)

        val removedFunctionsDiffMap =
            MetadataSyncAdapter.getRemovedFunctionsDiffMap(
                staticPackageToFunctionMap,
                runtimePackageToFunctionMap,
            )

        assertThat(removedFunctionsDiffMap.isEmpty()).isEqualTo(true)
    }

    @Test
    fun getRemovedFunctionsDiffMap_removedFunction() {
        val staticPackageToFunctionMap: ArrayMap<String, ArraySet<String>> = ArrayMap()
        val runtimePackageToFunctionMap: ArrayMap<String, ArraySet<String>> = ArrayMap()
        runtimePackageToFunctionMap.putAll(
            mapOf(TEST_TARGET_PKG_NAME to ArraySet(setOf("testFunction1")))
        )

        val removedFunctionsDiffMap =
            MetadataSyncAdapter.getRemovedFunctionsDiffMap(
                staticPackageToFunctionMap,
                runtimePackageToFunctionMap,
            )

        assertThat(removedFunctionsDiffMap.size).isEqualTo(1)
        assertThat(removedFunctionsDiffMap[TEST_TARGET_PKG_NAME]).containsExactly("testFunction1")
    }

    @Test
    fun getRemovedFunctionsDiffMap_addedFunction() {
        val staticPackageToFunctionMap: ArrayMap<String, ArraySet<String>> = ArrayMap()
        staticPackageToFunctionMap.putAll(
            mapOf(TEST_TARGET_PKG_NAME to ArraySet(setOf("testFunction1")))
        )
        val runtimePackageToFunctionMap: ArrayMap<String, ArraySet<String>> = ArrayMap()

        val removedFunctionsDiffMap =
            MetadataSyncAdapter.getRemovedFunctionsDiffMap(
                staticPackageToFunctionMap,
                runtimePackageToFunctionMap,
            )

        assertThat(removedFunctionsDiffMap.isEmpty()).isEqualTo(true)
    }

    private companion object {
        const val TEST_DB: String = "test_db"
        const val TEST_TARGET_PKG_NAME = "com.android.frameworks.appfunctionstests"
    }

    class FakeSearchSession : FutureAppSearchSession {
        private val schemas: MutableSet<AppSearchSchema> = mutableSetOf()
        private val genericDocumentMutableMap: MutableMap<String, GenericDocument> = mutableMapOf()

        override fun close() {
            Log.d("FakeRuntimeMetadataSearchSession", "Closing session")
        }

        override fun setSchema(
            setSchemaRequest: SetSchemaRequest
        ): AndroidFuture<SetSchemaResponse> {
            schemas.addAll(setSchemaRequest.schemas)
            return AndroidFuture.completedFuture(SetSchemaResponse.Builder().build())
        }

        override fun getSchema(): AndroidFuture<GetSchemaResponse> {
            val resultBuilder = GetSchemaResponse.Builder()
            for (schema in schemas) {
                resultBuilder.addSchema(schema)
            }
            return AndroidFuture.completedFuture(resultBuilder.build())
        }

        override fun put(
            putDocumentsRequest: PutDocumentsRequest
        ): AndroidFuture<AppSearchBatchResult<String, Void>> {
            for (document in putDocumentsRequest.genericDocuments) {
                genericDocumentMutableMap[document.id] = document
            }
            val batchResultBuilder = AppSearchBatchResult.Builder<String, Void>()
            for (document in putDocumentsRequest.genericDocuments) {
                batchResultBuilder.setResult(document.id, AppSearchResult.newSuccessfulResult(null))
            }
            return AndroidFuture.completedFuture(batchResultBuilder.build())
        }

        override fun remove(
            removeRequest: RemoveByDocumentIdRequest
        ): AndroidFuture<AppSearchBatchResult<String, Void>> {
            for (documentId in removeRequest.ids) {
                if (!genericDocumentMutableMap.keys.contains(documentId)) {
                    throw IllegalStateException("Document $documentId does not exist")
                }
            }
            val batchResultBuilder = AppSearchBatchResult.Builder<String, Void>()
            for (id in removeRequest.ids) {
                batchResultBuilder.setResult(id, AppSearchResult.newSuccessfulResult(null))
            }
            return AndroidFuture.completedFuture(batchResultBuilder.build())
        }

        override fun getByDocumentId(
            getRequest: GetByDocumentIdRequest
        ): AndroidFuture<AppSearchBatchResult<String, GenericDocument>> {
            val batchResultBuilder = AppSearchBatchResult.Builder<String, GenericDocument>()
            for (documentId in getRequest.ids) {
                if (!genericDocumentMutableMap.keys.contains(documentId)) {
                    throw IllegalStateException("Document $documentId does not exist")
                }
                batchResultBuilder.setResult(
                    documentId,
                    AppSearchResult.newSuccessfulResult(genericDocumentMutableMap[documentId]),
                )
            }
            return AndroidFuture.completedFuture(batchResultBuilder.build())
        }

        override fun search(
            queryExpression: String,
            searchSpec: SearchSpec,
        ): AndroidFuture<FutureSearchResults> {
            val futureSearchResults =
                object : FutureSearchResults {
                    val hasNextPage = AtomicBoolean(false)

                    override fun getNextPage(): AndroidFuture<MutableList<SearchResult>> {
                        val searchResultMutableList: MutableList<SearchResult> =
                            genericDocumentMutableMap.values
                                .map {
                                    SearchResult.Builder(TEST_TARGET_PKG_NAME, TEST_DB)
                                        .setGenericDocument(it)
                                        .build()
                                }
                                .toMutableList()
                        if (!hasNextPage.get()) {
                            hasNextPage.set(true)
                            return AndroidFuture.completedFuture(searchResultMutableList)
                        } else {
                            return AndroidFuture.completedFuture(mutableListOf())
                        }
                    }
                }
            return AndroidFuture.completedFuture(futureSearchResults)
        }
    }
}

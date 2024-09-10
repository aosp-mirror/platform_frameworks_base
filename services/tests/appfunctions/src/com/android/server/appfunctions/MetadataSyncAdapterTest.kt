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
import android.app.appsearch.AppSearchManager
import android.app.appsearch.AppSearchManager.SearchContext
import android.app.appsearch.PutDocumentsRequest
import android.app.appsearch.SetSchemaRequest
import android.util.ArrayMap
import android.util.ArraySet
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class MetadataSyncAdapterTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val appSearchManager = context.getSystemService(AppSearchManager::class.java)
    private val testExecutor = MoreExecutors.directExecutor()

    @Before
    @After
    fun clearData() {
        val searchContext = SearchContext.Builder(TEST_DB).build()
        FutureAppSearchSession(appSearchManager, testExecutor, searchContext).use {
            val setSchemaRequest = SetSchemaRequest.Builder().setForceOverride(true).build()
            it.setSchema(setSchemaRequest)
        }
    }

    @Test
    fun getPackageToFunctionIdMap() {
        val searchContext: SearchContext = SearchContext.Builder(TEST_DB).build()
        val functionRuntimeMetadata =
            AppFunctionRuntimeMetadata.Builder(TEST_TARGET_PKG_NAME, "testFunctionId", "").build()
        val setSchemaRequest =
            SetSchemaRequest.Builder()
                .addSchemas(AppFunctionRuntimeMetadata.createParentAppFunctionRuntimeSchema())
                .addSchemas(
                    AppFunctionRuntimeMetadata.createAppFunctionRuntimeSchema(TEST_TARGET_PKG_NAME)
                )
                .build()
        val putDocumentsRequest: PutDocumentsRequest =
            PutDocumentsRequest.Builder().addGenericDocuments(functionRuntimeMetadata).build()
        FutureAppSearchSession(appSearchManager, testExecutor, searchContext).use {
            val setSchemaResponse = it.setSchema(setSchemaRequest).get()
            assertThat(setSchemaResponse).isNotNull()
            val appSearchBatchResult = it.put(putDocumentsRequest).get()
            assertThat(appSearchBatchResult.isSuccess).isTrue()
        }

        val metadataSyncAdapter =
            MetadataSyncAdapter(
                testExecutor,
                FutureAppSearchSession(appSearchManager, testExecutor, searchContext),
            )
        val packageToFunctionIdMap =
            metadataSyncAdapter.getPackageToFunctionIdMap(
                AppFunctionRuntimeMetadata.RUNTIME_SCHEMA_TYPE,
                AppFunctionRuntimeMetadata.PROPERTY_FUNCTION_ID,
                AppFunctionRuntimeMetadata.PROPERTY_PACKAGE_NAME,
            )

        assertThat(packageToFunctionIdMap).isNotNull()
        assertThat(packageToFunctionIdMap[TEST_TARGET_PKG_NAME]).containsExactly("testFunctionId")
    }

    @Test
    fun getPackageToFunctionIdMap_multipleDocuments() {
        val searchContext: SearchContext = SearchContext.Builder(TEST_DB).build()
        val functionRuntimeMetadata =
            AppFunctionRuntimeMetadata.Builder(TEST_TARGET_PKG_NAME, "testFunctionId", "").build()
        val functionRuntimeMetadata1 =
            AppFunctionRuntimeMetadata.Builder(TEST_TARGET_PKG_NAME, "testFunctionId1", "").build()
        val functionRuntimeMetadata2 =
            AppFunctionRuntimeMetadata.Builder(TEST_TARGET_PKG_NAME, "testFunctionId2", "").build()
        val functionRuntimeMetadata3 =
            AppFunctionRuntimeMetadata.Builder(TEST_TARGET_PKG_NAME, "testFunctionId3", "").build()
        val setSchemaRequest =
            SetSchemaRequest.Builder()
                .addSchemas(AppFunctionRuntimeMetadata.createParentAppFunctionRuntimeSchema())
                .addSchemas(
                    AppFunctionRuntimeMetadata.createAppFunctionRuntimeSchema(TEST_TARGET_PKG_NAME)
                )
                .build()
        val putDocumentsRequest: PutDocumentsRequest =
            PutDocumentsRequest.Builder()
                .addGenericDocuments(
                    functionRuntimeMetadata,
                    functionRuntimeMetadata1,
                    functionRuntimeMetadata2,
                    functionRuntimeMetadata3,
                )
                .build()
        FutureAppSearchSession(appSearchManager, testExecutor, searchContext).use {
            val setSchemaResponse = it.setSchema(setSchemaRequest).get()
            assertThat(setSchemaResponse).isNotNull()
            val appSearchBatchResult = it.put(putDocumentsRequest).get()
            assertThat(appSearchBatchResult.isSuccess).isTrue()
        }

        val metadataSyncAdapter =
            MetadataSyncAdapter(
                testExecutor,
                FutureAppSearchSession(appSearchManager, testExecutor, searchContext),
            )
        val packageToFunctionIdMap =
            metadataSyncAdapter.getPackageToFunctionIdMap(
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
}

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
import android.app.appfunctions.AppFunctionRuntimeMetadata.createAppFunctionRuntimeSchema
import android.app.appfunctions.AppFunctionRuntimeMetadata.createParentAppFunctionRuntimeSchema
import android.app.appsearch.AppSearchManager
import android.app.appsearch.PutDocumentsRequest
import android.app.appsearch.SearchSpec
import android.app.appsearch.SetSchemaRequest
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class FutureAppSearchSessionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val appSearchManager = context.getSystemService(AppSearchManager::class.java)
    private val testExecutor = MoreExecutors.directExecutor()

    @Before
    @After
    fun clearData() {
        val searchContext = AppSearchManager.SearchContext.Builder(TEST_DB).build()
        FutureAppSearchSession(appSearchManager, testExecutor, searchContext).use {
            val setSchemaRequest = SetSchemaRequest.Builder().setForceOverride(true).build()
            it.setSchema(setSchemaRequest)
        }
    }

    @Test
    fun setSchema() {
        val searchContext = AppSearchManager.SearchContext.Builder(TEST_DB).build()
        FutureAppSearchSession(appSearchManager, testExecutor, searchContext).use { session ->
            val setSchemaRequest =
                SetSchemaRequest.Builder()
                    .addSchemas(
                        createParentAppFunctionRuntimeSchema(),
                        createAppFunctionRuntimeSchema(TEST_PACKAGE_NAME)
                    )
                    .build()

            val schema = session.setSchema(setSchemaRequest)

            assertThat(schema.get()).isNotNull()
        }
    }

    @Test
    fun put() {
        val searchContext = AppSearchManager.SearchContext.Builder(TEST_DB).build()
        FutureAppSearchSession(appSearchManager, testExecutor, searchContext).use { session ->
            val setSchemaRequest =
                SetSchemaRequest.Builder()
                    .addSchemas(
                        createParentAppFunctionRuntimeSchema(),
                        createAppFunctionRuntimeSchema(TEST_PACKAGE_NAME)
                    )
                    .build()
            val schema = session.setSchema(setSchemaRequest)
            assertThat(schema.get()).isNotNull()
            val appFunctionRuntimeMetadata =
                AppFunctionRuntimeMetadata.Builder(TEST_PACKAGE_NAME, TEST_FUNCTION_ID, "").build()
            val putDocumentsRequest: PutDocumentsRequest =
                PutDocumentsRequest.Builder()
                    .addGenericDocuments(appFunctionRuntimeMetadata)
                    .build()

            val putResult = session.put(putDocumentsRequest)

            assertThat(putResult.get().isSuccess).isTrue()
        }
    }

    @Test
    fun search() {
        val searchContext = AppSearchManager.SearchContext.Builder(TEST_DB).build()
        FutureAppSearchSession(appSearchManager, testExecutor, searchContext).use { session ->
            val setSchemaRequest =
                SetSchemaRequest.Builder()
                    .addSchemas(
                        createParentAppFunctionRuntimeSchema(),
                        createAppFunctionRuntimeSchema(TEST_PACKAGE_NAME)
                    )
                    .build()
            val schema = session.setSchema(setSchemaRequest)
            assertThat(schema.get()).isNotNull()
            val appFunctionRuntimeMetadata =
                AppFunctionRuntimeMetadata.Builder(TEST_PACKAGE_NAME, TEST_FUNCTION_ID, "").build()
            val putDocumentsRequest: PutDocumentsRequest =
                PutDocumentsRequest.Builder()
                    .addGenericDocuments(appFunctionRuntimeMetadata)
                    .build()
            val putResult = session.put(putDocumentsRequest)
            assertThat(putResult.get().isSuccess).isTrue()

            val searchResult = session.search("", SearchSpec.Builder().build())

            val genericDocs =
                searchResult.get().nextPage.get().stream().map { it.genericDocument }.toList()
            assertThat(genericDocs).hasSize(1)
            val foundAppFunctionRuntimeMetadata = AppFunctionRuntimeMetadata(genericDocs[0])
            assertThat(foundAppFunctionRuntimeMetadata.functionId).isEqualTo(TEST_FUNCTION_ID)
        }
    }

    private companion object {
        const val TEST_DB: String = "test_db"
        const val TEST_PACKAGE_NAME: String = "test_pkg"
        const val TEST_FUNCTION_ID: String = "print"
    }
}

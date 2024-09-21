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
import android.app.appsearch.AppSearchManager.SearchContext
import android.app.appsearch.PutDocumentsRequest
import android.app.appsearch.SetSchemaRequest
import android.app.appsearch.observer.DocumentChangeInfo
import android.app.appsearch.observer.ObserverCallback
import android.app.appsearch.observer.ObserverSpec
import android.app.appsearch.observer.SchemaChangeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.android.internal.infra.AndroidFuture
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class FutureGlobalSearchSessionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val appSearchManager = context.getSystemService(AppSearchManager::class.java)
    private val testExecutor = MoreExecutors.directExecutor()

    @Before
    @After
    fun clearData() {
        val searchContext = SearchContext.Builder(TEST_DB).build()
        FutureAppSearchSessionImpl(appSearchManager, testExecutor, searchContext).use {
            val setSchemaRequest = SetSchemaRequest.Builder().setForceOverride(true).build()
            it.setSchema(setSchemaRequest).get()
        }
    }

    @Test
    fun registerDocumentChangeObserverCallback() {
        val packageObserverSpec: ObserverSpec =
            ObserverSpec.Builder()
                .addFilterSchemas(
                    AppFunctionRuntimeMetadata.getRuntimeSchemaNameForPackage(TEST_TARGET_PKG_NAME)
                )
                .build()
        val settableDocumentChangeInfo: AndroidFuture<DocumentChangeInfo> = AndroidFuture()
        val observer: ObserverCallback =
            object : ObserverCallback {
                override fun onSchemaChanged(changeInfo: SchemaChangeInfo) {}

                override fun onDocumentChanged(changeInfo: DocumentChangeInfo) {
                    settableDocumentChangeInfo.complete(changeInfo)
                }
            }
        val futureGlobalSearchSession = FutureGlobalSearchSession(appSearchManager, testExecutor)

        val registerPackageObserver: Void? =
            futureGlobalSearchSession
                .registerObserverCallbackAsync(
                    TEST_TARGET_PKG_NAME,
                    packageObserverSpec,
                    testExecutor,
                    observer,
                )
                .get()
        assertThat(registerPackageObserver).isNull()
        // Trigger document change
        val searchContext = SearchContext.Builder(TEST_DB).build()
        FutureAppSearchSessionImpl(appSearchManager, testExecutor, searchContext).use { session ->
            val setSchemaRequest =
                SetSchemaRequest.Builder()
                    .addSchemas(
                        createParentAppFunctionRuntimeSchema(),
                        createAppFunctionRuntimeSchema(TEST_TARGET_PKG_NAME),
                    )
                    .build()
            val schema = session.setSchema(setSchemaRequest)
            assertThat(schema.get()).isNotNull()
            val appFunctionRuntimeMetadata =
                AppFunctionRuntimeMetadata.Builder(TEST_TARGET_PKG_NAME, TEST_FUNCTION_ID).build()
            val putDocumentsRequest: PutDocumentsRequest =
                PutDocumentsRequest.Builder()
                    .addGenericDocuments(appFunctionRuntimeMetadata)
                    .build()
            val putResult = session.put(putDocumentsRequest).get()
            assertThat(putResult.isSuccess).isTrue()
        }
        assertThat(
                settableDocumentChangeInfo
                    .get()
                    .changedDocumentIds
                    .contains(
                        AppFunctionRuntimeMetadata.getDocumentIdForAppFunction(
                            TEST_TARGET_PKG_NAME,
                            TEST_FUNCTION_ID,
                        )
                    )
            )
            .isTrue()
    }

    private companion object {
        const val TEST_DB: String = "test_db"
        const val TEST_TARGET_PKG_NAME = "com.android.frameworks.appfunctionstests"
        const val TEST_FUNCTION_ID: String = "print"
    }
}

/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.wm.shell.desktopmode.education

import android.content.Context
import android.testing.AndroidTestingRunner
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.wm.shell.desktopmode.education.data.AppHandleEducationDatastoreRepository
import com.android.wm.shell.desktopmode.education.data.WindowingEducationProto
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
@ExperimentalCoroutinesApi
class AppHandleEducationDatastoreRepositoryTest {
  private val testContext: Context = InstrumentationRegistry.getInstrumentation().targetContext
  private lateinit var testDatastore: DataStore<WindowingEducationProto>
  private lateinit var datastoreRepository: AppHandleEducationDatastoreRepository
  private lateinit var datastoreScope: CoroutineScope

  @Before
  fun setUp() {
    Dispatchers.setMain(StandardTestDispatcher())
    datastoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    testDatastore =
        DataStoreFactory.create(
            serializer =
                AppHandleEducationDatastoreRepository.Companion.WindowingEducationProtoSerializer,
            scope = datastoreScope) {
              testContext.dataStoreFile(APP_HANDLE_EDUCATION_DATASTORE_TEST_FILE)
            }
    datastoreRepository = AppHandleEducationDatastoreRepository(testDatastore)
  }

  @After
  fun tearDown() {
    File(ApplicationProvider.getApplicationContext<Context>().filesDir, "datastore")
        .deleteRecursively()

    datastoreScope.cancel()
  }

  @Test
  fun getWindowingEducationProto_returnsCorrectProto() =
      runTest(StandardTestDispatcher()) {
        val windowingEducationProto =
            createWindowingEducationProto(
                educationViewedTimestampMillis = 123L,
                featureUsedTimestampMillis = 124L,
                appUsageStats = mapOf(GMAIL_PACKAGE_NAME to 2),
                appUsageStatsLastUpdateTimestampMillis = 125L)
        testDatastore.updateData { windowingEducationProto }

        val resultProto = datastoreRepository.windowingEducationProto()

        assertThat(resultProto).isEqualTo(windowingEducationProto)
      }

  private fun createWindowingEducationProto(
      educationViewedTimestampMillis: Long? = null,
      featureUsedTimestampMillis: Long? = null,
      appUsageStats: Map<String, Int>? = null,
      appUsageStatsLastUpdateTimestampMillis: Long? = null
  ): WindowingEducationProto =
      WindowingEducationProto.newBuilder()
          .apply {
            if (educationViewedTimestampMillis != null)
                setEducationViewedTimestampMillis(educationViewedTimestampMillis)
            if (featureUsedTimestampMillis != null)
                setFeatureUsedTimestampMillis(featureUsedTimestampMillis)
            setAppHandleEducation(
                createAppHandleEducationProto(
                    appUsageStats, appUsageStatsLastUpdateTimestampMillis))
          }
          .build()

  private fun createAppHandleEducationProto(
      appUsageStats: Map<String, Int>? = null,
      appUsageStatsLastUpdateTimestampMillis: Long? = null
  ): WindowingEducationProto.AppHandleEducation =
      WindowingEducationProto.AppHandleEducation.newBuilder()
          .apply {
            if (appUsageStats != null) putAllAppUsageStats(appUsageStats)
            if (appUsageStatsLastUpdateTimestampMillis != null)
                setAppUsageStatsLastUpdateTimestampMillis(appUsageStatsLastUpdateTimestampMillis)
          }
          .build()

  companion object {
    private const val GMAIL_PACKAGE_NAME = "com.google.android.gm"
    private const val APP_HANDLE_EDUCATION_DATASTORE_TEST_FILE = "app_handle_education_test.pb"
  }
}

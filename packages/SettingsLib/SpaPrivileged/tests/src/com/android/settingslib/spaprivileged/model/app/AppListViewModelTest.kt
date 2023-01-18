/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.spaprivileged.model.app

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.framework.compose.stateOf
import com.android.settingslib.spa.framework.util.mapItem
import com.android.settingslib.spa.testutils.waitUntil
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AppListViewModelTest {
    @get:Rule
    val mockito: MockitoRule = MockitoJUnit.rule()

    @Mock
    private lateinit var application: Application

    private val listModel = TestAppListModel()

    private fun createViewModel(): AppListViewModelImpl<TestAppRecord> {
        val viewModel = AppListViewModelImpl<TestAppRecord>(
            application = application,
            appListRepositoryFactory = { FakeAppListRepository },
            appRepositoryFactory = { FakeAppRepository },
        )
        viewModel.appListConfig.setIfAbsent(CONFIG)
        viewModel.listModel.setIfAbsent(listModel)
        viewModel.showSystem.setIfAbsent(false)
        viewModel.optionFlow.value = 0
        viewModel.searchQuery.setIfAbsent("")
        viewModel.reloadApps()
        return viewModel
    }

    @Test
    fun appListDataFlow() = runTest {
        val viewModel = createViewModel()

        val (appEntries, option) = viewModel.appListDataFlow.first()

        assertThat(appEntries).hasSize(1)
        assertThat(appEntries[0].record.app).isSameInstanceAs(APP)
        assertThat(appEntries[0].label).isEqualTo(LABEL)
        assertThat(option).isEqualTo(0)
    }

    @Test
    fun onFirstLoaded_calledWhenLoaded() = runTest {
        val viewModel = createViewModel()

        viewModel.appListDataFlow.first()

        waitUntil { listModel.onFirstLoadedCalled }
    }

    private object FakeAppListRepository : AppListRepository {
        override suspend fun loadApps(config: AppListConfig) = listOf(APP)

        override fun showSystemPredicate(
            userIdFlow: Flow<Int>,
            showSystemFlow: Flow<Boolean>,
        ): Flow<(app: ApplicationInfo) -> Boolean> = flowOf { true }

        override fun getSystemPackageNamesBlocking(config: AppListConfig): Set<String> = setOf()
    }

    private object FakeAppRepository : AppRepository {
        override fun loadLabel(app: ApplicationInfo) = LABEL

        @Composable
        override fun produceIcon(app: ApplicationInfo) = stateOf(null)
    }

    private companion object {
        const val USER_ID = 0
        const val PACKAGE_NAME = "package.name"
        const val LABEL = "Label"
        val CONFIG = AppListConfig(userId = USER_ID, showInstantApps = false)
        val APP = ApplicationInfo().apply {
            packageName = PACKAGE_NAME
        }
    }
}

private data class TestAppRecord(override val app: ApplicationInfo) : AppRecord

private class TestAppListModel : AppListModel<TestAppRecord> {
    var onFirstLoadedCalled = false

    override fun transform(userIdFlow: Flow<Int>, appListFlow: Flow<List<ApplicationInfo>>) =
        appListFlow.mapItem(::TestAppRecord)

    override suspend fun onFirstLoaded(recordList: List<TestAppRecord>): Boolean {
        onFirstLoadedCalled = true
        return false
    }
}

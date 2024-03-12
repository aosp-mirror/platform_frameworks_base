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

package com.android.systemui.qs.tiles.impl.custom

import android.content.ComponentName
import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.qs.external.TileLifecycleManager
import com.android.systemui.qs.external.TileServiceManager
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.impl.custom.data.repository.CustomTilePackageUpdatesRepository
import com.android.systemui.qs.tiles.impl.custom.data.repository.CustomTilePackageUpdatesRepositoryImpl
import com.android.systemui.qs.tiles.impl.custom.data.repository.FakeCustomTileDefaultsRepository
import com.android.systemui.qs.tiles.impl.custom.data.repository.FakeCustomTileDefaultsRepository.DefaultsRequest
import com.android.systemui.util.mockito.capture
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class CustomTilePackageUpdatesRepositoryTest : SysuiTestCase() {

    @Mock private lateinit var tileServiceManager: TileServiceManager

    @Captor
    private lateinit var listenerCaptor: ArgumentCaptor<TileLifecycleManager.TileChangeListener>

    private val defaultsRepository = FakeCustomTileDefaultsRepository()
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var underTest: CustomTilePackageUpdatesRepository

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        underTest =
            CustomTilePackageUpdatesRepositoryImpl(
                TileSpec.create(COMPONENT_1),
                USER,
                tileServiceManager,
                defaultsRepository,
                testScope.backgroundScope,
            )
    }

    @Test
    fun packageChangesUpdatesDefaults() =
        testScope.runTest {
            val events = mutableListOf<Unit>()
            underTest.packageChanges.onEach { events.add(it) }.launchIn(backgroundScope)
            runCurrent()
            verify(tileServiceManager).setTileChangeListener(capture(listenerCaptor))

            emitPackageChange()
            runCurrent()

            assertThat(events).hasSize(1)
            assertThat(defaultsRepository.defaultsRequests).isNotEmpty()
            assertThat(defaultsRepository.defaultsRequests.last())
                .isEqualTo(DefaultsRequest(USER, COMPONENT_1, true))
        }

    @Test
    fun packageChangesEmittedOnlyForTheTile() =
        testScope.runTest {
            val events = mutableListOf<Unit>()
            underTest.packageChanges.onEach { events.add(it) }.launchIn(backgroundScope)
            runCurrent()
            verify(tileServiceManager).setTileChangeListener(capture(listenerCaptor))

            emitPackageChange(COMPONENT_2)
            runCurrent()

            assertThat(events).isEmpty()
        }

    private fun emitPackageChange(componentName: ComponentName = COMPONENT_1) {
        listenerCaptor.value.onTileChanged(componentName)
    }

    private companion object {
        val USER = UserHandle(0)
        val COMPONENT_1 = ComponentName("pkg.test.1", "cls.test")
        val COMPONENT_2 = ComponentName("pkg.test.2", "cls.test")
    }
}

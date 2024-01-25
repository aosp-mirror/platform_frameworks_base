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

package com.android.systemui.qs.pipeline.domain.interactor

import android.platform.test.annotations.EnabledOnRavenwood
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.dump.DumpManager
import com.android.systemui.qs.pipeline.data.repository.FakeAutoAddRepository
import com.android.systemui.qs.pipeline.domain.autoaddable.FakeAutoAddable
import com.android.systemui.qs.pipeline.domain.model.AutoAddTracking
import com.android.systemui.qs.pipeline.domain.model.AutoAddable
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.pipeline.shared.logging.QSPipelineLogger
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@EnabledOnRavenwood
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AutoAddInteractorTest : SysuiTestCase() {
    private val testScope = TestScope()

    private val autoAddRepository = FakeAutoAddRepository()

    @Mock private lateinit var dumpManager: DumpManager
    @Mock private lateinit var currentTilesInteractor: CurrentTilesInteractor
    @Mock private lateinit var logger: QSPipelineLogger
    private lateinit var underTest: AutoAddInteractor

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        whenever(currentTilesInteractor.userId).thenReturn(MutableStateFlow(USER))
    }

    @Test
    fun autoAddable_alwaysTrack_addSignal_tileAddedAndMarked() =
        testScope.runTest {
            val fakeAutoAddable = FakeAutoAddable(SPEC, AutoAddTracking.Always)
            val autoAddedTiles by collectLastValue(autoAddRepository.autoAddedTiles(USER))

            underTest = createInteractor(setOf(fakeAutoAddable))

            val position = 3
            fakeAutoAddable.sendAddSignal(USER, position)
            runCurrent()

            verify(currentTilesInteractor).addTile(SPEC, position)
            assertThat(autoAddedTiles).contains(SPEC)
        }

    @Test
    fun autoAddable_alwaysTrack_addThenRemoveSignal_tileAddedAndRemoved() =
        testScope.runTest {
            val fakeAutoAddable = FakeAutoAddable(SPEC, AutoAddTracking.Always)
            val autoAddedTiles by collectLastValue(autoAddRepository.autoAddedTiles(USER))

            underTest = createInteractor(setOf(fakeAutoAddable))

            val position = 3
            fakeAutoAddable.sendAddSignal(USER, position)
            runCurrent()
            fakeAutoAddable.sendRemoveSignal(USER)
            runCurrent()

            val inOrder = inOrder(currentTilesInteractor)
            inOrder.verify(currentTilesInteractor).addTile(SPEC, position)
            inOrder.verify(currentTilesInteractor).removeTiles(setOf(SPEC))
            assertThat(autoAddedTiles).doesNotContain(SPEC)
        }

    @Test
    fun autoAddable_alwaysTrack_addSignalWhenAddedPreviously_noop() =
        testScope.runTest {
            val fakeAutoAddable = FakeAutoAddable(SPEC, AutoAddTracking.Always)
            autoAddRepository.markTileAdded(USER, SPEC)
            runCurrent()

            underTest = createInteractor(setOf(fakeAutoAddable))

            val position = 3
            fakeAutoAddable.sendAddSignal(USER, position)
            runCurrent()

            verify(currentTilesInteractor, never()).addTile(SPEC, position)
        }

    @Test
    fun autoAddable_disabled_noInteractionsWithCurrentTilesInteractor() =
        testScope.runTest {
            val fakeAutoAddable = FakeAutoAddable(SPEC, AutoAddTracking.Disabled)
            val autoAddedTiles by collectLastValue(autoAddRepository.autoAddedTiles(USER))

            underTest = createInteractor(setOf(fakeAutoAddable))

            val position = 3
            fakeAutoAddable.sendAddSignal(USER, position)
            runCurrent()
            fakeAutoAddable.sendRemoveSignal(USER)
            runCurrent()

            verify(currentTilesInteractor, never()).addTile(any(), anyInt())
            verify(currentTilesInteractor, never()).removeTiles(any())
            assertThat(autoAddedTiles).doesNotContain(SPEC)
        }

    @Test
    fun autoAddable_trackIfNotAdded_removeSignal_noop() =
        testScope.runTest {
            val fakeAutoAddable = FakeAutoAddable(SPEC, AutoAddTracking.IfNotAdded(SPEC))
            runCurrent()

            underTest = createInteractor(setOf(fakeAutoAddable))

            fakeAutoAddable.sendRemoveSignal(USER)
            runCurrent()

            verify(currentTilesInteractor, never()).addTile(any(), anyInt())
            verify(currentTilesInteractor, never()).removeTiles(any())
        }

    @Test
    fun autoAddable_trackIfNotAdded_addSignalWhenPreviouslyAdded_noop() =
        testScope.runTest {
            val fakeAutoAddable = FakeAutoAddable(SPEC, AutoAddTracking.IfNotAdded(SPEC))
            autoAddRepository.markTileAdded(USER, SPEC)
            runCurrent()

            underTest = createInteractor(setOf(fakeAutoAddable))

            fakeAutoAddable.sendAddSignal(USER)
            runCurrent()

            verify(currentTilesInteractor, never()).addTile(any(), anyInt())
            verify(currentTilesInteractor, never()).removeTiles(any())
        }

    @Test
    fun autoAddable_trackIfNotAdded_addSignal_addedAndMarked() =
        testScope.runTest {
            val fakeAutoAddable = FakeAutoAddable(SPEC, AutoAddTracking.IfNotAdded(SPEC))
            val autoAddedTiles by collectLastValue(autoAddRepository.autoAddedTiles(USER))

            underTest = createInteractor(setOf(fakeAutoAddable))

            val position = 3
            fakeAutoAddable.sendAddSignal(USER, position)
            runCurrent()

            verify(currentTilesInteractor).addTile(SPEC, position)
            assertThat(autoAddedTiles).contains(SPEC)
        }

    @Test
    fun autoAddable_removeTrackingSignal_notRemovedButUnmarked() =
        testScope.runTest {
            autoAddRepository.markTileAdded(USER, SPEC)
            val autoAddedTiles by collectLastValue(autoAddRepository.autoAddedTiles(USER))
            val fakeAutoAddable = FakeAutoAddable(SPEC, AutoAddTracking.Always)

            underTest = createInteractor(setOf(fakeAutoAddable))

            fakeAutoAddable.sendRemoveTrackingSignal(USER)
            runCurrent()

            verify(currentTilesInteractor, never()).removeTiles(any())
            assertThat(autoAddedTiles).doesNotContain(SPEC)
        }

    private fun createInteractor(autoAddables: Set<AutoAddable>): AutoAddInteractor {
        return AutoAddInteractor(
                autoAddables,
                autoAddRepository,
                dumpManager,
                logger,
                testScope.backgroundScope
            )
            .apply { init(currentTilesInteractor) }
    }

    companion object {
        private val SPEC = TileSpec.create("spec")
        private val USER = 10
    }
}

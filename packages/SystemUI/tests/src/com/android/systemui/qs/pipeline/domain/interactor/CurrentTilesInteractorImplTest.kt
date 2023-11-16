/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.pipeline.domain.interactor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.UserInfo
import android.os.UserHandle
import android.service.quicksettings.Tile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_QS_NEW_PIPELINE
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.dump.nano.SystemUIProtoDump
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.qs.QSTile.BooleanState
import com.android.systemui.qs.FakeQSFactory
import com.android.systemui.qs.external.CustomTile
import com.android.systemui.qs.external.CustomTileStatePersister
import com.android.systemui.qs.external.TileLifecycleManager
import com.android.systemui.qs.external.TileServiceKey
import com.android.systemui.qs.pipeline.data.repository.CustomTileAddedRepository
import com.android.systemui.qs.pipeline.data.repository.FakeCustomTileAddedRepository
import com.android.systemui.qs.pipeline.data.repository.FakeInstalledTilesComponentRepository
import com.android.systemui.qs.pipeline.data.repository.FakeTileSpecRepository
import com.android.systemui.qs.pipeline.data.repository.TileSpecRepository
import com.android.systemui.qs.pipeline.domain.model.TileModel
import com.android.systemui.qs.pipeline.shared.QSPipelineFlagsRepository
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.pipeline.shared.logging.QSPipelineLogger
import com.android.systemui.qs.tiles.di.NewQSTileFactory
import com.android.systemui.qs.toProto
import com.android.systemui.settings.UserTracker
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.nano.MessageNano
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CurrentTilesInteractorImplTest : SysuiTestCase() {

    private val tileSpecRepository: TileSpecRepository = FakeTileSpecRepository()
    private val userRepository = FakeUserRepository()
    private val installedTilesPackageRepository = FakeInstalledTilesComponentRepository()
    private val tileFactory = FakeQSFactory(::tileCreator)
    private val customTileAddedRepository: CustomTileAddedRepository =
        FakeCustomTileAddedRepository()
    private val featureFlags = FakeFeatureFlags()
    private val pipelineFlags = QSPipelineFlagsRepository(featureFlags)
    private val tileLifecycleManagerFactory = TLMFactory()

    @Mock private lateinit var customTileStatePersister: CustomTileStatePersister

    @Mock private lateinit var userTracker: UserTracker

    @Mock private lateinit var logger: QSPipelineLogger

    @Mock private lateinit var newQSTileFactory: NewQSTileFactory

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val unavailableTiles = mutableSetOf("e")

    private lateinit var underTest: CurrentTilesInteractorImpl

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        mSetFlagsRule.enableFlags(FLAG_QS_NEW_PIPELINE)
        // TODO(b/299909337): Add test checking the new factory is used when the flag is on
        featureFlags.set(Flags.QS_PIPELINE_NEW_TILES, true)

        userRepository.setUserInfos(listOf(USER_INFO_0, USER_INFO_1))

        setUserTracker(0)

        underTest =
            CurrentTilesInteractorImpl(
                tileSpecRepository = tileSpecRepository,
                installedTilesComponentRepository = installedTilesPackageRepository,
                userRepository = userRepository,
                customTileStatePersister = customTileStatePersister,
                tileFactory = tileFactory,
                newQSTileFactory = { newQSTileFactory },
                customTileAddedRepository = customTileAddedRepository,
                tileLifecycleManagerFactory = tileLifecycleManagerFactory,
                userTracker = userTracker,
                mainDispatcher = testDispatcher,
                backgroundDispatcher = testDispatcher,
                scope = testScope.backgroundScope,
                logger = logger,
                featureFlags = pipelineFlags,
            )
    }

    @Test
    fun initialState() =
        testScope.runTest(USER_INFO_0) {
            assertThat(underTest.currentTiles.value).isEmpty()
            assertThat(underTest.currentQSTiles).isEmpty()
            assertThat(underTest.currentTilesSpecs).isEmpty()
            assertThat(underTest.userId.value).isEqualTo(0)
            assertThat(underTest.userContext.value.userId).isEqualTo(0)
        }

    @Test
    fun correctTiles() =
        testScope.runTest(USER_INFO_0) {
            val tiles by collectLastValue(underTest.currentTiles)

            val specs =
                listOf(
                    TileSpec.create("a"),
                    TileSpec.create("e"),
                    CUSTOM_TILE_SPEC,
                    TileSpec.create("d"),
                    TileSpec.create("non_existent")
                )
            tileSpecRepository.setTiles(USER_INFO_0.id, specs)

            // check each tile

            // Tile a
            val tile0 = tiles!![0]
            assertThat(tile0.spec).isEqualTo(specs[0])
            assertThat(tile0.tile.tileSpec).isEqualTo(specs[0].spec)
            assertThat(tile0.tile).isInstanceOf(FakeQSTile::class.java)
            assertThat(tile0.tile.isAvailable).isTrue()

            // Tile e is not available and is not in the list

            // Custom Tile
            val tile1 = tiles!![1]
            assertThat(tile1.spec).isEqualTo(specs[2])
            assertThat(tile1.tile.tileSpec).isEqualTo(specs[2].spec)
            assertThat(tile1.tile).isInstanceOf(CustomTile::class.java)
            assertThat(tile1.tile.isAvailable).isTrue()

            // Tile d
            val tile2 = tiles!![2]
            assertThat(tile2.spec).isEqualTo(specs[3])
            assertThat(tile2.tile.tileSpec).isEqualTo(specs[3].spec)
            assertThat(tile2.tile).isInstanceOf(FakeQSTile::class.java)
            assertThat(tile2.tile.isAvailable).isTrue()

            // Tile non-existent shouldn't be created. Therefore, only 3 tiles total
            assertThat(tiles?.size).isEqualTo(3)
        }

    @Test
    fun logTileCreated() =
        testScope.runTest(USER_INFO_0) {
            val specs =
                listOf(
                    TileSpec.create("a"),
                    CUSTOM_TILE_SPEC,
                )
            tileSpecRepository.setTiles(USER_INFO_0.id, specs)
            runCurrent()

            specs.forEach { verify(logger).logTileCreated(it) }
        }

    @Test
    fun logTileNotFoundInFactory() =
        testScope.runTest(USER_INFO_0) {
            val specs =
                listOf(
                    TileSpec.create("non_existing"),
                )
            tileSpecRepository.setTiles(USER_INFO_0.id, specs)
            runCurrent()

            verify(logger, never()).logTileCreated(any())
            verify(logger).logTileNotFoundInFactory(specs[0])
        }

    @Test
    fun tileNotAvailableDestroyed_logged() =
        testScope.runTest(USER_INFO_0) {
            val specs =
                listOf(
                    TileSpec.create("e"),
                )
            tileSpecRepository.setTiles(USER_INFO_0.id, specs)
            runCurrent()

            verify(logger, never()).logTileCreated(any())
            verify(logger)
                .logTileDestroyed(
                    specs[0],
                    QSPipelineLogger.TileDestroyedReason.NEW_TILE_NOT_AVAILABLE
                )
        }

    @Test
    fun someTilesNotValid_repositorySetToDefinitiveList() =
        testScope.runTest(USER_INFO_0) {
            val tiles by collectLastValue(tileSpecRepository.tilesSpecs(USER_INFO_0.id))

            val specs =
                listOf(
                    TileSpec.create("a"),
                    TileSpec.create("e"),
                )
            tileSpecRepository.setTiles(USER_INFO_0.id, specs)

            assertThat(tiles).isEqualTo(listOf(TileSpec.create("a")))
        }

    @Test
    fun deduplicatedTiles() =
        testScope.runTest(USER_INFO_0) {
            val tiles by collectLastValue(underTest.currentTiles)

            val specs = listOf(TileSpec.create("a"), TileSpec.create("a"))

            tileSpecRepository.setTiles(USER_INFO_0.id, specs)

            assertThat(tiles?.size).isEqualTo(1)
            assertThat(tiles!![0].spec).isEqualTo(specs[0])
        }

    @Test
    fun tilesChange_platformTileNotRecreated() =
        testScope.runTest(USER_INFO_0) {
            val tiles by collectLastValue(underTest.currentTiles)

            val specs =
                listOf(
                    TileSpec.create("a"),
                )

            tileSpecRepository.setTiles(USER_INFO_0.id, specs)
            val originalTileA = tiles!![0].tile

            tileSpecRepository.addTile(USER_INFO_0.id, TileSpec.create("b"))

            assertThat(tiles?.size).isEqualTo(2)
            assertThat(tiles!![0].tile).isSameInstanceAs(originalTileA)
        }

    @Test
    fun tileRemovedIsDestroyed() =
        testScope.runTest(USER_INFO_0) {
            val tiles by collectLastValue(underTest.currentTiles)

            val specs = listOf(TileSpec.create("a"), TileSpec.create("c"))

            tileSpecRepository.setTiles(USER_INFO_0.id, specs)
            val originalTileC = tiles!![1].tile

            tileSpecRepository.removeTiles(USER_INFO_0.id, listOf(TileSpec.create("c")))

            assertThat(tiles?.size).isEqualTo(1)
            assertThat(tiles!![0].spec).isEqualTo(TileSpec.create("a"))

            assertThat((originalTileC as FakeQSTile).destroyed).isTrue()
            verify(logger)
                .logTileDestroyed(
                    TileSpec.create("c"),
                    QSPipelineLogger.TileDestroyedReason.TILE_REMOVED
                )
        }

    @Test
    fun tileBecomesNotAvailable_destroyed() =
        testScope.runTest(USER_INFO_0) {
            val tiles by collectLastValue(underTest.currentTiles)
            val repoTiles by collectLastValue(tileSpecRepository.tilesSpecs(USER_INFO_0.id))

            val specs = listOf(TileSpec.create("a"))

            tileSpecRepository.setTiles(USER_INFO_0.id, specs)
            val originalTileA = tiles!![0].tile

            // Tile becomes unavailable
            (originalTileA as FakeQSTile).available = false
            unavailableTiles.add("a")
            // and there is some change in the specs
            tileSpecRepository.addTile(USER_INFO_0.id, TileSpec.create("b"))
            runCurrent()

            assertThat(originalTileA.destroyed).isTrue()
            verify(logger)
                .logTileDestroyed(
                    TileSpec.create("a"),
                    QSPipelineLogger.TileDestroyedReason.EXISTING_TILE_NOT_AVAILABLE
                )

            assertThat(tiles?.size).isEqualTo(1)
            assertThat(tiles!![0].spec).isEqualTo(TileSpec.create("b"))
            assertThat(tiles!![0].tile).isNotSameInstanceAs(originalTileA)

            assertThat(repoTiles).isEqualTo(tiles!!.map(TileModel::spec))
        }

    @Test
    fun userChange_tilesChange() =
        testScope.runTest(USER_INFO_0) {
            val tiles by collectLastValue(underTest.currentTiles)

            val specs0 = listOf(TileSpec.create("a"))
            val specs1 = listOf(TileSpec.create("b"))
            tileSpecRepository.setTiles(USER_INFO_0.id, specs0)
            tileSpecRepository.setTiles(USER_INFO_1.id, specs1)

            switchUser(USER_INFO_1)

            assertThat(tiles!![0].spec).isEqualTo(specs1[0])
            assertThat(tiles!![0].tile.tileSpec).isEqualTo(specs1[0].spec)
        }

    @Test
    fun tileNotPresentInSecondaryUser_destroyedInUserChange() =
        testScope.runTest(USER_INFO_0) {
            val tiles by collectLastValue(underTest.currentTiles)

            val specs0 = listOf(TileSpec.create("a"))
            val specs1 = listOf(TileSpec.create("b"))
            tileSpecRepository.setTiles(USER_INFO_0.id, specs0)
            tileSpecRepository.setTiles(USER_INFO_1.id, specs1)

            val originalTileA = tiles!![0].tile

            switchUser(USER_INFO_1)
            runCurrent()

            assertThat((originalTileA as FakeQSTile).destroyed).isTrue()
            verify(logger)
                .logTileDestroyed(
                    specs0[0],
                    QSPipelineLogger.TileDestroyedReason.TILE_NOT_PRESENT_IN_NEW_USER
                )
        }

    @Test
    fun userChange_customTileDestroyed_lifecycleNotTerminated() {
        testScope.runTest(USER_INFO_0) {
            val tiles by collectLastValue(underTest.currentTiles)

            val specs = listOf(CUSTOM_TILE_SPEC)
            tileSpecRepository.setTiles(USER_INFO_0.id, specs)
            tileSpecRepository.setTiles(USER_INFO_1.id, specs)

            val originalCustomTile = tiles!![0].tile

            switchUser(USER_INFO_1)
            runCurrent()

            verify(originalCustomTile).destroy()
            assertThat(tileLifecycleManagerFactory.created).isEmpty()
        }
    }

    @Test
    fun userChange_sameTileUserChanged() =
        testScope.runTest(USER_INFO_0) {
            val tiles by collectLastValue(underTest.currentTiles)

            val specs = listOf(TileSpec.create("a"))
            tileSpecRepository.setTiles(USER_INFO_0.id, specs)
            tileSpecRepository.setTiles(USER_INFO_1.id, specs)

            val originalTileA = tiles!![0].tile as FakeQSTile
            assertThat(originalTileA.user).isEqualTo(USER_INFO_0.id)

            switchUser(USER_INFO_1)
            runCurrent()

            assertThat(tiles!![0].tile).isSameInstanceAs(originalTileA)
            assertThat(originalTileA.user).isEqualTo(USER_INFO_1.id)
            verify(logger).logTileUserChanged(specs[0], USER_INFO_1.id)
        }

    @Test
    fun addTile() =
        testScope.runTest(USER_INFO_0) {
            val tiles by collectLastValue(tileSpecRepository.tilesSpecs(USER_INFO_0.id))
            val spec = TileSpec.create("a")
            val currentSpecs =
                listOf(
                    TileSpec.create("b"),
                    TileSpec.create("c"),
                )
            tileSpecRepository.setTiles(USER_INFO_0.id, currentSpecs)

            underTest.addTile(spec, position = 1)

            val expectedSpecs =
                listOf(
                    TileSpec.create("b"),
                    spec,
                    TileSpec.create("c"),
                )
            assertThat(tiles).isEqualTo(expectedSpecs)
        }

    @Test
    fun addTile_currentUser() =
        testScope.runTest(USER_INFO_1) {
            val tiles0 by collectLastValue(tileSpecRepository.tilesSpecs(USER_INFO_0.id))
            val tiles1 by collectLastValue(tileSpecRepository.tilesSpecs(USER_INFO_1.id))
            val spec = TileSpec.create("a")
            val currentSpecs =
                listOf(
                    TileSpec.create("b"),
                    TileSpec.create("c"),
                )
            tileSpecRepository.setTiles(USER_INFO_0.id, currentSpecs)
            tileSpecRepository.setTiles(USER_INFO_1.id, currentSpecs)

            switchUser(USER_INFO_1)
            underTest.addTile(spec, position = 1)

            assertThat(tiles0).isEqualTo(currentSpecs)

            val expectedSpecs =
                listOf(
                    TileSpec.create("b"),
                    spec,
                    TileSpec.create("c"),
                )
            assertThat(tiles1).isEqualTo(expectedSpecs)
        }

    @Test
    fun removeTile_platform() =
        testScope.runTest(USER_INFO_0) {
            val tiles by collectLastValue(tileSpecRepository.tilesSpecs(USER_INFO_0.id))

            val specs = listOf(TileSpec.create("a"), TileSpec.create("b"))
            tileSpecRepository.setTiles(USER_INFO_0.id, specs)
            runCurrent()

            underTest.removeTiles(specs.subList(0, 1))

            assertThat(tiles).isEqualTo(specs.subList(1, 2))
        }

    @Test
    fun removeTile_customTile_lifecycleEnded() {
        testScope.runTest(USER_INFO_0) {
            val tiles by collectLastValue(tileSpecRepository.tilesSpecs(USER_INFO_0.id))

            val specs = listOf(TileSpec.create("a"), CUSTOM_TILE_SPEC)
            tileSpecRepository.setTiles(USER_INFO_0.id, specs)
            runCurrent()
            assertThat(customTileAddedRepository.isTileAdded(TEST_COMPONENT, USER_INFO_0.id))
                .isTrue()

            underTest.removeTiles(listOf(CUSTOM_TILE_SPEC))

            assertThat(tiles).isEqualTo(specs.subList(0, 1))

            val tileLifecycleManager =
                tileLifecycleManagerFactory.created[USER_INFO_0.id to TEST_COMPONENT]
            assertThat(tileLifecycleManager).isNotNull()

            with(inOrder(tileLifecycleManager!!)) {
                verify(tileLifecycleManager).onStopListening()
                verify(tileLifecycleManager).onTileRemoved()
                verify(tileLifecycleManager).flushMessagesAndUnbind()
            }
            assertThat(customTileAddedRepository.isTileAdded(TEST_COMPONENT, USER_INFO_0.id))
                .isFalse()
            verify(customTileStatePersister)
                .removeState(TileServiceKey(TEST_COMPONENT, USER_INFO_0.id))
        }
    }

    @Test
    fun removeTiles_currentUser() =
        testScope.runTest {
            val tiles0 by collectLastValue(tileSpecRepository.tilesSpecs(USER_INFO_0.id))
            val tiles1 by collectLastValue(tileSpecRepository.tilesSpecs(USER_INFO_1.id))
            val currentSpecs =
                listOf(
                    TileSpec.create("a"),
                    TileSpec.create("b"),
                    TileSpec.create("c"),
                )
            tileSpecRepository.setTiles(USER_INFO_0.id, currentSpecs)
            tileSpecRepository.setTiles(USER_INFO_1.id, currentSpecs)

            switchUser(USER_INFO_1)
            runCurrent()

            underTest.removeTiles(currentSpecs.subList(0, 2))

            assertThat(tiles0).isEqualTo(currentSpecs)
            assertThat(tiles1).isEqualTo(currentSpecs.subList(2, 3))
        }

    @Test
    fun setTiles() =
        testScope.runTest(USER_INFO_0) {
            val tiles by collectLastValue(tileSpecRepository.tilesSpecs(USER_INFO_0.id))

            val currentSpecs = listOf(TileSpec.create("a"), TileSpec.create("b"))
            tileSpecRepository.setTiles(USER_INFO_0.id, currentSpecs)
            runCurrent()

            val newSpecs = listOf(TileSpec.create("b"), TileSpec.create("c"), TileSpec.create("a"))
            underTest.setTiles(newSpecs)
            runCurrent()

            assertThat(tiles).isEqualTo(newSpecs)
        }

    @Test
    fun setTiles_customTiles_lifecycleEndedIfGone() =
        testScope.runTest(USER_INFO_0) {
            val otherCustomTileSpec = TileSpec.create("custom(b/c)")

            val currentSpecs = listOf(CUSTOM_TILE_SPEC, TileSpec.create("a"), otherCustomTileSpec)
            tileSpecRepository.setTiles(USER_INFO_0.id, currentSpecs)
            runCurrent()

            val newSpecs =
                listOf(
                    otherCustomTileSpec,
                    TileSpec.create("a"),
                )

            underTest.setTiles(newSpecs)
            runCurrent()

            val tileLifecycleManager =
                tileLifecycleManagerFactory.created[USER_INFO_0.id to TEST_COMPONENT]!!

            with(inOrder(tileLifecycleManager)) {
                verify(tileLifecycleManager).onStopListening()
                verify(tileLifecycleManager).onTileRemoved()
                verify(tileLifecycleManager).flushMessagesAndUnbind()
            }
            assertThat(customTileAddedRepository.isTileAdded(TEST_COMPONENT, USER_INFO_0.id))
                .isFalse()
            verify(customTileStatePersister)
                .removeState(TileServiceKey(TEST_COMPONENT, USER_INFO_0.id))
        }

    @Test
    fun protoDump() =
        testScope.runTest(USER_INFO_0) {
            val tiles by collectLastValue(underTest.currentTiles)
            val specs = listOf(TileSpec.create("a"), CUSTOM_TILE_SPEC)

            tileSpecRepository.setTiles(USER_INFO_0.id, specs)

            val stateA = tiles!![0].tile.state
            stateA.fillIn(Tile.STATE_INACTIVE, "A", "AA")
            val stateCustom = QSTile.BooleanState()
            stateCustom.fillIn(Tile.STATE_ACTIVE, "B", "BB")
            stateCustom.spec = CUSTOM_TILE_SPEC.spec
            whenever(tiles!![1].tile.state).thenReturn(stateCustom)

            val proto = SystemUIProtoDump()
            underTest.dumpProto(proto, emptyArray())

            assertThat(MessageNano.messageNanoEquals(proto.tiles[0], stateA.toProto())).isTrue()
            assertThat(MessageNano.messageNanoEquals(proto.tiles[1], stateCustom.toProto()))
                .isTrue()
        }

    @Test
    fun retainedTiles_callbackNotRemoved() =
        testScope.runTest(USER_INFO_0) {
            val tiles by collectLastValue(underTest.currentTiles)
            tileSpecRepository.setTiles(USER_INFO_0.id, listOf(TileSpec.create("a")))

            val tileA = tiles!![0].tile
            val callback = mock<QSTile.Callback>()
            tileA.addCallback(callback)

            tileSpecRepository.setTiles(
                USER_INFO_0.id,
                listOf(TileSpec.create("a"), CUSTOM_TILE_SPEC)
            )
            val newTileA = tiles!![0].tile
            assertThat(tileA).isSameInstanceAs(newTileA)

            assertThat((tileA as FakeQSTile).callbacks).containsExactly(callback)
        }

    @Test
    fun packageNotInstalled_customTileNotVisible() =
        testScope.runTest(USER_INFO_0) {
            installedTilesPackageRepository.setInstalledPackagesForUser(USER_INFO_0.id, emptySet())

            val tiles by collectLastValue(underTest.currentTiles)

            val specs = listOf(TileSpec.create("a"), CUSTOM_TILE_SPEC)
            tileSpecRepository.setTiles(USER_INFO_0.id, specs)

            assertThat(tiles!!.size).isEqualTo(1)
            assertThat(tiles!![0].spec).isEqualTo(specs[0])
        }

    @Test
    fun packageInstalledLater_customTileAdded() =
        testScope.runTest(USER_INFO_0) {
            installedTilesPackageRepository.setInstalledPackagesForUser(USER_INFO_0.id, emptySet())

            val tiles by collectLastValue(underTest.currentTiles)
            val specs = listOf(TileSpec.create("a"), CUSTOM_TILE_SPEC, TileSpec.create("b"))
            tileSpecRepository.setTiles(USER_INFO_0.id, specs)

            assertThat(tiles!!.size).isEqualTo(2)

            installedTilesPackageRepository.setInstalledPackagesForUser(
                USER_INFO_0.id,
                setOf(TEST_COMPONENT)
            )

            assertThat(tiles!!.size).isEqualTo(3)
            assertThat(tiles!![1].spec).isEqualTo(CUSTOM_TILE_SPEC)
        }

    @Test
    fun tileAddedOnEmptyList_blocked() =
        testScope.runTest(USER_INFO_0) {
            val tiles by collectLastValue(underTest.currentTiles)
            val specs = listOf(TileSpec.create("a"), TileSpec.create("b"))
            val newTile = TileSpec.create("c")

            underTest.addTile(newTile)

            assertThat(tiles!!.isEmpty()).isTrue()

            tileSpecRepository.setTiles(USER_INFO_0.id, specs)

            assertThat(tiles!!.size).isEqualTo(3)
        }

    private fun QSTile.State.fillIn(state: Int, label: CharSequence, secondaryLabel: CharSequence) {
        this.state = state
        this.label = label
        this.secondaryLabel = secondaryLabel
        if (this is BooleanState) {
            value = state == Tile.STATE_ACTIVE
        }
    }

    private fun tileCreator(spec: String): QSTile? {
        val currentUser = userTracker.userId
        return when (spec) {
            CUSTOM_TILE_SPEC.spec ->
                mock<CustomTile> {
                    var tileSpecReference: String? = null
                    whenever(user).thenReturn(currentUser)
                    whenever(component).thenReturn(CUSTOM_TILE_SPEC.componentName)
                    whenever(isAvailable).thenReturn(true)
                    whenever(setTileSpec(anyString())).thenAnswer {
                        tileSpecReference = it.arguments[0] as? String
                        Unit
                    }
                    whenever(tileSpec).thenAnswer { tileSpecReference }
                    // Also, add it to the set of added tiles (as this happens as part of the tile
                    // creation).
                    customTileAddedRepository.setTileAdded(
                        CUSTOM_TILE_SPEC.componentName,
                        currentUser,
                        true
                    )
                }
            in VALID_TILES -> FakeQSTile(currentUser, available = spec !in unavailableTiles)
            else -> null
        }
    }

    private fun TestScope.runTest(user: UserInfo, body: suspend TestScope.() -> Unit) {
        return runTest {
            switchUser(user)
            body()
        }
    }

    private suspend fun switchUser(user: UserInfo) {
        setUserTracker(user.id)
        installedTilesPackageRepository.setInstalledPackagesForUser(user.id, setOf(TEST_COMPONENT))
        userRepository.setSelectedUserInfo(user)
    }

    private fun setUserTracker(user: Int) {
        val mockContext = mockUserContext(user)
        whenever(userTracker.userContext).thenReturn(mockContext)
        whenever(userTracker.userId).thenReturn(user)
    }

    private class TLMFactory : TileLifecycleManager.Factory {

        val created = mutableMapOf<Pair<Int, ComponentName>, TileLifecycleManager>()

        override fun create(intent: Intent, userHandle: UserHandle): TileLifecycleManager {
            val componentName = intent.component!!
            val user = userHandle.identifier
            val manager: TileLifecycleManager = mock()
            created[user to componentName] = manager
            return manager
        }
    }

    private fun mockUserContext(user: Int): Context {
        return mock {
            whenever(this.userId).thenReturn(user)
            whenever(this.user).thenReturn(UserHandle.of(user))
        }
    }

    companion object {
        private val USER_INFO_0 = UserInfo().apply { id = 0 }
        private val USER_INFO_1 = UserInfo().apply { id = 1 }

        private val VALID_TILES = setOf("a", "b", "c", "d", "e")
        private val TEST_COMPONENT = ComponentName("pkg", "cls")
        private val CUSTOM_TILE_SPEC = TileSpec.Companion.create(TEST_COMPONENT)
    }
}

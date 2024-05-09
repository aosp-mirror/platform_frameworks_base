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

package com.android.systemui.qs.panels.ui.viewmodel

import android.R
import android.content.ComponentName
import android.graphics.drawable.TestStubDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.Text
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testScope
import com.android.systemui.qs.FakeQSFactory
import com.android.systemui.qs.FakeQSTile
import com.android.systemui.qs.panels.data.repository.stockTilesRepository
import com.android.systemui.qs.panels.domain.interactor.editTilesListInteractor
import com.android.systemui.qs.panels.domain.interactor.gridLayoutMap
import com.android.systemui.qs.panels.domain.interactor.gridLayoutTypeInteractor
import com.android.systemui.qs.panels.domain.interactor.infiniteGridLayout
import com.android.systemui.qs.panels.shared.model.EditTileData
import com.android.systemui.qs.pipeline.data.repository.FakeInstalledTilesComponentRepository
import com.android.systemui.qs.pipeline.data.repository.MinimumTilesFixedRepository
import com.android.systemui.qs.pipeline.data.repository.fakeInstalledTilesRepository
import com.android.systemui.qs.pipeline.data.repository.fakeMinimumTilesRepository
import com.android.systemui.qs.pipeline.domain.interactor.currentTilesInteractor
import com.android.systemui.qs.pipeline.domain.interactor.minimumTilesInteractor
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.qsTileFactory
import com.android.systemui.qs.tiles.impl.alarm.qsAlarmTileConfig
import com.android.systemui.qs.tiles.impl.battery.qsBatterySaverTileConfig
import com.android.systemui.qs.tiles.impl.flashlight.qsFlashlightTileConfig
import com.android.systemui.qs.tiles.impl.internet.qsInternetTileConfig
import com.android.systemui.qs.tiles.impl.sensorprivacy.qsCameraSensorPrivacyToggleTileConfig
import com.android.systemui.qs.tiles.impl.sensorprivacy.qsMicrophoneSensorPrivacyToggleTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileConfig
import com.android.systemui.qs.tiles.viewmodel.fakeQSTileConfigProvider
import com.android.systemui.qs.tiles.viewmodel.qSTileConfigProvider
import com.android.systemui.settings.userTracker
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class EditModeViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()

    // Only have some configurations so we can test the effect of missing configurations.
    // As the configurations are injected by dagger, we'll have all the existing configurations
    private val configs =
        with(kosmos) {
            setOf(
                qsInternetTileConfig,
                qsFlashlightTileConfig,
                qsBatterySaverTileConfig,
                qsAlarmTileConfig,
                qsCameraSensorPrivacyToggleTileConfig,
                qsMicrophoneSensorPrivacyToggleTileConfig,
            )
        }

    private val serviceInfo1 =
        FakeInstalledTilesComponentRepository.ServiceInfo(
            component1,
            tileService1,
            drawable1,
            appName1,
        )

    private val serviceInfo2 =
        FakeInstalledTilesComponentRepository.ServiceInfo(
            component2,
            tileService2,
            drawable2,
            appName2,
        )

    private val underTest: EditModeViewModel by lazy {
        with(kosmos) {
            EditModeViewModel(
                editTilesListInteractor,
                currentTilesInteractor,
                minimumTilesInteractor,
                infiniteGridLayout,
                applicationCoroutineScope,
                gridLayoutTypeInteractor,
                gridLayoutMap,
            )
        }
    }

    @Before
    fun setUp() {
        with(kosmos) {
            fakeMinimumTilesRepository = MinimumTilesFixedRepository(minNumberOfTiles)

            fakeInstalledTilesRepository.setInstalledServicesForUser(
                userTracker.userId,
                listOf(serviceInfo1, serviceInfo2)
            )

            with(fakeQSTileConfigProvider) { configs.forEach { putConfig(it.tileSpec, it) } }
            qsTileFactory = FakeQSFactory { FakeQSTile(userTracker.userId, available = true) }
        }
    }

    @Test
    fun isEditing() =
        with(kosmos) {
            testScope.runTest {
                val isEditing by collectLastValue(underTest.isEditing)

                assertThat(isEditing).isFalse()

                underTest.startEditing()
                assertThat(isEditing).isTrue()

                underTest.stopEditing()
                assertThat(isEditing).isFalse()
            }
        }

    @Test
    fun editing_false_emptyFlowOfTiles() =
        with(kosmos) {
            testScope.runTest {
                val tiles by collectLastValue(underTest.tiles)

                assertThat(tiles).isNull()
            }
        }

    @Test
    fun editing_true_notEmptyTileData() =
        with(kosmos) {
            testScope.runTest {
                val tiles by collectLastValue(underTest.tiles)

                underTest.startEditing()

                assertThat(tiles).isNotEmpty()
            }
        }

    @Test
    fun tilesData_hasAllStockTiles() =
        with(kosmos) {
            testScope.runTest {
                val tiles by collectLastValue(underTest.tiles)

                underTest.startEditing()

                assertThat(
                        tiles!!
                            .filter { it.tileSpec is TileSpec.PlatformTileSpec }
                            .map { it.tileSpec }
                    )
                    .containsExactlyElementsIn(stockTilesRepository.stockTiles)
            }
        }

    @Test
    fun tilesData_stockTiles_haveCorrectUiValues() =
        with(kosmos) {
            testScope.runTest {
                val tiles by collectLastValue(underTest.tiles)

                underTest.startEditing()

                tiles!!
                    .filter { it.tileSpec is TileSpec.PlatformTileSpec }
                    .forEach {
                        val data = getEditTileData(it.tileSpec)

                        assertThat(it.label).isEqualTo(data.label)
                        assertThat(it.icon).isEqualTo(data.icon)
                        assertThat(it.appName).isNull()
                    }
            }
        }

    @Test
    fun tilesData_hasAllCustomTiles() =
        with(kosmos) {
            testScope.runTest {
                val tiles by collectLastValue(underTest.tiles)

                underTest.startEditing()

                assertThat(
                        tiles!!
                            .filter { it.tileSpec is TileSpec.CustomTileSpec }
                            .map { it.tileSpec }
                    )
                    .containsExactly(TileSpec.create(component1), TileSpec.create(component2))
            }
        }

    @Test
    fun tilesData_customTiles_haveCorrectUiValues() =
        with(kosmos) {
            testScope.runTest {
                val tiles by collectLastValue(underTest.tiles)

                underTest.startEditing()

                // service1
                val model1 = tiles!!.first { it.tileSpec == TileSpec.create(component1) }
                assertThat(model1.label).isEqualTo(Text.Loaded(tileService1))
                assertThat(model1.appName).isEqualTo(Text.Loaded(appName1))
                assertThat(model1.icon)
                    .isEqualTo(Icon.Loaded(drawable1, ContentDescription.Loaded(tileService1)))

                // service2
                val model2 = tiles!!.first { it.tileSpec == TileSpec.create(component2) }
                assertThat(model2.label).isEqualTo(Text.Loaded(tileService2))
                assertThat(model2.appName).isEqualTo(Text.Loaded(appName2))
                assertThat(model2.icon)
                    .isEqualTo(Icon.Loaded(drawable2, ContentDescription.Loaded(tileService2)))
            }
        }

    @Test
    fun currentTiles_inCorrectOrder_markedAsCurrent() =
        with(kosmos) {
            testScope.runTest {
                val tiles by collectLastValue(underTest.tiles)
                val currentTiles =
                    listOf(
                        TileSpec.create("flashlight"),
                        TileSpec.create("airplane"),
                        TileSpec.create(component2),
                        TileSpec.create("alarm"),
                    )
                currentTilesInteractor.setTiles(currentTiles)

                underTest.startEditing()

                assertThat(tiles!!.filter { it.isCurrent }.map { it.tileSpec })
                    .containsExactlyElementsIn(currentTiles)
                    .inOrder()
            }
        }

    @Test
    fun notCurrentTiles() =
        with(kosmos) {
            testScope.runTest {
                val tiles by collectLastValue(underTest.tiles)
                val currentTiles =
                    listOf(
                        TileSpec.create("flashlight"),
                        TileSpec.create("airplane"),
                        TileSpec.create(component2),
                        TileSpec.create("alarm"),
                    )
                val remainingTiles =
                    stockTilesRepository.stockTiles.filterNot { it in currentTiles } +
                        listOf(TileSpec.create(component1))
                currentTilesInteractor.setTiles(currentTiles)

                underTest.startEditing()

                assertThat(tiles!!.filterNot { it.isCurrent }.map { it.tileSpec })
                    .containsExactlyElementsIn(remainingTiles)
            }
        }

    @Test
    fun currentTilesChange_trackingChange() =
        with(kosmos) {
            testScope.runTest {
                val tiles by collectLastValue(underTest.tiles)
                val currentTiles =
                    mutableListOf(
                        TileSpec.create("flashlight"),
                        TileSpec.create("airplane"),
                        TileSpec.create(component2),
                        TileSpec.create("alarm"),
                    )
                currentTilesInteractor.setTiles(currentTiles)

                underTest.startEditing()

                val newTile = TileSpec.create("internet")
                val position = 1
                currentTilesInteractor.addTile(newTile, position)
                currentTiles.add(position, newTile)

                assertThat(tiles!!.filter { it.isCurrent }.map { it.tileSpec })
                    .containsExactlyElementsIn(currentTiles)
                    .inOrder()
            }
        }

    @Test
    fun nonCurrentTiles_orderPreservedWhenCurrentTilesChange() =
        with(kosmos) {
            testScope.runTest {
                val tiles by collectLastValue(underTest.tiles)
                val currentTiles =
                    mutableListOf(
                        TileSpec.create("flashlight"),
                        TileSpec.create("airplane"),
                        TileSpec.create(component2),
                        TileSpec.create("alarm"),
                    )
                currentTilesInteractor.setTiles(currentTiles)

                underTest.startEditing()

                val nonCurrentSpecs = tiles!!.filterNot { it.isCurrent }.map { it.tileSpec }
                val newTile = TileSpec.create("internet")
                currentTilesInteractor.addTile(newTile)

                assertThat(tiles!!.filterNot { it.isCurrent }.map { it.tileSpec })
                    .containsExactlyElementsIn(nonCurrentSpecs - listOf(newTile))
                    .inOrder()
            }
        }

    @Test
    fun nonCurrentTiles_haveOnlyAddAction() =
        with(kosmos) {
            testScope.runTest {
                val tiles by collectLastValue(underTest.tiles)
                val currentTiles =
                    mutableListOf(
                        TileSpec.create("flashlight"),
                        TileSpec.create("airplane"),
                        TileSpec.create(component2),
                        TileSpec.create("alarm"),
                    )
                currentTilesInteractor.setTiles(currentTiles)

                underTest.startEditing()

                tiles!!
                    .filterNot { it.isCurrent }
                    .forEach {
                        assertThat(it.availableEditActions)
                            .containsExactly(AvailableEditActions.ADD)
                    }
            }
        }

    @Test
    fun currentTiles_moreThanMinimumTiles_haveRemoveAction() =
        with(kosmos) {
            testScope.runTest {
                val tiles by collectLastValue(underTest.tiles)
                val currentTiles =
                    mutableListOf(
                        TileSpec.create("flashlight"),
                        TileSpec.create("airplane"),
                        TileSpec.create(component2),
                        TileSpec.create("alarm"),
                    )
                currentTilesInteractor.setTiles(currentTiles)
                assertThat(currentTiles.size).isGreaterThan(minNumberOfTiles)

                underTest.startEditing()

                tiles!!
                    .filter { it.isCurrent }
                    .forEach {
                        assertThat(it.availableEditActions).contains(AvailableEditActions.REMOVE)
                    }
            }
        }

    @Test
    fun currentTiles_minimumTiles_dontHaveRemoveAction() =
        with(kosmos) {
            testScope.runTest {
                val tiles by collectLastValue(underTest.tiles)
                val currentTiles =
                    mutableListOf(
                        TileSpec.create("flashlight"),
                        TileSpec.create("airplane"),
                        TileSpec.create(component2),
                    )
                currentTilesInteractor.setTiles(currentTiles)
                assertThat(currentTiles.size).isEqualTo(minNumberOfTiles)

                underTest.startEditing()

                tiles!!
                    .filter { it.isCurrent }
                    .forEach {
                        assertThat(it.availableEditActions)
                            .doesNotContain(AvailableEditActions.REMOVE)
                    }
            }
        }

    @Test
    fun currentTiles_lessThanMinimumTiles_dontHaveRemoveAction() =
        with(kosmos) {
            testScope.runTest {
                val tiles by collectLastValue(underTest.tiles)
                val currentTiles =
                    mutableListOf(
                        TileSpec.create("flashlight"),
                        TileSpec.create("airplane"),
                    )
                currentTilesInteractor.setTiles(currentTiles)
                assertThat(currentTiles.size).isLessThan(minNumberOfTiles)

                underTest.startEditing()

                tiles!!
                    .filter { it.isCurrent }
                    .forEach {
                        assertThat(it.availableEditActions)
                            .doesNotContain(AvailableEditActions.REMOVE)
                    }
            }
        }

    @Test
    fun currentTiles_haveMoveAction() =
        with(kosmos) {
            testScope.runTest {
                val tiles by collectLastValue(underTest.tiles)
                val currentTiles =
                    mutableListOf(
                        TileSpec.create("flashlight"),
                        TileSpec.create("airplane"),
                        TileSpec.create(component2),
                        TileSpec.create("alarm"),
                    )
                currentTilesInteractor.setTiles(currentTiles)

                underTest.startEditing()

                tiles!!
                    .filter { it.isCurrent }
                    .forEach {
                        assertThat(it.availableEditActions).contains(AvailableEditActions.MOVE)
                    }
            }
        }

    private companion object {
        val drawable1 = TestStubDrawable("drawable1")
        val appName1 = "App1"
        val tileService1 = "Tile Service 1"
        val component1 = ComponentName("pkg1", "srv1")

        val drawable2 = TestStubDrawable("drawable2")
        val appName2 = "App2"
        val tileService2 = "Tile Service 2"
        val component2 = ComponentName("pkg2", "srv2")

        fun TileSpec.missingConfigEditTileData(): EditTileData {
            return EditTileData(
                tileSpec = this,
                icon = Icon.Resource(R.drawable.star_on, ContentDescription.Loaded(spec)),
                label = Text.Loaded(spec),
                appName = null
            )
        }

        fun QSTileConfig.toEditTileData(): EditTileData {
            return EditTileData(
                tileSpec = tileSpec,
                icon =
                    Icon.Resource(uiConfig.iconRes, ContentDescription.Resource(uiConfig.labelRes)),
                label = Text.Resource(uiConfig.labelRes),
                appName = null,
            )
        }

        fun Kosmos.getEditTileData(tileSpec: TileSpec): EditTileData {
            return if (qSTileConfigProvider.hasConfig(tileSpec.spec)) {
                qSTileConfigProvider.getConfig(tileSpec.spec).toEditTileData()
            } else {
                tileSpec.missingConfigEditTileData()
            }
        }

        val minNumberOfTiles = 3
    }
}

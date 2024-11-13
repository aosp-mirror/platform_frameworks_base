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

package com.android.systemui.qs.panels.domain.interactor

import android.content.ComponentName
import android.graphics.drawable.TestStubDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.Text
import com.android.systemui.kosmos.testScope
import com.android.systemui.qs.panels.data.repository.iconAndNameCustomRepository
import com.android.systemui.qs.panels.data.repository.stockTilesRepository
import com.android.systemui.qs.panels.shared.model.EditTileData
import com.android.systemui.qs.pipeline.data.repository.FakeInstalledTilesComponentRepository
import com.android.systemui.qs.pipeline.data.repository.fakeInstalledTilesRepository
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.shared.model.TileCategory
import com.android.systemui.qs.tiles.impl.battery.qsBatterySaverTileConfig
import com.android.systemui.qs.tiles.impl.flashlight.qsFlashlightTileConfig
import com.android.systemui.qs.tiles.impl.internet.qsInternetTileConfig
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
class EditTilesListInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()

    // Only have some configurations so we can test the effect of missing configurations.
    // As the configurations are injected by dagger, we'll have all the existing configurations
    private val internetTileConfig = kosmos.qsInternetTileConfig
    private val flashlightTileConfig = kosmos.qsFlashlightTileConfig
    private val batteryTileConfig = kosmos.qsBatterySaverTileConfig

    private val serviceInfo =
        FakeInstalledTilesComponentRepository.ServiceInfo(
            component,
            tileName,
            icon,
            appName,
        )

    private val underTest =
        with(kosmos) {
            EditTilesListInteractor(
                stockTilesRepository,
                qSTileConfigProvider,
                iconAndNameCustomRepository,
            )
        }

    @Before
    fun setUp() {
        with(kosmos) {
            fakeInstalledTilesRepository.setInstalledServicesForUser(
                userTracker.userId,
                listOf(serviceInfo)
            )

            with(fakeQSTileConfigProvider) {
                putConfig(internetTileConfig.tileSpec, internetTileConfig)
                putConfig(flashlightTileConfig.tileSpec, flashlightTileConfig)
                putConfig(batteryTileConfig.tileSpec, batteryTileConfig)
            }
        }
    }

    @Test
    fun getTilesToEdit_stockTilesHaveNoAppName() =
        with(kosmos) {
            testScope.runTest {
                val editTiles = underTest.getTilesToEdit()

                assertThat(editTiles.stockTiles.all { it.appName == null }).isTrue()
            }
        }

    @Test
    fun getTilesToEdit_stockTilesAreAllPlatformSpecs() =
        with(kosmos) {
            testScope.runTest {
                val editTiles = underTest.getTilesToEdit()

                assertThat(editTiles.stockTiles.all { it.tileSpec is TileSpec.PlatformTileSpec })
                    .isTrue()
            }
        }

    @Test
    fun getTilesToEdit_stockTiles_sameOrderAsRepository() =
        with(kosmos) {
            testScope.runTest {
                val editTiles = underTest.getTilesToEdit()

                assertThat(editTiles.stockTiles.map { it.tileSpec })
                    .isEqualTo(stockTilesRepository.stockTiles)
            }
        }

    @Test
    fun getTilesToEdit_customTileData_matchesService() =
        with(kosmos) {
            testScope.runTest {
                val editTiles = underTest.getTilesToEdit()
                val expected =
                    EditTileData(
                        tileSpec = TileSpec.create(component),
                        icon = Icon.Loaded(icon, ContentDescription.Loaded(tileName)),
                        label = Text.Loaded(tileName),
                        appName = Text.Loaded(appName),
                        category = TileCategory.PROVIDED_BY_APP,
                    )

                assertThat(editTiles.customTiles).hasSize(1)
                assertThat(editTiles.customTiles[0]).isEqualTo(expected)
            }
        }

    @Test
    fun getTilesToEdit_tilesInConfigProvider_correctData() =
        with(kosmos) {
            testScope.runTest {
                val editTiles = underTest.getTilesToEdit()

                assertThat(
                        editTiles.stockTiles.first { it.tileSpec == internetTileConfig.tileSpec }
                    )
                    .isEqualTo(internetTileConfig.toEditTileData())
                assertThat(
                        editTiles.stockTiles.first { it.tileSpec == flashlightTileConfig.tileSpec }
                    )
                    .isEqualTo(flashlightTileConfig.toEditTileData())
                assertThat(editTiles.stockTiles.first { it.tileSpec == batteryTileConfig.tileSpec })
                    .isEqualTo(batteryTileConfig.toEditTileData())
            }
        }

    @Test
    fun getTilesToEdit_tilesNotInConfigProvider_useDefaultData() =
        with(kosmos) {
            testScope.runTest {
                underTest
                    .getTilesToEdit()
                    .stockTiles
                    .filterNot { qSTileConfigProvider.hasConfig(it.tileSpec.spec) }
                    .forEach { assertThat(it).isEqualTo(it.tileSpec.missingConfigEditTileData()) }
            }
        }

    private companion object {
        val component = ComponentName("pkg", "srv")
        const val tileName = "Tile Service"
        const val appName = "App"
        val icon = TestStubDrawable("icon")

        fun TileSpec.missingConfigEditTileData(): EditTileData {
            return EditTileData(
                tileSpec = this,
                icon = Icon.Resource(android.R.drawable.star_on, ContentDescription.Loaded(spec)),
                label = Text.Loaded(spec),
                appName = null,
                category = TileCategory.UNKNOWN,
            )
        }

        fun QSTileConfig.toEditTileData(): EditTileData {
            return EditTileData(
                tileSpec = tileSpec,
                icon =
                    Icon.Resource(uiConfig.iconRes, ContentDescription.Resource(uiConfig.labelRes)),
                label = Text.Resource(uiConfig.labelRes),
                appName = null,
                category = category,
            )
        }
    }
}

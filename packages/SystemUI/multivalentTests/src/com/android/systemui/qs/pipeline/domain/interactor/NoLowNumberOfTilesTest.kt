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

package com.android.systemui.qs.pipeline.domain.interactor

import android.content.ComponentName
import android.content.pm.UserInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.qs.FakeQSFactory
import com.android.systemui.qs.FakeQSTile
import com.android.systemui.qs.pipeline.data.model.RestoreData
import com.android.systemui.qs.pipeline.data.repository.FakeDefaultTilesRepository
import com.android.systemui.qs.pipeline.data.repository.MinimumTilesFixedRepository
import com.android.systemui.qs.pipeline.data.repository.fakeDefaultTilesRepository
import com.android.systemui.qs.pipeline.data.repository.fakeMinimumTilesRepository
import com.android.systemui.qs.pipeline.data.repository.fakeRestoreRepository
import com.android.systemui.qs.pipeline.data.repository.fakeRetailModeRepository
import com.android.systemui.qs.pipeline.data.repository.fakeTileSpecRepository
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.qsTileFactory
import com.android.systemui.settings.fakeUserTracker
import com.android.systemui.settings.userTracker
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * This integration test is for testing the solution to b/324575996. In particular, when restoring
 * from a device that uses different specs for tiles, we may end up with empty (or mostly empty) QS.
 * In that case, we want to prepend the default tiles instead.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@MediumTest
@RunWith(AndroidJUnit4::class)
class NoLowNumberOfTilesTest : SysuiTestCase() {

    private val USER_0_INFO =
        UserInfo(
            0,
            "zero",
            "",
            UserInfo.FLAG_ADMIN or UserInfo.FLAG_FULL,
        )

    private val defaultTiles =
        listOf(
            TileSpec.create("internet"),
            TileSpec.create("bt"),
        )

    private val kosmos =
        Kosmos().apply {
            fakeMinimumTilesRepository = MinimumTilesFixedRepository(minNumberOfTiles = 2)
            fakeUserTracker.set(listOf(USER_0_INFO), 0)
            qsTileFactory = FakeQSFactory(::tileCreator)
            fakeDefaultTilesRepository = FakeDefaultTilesRepository(defaultTiles)
        }

    private val currentUser: Int
        get() = kosmos.userTracker.userId

    private val goodTile = TileSpec.create("correct")

    private val restoredTiles =
        listOf(
            TileSpec.create("OEM:internet"),
            TileSpec.create("OEM:bt"),
            TileSpec.create("OEM:dnd"),
            // This is not an installed component so a tile won't be created
            TileSpec.create(ComponentName.unflattenFromString("oem/.tile")!!),
            TileSpec.create("OEM:flashlight"),
            goodTile,
        )

    @Before
    fun setUp() {
        with(kosmos) {
            restoreReconciliationInteractor.start()
            autoAddInteractor.init(kosmos.currentTilesInteractor)
        }
    }

    @Test
    fun noLessThanTwoTilesAfterOEMRestore_prependedDefault() =
        with(kosmos) {
            testScope.runTest {
                val tiles by collectLastValue(currentTilesInteractor.currentTiles)
                runCurrent()

                assertThat(tiles!!).isNotEmpty()

                val restoreData = RestoreData(restoredTiles, emptySet(), currentUser)
                fakeRestoreRepository.onDataRestored(restoreData)
                runCurrent()

                assertThat(tiles!!.map { it.spec }).isEqualTo(defaultTiles + listOf(goodTile))
            }
        }

    @Test
    fun noEmptyTilesAfterSettingTilesToUnknownNames() =
        with(kosmos) {
            testScope.runTest {
                val tiles by collectLastValue(currentTilesInteractor.currentTiles)
                runCurrent()

                assertThat(tiles!!).isNotEmpty()

                val badTiles = listOf(TileSpec.create("OEM:unknown_tile"))
                currentTilesInteractor.setTiles(badTiles)
                runCurrent()

                assertThat(tiles!!.map { it.spec }).isEqualTo(defaultTiles)
            }
        }

    @Test
    fun inRetailMode_onlyOneTile_noPrependDefault() =
        with(kosmos) {
            testScope.runTest {
                fakeRetailModeRepository.setRetailMode(true)
                fakeTileSpecRepository.setTiles(0, listOf(goodTile))
                val tiles by collectLastValue(currentTilesInteractor.currentTiles)
                runCurrent()

                assertThat(tiles!!.map { it.spec }).isEqualTo(listOf(goodTile))
            }
        }

    private fun tileCreator(spec: String): QSTile? {
        return if (spec.contains("OEM")) {
            null // We don't know how to create OEM spec tiles
        } else {
            FakeQSTile(currentUser)
        }
    }
}

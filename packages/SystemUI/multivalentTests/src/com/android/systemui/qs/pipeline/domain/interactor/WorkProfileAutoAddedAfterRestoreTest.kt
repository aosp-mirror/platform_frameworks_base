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

import android.content.pm.UserInfo
import android.os.UserManager
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
import com.android.systemui.qs.pipeline.data.repository.fakeRestoreRepository
import com.android.systemui.qs.pipeline.data.repository.fakeTileSpecRepository
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.qsTileFactory
import com.android.systemui.settings.fakeUserTracker
import com.android.systemui.settings.userTracker
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * This integration test is for testing the solution to b/314781280. In particular, there are two
 * issues we want to verify after a restore of a device with a work profile and a work mode tile:
 * * When the work profile is re-enabled in the target device, it is auto-added.
 * * The tile is auto-added in the same position that it was in the restored device.
 */
@MediumTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class WorkProfileAutoAddedAfterRestoreTest : SysuiTestCase() {

    private val kosmos by lazy { Kosmos().apply { fakeUserTracker.set(listOf(USER_0_INFO), 0) } }
    // Getter here so it can change when there is a managed profile.
    private val workTileAvailable: Boolean
        get() = hasManagedProfile()

    private val currentUser: Int
        get() = kosmos.userTracker.userId

    private val testScope: TestScope
        get() = kosmos.testScope

    @Before
    fun setUp() {
        kosmos.qsTileFactory = FakeQSFactory(::tileCreator)
        kosmos.restoreReconciliationInteractor.start()
        kosmos.autoAddInteractor.init(kosmos.currentTilesInteractor)
    }

    @Test
    fun workTileRestoredAndPreviouslyAutoAdded_notAvailable_willBeAutoaddedInCorrectPosition() =
        testScope.runTest {
            val tiles by collectLastValue(kosmos.currentTilesInteractor.currentTiles)

            // Set up
            val currentTiles = listOf("a".toTileSpec())
            kosmos.fakeTileSpecRepository.setTiles(currentUser, currentTiles)

            val restoredTiles =
                listOf(WORK_TILE_SPEC) + listOf("b", "c", "d").map { it.toTileSpec() }
            val restoredAutoAdded = setOf(WORK_TILE_SPEC)

            val restoreData = RestoreData(restoredTiles, restoredAutoAdded, currentUser)

            // WHEN we restore tiles that auto-added the WORK tile and it's not available (there
            // are no managed profiles)
            kosmos.fakeRestoreRepository.onDataRestored(restoreData)

            // THEN the work tile is not part of the current tiles
            assertThat(tiles!!).hasSize(3)
            assertThat(tiles!!.map { it.spec }).doesNotContain(WORK_TILE_SPEC)

            // WHEN we add a work profile
            createManagedProfileAndAdd()

            // THEN the work profile is added in the correct place
            assertThat(tiles!!.first().spec).isEqualTo(WORK_TILE_SPEC)
        }

    @Test
    fun workTileNotRestoredAndPreviouslyAutoAdded_wontBeAutoAddedWhenWorkProfileIsAdded() =
        testScope.runTest {
            val tiles by collectLastValue(kosmos.currentTilesInteractor.currentTiles)

            // Set up
            val currentTiles = listOf("a".toTileSpec())
            kosmos.fakeTileSpecRepository.setTiles(currentUser, currentTiles)
            runCurrent()

            val restoredTiles = listOf("b", "c", "d").map { it.toTileSpec() }
            val restoredAutoAdded = setOf(WORK_TILE_SPEC)

            val restoreData = RestoreData(restoredTiles, restoredAutoAdded, currentUser)

            // WHEN we restore tiles that auto-added the WORK tile
            kosmos.fakeRestoreRepository.onDataRestored(restoreData)

            // THEN the work tile is not part of the current tiles
            assertThat(tiles!!).hasSize(3)
            assertThat(tiles!!.map { it.spec }).doesNotContain(WORK_TILE_SPEC)

            // WHEN we add a work profile
            createManagedProfileAndAdd()

            // THEN the work profile is not added because the user had manually removed it in the
            // past
            assertThat(tiles!!.map { it.spec }).doesNotContain(WORK_TILE_SPEC)
        }

    private fun tileCreator(spec: String): QSTile {
        return if (spec == WORK_TILE_SPEC.spec) {
            FakeQSTile(currentUser, workTileAvailable)
        } else {
            FakeQSTile(currentUser)
        }
    }

    private fun hasManagedProfile(): Boolean {
        return kosmos.userTracker.userProfiles.any { it.isManagedProfile }
    }

    private fun TestScope.createManagedProfileAndAdd() {
        kosmos.fakeUserTracker.set(
            listOf(USER_0_INFO, MANAGED_USER_INFO),
            0,
        )
        runCurrent()
    }

    private companion object {
        val WORK_TILE_SPEC = "work".toTileSpec()
        val USER_0_INFO =
            UserInfo(
                0,
                "zero",
                "",
                UserInfo.FLAG_ADMIN or UserInfo.FLAG_FULL,
            )
        val MANAGED_USER_INFO =
            UserInfo(
                10,
                "ten-managed",
                "",
                0,
                UserManager.USER_TYPE_PROFILE_MANAGED,
            )

        fun String.toTileSpec() = TileSpec.create(this)
    }
}

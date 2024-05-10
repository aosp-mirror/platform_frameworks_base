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

package com.android.systemui.qs.pipeline.data.restoreprocessors

import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.qs.pipeline.data.model.RestoreData
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class WorkTileRestoreProcessorTest : SysuiTestCase() {

    private val underTest = WorkTileRestoreProcessor()
    @Test
    fun restoreWithWorkTile_removeTracking() = runTest {
        val removeTracking by collectLastValue(underTest.removeTrackingForUser(UserHandle.of(USER)))
        runCurrent()

        val restoreData =
            RestoreData(
                restoredTiles = listOf(TILE_SPEC),
                restoredAutoAddedTiles = setOf(TILE_SPEC),
                USER,
            )

        underTest.postProcessRestore(restoreData)

        assertThat(removeTracking).isEqualTo(Unit)
    }

    @Test
    fun restoreWithWorkTile_otherUser_noRemoveTracking() = runTest {
        val removeTracking by
            collectLastValue(underTest.removeTrackingForUser(UserHandle.of(USER + 1)))
        runCurrent()

        val restoreData =
            RestoreData(
                restoredTiles = listOf(TILE_SPEC),
                restoredAutoAddedTiles = setOf(TILE_SPEC),
                USER,
            )

        underTest.postProcessRestore(restoreData)

        assertThat(removeTracking).isNull()
    }

    @Test
    fun restoreWithoutWorkTile_noSignal() = runTest {
        val removeTracking by collectLastValue(underTest.removeTrackingForUser(UserHandle.of(USER)))
        runCurrent()

        val restoreData =
            RestoreData(
                restoredTiles = emptyList(),
                restoredAutoAddedTiles = emptySet(),
                USER,
            )

        underTest.postProcessRestore(restoreData)

        assertThat(removeTracking).isNull()
    }

    companion object {
        private const val USER = 10
        private val TILE_SPEC = TileSpec.Companion.create("work")
    }
}

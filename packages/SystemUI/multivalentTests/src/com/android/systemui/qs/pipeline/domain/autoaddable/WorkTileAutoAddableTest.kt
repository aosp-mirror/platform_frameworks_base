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

package com.android.systemui.qs.pipeline.domain.autoaddable

import android.content.pm.UserInfo
import android.content.pm.UserInfo.FLAG_DISABLED
import android.content.pm.UserInfo.FLAG_FULL
import android.content.pm.UserInfo.FLAG_MANAGED_PROFILE
import android.content.pm.UserInfo.FLAG_PRIMARY
import android.content.pm.UserInfo.FLAG_PROFILE
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.qs.pipeline.data.model.RestoreData
import com.android.systemui.qs.pipeline.data.model.RestoreProcessor
import com.android.systemui.qs.pipeline.data.model.workTileRestoreProcessor
import com.android.systemui.qs.pipeline.domain.model.AutoAddSignal
import com.android.systemui.qs.pipeline.domain.model.AutoAddTracking
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.WorkModeTile
import com.android.systemui.settings.FakeUserTracker
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class WorkTileAutoAddableTest : SysuiTestCase() {

    private val kosmos = Kosmos()

    private val restoreProcessor: RestoreProcessor
        get() = kosmos.workTileRestoreProcessor

    private lateinit var userTracker: FakeUserTracker

    private lateinit var underTest: WorkTileAutoAddable

    @Before
    fun setup() {
        userTracker =
            FakeUserTracker(
                _userId = USER_INFO_0.id,
                _userInfo = USER_INFO_0,
                _userProfiles = listOf(USER_INFO_0)
            )

        underTest = WorkTileAutoAddable(userTracker, kosmos.workTileRestoreProcessor)
    }

    @Test
    fun changeInProfiles_hasManagedProfile_sendsAddSignal() = runTest {
        val signal by collectLastValue(underTest.autoAddSignal(0))

        userTracker.set(listOf(USER_INFO_0, USER_INFO_WORK_ENABLED), selectedUserIndex = 0)

        assertThat(signal).isEqualTo(AutoAddSignal.Add(SPEC))
    }

    @Test
    fun changeInProfiles_noManagedProfile_sendsRemoveSignal() = runTest {
        userTracker.set(listOf(USER_INFO_0, USER_INFO_WORK_ENABLED), selectedUserIndex = 0)

        val signal by collectLastValue(underTest.autoAddSignal(0))

        userTracker.set(listOf(USER_INFO_0), selectedUserIndex = 0)

        assertThat(signal).isEqualTo(AutoAddSignal.Remove(SPEC))
    }

    @Test
    fun changeInProfile_hasDisabledManagedProfile_noAddSignal() = runTest {
        val signal by collectLastValue(underTest.autoAddSignal(0))

        userTracker.set(listOf(USER_INFO_0, USER_INFO_WORK_DISABLED), selectedUserIndex = 0)

        assertThat(signal).isNotInstanceOf(AutoAddSignal.Add::class.java)
    }

    @Test
    fun startingWithManagedProfile_sendsAddSignal() = runTest {
        userTracker.set(listOf(USER_INFO_0, USER_INFO_WORK_ENABLED), selectedUserIndex = 0)

        val signal by collectLastValue(underTest.autoAddSignal(0))

        assertThat(signal).isEqualTo(AutoAddSignal.Add(SPEC))
    }

    @Test
    fun userChangeToUserWithProfile_noSignalForOriginalUser() = runTest {
        val signal by collectLastValue(underTest.autoAddSignal(0))

        userTracker.set(listOf(USER_INFO_1, USER_INFO_WORK_ENABLED), selectedUserIndex = 0)

        assertThat(signal).isNotEqualTo(AutoAddSignal.Add(SPEC))
    }

    @Test
    fun userChangeToUserWithoutProfile_noSignalForOriginalUser() = runTest {
        userTracker.set(listOf(USER_INFO_0, USER_INFO_WORK_ENABLED), selectedUserIndex = 0)
        val signal by collectLastValue(underTest.autoAddSignal(0))

        userTracker.set(listOf(USER_INFO_1), selectedUserIndex = 0)

        assertThat(signal).isNotEqualTo(AutoAddSignal.Remove(SPEC))
    }

    @Test
    fun strategyAlways() {
        assertThat(underTest.autoAddTracking).isEqualTo(AutoAddTracking.Always)
    }

    @Test
    fun restoreDataWithWorkTile_noCurrentManagedProfile_triggersRemove() = runTest {
        val userId = 0
        val signal by collectLastValue(underTest.autoAddSignal(userId))
        runCurrent()

        val restoreData = createRestoreWithWorkTile(userId)

        restoreProcessor.postProcessRestore(restoreData)

        assertThat(signal!!).isEqualTo(AutoAddSignal.RemoveTracking(SPEC))
    }

    @Test
    fun restoreDataWithWorkTile_currentlyManagedProfile_doesntTriggerRemove() = runTest {
        userTracker.set(listOf(USER_INFO_0, USER_INFO_WORK_ENABLED), selectedUserIndex = 0)
        val userId = 0
        val signals by collectValues(underTest.autoAddSignal(userId))
        runCurrent()

        val restoreData = createRestoreWithWorkTile(userId)

        restoreProcessor.postProcessRestore(restoreData)

        assertThat(signals).doesNotContain(AutoAddSignal.RemoveTracking(SPEC))
    }

    @Test
    fun restoreDataWithoutWorkTile_noManagedProfile_doesntTriggerRemove() = runTest {
        val userId = 0
        val signals by collectValues(underTest.autoAddSignal(userId))
        runCurrent()

        val restoreData = createRestoreWithoutWorkTile(userId)

        restoreProcessor.postProcessRestore(restoreData)

        assertThat(signals).doesNotContain(AutoAddSignal.RemoveTracking(SPEC))
    }

    @Test
    fun restoreDataWithoutWorkTile_managedProfile_doesntTriggerRemove() = runTest {
        userTracker.set(listOf(USER_INFO_0, USER_INFO_WORK_ENABLED), selectedUserIndex = 0)
        val userId = 0
        val signals by collectValues(underTest.autoAddSignal(userId))
        runCurrent()

        val restoreData = createRestoreWithoutWorkTile(userId)

        restoreProcessor.postProcessRestore(restoreData)

        assertThat(signals).doesNotContain(AutoAddSignal.RemoveTracking(SPEC))
    }

    companion object {
        private val SPEC = TileSpec.create(WorkModeTile.TILE_SPEC)
        private val USER_INFO_0 = UserInfo(0, "", FLAG_PRIMARY or FLAG_FULL)
        private val USER_INFO_1 = UserInfo(1, "", FLAG_FULL)
        private val USER_INFO_WORK_DISABLED =
            UserInfo(10, "", FLAG_PROFILE or FLAG_MANAGED_PROFILE or FLAG_DISABLED)
        private val USER_INFO_WORK_ENABLED = UserInfo(10, "", FLAG_PROFILE or FLAG_MANAGED_PROFILE)

        private fun createRestoreWithWorkTile(userId: Int): RestoreData {
            return RestoreData(
                listOf(TileSpec.create("a"), SPEC, TileSpec.create("b")),
                setOf(SPEC),
                userId,
            )
        }

        private fun createRestoreWithoutWorkTile(userId: Int): RestoreData {
            return RestoreData(
                listOf(TileSpec.create("a"), TileSpec.create("b")),
                emptySet(),
                userId,
            )
        }
    }
}

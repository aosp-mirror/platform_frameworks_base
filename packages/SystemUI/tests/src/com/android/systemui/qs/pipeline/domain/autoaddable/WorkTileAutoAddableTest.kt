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
import android.content.pm.UserInfo.FLAG_FULL
import android.content.pm.UserInfo.FLAG_MANAGED_PROFILE
import android.content.pm.UserInfo.FLAG_PRIMARY
import android.content.pm.UserInfo.FLAG_PROFILE
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.qs.pipeline.domain.model.AutoAddSignal
import com.android.systemui.qs.pipeline.domain.model.AutoAddTracking
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.WorkModeTile
import com.android.systemui.settings.FakeUserTracker
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidTestingRunner::class)
class WorkTileAutoAddableTest : SysuiTestCase() {

    private lateinit var userTracker: FakeUserTracker

    private lateinit var underTest: WorkTileAutoAddable

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        userTracker =
            FakeUserTracker(
                _userId = USER_INFO_0.id,
                _userInfo = USER_INFO_0,
                _userProfiles = listOf(USER_INFO_0)
            )

        underTest = WorkTileAutoAddable(userTracker)
    }

    @Test
    fun changeInProfiles_hasManagedProfile_sendsAddSignal() = runTest {
        val signal by collectLastValue(underTest.autoAddSignal(0))

        userTracker.set(listOf(USER_INFO_0, USER_INFO_WORK), selectedUserIndex = 0)

        assertThat(signal).isEqualTo(AutoAddSignal.Add(SPEC))
    }

    @Test
    fun changeInProfiles_noManagedProfile_sendsRemoveSignal() = runTest {
        userTracker.set(listOf(USER_INFO_0, USER_INFO_WORK), selectedUserIndex = 0)

        val signal by collectLastValue(underTest.autoAddSignal(0))

        userTracker.set(listOf(USER_INFO_0), selectedUserIndex = 0)

        assertThat(signal).isEqualTo(AutoAddSignal.Remove(SPEC))
    }

    @Test
    fun startingWithManagedProfile_sendsAddSignal() = runTest {
        userTracker.set(listOf(USER_INFO_0, USER_INFO_WORK), selectedUserIndex = 0)

        val signal by collectLastValue(underTest.autoAddSignal(0))

        assertThat(signal).isEqualTo(AutoAddSignal.Add(SPEC))
    }

    @Test
    fun userChangeToUserWithProfile_noSignalForOriginalUser() = runTest {
        val signal by collectLastValue(underTest.autoAddSignal(0))

        userTracker.set(listOf(USER_INFO_1, USER_INFO_WORK), selectedUserIndex = 0)

        assertThat(signal).isNotEqualTo(AutoAddSignal.Add(SPEC))
    }

    @Test
    fun userChangeToUserWithoutProfile_noSignalForOriginalUser() = runTest {
        userTracker.set(listOf(USER_INFO_0, USER_INFO_WORK), selectedUserIndex = 0)
        val signal by collectLastValue(underTest.autoAddSignal(0))

        userTracker.set(listOf(USER_INFO_1), selectedUserIndex = 0)

        assertThat(signal).isNotEqualTo(AutoAddSignal.Remove(SPEC))
    }

    @Test
    fun strategyAlways() {
        assertThat(underTest.autoAddTracking).isEqualTo(AutoAddTracking.Always)
    }

    companion object {
        private val SPEC = TileSpec.create(WorkModeTile.TILE_SPEC)
        private val USER_INFO_0 = UserInfo(0, "", FLAG_PRIMARY or FLAG_FULL)
        private val USER_INFO_1 = UserInfo(1, "", FLAG_FULL)
        private val USER_INFO_WORK = UserInfo(10, "", FLAG_PROFILE or FLAG_MANAGED_PROFILE)
    }
}

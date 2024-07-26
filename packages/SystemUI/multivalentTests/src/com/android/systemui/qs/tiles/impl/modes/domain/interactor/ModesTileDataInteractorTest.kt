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

package com.android.systemui.qs.tiles.impl.modes.domain.interactor

import android.app.Flags
import android.os.UserHandle
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectValues
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.android.systemui.qs.tiles.impl.modes.domain.model.ModesTileModel
import com.android.systemui.statusbar.policy.data.repository.fakeZenModeRepository
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toCollection
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class ModesTileDataInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val dispatcher = kosmos.testDispatcher
    private val zenModeRepository = kosmos.fakeZenModeRepository

    private val underTest = ModesTileDataInteractor(zenModeRepository, dispatcher)

    @EnableFlags(Flags.FLAG_MODES_UI)
    @Test
    fun availableWhenFlagIsOn() =
        testScope.runTest {
            val availability = underTest.availability(TEST_USER).toCollection(mutableListOf())

            assertThat(availability).containsExactly(true)
        }

    @DisableFlags(Flags.FLAG_MODES_UI)
    @Test
    fun unavailableWhenFlagIsOff() =
        testScope.runTest {
            val availability = underTest.availability(TEST_USER).toCollection(mutableListOf())

            assertThat(availability).containsExactly(false)
        }

    @EnableFlags(Flags.FLAG_MODES_UI)
    @Test
    fun isActivatedWhenModesChange() =
        testScope.runTest {
            val dataList: List<ModesTileModel> by
                collectValues(
                    underTest.tileData(TEST_USER, flowOf(DataUpdateTrigger.InitialRequest))
                )
            runCurrent()
            assertThat(dataList.map { it.isActivated }).containsExactly(false).inOrder()

            // Add active mode
            zenModeRepository.addMode(id = "One", active = true)
            runCurrent()
            assertThat(dataList.map { it.isActivated }).containsExactly(false, true).inOrder()
            assertThat(dataList.map { it.activeModes }.last()).containsExactly("Mode One")

            // Add an inactive mode: state hasn't changed, so this shouldn't cause another emission
            zenModeRepository.addMode(id = "Two", active = false)
            runCurrent()
            assertThat(dataList.map { it.isActivated }).containsExactly(false, true).inOrder()
            assertThat(dataList.map { it.activeModes }.last()).containsExactly("Mode One")

            // Add another active mode
            zenModeRepository.addMode(id = "Three", active = true)
            runCurrent()
            assertThat(dataList.map { it.isActivated }).containsExactly(false, true, true).inOrder()
            assertThat(dataList.map { it.activeModes }.last())
                .containsExactly("Mode One", "Mode Three")
                .inOrder()

            // Remove a mode and deactivate the other
            zenModeRepository.removeMode("One")
            runCurrent()
            zenModeRepository.deactivateMode("Three")
            runCurrent()
            assertThat(dataList.map { it.isActivated })
                .containsExactly(false, true, true, true, false)
                .inOrder()
            assertThat(dataList.map { it.activeModes }.last()).isEmpty()
        }

    private companion object {

        val TEST_USER = UserHandle.of(1)!!
    }
}

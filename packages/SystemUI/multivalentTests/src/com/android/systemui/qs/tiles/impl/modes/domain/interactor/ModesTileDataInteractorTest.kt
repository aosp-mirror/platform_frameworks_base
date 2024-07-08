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
import android.platform.test.annotations.EnabledOnRavenwood
import android.provider.Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS
import android.provider.Settings.Global.ZEN_MODE_NO_INTERRUPTIONS
import android.provider.Settings.Global.ZEN_MODE_OFF
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.notification.data.repository.FakeZenModeRepository
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectValues
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.android.systemui.qs.tiles.impl.modes.domain.model.ModesTileModel
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
@EnabledOnRavenwood
@RunWith(AndroidJUnit4::class)
class ModesTileDataInteractorTest : SysuiTestCase() {
    private val zenModeRepository = FakeZenModeRepository()

    private val underTest = ModesTileDataInteractor(zenModeRepository)

    @EnableFlags(Flags.FLAG_MODES_UI)
    @Test
    fun availableWhenFlagIsOn() = runTest {
        val availability = underTest.availability(TEST_USER).toCollection(mutableListOf())

        assertThat(availability).containsExactly(true)
    }

    @DisableFlags(Flags.FLAG_MODES_UI)
    @Test
    fun unavailableWhenFlagIsOff() = runTest {
        val availability = underTest.availability(TEST_USER).toCollection(mutableListOf())

        assertThat(availability).containsExactly(false)
    }

    @EnableFlags(Flags.FLAG_MODES_UI)
    @Test
    fun dataMatchesTheRepository() = runTest {
        val dataList: List<ModesTileModel> by
            collectValues(underTest.tileData(TEST_USER, flowOf(DataUpdateTrigger.InitialRequest)))
        runCurrent()

        // Enable zen mode
        zenModeRepository.updateZenMode(ZEN_MODE_IMPORTANT_INTERRUPTIONS)
        runCurrent()

        // Change zen mode: it's still enabled, so this shouldn't cause another emission
        zenModeRepository.updateZenMode(ZEN_MODE_NO_INTERRUPTIONS)
        runCurrent()

        // Disable zen mode
        zenModeRepository.updateZenMode(ZEN_MODE_OFF)
        runCurrent()

        assertThat(dataList.map { it.isActivated }).containsExactly(false, true, false)
    }

    private companion object {

        val TEST_USER = UserHandle.of(1)!!
    }
}

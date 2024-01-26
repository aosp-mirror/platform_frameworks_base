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

package com.android.systemui.qs.tiles.impl.colorcorrection.domain.interactor

import android.os.UserHandle
import android.platform.test.annotations.EnabledOnRavenwood
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.accessibility.data.repository.FakeColorCorrectionRepository
import com.android.systemui.coroutines.collectValues
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.android.systemui.qs.tiles.impl.colorcorrection.domain.model.ColorCorrectionTileModel
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
class ColorCorrectionTileDataInteractorTest : SysuiTestCase() {

    private val colorCorrectionRepository = FakeColorCorrectionRepository()
    private val underTest: ColorCorrectionTileDataInteractor =
        ColorCorrectionTileDataInteractor(colorCorrectionRepository)

    @Test
    fun alwaysAvailable() = runTest {
        val availability = underTest.availability(TEST_USER).toCollection(mutableListOf())

        assertThat(availability).hasSize(1)
        assertThat(availability.last()).isTrue()
    }

    @Test
    fun dataMatchesTheRepository() = runTest {
        val dataList: List<ColorCorrectionTileModel> by
            collectValues(underTest.tileData(TEST_USER, flowOf(DataUpdateTrigger.InitialRequest)))
        runCurrent()

        colorCorrectionRepository.setIsEnabled(true, TEST_USER)
        runCurrent()

        colorCorrectionRepository.setIsEnabled(false, TEST_USER)
        runCurrent()

        assertThat(dataList).hasSize(3)
        assertThat(dataList.map { it.isEnabled }).isEqualTo(listOf(false, true, false))
    }

    private companion object {
        val TEST_USER = UserHandle.of(1)!!
    }
}

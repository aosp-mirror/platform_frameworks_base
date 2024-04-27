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

package com.android.systemui.smartspace

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAsleepForTest
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAwakeForTest
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.smartspace.ui.viewmodel.SmartspaceViewModel
import com.android.systemui.smartspace.viewmodel.smartspaceViewModelFactory
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class SmartspaceViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val powerInteractor = kosmos.powerInteractor
    private val smartspaceViewModelFactory = kosmos.smartspaceViewModelFactory

    private lateinit var underTest: SmartspaceViewModel

    @Test
    fun dateVew_isAwakeTrue_true() =
        testScope.runTest {
            underTest = smartspaceViewModelFactory.create(SmartspaceViewModel.SURFACE_DATE_VIEW)

            powerInteractor.setAwakeForTest()
            val isAwake by collectLastValue(underTest.isAwake)

            assertThat(isAwake).isTrue()
        }

    @Test
    fun dateVew_isAwakeFalse_false() =
        testScope.runTest {
            underTest = smartspaceViewModelFactory.create(SmartspaceViewModel.SURFACE_DATE_VIEW)

            powerInteractor.setAsleepForTest()
            val isAwake by collectLastValue(underTest.isAwake)

            assertThat(isAwake).isFalse()
        }

    @Test
    fun dateVew_isAwakeMultipleTimes_correctResults() =
        testScope.runTest {
            underTest = smartspaceViewModelFactory.create(SmartspaceViewModel.SURFACE_DATE_VIEW)
            val isAwake by collectLastValue(underTest.isAwake)

            powerInteractor.setAsleepForTest()

            assertThat(isAwake).isFalse()

            powerInteractor.setAwakeForTest()

            assertThat(isAwake).isTrue()

            powerInteractor.setAsleepForTest()

            assertThat(isAwake).isFalse()

            powerInteractor.setAwakeForTest()

            assertThat(isAwake).isTrue()
        }

    @Test
    fun weatherView_isAwakeTrue_doesNotEmit() =
        testScope.runTest {
            underTest = smartspaceViewModelFactory.create(SmartspaceViewModel.SURFACE_WEATHER_VIEW)

            powerInteractor.setAwakeForTest()
            val isAwake = withTimeoutOrNull(100) { underTest.isAwake.firstOrNull() }

            assertThat(isAwake).isNull()
        }
}

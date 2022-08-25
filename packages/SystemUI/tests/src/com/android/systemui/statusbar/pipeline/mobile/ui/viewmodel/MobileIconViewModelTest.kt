/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel

import androidx.test.filters.SmallTest
import com.android.settingslib.graph.SignalDrawable
import com.android.settingslib.mobile.TelephonyIcons
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.FakeMobileIconInteractor
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
class MobileIconViewModelTest : SysuiTestCase() {
    private lateinit var underTest: MobileIconViewModel
    private val interactor = FakeMobileIconInteractor()
    @Mock private lateinit var logger: ConnectivityPipelineLogger

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        interactor.apply {
            setLevel(1)
            setCutOut(false)
            setIconGroup(TelephonyIcons.THREE_G)
            setIsEmergencyOnly(false)
            setNumberOfLevels(4)
        }
        underTest = MobileIconViewModel(SUB_1_ID, interactor, logger)
    }

    @Test
    fun iconId_correctLevel_notCutout() =
        runBlocking(IMMEDIATE) {
            var latest: Int? = null
            val job = underTest.iconId.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(SignalDrawable.getState(1, 4, false))

            job.cancel()
        }

    companion object {
        private val IMMEDIATE = Dispatchers.Main.immediate
        private const val SUB_1_ID = 1
    }
}

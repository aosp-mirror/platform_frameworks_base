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

package com.android.systemui.statusbar.phone.domain.interactor

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.statusbar.data.model.StatusBarMode
import com.android.systemui.statusbar.data.repository.FakeStatusBarModeRepository
import com.android.systemui.statusbar.data.repository.FakeStatusBarModeRepository.Companion.DISPLAY_ID
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

@SmallTest
class LightsOutInteractorTest : SysuiTestCase() {

    private val statusBarModeRepository = FakeStatusBarModeRepository()
    private val interactor: LightsOutInteractor = LightsOutInteractor(statusBarModeRepository)

    @Test
    fun isLowProfile_lightsOutStatusBarMode_false() = runTest {
        statusBarModeRepository.defaultDisplay.statusBarMode.value = StatusBarMode.LIGHTS_OUT

        val actual by collectLastValue(interactor.isLowProfile(DISPLAY_ID))

        assertThat(actual).isTrue()
    }

    @Test
    fun isLowProfile_lightsOutTransparentStatusBarMode_true() = runTest {
        statusBarModeRepository.defaultDisplay.statusBarMode.value =
            StatusBarMode.LIGHTS_OUT_TRANSPARENT

        val actual by collectLastValue(interactor.isLowProfile(DISPLAY_ID))

        assertThat(actual).isTrue()
    }

    @Test
    fun isLowProfile_transparentStatusBarMode_false() = runTest {
        statusBarModeRepository.defaultDisplay.statusBarMode.value = StatusBarMode.TRANSPARENT

        val actual by collectLastValue(interactor.isLowProfile(DISPLAY_ID))

        assertThat(actual).isFalse()
    }
}

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
 *
 */

package com.android.systemui.keyboard.backlight.domain.interactor

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyboard.data.repository.FakeKeyboardRepository
import com.android.systemui.keyboard.shared.model.BacklightModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class KeyboardBacklightInteractorTest : SysuiTestCase() {

    private val keyboardRepository = FakeKeyboardRepository()
    private lateinit var underTest: KeyboardBacklightInteractor

    @Before
    fun setUp() {
        underTest = KeyboardBacklightInteractor(keyboardRepository)
    }

    @Test
    fun emitsNull_whenKeyboardJustConnected() = runTest {
        val latest by collectLastValue(underTest.backlight)
        keyboardRepository.setIsAnyKeyboardConnected(true)

        assertThat(latest).isNull()
    }

    @Test
    fun emitsBacklight_whenKeyboardConnectedAndBacklightChanged() = runTest {
        keyboardRepository.setIsAnyKeyboardConnected(true)
        keyboardRepository.setBacklight(BacklightModel(1, 5))

        assertThat(underTest.backlight.first()).isEqualTo(BacklightModel(1, 5))
    }

    @Test
    fun emitsNull_afterKeyboardDisconnecting() = runTest {
        val latest by collectLastValue(underTest.backlight)
        keyboardRepository.setIsAnyKeyboardConnected(true)
        keyboardRepository.setBacklight(BacklightModel(1, 5))

        keyboardRepository.setIsAnyKeyboardConnected(false)

        assertThat(latest).isNull()
    }
}

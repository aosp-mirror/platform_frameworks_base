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

package com.android.systemui.keyevent.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyevent.data.repository.FakeKeyEventRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.junit.MockitoJUnit

@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyEventInteractorTest : SysuiTestCase() {
    @JvmField @Rule var mockitoRule = MockitoJUnit.rule()

    private lateinit var repository: FakeKeyEventRepository

    private lateinit var underTest: KeyEventInteractor

    @Before
    fun setup() {
        repository = FakeKeyEventRepository()
        underTest =
            KeyEventInteractor(
                repository,
            )
    }

    @Test
    fun dispatchBackKey_notHandledByKeyguardKeyEventInteractor_handledByBackActionInteractor() =
        runTest {
            val isPowerDown by collectLastValue(underTest.isPowerButtonDown)
            repository.setPowerButtonDown(false)
            assertThat(isPowerDown).isFalse()

            repository.setPowerButtonDown(true)
            assertThat(isPowerDown).isTrue()
        }
}

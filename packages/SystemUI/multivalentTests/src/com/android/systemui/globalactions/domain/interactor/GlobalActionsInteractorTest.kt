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

package com.android.systemui.globalactions.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
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
class GlobalActionsInteractorTest : SysuiTestCase() {
    private lateinit var underTest: GlobalActionsInteractor
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    @Before
    fun setup() {
        underTest = kosmos.globalActionsInteractor
    }

    @Test
    fun OnDismissed() {
        testScope.runTest {
            val isVisible by collectLastValue(underTest.isVisible)
            underTest.onDismissed()
            runCurrent()

            assertThat(isVisible).isFalse()
        }
    }

    @Test
    fun OnShown() {
        testScope.runTest {
            val isVisible by collectLastValue(underTest.isVisible)
            underTest.onShown()
            runCurrent()

            assertThat(isVisible).isTrue()
        }
    }
}

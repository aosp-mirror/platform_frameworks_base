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

package com.android.systemui.window.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.lifecycle.activateIn
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class WindowRootViewModelTest : SysuiTestCase() {
    val kosmos = testKosmos()
    val testScope = kosmos.testScope

    val underTest by lazy { kosmos.windowRootViewModel }

    @Before
    fun setup() {
        underTest.activateIn(testScope)
    }

    @Test
    fun bouncerTransitionChangesWindowBlurRadius() =
        testScope.runTest {
            val blurRadius by collectLastValue(underTest.blurRadius)
            val isBlurOpaque by collectLastValue(underTest.isBlurOpaque)
            runCurrent()

            kosmos.fakeBouncerTransitions.first().windowBlurRadius.value = 30.0f
            runCurrent()

            assertThat(blurRadius).isEqualTo(30)
            assertThat(isBlurOpaque).isEqualTo(false)
        }
}

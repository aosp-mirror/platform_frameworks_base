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

package com.android.systemui.shade.display

import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.shade.data.repository.fakeFocusedDisplayRepository
import com.android.systemui.shade.data.repository.focusShadeDisplayPolicy
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class FocusShadeDisplayPolicyTest : SysuiTestCase() {
    private val kosmos = testKosmos().useUnconfinedTestDispatcher()
    private val testScope = kosmos.testScope
    private val focusedDisplayRepository = kosmos.fakeFocusedDisplayRepository

    private val underTest = kosmos.focusShadeDisplayPolicy

    @Test
    fun displayId_propagatedFromRepository() =
        testScope.runTest {
            val displayId by collectLastValue(underTest.displayId)

            assertThat(displayId).isEqualTo(Display.DEFAULT_DISPLAY)

            focusedDisplayRepository.setDisplayId(2)

            assertThat(displayId).isEqualTo(2)

            focusedDisplayRepository.setDisplayId(3)

            assertThat(displayId).isEqualTo(3)
        }
}

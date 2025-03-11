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
import com.android.systemui.display.data.repository.display
import com.android.systemui.display.data.repository.displayRepository
import com.android.systemui.kosmos.testScope
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class AnyExternalShadeDisplayPolicyTest : SysuiTestCase() {
    private val kosmos = testKosmos().useUnconfinedTestDispatcher()
    private val testScope = kosmos.testScope
    private val displayRepository = kosmos.displayRepository
    val underTest = AnyExternalShadeDisplayPolicy(displayRepository, testScope.backgroundScope)

    @Test
    fun displayId_ignoresUnwantedTypes() =
        testScope.runTest {
            val displayId by collectLastValue(underTest.displayId)

            displayRepository.addDisplays(
                display(id = 0, type = Display.TYPE_INTERNAL),
                display(id = 1, type = Display.TYPE_UNKNOWN),
                display(id = 2, type = Display.TYPE_VIRTUAL),
                display(id = 3, type = Display.TYPE_EXTERNAL),
            )

            assertThat(displayId).isEqualTo(3)
        }

    @Test
    fun displayId_onceRemoved_goesToNextDisplay() =
        testScope.runTest {
            val displayId by collectLastValue(underTest.displayId)

            displayRepository.addDisplays(
                display(id = 0, type = Display.TYPE_INTERNAL),
                display(id = 2, type = Display.TYPE_EXTERNAL),
                display(id = 3, type = Display.TYPE_EXTERNAL),
            )

            assertThat(displayId).isEqualTo(2)

            displayRepository.removeDisplay(2)

            assertThat(displayId).isEqualTo(3)
        }

    @Test
    fun displayId_onlyDefaultDisplay_defaultDisplay() =
        testScope.runTest {
            val displayId by collectLastValue(underTest.displayId)

            displayRepository.addDisplays(display(id = 0, type = Display.TYPE_INTERNAL))

            assertThat(displayId).isEqualTo(0)
        }
}

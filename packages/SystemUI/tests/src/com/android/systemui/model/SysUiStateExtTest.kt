/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.systemui.model

import android.view.Display
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.settings.FakeDisplayTracker
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@SmallTest
@RunWith(JUnit4::class)
class SysUiStateExtTest : SysuiTestCase() {

    private val kosmos = testKosmos()

    private val underTest =
        SysUiState(
            FakeDisplayTracker(context),
            kosmos.sceneContainerPlugin,
        )

    @Test
    fun updateFlags() {
        underTest.updateFlags(
            Display.DEFAULT_DISPLAY,
            1 to true,
            2 to false,
            3 to true,
        )

        assertThat(underTest.flags and 1).isNotEqualTo(0)
        assertThat(underTest.flags and 2).isEqualTo(0)
        assertThat(underTest.flags and 3).isNotEqualTo(0)
    }
}

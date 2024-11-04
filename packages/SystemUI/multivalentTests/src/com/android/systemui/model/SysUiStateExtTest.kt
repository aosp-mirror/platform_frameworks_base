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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.settings.FakeDisplayTracker
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
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
            1L to true,
            2L to false,
            3L to true,
        )

        assertThat(underTest.flags and 1L).isNotEqualTo(0L)
        assertThat(underTest.flags and 2L).isEqualTo(0L)
        assertThat(underTest.flags and 3L).isNotEqualTo(0L)
    }
}

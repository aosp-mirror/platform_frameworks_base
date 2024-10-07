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

package com.android.systemui.volume.data.repository

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
class VolumeDialogRepositoryTest : SysuiTestCase() {
    private lateinit var underTest: VolumeDialogRepository
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    @Before
    fun setUp() {
        underTest = kosmos.volumeDialogRepository
    }

    @Test
    fun isDialogVisible_initialValueFalse() {
        testScope.runTest {
            val isVisible by collectLastValue(underTest.isDialogVisible)
            runCurrent()

            assertThat(isVisible).isFalse()
        }
    }

    @Test
    fun isDialogVisible_onChange() {
        testScope.runTest {
            val isVisible by collectLastValue(underTest.isDialogVisible)
            runCurrent()

            underTest.setDialogVisibility(true)
            assertThat(isVisible).isTrue()

            underTest.setDialogVisibility(false)
            assertThat(isVisible).isFalse()
        }
    }
}

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

package com.android.systemui.keyguard.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectValues
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyguardSurfaceBehindRepositoryImplTest : SysuiTestCase() {
    private val testScope = TestScope()

    private lateinit var underTest: KeyguardSurfaceBehindRepositoryImpl

    @Before
    fun setUp() {
        underTest = KeyguardSurfaceBehindRepositoryImpl()
    }

    @Test
    fun testSetAnimatingSurface() {
        testScope.runTest {
            val values by collectValues(underTest.isAnimatingSurface)

            runCurrent()
            underTest.setAnimatingSurface(true)
            runCurrent()
            underTest.setAnimatingSurface(false)
            runCurrent()

            // Default (first) value should be false.
            assertThat(values).isEqualTo(listOf(false, true, false))
        }
    }
}

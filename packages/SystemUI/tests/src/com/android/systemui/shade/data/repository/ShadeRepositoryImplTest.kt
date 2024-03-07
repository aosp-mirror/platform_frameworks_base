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

package com.android.systemui.shade.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class ShadeRepositoryImplTest : SysuiTestCase() {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var underTest: ShadeRepositoryImpl

    @Before
    fun setUp() {
        underTest = ShadeRepositoryImpl()
    }

    @Test
    fun updateQsExpansion() =
        testScope.runTest {
            assertThat(underTest.qsExpansion.value).isEqualTo(0f)

            underTest.setQsExpansion(.5f)
            assertThat(underTest.qsExpansion.value).isEqualTo(.5f)

            underTest.setQsExpansion(.82f)
            assertThat(underTest.qsExpansion.value).isEqualTo(.82f)

            underTest.setQsExpansion(1f)
            assertThat(underTest.qsExpansion.value).isEqualTo(1f)
        }

    @Test
    fun updateDragDownAmount() =
        testScope.runTest {
            assertThat(underTest.lockscreenShadeExpansion.value).isEqualTo(0f)

            underTest.setLockscreenShadeExpansion(.5f)
            assertThat(underTest.lockscreenShadeExpansion.value).isEqualTo(.5f)

            underTest.setLockscreenShadeExpansion(.82f)
            assertThat(underTest.lockscreenShadeExpansion.value).isEqualTo(.82f)

            underTest.setLockscreenShadeExpansion(1f)
            assertThat(underTest.lockscreenShadeExpansion.value).isEqualTo(1f)
        }

    @Test
    fun updateLegacyShadeExpansion() =
        testScope.runTest {
            assertThat(underTest.legacyShadeExpansion.value).isEqualTo(0f)

            underTest.setLegacyShadeExpansion(.5f)
            assertThat(underTest.legacyShadeExpansion.value).isEqualTo(.5f)

            underTest.setLegacyShadeExpansion(.82f)
            assertThat(underTest.legacyShadeExpansion.value).isEqualTo(.82f)

            underTest.setLegacyShadeExpansion(1f)
            assertThat(underTest.legacyShadeExpansion.value).isEqualTo(1f)
        }

    @Test
    fun updateLegacyShadeTracking() =
        testScope.runTest {
            assertThat(underTest.legacyShadeTracking.value).isEqualTo(false)

            underTest.setLegacyShadeTracking(true)
            assertThat(underTest.legacyShadeTracking.value).isEqualTo(true)
        }

    @Test
    fun updateLegacyLockscreenShadeTracking() =
        testScope.runTest {
            assertThat(underTest.legacyLockscreenShadeTracking.value).isEqualTo(false)

            underTest.setLegacyLockscreenShadeTracking(true)
            assertThat(underTest.legacyLockscreenShadeTracking.value).isEqualTo(true)
        }

    @Test
    fun updateLegacyQsTracking() =
        testScope.runTest {
            assertThat(underTest.legacyQsTracking.value).isEqualTo(false)

            underTest.setLegacyQsTracking(true)
            assertThat(underTest.legacyQsTracking.value).isEqualTo(true)
        }

    @Test
    fun updateLegacyExpandedOrAwaitingInputTransfer() =
        testScope.runTest {
            assertThat(underTest.legacyExpandedOrAwaitingInputTransfer.value).isEqualTo(false)

            underTest.setLegacyExpandedOrAwaitingInputTransfer(true)
            assertThat(underTest.legacyExpandedOrAwaitingInputTransfer.value).isEqualTo(true)
        }

    @Test
    fun updateUdfpsTransitionToFullShadeProgress() =
        testScope.runTest {
            assertThat(underTest.udfpsTransitionToFullShadeProgress.value).isEqualTo(0f)

            underTest.setUdfpsTransitionToFullShadeProgress(.5f)
            assertThat(underTest.udfpsTransitionToFullShadeProgress.value).isEqualTo(.5f)

            underTest.setUdfpsTransitionToFullShadeProgress(.82f)
            assertThat(underTest.udfpsTransitionToFullShadeProgress.value).isEqualTo(.82f)

            underTest.setUdfpsTransitionToFullShadeProgress(1f)
            assertThat(underTest.udfpsTransitionToFullShadeProgress.value).isEqualTo(1f)
        }

    @Test
    fun updateLegacyIsQsExpanded() =
        testScope.runTest {
            assertThat(underTest.legacyIsQsExpanded.value).isEqualTo(false)

            underTest.setLegacyIsQsExpanded(true)
            assertThat(underTest.legacyIsQsExpanded.value).isEqualTo(true)
        }

    @Test
    fun updateLegacyExpandImmediate() =
        testScope.runTest {
            assertThat(underTest.legacyExpandImmediate.value).isEqualTo(false)

            underTest.setLegacyExpandImmediate(true)
            assertThat(underTest.legacyExpandImmediate.value).isEqualTo(true)
        }

    @Test
    fun updateLegacyQsFullscreen() =
        testScope.runTest {
            assertThat(underTest.legacyQsFullscreen.value).isEqualTo(false)

            underTest.setLegacyQsFullscreen(true)
            assertThat(underTest.legacyQsFullscreen.value).isEqualTo(true)
        }

    @Test
    fun updateLegacyIsClosing() =
        testScope.runTest {
            assertThat(underTest.legacyIsClosing.value).isEqualTo(false)

            underTest.setLegacyIsClosing(true)
            assertThat(underTest.legacyIsClosing.value).isEqualTo(true)
        }
}

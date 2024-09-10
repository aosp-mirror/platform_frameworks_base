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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.accessibility.data.repository

import android.view.accessibility.AccessibilityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.mockito.withArgCaptor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

@RunWith(AndroidJUnit4::class)
@SmallTest
class AccessibilityRepositoryTest : SysuiTestCase() {

    @Rule @JvmField val mockitoRule: MockitoRule = MockitoJUnit.rule()

    // mocks
    @Mock private lateinit var a11yManager: AccessibilityManager

    // real impls
    private val underTest by lazy { AccessibilityRepository(a11yManager) }

    @Test
    fun isTouchExplorationEnabled_reflectsA11yManager_initFalse() = runTest {
        whenever(a11yManager.isTouchExplorationEnabled).thenReturn(false)
        val isTouchExplorationEnabled by collectLastValue(underTest.isTouchExplorationEnabled)
        assertThat(isTouchExplorationEnabled).isFalse()
    }

    @Test
    fun isTouchExplorationEnabled_reflectsA11yManager_initTrue() = runTest {
        whenever(a11yManager.isTouchExplorationEnabled).thenReturn(true)
        val isTouchExplorationEnabled by collectLastValue(underTest.isTouchExplorationEnabled)
        assertThat(isTouchExplorationEnabled).isTrue()
    }

    @Test
    fun isTouchExplorationEnabled_reflectsA11yManager_changeTrue() = runTest {
        whenever(a11yManager.isTouchExplorationEnabled).thenReturn(false)
        val isTouchExplorationEnabled by collectLastValue(underTest.isTouchExplorationEnabled)
        runCurrent()
        withArgCaptor { verify(a11yManager).addTouchExplorationStateChangeListener(capture()) }
            .onTouchExplorationStateChanged(/* enabled = */ true)
        assertThat(isTouchExplorationEnabled).isTrue()
    }

    @Test
    fun isTouchExplorationEnabled_reflectsA11yManager_changeFalse() = runTest {
        whenever(a11yManager.isTouchExplorationEnabled).thenReturn(true)
        val isTouchExplorationEnabled by collectLastValue(underTest.isTouchExplorationEnabled)
        runCurrent()
        withArgCaptor { verify(a11yManager).addTouchExplorationStateChangeListener(capture()) }
            .onTouchExplorationStateChanged(/* enabled = */ false)
        assertThat(isTouchExplorationEnabled).isFalse()
    }
}

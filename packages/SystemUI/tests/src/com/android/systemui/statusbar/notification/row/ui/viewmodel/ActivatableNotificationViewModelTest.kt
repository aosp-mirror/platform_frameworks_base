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

package com.android.systemui.statusbar.notification.row.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.accessibility.data.repository.FakeAccessibilityRepository
import com.android.systemui.accessibility.domain.interactor.AccessibilityInteractor
import com.android.systemui.coroutines.collectLastValue
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class ActivatableNotificationViewModelTest : SysuiTestCase() {

    // fakes
    private val a11yRepo = FakeAccessibilityRepository()

    // real impls
    private val a11yInteractor = AccessibilityInteractor(a11yRepo)
    private val underTest = ActivatableNotificationViewModel(a11yInteractor)

    @Test
    fun isTouchable_whenA11yTouchExplorationDisabled() = runTest {
        a11yRepo.isTouchExplorationEnabled.value = false
        val isTouchable: Boolean? by collectLastValue(underTest.isTouchable)
        assertThat(isTouchable).isTrue()
    }

    @Test
    fun isNotTouchable_whenA11yTouchExplorationEnabled() = runTest {
        a11yRepo.isTouchExplorationEnabled.value = true
        val isTouchable: Boolean? by collectLastValue(underTest.isTouchable)
        assertThat(isTouchable).isFalse()
    }

    @Test
    fun isTouchable_whenA11yTouchExplorationChangesToDisabled() = runTest {
        a11yRepo.isTouchExplorationEnabled.value = true
        val isTouchable: Boolean? by collectLastValue(underTest.isTouchable)
        runCurrent()
        a11yRepo.isTouchExplorationEnabled.value = false
        assertThat(isTouchable).isTrue()
    }

    @Test
    fun isNotTouchable_whenA11yTouchExplorationChangesToEnabled() = runTest {
        a11yRepo.isTouchExplorationEnabled.value = false
        val isTouchable: Boolean? by collectLastValue(underTest.isTouchable)
        runCurrent()
        a11yRepo.isTouchExplorationEnabled.value = true
        assertThat(isTouchable).isFalse()
    }
}

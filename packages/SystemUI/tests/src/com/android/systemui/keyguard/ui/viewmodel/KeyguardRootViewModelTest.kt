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
 *
 */

package com.android.systemui.keyguard.ui.viewmodel

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractorFactory
import com.google.common.truth.Truth
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(JUnit4::class)
class KeyguardRootViewModelTest : SysuiTestCase() {

    private lateinit var underTest: KeyguardRootViewModel
    private lateinit var testScope: TestScope
    private lateinit var repository: FakeKeyguardRepository
    private lateinit var keyguardInteractor: KeyguardInteractor
    @Mock private lateinit var keyguardQuickAffordancesCombinedViewModel: KeyguardQuickAffordancesCombinedViewModel

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        val testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)

        val featureFlags =
            FakeFeatureFlags().apply {
                set(Flags.MIGRATE_SPLIT_KEYGUARD_BOTTOM_AREA, true)
                set(Flags.FACE_AUTH_REFACTOR, true)
            }

        val withDeps = KeyguardInteractorFactory.create(featureFlags = featureFlags)
        keyguardInteractor = withDeps.keyguardInteractor
        repository = withDeps.repository

        underTest = KeyguardRootViewModel(
            keyguardInteractor,
            keyguardQuickAffordancesCombinedViewModel,
        )
    }

    @Test
    fun alpha() =
        testScope.runTest {
            val value = collectLastValue(underTest.alpha)

            Truth.assertThat(value()).isEqualTo(1f)
            repository.setKeyguardAlpha(0.1f)
            Truth.assertThat(value()).isEqualTo(0.1f)
            repository.setKeyguardAlpha(0.5f)
            Truth.assertThat(value()).isEqualTo(0.5f)
            repository.setKeyguardAlpha(0.2f)
            Truth.assertThat(value()).isEqualTo(0.2f)
            repository.setKeyguardAlpha(0f)
            Truth.assertThat(value()).isEqualTo(0f)
        }

    @Test
    fun alpha_inPreviewMode_doesNotChange() =
        testScope.runTest {
            val value = collectLastValue(underTest.alpha)
            underTest.enablePreviewMode(
                initiallySelectedSlotId = null,
                shouldHighlightSelectedAffordance = false,
            )

            Truth.assertThat(value()).isEqualTo(1f)
            repository.setKeyguardAlpha(0.1f)
            Truth.assertThat(value()).isEqualTo(1f)
            repository.setKeyguardAlpha(0.5f)
            Truth.assertThat(value()).isEqualTo(1f)
            repository.setKeyguardAlpha(0.2f)
            Truth.assertThat(value()).isEqualTo(1f)
            repository.setKeyguardAlpha(0f)
            Truth.assertThat(value()).isEqualTo(1f)
        }
}
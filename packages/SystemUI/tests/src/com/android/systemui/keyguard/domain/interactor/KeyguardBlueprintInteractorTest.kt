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

package com.android.systemui.keyguard.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.data.repository.KeyguardBlueprintRepository
import com.android.systemui.keyguard.ui.view.layout.blueprints.DefaultKeyguardBlueprint
import com.android.systemui.keyguard.ui.view.layout.blueprints.SplitShadeKeyguardBlueprint
import com.android.systemui.keyguard.ui.view.layout.blueprints.transitions.IntraBlueprintTransitionType
import com.android.systemui.statusbar.policy.SplitShadeStateController
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyguardBlueprintInteractorTest : SysuiTestCase() {
    private val configurationFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private lateinit var underTest: KeyguardBlueprintInteractor
    private lateinit var testScope: TestScope

    val refreshBluePrint: MutableSharedFlow<Unit> = MutableSharedFlow(extraBufferCapacity = 1)
    val refreshBlueprintTransition: MutableSharedFlow<IntraBlueprintTransitionType> =
        MutableSharedFlow(extraBufferCapacity = 1)

    @Mock private lateinit var splitShadeStateController: SplitShadeStateController
    @Mock private lateinit var keyguardBlueprintRepository: KeyguardBlueprintRepository

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        testScope = TestScope(StandardTestDispatcher())
        whenever(keyguardBlueprintRepository.configurationChange).thenReturn(configurationFlow)
        whenever(keyguardBlueprintRepository.refreshBluePrint).thenReturn(refreshBluePrint)
        whenever(keyguardBlueprintRepository.refreshBlueprintTransition)
            .thenReturn(refreshBlueprintTransition)

        underTest =
            KeyguardBlueprintInteractor(
                keyguardBlueprintRepository,
                testScope.backgroundScope,
                mContext,
                splitShadeStateController,
            )
    }

    @Test
    fun testAppliesDefaultBlueprint() {
        testScope.runTest {
            whenever(splitShadeStateController.shouldUseSplitNotificationShade(any()))
                .thenReturn(false)

            reset(keyguardBlueprintRepository)
            configurationFlow.tryEmit(Unit)
            runCurrent()

            verify(keyguardBlueprintRepository)
                .applyBlueprint(DefaultKeyguardBlueprint.Companion.DEFAULT)
        }
    }

    @Test
    fun testAppliesSplitShadeBlueprint() {
        testScope.runTest {
            whenever(splitShadeStateController.shouldUseSplitNotificationShade(any()))
                .thenReturn(true)

            reset(keyguardBlueprintRepository)
            configurationFlow.tryEmit(Unit)
            runCurrent()

            verify(keyguardBlueprintRepository)
                .applyBlueprint(SplitShadeKeyguardBlueprint.Companion.ID)
        }
    }

    @Test
    fun testRefreshBlueprint() {
        underTest.refreshBlueprint()
        verify(keyguardBlueprintRepository).refreshBlueprint()
    }

    @Test
    fun testTransitionToBlueprint() {
        underTest.transitionToBlueprint("abc")
        verify(keyguardBlueprintRepository).applyBlueprint("abc")
    }

    @Test
    fun testRefreshBlueprintWithTransition() {
        underTest.refreshBlueprintWithTransition(IntraBlueprintTransitionType.DefaultTransition)
        verify(keyguardBlueprintRepository)
            .refreshBlueprintWithTransition(IntraBlueprintTransitionType.DefaultTransition)
    }
}

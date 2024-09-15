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

package com.android.systemui.keyguard.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectValues
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.kosmos.testScope
import com.android.systemui.shade.data.repository.FlingInfo
import com.android.systemui.shade.data.repository.fakeShadeRepository
import com.android.systemui.testKosmos
import com.google.common.truth.Correspondence
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@kotlinx.coroutines.ExperimentalCoroutinesApi
class SwipeToDismissInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val underTest = kosmos.swipeToDismissInteractor
    private val transitionRepository = kosmos.fakeKeyguardTransitionRepository
    private val shadeRepository = kosmos.fakeShadeRepository
    private val keyguardRepository = kosmos.fakeKeyguardRepository

    @Test
    fun testDismissFling_emitsInLockscreenWhenDismissable() =
        testScope.runTest {
            val values by collectValues(underTest.dismissFling)
            keyguardRepository.setKeyguardDismissible(true)
            runCurrent()
            shadeRepository.setCurrentFling(FlingInfo(expand = false))
            runCurrent()

            assertThat(values)
                .comparingElementsUsing(
                    Correspondence.transforming(
                        { fling: FlingInfo? -> fling?.expand },
                        "expand equals"
                    )
                )
                .containsExactly(null, false)
                .inOrder()
        }

    @Test
    fun testDismissFling_doesNotEmitInWrongStateOrNotDismissable() =
        testScope.runTest {
            val values by collectValues(underTest.dismissFling)
            keyguardRepository.setKeyguardDismissible(false)
            shadeRepository.setCurrentFling(FlingInfo(expand = false))
            runCurrent()

            assertThat(values).containsExactly(null)

            // Not in LOCKSCREEN, but dismissable.
            transitionRepository.sendTransitionSteps(
                KeyguardState.LOCKSCREEN,
                KeyguardState.GONE,
                testScope
            )
            keyguardRepository.setKeyguardDismissible(true)

            // Re-emit a valid dismiss fling.
            shadeRepository.setCurrentFling(null)
            shadeRepository.setCurrentFling(FlingInfo(expand = false))
            runCurrent()

            assertThat(values).containsExactly(null)
        }

    @Test
    fun testExpandFling_doesNotEmit() =
        testScope.runTest {
            val values by collectValues(underTest.dismissFling)
            keyguardRepository.setKeyguardDismissible(true)
            runCurrent()
            shadeRepository.setCurrentFling(
                FlingInfo(expand = true) // Not a dismiss fling (expand = true).
            )
            runCurrent()

            assertThat(values).containsExactly(null)
        }
}

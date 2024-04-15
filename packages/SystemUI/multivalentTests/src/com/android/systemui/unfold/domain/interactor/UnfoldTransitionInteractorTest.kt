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
 * limitations under the License
 */
package com.android.systemui.unfold.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.unfold.fakeUnfoldTransitionProgressProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class UnfoldTransitionInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val unfoldTransitionProgressProvider = kosmos.fakeUnfoldTransitionProgressProvider

    private val underTest: UnfoldTransitionInteractor = kosmos.unfoldTransitionInteractor

    @Test
    fun waitForTransitionFinish_noEvents_doesNotComplete() =
        testScope.runTest {
            val deferred = async { underTest.waitForTransitionFinish() }

            runCurrent()

            assertThat(deferred.isCompleted).isFalse()
            deferred.cancel()
        }

    @Test
    fun waitForTransitionFinish_finishEvent_completes() =
        testScope.runTest {
            val deferred = async { underTest.waitForTransitionFinish() }

            runCurrent()
            unfoldTransitionProgressProvider.onTransitionFinished()
            runCurrent()

            assertThat(deferred.isCompleted).isTrue()
            deferred.cancel()
        }

    @Test
    fun waitForTransitionFinish_otherEvent_doesNotComplete() =
        testScope.runTest {
            val deferred = async { underTest.waitForTransitionFinish() }

            runCurrent()
            unfoldTransitionProgressProvider.onTransitionStarted()
            runCurrent()

            assertThat(deferred.isCompleted).isFalse()
            deferred.cancel()
        }

    @Test
    fun unfoldProgress() =
        testScope.runTest {
            val progress by collectLastValue(underTest.unfoldProgress)
            runCurrent()

            unfoldTransitionProgressProvider.onTransitionStarted()
            assertThat(progress).isEqualTo(1f)

            repeat(10) { repetition ->
                val transitionProgress = 0.1f * (repetition + 1)
                unfoldTransitionProgressProvider.onTransitionProgress(transitionProgress)
                assertThat(progress).isEqualTo(transitionProgress)
            }

            unfoldTransitionProgressProvider.onTransitionFinishing()
            assertThat(progress).isEqualTo(1f)

            unfoldTransitionProgressProvider.onTransitionFinished()
            assertThat(progress).isEqualTo(1f)
        }
}

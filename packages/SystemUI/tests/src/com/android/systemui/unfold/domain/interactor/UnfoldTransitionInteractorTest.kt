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

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.unfold.TestUnfoldTransitionProvider
import com.android.systemui.unfold.data.repository.UnfoldTransitionRepositoryImpl
import com.google.common.truth.Truth.assertThat
import java.util.Optional
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidTestingRunner::class)
open class UnfoldTransitionInteractorTest : SysuiTestCase() {

    private val testScope = TestScope()

    private val unfoldTransitionProgressProvider = TestUnfoldTransitionProvider()
    private val unfoldTransitionRepository =
        UnfoldTransitionRepositoryImpl(Optional.of(unfoldTransitionProgressProvider))

    private lateinit var underTest: UnfoldTransitionInteractor

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        underTest = UnfoldTransitionInteractorImpl(unfoldTransitionRepository)
    }

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
}

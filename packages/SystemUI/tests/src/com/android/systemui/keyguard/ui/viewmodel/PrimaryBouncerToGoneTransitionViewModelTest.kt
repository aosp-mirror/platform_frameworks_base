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

package com.android.systemui.keyguard.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.domain.interactor.PrimaryBouncerInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.ScrimAlpha
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.util.mockito.whenever
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class PrimaryBouncerToGoneTransitionViewModelTest : SysuiTestCase() {
    private lateinit var underTest: PrimaryBouncerToGoneTransitionViewModel
    private lateinit var repository: FakeKeyguardTransitionRepository
    @Mock private lateinit var statusBarStateController: SysuiStatusBarStateController
    @Mock private lateinit var primaryBouncerInteractor: PrimaryBouncerInteractor

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        repository = FakeKeyguardTransitionRepository()
        val interactor = KeyguardTransitionInteractor(repository)
        underTest =
            PrimaryBouncerToGoneTransitionViewModel(
                interactor,
                statusBarStateController,
                primaryBouncerInteractor
            )

        whenever(primaryBouncerInteractor.willRunDismissFromKeyguard()).thenReturn(false)
        whenever(statusBarStateController.leaveOpenOnKeyguardHide()).thenReturn(false)
    }

    @Test
    fun bouncerAlpha() =
        runTest(UnconfinedTestDispatcher()) {
            val values = mutableListOf<Float>()

            val job = underTest.bouncerAlpha.onEach { values.add(it) }.launchIn(this)

            repository.sendTransitionStep(step(0f, TransitionState.STARTED))
            repository.sendTransitionStep(step(0.3f))
            repository.sendTransitionStep(step(0.6f))

            assertThat(values.size).isEqualTo(3)
            values.forEach { assertThat(it).isIn(Range.closed(0f, 1f)) }

            job.cancel()
        }

    @Test
    fun bouncerAlpha_runDimissFromKeyguard() =
        runTest(UnconfinedTestDispatcher()) {
            val values = mutableListOf<Float>()

            val job = underTest.bouncerAlpha.onEach { values.add(it) }.launchIn(this)

            whenever(primaryBouncerInteractor.willRunDismissFromKeyguard()).thenReturn(true)

            repository.sendTransitionStep(step(0f, TransitionState.STARTED))
            repository.sendTransitionStep(step(0.3f))
            repository.sendTransitionStep(step(0.6f))

            assertThat(values.size).isEqualTo(3)
            values.forEach { assertThat(it).isEqualTo(0f) }

            job.cancel()
        }

    @Test
    fun scrimAlpha_runDimissFromKeyguard() =
        runTest(UnconfinedTestDispatcher()) {
            val values = mutableListOf<ScrimAlpha>()

            val job = underTest.scrimAlpha.onEach { values.add(it) }.launchIn(this)

            whenever(primaryBouncerInteractor.willRunDismissFromKeyguard()).thenReturn(true)

            repository.sendTransitionStep(step(0f, TransitionState.STARTED))
            repository.sendTransitionStep(step(0.3f))
            repository.sendTransitionStep(step(0.6f))
            repository.sendTransitionStep(step(1f))

            assertThat(values.size).isEqualTo(4)
            values.forEach { assertThat(it).isEqualTo(ScrimAlpha()) }

            job.cancel()
        }

    @Test
    fun scrimBehindAlpha_leaveShadeOpen() =
        runTest(UnconfinedTestDispatcher()) {
            val values = mutableListOf<ScrimAlpha>()

            val job = underTest.scrimAlpha.onEach { values.add(it) }.launchIn(this)

            whenever(statusBarStateController.leaveOpenOnKeyguardHide()).thenReturn(true)

            repository.sendTransitionStep(step(0f, TransitionState.STARTED))
            repository.sendTransitionStep(step(0.3f))
            repository.sendTransitionStep(step(0.6f))
            repository.sendTransitionStep(step(1f))

            assertThat(values.size).isEqualTo(4)
            values.forEach {
                assertThat(it).isEqualTo(ScrimAlpha(notificationsAlpha = 1f, behindAlpha = 1f))
            }

            job.cancel()
        }

    @Test
    fun scrimBehindAlpha_doNotLeaveShadeOpen() =
        runTest(UnconfinedTestDispatcher()) {
            val values = mutableListOf<ScrimAlpha>()

            val job = underTest.scrimAlpha.onEach { values.add(it) }.launchIn(this)

            whenever(statusBarStateController.leaveOpenOnKeyguardHide()).thenReturn(false)

            repository.sendTransitionStep(step(0f, TransitionState.STARTED))
            repository.sendTransitionStep(step(0.3f))
            repository.sendTransitionStep(step(0.6f))
            repository.sendTransitionStep(step(1f))

            assertThat(values.size).isEqualTo(4)
            values.forEach { assertThat(it.notificationsAlpha).isEqualTo(0f) }
            values.forEach { assertThat(it.frontAlpha).isEqualTo(0f) }
            values.forEach { assertThat(it.behindAlpha).isIn(Range.closed(0f, 1f)) }
            assertThat(values[3].behindAlpha).isEqualTo(0f)

            job.cancel()
        }

    private fun step(
        value: Float,
        state: TransitionState = TransitionState.RUNNING
    ): TransitionStep {
        return TransitionStep(
            from = KeyguardState.PRIMARY_BOUNCER,
            to = KeyguardState.GONE,
            value = value,
            transitionState = state,
            ownerName = "PrimaryBouncerToGoneTransitionViewModelTest"
        )
    }
}

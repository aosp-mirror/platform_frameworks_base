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

package com.android.systemui.keyguard.util

import androidx.core.animation.ValueAnimator
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionInfo
import com.android.systemui.keyguard.shared.model.TransitionModeOnCanceled
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.util.mockito.withArgCaptor
import com.google.common.truth.FailureMetadata
import com.google.common.truth.Subject
import com.google.common.truth.Truth
import com.google.common.truth.Truth.assertAbout
import com.google.common.truth.Truth.assertThat
import junit.framework.Assert.assertEquals
import org.junit.Assert.fail
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor

/** [Subject] used to make assertions about a [Mockito.spy] KeyguardTransitionRepository. */
class KeyguardTransitionRepositorySpySubject
private constructor(
    failureMetadata: FailureMetadata,
    private val repository: KeyguardTransitionRepository,
) : Subject(failureMetadata, repository) {

    /**
     * Asserts that we started a transition to the given state, optionally checking additional
     * parameters. If an animator param or assertion is not provided, we will not assert anything
     * about the animator.
     */
    suspend fun startedTransition(
        ownerName: String? = null,
        from: KeyguardState? = null,
        to: KeyguardState,
        modeOnCanceled: TransitionModeOnCanceled? = null,
    ) {
        startedTransition(ownerName, from, to, {}, modeOnCanceled)
    }

    /**
     * Asserts that we started a transition to the given state, optionally verifying additional
     * params.
     */
    suspend fun startedTransition(
        ownerName: String? = null,
        from: KeyguardState? = null,
        to: KeyguardState,
        animator: ValueAnimator?,
        modeOnCanceled: TransitionModeOnCanceled? = null,
    ) {
        startedTransition(ownerName, from, to, { assertEquals(animator, it) }, modeOnCanceled)
    }

    /**
     * Asserts that we started a transition to the given state, optionally verifying additional
     * params.
     */
    suspend fun startedTransition(
        ownerName: String? = null,
        from: KeyguardState? = null,
        to: KeyguardState,
        animatorAssertion: (Subject) -> Unit,
        modeOnCanceled: TransitionModeOnCanceled? = null,
    ) {
        withArgCaptor<TransitionInfo> { verify(repository).startTransition(capture()) }
            .also { transitionInfo ->
                assertEquals(to, transitionInfo.to)
                animatorAssertion.invoke(Truth.assertThat(transitionInfo.animator))
                from?.let { assertEquals(it, transitionInfo.from) }
                ownerName?.let { assertEquals(it, transitionInfo.ownerName) }
                modeOnCanceled?.let { assertEquals(it, transitionInfo.modeOnCanceled) }
            }
    }

    /**
     * Asserts that we started a transition to the given state, optionally verifying additional
     * params.
     */
    suspend fun updatedTransition(value: Float, state: TransitionState) {
        val valueCaptor = argumentCaptor<Float>()
        val stateCaptor = argumentCaptor<TransitionState>()

        verify(repository).updateTransition(any(), valueCaptor.capture(), stateCaptor.capture())

        assertThat(value).isEqualTo(valueCaptor.firstValue)
        assertThat(state).isEqualTo(stateCaptor.firstValue)
    }

    /** Verifies that [KeyguardTransitionRepository.startTransition] was never called. */
    suspend fun noTransitionsStarted() {
        verify(repository, never()).startTransition(any())
    }

    companion object {
        fun assertThat(
            repository: KeyguardTransitionRepository
        ): KeyguardTransitionRepositorySpySubject =
            assertAbout { failureMetadata, repository: KeyguardTransitionRepository ->
                    if (!Mockito.mockingDetails(repository).isSpy) {
                        fail(
                            "Cannot assert on a non-spy KeyguardTransitionRepository. " +
                                "Use Mockito.spy(keyguardTransitionRepository)."
                        )
                    }
                    KeyguardTransitionRepositorySpySubject(failureMetadata, repository)
                }
                .that(repository)
    }
}

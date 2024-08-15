/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.biometrics

import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.biometrics.UdfpsKeyguardViewLegacy.ANIMATE_APPEAR_ON_SCREEN_OFF
import com.android.systemui.biometrics.UdfpsKeyguardViewLegacy.ANIMATION_BETWEEN_AOD_AND_LOCKSCREEN
import com.android.systemui.biometrics.domain.interactor.udfpsOverlayInteractor
import com.android.systemui.bouncer.data.repository.fakeKeyguardBouncerRepository
import com.android.systemui.bouncer.domain.interactor.alternateBouncerInteractor
import com.android.systemui.bouncer.domain.interactor.primaryBouncerInteractor
import com.android.systemui.bouncer.shared.constants.KeyguardBouncerConstants
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.keyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@RunWith(AndroidJUnit4::class)
@SmallTest
@TestableLooper.RunWithLooper
@kotlinx.coroutines.ExperimentalCoroutinesApi
class UdfpsKeyguardViewLegacyControllerWithCoroutinesTest :
    UdfpsKeyguardViewLegacyControllerBaseTest() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val keyguardBouncerRepository = kosmos.fakeKeyguardBouncerRepository
    private val transitionRepository = kosmos.fakeKeyguardTransitionRepository

    @Before
    override fun setUp() {
        allowTestableLooperAsMainThread() // repeatWhenAttached requires the main thread
        MockitoAnnotations.initMocks(this)
        super.setUp()
    }

    override fun createUdfpsKeyguardViewController(): UdfpsKeyguardViewControllerLegacy {
        mPrimaryBouncerInteractor = kosmos.primaryBouncerInteractor
        mAlternateBouncerInteractor = kosmos.alternateBouncerInteractor
        mKeyguardTransitionInteractor = kosmos.keyguardTransitionInteractor
        mUdfpsOverlayInteractor = kosmos.udfpsOverlayInteractor
        return createUdfpsKeyguardViewController(/* useModernBouncer */ true)
    }

    @Test
    fun bouncerExpansionChange_fadeIn() =
        testScope.runTest {
            // GIVEN view is attached
            mController.onViewAttached()
            captureKeyguardStateControllerCallback()
            Mockito.reset(mView)

            // WHEN status bar expansion is 0
            val job = mController.listenForBouncerExpansion(this)
            keyguardBouncerRepository.setPrimaryShow(true)
            keyguardBouncerRepository.setPanelExpansion(KeyguardBouncerConstants.EXPANSION_VISIBLE)
            runCurrent()

            // THEN alpha is 0
            verify(mView).unpausedAlpha = 0

            job.cancel()
        }

    @Test
    fun bouncerExpansionChange_pauseAuth() =
        testScope.runTest {
            // GIVEN view is attached + on the keyguard
            mController.onViewAttached()
            captureStatusBarStateListeners()
            sendStatusBarStateChanged(StatusBarState.KEYGUARD)
            Mockito.reset(mView)

            // WHEN panelViewExpansion changes to hide
            whenever(mView.unpausedAlpha).thenReturn(0)
            val job = mController.listenForBouncerExpansion(this)
            keyguardBouncerRepository.setPrimaryShow(true)
            keyguardBouncerRepository.setPanelExpansion(KeyguardBouncerConstants.EXPANSION_VISIBLE)
            runCurrent()

            // THEN pause auth is updated to PAUSE
            verify(mView, Mockito.atLeastOnce()).setPauseAuth(true)

            job.cancel()
        }

    @Test
    fun bouncerExpansionChange_unpauseAuth() =
        testScope.runTest {
            // GIVEN view is attached + on the keyguard + panel expansion is 0f
            mController.onViewAttached()
            captureStatusBarStateListeners()
            sendStatusBarStateChanged(StatusBarState.KEYGUARD)
            Mockito.reset(mView)

            // WHEN panelViewExpansion changes to expanded
            whenever(mView.unpausedAlpha).thenReturn(255)
            val job = mController.listenForBouncerExpansion(this)
            keyguardBouncerRepository.setPrimaryShow(true)
            keyguardBouncerRepository.setPanelExpansion(KeyguardBouncerConstants.EXPANSION_HIDDEN)
            runCurrent()

            // THEN pause auth is updated to NOT pause
            verify(mView, Mockito.atLeastOnce()).setPauseAuth(false)

            job.cancel()
        }

    @Test
    fun shadeLocked_showAlternateBouncer_unpauseAuth() =
        testScope.runTest {
            // GIVEN view is attached + on the SHADE_LOCKED (udfps view not showing)
            mController.onViewAttached()
            captureStatusBarStateListeners()
            sendStatusBarStateChanged(StatusBarState.SHADE_LOCKED)

            // WHEN alternate bouncer is requested
            val job = mController.listenForAlternateBouncerVisibility(this)
            keyguardBouncerRepository.setAlternateVisible(true)
            runCurrent()

            // THEN udfps view will animate in & pause auth is updated to NOT pause
            verify(mView).animateInUdfpsBouncer(any())
            assertFalse(mController.shouldPauseAuth())

            job.cancel()
        }

    /** After migration to MODERN_BOUNCER, replaces UdfpsKeyguardViewControllerTest version */
    @Test
    fun shouldPauseAuthBouncerShowing() =
        testScope.runTest {
            // GIVEN view attached and we're on the keyguard
            mController.onViewAttached()
            captureStatusBarStateListeners()
            sendStatusBarStateChanged(StatusBarState.KEYGUARD)

            // WHEN the bouncer expansion is VISIBLE
            val job = mController.listenForBouncerExpansion(this)
            keyguardBouncerRepository.setPrimaryShow(true)
            keyguardBouncerRepository.setPanelExpansion(KeyguardBouncerConstants.EXPANSION_VISIBLE)
            runCurrent()

            // THEN UDFPS shouldPauseAuth == true
            assertTrue(mController.shouldPauseAuth())

            job.cancel()
        }

    @Test
    fun shouldHandleTouchesChange() =
        testScope.runTest {
            val shouldHandleTouches by collectLastValue(mUdfpsOverlayInteractor.shouldHandleTouches)

            // GIVEN view is attached + on the keyguard
            mController.onViewAttached()
            captureStatusBarStateListeners()
            sendStatusBarStateChanged(StatusBarState.KEYGUARD)
            whenever(mView.setPauseAuth(true)).thenReturn(true)
            whenever(mView.unpausedAlpha).thenReturn(0)

            // WHEN panelViewExpansion changes to expanded
            val job = mController.listenForBouncerExpansion(this)
            keyguardBouncerRepository.setPrimaryShow(true)
            keyguardBouncerRepository.setPanelExpansion(KeyguardBouncerConstants.EXPANSION_VISIBLE)
            runCurrent()

            // THEN UDFPS auth is paused and should not handle touches
            assertThat(mController.shouldPauseAuth()).isTrue()
            assertThat(shouldHandleTouches!!).isFalse()

            job.cancel()
        }

    @Test
    fun shouldHandleTouchesOnDetach() =
        testScope.runTest {
            val shouldHandleTouches by collectLastValue(mUdfpsOverlayInteractor.shouldHandleTouches)

            // GIVEN view is attached + on the keyguard
            mController.onViewAttached()
            captureStatusBarStateListeners()
            sendStatusBarStateChanged(StatusBarState.KEYGUARD)
            whenever(mView.setPauseAuth(true)).thenReturn(true)
            whenever(mView.unpausedAlpha).thenReturn(0)

            // WHEN panelViewExpansion changes to expanded
            val job = mController.listenForBouncerExpansion(this)
            keyguardBouncerRepository.setPrimaryShow(true)
            keyguardBouncerRepository.setPanelExpansion(KeyguardBouncerConstants.EXPANSION_VISIBLE)
            runCurrent()

            mController.onViewDetached()

            // THEN UDFPS auth is paused and should not handle touches
            assertThat(mController.shouldPauseAuth()).isTrue()
            assertThat(shouldHandleTouches!!).isFalse()

            job.cancel()
        }

    @Test
    fun fadeFromDialogSuggestedAlpha() =
        testScope.runTest {
            // GIVEN view is attached and status bar expansion is 1f
            mController.onViewAttached()
            captureStatusBarStateListeners()
            val job = mController.listenForBouncerExpansion(this)
            keyguardBouncerRepository.setPrimaryShow(true)
            keyguardBouncerRepository.setPanelExpansion(KeyguardBouncerConstants.EXPANSION_HIDDEN)
            runCurrent()
            Mockito.reset(mView)

            // WHEN dialog suggested alpha is .6f
            whenever(mView.dialogSuggestedAlpha).thenReturn(.6f)
            sendStatusBarStateChanged(StatusBarState.KEYGUARD)

            // THEN alpha is updated based on dialog suggested alpha
            verify(mView).unpausedAlpha = (.6f * 255).toInt()

            job.cancel()
        }

    @Test
    fun transitionToFullShadeProgress() =
        testScope.runTest {
            // GIVEN view is attached and status bar expansion is 1f
            mController.onViewAttached()
            val job = mController.listenForBouncerExpansion(this)
            keyguardBouncerRepository.setPrimaryShow(true)
            keyguardBouncerRepository.setPanelExpansion(KeyguardBouncerConstants.EXPANSION_HIDDEN)
            runCurrent()
            Mockito.reset(mView)
            whenever(mView.dialogSuggestedAlpha).thenReturn(1f)

            // WHEN we're transitioning to the full shade
            val transitionProgress = .6f
            mController.setTransitionToFullShadeProgress(transitionProgress)

            // THEN alpha is between 0 and 255
            verify(mView).unpausedAlpha = ((1f - transitionProgress) * 255).toInt()

            job.cancel()
        }

    @Test
    fun aodToLockscreen_dozeAmountChanged() =
        testScope.runTest {
            // GIVEN view is attached
            mController.onViewAttached()
            Mockito.reset(mView)

            val job = mController.listenForLockscreenAodTransitions(this)

            // WHEN transitioning from lockscreen to aod
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.AOD,
                    value = .3f,
                    transitionState = TransitionState.RUNNING
                )
            )
            runCurrent()
            // THEN doze amount is updated
            verify(mView)
                .onDozeAmountChanged(
                    eq(.3f),
                    eq(.3f),
                    eq(UdfpsKeyguardViewLegacy.ANIMATION_BETWEEN_AOD_AND_LOCKSCREEN)
                )

            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.AOD,
                    value = 1f,
                    transitionState = TransitionState.FINISHED
                )
            )
            runCurrent()
            // THEN doze amount is updated
            verify(mView)
                .onDozeAmountChanged(
                    eq(1f),
                    eq(1f),
                    eq(UdfpsKeyguardViewLegacy.ANIMATION_BETWEEN_AOD_AND_LOCKSCREEN)
                )

            job.cancel()
        }

    @Test
    fun lockscreenToAod_dozeAmountChanged() =
        testScope.runTest {
            // GIVEN view is attached
            mController.onViewAttached()
            Mockito.reset(mView)

            val job = mController.listenForLockscreenAodTransitions(this)

            // WHEN transitioning from lockscreen to aod
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.AOD,
                    value = .3f,
                    transitionState = TransitionState.RUNNING
                )
            )
            runCurrent()
            // THEN doze amount is updated
            verify(mView)
                .onDozeAmountChanged(
                    eq(.3f),
                    eq(.3f),
                    eq(UdfpsKeyguardViewLegacy.ANIMATION_BETWEEN_AOD_AND_LOCKSCREEN)
                )

            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.AOD,
                    value = 1f,
                    transitionState = TransitionState.FINISHED
                )
            )
            runCurrent()
            // THEN doze amount is updated
            verify(mView)
                .onDozeAmountChanged(
                    eq(1f),
                    eq(1f),
                    eq(UdfpsKeyguardViewLegacy.ANIMATION_BETWEEN_AOD_AND_LOCKSCREEN)
                )

            job.cancel()
        }

    @Test
    fun goneToAod_dozeAmountChanged() =
        testScope.runTest {
            // GIVEN view is attached
            mController.onViewAttached()
            Mockito.reset(mView)

            val job = mController.listenForGoneToAodTransition(this)

            // WHEN transitioning from lockscreen to aod
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.GONE,
                    to = KeyguardState.AOD,
                    value = .3f,
                    transitionState = TransitionState.RUNNING
                )
            )
            runCurrent()
            // THEN doze amount is updated
            verify(mView)
                .onDozeAmountChanged(
                    eq(.3f),
                    eq(.3f),
                    eq(UdfpsKeyguardViewLegacy.ANIMATE_APPEAR_ON_SCREEN_OFF)
                )

            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.GONE,
                    to = KeyguardState.AOD,
                    value = 1f,
                    transitionState = TransitionState.FINISHED
                )
            )
            runCurrent()
            // THEN doze amount is updated
            verify(mView)
                .onDozeAmountChanged(
                    eq(1f),
                    eq(1f),
                    eq(UdfpsKeyguardViewLegacy.ANIMATE_APPEAR_ON_SCREEN_OFF)
                )

            job.cancel()
        }

    @Test
    fun aodToOccluded_dozeAmountChanged() =
        testScope.runTest {
            // GIVEN view is attached
            mController.onViewAttached()
            Mockito.reset(mView)

            val job = mController.listenForAodToOccludedTransitions(this)

            // WHEN transitioning from aod to occluded
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.AOD,
                    to = KeyguardState.OCCLUDED,
                    value = .3f,
                    transitionState = TransitionState.RUNNING
                )
            )
            runCurrent()
            // THEN doze amount is updated
            verify(mView)
                .onDozeAmountChanged(eq(.7f), eq(.7f), eq(UdfpsKeyguardViewLegacy.ANIMATION_NONE))

            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.AOD,
                    to = KeyguardState.OCCLUDED,
                    value = 1f,
                    transitionState = TransitionState.FINISHED
                )
            )
            runCurrent()
            // THEN doze amount is updated
            verify(mView)
                .onDozeAmountChanged(eq(0f), eq(0f), eq(UdfpsKeyguardViewLegacy.ANIMATION_NONE))

            job.cancel()
        }

    @Test
    fun occludedToAod_dozeAmountChanged() =
        testScope.runTest {
            // GIVEN view is attached
            mController.onViewAttached()
            Mockito.reset(mView)

            val job = mController.listenForOccludedToAodTransition(this)

            // WHEN transitioning from occluded to aod
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.OCCLUDED,
                    to = KeyguardState.AOD,
                    value = .3f,
                    transitionState = TransitionState.RUNNING
                )
            )
            runCurrent()
            // THEN doze amount is updated
            verify(mView)
                .onDozeAmountChanged(
                    eq(.3f),
                    eq(.3f),
                    eq(UdfpsKeyguardViewLegacy.ANIMATE_APPEAR_ON_SCREEN_OFF)
                )

            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.OCCLUDED,
                    to = KeyguardState.AOD,
                    value = 1f,
                    transitionState = TransitionState.FINISHED
                )
            )
            runCurrent()
            // THEN doze amount is updated
            verify(mView)
                .onDozeAmountChanged(
                    eq(1f),
                    eq(1f),
                    eq(UdfpsKeyguardViewLegacy.ANIMATE_APPEAR_ON_SCREEN_OFF)
                )

            job.cancel()
        }

    @Test
    fun cancelledAodToLockscreen_dozeAmountChangedToZero() =
        testScope.runTest {
            // GIVEN view is attached
            mController.onViewAttached()
            val job = mController.listenForLockscreenAodTransitions(this)
            runCurrent()
            Mockito.reset(mView)

            // WHEN aod to lockscreen transition is cancelled
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.AOD,
                    to = KeyguardState.LOCKSCREEN,
                    value = 1f,
                    transitionState = TransitionState.CANCELED
                )
            )
            runCurrent()
            // ... and WHEN the next transition is from lockscreen => occluded
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.OCCLUDED,
                    value = .4f,
                    transitionState = TransitionState.STARTED
                )
            )
            runCurrent()

            // THEN doze amount is updated to zero
            verify(mView)
                .onDozeAmountChanged(eq(0f), eq(0f), eq(ANIMATION_BETWEEN_AOD_AND_LOCKSCREEN))
            job.cancel()
        }

    @Test
    fun cancelledLockscreenToAod_dozeAmountNotUpdatedToZero() =
        testScope.runTest {
            // GIVEN view is attached
            mController.onViewAttached()
            val job = mController.listenForLockscreenAodTransitions(this)
            runCurrent()
            Mockito.reset(mView)

            // WHEN lockscreen to aod transition is cancelled
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.AOD,
                    value = 1f,
                    transitionState = TransitionState.CANCELED
                )
            )
            runCurrent()

            // THEN doze amount is NOT updated to zero
            verify(mView, never()).onDozeAmountChanged(eq(0f), eq(0f), anyInt())
            job.cancel()
        }

    @Test
    fun dreamingToAod_dozeAmountChanged() =
        testScope.runTest {
            // GIVEN view is attached
            mController.onViewAttached()
            Mockito.reset(mView)

            val job = mController.listenForDreamingToAodTransitions(this)
            // WHEN dreaming to aod transition in progress
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.DREAMING,
                    to = KeyguardState.AOD,
                    value = .3f,
                    transitionState = TransitionState.RUNNING
                )
            )
            runCurrent()

            // THEN doze amount is updated to
            verify(mView).onDozeAmountChanged(eq(.3f), eq(.3f), eq(ANIMATE_APPEAR_ON_SCREEN_OFF))
            job.cancel()
        }

    @Test
    fun alternateBouncerToAod_dozeAmountChanged() =
        testScope.runTest {
            // GIVEN view is attached
            mController.onViewAttached()
            Mockito.reset(mView)

            val job = mController.listenForAlternateBouncerToAodTransitions(this)
            // WHEN alternate bouncer to aod transition in progress
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.ALTERNATE_BOUNCER,
                    to = KeyguardState.AOD,
                    value = .3f,
                    transitionState = TransitionState.RUNNING
                )
            )
            runCurrent()

            // THEN doze amount is updated to
            verify(mView)
                .onDozeAmountChanged(eq(.3f), eq(.3f), eq(ANIMATION_BETWEEN_AOD_AND_LOCKSCREEN))
            job.cancel()
        }

    @Test
    fun bouncerToAod_dozeAmountChanged() =
        testScope.runTest {
            // GIVEN view is attached
            mController.onViewAttached()
            Mockito.reset(mView)

            val job = mController.listenForPrimaryBouncerToAodTransitions(this)
            // WHEN alternate bouncer to aod transition in progress
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.PRIMARY_BOUNCER,
                    to = KeyguardState.AOD,
                    value = .3f,
                    transitionState = TransitionState.RUNNING
                )
            )
            runCurrent()

            // THEN doze amount is updated to
            verify(mView).onDozeAmountChanged(eq(.3f), eq(.3f), eq(ANIMATE_APPEAR_ON_SCREEN_OFF))
            job.cancel()
        }
}

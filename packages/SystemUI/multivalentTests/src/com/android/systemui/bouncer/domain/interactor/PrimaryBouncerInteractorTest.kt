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
 * limitations under the License
 */

package com.android.systemui.bouncer.domain.interactor

import android.testing.TestableLooper.RunWithLooper
import android.testing.TestableResources
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardSecurityModel
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.DejankUtils
import com.android.systemui.SysuiTestCase
import com.android.systemui.bouncer.data.repository.KeyguardBouncerRepository
import com.android.systemui.bouncer.shared.constants.KeyguardBouncerConstants.EXPANSION_HIDDEN
import com.android.systemui.bouncer.shared.constants.KeyguardBouncerConstants.EXPANSION_VISIBLE
import com.android.systemui.bouncer.shared.model.BouncerShowMessageModel
import com.android.systemui.bouncer.ui.BouncerView
import com.android.systemui.bouncer.ui.BouncerViewDelegate
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryFaceAuthInteractor
import com.android.systemui.keyguard.DismissCallbackRegistry
import com.android.systemui.keyguard.data.repository.FakeTrustRepository
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import com.android.systemui.utils.os.FakeHandler
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWithLooper(setAsMainLooper = true)
@RunWith(AndroidJUnit4::class)
class PrimaryBouncerInteractorTest : SysuiTestCase() {
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private lateinit var repository: KeyguardBouncerRepository
    @Mock(answer = Answers.RETURNS_DEEP_STUBS) private lateinit var bouncerView: BouncerView
    @Mock private lateinit var bouncerViewDelegate: BouncerViewDelegate
    @Mock private lateinit var keyguardStateController: KeyguardStateController
    @Mock private lateinit var keyguardSecurityModel: KeyguardSecurityModel
    @Mock private lateinit var mPrimaryBouncerCallbackInteractor: PrimaryBouncerCallbackInteractor
    @Mock private lateinit var falsingCollector: FalsingCollector
    @Mock private lateinit var dismissCallbackRegistry: DismissCallbackRegistry
    @Mock private lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor
    @Mock private lateinit var mSelectedUserInteractor: SelectedUserInteractor
    @Mock private lateinit var faceAuthInteractor: DeviceEntryFaceAuthInteractor
    private lateinit var mainHandler: FakeHandler
    private lateinit var underTest: PrimaryBouncerInteractor
    private lateinit var resources: TestableResources
    private lateinit var trustRepository: FakeTrustRepository
    private lateinit var testScope: TestScope

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(keyguardSecurityModel.getSecurityMode(anyInt()))
            .thenReturn(KeyguardSecurityModel.SecurityMode.PIN)

        DejankUtils.setImmediate(true)
        testScope = TestScope()
        mainHandler = FakeHandler(android.os.Looper.getMainLooper())
        trustRepository = FakeTrustRepository()
        underTest =
            PrimaryBouncerInteractor(
                repository,
                bouncerView,
                mainHandler,
                keyguardStateController,
                keyguardSecurityModel,
                mPrimaryBouncerCallbackInteractor,
                falsingCollector,
                dismissCallbackRegistry,
                context,
                keyguardUpdateMonitor,
                trustRepository,
                testScope.backgroundScope,
                mSelectedUserInteractor,
                faceAuthInteractor,
            )
        whenever(repository.primaryBouncerStartingDisappearAnimation.value).thenReturn(null)
        whenever(repository.primaryBouncerShow.value).thenReturn(false)
        whenever(bouncerView.delegate).thenReturn(bouncerViewDelegate)
        resources = context.orCreateTestableResources
    }

    @Test
    fun show_nullDelegate() {
        testScope.run {
            whenever(bouncerView.delegate).thenReturn(null)
            mainHandler.setMode(FakeHandler.Mode.QUEUEING)

            // WHEN bouncer show is requested
            underTest.show(true)

            // WHEN all queued messages are dispatched
            mainHandler.dispatchQueuedMessages()

            // THEN primary bouncer state doesn't update to show since delegate was null
            verify(repository, never()).setPrimaryShow(true)
            verify(repository, never()).setPrimaryShowingSoon(false)
            verify(mPrimaryBouncerCallbackInteractor, never()).dispatchStartingToShow()
            verify(mPrimaryBouncerCallbackInteractor, never())
                .dispatchVisibilityChanged(View.VISIBLE)
        }
    }

    @Test
    fun testShow_isScrimmed() {
        underTest.show(true)
        verify(repository).setKeyguardAuthenticatedBiometrics(null)
        verify(repository).setPrimaryStartingToHide(false)
        verify(repository).setPrimaryScrimmed(true)
        verify(repository).setPanelExpansion(EXPANSION_VISIBLE)
        verify(repository).setPrimaryShowingSoon(true)
        verify(keyguardStateController).notifyPrimaryBouncerShowing(true)
        verify(mPrimaryBouncerCallbackInteractor).dispatchStartingToShow()
        verify(repository).setPrimaryShow(true)
        verify(repository).setPrimaryShowingSoon(false)
        verify(mPrimaryBouncerCallbackInteractor).dispatchVisibilityChanged(View.VISIBLE)
    }

    @Test
    fun testShow_isNotScrimmed() {
        verify(repository, never()).setPanelExpansion(EXPANSION_VISIBLE)
    }

    @Test
    fun testShow_keyguardIsDone() {
        whenever(bouncerView.delegate?.showNextSecurityScreenOrFinish()).thenReturn(true)
        verify(keyguardStateController, never()).notifyPrimaryBouncerShowing(true)
        verify(mPrimaryBouncerCallbackInteractor, never()).dispatchStartingToShow()
    }

    @Test
    fun testShowReturnsFalseWhenDelegateIsNotSet() {
        whenever(bouncerView.delegate).thenReturn(null)
        assertThat(underTest.show(true)).isEqualTo(false)
    }

    @Test
    fun testShow_isResumed() {
        whenever(repository.primaryBouncerShow.value).thenReturn(true)
        whenever(keyguardSecurityModel.getSecurityMode(anyInt()))
            .thenReturn(KeyguardSecurityModel.SecurityMode.SimPuk)

        underTest.show(true)
        verify(repository).setPrimaryShow(false)
        verify(repository).setPrimaryShow(true)
    }

    @Test
    fun testHide() {
        underTest.hide()
        verify(falsingCollector).onBouncerHidden()
        verify(keyguardStateController).notifyPrimaryBouncerShowing(false)
        verify(repository).setPrimaryShowingSoon(false)
        verify(repository).setPrimaryShow(false)
        verify(mPrimaryBouncerCallbackInteractor).dispatchVisibilityChanged(View.INVISIBLE)
        verify(repository).setPrimaryStartDisappearAnimation(null)
        verify(repository).setPanelExpansion(EXPANSION_HIDDEN)
    }

    @Test
    fun testExpansion() {
        whenever(repository.panelExpansionAmount.value).thenReturn(0.5f)
        underTest.setPanelExpansion(0.6f)
        verify(repository).setPanelExpansion(0.6f)
        verify(mPrimaryBouncerCallbackInteractor).dispatchExpansionChanged(0.6f)
    }

    @Test
    fun testExpansion_fullyShown() {
        whenever(repository.panelExpansionAmount.value).thenReturn(0.5f)
        whenever(repository.primaryBouncerStartingDisappearAnimation.value).thenReturn(null)
        underTest.setPanelExpansion(EXPANSION_VISIBLE)
        verify(falsingCollector).onBouncerShown()
        verify(mPrimaryBouncerCallbackInteractor).dispatchFullyShown()
    }

    @Test
    fun testExpansion_fullyHidden() {
        whenever(repository.panelExpansionAmount.value).thenReturn(0.5f)
        whenever(repository.primaryBouncerStartingDisappearAnimation.value).thenReturn(null)
        underTest.setPanelExpansion(EXPANSION_HIDDEN)
        verify(repository).setPrimaryShow(false)
        verify(falsingCollector).onBouncerHidden()
        verify(mPrimaryBouncerCallbackInteractor).dispatchReset()
        verify(mPrimaryBouncerCallbackInteractor).dispatchFullyHidden()
    }

    @Test
    fun testExpansion_startingToHide() {
        whenever(repository.panelExpansionAmount.value).thenReturn(EXPANSION_VISIBLE)
        underTest.setPanelExpansion(0.1f)
        verify(repository).setPrimaryStartingToHide(true)
        verify(mPrimaryBouncerCallbackInteractor).dispatchStartingToHide()
    }

    @Test
    fun testShowMessage() {
        val argCaptor = ArgumentCaptor.forClass(BouncerShowMessageModel::class.java)
        underTest.showMessage("abc", null)
        verify(repository).setShowMessage(argCaptor.capture())
        assertThat(argCaptor.value.message).isEqualTo("abc")
    }

    @Test
    fun testDismissAction() {
        val onDismissAction = mock(ActivityStarter.OnDismissAction::class.java)
        val cancelAction = mock(Runnable::class.java)
        underTest.setDismissAction(onDismissAction, cancelAction)
        verify(bouncerViewDelegate).setDismissAction(onDismissAction, cancelAction)
    }

    @Test
    fun testUpdateResources() {
        underTest.updateResources()
        verify(repository).setResourceUpdateRequests(true)
    }

    @Test
    fun testNotifyKeyguardAuthenticated() {
        underTest.notifyKeyguardAuthenticatedBiometrics(true)
        verify(repository).setKeyguardAuthenticatedBiometrics(true)
    }

    @Test
    fun testNotifyShowedMessage() {
        underTest.onMessageShown()
        verify(repository).setShowMessage(null)
    }

    @Test
    fun testSetKeyguardPosition() {
        underTest.setKeyguardPosition(0f)
        verify(repository).setKeyguardPosition(0f)
    }

    @Test
    fun testNotifyKeyguardAuthenticatedHandled() {
        underTest.notifyKeyguardAuthenticatedHandled()
        verify(repository).setKeyguardAuthenticatedBiometrics(null)
    }

    @Test
    fun testNotifyUpdatedResources() {
        underTest.notifyUpdatedResources()
        verify(repository).setResourceUpdateRequests(false)
    }

    @Test
    fun testSetBackButtonEnabled() {
        underTest.setBackButtonEnabled(true)
        verify(repository).setIsBackButtonEnabled(true)
    }

    @Test
    fun testStartDisappearAnimation_willRunDismissFromKeyguard() {
        whenever(bouncerViewDelegate.willRunDismissFromKeyguard()).thenReturn(true)

        val runnable = mock(Runnable::class.java)
        underTest.startDisappearAnimation(runnable)
        // End runnable should run immediately
        verify(runnable).run()
        // ... while the disappear animation should never be run
        verify(repository, never()).setPrimaryStartDisappearAnimation(any(Runnable::class.java))
    }

    @Test
    fun testStartDisappearAnimation_willNotRunDismissFromKeyguard_() {
        whenever(bouncerViewDelegate.willRunDismissFromKeyguard()).thenReturn(false)

        val runnable = mock(Runnable::class.java)
        underTest.startDisappearAnimation(runnable)
        verify(repository).setPrimaryStartDisappearAnimation(any(Runnable::class.java))
    }

    @Test
    fun testIsFullShowing() {
        whenever(repository.primaryBouncerShow.value).thenReturn(true)
        whenever(repository.panelExpansionAmount.value).thenReturn(EXPANSION_VISIBLE)
        whenever(repository.primaryBouncerStartingDisappearAnimation.value).thenReturn(null)
        assertThat(underTest.isFullyShowing()).isTrue()
        whenever(repository.primaryBouncerShow.value).thenReturn(false)
        assertThat(underTest.isFullyShowing()).isFalse()
    }

    @Test
    fun testIsScrimmed() {
        whenever(repository.primaryBouncerScrimmed.value).thenReturn(true)
        assertThat(underTest.isScrimmed()).isTrue()
        whenever(repository.primaryBouncerScrimmed.value).thenReturn(false)
        assertThat(underTest.isScrimmed()).isFalse()
    }

    @Test
    fun testIsInTransit() {
        whenever(repository.primaryBouncerShowingSoon.value).thenReturn(true)
        assertThat(underTest.isInTransit()).isTrue()
        whenever(repository.primaryBouncerShowingSoon.value).thenReturn(false)
        assertThat(underTest.isInTransit()).isFalse()
        whenever(repository.panelExpansionAmount.value).thenReturn(0.5f)
        assertThat(underTest.isInTransit()).isTrue()
    }

    @Test
    fun testIsAnimatingAway() {
        whenever(repository.primaryBouncerStartingDisappearAnimation.value).thenReturn(Runnable {})
        assertThat(underTest.isAnimatingAway()).isTrue()
        whenever(repository.primaryBouncerStartingDisappearAnimation.value).thenReturn(null)
        assertThat(underTest.isAnimatingAway()).isFalse()
    }

    @Test
    fun testWillDismissWithAction() {
        whenever(bouncerViewDelegate.willDismissWithActions()).thenReturn(true)
        assertThat(underTest.willDismissWithAction()).isTrue()
        whenever(bouncerViewDelegate.willDismissWithActions()).thenReturn(false)
        assertThat(underTest.willDismissWithAction()).isFalse()
    }

    @Test
    fun delayBouncerWhenFaceAuthPossible() {
        mainHandler.setMode(FakeHandler.Mode.QUEUEING)

        // GIVEN bouncer should be delayed due to face auth
        whenever(faceAuthInteractor.canFaceAuthRun()).thenReturn(true)

        // WHEN bouncer show is requested
        underTest.show(true)

        // THEN primary show & primary showing soon aren't updated immediately
        verify(repository, never()).setPrimaryShow(true)
        verify(repository, never()).setPrimaryShowingSoon(false)

        // WHEN all queued messages are dispatched
        mainHandler.dispatchQueuedMessages()

        // THEN primary show & primary showing soon are updated
        verify(repository).setPrimaryShow(true)
        verify(repository).setPrimaryShowingSoon(false)
    }

    @Test
    fun noDelayBouncer_faceAuthNotAllowed() {
        mainHandler.setMode(FakeHandler.Mode.QUEUEING)

        // GIVEN bouncer should not be delayed because device isn't in the right posture for
        // face auth
        whenever(faceAuthInteractor.canFaceAuthRun()).thenReturn(false)

        // WHEN bouncer show is requested
        underTest.show(true)

        // THEN primary show & primary showing soon are updated immediately
        verify(repository).setPrimaryShow(true)
        verify(repository).setPrimaryShowingSoon(false)
    }

    @Test
    fun delayBouncerWhenActiveUnlockPossible() {
        testScope.run {
            mainHandler.setMode(FakeHandler.Mode.QUEUEING)

            // GIVEN bouncer should be delayed due to active unlock
            trustRepository.setCurrentUserActiveUnlockAvailable(true)
            whenever(keyguardUpdateMonitor.canTriggerActiveUnlockBasedOnDeviceState())
                .thenReturn(true)
            runCurrent()

            // WHEN bouncer show is requested
            underTest.show(true)

            // THEN primary show & primary showing soon were scheduled to update
            verify(repository, never()).setPrimaryShow(true)
            verify(repository, never()).setPrimaryShowingSoon(false)

            // WHEN all queued messages are dispatched
            mainHandler.dispatchQueuedMessages()

            // THEN primary show & primary showing soon are updated
            verify(repository).setPrimaryShow(true)
            verify(repository).setPrimaryShowingSoon(false)
        }
    }
}

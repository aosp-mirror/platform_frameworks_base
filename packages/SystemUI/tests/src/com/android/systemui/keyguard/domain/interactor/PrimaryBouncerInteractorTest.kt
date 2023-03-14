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

package com.android.systemui.keyguard.domain.interactor

import android.os.Looper
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import android.view.View
import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardSecurityModel
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.DejankUtils
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.keyguard.DismissCallbackRegistry
import com.android.systemui.keyguard.data.BouncerView
import com.android.systemui.keyguard.data.BouncerViewDelegate
import com.android.systemui.keyguard.data.repository.KeyguardBouncerRepository
import com.android.systemui.keyguard.shared.model.BouncerShowMessageModel
import com.android.systemui.keyguard.shared.model.KeyguardBouncerModel
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.phone.KeyguardBouncer.EXPANSION_HIDDEN
import com.android.systemui.statusbar.phone.KeyguardBouncer.EXPANSION_VISIBLE
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.mockito.any
import com.android.systemui.utils.os.FakeHandler
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
@RunWithLooper(setAsMainLooper = true)
@RunWith(AndroidTestingRunner::class)
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
    @Mock private lateinit var keyguardBypassController: KeyguardBypassController
    @Mock private lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor
    private val mainHandler = FakeHandler(Looper.getMainLooper())
    private lateinit var mPrimaryBouncerInteractor: PrimaryBouncerInteractor

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        DejankUtils.setImmediate(true)
        mPrimaryBouncerInteractor =
            PrimaryBouncerInteractor(
                repository,
                bouncerView,
                mainHandler,
                keyguardStateController,
                keyguardSecurityModel,
                mPrimaryBouncerCallbackInteractor,
                falsingCollector,
                dismissCallbackRegistry,
                keyguardBypassController,
                keyguardUpdateMonitor,
            )
        `when`(repository.primaryBouncerStartingDisappearAnimation.value).thenReturn(null)
        `when`(repository.primaryBouncerShow.value).thenReturn(null)
        `when`(bouncerView.delegate).thenReturn(bouncerViewDelegate)
    }

    @Test
    fun testShow_isScrimmed() {
        mPrimaryBouncerInteractor.show(true)
        verify(repository).setOnScreenTurnedOff(false)
        verify(repository).setKeyguardAuthenticated(null)
        verify(repository).setPrimaryHide(false)
        verify(repository).setPrimaryStartingToHide(false)
        verify(repository).setPrimaryScrimmed(true)
        verify(repository).setPanelExpansion(EXPANSION_VISIBLE)
        verify(repository).setPrimaryShowingSoon(true)
        verify(keyguardStateController).notifyBouncerShowing(true)
        verify(mPrimaryBouncerCallbackInteractor).dispatchStartingToShow()
        verify(repository).setPrimaryVisible(true)
        verify(repository).setPrimaryShow(any(KeyguardBouncerModel::class.java))
        verify(repository).setPrimaryShowingSoon(false)
        verify(mPrimaryBouncerCallbackInteractor).dispatchVisibilityChanged(View.VISIBLE)
    }

    @Test
    fun testShow_isNotScrimmed() {
        verify(repository, never()).setPanelExpansion(EXPANSION_VISIBLE)
    }

    @Test
    fun testShow_keyguardIsDone() {
        `when`(bouncerView.delegate?.showNextSecurityScreenOrFinish()).thenReturn(true)
        verify(keyguardStateController, never()).notifyBouncerShowing(true)
        verify(mPrimaryBouncerCallbackInteractor, never()).dispatchStartingToShow()
    }

    @Test
    fun testHide() {
        mPrimaryBouncerInteractor.hide()
        verify(falsingCollector).onBouncerHidden()
        verify(keyguardStateController).notifyBouncerShowing(false)
        verify(repository).setPrimaryShowingSoon(false)
        verify(repository).setPrimaryVisible(false)
        verify(repository).setPrimaryHide(true)
        verify(repository).setPrimaryShow(null)
        verify(mPrimaryBouncerCallbackInteractor).dispatchVisibilityChanged(View.INVISIBLE)
    }

    @Test
    fun testExpansion() {
        `when`(repository.panelExpansionAmount.value).thenReturn(0.5f)
        mPrimaryBouncerInteractor.setPanelExpansion(0.6f)
        verify(repository).setPanelExpansion(0.6f)
        verify(mPrimaryBouncerCallbackInteractor).dispatchExpansionChanged(0.6f)
    }

    @Test
    fun testExpansion_fullyShown() {
        `when`(repository.panelExpansionAmount.value).thenReturn(0.5f)
        `when`(repository.primaryBouncerStartingDisappearAnimation.value).thenReturn(null)
        mPrimaryBouncerInteractor.setPanelExpansion(EXPANSION_VISIBLE)
        verify(falsingCollector).onBouncerShown()
        verify(mPrimaryBouncerCallbackInteractor).dispatchFullyShown()
    }

    @Test
    fun testExpansion_fullyHidden() {
        `when`(repository.panelExpansionAmount.value).thenReturn(0.5f)
        `when`(repository.primaryBouncerStartingDisappearAnimation.value).thenReturn(null)
        mPrimaryBouncerInteractor.setPanelExpansion(EXPANSION_HIDDEN)
        verify(repository).setPrimaryVisible(false)
        verify(repository).setPrimaryShow(null)
        verify(repository).setPrimaryHide(true)
        verify(falsingCollector).onBouncerHidden()
        verify(mPrimaryBouncerCallbackInteractor).dispatchReset()
        verify(mPrimaryBouncerCallbackInteractor).dispatchFullyHidden()
    }

    @Test
    fun testExpansion_startingToHide() {
        `when`(repository.panelExpansionAmount.value).thenReturn(EXPANSION_VISIBLE)
        mPrimaryBouncerInteractor.setPanelExpansion(0.1f)
        verify(repository).setPrimaryStartingToHide(true)
        verify(mPrimaryBouncerCallbackInteractor).dispatchStartingToHide()
    }

    @Test
    fun testShowMessage() {
        val argCaptor = ArgumentCaptor.forClass(BouncerShowMessageModel::class.java)
        mPrimaryBouncerInteractor.showMessage("abc", null)
        verify(repository).setShowMessage(argCaptor.capture())
        assertThat(argCaptor.value.message).isEqualTo("abc")
    }

    @Test
    fun testDismissAction() {
        val onDismissAction = mock(ActivityStarter.OnDismissAction::class.java)
        val cancelAction = mock(Runnable::class.java)
        mPrimaryBouncerInteractor.setDismissAction(onDismissAction, cancelAction)
        verify(bouncerViewDelegate).setDismissAction(onDismissAction, cancelAction)
    }

    @Test
    fun testUpdateResources() {
        mPrimaryBouncerInteractor.updateResources()
        verify(repository).setResourceUpdateRequests(true)
    }

    @Test
    fun testNotifyKeyguardAuthenticated() {
        mPrimaryBouncerInteractor.notifyKeyguardAuthenticated(true)
        verify(repository).setKeyguardAuthenticated(true)
    }

    @Test
    fun testNotifyShowedMessage() {
        mPrimaryBouncerInteractor.onMessageShown()
        verify(repository).setShowMessage(null)
    }

    @Test
    fun testOnScreenTurnedOff() {
        mPrimaryBouncerInteractor.onScreenTurnedOff()
        verify(repository).setOnScreenTurnedOff(true)
    }

    @Test
    fun testSetKeyguardPosition() {
        mPrimaryBouncerInteractor.setKeyguardPosition(0f)
        verify(repository).setKeyguardPosition(0f)
    }

    @Test
    fun testNotifyKeyguardAuthenticatedHandled() {
        mPrimaryBouncerInteractor.notifyKeyguardAuthenticatedHandled()
        verify(repository).setKeyguardAuthenticated(null)
    }

    @Test
    fun testNotifyUpdatedResources() {
        mPrimaryBouncerInteractor.notifyUpdatedResources()
        verify(repository).setResourceUpdateRequests(false)
    }

    @Test
    fun testSetBackButtonEnabled() {
        mPrimaryBouncerInteractor.setBackButtonEnabled(true)
        verify(repository).setIsBackButtonEnabled(true)
    }

    @Test
    fun testStartDisappearAnimation() {
        val runnable = mock(Runnable::class.java)
        mPrimaryBouncerInteractor.startDisappearAnimation(runnable)
        verify(repository).setPrimaryStartDisappearAnimation(any(Runnable::class.java))
    }

    @Test
    fun testIsFullShowing() {
        `when`(repository.primaryBouncerVisible.value).thenReturn(true)
        `when`(repository.panelExpansionAmount.value).thenReturn(EXPANSION_VISIBLE)
        `when`(repository.primaryBouncerStartingDisappearAnimation.value).thenReturn(null)
        assertThat(mPrimaryBouncerInteractor.isFullyShowing()).isTrue()
        `when`(repository.primaryBouncerVisible.value).thenReturn(false)
        assertThat(mPrimaryBouncerInteractor.isFullyShowing()).isFalse()
    }

    @Test
    fun testIsScrimmed() {
        `when`(repository.primaryBouncerScrimmed.value).thenReturn(true)
        assertThat(mPrimaryBouncerInteractor.isScrimmed()).isTrue()
        `when`(repository.primaryBouncerScrimmed.value).thenReturn(false)
        assertThat(mPrimaryBouncerInteractor.isScrimmed()).isFalse()
    }

    @Test
    fun testIsInTransit() {
        `when`(repository.primaryBouncerShowingSoon.value).thenReturn(true)
        assertThat(mPrimaryBouncerInteractor.isInTransit()).isTrue()
        `when`(repository.primaryBouncerShowingSoon.value).thenReturn(false)
        assertThat(mPrimaryBouncerInteractor.isInTransit()).isFalse()
        `when`(repository.panelExpansionAmount.value).thenReturn(0.5f)
        assertThat(mPrimaryBouncerInteractor.isInTransit()).isTrue()
    }

    @Test
    fun testIsAnimatingAway() {
        `when`(repository.primaryBouncerStartingDisappearAnimation.value).thenReturn(Runnable {})
        assertThat(mPrimaryBouncerInteractor.isAnimatingAway()).isTrue()
        `when`(repository.primaryBouncerStartingDisappearAnimation.value).thenReturn(null)
        assertThat(mPrimaryBouncerInteractor.isAnimatingAway()).isFalse()
    }

    @Test
    fun testWillDismissWithAction() {
        `when`(bouncerViewDelegate.willDismissWithActions()).thenReturn(true)
        assertThat(mPrimaryBouncerInteractor.willDismissWithAction()).isTrue()
        `when`(bouncerViewDelegate.willDismissWithActions()).thenReturn(false)
        assertThat(mPrimaryBouncerInteractor.willDismissWithAction()).isFalse()
    }
}

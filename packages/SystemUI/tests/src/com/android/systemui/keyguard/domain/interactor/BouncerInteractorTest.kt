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
import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardSecurityModel
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.DejankUtils
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.keyguard.DismissCallbackRegistry
import com.android.systemui.keyguard.data.BouncerView
import com.android.systemui.keyguard.data.repository.KeyguardBouncerRepository
import com.android.systemui.keyguard.shared.model.BouncerCallbackActionsModel
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
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
@RunWithLooper(setAsMainLooper = true)
@RunWith(AndroidTestingRunner::class)
class BouncerInteractorTest : SysuiTestCase() {
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private lateinit var repository: KeyguardBouncerRepository
    @Mock(answer = Answers.RETURNS_DEEP_STUBS) private lateinit var bouncerView: BouncerView
    @Mock private lateinit var keyguardStateController: KeyguardStateController
    @Mock private lateinit var keyguardSecurityModel: KeyguardSecurityModel
    @Mock private lateinit var bouncerCallbackInteractor: BouncerCallbackInteractor
    @Mock private lateinit var falsingCollector: FalsingCollector
    @Mock private lateinit var dismissCallbackRegistry: DismissCallbackRegistry
    @Mock private lateinit var keyguardBypassController: KeyguardBypassController
    @Mock private lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor
    private val mainHandler = FakeHandler(Looper.getMainLooper())
    private lateinit var bouncerInteractor: BouncerInteractor

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        DejankUtils.setImmediate(true)
        bouncerInteractor =
            BouncerInteractor(
                repository,
                bouncerView,
                mainHandler,
                keyguardStateController,
                keyguardSecurityModel,
                bouncerCallbackInteractor,
                falsingCollector,
                dismissCallbackRegistry,
                keyguardBypassController,
                keyguardUpdateMonitor,
            )
        `when`(repository.startingDisappearAnimation.value).thenReturn(null)
        `when`(repository.show.value).thenReturn(null)
    }

    @Test
    fun testShow_isScrimmed() {
        bouncerInteractor.show(true)
        verify(repository).setShowMessage(null)
        verify(repository).setOnScreenTurnedOff(false)
        verify(repository).setKeyguardAuthenticated(null)
        verify(repository).setHide(false)
        verify(repository).setStartingToHide(false)
        verify(repository).setScrimmed(true)
        verify(repository).setExpansion(EXPANSION_VISIBLE)
        verify(repository).setShowingSoon(true)
        verify(keyguardStateController).notifyBouncerShowing(true)
        verify(bouncerCallbackInteractor).dispatchStartingToShow()
        verify(repository).setVisible(true)
        verify(repository).setShow(any(KeyguardBouncerModel::class.java))
        verify(repository).setShowingSoon(false)
    }

    @Test
    fun testShow_isNotScrimmed() {
        verify(repository, never()).setExpansion(EXPANSION_VISIBLE)
    }

    @Test
    fun testShow_keyguardIsDone() {
        `when`(bouncerView.delegate?.showNextSecurityScreenOrFinish()).thenReturn(true)
        verify(keyguardStateController, never()).notifyBouncerShowing(true)
        verify(bouncerCallbackInteractor, never()).dispatchStartingToShow()
    }

    @Test
    fun testHide() {
        bouncerInteractor.hide()
        verify(falsingCollector).onBouncerHidden()
        verify(keyguardStateController).notifyBouncerShowing(false)
        verify(repository).setShowingSoon(false)
        verify(repository).setOnDismissAction(null)
        verify(repository).setVisible(false)
        verify(repository).setHide(true)
        verify(repository).setShow(null)
    }

    @Test
    fun testExpansion() {
        `when`(repository.expansionAmount.value).thenReturn(0.5f)
        bouncerInteractor.setExpansion(0.6f)
        verify(repository).setExpansion(0.6f)
        verify(bouncerCallbackInteractor).dispatchExpansionChanged(0.6f)
    }

    @Test
    fun testExpansion_fullyShown() {
        `when`(repository.expansionAmount.value).thenReturn(0.5f)
        `when`(repository.startingDisappearAnimation.value).thenReturn(null)
        bouncerInteractor.setExpansion(EXPANSION_VISIBLE)
        verify(falsingCollector).onBouncerShown()
        verify(bouncerCallbackInteractor).dispatchFullyShown()
    }

    @Test
    fun testExpansion_fullyHidden() {
        `when`(repository.expansionAmount.value).thenReturn(0.5f)
        `when`(repository.startingDisappearAnimation.value).thenReturn(null)
        bouncerInteractor.setExpansion(EXPANSION_HIDDEN)
        verify(repository).setVisible(false)
        verify(repository).setShow(null)
        verify(falsingCollector).onBouncerHidden()
        verify(bouncerCallbackInteractor).dispatchReset()
        verify(bouncerCallbackInteractor).dispatchFullyHidden()
    }

    @Test
    fun testExpansion_startingToHide() {
        `when`(repository.expansionAmount.value).thenReturn(EXPANSION_VISIBLE)
        bouncerInteractor.setExpansion(0.1f)
        verify(repository).setStartingToHide(true)
        verify(bouncerCallbackInteractor).dispatchStartingToHide()
    }

    @Test
    fun testShowMessage() {
        bouncerInteractor.showMessage("abc", null)
        verify(repository).setShowMessage(BouncerShowMessageModel("abc", null))
    }

    @Test
    fun testDismissAction() {
        val onDismissAction = mock(ActivityStarter.OnDismissAction::class.java)
        val cancelAction = mock(Runnable::class.java)
        bouncerInteractor.setDismissAction(onDismissAction, cancelAction)
        verify(repository)
            .setOnDismissAction(BouncerCallbackActionsModel(onDismissAction, cancelAction))
    }

    @Test
    fun testUpdateResources() {
        bouncerInteractor.updateResources()
        verify(repository).setResourceUpdateRequests(true)
    }

    @Test
    fun testNotifyKeyguardAuthenticated() {
        bouncerInteractor.notifyKeyguardAuthenticated(true)
        verify(repository).setKeyguardAuthenticated(true)
    }

    @Test
    fun testOnScreenTurnedOff() {
        bouncerInteractor.onScreenTurnedOff()
        verify(repository).setOnScreenTurnedOff(true)
    }

    @Test
    fun testSetKeyguardPosition() {
        bouncerInteractor.setKeyguardPosition(0f)
        verify(repository).setKeyguardPosition(0f)
    }

    @Test
    fun testNotifyKeyguardAuthenticatedHandled() {
        bouncerInteractor.notifyKeyguardAuthenticatedHandled()
        verify(repository).setKeyguardAuthenticated(null)
    }

    @Test
    fun testNotifyUpdatedResources() {
        bouncerInteractor.notifyUpdatedResources()
        verify(repository).setResourceUpdateRequests(false)
    }

    @Test
    fun testSetBackButtonEnabled() {
        bouncerInteractor.setBackButtonEnabled(true)
        verify(repository).setIsBackButtonEnabled(true)
    }

    @Test
    fun testStartDisappearAnimation() {
        val runnable = mock(Runnable::class.java)
        bouncerInteractor.startDisappearAnimation(runnable)
        verify(repository).setStartDisappearAnimation(any(Runnable::class.java))
    }

    @Test
    fun testIsFullShowing() {
        `when`(repository.isVisible.value).thenReturn(true)
        `when`(repository.expansionAmount.value).thenReturn(EXPANSION_VISIBLE)
        `when`(repository.startingDisappearAnimation.value).thenReturn(null)
        assertThat(bouncerInteractor.isFullyShowing()).isTrue()
        `when`(repository.isVisible.value).thenReturn(false)
        assertThat(bouncerInteractor.isFullyShowing()).isFalse()
    }

    @Test
    fun testIsScrimmed() {
        `when`(repository.isScrimmed.value).thenReturn(true)
        assertThat(bouncerInteractor.isScrimmed()).isTrue()
        `when`(repository.isScrimmed.value).thenReturn(false)
        assertThat(bouncerInteractor.isScrimmed()).isFalse()
    }

    @Test
    fun testIsInTransit() {
        `when`(repository.showingSoon.value).thenReturn(true)
        assertThat(bouncerInteractor.isInTransit()).isTrue()
        `when`(repository.showingSoon.value).thenReturn(false)
        assertThat(bouncerInteractor.isInTransit()).isFalse()
        `when`(repository.expansionAmount.value).thenReturn(0.5f)
        assertThat(bouncerInteractor.isInTransit()).isTrue()
    }

    @Test
    fun testIsAnimatingAway() {
        `when`(repository.startingDisappearAnimation.value).thenReturn(Runnable {})
        assertThat(bouncerInteractor.isAnimatingAway()).isTrue()
        `when`(repository.startingDisappearAnimation.value).thenReturn(null)
        assertThat(bouncerInteractor.isAnimatingAway()).isFalse()
    }

    @Test
    fun testWillDismissWithAction() {
        `when`(repository.onDismissAction.value?.onDismissAction)
            .thenReturn(mock(ActivityStarter.OnDismissAction::class.java))
        assertThat(bouncerInteractor.willDismissWithAction()).isTrue()
        `when`(repository.onDismissAction.value?.onDismissAction).thenReturn(null)
        assertThat(bouncerInteractor.willDismissWithAction()).isFalse()
    }
}

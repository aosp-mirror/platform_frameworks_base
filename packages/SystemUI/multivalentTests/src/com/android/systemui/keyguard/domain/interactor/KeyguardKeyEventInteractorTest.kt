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

package com.android.systemui.keyguard.domain.interactor

import android.media.AudioManager
import android.media.session.MediaSessionLegacyHelper
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.back.domain.interactor.BackActionInteractor
import com.android.systemui.media.controls.util.MediaSessionLegacyHelperWrapper
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAsleepForTest
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAwakeForTest
import com.android.systemui.power.domain.interactor.PowerInteractorFactory
import com.android.systemui.shade.ShadeController
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit
import org.mockito.kotlin.isNull

@ExperimentalCoroutinesApi
@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyguardKeyEventInteractorTest : SysuiTestCase() {
    @JvmField @Rule var mockitoRule = MockitoJUnit.rule()

    private val actionDownVolumeDownKeyEvent =
        KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)
    private val actionDownVolumeUpKeyEvent =
        KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)
    private val backKeyEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)

    private lateinit var powerInteractor: PowerInteractor
    @Mock private lateinit var statusBarStateController: StatusBarStateController
    @Mock private lateinit var statusBarKeyguardViewManager: StatusBarKeyguardViewManager
    @Mock private lateinit var shadeController: ShadeController
    @Mock private lateinit var mediaSessionLegacyHelperWrapper: MediaSessionLegacyHelperWrapper
    @Mock private lateinit var mediaSessionLegacyHelper: MediaSessionLegacyHelper
    @Mock private lateinit var backActionInteractor: BackActionInteractor

    private lateinit var underTest: KeyguardKeyEventInteractor

    @Before
    fun setup() {
        whenever(mediaSessionLegacyHelperWrapper.getHelper(any()))
            .thenReturn(mediaSessionLegacyHelper)
        powerInteractor = PowerInteractorFactory.create().powerInteractor

        underTest =
            KeyguardKeyEventInteractor(
                context,
                statusBarStateController,
                statusBarKeyguardViewManager,
                shadeController,
                mediaSessionLegacyHelperWrapper,
                backActionInteractor,
                powerInteractor,
            )
    }

    @Test
    fun dispatchKeyEvent_volumeKey_dozing_handlesEvents() {
        whenever(statusBarStateController.isDozing).thenReturn(true)

        assertThat(underTest.dispatchKeyEvent(actionDownVolumeDownKeyEvent)).isTrue()
        verify(mediaSessionLegacyHelper)
            .sendVolumeKeyEvent(
                eq(actionDownVolumeDownKeyEvent),
                eq(AudioManager.USE_DEFAULT_STREAM_TYPE),
                eq(true),
            )

        assertThat(underTest.dispatchKeyEvent(actionDownVolumeUpKeyEvent)).isTrue()
        verify(mediaSessionLegacyHelper)
            .sendVolumeKeyEvent(
                eq(actionDownVolumeUpKeyEvent),
                eq(AudioManager.USE_DEFAULT_STREAM_TYPE),
                eq(true),
            )
    }

    @Test
    fun dispatchKeyEvent_volumeKey_notDozing_doesNotHandleEvents() {
        whenever(statusBarStateController.isDozing).thenReturn(false)

        assertThat(underTest.dispatchKeyEvent(actionDownVolumeDownKeyEvent)).isFalse()
        verify(mediaSessionLegacyHelper, never())
            .sendVolumeKeyEvent(
                eq(actionDownVolumeDownKeyEvent),
                eq(AudioManager.USE_DEFAULT_STREAM_TYPE),
                eq(true),
            )

        assertThat(underTest.dispatchKeyEvent(actionDownVolumeUpKeyEvent)).isFalse()
        verify(mediaSessionLegacyHelper, never())
            .sendVolumeKeyEvent(
                eq(actionDownVolumeUpKeyEvent),
                eq(AudioManager.USE_DEFAULT_STREAM_TYPE),
                eq(true),
            )
    }

    @Test
    fun dispatchKeyEvent_menuActionUp_awakeShadeLocked_collapsesShade() {
        powerInteractor.setAwakeForTest()
        whenever(statusBarStateController.state).thenReturn(StatusBarState.SHADE_LOCKED)
        whenever(statusBarKeyguardViewManager.shouldDismissOnMenuPressed()).thenReturn(true)

        val actionUpMenuKeyEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU)
        assertThat(underTest.dispatchKeyEvent(actionUpMenuKeyEvent)).isTrue()
        verify(statusBarKeyguardViewManager).dismissWithAction(any(), isNull(), eq(false))
    }

    @Test
    fun dispatchKeyEvent_menuActionUp_asleepKeyguard_neverCollapsesShade() {
        powerInteractor.setAsleepForTest()
        whenever(statusBarStateController.state).thenReturn(StatusBarState.KEYGUARD)
        whenever(statusBarKeyguardViewManager.shouldDismissOnMenuPressed()).thenReturn(true)

        verifyActionsDoNothing(KeyEvent.KEYCODE_MENU)
    }

    @Test
    fun dispatchKeyEvent_spaceActionUp_awakeKeyguard_collapsesShade() {
        powerInteractor.setAwakeForTest()
        whenever(statusBarStateController.state).thenReturn(StatusBarState.KEYGUARD)

        verifyActionUpShowsPrimaryBouncer(KeyEvent.KEYCODE_SPACE)
    }

    @Test
    fun dispatchKeyEvent_spaceActionUp_shadeLocked_collapsesShade() {
        powerInteractor.setAwakeForTest()
        whenever(statusBarStateController.state).thenReturn(StatusBarState.SHADE_LOCKED)

        verifyActionUpCollapsesTheShade(KeyEvent.KEYCODE_SPACE)
    }

    @Test
    fun dispatchKeyEvent_enterActionUp_awakeKeyguard_showsPrimaryBouncer() {
        powerInteractor.setAwakeForTest()
        whenever(statusBarStateController.state).thenReturn(StatusBarState.KEYGUARD)

        verifyActionUpShowsPrimaryBouncer(KeyEvent.KEYCODE_ENTER)
    }

    @Test
    fun dispatchKeyEvent_enterActionUp_shadeLocked_collapsesShade() {
        powerInteractor.setAwakeForTest()
        whenever(statusBarStateController.state).thenReturn(StatusBarState.SHADE_LOCKED)

        verifyActionUpCollapsesTheShade(KeyEvent.KEYCODE_ENTER)
    }

    @Test
    fun dispatchKeyEventPreIme_back_keyguard_onBackRequested() {
        whenever(statusBarStateController.state).thenReturn(StatusBarState.KEYGUARD)
        whenever(statusBarKeyguardViewManager.dispatchBackKeyEventPreIme()).thenReturn(true)

        whenever(backActionInteractor.onBackRequested()).thenReturn(false)
        assertThat(underTest.dispatchKeyEventPreIme(backKeyEvent)).isFalse()
        verify(backActionInteractor).onBackRequested()
        clearInvocations(backActionInteractor)

        whenever(backActionInteractor.onBackRequested()).thenReturn(true)
        assertThat(underTest.dispatchKeyEventPreIme(backKeyEvent)).isTrue()
        verify(backActionInteractor).onBackRequested()
    }

    @Test
    fun dispatchKeyEventPreIme_back_keyguard_SBKVMdoesNotHandle_neverOnBackRequested() {
        whenever(statusBarStateController.state).thenReturn(StatusBarState.KEYGUARD)
        whenever(statusBarKeyguardViewManager.dispatchBackKeyEventPreIme()).thenReturn(false)
        whenever(backActionInteractor.onBackRequested()).thenReturn(true)

        assertThat(underTest.dispatchKeyEventPreIme(backKeyEvent)).isFalse()
        verify(backActionInteractor, never()).onBackRequested()
    }

    @Test
    fun dispatchKeyEventPreIme_back_shade_neverOnBackRequested() {
        whenever(statusBarStateController.state).thenReturn(StatusBarState.SHADE)
        whenever(statusBarKeyguardViewManager.dispatchBackKeyEventPreIme()).thenReturn(true)
        whenever(backActionInteractor.onBackRequested()).thenReturn(true)

        assertThat(underTest.dispatchKeyEventPreIme(backKeyEvent)).isFalse()
        verify(backActionInteractor, never()).onBackRequested()
    }

    @Test
    fun interceptMediaKey_keyguard_SBKVMdoesNotHandle_doesNotHandleMediaKey() {
        val keyEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP)
        whenever(statusBarStateController.state).thenReturn(StatusBarState.KEYGUARD)
        whenever(statusBarKeyguardViewManager.interceptMediaKey(eq(keyEvent))).thenReturn(false)

        assertThat(underTest.interceptMediaKey(keyEvent)).isFalse()
        verify(statusBarKeyguardViewManager).interceptMediaKey(eq(keyEvent))
    }

    @Test
    fun interceptMediaKey_keyguard_handleMediaKey() {
        val keyEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP)
        whenever(statusBarStateController.state).thenReturn(StatusBarState.KEYGUARD)
        whenever(statusBarKeyguardViewManager.interceptMediaKey(eq(keyEvent))).thenReturn(true)

        assertThat(underTest.interceptMediaKey(keyEvent)).isTrue()
        verify(statusBarKeyguardViewManager).interceptMediaKey(eq(keyEvent))
    }

    @Test
    fun interceptMediaKey_shade_doesNotHandleMediaKey() {
        whenever(statusBarStateController.state).thenReturn(StatusBarState.SHADE)

        assertThat(
                underTest.interceptMediaKey(
                    KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP)
                )
            )
            .isFalse()
        verify(statusBarKeyguardViewManager, never()).interceptMediaKey(any())
    }

    private fun verifyActionUpCollapsesTheShade(keycode: Int) {
        // action down: does NOT collapse the shade
        val actionDownMenuKeyEvent = KeyEvent(KeyEvent.ACTION_DOWN, keycode)
        assertThat(underTest.dispatchKeyEvent(actionDownMenuKeyEvent)).isFalse()
        verify(shadeController, never()).animateCollapseShadeForced()

        // action up: collapses the shade
        val actionUpMenuKeyEvent = KeyEvent(KeyEvent.ACTION_UP, keycode)
        assertThat(underTest.dispatchKeyEvent(actionUpMenuKeyEvent)).isTrue()
        verify(shadeController).animateCollapseShadeForced()
    }

    private fun verifyActionUpShowsPrimaryBouncer(keycode: Int) {
        // action down: does NOT collapse the shade
        val actionDownMenuKeyEvent = KeyEvent(KeyEvent.ACTION_DOWN, keycode)
        assertThat(underTest.dispatchKeyEvent(actionDownMenuKeyEvent)).isFalse()
        verify(statusBarKeyguardViewManager, never()).showPrimaryBouncer(any())

        // action up: collapses the shade
        val actionUpMenuKeyEvent = KeyEvent(KeyEvent.ACTION_UP, keycode)
        assertThat(underTest.dispatchKeyEvent(actionUpMenuKeyEvent)).isTrue()
        verify(statusBarKeyguardViewManager).showPrimaryBouncer(eq(true))
    }

    private fun verifyActionsDoNothing(keycode: Int) {
        // action down: does nothing
        val actionDownMenuKeyEvent = KeyEvent(KeyEvent.ACTION_DOWN, keycode)
        assertThat(underTest.dispatchKeyEvent(actionDownMenuKeyEvent)).isFalse()
        verify(shadeController, never()).animateCollapseShadeForced()
        verify(statusBarKeyguardViewManager, never()).showPrimaryBouncer(any())

        // action up: doesNothing
        val actionUpMenuKeyEvent = KeyEvent(KeyEvent.ACTION_UP, keycode)
        assertThat(underTest.dispatchKeyEvent(actionUpMenuKeyEvent)).isFalse()
        verify(shadeController, never()).animateCollapseShadeForced()
        verify(statusBarKeyguardViewManager, never()).showPrimaryBouncer(any())
    }
}

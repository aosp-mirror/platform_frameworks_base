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

package com.android.systemui.keyevent.domain.interactor

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.back.domain.interactor.BackActionInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractorFactory
import com.android.systemui.keyguard.domain.interactor.KeyguardKeyEventInteractor
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit

@SmallTest
@RunWith(AndroidJUnit4::class)
class SysUIKeyEventHandlerTest : SysuiTestCase() {
    @JvmField @Rule var mockitoRule = MockitoJUnit.rule()

    private lateinit var keyguardInteractorWithDependencies:
        KeyguardInteractorFactory.WithDependencies
    @Mock private lateinit var keyguardKeyEventInteractor: KeyguardKeyEventInteractor
    @Mock private lateinit var backActionInteractor: BackActionInteractor

    private lateinit var underTest: SysUIKeyEventHandler

    @Before
    fun setup() {
        keyguardInteractorWithDependencies = KeyguardInteractorFactory.create()
        underTest =
            SysUIKeyEventHandler(
                backActionInteractor,
                keyguardKeyEventInteractor,
            )
    }

    @Test
    fun dispatchBackKey_notHandledByKeyguardKeyEventInteractor_handledByBackActionInteractor() {
        val backKeyEventActionDown = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
        val backKeyEventActionUp = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)

        // GIVEN back key ACTION_DOWN and ACTION_UP aren't handled by the keyguardKeyEventInteractor
        whenever(keyguardKeyEventInteractor.dispatchKeyEvent(backKeyEventActionDown))
            .thenReturn(false)
        whenever(keyguardKeyEventInteractor.dispatchKeyEvent(backKeyEventActionUp))
            .thenReturn(false)

        // WHEN back key event ACTION_DOWN, the event is handled even though back isn't requested
        assertThat(underTest.dispatchKeyEvent(backKeyEventActionDown)).isTrue()
        // THEN back event isn't handled on ACTION_DOWN
        verify(backActionInteractor, never()).onBackRequested()

        // WHEN back key event ACTION_UP
        assertThat(underTest.dispatchKeyEvent(backKeyEventActionUp)).isTrue()
        // THEN back event is handled on ACTION_UP
        verify(backActionInteractor).onBackRequested()
    }

    @Test
    fun dispatchKeyEvent_isNotHandledByKeyguardKeyEventInteractor() {
        val keyEvent =
            KeyEvent(
                KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_SPACE,
            )
        whenever(keyguardKeyEventInteractor.dispatchKeyEvent(eq(keyEvent))).thenReturn(false)
        assertThat(underTest.dispatchKeyEvent(keyEvent)).isFalse()
    }

    @Test
    fun dispatchKeyEvent_handledByKeyguardKeyEventInteractor() {
        val keyEvent =
            KeyEvent(
                KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_SPACE,
            )
        whenever(keyguardKeyEventInteractor.dispatchKeyEvent(eq(keyEvent))).thenReturn(true)
        assertThat(underTest.dispatchKeyEvent(keyEvent)).isTrue()
    }

    @Test
    fun interceptMediaKey_isNotHandledByKeyguardKeyEventInteractor() {
        val keyEvent =
            KeyEvent(
                KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_SPACE,
            )
        whenever(keyguardKeyEventInteractor.interceptMediaKey(eq(keyEvent))).thenReturn(false)
        assertThat(underTest.interceptMediaKey(keyEvent)).isFalse()
    }

    @Test
    fun interceptMediaKey_handledByKeyguardKeyEventInteractor() {
        val keyEvent =
            KeyEvent(
                KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_SPACE,
            )
        whenever(keyguardKeyEventInteractor.interceptMediaKey(eq(keyEvent))).thenReturn(true)
        assertThat(underTest.interceptMediaKey(keyEvent)).isTrue()
    }

    @Test
    fun dispatchKeyEventPreIme_isNotHandledByKeyguardKeyEventInteractor() {
        val keyEvent =
            KeyEvent(
                KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_SPACE,
            )
        whenever(keyguardKeyEventInteractor.dispatchKeyEventPreIme(eq(keyEvent))).thenReturn(false)
        assertThat(underTest.dispatchKeyEventPreIme(keyEvent)).isFalse()
    }

    @Test
    fun dispatchKeyEventPreIme_handledByKeyguardKeyEventInteractor() {
        val keyEvent =
            KeyEvent(
                KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_SPACE,
            )
        whenever(keyguardKeyEventInteractor.dispatchKeyEventPreIme(eq(keyEvent))).thenReturn(true)
        assertThat(underTest.dispatchKeyEventPreIme(keyEvent)).isTrue()
    }
}

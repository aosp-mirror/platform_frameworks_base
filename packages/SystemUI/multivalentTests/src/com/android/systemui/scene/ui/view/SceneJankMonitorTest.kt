/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.scene.ui.view

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.jank.Cuj
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.fakeAuthenticationRepository
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.deviceentry.domain.interactor.deviceUnlockedInteractor
import com.android.systemui.jank.interactionJankMonitor
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.shared.model.SuccessFingerprintAuthenticationStatus
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.runCurrent
import com.android.systemui.kosmos.runTestWithSnapshots
import com.android.systemui.kosmos.testScope
import com.android.systemui.lifecycle.activateIn
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
class SceneJankMonitorTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val underTest: SceneJankMonitor = kosmos.sceneJankMonitorFactory.create()

    @Before
    fun setUp() {
        underTest.activateIn(kosmos.testScope)
    }

    @Test
    fun onTransitionStart_withProvidedCuj_beginsThatCuj() =
        kosmos.runTestWithSnapshots {
            val cuj = 1337
            underTest.onTransitionStart(
                view = mock(),
                from = Scenes.Communal,
                to = Scenes.Dream,
                cuj = cuj,
            )
            verify(interactionJankMonitor).begin(any(), eq(cuj))
            verify(interactionJankMonitor, never()).end(anyInt())
        }

    @Test
    fun onTransitionEnd_withProvidedCuj_endsThatCuj() =
        kosmos.runTestWithSnapshots {
            val cuj = 1337
            underTest.onTransitionEnd(from = Scenes.Communal, to = Scenes.Dream, cuj = cuj)
            verify(interactionJankMonitor, never()).begin(any(), anyInt())
            verify(interactionJankMonitor).end(cuj)
        }

    @Test
    fun bouncer_authMethodPin() =
        kosmos.runTestWithSnapshots {
            bouncer(
                authenticationMethod = AuthenticationMethodModel.Pin,
                appearCuj = Cuj.CUJ_LOCKSCREEN_PIN_APPEAR,
                disappearCuj = Cuj.CUJ_LOCKSCREEN_PIN_DISAPPEAR,
            )
        }

    @Test
    fun bouncer_authMethodSim() =
        kosmos.runTestWithSnapshots {
            bouncer(
                authenticationMethod = AuthenticationMethodModel.Sim,
                appearCuj = Cuj.CUJ_LOCKSCREEN_PIN_APPEAR,
                disappearCuj = Cuj.CUJ_LOCKSCREEN_PIN_DISAPPEAR,
                // When the auth method is SIM, unlocking doesn't work like normal. Instead of
                // leaving the bouncer, the bouncer is switched over to the real authentication
                // method when the SIM is unlocked.
                //
                // Therefore, there's no point in testing this code path and it will, in fact, fail
                // to unlock.
                testUnlockedDisappearance = false,
            )
        }

    @Test
    fun bouncer_authMethodPattern() =
        kosmos.runTestWithSnapshots {
            bouncer(
                authenticationMethod = AuthenticationMethodModel.Pattern,
                appearCuj = Cuj.CUJ_LOCKSCREEN_PATTERN_APPEAR,
                disappearCuj = Cuj.CUJ_LOCKSCREEN_PATTERN_DISAPPEAR,
            )
        }

    @Test
    fun bouncer_authMethodPassword() =
        kosmos.runTestWithSnapshots {
            bouncer(
                authenticationMethod = AuthenticationMethodModel.Password,
                appearCuj = Cuj.CUJ_LOCKSCREEN_PASSWORD_APPEAR,
                disappearCuj = Cuj.CUJ_LOCKSCREEN_PASSWORD_DISAPPEAR,
            )
        }

    private fun Kosmos.bouncer(
        authenticationMethod: AuthenticationMethodModel,
        appearCuj: Int,
        disappearCuj: Int,
        testUnlockedDisappearance: Boolean = true,
    ) {
        // Set up state:
        fakeAuthenticationRepository.setAuthenticationMethod(authenticationMethod)
        runCurrent()

        fun verifyCujCounts(
            beginAppearCount: Int = 0,
            beginDisappearCount: Int = 0,
            endAppearCount: Int = 0,
            endDisappearCount: Int = 0,
        ) {
            verify(interactionJankMonitor, times(beginAppearCount)).begin(any(), eq(appearCuj))
            verify(interactionJankMonitor, times(beginDisappearCount))
                .begin(any(), eq(disappearCuj))
            verify(interactionJankMonitor, times(endAppearCount)).end(appearCuj)
            verify(interactionJankMonitor, times(endDisappearCount)).end(disappearCuj)
        }

        // Precondition checks:
        assertThat(deviceUnlockedInteractor.deviceUnlockStatus.value.isUnlocked).isFalse()
        verifyCujCounts()

        // Bouncer appears CUJ:
        underTest.onTransitionStart(
            view = mock(),
            from = Scenes.Lockscreen,
            to = Scenes.Bouncer,
            cuj = null,
        )
        verifyCujCounts(beginAppearCount = 1)
        underTest.onTransitionEnd(from = Scenes.Lockscreen, to = Scenes.Bouncer, cuj = null)
        verifyCujCounts(beginAppearCount = 1, endAppearCount = 1)

        // Bouncer disappear CUJ but it doesn't log because the device isn't unlocked.
        underTest.onTransitionStart(
            view = mock(),
            from = Scenes.Bouncer,
            to = Scenes.Lockscreen,
            cuj = null,
        )
        verifyCujCounts(beginAppearCount = 1, endAppearCount = 1)
        underTest.onTransitionEnd(from = Scenes.Bouncer, to = Scenes.Lockscreen, cuj = null)
        verifyCujCounts(beginAppearCount = 1, endAppearCount = 1)

        if (!testUnlockedDisappearance) {
            return
        }

        // Unlock the device and transition away from the bouncer.
        fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
            SuccessFingerprintAuthenticationStatus(0, true)
        )
        runCurrent()
        assertThat(deviceUnlockedInteractor.deviceUnlockStatus.value.isUnlocked).isTrue()

        // Bouncer disappear CUJ and it doeslog because the device is unlocked.
        underTest.onTransitionStart(
            view = mock(),
            from = Scenes.Bouncer,
            to = Scenes.Gone,
            cuj = null,
        )
        verifyCujCounts(beginAppearCount = 1, endAppearCount = 1, beginDisappearCount = 1)
        underTest.onTransitionEnd(from = Scenes.Bouncer, to = Scenes.Gone, cuj = null)
        verifyCujCounts(
            beginAppearCount = 1,
            endAppearCount = 1,
            beginDisappearCount = 1,
            endDisappearCount = 1,
        )
    }
}

/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.systemui.bouncer.domain.interactor

import android.telecom.TelecomManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.app.activityTaskManager
import com.android.internal.R
import com.android.internal.logging.fakeMetricsLogger
import com.android.internal.logging.nano.MetricsProto
import com.android.internal.util.emergencyAffordanceManager
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.fakeAuthenticationRepository
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.common.ui.data.repository.fakeConfigurationRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.statusbar.pipeline.mobile.data.repository.FakeMobileConnectionsRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.fakeMobileConnectionsRepository
import com.android.systemui.telephony.data.repository.fakeTelephonyRepository
import com.android.systemui.testKosmos
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import com.android.systemui.util.mockito.whenever
import com.android.telecom.telecomManager
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
@EnableSceneContainer
class BouncerActionButtonInteractorTest : SysuiTestCase() {

    @Mock private lateinit var selectedUserInteractor: SelectedUserInteractor
    @Mock private lateinit var telecomManager: TelecomManager

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val metricsLogger = kosmos.fakeMetricsLogger
    private val activityTaskManager = kosmos.activityTaskManager
    private val emergencyAffordanceManager = kosmos.emergencyAffordanceManager

    private lateinit var mobileConnectionsRepository: FakeMobileConnectionsRepository

    private var currentUserId: Int = 0
    private var needsEmergencyAffordance = true

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        mobileConnectionsRepository = kosmos.fakeMobileConnectionsRepository

        overrideResource(R.string.lockscreen_emergency_call, MESSAGE_EMERGENCY_CALL)
        overrideResource(R.string.lockscreen_return_to_call, MESSAGE_RETURN_TO_CALL)
        overrideResource(
            R.bool.config_enable_emergency_call_while_sim_locked,
            ENABLE_EMERGENCY_CALL_WHILE_SIM_LOCKED
        )
        whenever(selectedUserInteractor.getSelectedUserId()).thenReturn(currentUserId)
        whenever(emergencyAffordanceManager.needsEmergencyAffordance())
            .thenReturn(needsEmergencyAffordance)
        whenever(telecomManager.isInCall).thenReturn(false)

        kosmos.fakeTelephonyRepository.setHasTelephonyRadio(true)

        kosmos.telecomManager = telecomManager

        kosmos.sceneInteractor.changeScene(Scenes.Bouncer, "")
    }

    @Test
    fun noTelephonyRadio_noButton() =
        testScope.runTest {
            kosmos.fakeTelephonyRepository.setHasTelephonyRadio(false)
            val underTest = kosmos.bouncerActionButtonInteractor
            val actionButton by collectLastValue(underTest.actionButton)
            assertThat(actionButton).isNull()
        }

    @Test
    fun noTelecomManager_noButton() =
        testScope.runTest {
            kosmos.telecomManager = null
            val underTest = kosmos.bouncerActionButtonInteractor
            val actionButton by collectLastValue(underTest.actionButton)
            assertThat(actionButton).isNull()
        }

    @Test
    fun duringCall_returnToCallButton() =
        testScope.runTest {
            val underTest = kosmos.bouncerActionButtonInteractor
            val actionButton by collectLastValue(underTest.actionButton)
            kosmos.fakeTelephonyRepository.setIsInCall(true)

            assertThat(actionButton).isNotNull()
            assertThat(actionButton?.label).isEqualTo(MESSAGE_RETURN_TO_CALL)
            assertThat(actionButton?.onClick).isNotNull()
            assertThat(actionButton?.onLongClick).isNull()

            actionButton?.onClick?.invoke()
            runCurrent()

            assertThat(metricsLogger.logs.size).isEqualTo(1)
            assertThat(metricsLogger.logs.element().category)
                .isEqualTo(MetricsProto.MetricsEvent.ACTION_EMERGENCY_CALL)
            verify(activityTaskManager).stopSystemLockTaskMode()
            assertThat(kosmos.sceneInteractor.currentScene.value).isEqualTo(Scenes.Lockscreen)
            verify(telecomManager).showInCallScreen(eq(false))
        }

    @Test
    fun noCall_secureAuthMethod_emergencyCallButton() =
        testScope.runTest {
            val underTest = kosmos.bouncerActionButtonInteractor
            val actionButton by collectLastValue(underTest.actionButton)
            mobileConnectionsRepository.isAnySimSecure.value = false
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin
            )
            kosmos.fakeTelephonyRepository.setIsInCall(false)

            assertThat(actionButton).isNotNull()
            assertThat(actionButton?.label).isEqualTo(MESSAGE_EMERGENCY_CALL)
            assertThat(actionButton?.onClick).isNotNull()
            assertThat(actionButton?.onLongClick).isNotNull()

            actionButton?.onClick?.invoke()
            runCurrent()

            assertThat(metricsLogger.logs.size).isEqualTo(1)
            assertThat(metricsLogger.logs.element().category)
                .isEqualTo(MetricsProto.MetricsEvent.ACTION_EMERGENCY_CALL)
            verify(activityTaskManager).stopSystemLockTaskMode()
            assertThat(kosmos.sceneInteractor.currentScene.value).isEqualTo(Scenes.Lockscreen)

            // TODO(b/25189994): Test the activity has been started once we switch to the
            //  ActivityStarter interface here.
            verify(emergencyAffordanceManager, never()).performEmergencyCall()

            actionButton?.onLongClick?.invoke()
            verify(emergencyAffordanceManager).performEmergencyCall()
        }

    @Test
    fun noCall_insecureAuthMethodButSecureSim_emergencyCallButton() =
        testScope.runTest {
            val underTest = kosmos.bouncerActionButtonInteractor
            val actionButton by collectLastValue(underTest.actionButton)
            mobileConnectionsRepository.isAnySimSecure.value = true
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.None
            )
            kosmos.fakeTelephonyRepository.setIsInCall(false)
            runCurrent()

            assertThat(actionButton).isNotNull()
            assertThat(actionButton?.label).isEqualTo(MESSAGE_EMERGENCY_CALL)
            assertThat(actionButton?.onClick).isNotNull()
            assertThat(actionButton?.onLongClick).isNotNull()
        }

    @Test
    fun noCall_insecure_noButton() =
        testScope.runTest {
            val underTest = kosmos.bouncerActionButtonInteractor
            val actionButton by collectLastValue(underTest.actionButton)
            mobileConnectionsRepository.isAnySimSecure.value = false
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.None
            )
            kosmos.fakeTelephonyRepository.setIsInCall(false)

            assertThat(actionButton).isNull()
        }

    @Test
    fun noCall_simSecureButEmergencyNotSupported_noButton() =
        testScope.runTest {
            val underTest = kosmos.bouncerActionButtonInteractor
            val actionButton by collectLastValue(underTest.actionButton)
            mobileConnectionsRepository.isAnySimSecure.value = true
            overrideResource(R.bool.config_enable_emergency_call_while_sim_locked, false)
            kosmos.fakeConfigurationRepository.onConfigurationChange()
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.None
            )
            kosmos.fakeTelephonyRepository.setIsInCall(false)
            runCurrent()

            assertThat(actionButton).isNull()
        }

    companion object {
        private const val MESSAGE_EMERGENCY_CALL = "Emergency"
        private const val MESSAGE_RETURN_TO_CALL = "Return to call"
        private const val ENABLE_EMERGENCY_CALL_WHILE_SIM_LOCKED = true
    }
}

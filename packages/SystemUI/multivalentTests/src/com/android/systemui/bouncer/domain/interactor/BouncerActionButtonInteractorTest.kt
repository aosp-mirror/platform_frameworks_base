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
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.Flags.REFACTOR_GETCURRENTUSER
import com.android.systemui.scene.SceneTestUtils
import com.android.systemui.statusbar.pipeline.mobile.data.repository.FakeMobileConnectionsRepository
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
class BouncerActionButtonInteractorTest : SysuiTestCase() {

    @Mock private lateinit var selectedUserInteractor: SelectedUserInteractor
    @Mock private lateinit var telecomManager: TelecomManager

    private val utils = SceneTestUtils(this)
    private val testScope = utils.testScope
    private val metricsLogger = utils.kosmos.fakeMetricsLogger
    private val activityTaskManager = utils.kosmos.activityTaskManager
    private val emergencyAffordanceManager = utils.kosmos.emergencyAffordanceManager

    private lateinit var mobileConnectionsRepository: FakeMobileConnectionsRepository

    private var currentUserId: Int = 0
    private var needsEmergencyAffordance = true

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        utils.fakeSceneContainerFlags.enabled = true

        mobileConnectionsRepository = utils.mobileConnectionsRepository

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

        utils.fakeFeatureFlags.set(REFACTOR_GETCURRENTUSER, true)

        utils.telephonyRepository.setHasTelephonyRadio(true)

        utils.kosmos.telecomManager = telecomManager
    }

    @Test
    fun noTelephonyRadio_noButton() =
        testScope.runTest {
            utils.telephonyRepository.setHasTelephonyRadio(false)
            val underTest = utils.bouncerActionButtonInteractor()
            val actionButton by collectLastValue(underTest.actionButton)
            assertThat(actionButton).isNull()
        }

    @Test
    fun noTelecomManager_noButton() =
        testScope.runTest {
            utils.kosmos.telecomManager = null
            val underTest = utils.bouncerActionButtonInteractor()
            val actionButton by collectLastValue(underTest.actionButton)
            assertThat(actionButton).isNull()
        }

    @Test
    fun duringCall_returnToCallButton() =
        testScope.runTest {
            val underTest = utils.bouncerActionButtonInteractor()
            val actionButton by collectLastValue(underTest.actionButton)
            utils.telephonyRepository.setIsInCall(true)

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
            verify(telecomManager).showInCallScreen(eq(false))
        }

    @Test
    fun noCall_secureAuthMethod_emergencyCallButton() =
        testScope.runTest {
            val underTest = utils.bouncerActionButtonInteractor()
            val actionButton by collectLastValue(underTest.actionButton)
            mobileConnectionsRepository.isAnySimSecure.value = false
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
            utils.telephonyRepository.setIsInCall(false)

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

            // TODO(b/25189994): Test the activity has been started once we switch to the
            //  ActivityStarter interface here.
            verify(emergencyAffordanceManager, never()).performEmergencyCall()

            actionButton?.onLongClick?.invoke()
            verify(emergencyAffordanceManager).performEmergencyCall()
        }

    @Test
    fun noCall_insecureAuthMethodButSecureSim_emergencyCallButton() =
        testScope.runTest {
            val underTest = utils.bouncerActionButtonInteractor()
            val actionButton by collectLastValue(underTest.actionButton)
            mobileConnectionsRepository.isAnySimSecure.value = true
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.None)
            utils.telephonyRepository.setIsInCall(false)
            runCurrent()

            assertThat(actionButton).isNotNull()
            assertThat(actionButton?.label).isEqualTo(MESSAGE_EMERGENCY_CALL)
            assertThat(actionButton?.onClick).isNotNull()
            assertThat(actionButton?.onLongClick).isNotNull()
        }

    @Test
    fun noCall_insecure_noButton() =
        testScope.runTest {
            val underTest = utils.bouncerActionButtonInteractor()
            val actionButton by collectLastValue(underTest.actionButton)
            mobileConnectionsRepository.isAnySimSecure.value = false
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.None)
            utils.telephonyRepository.setIsInCall(false)

            assertThat(actionButton).isNull()
        }

    @Test
    fun noCall_simSecureButEmergencyNotSupported_noButton() =
        testScope.runTest {
            val underTest = utils.bouncerActionButtonInteractor()
            val actionButton by collectLastValue(underTest.actionButton)
            mobileConnectionsRepository.isAnySimSecure.value = true
            overrideResource(R.bool.config_enable_emergency_call_while_sim_locked, false)
            utils.configurationRepository.onConfigurationChange()
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.None)
            utils.telephonyRepository.setIsInCall(false)
            runCurrent()

            assertThat(actionButton).isNull()
        }

    companion object {
        private const val MESSAGE_EMERGENCY_CALL = "Emergency"
        private const val MESSAGE_RETURN_TO_CALL = "Return to call"
        private const val ENABLE_EMERGENCY_CALL_WHILE_SIM_LOCKED = true
    }
}

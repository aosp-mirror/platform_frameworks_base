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
 * limitations under the License
 */

package com.android.systemui.shade.data.repository

import android.app.ActivityManager
import android.app.StatusBarManager.DISABLE2_NONE
import android.app.StatusBarManager.DISABLE2_NOTIFICATION_SHADE
import android.app.StatusBarManager.DISABLE2_QUICK_SETTINGS
import android.os.UserManager
import androidx.test.filters.SmallTest
import com.android.internal.logging.UiEventLogger
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractorFactory
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.disableflags.data.model.DisableFlagsModel
import com.android.systemui.statusbar.disableflags.data.repository.FakeDisableFlagsRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.FakeUserSetupRepository
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.telephony.data.repository.FakeTelephonyRepository
import com.android.systemui.telephony.domain.interactor.TelephonyInteractor
import com.android.systemui.user.data.model.UserSwitcherSettingsModel
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.user.domain.interactor.GuestUserInteractor
import com.android.systemui.user.domain.interactor.HeadlessSystemUserMode
import com.android.systemui.user.domain.interactor.RefreshUsersScheduler
import com.android.systemui.user.domain.interactor.UserInteractor
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
class ShadeInteractorTest : SysuiTestCase() {
    private lateinit var underTest: ShadeInteractor

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val featureFlags = FakeFeatureFlags()
    private val userSetupRepository = FakeUserSetupRepository()
    private val userRepository = FakeUserRepository()
    private val disableFlagsRepository = FakeDisableFlagsRepository()
    private val keyguardRepository = FakeKeyguardRepository()

    @Mock private lateinit var manager: UserManager
    @Mock private lateinit var headlessSystemUserMode: HeadlessSystemUserMode
    @Mock private lateinit var deviceProvisionedController: DeviceProvisionedController
    @Mock private lateinit var activityStarter: ActivityStarter
    @Mock private lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor
    @Mock private lateinit var activityManager: ActivityManager
    @Mock private lateinit var uiEventLogger: UiEventLogger
    @Mock private lateinit var guestInteractor: GuestUserInteractor

    private lateinit var userInteractor: UserInteractor

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        featureFlags.set(Flags.FACE_AUTH_REFACTOR, false)

        val refreshUsersScheduler =
            RefreshUsersScheduler(
                applicationScope = testScope.backgroundScope,
                mainDispatcher = testDispatcher,
                repository = userRepository,
            )
        userInteractor =
            UserInteractor(
                applicationContext = context,
                repository = userRepository,
                activityStarter = activityStarter,
                keyguardInteractor =
                    KeyguardInteractorFactory.create(featureFlags = featureFlags)
                        .keyguardInteractor,
                featureFlags = featureFlags,
                manager = manager,
                headlessSystemUserMode = headlessSystemUserMode,
                applicationScope = testScope.backgroundScope,
                telephonyInteractor =
                    TelephonyInteractor(
                        repository = FakeTelephonyRepository(),
                    ),
                broadcastDispatcher = fakeBroadcastDispatcher,
                keyguardUpdateMonitor = keyguardUpdateMonitor,
                backgroundDispatcher = testDispatcher,
                activityManager = activityManager,
                refreshUsersScheduler = refreshUsersScheduler,
                guestUserInteractor = guestInteractor,
                uiEventLogger = uiEventLogger,
            )
        underTest =
            ShadeInteractor(
                disableFlagsRepository,
                keyguardRepository,
                userSetupRepository,
                deviceProvisionedController,
                userInteractor,
            )
    }

    @Test
    fun isExpandToQsEnabled_deviceNotProvisioned_false() =
        testScope.runTest {
            whenever(deviceProvisionedController.isDeviceProvisioned).thenReturn(false)

            val actual by collectLastValue(underTest.isExpandToQsEnabled)

            assertThat(actual).isFalse()
        }

    @Test
    fun isExpandToQsEnabled_userNotSetupAndSimpleUserSwitcher_false() =
        testScope.runTest {
            whenever(deviceProvisionedController.isDeviceProvisioned).thenReturn(true)

            userSetupRepository.setUserSetup(false)
            userRepository.setSettings(UserSwitcherSettingsModel(isSimpleUserSwitcher = true))

            val actual by collectLastValue(underTest.isExpandToQsEnabled)

            assertThat(actual).isFalse()
        }

    @Test
    fun isExpandToQsEnabled_shadeNotEnabled_false() =
        testScope.runTest {
            whenever(deviceProvisionedController.isDeviceProvisioned).thenReturn(true)
            userSetupRepository.setUserSetup(true)

            disableFlagsRepository.disableFlags.value =
                DisableFlagsModel(
                    disable2 = DISABLE2_NOTIFICATION_SHADE,
                )

            val actual by collectLastValue(underTest.isExpandToQsEnabled)

            assertThat(actual).isFalse()
        }

    @Test
    fun isExpandToQsEnabled_quickSettingsNotEnabled_false() =
        testScope.runTest {
            whenever(deviceProvisionedController.isDeviceProvisioned).thenReturn(true)
            userSetupRepository.setUserSetup(true)

            disableFlagsRepository.disableFlags.value =
                DisableFlagsModel(
                    disable2 = DISABLE2_QUICK_SETTINGS,
                )
            val actual by collectLastValue(underTest.isExpandToQsEnabled)

            assertThat(actual).isFalse()
        }

    @Test
    fun isExpandToQsEnabled_dozing_false() =
        testScope.runTest {
            whenever(deviceProvisionedController.isDeviceProvisioned).thenReturn(true)
            userSetupRepository.setUserSetup(true)
            disableFlagsRepository.disableFlags.value =
                DisableFlagsModel(
                    disable2 = DISABLE2_NONE,
                )

            keyguardRepository.setIsDozing(true)

            val actual by collectLastValue(underTest.isExpandToQsEnabled)

            assertThat(actual).isFalse()
        }

    @Test
    fun isExpandToQsEnabled_userSetup_true() =
        testScope.runTest {
            whenever(deviceProvisionedController.isDeviceProvisioned).thenReturn(true)
            keyguardRepository.setIsDozing(false)
            disableFlagsRepository.disableFlags.value =
                DisableFlagsModel(
                    disable2 = DISABLE2_NONE,
                )

            userSetupRepository.setUserSetup(true)

            val actual by collectLastValue(underTest.isExpandToQsEnabled)

            assertThat(actual).isTrue()
        }

    @Test
    fun isExpandToQsEnabled_notSimpleUserSwitcher_true() =
        testScope.runTest {
            whenever(deviceProvisionedController.isDeviceProvisioned).thenReturn(true)
            keyguardRepository.setIsDozing(false)
            disableFlagsRepository.disableFlags.value =
                DisableFlagsModel(
                    disable2 = DISABLE2_NONE,
                )

            userRepository.setSettings(UserSwitcherSettingsModel(isSimpleUserSwitcher = false))

            val actual by collectLastValue(underTest.isExpandToQsEnabled)

            assertThat(actual).isTrue()
        }

    @Test
    fun isExpandToQsEnabled_respondsToDozingUpdates() =
        testScope.runTest {
            whenever(deviceProvisionedController.isDeviceProvisioned).thenReturn(true)
            keyguardRepository.setIsDozing(false)
            disableFlagsRepository.disableFlags.value =
                DisableFlagsModel(
                    disable2 = DISABLE2_NONE,
                )
            userSetupRepository.setUserSetup(true)

            val actual by collectLastValue(underTest.isExpandToQsEnabled)

            assertThat(actual).isTrue()

            // WHEN dozing starts
            keyguardRepository.setIsDozing(true)

            // THEN expand is disabled
            assertThat(actual).isFalse()

            // WHEN dozing stops
            keyguardRepository.setIsDozing(false)

            // THEN expand is enabled
            assertThat(actual).isTrue()
        }

    @Test
    fun isExpandToQsEnabled_respondsToDisableUpdates() =
        testScope.runTest {
            whenever(deviceProvisionedController.isDeviceProvisioned).thenReturn(true)
            keyguardRepository.setIsDozing(false)
            disableFlagsRepository.disableFlags.value =
                DisableFlagsModel(
                    disable2 = DISABLE2_NONE,
                )
            userSetupRepository.setUserSetup(true)

            val actual by collectLastValue(underTest.isExpandToQsEnabled)

            assertThat(actual).isTrue()

            // WHEN QS is disabled
            disableFlagsRepository.disableFlags.value =
                DisableFlagsModel(
                    disable2 = DISABLE2_QUICK_SETTINGS,
                )
            // THEN expand is disabled
            assertThat(actual).isFalse()

            // WHEN QS is enabled
            disableFlagsRepository.disableFlags.value =
                DisableFlagsModel(
                    disable2 = DISABLE2_NONE,
                )
            // THEN expand is enabled
            assertThat(actual).isTrue()
        }

    @Test
    fun isExpandToQsEnabled_respondsToUserUpdates() =
        testScope.runTest {
            whenever(deviceProvisionedController.isDeviceProvisioned).thenReturn(true)
            keyguardRepository.setIsDozing(false)
            disableFlagsRepository.disableFlags.value =
                DisableFlagsModel(
                    disable2 = DISABLE2_NONE,
                )
            userSetupRepository.setUserSetup(true)

            val actual by collectLastValue(underTest.isExpandToQsEnabled)

            assertThat(actual).isTrue()

            // WHEN the user is no longer setup
            userSetupRepository.setUserSetup(false)
            userRepository.setSettings(UserSwitcherSettingsModel(isSimpleUserSwitcher = true))

            // THEN expand is disabled
            assertThat(actual).isFalse()

            // WHEN the user is setup again
            userSetupRepository.setUserSetup(true)

            // THEN expand is enabled
            assertThat(actual).isTrue()
        }
}

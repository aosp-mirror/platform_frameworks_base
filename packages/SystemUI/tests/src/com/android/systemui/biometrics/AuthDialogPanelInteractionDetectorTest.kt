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

package com.android.systemui.biometrics

import android.app.ActivityManager
import android.os.UserManager
import androidx.test.filters.SmallTest
import com.android.internal.logging.UiEventLogger
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.ui.data.repository.FakeConfigurationRepository
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractorFactory
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.scene.SceneTestUtils
import com.android.systemui.scene.shared.flag.FakeSceneContainerFlags
import com.android.systemui.shade.data.repository.FakeShadeRepository
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.disableflags.data.repository.FakeDisableFlagsRepository
import com.android.systemui.statusbar.notification.stack.domain.interactor.SharedNotificationContainerInteractor
import com.android.systemui.statusbar.pipeline.mobile.data.repository.FakeUserSetupRepository
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.statusbar.policy.ResourcesSplitShadeStateController
import com.android.systemui.telephony.data.repository.FakeTelephonyRepository
import com.android.systemui.telephony.domain.interactor.TelephonyInteractor
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.user.domain.interactor.GuestUserInteractor
import com.android.systemui.user.domain.interactor.HeadlessSystemUserMode
import com.android.systemui.user.domain.interactor.RefreshUsersScheduler
import com.android.systemui.user.domain.interactor.UserInteractor
import com.android.systemui.util.mockito.mock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyZeroInteractions
import org.mockito.MockitoAnnotations

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
class AuthDialogPanelInteractionDetectorTest : SysuiTestCase() {
    private val utils = SceneTestUtils(this)
    private val testScope = utils.testScope
    private val testDispatcher = utils.testDispatcher
    private val disableFlagsRepository = FakeDisableFlagsRepository()
    private val featureFlags = FakeFeatureFlags()
    private val keyguardRepository = FakeKeyguardRepository()
    private val shadeRepository = FakeShadeRepository()
    private val sceneContainerFlags = FakeSceneContainerFlags()
    private val sceneInteractor = utils.sceneInteractor()
    private val userSetupRepository = FakeUserSetupRepository()
    private val userRepository = FakeUserRepository()
    private val configurationRepository = FakeConfigurationRepository()
    private val sharedNotificationContainerInteractor =
        SharedNotificationContainerInteractor(
            configurationRepository,
            mContext,
            ResourcesSplitShadeStateController()
        )

    private lateinit var detector: AuthDialogPanelInteractionDetector
    private lateinit var shadeInteractor: ShadeInteractor
    private lateinit var userInteractor: UserInteractor

    @Mock private lateinit var action: Runnable
    @Mock private lateinit var activityManager: ActivityManager
    @Mock private lateinit var activityStarter: ActivityStarter
    @Mock private lateinit var deviceProvisionedController: DeviceProvisionedController
    @Mock private lateinit var guestInteractor: GuestUserInteractor
    @Mock private lateinit var headlessSystemUserMode: HeadlessSystemUserMode
    @Mock private lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor
    @Mock private lateinit var manager: UserManager
    @Mock private lateinit var uiEventLogger: UiEventLogger

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        featureFlags.set(Flags.FACE_AUTH_REFACTOR, false)
        featureFlags.set(Flags.FULL_SCREEN_USER_SWITCHER, true)

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
                userRestrictionChecker = mock(),
            )
        shadeInteractor =
            ShadeInteractor(
                testScope.backgroundScope,
                disableFlagsRepository,
                sceneContainerFlags,
                { sceneInteractor },
                keyguardRepository,
                userSetupRepository,
                deviceProvisionedController,
                userInteractor,
                sharedNotificationContainerInteractor,
                shadeRepository,
            )
        detector = AuthDialogPanelInteractionDetector(testScope, { shadeInteractor })
    }

    @Test
    fun enableDetector_expand_shouldRunAction() =
        testScope.runTest {
            // GIVEN shade is closed and detector is enabled
            shadeRepository.setLegacyShadeExpansion(0f)
            detector.enable(action)
            runCurrent()

            // WHEN shade expands
            shadeRepository.setLegacyShadeExpansion(.5f)
            runCurrent()

            // THEN action was run
            verify(action).run()
        }

    @Test
    fun enableDetector_shadeExpandImmediate_shouldNotPostRunnable() =
        testScope.runTest {
            // GIVEN shade is closed and detector is enabled
            shadeRepository.setLegacyShadeExpansion(0f)
            detector.enable(action)
            runCurrent()

            // WHEN shade expands fully instantly
            shadeRepository.setLegacyShadeExpansion(1f)
            runCurrent()

            // THEN action not run
            verifyZeroInteractions(action)

            // Clean up job
            detector.disable()
        }

    @Test
    fun disableDetector_shouldNotPostRunnable() =
        testScope.runTest {
            // GIVEN shade is closed and detector is enabled
            shadeRepository.setLegacyShadeExpansion(0f)
            detector.enable(action)
            runCurrent()

            // WHEN detector is disabled and shade opens
            detector.disable()
            shadeRepository.setLegacyShadeExpansion(.5f)
            runCurrent()

            // THEN action not run
            verifyZeroInteractions(action)
        }

    @Test
    fun enableDetector_beginCollapse_shouldNotPostRunnable() =
        testScope.runTest {
            // GIVEN shade is open and detector is enabled
            shadeRepository.setLegacyShadeExpansion(1f)
            detector.enable(action)
            runCurrent()

            // WHEN shade begins to collapse
            shadeRepository.setLegacyShadeExpansion(.5f)
            runCurrent()

            // THEN action not run
            verifyZeroInteractions(action)

            // Clean up job
            detector.disable()
        }
}

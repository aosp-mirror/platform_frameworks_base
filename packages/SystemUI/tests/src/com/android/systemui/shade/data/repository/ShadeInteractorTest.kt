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
import android.content.pm.UserInfo
import android.os.UserManager
import androidx.test.filters.SmallTest
import com.android.internal.logging.UiEventLogger
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.ui.data.repository.FakeConfigurationRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractorFactory
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.scene.SceneTestUtils
import com.android.systemui.scene.shared.flag.FakeSceneContainerFlags
import com.android.systemui.scene.shared.model.ObservableTransitionState
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.disableflags.data.model.DisableFlagsModel
import com.android.systemui.statusbar.disableflags.data.repository.FakeDisableFlagsRepository
import com.android.systemui.statusbar.notification.stack.domain.interactor.SharedNotificationContainerInteractor
import com.android.systemui.statusbar.pipeline.mobile.data.repository.FakeUserSetupRepository
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.statusbar.policy.ResourcesSplitShadeStateController
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
class ShadeInteractorTest : SysuiTestCase() {
    private lateinit var underTest: ShadeInteractor

    private val utils = SceneTestUtils(this)
    private val testScope = utils.testScope
    private val featureFlags = FakeFeatureFlags()
    private val sceneContainerFlags = FakeSceneContainerFlags()
    private val sceneInteractor = utils.sceneInteractor()
    private val userSetupRepository = FakeUserSetupRepository()
    private val userRepository = FakeUserRepository()
    private val disableFlagsRepository = FakeDisableFlagsRepository()
    private val keyguardRepository = FakeKeyguardRepository()
    private val shadeRepository = FakeShadeRepository()
    private val configurationRepository = FakeConfigurationRepository()
    private val sharedNotificationContainerInteractor =
        SharedNotificationContainerInteractor(
            configurationRepository,
            mContext,
            ResourcesSplitShadeStateController()
        )

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
        featureFlags.set(Flags.FULL_SCREEN_USER_SWITCHER, true)

        val refreshUsersScheduler =
            RefreshUsersScheduler(
                applicationScope = testScope.backgroundScope,
                mainDispatcher = utils.testDispatcher,
                repository = userRepository,
            )

        runBlocking {
            val userInfos =
                listOf(
                    UserInfo(
                        /* id= */ 0,
                        /* name= */ "zero",
                        /* iconPath= */ "",
                        /* flags= */ UserInfo.FLAG_PRIMARY or
                            UserInfo.FLAG_ADMIN or
                            UserInfo.FLAG_FULL,
                        UserManager.USER_TYPE_FULL_SYSTEM,
                    ),
                )
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
        }
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
                backgroundDispatcher = utils.testDispatcher,
                activityManager = activityManager,
                refreshUsersScheduler = refreshUsersScheduler,
                guestUserInteractor = guestInteractor,
                uiEventLogger = uiEventLogger,
            )
        underTest =
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
    }

    @Test
    fun isShadeEnabled_matchesDisableFlagsRepo() =
        testScope.runTest {
            val actual by collectLastValue(underTest.isShadeEnabled)

            disableFlagsRepository.disableFlags.value =
                DisableFlagsModel(disable2 = DISABLE2_NOTIFICATION_SHADE)
            assertThat(actual).isFalse()

            disableFlagsRepository.disableFlags.value = DisableFlagsModel(disable2 = DISABLE2_NONE)

            assertThat(actual).isTrue()
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

    @Test
    fun fullShadeExpansionWhenShadeLocked() =
        testScope.runTest {
            val actual by collectLastValue(underTest.shadeExpansion)

            keyguardRepository.setStatusBarState(StatusBarState.SHADE_LOCKED)
            shadeRepository.setLockscreenShadeExpansion(0.5f)

            assertThat(actual).isEqualTo(1f)
        }

    @Test
    fun fullShadeExpansionWhenStatusBarStateIsNotShadeLocked() =
        testScope.runTest {
            val actual by collectLastValue(underTest.shadeExpansion)

            keyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)

            shadeRepository.setLockscreenShadeExpansion(0.5f)
            assertThat(actual).isEqualTo(0.5f)

            shadeRepository.setLockscreenShadeExpansion(0.8f)
            assertThat(actual).isEqualTo(0.8f)
        }

    @Test
    fun shadeExpansionWhenInSplitShadeAndQsExpanded() =
        testScope.runTest {
            val actual by collectLastValue(underTest.shadeExpansion)

            // WHEN split shade is enabled and QS is expanded
            keyguardRepository.setStatusBarState(StatusBarState.SHADE)
            overrideResource(R.bool.config_use_split_notification_shade, true)
            configurationRepository.onAnyConfigurationChange()
            shadeRepository.setQsExpansion(.5f)
            shadeRepository.setLegacyShadeExpansion(.7f)
            runCurrent()

            // THEN legacy shade expansion is passed through
            assertThat(actual).isEqualTo(.7f)
        }

    @Test
    fun shadeExpansionWhenNotInSplitShadeAndQsExpanded() =
        testScope.runTest {
            val actual by collectLastValue(underTest.shadeExpansion)

            // WHEN split shade is not enabled and QS is expanded
            keyguardRepository.setStatusBarState(StatusBarState.SHADE)
            overrideResource(R.bool.config_use_split_notification_shade, false)
            shadeRepository.setQsExpansion(.5f)
            shadeRepository.setLegacyShadeExpansion(1f)
            runCurrent()

            // THEN shade expansion is zero
            assertThat(actual).isEqualTo(0f)
        }

    @Test
    fun shadeExpansionWhenNotInSplitShadeAndQsCollapsed() =
        testScope.runTest {
            val actual by collectLastValue(underTest.shadeExpansion)

            // WHEN split shade is not enabled and QS is expanded
            keyguardRepository.setStatusBarState(StatusBarState.SHADE)
            shadeRepository.setQsExpansion(0f)
            shadeRepository.setLegacyShadeExpansion(.6f)

            // THEN shade expansion is zero
            assertThat(actual).isEqualTo(.6f)
        }

    @Test
    fun anyExpansion_shadeGreater() =
        testScope.runTest() {
            // WHEN shade is more expanded than QS
            shadeRepository.setLegacyShadeExpansion(.5f)
            shadeRepository.setQsExpansion(0f)
            runCurrent()

            // THEN anyExpansion is .5f
            assertThat(underTest.anyExpansion.value).isEqualTo(.5f)
        }

    @Test
    fun anyExpansion_qsGreater() =
        testScope.runTest() {
            // WHEN qs is more expanded than shade
            shadeRepository.setLegacyShadeExpansion(0f)
            shadeRepository.setQsExpansion(.5f)
            runCurrent()

            // THEN anyExpansion is .5f
            assertThat(underTest.anyExpansion.value).isEqualTo(.5f)
        }

    @Test
    fun expanding_shadeDraggedDown_expandingTrue() =
        testScope.runTest() {
            val actual by collectLastValue(underTest.isAnyExpanding)

            // GIVEN shade and QS collapsed
            shadeRepository.setLegacyShadeExpansion(0f)
            shadeRepository.setQsExpansion(0f)
            runCurrent()

            // WHEN shade partially expanded
            shadeRepository.setLegacyShadeExpansion(.5f)
            runCurrent()

            // THEN anyExpanding is true
            assertThat(actual).isTrue()
        }

    @Test
    fun expanding_qsDraggedDown_expandingTrue() =
        testScope.runTest() {
            val actual by collectLastValue(underTest.isAnyExpanding)

            // GIVEN shade and QS collapsed
            shadeRepository.setLegacyShadeExpansion(0f)
            shadeRepository.setQsExpansion(0f)
            runCurrent()

            // WHEN shade partially expanded
            shadeRepository.setQsExpansion(.5f)
            runCurrent()

            // THEN anyExpanding is true
            assertThat(actual).isTrue()
        }

    @Test
    fun expanding_shadeDraggedUpAndDown() =
        testScope.runTest() {
            val actual by collectLastValue(underTest.isAnyExpanding)

            // WHEN shade starts collapsed then partially expanded
            shadeRepository.setLegacyShadeExpansion(0f)
            shadeRepository.setLegacyShadeExpansion(.5f)
            shadeRepository.setQsExpansion(0f)
            runCurrent()

            // THEN anyExpanding is true
            assertThat(actual).isTrue()

            // WHEN shade dragged up a bit
            shadeRepository.setLegacyShadeExpansion(.2f)
            runCurrent()

            // THEN anyExpanding is still true
            assertThat(actual).isTrue()

            // WHEN shade dragged down a bit
            shadeRepository.setLegacyShadeExpansion(.7f)
            runCurrent()

            // THEN anyExpanding is still true
            assertThat(actual).isTrue()

            // WHEN shade fully expanded
            shadeRepository.setLegacyShadeExpansion(1f)
            runCurrent()

            // THEN anyExpanding is now false
            assertThat(actual).isFalse()

            // WHEN shade dragged up a bit
            shadeRepository.setLegacyShadeExpansion(.7f)
            runCurrent()

            // THEN anyExpanding is still false
            assertThat(actual).isFalse()
        }

    @Test
    fun expanding_shadeDraggedDownThenUp_expandingFalse() =
        testScope.runTest() {
            val actual by collectLastValue(underTest.isAnyExpanding)

            // GIVEN shade starts collapsed
            shadeRepository.setLegacyShadeExpansion(0f)
            shadeRepository.setQsExpansion(0f)
            runCurrent()

            // WHEN shade expands but doesn't complete
            shadeRepository.setLegacyShadeExpansion(.5f)
            runCurrent()
            shadeRepository.setLegacyShadeExpansion(0f)
            runCurrent()

            // THEN anyExpanding is false
            assertThat(actual).isFalse()
        }

    @Test
    fun lockscreenShadeExpansion_idle_onScene() =
        testScope.runTest() {
            // GIVEN an expansion flow based on transitions to and from a scene
            val key = SceneKey.Shade
            val expansion = underTest.sceneBasedExpansion(sceneInteractor, key)
            val expansionAmount by collectLastValue(expansion)

            // WHEN transition state is idle on the scene
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(ObservableTransitionState.Idle(key))
            sceneInteractor.setTransitionState(transitionState)

            // THEN expansion is 1
            assertThat(expansionAmount).isEqualTo(1f)
        }

    @Test
    fun lockscreenShadeExpansion_idle_onDifferentScene() =
        testScope.runTest() {
            // GIVEN an expansion flow based on transitions to and from a scene
            val expansion = underTest.sceneBasedExpansion(sceneInteractor, SceneKey.Shade)
            val expansionAmount by collectLastValue(expansion)

            // WHEN transition state is idle on a different scene
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Idle(SceneKey.Lockscreen)
                )
            sceneInteractor.setTransitionState(transitionState)

            // THEN expansion is 0
            assertThat(expansionAmount).isEqualTo(0f)
        }

    @Test
    fun lockscreenShadeExpansion_transitioning_toScene() =
        testScope.runTest() {
            // GIVEN an expansion flow based on transitions to and from a scene
            val key = SceneKey.QuickSettings
            val expansion = underTest.sceneBasedExpansion(sceneInteractor, key)
            val expansionAmount by collectLastValue(expansion)

            // WHEN transition state is starting to move to the scene
            val progress = MutableStateFlow(0f)
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Transition(
                        fromScene = SceneKey.Lockscreen,
                        toScene = key,
                        progress = progress,
                        isUserInputDriven = false,
                    )
                )
            sceneInteractor.setTransitionState(transitionState)

            // THEN expansion is 0
            assertThat(expansionAmount).isEqualTo(0f)

            // WHEN transition state is partially to the scene
            progress.value = .4f

            // THEN expansion matches the progress
            assertThat(expansionAmount).isEqualTo(.4f)

            // WHEN transition completes
            progress.value = 1f

            // THEN expansion is 1
            assertThat(expansionAmount).isEqualTo(1f)
        }

    @Test
    fun lockscreenShadeExpansion_transitioning_fromScene() =
        testScope.runTest() {
            // GIVEN an expansion flow based on transitions to and from a scene
            val key = SceneKey.QuickSettings
            val expansion = underTest.sceneBasedExpansion(sceneInteractor, key)
            val expansionAmount by collectLastValue(expansion)

            // WHEN transition state is starting to move to the scene
            val progress = MutableStateFlow(0f)
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Transition(
                        fromScene = key,
                        toScene = SceneKey.Lockscreen,
                        progress = progress,
                        isUserInputDriven = false,
                    )
                )
            sceneInteractor.setTransitionState(transitionState)

            // THEN expansion is 1
            assertThat(expansionAmount).isEqualTo(1f)

            // WHEN transition state is partially to the scene
            progress.value = .4f

            // THEN expansion reflects the progress
            assertThat(expansionAmount).isEqualTo(.6f)

            // WHEN transition completes
            progress.value = 1f

            // THEN expansion is 0
            assertThat(expansionAmount).isEqualTo(0f)
        }

    @Test
    fun lockscreenShadeExpansion_transitioning_toAndFromDifferentScenes() =
        testScope.runTest() {
            // GIVEN an expansion flow based on transitions to and from a scene
            val expansion = underTest.sceneBasedExpansion(sceneInteractor, SceneKey.QuickSettings)
            val expansionAmount by collectLastValue(expansion)

            // WHEN transition state is starting to between different scenes
            val progress = MutableStateFlow(0f)
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Transition(
                        fromScene = SceneKey.Lockscreen,
                        toScene = SceneKey.Shade,
                        progress = progress,
                        isUserInputDriven = false,
                    )
                )
            sceneInteractor.setTransitionState(transitionState)

            // THEN expansion is 0
            assertThat(expansionAmount).isEqualTo(0f)

            // WHEN transition state is partially complete
            progress.value = .4f

            // THEN expansion is still 0
            assertThat(expansionAmount).isEqualTo(0f)

            // WHEN transition completes
            progress.value = 1f

            // THEN expansion is still 0
            assertThat(expansionAmount).isEqualTo(0f)
        }

    @Test
    fun userInteractingWithShade_shadeDraggedUpAndDown() =
        testScope.runTest() {
            val actual by collectLastValue(underTest.isUserInteractingWithShade)
            // GIVEN shade collapsed and not tracking input
            shadeRepository.setLegacyShadeExpansion(0f)
            shadeRepository.setLegacyShadeTracking(false)
            runCurrent()

            // THEN user is not interacting
            assertThat(actual).isFalse()

            // WHEN shade tracking starts
            shadeRepository.setLegacyShadeTracking(true)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN shade dragged down halfway
            shadeRepository.setLegacyShadeExpansion(.5f)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN shade fully expanded but tracking is not stopped
            shadeRepository.setLegacyShadeExpansion(1f)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN shade fully collapsed but tracking is not stopped
            shadeRepository.setLegacyShadeExpansion(0f)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN shade dragged halfway and tracking is stopped
            shadeRepository.setLegacyShadeExpansion(.6f)
            shadeRepository.setLegacyShadeTracking(false)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN shade completes expansion stopped
            shadeRepository.setLegacyShadeExpansion(1f)
            runCurrent()

            // THEN user is not interacting
            assertThat(actual).isFalse()
        }

    @Test
    fun userInteractingWithShade_shadeExpanded() =
        testScope.runTest() {
            val actual by collectLastValue(underTest.isUserInteractingWithShade)
            // GIVEN shade collapsed and not tracking input
            shadeRepository.setLegacyShadeExpansion(0f)
            shadeRepository.setLegacyShadeTracking(false)
            runCurrent()

            // THEN user is not interacting
            assertThat(actual).isFalse()

            // WHEN shade tracking starts
            shadeRepository.setLegacyShadeTracking(true)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN shade dragged down halfway
            shadeRepository.setLegacyShadeExpansion(.5f)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN shade fully expanded and tracking is stopped
            shadeRepository.setLegacyShadeExpansion(1f)
            shadeRepository.setLegacyShadeTracking(false)
            runCurrent()

            // THEN user is not interacting
            assertThat(actual).isFalse()
        }

    @Test
    fun userInteractingWithShade_shadePartiallyExpanded() =
        testScope.runTest() {
            val actual by collectLastValue(underTest.isUserInteractingWithShade)
            // GIVEN shade collapsed and not tracking input
            shadeRepository.setLegacyShadeExpansion(0f)
            shadeRepository.setLegacyShadeTracking(false)
            runCurrent()

            // THEN user is not interacting
            assertThat(actual).isFalse()

            // WHEN shade tracking starts
            shadeRepository.setLegacyShadeTracking(true)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN shade partially expanded
            shadeRepository.setLegacyShadeExpansion(.4f)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN tracking is stopped
            shadeRepository.setLegacyShadeTracking(false)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN shade goes back to collapsed
            shadeRepository.setLegacyShadeExpansion(0f)
            runCurrent()

            // THEN user is not interacting
            assertThat(actual).isFalse()
        }

    @Test
    fun userInteractingWithShade_shadeCollapsed() =
        testScope.runTest() {
            val actual by collectLastValue(underTest.isUserInteractingWithShade)
            // GIVEN shade expanded and not tracking input
            shadeRepository.setLegacyShadeExpansion(1f)
            shadeRepository.setLegacyShadeTracking(false)
            runCurrent()

            // THEN user is not interacting
            assertThat(actual).isFalse()

            // WHEN shade tracking starts
            shadeRepository.setLegacyShadeTracking(true)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN shade dragged up halfway
            shadeRepository.setLegacyShadeExpansion(.5f)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN shade fully collapsed and tracking is stopped
            shadeRepository.setLegacyShadeExpansion(0f)
            shadeRepository.setLegacyShadeTracking(false)
            runCurrent()

            // THEN user is not interacting
            assertThat(actual).isFalse()
        }

    @Test
    fun userInteractingWithQs_qsDraggedUpAndDown() =
        testScope.runTest() {
            val actual by collectLastValue(underTest.isUserInteractingWithQs)
            // GIVEN qs collapsed and not tracking input
            shadeRepository.setQsExpansion(0f)
            shadeRepository.setLegacyQsTracking(false)
            runCurrent()

            // THEN user is not interacting
            assertThat(actual).isFalse()

            // WHEN qs tracking starts
            shadeRepository.setLegacyQsTracking(true)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN qs dragged down halfway
            shadeRepository.setQsExpansion(.5f)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN qs fully expanded but tracking is not stopped
            shadeRepository.setQsExpansion(1f)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN qs fully collapsed but tracking is not stopped
            shadeRepository.setQsExpansion(0f)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN qs dragged halfway and tracking is stopped
            shadeRepository.setQsExpansion(.6f)
            shadeRepository.setLegacyQsTracking(false)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN qs completes expansion stopped
            shadeRepository.setQsExpansion(1f)
            runCurrent()

            // THEN user is not interacting
            assertThat(actual).isFalse()
        }
    @Test
    fun userInteracting_idle() =
        testScope.runTest() {
            // GIVEN an interacting flow based on transitions to and from a scene
            val key = SceneKey.Shade
            val interactingFlow = underTest.sceneBasedInteracting(sceneInteractor, key)
            val interacting by collectLastValue(interactingFlow)

            // WHEN transition state is idle
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(ObservableTransitionState.Idle(key))
            sceneInteractor.setTransitionState(transitionState)

            // THEN interacting is false
            assertThat(interacting).isFalse()
        }

    @Test
    fun userInteracting_transitioning_toScene_programmatic() =
        testScope.runTest() {
            // GIVEN an interacting flow based on transitions to and from a scene
            val key = SceneKey.QuickSettings
            val interactingFlow = underTest.sceneBasedInteracting(sceneInteractor, key)
            val interacting by collectLastValue(interactingFlow)

            // WHEN transition state is starting to move to the scene
            val progress = MutableStateFlow(0f)
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Transition(
                        fromScene = SceneKey.Lockscreen,
                        toScene = key,
                        progress = progress,
                        isUserInputDriven = false,
                    )
                )
            sceneInteractor.setTransitionState(transitionState)

            // THEN interacting is false
            assertThat(interacting).isFalse()

            // WHEN transition state is partially to the scene
            progress.value = .4f

            // THEN interacting is false
            assertThat(interacting).isFalse()

            // WHEN transition completes
            progress.value = 1f

            // THEN interacting is false
            assertThat(interacting).isFalse()
        }

    @Test
    fun userInteracting_transitioning_toScene_userInputDriven() =
        testScope.runTest() {
            // GIVEN an interacting flow based on transitions to and from a scene
            val key = SceneKey.QuickSettings
            val interactingFlow = underTest.sceneBasedInteracting(sceneInteractor, key)
            val interacting by collectLastValue(interactingFlow)

            // WHEN transition state is starting to move to the scene
            val progress = MutableStateFlow(0f)
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Transition(
                        fromScene = SceneKey.Lockscreen,
                        toScene = key,
                        progress = progress,
                        isUserInputDriven = true,
                    )
                )
            sceneInteractor.setTransitionState(transitionState)

            // THEN interacting is true
            assertThat(interacting).isTrue()

            // WHEN transition state is partially to the scene
            progress.value = .4f

            // THEN interacting is true
            assertThat(interacting).isTrue()

            // WHEN transition completes
            progress.value = 1f

            // THEN interacting is true
            assertThat(interacting).isTrue()
        }

    @Test
    fun userInteracting_transitioning_fromScene_programmatic() =
        testScope.runTest() {
            // GIVEN an interacting flow based on transitions to and from a scene
            val key = SceneKey.QuickSettings
            val interactingFlow = underTest.sceneBasedInteracting(sceneInteractor, key)
            val interacting by collectLastValue(interactingFlow)

            // WHEN transition state is starting to move to the scene
            val progress = MutableStateFlow(0f)
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Transition(
                        fromScene = key,
                        toScene = SceneKey.Lockscreen,
                        progress = progress,
                        isUserInputDriven = false,
                    )
                )
            sceneInteractor.setTransitionState(transitionState)

            // THEN interacting is false
            assertThat(interacting).isFalse()

            // WHEN transition state is partially to the scene
            progress.value = .4f

            // THEN interacting is false
            assertThat(interacting).isFalse()

            // WHEN transition completes
            progress.value = 1f

            // THEN interacting is false
            assertThat(interacting).isFalse()
        }

    @Test
    fun userInteracting_transitioning_fromScene_userInputDriven() =
        testScope.runTest() {
            // GIVEN an interacting flow based on transitions to and from a scene
            val key = SceneKey.QuickSettings
            val interactingFlow = underTest.sceneBasedInteracting(sceneInteractor, key)
            val interacting by collectLastValue(interactingFlow)

            // WHEN transition state is starting to move to the scene
            val progress = MutableStateFlow(0f)
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Transition(
                        fromScene = key,
                        toScene = SceneKey.Lockscreen,
                        progress = progress,
                        isUserInputDriven = true,
                    )
                )
            sceneInteractor.setTransitionState(transitionState)

            // THEN interacting is true
            assertThat(interacting).isTrue()

            // WHEN transition state is partially to the scene
            progress.value = .4f

            // THEN interacting is true
            assertThat(interacting).isTrue()

            // WHEN transition completes
            progress.value = 1f

            // THEN interacting is true
            assertThat(interacting).isTrue()
        }

    @Test
    fun userInteracting_transitioning_toAndFromDifferentScenes() =
        testScope.runTest() {
            // GIVEN an interacting flow based on transitions to and from a scene
            val interactingFlow = underTest.sceneBasedInteracting(sceneInteractor, SceneKey.Shade)
            val interacting by collectLastValue(interactingFlow)

            // WHEN transition state is starting to between different scenes
            val progress = MutableStateFlow(0f)
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Transition(
                        fromScene = SceneKey.Lockscreen,
                        toScene = SceneKey.QuickSettings,
                        progress = progress,
                        isUserInputDriven = true,
                    )
                )
            sceneInteractor.setTransitionState(transitionState)

            // THEN interacting is false
            assertThat(interacting).isFalse()
        }
}

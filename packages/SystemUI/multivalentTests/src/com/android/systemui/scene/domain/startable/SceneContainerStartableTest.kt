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

@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalCoroutinesApi::class)

package com.android.systemui.scene.domain.startable

import android.app.StatusBarManager
import android.os.PowerManager
import android.platform.test.annotations.EnableFlags
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.SceneKey
import com.android.systemui.Flags as AconfigFlags
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.fakeAuthenticationRepository
import com.android.systemui.authentication.domain.interactor.authenticationInteractor
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.bouncer.domain.interactor.bouncerInteractor
import com.android.systemui.bouncer.domain.interactor.simBouncerInteractor
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.classifier.falsingManager
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.data.repository.fakeDeviceEntryRepository
import com.android.systemui.deviceentry.domain.interactor.deviceEntryInteractor
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFaceAuthRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.kosmos.testScope
import com.android.systemui.model.SysUiState
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAsleepForTest
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAwakeForTest
import com.android.systemui.power.domain.interactor.PowerInteractorFactory
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.flag.fakeSceneContainerFlags
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.shared.model.fakeSceneDataSource
import com.android.systemui.statusbar.NotificationShadeWindowController
import com.android.systemui.statusbar.notification.stack.data.repository.headsUpNotificationRepository
import com.android.systemui.statusbar.notification.stack.domain.interactor.headsUpNotificationInteractor
import com.android.systemui.statusbar.phone.CentralSurfaces
import com.android.systemui.statusbar.pipeline.mobile.data.repository.fakeMobileConnectionsRepository
import com.android.systemui.statusbar.policy.data.repository.fakeDeviceProvisioningRepository
import com.android.systemui.statusbar.policy.domain.interactor.deviceProvisioningInteractor
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@EnableFlags(AconfigFlags.FLAG_SCENE_CONTAINER)
class SceneContainerStartableTest : SysuiTestCase() {

    @Mock private lateinit var windowController: NotificationShadeWindowController
    @Mock private lateinit var centralSurfaces: CentralSurfaces

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val sceneInteractor by lazy { kosmos.sceneInteractor }
    private val sceneContainerFlags by lazy { kosmos.fakeSceneContainerFlags }
    private val authenticationInteractor by lazy { kosmos.authenticationInteractor }
    private val bouncerInteractor by lazy { kosmos.bouncerInteractor }
    private val faceAuthRepository by lazy { kosmos.fakeDeviceEntryFaceAuthRepository }
    private val deviceEntryInteractor by lazy { kosmos.deviceEntryInteractor }
    private val keyguardInteractor by lazy { kosmos.keyguardInteractor }
    private val sysUiState: SysUiState = mock()
    private val falsingCollector: FalsingCollector = mock()
    private val powerInteractor = PowerInteractorFactory.create().powerInteractor
    private val fakeSceneDataSource = kosmos.fakeSceneDataSource

    private lateinit var underTest: SceneContainerStartable

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        underTest =
            SceneContainerStartable(
                applicationScope = testScope.backgroundScope,
                sceneInteractor = sceneInteractor,
                deviceEntryInteractor = deviceEntryInteractor,
                keyguardInteractor = keyguardInteractor,
                flags = sceneContainerFlags,
                sysUiState = sysUiState,
                displayId = Display.DEFAULT_DISPLAY,
                sceneLogger = mock(),
                falsingCollector = falsingCollector,
                falsingManager = kosmos.falsingManager,
                powerInteractor = powerInteractor,
                bouncerInteractor = bouncerInteractor,
                simBouncerInteractor = { kosmos.simBouncerInteractor },
                authenticationInteractor = { authenticationInteractor },
                windowController = windowController,
                deviceProvisioningInteractor = kosmos.deviceProvisioningInteractor,
                centralSurfaces = centralSurfaces,
                headsUpInteractor = kosmos.headsUpNotificationInteractor,
            )
    }

    @Test
    fun hydrateVisibility() =
        testScope.runTest {
            val currentDesiredSceneKey by collectLastValue(sceneInteractor.currentScene)
            val isVisible by collectLastValue(sceneInteractor.isVisible)
            val transitionStateFlow =
                prepareState(
                    isDeviceUnlocked = true,
                    initialSceneKey = Scenes.Gone,
                )
            assertThat(currentDesiredSceneKey).isEqualTo(Scenes.Gone)
            assertThat(isVisible).isTrue()

            underTest.start()
            assertThat(isVisible).isFalse()

            fakeSceneDataSource.pause()
            sceneInteractor.changeScene(Scenes.Shade, "reason")
            transitionStateFlow.value =
                ObservableTransitionState.Transition(
                    fromScene = Scenes.Gone,
                    toScene = Scenes.Shade,
                    progress = flowOf(0.5f),
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = flowOf(false),
                )
            assertThat(isVisible).isTrue()
            fakeSceneDataSource.unpause(expectedScene = Scenes.Shade)
            transitionStateFlow.value = ObservableTransitionState.Idle(Scenes.Shade)
            assertThat(isVisible).isTrue()

            fakeSceneDataSource.pause()
            sceneInteractor.changeScene(Scenes.Gone, "reason")
            transitionStateFlow.value =
                ObservableTransitionState.Transition(
                    fromScene = Scenes.Shade,
                    toScene = Scenes.Gone,
                    progress = flowOf(0.5f),
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = flowOf(false),
                )
            assertThat(isVisible).isTrue()
            fakeSceneDataSource.unpause(expectedScene = Scenes.Gone)
            transitionStateFlow.value = ObservableTransitionState.Idle(Scenes.Gone)
            assertThat(isVisible).isFalse()

            kosmos.headsUpNotificationRepository.hasPinnedHeadsUp.value = true
            assertThat(isVisible).isTrue()

            kosmos.headsUpNotificationRepository.hasPinnedHeadsUp.value = false
            assertThat(isVisible).isFalse()
        }

    @Test
    fun hydrateVisibility_basedOnDeviceProvisioningAndFactoryResetProtection() =
        testScope.runTest {
            val isVisible by collectLastValue(sceneInteractor.isVisible)
            prepareState(
                isDeviceUnlocked = true,
                initialSceneKey = Scenes.Lockscreen,
                isDeviceProvisioned = false,
                isFrpActive = true,
            )

            underTest.start()
            assertThat(isVisible).isFalse()

            kosmos.fakeDeviceProvisioningRepository.setFactoryResetProtectionActive(false)
            assertThat(isVisible).isFalse()

            kosmos.fakeDeviceProvisioningRepository.setDeviceProvisioned(true)
            assertThat(isVisible).isTrue()

            kosmos.fakeDeviceProvisioningRepository.setFactoryResetProtectionActive(true)
            assertThat(isVisible).isFalse()
        }

    @Test
    fun startsInLockscreenScene() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            prepareState()

            underTest.start()
            runCurrent()

            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun switchToLockscreenWhenDeviceLocks() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            prepareState(
                isDeviceUnlocked = true,
                initialSceneKey = Scenes.Gone,
            )
            assertThat(currentSceneKey).isEqualTo(Scenes.Gone)
            underTest.start()

            kosmos.fakeDeviceEntryRepository.setUnlocked(false)

            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun switchFromBouncerToGoneWhenDeviceUnlocked() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            prepareState(
                isDeviceUnlocked = false,
                initialSceneKey = Scenes.Bouncer,
            )
            assertThat(currentSceneKey).isEqualTo(Scenes.Bouncer)
            underTest.start()

            kosmos.fakeDeviceEntryRepository.setUnlocked(true)

            assertThat(currentSceneKey).isEqualTo(Scenes.Gone)
        }

    @Test
    fun switchFromLockscreenToGoneWhenDeviceUnlocksWithBypassOn() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            prepareState(
                isBypassEnabled = true,
                initialSceneKey = Scenes.Lockscreen,
            )
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            underTest.start()

            kosmos.fakeDeviceEntryRepository.setUnlocked(true)

            assertThat(currentSceneKey).isEqualTo(Scenes.Gone)
        }

    @Test
    fun stayOnLockscreenWhenDeviceUnlocksWithBypassOff() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            prepareState(
                isBypassEnabled = false,
                initialSceneKey = Scenes.Lockscreen,
            )
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            underTest.start()

            // Authenticate using a passive auth method like face auth while bypass is disabled.
            faceAuthRepository.isAuthenticated.value = true
            kosmos.fakeDeviceEntryRepository.setUnlocked(true)

            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun stayOnCurrentSceneWhenDeviceIsUnlockedAndUserIsNotOnLockscreen() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val transitionStateFlowValue =
                prepareState(
                    isBypassEnabled = true,
                    authenticationMethod = AuthenticationMethodModel.Pin,
                    initialSceneKey = Scenes.Lockscreen,
                )
            underTest.start()
            runCurrent()

            sceneInteractor.changeScene(Scenes.Shade, "switch to shade")
            transitionStateFlowValue.value = ObservableTransitionState.Idle(Scenes.Shade)
            assertThat(currentSceneKey).isEqualTo(Scenes.Shade)

            kosmos.fakeDeviceEntryRepository.setUnlocked(true)
            runCurrent()

            assertThat(currentSceneKey).isEqualTo(Scenes.Shade)
        }

    @Test
    fun switchToGoneWhenDeviceIsUnlockedAndUserIsOnBouncerWithBypassDisabled() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            prepareState(
                isBypassEnabled = false,
                initialSceneKey = Scenes.Bouncer,
            )
            assertThat(currentSceneKey).isEqualTo(Scenes.Bouncer)
            underTest.start()

            // Authenticate using a passive auth method like face auth while bypass is disabled.
            faceAuthRepository.isAuthenticated.value = true
            kosmos.fakeDeviceEntryRepository.setUnlocked(true)

            assertThat(currentSceneKey).isEqualTo(Scenes.Gone)
        }

    @Test
    fun switchToLockscreenWhenDeviceSleepsLocked() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            prepareState(
                isDeviceUnlocked = false,
                initialSceneKey = Scenes.Shade,
            )
            assertThat(currentSceneKey).isEqualTo(Scenes.Shade)
            underTest.start()
            powerInteractor.setAsleepForTest()

            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun hydrateSystemUiState() =
        testScope.runTest {
            val transitionStateFlow = prepareState()
            underTest.start()
            runCurrent()
            clearInvocations(sysUiState)

            listOf(
                    Scenes.Gone,
                    Scenes.Lockscreen,
                    Scenes.Bouncer,
                    Scenes.Shade,
                    Scenes.QuickSettings,
                )
                .forEachIndexed { index, sceneKey ->
                    if (sceneKey == Scenes.Gone) {
                        kosmos.fakeDeviceEntryRepository.setUnlocked(true)
                        runCurrent()
                    }
                    fakeSceneDataSource.pause()
                    sceneInteractor.changeScene(sceneKey, "reason")
                    runCurrent()
                    verify(sysUiState, times(index)).commitUpdate(Display.DEFAULT_DISPLAY)

                    fakeSceneDataSource.unpause(expectedScene = sceneKey)
                    runCurrent()
                    verify(sysUiState, times(index)).commitUpdate(Display.DEFAULT_DISPLAY)

                    transitionStateFlow.value = ObservableTransitionState.Idle(sceneKey)
                    runCurrent()
                    verify(sysUiState, times(index + 1)).commitUpdate(Display.DEFAULT_DISPLAY)
                }
        }

    @Test
    fun switchToGoneWhenDeviceStartsToWakeUp_authMethodNone() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            prepareState(
                initialSceneKey = Scenes.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.None,
                isLockscreenEnabled = false,
            )
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            underTest.start()
            powerInteractor.setAwakeForTest()

            assertThat(currentSceneKey).isEqualTo(Scenes.Gone)
        }

    @Test
    fun stayOnLockscreenWhenDeviceStartsToWakeUp_authMethodSwipe() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            prepareState(
                initialSceneKey = Scenes.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.None,
                isLockscreenEnabled = true,
            )
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            underTest.start()
            powerInteractor.setAwakeForTest()

            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun doesNotSwitchToGoneWhenDeviceStartsToWakeUp_authMethodSecure() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            prepareState(
                initialSceneKey = Scenes.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.Pin,
            )
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            underTest.start()
            powerInteractor.setAwakeForTest()

            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun switchToGoneWhenDeviceStartsToWakeUp_authMethodSecure_deviceUnlocked() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            prepareState(
                initialSceneKey = Scenes.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = false,
                startsAwake = false
            )
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            underTest.start()

            kosmos.fakeDeviceEntryRepository.setUnlocked(true)
            runCurrent()
            powerInteractor.setAwakeForTest()
            runCurrent()

            assertThat(currentSceneKey).isEqualTo(Scenes.Gone)
        }

    @Test
    fun collectFalsingSignals_onSuccessfulUnlock() =
        testScope.runTest {
            prepareState(
                initialSceneKey = Scenes.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = false,
            )
            underTest.start()
            runCurrent()
            verify(falsingCollector, never()).onSuccessfulUnlock()

            // Move around scenes without unlocking.
            listOf(
                    Scenes.Shade,
                    Scenes.QuickSettings,
                    Scenes.Shade,
                    Scenes.Lockscreen,
                    Scenes.Bouncer,
                )
                .forEach { sceneKey ->
                    sceneInteractor.changeScene(sceneKey, "reason")
                    runCurrent()
                    verify(falsingCollector, never()).onSuccessfulUnlock()
                }

            // Changing to the Gone scene should report a successful unlock.
            kosmos.fakeDeviceEntryRepository.setUnlocked(true)
            runCurrent()
            sceneInteractor.changeScene(Scenes.Gone, "reason")
            runCurrent()
            verify(falsingCollector).onSuccessfulUnlock()

            // Move around scenes without changing back to Lockscreen, shouldn't report another
            // unlock.
            listOf(
                    Scenes.Shade,
                    Scenes.QuickSettings,
                    Scenes.Shade,
                    Scenes.Gone,
                )
                .forEach { sceneKey ->
                    sceneInteractor.changeScene(sceneKey, "reason")
                    runCurrent()
                    verify(falsingCollector, times(1)).onSuccessfulUnlock()
                }

            // Changing to the Lockscreen scene shouldn't report a successful unlock.
            sceneInteractor.changeScene(Scenes.Lockscreen, "reason")
            runCurrent()
            verify(falsingCollector, times(1)).onSuccessfulUnlock()

            // Move around scenes without unlocking.
            listOf(
                    Scenes.Shade,
                    Scenes.QuickSettings,
                    Scenes.Shade,
                    Scenes.Lockscreen,
                    Scenes.Bouncer,
                )
                .forEach { sceneKey ->
                    sceneInteractor.changeScene(sceneKey, "reason")
                    runCurrent()
                    verify(falsingCollector, times(1)).onSuccessfulUnlock()
                }

            // Changing to the Gone scene should report a second successful unlock.
            sceneInteractor.changeScene(Scenes.Gone, "reason")
            runCurrent()
            verify(falsingCollector, times(2)).onSuccessfulUnlock()
        }

    @Test
    fun collectFalsingSignals_setShowingAod() =
        testScope.runTest {
            prepareState(
                initialSceneKey = Scenes.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = false,
            )
            underTest.start()
            runCurrent()
            verify(falsingCollector).setShowingAod(false)

            kosmos.fakeKeyguardRepository.setIsDozing(true)
            runCurrent()
            verify(falsingCollector).setShowingAod(true)

            kosmos.fakeKeyguardRepository.setIsDozing(false)
            runCurrent()
            verify(falsingCollector, times(2)).setShowingAod(false)
        }

    @Test
    fun bouncerImeHidden_shouldTransitionBackToLockscreen() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            prepareState(
                initialSceneKey = Scenes.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.Password,
                isDeviceUnlocked = false,
            )
            underTest.start()
            runCurrent()

            bouncerInteractor.onImeHiddenByUser()
            runCurrent()

            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun collectFalsingSignals_screenOnAndOff_aodUnavailable() =
        testScope.runTest {
            kosmos.fakeKeyguardRepository.setAodAvailable(false)
            runCurrent()
            prepareState(
                initialSceneKey = Scenes.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = false,
                startsAwake = false,
            )
            underTest.start()
            runCurrent()
            verify(falsingCollector, never()).onScreenTurningOn()
            verify(falsingCollector, never()).onScreenOnFromTouch()
            verify(falsingCollector, times(1)).onScreenOff()

            powerInteractor.setAwakeForTest(reason = PowerManager.WAKE_REASON_POWER_BUTTON)
            runCurrent()
            verify(falsingCollector, times(1)).onScreenTurningOn()
            verify(falsingCollector, never()).onScreenOnFromTouch()
            verify(falsingCollector, times(1)).onScreenOff()

            powerInteractor.setAsleepForTest()
            runCurrent()
            verify(falsingCollector, times(1)).onScreenTurningOn()
            verify(falsingCollector, never()).onScreenOnFromTouch()
            verify(falsingCollector, times(2)).onScreenOff()

            powerInteractor.setAwakeForTest(reason = PowerManager.WAKE_REASON_TAP)
            runCurrent()
            verify(falsingCollector, times(1)).onScreenTurningOn()
            verify(falsingCollector, times(1)).onScreenOnFromTouch()
            verify(falsingCollector, times(2)).onScreenOff()

            powerInteractor.setAsleepForTest()
            runCurrent()
            verify(falsingCollector, times(1)).onScreenTurningOn()
            verify(falsingCollector, times(1)).onScreenOnFromTouch()
            verify(falsingCollector, times(3)).onScreenOff()

            powerInteractor.setAwakeForTest(reason = PowerManager.WAKE_REASON_POWER_BUTTON)
            runCurrent()
            verify(falsingCollector, times(2)).onScreenTurningOn()
            verify(falsingCollector, times(1)).onScreenOnFromTouch()
            verify(falsingCollector, times(3)).onScreenOff()
        }

    @Test
    fun collectFalsingSignals_screenOnAndOff_aodAvailable() =
        testScope.runTest {
            kosmos.fakeKeyguardRepository.setAodAvailable(true)
            runCurrent()
            prepareState(
                initialSceneKey = Scenes.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = false,
            )
            underTest.start()
            runCurrent()
            verify(falsingCollector, never()).onScreenTurningOn()
            verify(falsingCollector, never()).onScreenOnFromTouch()
            verify(falsingCollector, never()).onScreenOff()

            powerInteractor.setAwakeForTest(reason = PowerManager.WAKE_REASON_POWER_BUTTON)
            runCurrent()
            verify(falsingCollector, never()).onScreenTurningOn()
            verify(falsingCollector, never()).onScreenOnFromTouch()
            verify(falsingCollector, never()).onScreenOff()

            powerInteractor.setAsleepForTest()
            runCurrent()
            verify(falsingCollector, never()).onScreenTurningOn()
            verify(falsingCollector, never()).onScreenOnFromTouch()
            verify(falsingCollector, never()).onScreenOff()

            powerInteractor.setAwakeForTest(reason = PowerManager.WAKE_REASON_TAP)
            runCurrent()
            verify(falsingCollector, never()).onScreenTurningOn()
            verify(falsingCollector, never()).onScreenOnFromTouch()
            verify(falsingCollector, never()).onScreenOff()

            powerInteractor.setAsleepForTest()
            runCurrent()
            verify(falsingCollector, never()).onScreenTurningOn()
            verify(falsingCollector, never()).onScreenOnFromTouch()
            verify(falsingCollector, never()).onScreenOff()

            powerInteractor.setAwakeForTest(reason = PowerManager.WAKE_REASON_POWER_BUTTON)
            runCurrent()
            verify(falsingCollector, never()).onScreenTurningOn()
            verify(falsingCollector, never()).onScreenOnFromTouch()
            verify(falsingCollector, never()).onScreenOff()
        }

    @Test
    fun collectFalsingSignals_bouncerVisibility() =
        testScope.runTest {
            prepareState(
                initialSceneKey = Scenes.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = false,
            )
            underTest.start()
            runCurrent()
            verify(falsingCollector).onBouncerHidden()

            sceneInteractor.changeScene(Scenes.Bouncer, "reason")
            runCurrent()
            verify(falsingCollector).onBouncerShown()

            kosmos.fakeDeviceEntryRepository.setUnlocked(true)
            runCurrent()
            sceneInteractor.changeScene(Scenes.Gone, "reason")
            runCurrent()
            verify(falsingCollector, times(2)).onBouncerHidden()
        }

    @Test
    fun switchesToBouncer_whenSimBecomesLocked() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)

            prepareState(
                initialSceneKey = Scenes.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = false,
            )
            underTest.start()
            runCurrent()

            kosmos.fakeMobileConnectionsRepository.isAnySimSecure.value = true
            runCurrent()

            assertThat(currentSceneKey).isEqualTo(Scenes.Bouncer)
        }

    @Test
    fun switchesToLockscreen_whenSimBecomesUnlocked() =
        testScope.runTest {
            kosmos.fakeMobileConnectionsRepository.isAnySimSecure.value = true
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)

            prepareState(
                initialSceneKey = Scenes.Bouncer,
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = false,
            )
            underTest.start()
            runCurrent()
            kosmos.fakeMobileConnectionsRepository.isAnySimSecure.value = false
            runCurrent()

            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun switchesToGone_whenSimBecomesUnlocked_ifDeviceUnlockedAndLockscreenDisabled() =
        testScope.runTest {
            kosmos.fakeMobileConnectionsRepository.isAnySimSecure.value = true
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)

            prepareState(
                initialSceneKey = Scenes.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.None,
                isDeviceUnlocked = true,
                isLockscreenEnabled = false,
            )
            underTest.start()
            runCurrent()
            kosmos.fakeMobileConnectionsRepository.isAnySimSecure.value = false
            runCurrent()

            assertThat(currentSceneKey).isEqualTo(Scenes.Gone)
        }

    @Test
    fun hydrateWindowFocus() =
        testScope.runTest {
            val currentDesiredSceneKey by collectLastValue(sceneInteractor.currentScene)
            val transitionStateFlow =
                prepareState(
                    isDeviceUnlocked = true,
                    initialSceneKey = Scenes.Gone,
                )
            assertThat(currentDesiredSceneKey).isEqualTo(Scenes.Gone)
            verify(windowController, never()).setNotificationShadeFocusable(anyBoolean())

            underTest.start()
            runCurrent()
            verify(windowController, times(1)).setNotificationShadeFocusable(false)

            fakeSceneDataSource.pause()
            sceneInteractor.changeScene(Scenes.Shade, "reason")
            transitionStateFlow.value =
                ObservableTransitionState.Transition(
                    fromScene = Scenes.Gone,
                    toScene = Scenes.Shade,
                    progress = flowOf(0.5f),
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = flowOf(false),
                )
            runCurrent()
            verify(windowController, times(1)).setNotificationShadeFocusable(false)

            fakeSceneDataSource.unpause(expectedScene = Scenes.Shade)
            transitionStateFlow.value = ObservableTransitionState.Idle(Scenes.Shade)
            runCurrent()
            verify(windowController, times(1)).setNotificationShadeFocusable(true)

            fakeSceneDataSource.pause()
            sceneInteractor.changeScene(Scenes.Gone, "reason")
            transitionStateFlow.value =
                ObservableTransitionState.Transition(
                    fromScene = Scenes.Shade,
                    toScene = Scenes.Gone,
                    progress = flowOf(0.5f),
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = flowOf(false),
                )
            runCurrent()
            verify(windowController, times(1)).setNotificationShadeFocusable(true)

            fakeSceneDataSource.unpause(expectedScene = Scenes.Gone)
            transitionStateFlow.value = ObservableTransitionState.Idle(Scenes.Gone)
            runCurrent()
            verify(windowController, times(2)).setNotificationShadeFocusable(false)
        }

    @Test
    fun hydrateInteractionState_whileLocked() =
        testScope.runTest {
            val transitionStateFlow =
                prepareState(
                    initialSceneKey = Scenes.Lockscreen,
                )
            underTest.start()
            runCurrent()
            verify(centralSurfaces).setInteracting(StatusBarManager.WINDOW_STATUS_BAR, true)

            clearInvocations(centralSurfaces)
            emulateSceneTransition(
                transitionStateFlow = transitionStateFlow,
                toScene = Scenes.Bouncer,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces)
                        .setInteracting(
                            StatusBarManager.WINDOW_STATUS_BAR,
                            false,
                        )
                },
            )

            clearInvocations(centralSurfaces)
            emulateSceneTransition(
                transitionStateFlow = transitionStateFlow,
                toScene = Scenes.Lockscreen,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces)
                        .setInteracting(
                            StatusBarManager.WINDOW_STATUS_BAR,
                            true,
                        )
                },
            )

            clearInvocations(centralSurfaces)
            emulateSceneTransition(
                transitionStateFlow = transitionStateFlow,
                toScene = Scenes.Shade,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces)
                        .setInteracting(
                            StatusBarManager.WINDOW_STATUS_BAR,
                            false,
                        )
                },
            )

            clearInvocations(centralSurfaces)
            emulateSceneTransition(
                transitionStateFlow = transitionStateFlow,
                toScene = Scenes.Lockscreen,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces)
                        .setInteracting(
                            StatusBarManager.WINDOW_STATUS_BAR,
                            true,
                        )
                },
            )

            clearInvocations(centralSurfaces)
            emulateSceneTransition(
                transitionStateFlow = transitionStateFlow,
                toScene = Scenes.QuickSettings,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
            )
        }

    @Test
    fun hydrateInteractionState_whileUnlocked() =
        testScope.runTest {
            val transitionStateFlow =
                prepareState(
                    isDeviceUnlocked = true,
                    initialSceneKey = Scenes.Gone,
                )
            underTest.start()
            verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())

            clearInvocations(centralSurfaces)
            emulateSceneTransition(
                transitionStateFlow = transitionStateFlow,
                toScene = Scenes.Bouncer,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
            )

            clearInvocations(centralSurfaces)
            emulateSceneTransition(
                transitionStateFlow = transitionStateFlow,
                toScene = Scenes.Lockscreen,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
            )

            clearInvocations(centralSurfaces)
            emulateSceneTransition(
                transitionStateFlow = transitionStateFlow,
                toScene = Scenes.Shade,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
            )

            clearInvocations(centralSurfaces)
            emulateSceneTransition(
                transitionStateFlow = transitionStateFlow,
                toScene = Scenes.Lockscreen,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
            )

            clearInvocations(centralSurfaces)
            emulateSceneTransition(
                transitionStateFlow = transitionStateFlow,
                toScene = Scenes.QuickSettings,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
            )
        }

    @Test
    fun respondToFalsingDetections() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            val transitionStateFlow = prepareState()
            underTest.start()
            emulateSceneTransition(transitionStateFlow, toScene = Scenes.Bouncer)
            assertThat(currentScene).isNotEqualTo(Scenes.Lockscreen)

            kosmos.falsingManager.sendFalsingBelief()

            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
        }

    private fun TestScope.emulateSceneTransition(
        transitionStateFlow: MutableStateFlow<ObservableTransitionState>,
        toScene: SceneKey,
        verifyBeforeTransition: (() -> Unit)? = null,
        verifyDuringTransition: (() -> Unit)? = null,
        verifyAfterTransition: (() -> Unit)? = null,
    ) {
        val fromScene = sceneInteractor.currentScene.value
        sceneInteractor.changeScene(toScene, "reason")
        runCurrent()
        verifyBeforeTransition?.invoke()

        transitionStateFlow.value =
            ObservableTransitionState.Transition(
                fromScene = fromScene,
                toScene = toScene,
                progress = flowOf(0.5f),
                isInitiatedByUserInput = true,
                isUserInputOngoing = flowOf(true),
            )
        runCurrent()
        verifyDuringTransition?.invoke()

        transitionStateFlow.value =
            ObservableTransitionState.Idle(
                scene = toScene,
            )
        runCurrent()
        verifyAfterTransition?.invoke()
    }

    private fun TestScope.prepareState(
        isDeviceUnlocked: Boolean = false,
        isBypassEnabled: Boolean = false,
        initialSceneKey: SceneKey? = null,
        authenticationMethod: AuthenticationMethodModel? = null,
        isLockscreenEnabled: Boolean = true,
        startsAwake: Boolean = true,
        isDeviceProvisioned: Boolean = true,
        isFrpActive: Boolean = false,
    ): MutableStateFlow<ObservableTransitionState> {
        if (authenticationMethod?.isSecure == true) {
            assert(isLockscreenEnabled) {
                "Lockscreen cannot be disabled while having a secure authentication method"
            }
        }

        check(initialSceneKey != Scenes.Gone || isDeviceUnlocked) {
            "Cannot start on the Gone scene and have the device be locked at the same time."
        }

        sceneContainerFlags.enabled = true
        kosmos.fakeDeviceEntryRepository.setUnlocked(isDeviceUnlocked)
        kosmos.fakeDeviceEntryRepository.setBypassEnabled(isBypassEnabled)
        runCurrent()
        val transitionStateFlow =
            MutableStateFlow<ObservableTransitionState>(
                ObservableTransitionState.Idle(Scenes.Lockscreen)
            )
        sceneInteractor.setTransitionState(transitionStateFlow)
        initialSceneKey?.let {
            transitionStateFlow.value = ObservableTransitionState.Idle(it)
            sceneInteractor.changeScene(it, "reason")
        }
        authenticationMethod?.let {
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(authenticationMethod)
            kosmos.fakeDeviceEntryRepository.setLockscreenEnabled(
                isLockscreenEnabled = isLockscreenEnabled
            )
        }
        if (startsAwake) {
            powerInteractor.setAwakeForTest()
        } else {
            powerInteractor.setAsleepForTest()
        }

        kosmos.fakeDeviceProvisioningRepository.setDeviceProvisioned(isDeviceProvisioned)
        kosmos.fakeDeviceProvisioningRepository.setFactoryResetProtectionActive(isFrpActive)

        runCurrent()

        return transitionStateFlow
    }
}

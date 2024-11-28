/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.systemui.statusbar.phone

import android.app.StatusBarManager
import android.graphics.Insets
import android.os.UserHandle
import android.os.UserManager
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.provider.Settings
import android.testing.TestableLooper
import android.testing.TestableLooper.RunWithLooper
import android.testing.ViewUtils
import android.view.LayoutInflater
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.keyguard.CarrierTextController
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.keyguard.logging.KeyguardLogger
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.battery.BatteryMeterViewController
import com.android.systemui.communal.data.repository.fakeCommunalSceneRepository
import com.android.systemui.communal.domain.interactor.communalSceneInteractor
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.flags.DisableSceneContainer
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.keyguard.ui.viewmodel.glanceableHubToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.lockscreenToGlanceableHubTransitionViewModel
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.statusbar.statusBarStateController
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeViewStateProvider
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.data.repository.StatusBarContentInsetsProviderStore
import com.android.systemui.statusbar.events.SystemStatusAnimationScheduler
import com.android.systemui.statusbar.phone.ui.StatusBarIconController
import com.android.systemui.statusbar.phone.ui.TintedIconManager
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.statusbar.policy.UserInfoController
import com.android.systemui.statusbar.ui.viewmodel.keyguardStatusBarViewModel
import com.android.systemui.statusbar.ui.viewmodel.statusBarUserChipViewModel
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.fakeUserRepository
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@RunWithLooper(setAsMainLooper = true)
class KeyguardStatusBarViewControllerTest : SysuiTestCase() {
    private lateinit var kosmos: Kosmos
    private lateinit var testScope: TestScope

    @Mock private lateinit var carrierTextController: CarrierTextController

    @Mock private lateinit var configurationController: ConfigurationController

    @Mock private lateinit var animationScheduler: SystemStatusAnimationScheduler

    @Mock private lateinit var batteryController: BatteryController

    @Mock private lateinit var userInfoController: UserInfoController

    @Mock private lateinit var statusBarIconController: StatusBarIconController

    @Mock private lateinit var iconManagerFactory: TintedIconManager.Factory

    @Mock private lateinit var iconManager: TintedIconManager

    @Mock private lateinit var batteryMeterViewController: BatteryMeterViewController

    @Mock private lateinit var keyguardStateController: KeyguardStateController

    @Mock private lateinit var keyguardBypassController: KeyguardBypassController

    @Mock private lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor

    @Mock private lateinit var biometricUnlockController: BiometricUnlockController

    @Mock
    private lateinit var statusBarContentInsetsProviderStore: StatusBarContentInsetsProviderStore

    @Mock private lateinit var userManager: UserManager

    @Captor
    private lateinit var configurationListenerCaptor:
        ArgumentCaptor<ConfigurationController.ConfigurationListener>

    @Captor
    private lateinit var keyguardCallbackCaptor: ArgumentCaptor<KeyguardUpdateMonitorCallback>

    @Mock private lateinit var secureSettings: SecureSettings

    @Mock private lateinit var commandQueue: CommandQueue

    @Mock private lateinit var logger: KeyguardLogger

    @Mock private lateinit var statusOverlayHoverListenerFactory: StatusOverlayHoverListenerFactory

    private lateinit var shadeViewStateProvider: TestShadeViewStateProvider

    private lateinit var keyguardStatusBarView: KeyguardStatusBarView
    private lateinit var controller: KeyguardStatusBarViewController
    private val fakeExecutor = FakeExecutor(FakeSystemClock())
    private val backgroundExecutor = FakeExecutor(FakeSystemClock())

    private lateinit var looper: TestableLooper

    @Before
    @Throws(Exception::class)
    fun setup() {
        looper = TestableLooper.get(this)
        kosmos = testKosmos()
        testScope = kosmos.testScope
        shadeViewStateProvider = TestShadeViewStateProvider()

        Mockito.`when`(
                kosmos.statusBarContentInsetsProvider.getStatusBarContentInsetsForCurrentRotation()
            )
            .thenReturn(Insets.of(0, 0, 0, 0))

        MockitoAnnotations.initMocks(this)

        Mockito.`when`(iconManagerFactory.create(ArgumentMatchers.any(), ArgumentMatchers.any()))
            .thenReturn(iconManager)
        Mockito.`when`(statusBarContentInsetsProviderStore.defaultDisplay)
            .thenReturn(kosmos.statusBarContentInsetsProvider)
        allowTestableLooperAsMainThread()
        looper.runWithLooper {
            keyguardStatusBarView =
                Mockito.spy(
                    LayoutInflater.from(mContext).inflate(R.layout.keyguard_status_bar, null)
                        as KeyguardStatusBarView
                )
            Mockito.`when`(keyguardStatusBarView.getDisplay()).thenReturn(mContext.display)
        }

        controller = createController()
    }

    private fun createController(): KeyguardStatusBarViewController {
        return KeyguardStatusBarViewController(
            kosmos.testDispatcher,
            keyguardStatusBarView,
            carrierTextController,
            configurationController,
            animationScheduler,
            batteryController,
            userInfoController,
            statusBarIconController,
            iconManagerFactory,
            batteryMeterViewController,
            shadeViewStateProvider,
            keyguardStateController,
            keyguardBypassController,
            keyguardUpdateMonitor,
            kosmos.keyguardStatusBarViewModel,
            biometricUnlockController,
            kosmos.statusBarStateController,
            statusBarContentInsetsProviderStore,
            userManager,
            kosmos.statusBarUserChipViewModel,
            secureSettings,
            commandQueue,
            fakeExecutor,
            backgroundExecutor,
            logger,
            statusOverlayHoverListenerFactory,
            kosmos.communalSceneInteractor,
            kosmos.glanceableHubToLockscreenTransitionViewModel,
            kosmos.lockscreenToGlanceableHubTransitionViewModel,
        )
    }

    @Test
    @EnableFlags(Flags.FLAG_UPDATE_USER_SWITCHER_BACKGROUND)
    fun onViewAttached_updateUserSwitcherFlagEnabled_callbacksRegistered() {
        controller.onViewAttached()

        runAllScheduled()
        Mockito.verify(configurationController).addCallback(ArgumentMatchers.any())
        Mockito.verify(animationScheduler).addCallback(ArgumentMatchers.any())
        Mockito.verify(userInfoController).addCallback(ArgumentMatchers.any())
        Mockito.verify(commandQueue).addCallback(ArgumentMatchers.any())
        Mockito.verify(statusBarIconController).addIconGroup(ArgumentMatchers.any())
        Mockito.verify(userManager).isUserSwitcherEnabled(ArgumentMatchers.anyBoolean())
    }

    @Test
    @DisableFlags(Flags.FLAG_UPDATE_USER_SWITCHER_BACKGROUND)
    fun onViewAttached_updateUserSwitcherFlagDisabled_callbacksRegistered() {
        controller.onViewAttached()

        Mockito.verify(configurationController).addCallback(ArgumentMatchers.any())
        Mockito.verify(animationScheduler).addCallback(ArgumentMatchers.any())
        Mockito.verify(userInfoController).addCallback(ArgumentMatchers.any())
        Mockito.verify(commandQueue).addCallback(ArgumentMatchers.any())
        Mockito.verify(statusBarIconController).addIconGroup(ArgumentMatchers.any())
        Mockito.verify(userManager).isUserSwitcherEnabled(ArgumentMatchers.anyBoolean())
    }

    @Test
    @EnableFlags(Flags.FLAG_UPDATE_USER_SWITCHER_BACKGROUND)
    fun onConfigurationChanged_updateUserSwitcherFlagEnabled_updatesUserSwitcherVisibility() {
        controller.onViewAttached()
        runAllScheduled()
        Mockito.verify(configurationController).addCallback(configurationListenerCaptor.capture())
        Mockito.clearInvocations(userManager)
        Mockito.clearInvocations(keyguardStatusBarView)

        configurationListenerCaptor.value.onConfigChanged(null)

        runAllScheduled()
        Mockito.verify(userManager).isUserSwitcherEnabled(ArgumentMatchers.anyBoolean())
        Mockito.verify(keyguardStatusBarView).setUserSwitcherEnabled(ArgumentMatchers.anyBoolean())
    }

    @Test
    @DisableFlags(Flags.FLAG_UPDATE_USER_SWITCHER_BACKGROUND)
    fun onConfigurationChanged_updateUserSwitcherFlagDisabled_updatesUserSwitcherVisibility() {
        controller.onViewAttached()
        Mockito.verify(configurationController).addCallback(configurationListenerCaptor.capture())
        Mockito.clearInvocations(userManager)
        Mockito.clearInvocations(keyguardStatusBarView)

        configurationListenerCaptor.value.onConfigChanged(null)
        Mockito.verify(userManager).isUserSwitcherEnabled(ArgumentMatchers.anyBoolean())
        Mockito.verify(keyguardStatusBarView).setUserSwitcherEnabled(ArgumentMatchers.anyBoolean())
    }

    @Test
    @EnableFlags(Flags.FLAG_UPDATE_USER_SWITCHER_BACKGROUND)
    fun onKeyguardVisibilityChanged_userSwitcherFlagEnabled_updatesUserSwitcherVisibility() {
        controller.onViewAttached()
        runAllScheduled()
        Mockito.verify(keyguardUpdateMonitor).registerCallback(keyguardCallbackCaptor.capture())
        Mockito.clearInvocations(userManager)
        Mockito.clearInvocations(keyguardStatusBarView)

        keyguardCallbackCaptor.value.onKeyguardVisibilityChanged(true)

        runAllScheduled()
        Mockito.verify(userManager).isUserSwitcherEnabled(ArgumentMatchers.anyBoolean())
        Mockito.verify(keyguardStatusBarView).setUserSwitcherEnabled(ArgumentMatchers.anyBoolean())
    }

    @Test
    @DisableFlags(Flags.FLAG_UPDATE_USER_SWITCHER_BACKGROUND)
    fun onKeyguardVisibilityChanged_userSwitcherFlagDisabled_updatesUserSwitcherVisibility() {
        controller.onViewAttached()
        Mockito.verify(keyguardUpdateMonitor).registerCallback(keyguardCallbackCaptor.capture())
        Mockito.clearInvocations(userManager)
        Mockito.clearInvocations(keyguardStatusBarView)

        keyguardCallbackCaptor.value.onKeyguardVisibilityChanged(true)
        Mockito.verify(userManager).isUserSwitcherEnabled(ArgumentMatchers.anyBoolean())
        Mockito.verify(keyguardStatusBarView).setUserSwitcherEnabled(ArgumentMatchers.anyBoolean())
    }

    @Test
    fun onViewDetached_callbacksUnregistered() {
        // Set everything up first.
        controller.onViewAttached()

        controller.onViewDetached()

        Mockito.verify(configurationController).removeCallback(ArgumentMatchers.any())
        Mockito.verify(animationScheduler).removeCallback(ArgumentMatchers.any())
        Mockito.verify(userInfoController).removeCallback(ArgumentMatchers.any())
        Mockito.verify(commandQueue).removeCallback(ArgumentMatchers.any())
        Mockito.verify(statusBarIconController).removeIconGroup(ArgumentMatchers.any())
    }

    @Test
    @DisableSceneContainer
    fun onViewReAttached_flagOff_iconManagerNotReRegistered() {
        controller.onViewAttached()
        controller.onViewDetached()
        Mockito.reset(statusBarIconController)

        controller.onViewAttached()

        Mockito.verify(statusBarIconController, Mockito.never())
            .addIconGroup(ArgumentMatchers.any())
    }

    @Test
    @EnableSceneContainer
    fun onViewReAttached_flagOn_iconManagerReRegistered() {
        controller.onViewAttached()
        controller.onViewDetached()
        Mockito.reset(statusBarIconController)

        controller.onViewAttached()

        Mockito.verify(statusBarIconController).addIconGroup(ArgumentMatchers.any())
    }

    @Test
    @DisableSceneContainer
    fun setBatteryListening_true_callbackAdded() {
        controller.setBatteryListening(true)

        Mockito.verify(batteryController).addCallback(ArgumentMatchers.any())
    }

    @Test
    @DisableSceneContainer
    fun setBatteryListening_false_callbackRemoved() {
        // First set to true so that we know setting to false is a change in state.
        controller.setBatteryListening(true)

        controller.setBatteryListening(false)

        Mockito.verify(batteryController).removeCallback(ArgumentMatchers.any())
    }

    @Test
    @DisableSceneContainer
    fun setBatteryListening_trueThenTrue_callbackAddedOnce() {
        controller.setBatteryListening(true)
        controller.setBatteryListening(true)

        Mockito.verify(batteryController).addCallback(ArgumentMatchers.any())
    }

    @Test
    @EnableSceneContainer
    fun setBatteryListening_true_flagOn_callbackNotAdded() {
        controller.setBatteryListening(true)

        Mockito.verify(batteryController, Mockito.never()).addCallback(ArgumentMatchers.any())
    }

    @Test
    fun updateTopClipping_viewClippingUpdated() {
        val viewTop = 20
        keyguardStatusBarView.top = viewTop
        val notificationPanelTop = 30

        controller.updateTopClipping(notificationPanelTop)

        Truth.assertThat(keyguardStatusBarView.clipBounds.top)
            .isEqualTo(notificationPanelTop - viewTop)
    }

    @Test
    fun setNotTopClipping_viewClippingUpdatedToZero() {
        // Start out with some amount of top clipping.
        controller.updateTopClipping(50)
        Truth.assertThat(keyguardStatusBarView.clipBounds.top).isGreaterThan(0)

        controller.setNoTopClipping()

        Truth.assertThat(keyguardStatusBarView.clipBounds.top).isEqualTo(0)
    }

    @Test
    @DisableSceneContainer
    fun updateViewState_alphaAndVisibilityGiven_viewUpdated() {
        // Verify the initial values so we know the method triggers changes.
        Truth.assertThat(keyguardStatusBarView.alpha).isEqualTo(1f)
        Truth.assertThat(keyguardStatusBarView.visibility).isEqualTo(View.VISIBLE)

        val newAlpha = 0.5f
        val newVisibility = View.INVISIBLE
        controller.updateViewState(newAlpha, newVisibility)

        Truth.assertThat(keyguardStatusBarView.alpha).isEqualTo(newAlpha)
        Truth.assertThat(keyguardStatusBarView.visibility).isEqualTo(newVisibility)
    }

    @Test
    @DisableSceneContainer
    fun updateViewState_paramVisibleButIsDisabled_viewIsInvisible() {
        controller.onViewAttached()
        setDisableSystemIcons(true)

        controller.updateViewState(1f, View.VISIBLE)

        // Since we're disabled, we stay invisible
        Truth.assertThat(keyguardStatusBarView.visibility).isEqualTo(View.INVISIBLE)
    }

    @Test
    @DisableSceneContainer
    fun updateViewState_notKeyguardState_nothingUpdated() {
        controller.onViewAttached()
        updateStateToNotKeyguard()

        val oldAlpha = keyguardStatusBarView.alpha

        controller.updateViewState()

        Truth.assertThat(keyguardStatusBarView.alpha).isEqualTo(oldAlpha)
    }

    @Test
    @DisableSceneContainer
    fun updateViewState_bypassEnabledAndShouldListenForFace_viewHidden() {
        controller.onViewAttached()
        updateStateToKeyguard()
        Truth.assertThat(keyguardStatusBarView.visibility).isEqualTo(View.VISIBLE)

        Mockito.`when`(keyguardUpdateMonitor.shouldListenForFace()).thenReturn(true)
        Mockito.`when`(keyguardBypassController.bypassEnabled).thenReturn(true)
        onFinishedGoingToSleep()

        controller.updateViewState()

        Truth.assertThat(keyguardStatusBarView.visibility).isEqualTo(View.INVISIBLE)
    }

    @Test
    @DisableSceneContainer
    fun updateViewState_bypassNotEnabled_viewShown() {
        controller.onViewAttached()
        updateStateToKeyguard()

        Mockito.`when`(keyguardUpdateMonitor.shouldListenForFace()).thenReturn(true)
        Mockito.`when`(keyguardBypassController.bypassEnabled).thenReturn(false)
        onFinishedGoingToSleep()

        controller.updateViewState()

        Truth.assertThat(keyguardStatusBarView.visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    @DisableSceneContainer
    fun updateViewState_shouldNotListenForFace_viewShown() {
        controller.onViewAttached()
        updateStateToKeyguard()

        Mockito.`when`(keyguardUpdateMonitor.shouldListenForFace()).thenReturn(false)
        Mockito.`when`(keyguardBypassController.bypassEnabled).thenReturn(true)
        onFinishedGoingToSleep()

        controller.updateViewState()

        Truth.assertThat(keyguardStatusBarView.visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    @DisableSceneContainer
    fun updateViewState_panelExpandedHeightZero_viewHidden() {
        controller.onViewAttached()
        updateStateToKeyguard()

        shadeViewStateProvider.panelViewExpandedHeight = 0f

        controller.updateViewState()

        Truth.assertThat(keyguardStatusBarView.visibility).isEqualTo(View.INVISIBLE)
    }

    @Test
    @DisableSceneContainer
    fun updateViewState_dragProgressOne_viewHidden() {
        controller.onViewAttached()
        updateStateToKeyguard()

        shadeViewStateProvider.lockscreenShadeDragProgress = 1f

        controller.updateViewState()

        Truth.assertThat(keyguardStatusBarView.visibility).isEqualTo(View.INVISIBLE)
    }

    @Test
    @DisableSceneContainer
    fun updateViewState_disableSystemInfoFalse_viewShown() {
        controller.onViewAttached()
        updateStateToKeyguard()
        setDisableSystemInfo(false)

        controller.updateViewState()

        Truth.assertThat(keyguardStatusBarView.visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    @DisableSceneContainer
    fun updateViewState_disableSystemInfoTrue_viewHidden() {
        controller.onViewAttached()
        updateStateToKeyguard()
        setDisableSystemInfo(true)

        controller.updateViewState()

        Truth.assertThat(keyguardStatusBarView.visibility).isEqualTo(View.INVISIBLE)
    }

    @Test
    @DisableSceneContainer
    fun updateViewState_disableSystemIconsFalse_viewShown() {
        controller.onViewAttached()
        updateStateToKeyguard()
        setDisableSystemIcons(false)

        controller.updateViewState()

        Truth.assertThat(keyguardStatusBarView.visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    @DisableSceneContainer
    fun updateViewState_disableSystemIconsTrue_viewHidden() {
        controller.onViewAttached()
        updateStateToKeyguard()
        setDisableSystemIcons(true)

        controller.updateViewState()

        Truth.assertThat(keyguardStatusBarView.visibility).isEqualTo(View.INVISIBLE)
    }

    @Test
    @DisableSceneContainer
    fun updateViewState_dozingTrue_flagOff_viewHidden() {
        controller.init()
        controller.onViewAttached()
        updateStateToKeyguard()

        controller.setDozing(true)
        controller.updateViewState()

        Truth.assertThat(keyguardStatusBarView.visibility).isEqualTo(View.INVISIBLE)
    }

    @Test
    @DisableSceneContainer
    fun updateViewState_dozingFalse_flagOff_viewShown() {
        controller.init()
        controller.onViewAttached()
        updateStateToKeyguard()

        controller.setDozing(false)
        controller.updateViewState()

        Truth.assertThat(keyguardStatusBarView.visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    @EnableSceneContainer
    fun updateViewState_flagOn_doesNothing() {
        controller.init()
        controller.onViewAttached()
        updateStateToKeyguard()

        keyguardStatusBarView.visibility = View.GONE
        keyguardStatusBarView.alpha = 0.456f

        controller.updateViewState()

        Truth.assertThat(keyguardStatusBarView.visibility).isEqualTo(View.GONE)
        Truth.assertThat(keyguardStatusBarView.alpha).isEqualTo(0.456f)
    }

    @Test
    @EnableSceneContainer
    fun updateViewStateWithAlphaAndVis_flagOn_doesNothing() {
        controller.init()
        controller.onViewAttached()
        updateStateToKeyguard()

        keyguardStatusBarView.visibility = View.GONE
        keyguardStatusBarView.alpha = 0.456f

        controller.updateViewState(0.789f, View.VISIBLE)

        Truth.assertThat(keyguardStatusBarView.visibility).isEqualTo(View.GONE)
        Truth.assertThat(keyguardStatusBarView.alpha).isEqualTo(0.456f)
    }

    @Test
    @EnableSceneContainer
    fun setAlpha_flagOn_doesNothing() {
        controller.init()
        controller.onViewAttached()
        updateStateToKeyguard()

        keyguardStatusBarView.alpha = 0.456f

        controller.setAlpha(0.123f)

        Truth.assertThat(keyguardStatusBarView.alpha).isEqualTo(0.456f)
    }

    @Test
    @EnableSceneContainer
    fun setDozing_flagOn_doesNothing() {
        controller.init()
        controller.onViewAttached()
        updateStateToKeyguard()
        Truth.assertThat(keyguardStatusBarView.visibility).isEqualTo(View.VISIBLE)

        controller.setDozing(true)
        controller.updateViewState()

        // setDozing(true) should typically cause the view to hide. But since the flag is on, we
        // should ignore these set dozing calls and stay the same visibility.
        Truth.assertThat(keyguardStatusBarView.visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    @DisableSceneContainer
    fun setAlpha_explicitAlpha_setsExplicitAlpha() {
        controller.onViewAttached()
        updateStateToKeyguard()

        controller.setAlpha(0.5f)

        Truth.assertThat(keyguardStatusBarView.alpha).isEqualTo(0.5f)
    }

    @Test
    @DisableSceneContainer
    fun setAlpha_explicitAlpha_thenMinusOneAlpha_setsAlphaBasedOnDefaultCriteria() {
        controller.onViewAttached()
        updateStateToKeyguard()

        controller.setAlpha(0.5f)
        controller.setAlpha(-1f)

        Truth.assertThat(keyguardStatusBarView.alpha).isGreaterThan(0)
        Truth.assertThat(keyguardStatusBarView.alpha).isNotEqualTo(0.5f)
    }

    // TODO(b/195442899): Add more tests for #updateViewState once CLs are finalized.
    @Test
    @DisableSceneContainer
    fun updateForHeadsUp_headsUpShouldBeVisible_viewHidden() {
        controller.onViewAttached()
        updateStateToKeyguard()
        keyguardStatusBarView.visibility = View.VISIBLE

        shadeViewStateProvider.setShouldHeadsUpBeVisible(true)
        controller.updateForHeadsUp(/* animate= */ false)

        Truth.assertThat(keyguardStatusBarView.visibility).isEqualTo(View.INVISIBLE)
    }

    @Test
    @DisableSceneContainer
    fun updateForHeadsUp_headsUpShouldNotBeVisible_viewShown() {
        controller.onViewAttached()
        updateStateToKeyguard()

        // Start with the opposite state.
        shadeViewStateProvider.setShouldHeadsUpBeVisible(true)
        controller.updateForHeadsUp(/* animate= */ false)

        shadeViewStateProvider.setShouldHeadsUpBeVisible(false)
        controller.updateForHeadsUp(/* animate= */ false)

        Truth.assertThat(keyguardStatusBarView.visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun testNewUserSwitcherDisablesAvatar_newUiOn() =
        testScope.runTest {
            // GIVEN the status bar user switcher chip is enabled
            kosmos.fakeUserRepository.isStatusBarUserChipEnabled = true

            // WHEN the controller is created
            controller = createController()

            // THEN keyguard status bar view avatar is disabled
            Truth.assertThat(keyguardStatusBarView.isKeyguardUserAvatarEnabled).isFalse()
        }

    @Test
    fun testNewUserSwitcherDisablesAvatar_newUiOff() {
        // GIVEN the status bar user switcher chip is disabled
        kosmos.fakeUserRepository.isStatusBarUserChipEnabled = false

        // WHEN the controller is created
        controller = createController()

        // THEN keyguard status bar view avatar is enabled
        Truth.assertThat(keyguardStatusBarView.isKeyguardUserAvatarEnabled).isTrue()
    }

    @Test
    fun testBlockedIcons_obeysSettingForVibrateIcon_settingOff() {
        val str = mContext.getString(com.android.internal.R.string.status_bar_volume)

        // GIVEN the setting is off
        Mockito.`when`(secureSettings.getInt(Settings.Secure.STATUS_BAR_SHOW_VIBRATE_ICON, 0))
            .thenReturn(0)

        // WHEN CollapsedStatusBarFragment builds the blocklist
        controller.updateBlockedIcons()

        // THEN status_bar_volume SHOULD be present in the list
        val contains = controller.blockedIcons.contains(str)
        Assert.assertTrue(contains)
    }

    @Test
    fun testBlockedIcons_obeysSettingForVibrateIcon_settingOn() {
        val str = mContext.getString(com.android.internal.R.string.status_bar_volume)

        // GIVEN the setting is ON
        Mockito.`when`(
                secureSettings.getIntForUser(
                    Settings.Secure.STATUS_BAR_SHOW_VIBRATE_ICON,
                    0,
                    UserHandle.USER_CURRENT,
                )
            )
            .thenReturn(1)

        // WHEN CollapsedStatusBarFragment builds the blocklist
        controller.updateBlockedIcons()

        // THEN status_bar_volume SHOULD NOT be present in the list
        val contains = controller.blockedIcons.contains(str)
        Assert.assertFalse(contains)
    }

    private fun updateStateToNotKeyguard() {
        updateStatusBarState(StatusBarState.SHADE)
    }

    private fun updateStateToKeyguard() {
        updateStatusBarState(StatusBarState.KEYGUARD)
    }

    private fun updateStatusBarState(state: Int) {
        kosmos.statusBarStateController.setState(state)
    }

    @Test
    @DisableSceneContainer
    fun animateKeyguardStatusBarIn_isDisabled_viewStillHidden() {
        controller.onViewAttached()
        updateStateToKeyguard()
        setDisableSystemInfo(true)
        Truth.assertThat(keyguardStatusBarView.visibility).isEqualTo(View.INVISIBLE)

        controller.animateKeyguardStatusBarIn()

        // Since we're disabled, we don't actually animate in and stay invisible
        Truth.assertThat(keyguardStatusBarView.visibility).isEqualTo(View.INVISIBLE)
    }

    @Test
    fun animateToGlanceableHub_affectsAlpha() =
        testScope.runTest {
            controller.init()
            val transitionAlphaAmount = .5f
            ViewUtils.attachView(keyguardStatusBarView)
            looper.processAllMessages()
            updateStateToKeyguard()
            kosmos.fakeCommunalSceneRepository.snapToScene(CommunalScenes.Communal)
            runCurrent()
            controller.updateCommunalAlphaTransition(transitionAlphaAmount)
            Truth.assertThat(keyguardStatusBarView.getAlpha()).isEqualTo(transitionAlphaAmount)
        }

    @Test
    fun animateToGlanceableHub_alphaResetOnCommunalNotShowing() =
        testScope.runTest {
            controller.init()
            val transitionAlphaAmount = .5f
            ViewUtils.attachView(keyguardStatusBarView)
            looper.processAllMessages()
            updateStateToKeyguard()
            kosmos.fakeCommunalSceneRepository.snapToScene(CommunalScenes.Communal)
            runCurrent()
            controller.updateCommunalAlphaTransition(transitionAlphaAmount)
            kosmos.fakeCommunalSceneRepository.snapToScene(CommunalScenes.Blank)
            runCurrent()
            Truth.assertThat(keyguardStatusBarView.getAlpha()).isNotEqualTo(transitionAlphaAmount)
        }

    /**
     * Calls [com.android.keyguard.KeyguardUpdateMonitorCallback.onFinishedGoingToSleep] to ensure
     * values are updated properly.
     */
    private fun onFinishedGoingToSleep() {
        val keyguardUpdateCallbackCaptor =
            ArgumentCaptor.forClass(KeyguardUpdateMonitorCallback::class.java)
        Mockito.verify(keyguardUpdateMonitor)
            .registerCallback(keyguardUpdateCallbackCaptor.capture())
        val callback = keyguardUpdateCallbackCaptor.value

        callback.onFinishedGoingToSleep(0)
    }

    private fun setDisableSystemInfo(disabled: Boolean) {
        val callback = commandQueueCallback
        val disabled1 = if (disabled) StatusBarManager.DISABLE_SYSTEM_INFO else 0
        callback.disable(mContext.displayId, disabled1, 0, false)
    }

    private fun setDisableSystemIcons(disabled: Boolean) {
        val callback = commandQueueCallback
        val disabled2 = if (disabled) StatusBarManager.DISABLE2_SYSTEM_ICONS else 0
        callback.disable(mContext.displayId, 0, disabled2, false)
    }

    private val commandQueueCallback: CommandQueue.Callbacks
        get() {
            val captor = ArgumentCaptor.forClass(CommandQueue.Callbacks::class.java)
            Mockito.verify(commandQueue).addCallback(captor.capture())
            return captor.value
        }

    private fun runAllScheduled() {
        backgroundExecutor.runAllReady()
        fakeExecutor.runAllReady()
    }

    private class TestShadeViewStateProvider : ShadeViewStateProvider {
        override var panelViewExpandedHeight: Float = 100f
        private var mShouldHeadsUpBeVisible = false
        override var lockscreenShadeDragProgress: Float = 0f

        override fun shouldHeadsUpBeVisible(): Boolean {
            return mShouldHeadsUpBeVisible
        }

        fun setShouldHeadsUpBeVisible(shouldHeadsUpBeVisible: Boolean) {
            this.mShouldHeadsUpBeVisible = shouldHeadsUpBeVisible
        }
    }
}

/*
 * Copyright (C) 2022 The Android Open Source Project
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
 *
 */

package com.android.systemui.keyguard.domain.interactor

import android.app.admin.DevicePolicyManager
import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.widget.LockPatternUtils
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.dock.DockManager
import com.android.systemui.dock.DockManagerFake
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.keyguard.data.quickaffordance.BuiltInKeyguardQuickAffordanceKeys
import com.android.systemui.keyguard.data.quickaffordance.FakeKeyguardQuickAffordanceConfig
import com.android.systemui.keyguard.data.quickaffordance.FakeKeyguardQuickAffordanceProviderClientFactory
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceConfig
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceLegacySettingSyncer
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceLocalUserSelectionManager
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceRemoteUserSelectionManager
import com.android.systemui.keyguard.data.repository.FakeBiometricSettingsRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.KeyguardQuickAffordanceRepository
import com.android.systemui.keyguard.domain.model.KeyguardQuickAffordanceModel
import com.android.systemui.keyguard.shared.model.KeyguardQuickAffordancePickerRepresentation
import com.android.systemui.keyguard.shared.quickaffordance.ActivationState
import com.android.systemui.keyguard.shared.quickaffordance.KeyguardQuickAffordancePosition
import com.android.systemui.keyguard.shared.quickaffordance.KeyguardQuickAffordancesMetricsLogger
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.settings.UserFileManager
import com.android.systemui.settings.UserTracker
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shared.keyguard.shared.model.KeyguardQuickAffordanceSlots
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.testKosmos
import com.android.systemui.util.FakeSharedPreferences
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.settings.FakeSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyguardQuickAffordanceInteractorTest : SysuiTestCase() {

    @Mock private lateinit var lockPatternUtils: LockPatternUtils
    @Mock private lateinit var keyguardStateController: KeyguardStateController
    @Mock private lateinit var userTracker: UserTracker
    @Mock private lateinit var activityStarter: ActivityStarter
    @Mock private lateinit var launchAnimator: DialogTransitionAnimator
    @Mock private lateinit var devicePolicyManager: DevicePolicyManager
    @Mock private lateinit var shadeInteractor: ShadeInteractor
    @Mock private lateinit var logger: KeyguardQuickAffordancesMetricsLogger

    private val kosmos = testKosmos()

    private lateinit var underTest: KeyguardQuickAffordanceInteractor

    private val testScope = kosmos.testScope
    private lateinit var repository: FakeKeyguardRepository
    private lateinit var homeControls: FakeKeyguardQuickAffordanceConfig
    private lateinit var quickAccessWallet: FakeKeyguardQuickAffordanceConfig
    private lateinit var qrCodeScanner: FakeKeyguardQuickAffordanceConfig
    private lateinit var featureFlags: FakeFeatureFlags
    private lateinit var dockManager: DockManagerFake
    private lateinit var biometricSettingsRepository: FakeBiometricSettingsRepository

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        overrideResource(R.bool.custom_lockscreen_shortcuts_enabled, true)
        overrideResource(
            R.array.config_keyguardQuickAffordanceDefaults,
            arrayOf(
                KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START +
                    ":" +
                    BuiltInKeyguardQuickAffordanceKeys.HOME_CONTROLS,
                KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_END +
                    ":" +
                    BuiltInKeyguardQuickAffordanceKeys.QUICK_ACCESS_WALLET
            )
        )

        repository = FakeKeyguardRepository()
        repository.setKeyguardShowing(true)

        homeControls =
            FakeKeyguardQuickAffordanceConfig(BuiltInKeyguardQuickAffordanceKeys.HOME_CONTROLS)
        quickAccessWallet =
            FakeKeyguardQuickAffordanceConfig(
                BuiltInKeyguardQuickAffordanceKeys.QUICK_ACCESS_WALLET
            )
        qrCodeScanner =
            FakeKeyguardQuickAffordanceConfig(BuiltInKeyguardQuickAffordanceKeys.QR_CODE_SCANNER)

        dockManager = DockManagerFake()
        biometricSettingsRepository = FakeBiometricSettingsRepository()

        val localUserSelectionManager =
            KeyguardQuickAffordanceLocalUserSelectionManager(
                context = context,
                userFileManager =
                    mock<UserFileManager>().apply {
                        whenever(
                                getSharedPreferences(
                                    anyString(),
                                    anyInt(),
                                    anyInt(),
                                )
                            )
                            .thenReturn(FakeSharedPreferences())
                    },
                userTracker = userTracker,
                systemSettings = FakeSettings(),
                broadcastDispatcher = fakeBroadcastDispatcher,
            )
        val remoteUserSelectionManager =
            KeyguardQuickAffordanceRemoteUserSelectionManager(
                scope = testScope.backgroundScope,
                userTracker = userTracker,
                clientFactory = FakeKeyguardQuickAffordanceProviderClientFactory(userTracker),
                userHandle = UserHandle.SYSTEM,
            )
        val quickAffordanceRepository =
            KeyguardQuickAffordanceRepository(
                appContext = context,
                scope = testScope.backgroundScope,
                localUserSelectionManager = localUserSelectionManager,
                remoteUserSelectionManager = remoteUserSelectionManager,
                userTracker = userTracker,
                legacySettingSyncer =
                    KeyguardQuickAffordanceLegacySettingSyncer(
                        scope = testScope.backgroundScope,
                        backgroundDispatcher = kosmos.testDispatcher,
                        secureSettings = FakeSettings(),
                        selectionsManager = localUserSelectionManager,
                    ),
                configs = setOf(homeControls, quickAccessWallet, qrCodeScanner),
                dumpManager = mock(),
                userHandle = UserHandle.SYSTEM,
            )
        featureFlags = FakeFeatureFlags()

        val withDeps =
            KeyguardInteractorFactory.create(
                featureFlags = featureFlags,
                repository = repository,
            )
        underTest =
            KeyguardQuickAffordanceInteractor(
                keyguardInteractor = withDeps.keyguardInteractor,
                shadeInteractor = shadeInteractor,
                lockPatternUtils = lockPatternUtils,
                keyguardStateController = keyguardStateController,
                userTracker = userTracker,
                activityStarter = activityStarter,
                featureFlags = featureFlags,
                repository = { quickAffordanceRepository },
                launchAnimator = launchAnimator,
                logger = logger,
                devicePolicyManager = devicePolicyManager,
                dockManager = dockManager,
                biometricSettingsRepository = biometricSettingsRepository,
                backgroundDispatcher = kosmos.testDispatcher,
                appContext = context,
                sceneInteractor = { kosmos.sceneInteractor },
            )

        whenever(shadeInteractor.anyExpansion).thenReturn(MutableStateFlow(0f))
    }

    @Test
    fun quickAffordance_bottomStartAffordanceIsVisible() =
        testScope.runTest {
            val configKey = BuiltInKeyguardQuickAffordanceKeys.HOME_CONTROLS
            homeControls.setState(
                KeyguardQuickAffordanceConfig.LockScreenState.Visible(
                    icon = ICON,
                    activationState = ActivationState.Active,
                )
            )

            val collectedValue =
                collectLastValue(
                    underTest.quickAffordance(KeyguardQuickAffordancePosition.BOTTOM_START)
                )

            assertThat(collectedValue())
                .isInstanceOf(KeyguardQuickAffordanceModel.Visible::class.java)
            val visibleModel = collectedValue() as KeyguardQuickAffordanceModel.Visible
            assertThat(visibleModel.configKey)
                .isEqualTo("${KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START}::$configKey")
            assertThat(visibleModel.icon).isEqualTo(ICON)
            assertThat(visibleModel.icon.contentDescription)
                .isEqualTo(ContentDescription.Resource(res = CONTENT_DESCRIPTION_RESOURCE_ID))
            assertThat(visibleModel.activationState).isEqualTo(ActivationState.Active)
        }

    @Test
    fun quickAffordance_bottomEndAffordanceIsVisible() =
        testScope.runTest {
            val configKey = BuiltInKeyguardQuickAffordanceKeys.QUICK_ACCESS_WALLET
            quickAccessWallet.setState(
                KeyguardQuickAffordanceConfig.LockScreenState.Visible(
                    icon = ICON,
                )
            )

            val collectedValue =
                collectLastValue(
                    underTest.quickAffordance(KeyguardQuickAffordancePosition.BOTTOM_END)
                )

            assertThat(collectedValue())
                .isInstanceOf(KeyguardQuickAffordanceModel.Visible::class.java)
            val visibleModel = collectedValue() as KeyguardQuickAffordanceModel.Visible
            assertThat(visibleModel.configKey)
                .isEqualTo("${KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_END}::$configKey")
            assertThat(visibleModel.icon).isEqualTo(ICON)
            assertThat(visibleModel.icon.contentDescription)
                .isEqualTo(ContentDescription.Resource(res = CONTENT_DESCRIPTION_RESOURCE_ID))
            assertThat(visibleModel.activationState).isEqualTo(ActivationState.NotSupported)
        }

    @Test
    fun quickAffordance_hiddenWhenAllFeaturesAreDisabledByDevicePolicy() =
        testScope.runTest {
            whenever(devicePolicyManager.getKeyguardDisabledFeatures(null, userTracker.userId))
                .thenReturn(DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_ALL)
            quickAccessWallet.setState(
                KeyguardQuickAffordanceConfig.LockScreenState.Visible(
                    icon = ICON,
                )
            )

            val collectedValue by
                collectLastValue(
                    underTest.quickAffordance(KeyguardQuickAffordancePosition.BOTTOM_END)
                )

            assertThat(collectedValue).isInstanceOf(KeyguardQuickAffordanceModel.Hidden::class.java)
        }

    @Test
    fun quickAffordance_hiddenWhenShortcutsFeatureIsDisabledByDevicePolicy() =
        testScope.runTest {
            whenever(devicePolicyManager.getKeyguardDisabledFeatures(null, userTracker.userId))
                .thenReturn(DevicePolicyManager.KEYGUARD_DISABLE_SHORTCUTS_ALL)
            quickAccessWallet.setState(
                KeyguardQuickAffordanceConfig.LockScreenState.Visible(
                    icon = ICON,
                )
            )

            val collectedValue by
                collectLastValue(
                    underTest.quickAffordance(KeyguardQuickAffordancePosition.BOTTOM_END)
                )

            assertThat(collectedValue).isInstanceOf(KeyguardQuickAffordanceModel.Hidden::class.java)
        }

    @Test
    fun quickAffordance_hiddenWhenUserIsInLockdownMode() =
        testScope.runTest {
            biometricSettingsRepository.setIsUserInLockdown(true)
            quickAccessWallet.setState(
                KeyguardQuickAffordanceConfig.LockScreenState.Visible(
                    icon = ICON,
                )
            )

            val collectedValue by
                collectLastValue(
                    underTest.quickAffordance(KeyguardQuickAffordancePosition.BOTTOM_END)
                )

            assertThat(collectedValue).isEqualTo(KeyguardQuickAffordanceModel.Hidden)
        }

    @Test
    fun quickAffordance_bottomStartAffordanceHiddenWhileDozing() =
        testScope.runTest {
            repository.setIsDozing(true)
            homeControls.setState(
                KeyguardQuickAffordanceConfig.LockScreenState.Visible(
                    icon = ICON,
                )
            )

            val collectedValue =
                collectLastValue(
                    underTest.quickAffordance(KeyguardQuickAffordancePosition.BOTTOM_START)
                )
            assertThat(collectedValue()).isEqualTo(KeyguardQuickAffordanceModel.Hidden)
        }

    @Test
    fun quickAffordance_bottomStartAffordanceHiddenWhenLockscreenIsNotShowing() =
        testScope.runTest {
            repository.setKeyguardShowing(false)
            homeControls.setState(
                KeyguardQuickAffordanceConfig.LockScreenState.Visible(
                    icon = ICON,
                )
            )

            val collectedValue =
                collectLastValue(
                    underTest.quickAffordance(KeyguardQuickAffordancePosition.BOTTOM_START)
                )
            assertThat(collectedValue()).isEqualTo(KeyguardQuickAffordanceModel.Hidden)
        }

    @Test
    fun quickAffordance_doNotSendUpdatesWhileShadeExpandingAndStillHidden() =
        testScope.runTest {
            val shadeExpansion = MutableStateFlow(0f)
            whenever(shadeInteractor.anyExpansion).thenReturn(shadeExpansion)

            val collectedValue by
                collectValues(
                    underTest.quickAffordance(KeyguardQuickAffordancePosition.BOTTOM_START)
                )

            val initialSize = collectedValue.size
            for (i in 0..10) {
                shadeExpansion.value = i / 10f
            }

            assertThat(collectedValue[0])
                .isInstanceOf(KeyguardQuickAffordanceModel.Hidden::class.java)
            assertThat(collectedValue.size).isEqualTo(initialSize)
        }

    @Test
    fun quickAffordanceAlwaysVisible_notVisible_restrictedByPolicyManager() =
        testScope.runTest {
            whenever(devicePolicyManager.getKeyguardDisabledFeatures(null, userTracker.userId))
                .thenReturn(DevicePolicyManager.KEYGUARD_DISABLE_SHORTCUTS_ALL)

            repository.setKeyguardShowing(false)
            repository.setIsDozing(true)
            homeControls.setState(
                KeyguardQuickAffordanceConfig.LockScreenState.Visible(
                    icon = ICON,
                    activationState = ActivationState.Active,
                )
            )

            val collectedValue by
                collectLastValue(
                    underTest.quickAffordanceAlwaysVisible(
                        KeyguardQuickAffordancePosition.BOTTOM_START
                    )
                )

            assertThat(collectedValue).isInstanceOf(KeyguardQuickAffordanceModel.Hidden::class.java)
        }

    @Test
    fun quickAffordanceAlwaysVisible_evenWhenLockScreenNotShowingAndDozing() =
        testScope.runTest {
            repository.setKeyguardShowing(false)
            repository.setIsDozing(true)
            val configKey = BuiltInKeyguardQuickAffordanceKeys.HOME_CONTROLS
            homeControls.setState(
                KeyguardQuickAffordanceConfig.LockScreenState.Visible(
                    icon = ICON,
                    activationState = ActivationState.Active,
                )
            )

            val collectedValue =
                collectLastValue(
                    underTest.quickAffordanceAlwaysVisible(
                        KeyguardQuickAffordancePosition.BOTTOM_START
                    )
                )
            assertThat(collectedValue())
                .isInstanceOf(KeyguardQuickAffordanceModel.Visible::class.java)
            val visibleModel = collectedValue() as KeyguardQuickAffordanceModel.Visible
            assertThat(visibleModel.configKey)
                .isEqualTo("${KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START}::$configKey")
            assertThat(visibleModel.icon).isEqualTo(ICON)
            assertThat(visibleModel.icon.contentDescription)
                .isEqualTo(ContentDescription.Resource(res = CONTENT_DESCRIPTION_RESOURCE_ID))
            assertThat(visibleModel.activationState).isEqualTo(ActivationState.Active)
        }

    @Test
    fun select() =
        testScope.runTest {
            overrideResource(
                R.array.config_keyguardQuickAffordanceDefaults,
                arrayOf<String>(),
            )
            homeControls.setState(
                KeyguardQuickAffordanceConfig.LockScreenState.Visible(icon = ICON)
            )
            quickAccessWallet.setState(
                KeyguardQuickAffordanceConfig.LockScreenState.Visible(icon = ICON)
            )
            qrCodeScanner.setState(
                KeyguardQuickAffordanceConfig.LockScreenState.Visible(icon = ICON)
            )

            assertThat(underTest.getSelections())
                .isEqualTo(
                    mapOf<String, List<String>>(
                        KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START to emptyList(),
                        KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_END to emptyList(),
                    )
                )

            val startConfig =
                collectLastValue(
                    underTest.quickAffordance(KeyguardQuickAffordancePosition.BOTTOM_START)
                )
            val endConfig =
                collectLastValue(
                    underTest.quickAffordance(KeyguardQuickAffordancePosition.BOTTOM_END)
                )

            underTest.select(KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START, homeControls.key)

            assertThat(startConfig())
                .isEqualTo(
                    KeyguardQuickAffordanceModel.Visible(
                        configKey =
                            KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START +
                                "::${homeControls.key}",
                        icon = ICON,
                        activationState = ActivationState.NotSupported,
                    )
                )
            assertThat(endConfig())
                .isEqualTo(
                    KeyguardQuickAffordanceModel.Hidden,
                )
            assertThat(underTest.getSelections())
                .isEqualTo(
                    mapOf(
                        KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START to
                            listOf(
                                KeyguardQuickAffordancePickerRepresentation(
                                    id = homeControls.key,
                                    name = homeControls.pickerName(),
                                    iconResourceId = homeControls.pickerIconResourceId,
                                ),
                            ),
                        KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_END to emptyList(),
                    )
                )

            underTest.select(
                KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START,
                quickAccessWallet.key
            )

            assertThat(startConfig())
                .isEqualTo(
                    KeyguardQuickAffordanceModel.Visible(
                        configKey =
                            KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START +
                                "::${quickAccessWallet.key}",
                        icon = ICON,
                        activationState = ActivationState.NotSupported,
                    )
                )
            assertThat(endConfig())
                .isEqualTo(
                    KeyguardQuickAffordanceModel.Hidden,
                )
            assertThat(underTest.getSelections())
                .isEqualTo(
                    mapOf(
                        KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START to
                            listOf(
                                KeyguardQuickAffordancePickerRepresentation(
                                    id = quickAccessWallet.key,
                                    name = quickAccessWallet.pickerName(),
                                    iconResourceId = quickAccessWallet.pickerIconResourceId,
                                ),
                            ),
                        KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_END to emptyList(),
                    )
                )

            underTest.select(KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_END, qrCodeScanner.key)

            assertThat(startConfig())
                .isEqualTo(
                    KeyguardQuickAffordanceModel.Visible(
                        configKey =
                            KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START +
                                "::${quickAccessWallet.key}",
                        icon = ICON,
                        activationState = ActivationState.NotSupported,
                    )
                )
            assertThat(endConfig())
                .isEqualTo(
                    KeyguardQuickAffordanceModel.Visible(
                        configKey =
                            KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_END +
                                "::${qrCodeScanner.key}",
                        icon = ICON,
                        activationState = ActivationState.NotSupported,
                    )
                )
            assertThat(underTest.getSelections())
                .isEqualTo(
                    mapOf(
                        KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START to
                            listOf(
                                KeyguardQuickAffordancePickerRepresentation(
                                    id = quickAccessWallet.key,
                                    name = quickAccessWallet.pickerName(),
                                    iconResourceId = quickAccessWallet.pickerIconResourceId,
                                ),
                            ),
                        KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_END to
                            listOf(
                                KeyguardQuickAffordancePickerRepresentation(
                                    id = qrCodeScanner.key,
                                    name = qrCodeScanner.pickerName(),
                                    iconResourceId = qrCodeScanner.pickerIconResourceId,
                                ),
                            ),
                    )
                )
        }

    @Test
    fun unselect_one() =
        testScope.runTest {
            homeControls.setState(
                KeyguardQuickAffordanceConfig.LockScreenState.Visible(icon = ICON)
            )
            quickAccessWallet.setState(
                KeyguardQuickAffordanceConfig.LockScreenState.Visible(icon = ICON)
            )
            qrCodeScanner.setState(
                KeyguardQuickAffordanceConfig.LockScreenState.Visible(icon = ICON)
            )

            val startConfig =
                collectLastValue(
                    underTest.quickAffordance(KeyguardQuickAffordancePosition.BOTTOM_START)
                )
            val endConfig =
                collectLastValue(
                    underTest.quickAffordance(KeyguardQuickAffordancePosition.BOTTOM_END)
                )
            underTest.select(KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START, homeControls.key)
            underTest.select(KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_END, quickAccessWallet.key)
            underTest.unselect(KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START, homeControls.key)

            assertThat(startConfig())
                .isEqualTo(
                    KeyguardQuickAffordanceModel.Hidden,
                )
            assertThat(endConfig())
                .isEqualTo(
                    KeyguardQuickAffordanceModel.Visible(
                        configKey =
                            KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_END +
                                "::${quickAccessWallet.key}",
                        icon = ICON,
                        activationState = ActivationState.NotSupported,
                    )
                )
            assertThat(underTest.getSelections())
                .isEqualTo(
                    mapOf(
                        KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START to emptyList(),
                        KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_END to
                            listOf(
                                KeyguardQuickAffordancePickerRepresentation(
                                    id = quickAccessWallet.key,
                                    name = quickAccessWallet.pickerName(),
                                    iconResourceId = quickAccessWallet.pickerIconResourceId,
                                ),
                            ),
                    )
                )

            underTest.unselect(
                KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_END,
                quickAccessWallet.key
            )

            assertThat(startConfig())
                .isEqualTo(
                    KeyguardQuickAffordanceModel.Hidden,
                )
            assertThat(endConfig())
                .isEqualTo(
                    KeyguardQuickAffordanceModel.Hidden,
                )
            assertThat(underTest.getSelections())
                .isEqualTo(
                    mapOf<String, List<String>>(
                        KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START to emptyList(),
                        KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_END to emptyList(),
                    )
                )
        }

    @Test
    fun useLongPress_whenDocked_isFalse() =
        testScope.runTest {
            dockManager.setIsDocked(true)

            val useLongPress by collectLastValue(underTest.useLongPress())

            assertThat(useLongPress).isFalse()
        }

    @Test
    fun useLongPress_whenNotDocked_isTrue() =
        testScope.runTest {
            dockManager.setIsDocked(false)

            val useLongPress by collectLastValue(underTest.useLongPress())

            assertThat(useLongPress).isTrue()
        }

    @Test
    fun useLongPress_whenNotDocked_isTrue_changedTo_whenDocked_isFalse() =
        testScope.runTest {
            dockManager.setIsDocked(false)
            val firstUseLongPress by collectLastValue(underTest.useLongPress())
            runCurrent()

            assertThat(firstUseLongPress).isTrue()

            dockManager.setIsDocked(true)
            dockManager.setDockEvent(DockManager.STATE_DOCKED)
            val secondUseLongPress by collectLastValue(underTest.useLongPress())
            runCurrent()

            assertThat(secondUseLongPress).isFalse()
        }

    @Test
    fun unselect_all() =
        testScope.runTest {
            homeControls.setState(
                KeyguardQuickAffordanceConfig.LockScreenState.Visible(icon = ICON)
            )
            quickAccessWallet.setState(
                KeyguardQuickAffordanceConfig.LockScreenState.Visible(icon = ICON)
            )
            qrCodeScanner.setState(
                KeyguardQuickAffordanceConfig.LockScreenState.Visible(icon = ICON)
            )

            underTest.select(KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START, homeControls.key)
            underTest.select(KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_END, quickAccessWallet.key)
            underTest.unselect(KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START, null)

            assertThat(underTest.getSelections())
                .isEqualTo(
                    mapOf(
                        KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START to emptyList(),
                        KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_END to
                            listOf(
                                KeyguardQuickAffordancePickerRepresentation(
                                    id = quickAccessWallet.key,
                                    name = quickAccessWallet.pickerName(),
                                    iconResourceId = quickAccessWallet.pickerIconResourceId,
                                ),
                            ),
                    )
                )

            underTest.unselect(
                KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_END,
                null,
            )

            assertThat(underTest.getSelections())
                .isEqualTo(
                    mapOf<String, List<String>>(
                        KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START to emptyList(),
                        KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_END to emptyList(),
                    )
                )
        }

    companion object {
        private const val CONTENT_DESCRIPTION_RESOURCE_ID = 1337
        private val ICON: Icon =
            Icon.Resource(
                res = CONTENT_DESCRIPTION_RESOURCE_ID,
                contentDescription =
                    ContentDescription.Resource(
                        res = CONTENT_DESCRIPTION_RESOURCE_ID,
                    ),
            )
    }
}

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
 */

package com.android.systemui.keyguard.ui.viewmodel

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.UserHandle
import androidx.test.filters.SmallTest
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.internal.widget.LockPatternUtils
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.DialogLaunchAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.dock.DockManagerFake
import com.android.systemui.doze.util.BurnInHelperWrapper
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.quickaffordance.BuiltInKeyguardQuickAffordanceKeys
import com.android.systemui.keyguard.data.quickaffordance.FakeKeyguardQuickAffordanceConfig
import com.android.systemui.keyguard.data.quickaffordance.FakeKeyguardQuickAffordanceProviderClientFactory
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceConfig
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceLegacySettingSyncer
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceLocalUserSelectionManager
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceRemoteUserSelectionManager
import com.android.systemui.keyguard.data.repository.FakeKeyguardBouncerRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.KeyguardQuickAffordanceRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardBottomAreaInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardLongPressInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardQuickAffordanceInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.domain.quickaffordance.FakeKeyguardQuickAffordanceRegistry
import com.android.systemui.keyguard.shared.quickaffordance.ActivationState
import com.android.systemui.keyguard.shared.quickaffordance.KeyguardQuickAffordancePosition
import com.android.systemui.keyguard.shared.quickaffordance.KeyguardQuickAffordancesMetricsLogger
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.settings.UserFileManager
import com.android.systemui.settings.UserTracker
import com.android.systemui.shared.keyguard.shared.model.KeyguardQuickAffordanceSlots
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.policy.AccessibilityManagerWrapper
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.FakeSharedPreferences
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.settings.FakeSettings
import com.google.common.truth.Truth.assertThat
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.verifyZeroInteractions
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class KeyguardBottomAreaViewModelTest : SysuiTestCase() {

    @Mock private lateinit var expandable: Expandable
    @Mock private lateinit var burnInHelperWrapper: BurnInHelperWrapper
    @Mock private lateinit var lockPatternUtils: LockPatternUtils
    @Mock private lateinit var keyguardStateController: KeyguardStateController
    @Mock private lateinit var userTracker: UserTracker
    @Mock private lateinit var activityStarter: ActivityStarter
    @Mock private lateinit var launchAnimator: DialogLaunchAnimator
    @Mock private lateinit var commandQueue: CommandQueue
    @Mock private lateinit var devicePolicyManager: DevicePolicyManager
    @Mock private lateinit var logger: KeyguardQuickAffordancesMetricsLogger
    @Mock private lateinit var broadcastDispatcher: BroadcastDispatcher
    @Mock private lateinit var accessibilityManager: AccessibilityManagerWrapper

    private lateinit var underTest: KeyguardBottomAreaViewModel

    private lateinit var testScope: TestScope
    private lateinit var repository: FakeKeyguardRepository
    private lateinit var registry: FakeKeyguardQuickAffordanceRegistry
    private lateinit var homeControlsQuickAffordanceConfig: FakeKeyguardQuickAffordanceConfig
    private lateinit var quickAccessWalletAffordanceConfig: FakeKeyguardQuickAffordanceConfig
    private lateinit var qrCodeScannerAffordanceConfig: FakeKeyguardQuickAffordanceConfig
    private lateinit var dockManager: DockManagerFake

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(burnInHelperWrapper.burnInOffset(anyInt(), any()))
            .thenReturn(RETURNED_BURN_IN_OFFSET)

        homeControlsQuickAffordanceConfig =
            FakeKeyguardQuickAffordanceConfig(BuiltInKeyguardQuickAffordanceKeys.HOME_CONTROLS)
        quickAccessWalletAffordanceConfig =
            FakeKeyguardQuickAffordanceConfig(
                BuiltInKeyguardQuickAffordanceKeys.QUICK_ACCESS_WALLET
            )
        qrCodeScannerAffordanceConfig =
            FakeKeyguardQuickAffordanceConfig(BuiltInKeyguardQuickAffordanceKeys.QR_CODE_SCANNER)
        dockManager = DockManagerFake()
        registry =
            FakeKeyguardQuickAffordanceRegistry(
                mapOf(
                    KeyguardQuickAffordancePosition.BOTTOM_START to
                        listOf(
                            homeControlsQuickAffordanceConfig,
                        ),
                    KeyguardQuickAffordancePosition.BOTTOM_END to
                        listOf(
                            quickAccessWalletAffordanceConfig,
                            qrCodeScannerAffordanceConfig,
                        ),
                ),
            )
        repository = FakeKeyguardRepository()
        val featureFlags =
            FakeFeatureFlags().apply {
                set(Flags.CUSTOMIZABLE_LOCK_SCREEN_QUICK_AFFORDANCES, false)
                set(Flags.FACE_AUTH_REFACTOR, true)
                set(Flags.LOCK_SCREEN_LONG_PRESS_ENABLED, false)
                set(Flags.LOCK_SCREEN_LONG_PRESS_DIRECT_TO_WPP, false)
            }

        val keyguardInteractor =
            KeyguardInteractor(
                repository = repository,
                commandQueue = commandQueue,
                featureFlags = featureFlags,
                bouncerRepository = FakeKeyguardBouncerRepository(),
            )
        whenever(userTracker.userHandle).thenReturn(mock())
        whenever(userTracker.userId).thenReturn(10)
        whenever(lockPatternUtils.getStrongAuthForUser(anyInt()))
            .thenReturn(LockPatternUtils.StrongAuthTracker.STRONG_AUTH_NOT_REQUIRED)
        val testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)
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
                        backgroundDispatcher = testDispatcher,
                        secureSettings = FakeSettings(),
                        selectionsManager = localUserSelectionManager,
                    ),
                configs =
                    setOf(
                        homeControlsQuickAffordanceConfig,
                        quickAccessWalletAffordanceConfig,
                        qrCodeScannerAffordanceConfig,
                    ),
                dumpManager = mock(),
                userHandle = UserHandle.SYSTEM,
            )
        val keyguardLongPressInteractor =
            KeyguardLongPressInteractor(
                scope = testScope.backgroundScope,
                transitionInteractor =
                    KeyguardTransitionInteractor(
                        repository = FakeKeyguardTransitionRepository(),
                    ),
                repository = repository,
                logger = UiEventLoggerFake(),
                featureFlags = featureFlags,
                broadcastDispatcher = broadcastDispatcher,
                accessibilityManager = accessibilityManager,
            )
        underTest =
            KeyguardBottomAreaViewModel(
                keyguardInteractor = keyguardInteractor,
                quickAffordanceInteractor =
                    KeyguardQuickAffordanceInteractor(
                        keyguardInteractor = keyguardInteractor,
                        registry = registry,
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
                        backgroundDispatcher = testDispatcher,
                    ),
                bottomAreaInteractor = KeyguardBottomAreaInteractor(repository = repository),
                burnInHelperWrapper = burnInHelperWrapper,
                longPressViewModel =
                    KeyguardLongPressViewModel(
                        interactor = keyguardLongPressInteractor,
                    ),
                settingsMenuViewModel =
                    KeyguardSettingsMenuViewModel(
                        interactor = keyguardLongPressInteractor,
                    ),
            )
    }

    @Test
    fun startButton_present_visibleModel_startsActivityOnClick() =
        testScope.runTest {
            repository.setKeyguardShowing(true)
            val latest = collectLastValue(underTest.startButton)

            val testConfig =
                TestConfig(
                    isVisible = true,
                    isClickable = true,
                    isActivated = true,
                    icon = mock(),
                    canShowWhileLocked = false,
                    intent = Intent("action"),
                    slotId = KeyguardQuickAffordancePosition.BOTTOM_START.toSlotId(),
                )
            val configKey =
                setUpQuickAffordanceModel(
                    position = KeyguardQuickAffordancePosition.BOTTOM_START,
                    testConfig = testConfig,
                )

            assertQuickAffordanceViewModel(
                viewModel = latest(),
                testConfig = testConfig,
                configKey = configKey,
            )
        }

    @Test
    fun startButton_hiddenWhenDevicePolicyDisablesAllKeyguardFeatures() =
        testScope.runTest {
            whenever(devicePolicyManager.getKeyguardDisabledFeatures(null, userTracker.userId))
                .thenReturn(DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_ALL)
            repository.setKeyguardShowing(true)
            val latest by collectLastValue(underTest.startButton)

            val testConfig =
                TestConfig(
                    isVisible = true,
                    isClickable = true,
                    isActivated = true,
                    icon = mock(),
                    canShowWhileLocked = false,
                    intent = Intent("action"),
                    slotId = KeyguardQuickAffordancePosition.BOTTOM_START.toSlotId(),
                )
            val configKey =
                setUpQuickAffordanceModel(
                    position = KeyguardQuickAffordancePosition.BOTTOM_START,
                    testConfig = testConfig,
                )

            assertQuickAffordanceViewModel(
                viewModel = latest,
                testConfig =
                    TestConfig(
                        isVisible = false,
                        slotId = KeyguardQuickAffordancePosition.BOTTOM_START.toSlotId(),
                    ),
                configKey = configKey,
            )
        }

    @Test
    fun startButton_inPreviewMode_visibleEvenWhenKeyguardNotShowing() =
        testScope.runTest {
            underTest.enablePreviewMode(
                initiallySelectedSlotId = KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START,
                shouldHighlightSelectedAffordance = true,
            )
            repository.setKeyguardShowing(false)
            val latest = collectLastValue(underTest.startButton)

            val icon: Icon = mock()
            val configKey =
                setUpQuickAffordanceModel(
                    position = KeyguardQuickAffordancePosition.BOTTOM_START,
                    testConfig =
                        TestConfig(
                            isVisible = true,
                            isClickable = true,
                            isActivated = true,
                            icon = icon,
                            canShowWhileLocked = false,
                            intent = Intent("action"),
                            slotId = KeyguardQuickAffordancePosition.BOTTOM_START.toSlotId(),
                        ),
                )

            assertQuickAffordanceViewModel(
                viewModel = latest(),
                testConfig =
                    TestConfig(
                        isVisible = true,
                        isClickable = false,
                        isActivated = false,
                        icon = icon,
                        canShowWhileLocked = false,
                        intent = Intent("action"),
                        isSelected = true,
                        slotId = KeyguardQuickAffordancePosition.BOTTOM_START.toSlotId(),
                    ),
                configKey = configKey,
            )
            assertThat(latest()?.isSelected).isTrue()
        }

    @Test
    fun endButton_inHiglightedPreviewMode_dimmedWhenOtherIsSelected() =
        testScope.runTest {
            underTest.enablePreviewMode(
                initiallySelectedSlotId = KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START,
                shouldHighlightSelectedAffordance = true,
            )
            repository.setKeyguardShowing(false)
            val startButton = collectLastValue(underTest.startButton)
            val endButton = collectLastValue(underTest.endButton)

            val icon: Icon = mock()
            setUpQuickAffordanceModel(
                position = KeyguardQuickAffordancePosition.BOTTOM_START,
                testConfig =
                    TestConfig(
                        isVisible = true,
                        isClickable = true,
                        isActivated = true,
                        icon = icon,
                        canShowWhileLocked = false,
                        intent = Intent("action"),
                        slotId = KeyguardQuickAffordancePosition.BOTTOM_START.toSlotId(),
                    ),
            )
            val configKey =
                setUpQuickAffordanceModel(
                    position = KeyguardQuickAffordancePosition.BOTTOM_END,
                    testConfig =
                        TestConfig(
                            isVisible = true,
                            isClickable = true,
                            isActivated = true,
                            icon = icon,
                            canShowWhileLocked = false,
                            intent = Intent("action"),
                            slotId = KeyguardQuickAffordancePosition.BOTTOM_END.toSlotId(),
                        ),
                )

            assertQuickAffordanceViewModel(
                viewModel = endButton(),
                testConfig =
                    TestConfig(
                        isVisible = true,
                        isClickable = false,
                        isActivated = false,
                        icon = icon,
                        canShowWhileLocked = false,
                        intent = Intent("action"),
                        isDimmed = true,
                        slotId = KeyguardQuickAffordancePosition.BOTTOM_END.toSlotId(),
                    ),
                configKey = configKey,
            )
        }

    @Test
    fun endButton_present_visibleModel_doNothingOnClick() =
        testScope.runTest {
            repository.setKeyguardShowing(true)
            val latest = collectLastValue(underTest.endButton)

            val config =
                TestConfig(
                    isVisible = true,
                    isClickable = true,
                    icon = mock(),
                    canShowWhileLocked = false,
                    intent =
                        null, // This will cause it to tell the system that the click was handled.
                    slotId = KeyguardQuickAffordancePosition.BOTTOM_END.toSlotId(),
                )
            val configKey =
                setUpQuickAffordanceModel(
                    position = KeyguardQuickAffordancePosition.BOTTOM_END,
                    testConfig = config,
                )

            assertQuickAffordanceViewModel(
                viewModel = latest(),
                testConfig = config,
                configKey = configKey,
            )
        }

    @Test
    fun startButton_notPresent_modelIsHidden() =
        testScope.runTest {
            val latest = collectLastValue(underTest.startButton)

            val config =
                TestConfig(
                    isVisible = false,
                    slotId = KeyguardQuickAffordancePosition.BOTTOM_START.toSlotId(),
                )
            val configKey =
                setUpQuickAffordanceModel(
                    position = KeyguardQuickAffordancePosition.BOTTOM_START,
                    testConfig = config,
                )

            assertQuickAffordanceViewModel(
                viewModel = latest(),
                testConfig = config,
                configKey = configKey,
            )
        }

    @Test
    fun animateButtonReveal() =
        testScope.runTest {
            repository.setKeyguardShowing(true)
            val testConfig =
                TestConfig(
                    isVisible = true,
                    isClickable = true,
                    icon = mock(),
                    canShowWhileLocked = false,
                    intent = Intent("action"),
                    slotId = KeyguardQuickAffordancePosition.BOTTOM_START.toSlotId(),
                )

            setUpQuickAffordanceModel(
                position = KeyguardQuickAffordancePosition.BOTTOM_START,
                testConfig = testConfig,
            )

            val value = collectLastValue(underTest.startButton.map { it.animateReveal })

            assertThat(value()).isFalse()
            repository.setAnimateDozingTransitions(true)
            assertThat(value()).isTrue()
            repository.setAnimateDozingTransitions(false)
            assertThat(value()).isFalse()
        }

    @Test
    fun isOverlayContainerVisible() =
        testScope.runTest {
            val value = collectLastValue(underTest.isOverlayContainerVisible)

            assertThat(value()).isTrue()
            repository.setDozing(true)
            assertThat(value()).isFalse()
            repository.setDozing(false)
            assertThat(value()).isTrue()
        }

    @Test
    fun alpha() =
        testScope.runTest {
            val value = collectLastValue(underTest.alpha)

            assertThat(value()).isEqualTo(1f)
            repository.setBottomAreaAlpha(0.1f)
            assertThat(value()).isEqualTo(0.1f)
            repository.setBottomAreaAlpha(0.5f)
            assertThat(value()).isEqualTo(0.5f)
            repository.setBottomAreaAlpha(0.2f)
            assertThat(value()).isEqualTo(0.2f)
            repository.setBottomAreaAlpha(0f)
            assertThat(value()).isEqualTo(0f)
        }

    @Test
    fun alpha_inPreviewMode_doesNotChange() =
        testScope.runTest {
            underTest.enablePreviewMode(
                initiallySelectedSlotId = null,
                shouldHighlightSelectedAffordance = false,
            )
            val value = collectLastValue(underTest.alpha)

            assertThat(value()).isEqualTo(1f)
            repository.setBottomAreaAlpha(0.1f)
            assertThat(value()).isEqualTo(1f)
            repository.setBottomAreaAlpha(0.5f)
            assertThat(value()).isEqualTo(1f)
            repository.setBottomAreaAlpha(0.2f)
            assertThat(value()).isEqualTo(1f)
            repository.setBottomAreaAlpha(0f)
            assertThat(value()).isEqualTo(1f)
        }

    @Test
    fun isIndicationAreaPadded() =
        testScope.runTest {
            repository.setKeyguardShowing(true)
            val value = collectLastValue(underTest.isIndicationAreaPadded)

            assertThat(value()).isFalse()
            setUpQuickAffordanceModel(
                position = KeyguardQuickAffordancePosition.BOTTOM_START,
                testConfig =
                    TestConfig(
                        isVisible = true,
                        isClickable = true,
                        icon = mock(),
                        canShowWhileLocked = true,
                        slotId = KeyguardQuickAffordancePosition.BOTTOM_START.toSlotId(),
                    )
            )
            assertThat(value()).isTrue()
            setUpQuickAffordanceModel(
                position = KeyguardQuickAffordancePosition.BOTTOM_END,
                testConfig =
                    TestConfig(
                        isVisible = true,
                        isClickable = true,
                        icon = mock(),
                        canShowWhileLocked = false,
                        slotId = KeyguardQuickAffordancePosition.BOTTOM_END.toSlotId(),
                    )
            )
            assertThat(value()).isTrue()
            setUpQuickAffordanceModel(
                position = KeyguardQuickAffordancePosition.BOTTOM_START,
                testConfig =
                    TestConfig(
                        isVisible = false,
                        slotId = KeyguardQuickAffordancePosition.BOTTOM_START.toSlotId(),
                    )
            )
            assertThat(value()).isTrue()
            setUpQuickAffordanceModel(
                position = KeyguardQuickAffordancePosition.BOTTOM_END,
                testConfig =
                    TestConfig(
                        isVisible = false,
                        slotId = KeyguardQuickAffordancePosition.BOTTOM_END.toSlotId(),
                    )
            )
            assertThat(value()).isFalse()
        }

    @Test
    fun indicationAreaTranslationX() =
        testScope.runTest {
            val value = collectLastValue(underTest.indicationAreaTranslationX)

            assertThat(value()).isEqualTo(0f)
            repository.setClockPosition(100, 100)
            assertThat(value()).isEqualTo(100f)
            repository.setClockPosition(200, 100)
            assertThat(value()).isEqualTo(200f)
            repository.setClockPosition(200, 200)
            assertThat(value()).isEqualTo(200f)
            repository.setClockPosition(300, 100)
            assertThat(value()).isEqualTo(300f)
        }

    @Test
    fun indicationAreaTranslationY() =
        testScope.runTest {
            val value =
                collectLastValue(underTest.indicationAreaTranslationY(DEFAULT_BURN_IN_OFFSET))

            // Negative 0 - apparently there's a difference in floating point arithmetic - FML
            assertThat(value()).isEqualTo(-0f)
            val expected1 = setDozeAmountAndCalculateExpectedTranslationY(0.1f)
            assertThat(value()).isEqualTo(expected1)
            val expected2 = setDozeAmountAndCalculateExpectedTranslationY(0.2f)
            assertThat(value()).isEqualTo(expected2)
            val expected3 = setDozeAmountAndCalculateExpectedTranslationY(0.5f)
            assertThat(value()).isEqualTo(expected3)
            val expected4 = setDozeAmountAndCalculateExpectedTranslationY(1f)
            assertThat(value()).isEqualTo(expected4)
        }

    @Test
    fun isClickable_trueWhenAlphaAtThreshold() =
        testScope.runTest {
            repository.setKeyguardShowing(true)
            repository.setBottomAreaAlpha(
                KeyguardBottomAreaViewModel.AFFORDANCE_FULLY_OPAQUE_ALPHA_THRESHOLD
            )

            val testConfig =
                TestConfig(
                    isVisible = true,
                    isClickable = true,
                    icon = mock(),
                    canShowWhileLocked = false,
                    intent = Intent("action"),
                    slotId = KeyguardQuickAffordancePosition.BOTTOM_START.toSlotId(),
                )
            val configKey =
                setUpQuickAffordanceModel(
                    position = KeyguardQuickAffordancePosition.BOTTOM_START,
                    testConfig = testConfig,
                )

            val latest = collectLastValue(underTest.startButton)

            assertQuickAffordanceViewModel(
                viewModel = latest(),
                testConfig = testConfig,
                configKey = configKey,
            )
        }

    @Test
    fun isClickable_trueWhenAlphaAboveThreshold() =
        testScope.runTest {
            repository.setKeyguardShowing(true)
            val latest = collectLastValue(underTest.startButton)
            repository.setBottomAreaAlpha(
                min(1f, KeyguardBottomAreaViewModel.AFFORDANCE_FULLY_OPAQUE_ALPHA_THRESHOLD + 0.1f),
            )

            val testConfig =
                TestConfig(
                    isVisible = true,
                    isClickable = true,
                    icon = mock(),
                    canShowWhileLocked = false,
                    intent = Intent("action"),
                    slotId = KeyguardQuickAffordancePosition.BOTTOM_START.toSlotId(),
                )
            val configKey =
                setUpQuickAffordanceModel(
                    position = KeyguardQuickAffordancePosition.BOTTOM_START,
                    testConfig = testConfig,
                )

            assertQuickAffordanceViewModel(
                viewModel = latest(),
                testConfig = testConfig,
                configKey = configKey,
            )
        }

    @Test
    fun isClickable_falseWhenAlphaBelowThreshold() =
        testScope.runTest {
            repository.setKeyguardShowing(true)
            val latest = collectLastValue(underTest.startButton)
            repository.setBottomAreaAlpha(
                max(0f, KeyguardBottomAreaViewModel.AFFORDANCE_FULLY_OPAQUE_ALPHA_THRESHOLD - 0.1f),
            )

            val testConfig =
                TestConfig(
                    isVisible = true,
                    isClickable = false,
                    icon = mock(),
                    canShowWhileLocked = false,
                    intent = Intent("action"),
                    slotId = KeyguardQuickAffordancePosition.BOTTOM_START.toSlotId(),
                )
            val configKey =
                setUpQuickAffordanceModel(
                    position = KeyguardQuickAffordancePosition.BOTTOM_START,
                    testConfig = testConfig,
                )

            assertQuickAffordanceViewModel(
                viewModel = latest(),
                testConfig = testConfig,
                configKey = configKey,
            )
        }

    @Test
    fun isClickable_falseWhenAlphaAtZero() =
        testScope.runTest {
            repository.setKeyguardShowing(true)
            val latest = collectLastValue(underTest.startButton)
            repository.setBottomAreaAlpha(0f)

            val testConfig =
                TestConfig(
                    isVisible = true,
                    isClickable = false,
                    icon = mock(),
                    canShowWhileLocked = false,
                    intent = Intent("action"),
                    slotId = KeyguardQuickAffordancePosition.BOTTOM_START.toSlotId(),
                )
            val configKey =
                setUpQuickAffordanceModel(
                    position = KeyguardQuickAffordancePosition.BOTTOM_START,
                    testConfig = testConfig,
                )

            assertQuickAffordanceViewModel(
                viewModel = latest(),
                testConfig = testConfig,
                configKey = configKey,
            )
        }

    private fun setDozeAmountAndCalculateExpectedTranslationY(dozeAmount: Float): Float {
        repository.setDozeAmount(dozeAmount)
        return dozeAmount * (RETURNED_BURN_IN_OFFSET - DEFAULT_BURN_IN_OFFSET)
    }

    private suspend fun setUpQuickAffordanceModel(
        position: KeyguardQuickAffordancePosition,
        testConfig: TestConfig,
    ): String {
        val config =
            when (position) {
                KeyguardQuickAffordancePosition.BOTTOM_START -> homeControlsQuickAffordanceConfig
                KeyguardQuickAffordancePosition.BOTTOM_END -> quickAccessWalletAffordanceConfig
            }

        val lockScreenState =
            if (testConfig.isVisible) {
                if (testConfig.intent != null) {
                    config.onTriggeredResult =
                        KeyguardQuickAffordanceConfig.OnTriggeredResult.StartActivity(
                            intent = testConfig.intent,
                            canShowWhileLocked = testConfig.canShowWhileLocked,
                        )
                }
                KeyguardQuickAffordanceConfig.LockScreenState.Visible(
                    icon = testConfig.icon ?: error("Icon is unexpectedly null!"),
                    activationState =
                        when (testConfig.isActivated) {
                            true -> ActivationState.Active
                            false -> ActivationState.Inactive
                        }
                )
            } else {
                KeyguardQuickAffordanceConfig.LockScreenState.Hidden
            }
        config.setState(lockScreenState)
        return config.key
    }

    private fun assertQuickAffordanceViewModel(
        viewModel: KeyguardQuickAffordanceViewModel?,
        testConfig: TestConfig,
        configKey: String,
    ) {
        checkNotNull(viewModel)
        assertThat(viewModel.isVisible).isEqualTo(testConfig.isVisible)
        assertThat(viewModel.isClickable).isEqualTo(testConfig.isClickable)
        assertThat(viewModel.isActivated).isEqualTo(testConfig.isActivated)
        assertThat(viewModel.isSelected).isEqualTo(testConfig.isSelected)
        assertThat(viewModel.isDimmed).isEqualTo(testConfig.isDimmed)
        assertThat(viewModel.slotId).isEqualTo(testConfig.slotId)
        if (testConfig.isVisible) {
            assertThat(viewModel.icon).isEqualTo(testConfig.icon)
            viewModel.onClicked.invoke(
                KeyguardQuickAffordanceViewModel.OnClickedParameters(
                    configKey = configKey,
                    expandable = expandable,
                    slotId = viewModel.slotId,
                )
            )
            if (testConfig.intent != null) {
                assertThat(Mockito.mockingDetails(activityStarter).invocations).hasSize(1)
            } else {
                verifyZeroInteractions(activityStarter)
            }
        } else {
            assertThat(viewModel.isVisible).isFalse()
        }
    }

    private data class TestConfig(
        val isVisible: Boolean,
        val isClickable: Boolean = false,
        val isActivated: Boolean = false,
        val icon: Icon? = null,
        val canShowWhileLocked: Boolean = false,
        val intent: Intent? = null,
        val isSelected: Boolean = false,
        val isDimmed: Boolean = false,
        val slotId: String = ""
    ) {
        init {
            check(!isVisible || icon != null) { "Must supply non-null icon if visible!" }
        }
    }

    companion object {
        private const val DEFAULT_BURN_IN_OFFSET = 5
        private const val RETURNED_BURN_IN_OFFSET = 3
    }
}

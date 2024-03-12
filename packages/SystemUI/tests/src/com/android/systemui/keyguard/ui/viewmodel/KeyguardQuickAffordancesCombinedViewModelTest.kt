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
 *
 */

package com.android.systemui.keyguard.ui.viewmodel

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.UserHandle
import androidx.test.filters.SmallTest
import com.android.internal.widget.LockPatternUtils
import com.android.systemui.Flags as AConfigFlags
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.dock.DockManagerFake
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
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
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractorFactory
import com.android.systemui.keyguard.domain.interactor.KeyguardQuickAffordanceInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.quickaffordance.ActivationState
import com.android.systemui.keyguard.shared.quickaffordance.KeyguardQuickAffordancePosition
import com.android.systemui.keyguard.shared.quickaffordance.KeyguardQuickAffordancesMetricsLogger
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.settings.UserFileManager
import com.android.systemui.settings.UserTracker
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shared.keyguard.shared.model.KeyguardQuickAffordanceSlots
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.FakeSharedPreferences
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.settings.FakeSettings
import com.google.common.truth.Truth
import kotlin.math.min
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class KeyguardQuickAffordancesCombinedViewModelTest : SysuiTestCase() {

    @Mock private lateinit var activityStarter: ActivityStarter
    @Mock private lateinit var devicePolicyManager: DevicePolicyManager
    @Mock private lateinit var expandable: Expandable
    @Mock private lateinit var userTracker: UserTracker
    @Mock private lateinit var lockPatternUtils: LockPatternUtils
    @Mock private lateinit var keyguardStateController: KeyguardStateController
    @Mock private lateinit var launchAnimator: DialogTransitionAnimator
    @Mock private lateinit var logger: KeyguardQuickAffordancesMetricsLogger
    @Mock private lateinit var shadeInteractor: ShadeInteractor
    @Mock
    private lateinit var aodToLockscreenTransitionViewModel: AodToLockscreenTransitionViewModel
    @Mock
    private lateinit var dozingToLockscreenTransitionViewModel:
        DozingToLockscreenTransitionViewModel
    @Mock
    private lateinit var dreamingHostedToLockscreenTransitionViewModel:
        DreamingHostedToLockscreenTransitionViewModel
    @Mock
    private lateinit var dreamingToLockscreenTransitionViewModel:
        DreamingToLockscreenTransitionViewModel
    @Mock
    private lateinit var goneToLockscreenTransitionViewModel: GoneToLockscreenTransitionViewModel
    @Mock
    private lateinit var occludedToLockscreenTransitionViewModel:
        OccludedToLockscreenTransitionViewModel
    @Mock
    private lateinit var offToLockscreenTransitionViewModel: OffToLockscreenTransitionViewModel
    @Mock
    private lateinit var primaryBouncerToLockscreenTransitionViewModel:
        PrimaryBouncerToLockscreenTransitionViewModel
    @Mock
    private lateinit var lockscreenToAodTransitionViewModel: LockscreenToAodTransitionViewModel
    @Mock
    private lateinit var lockscreenToDozingTransitionViewModel:
        LockscreenToDozingTransitionViewModel
    @Mock
    private lateinit var lockscreenToDreamingHostedTransitionViewModel:
        LockscreenToDreamingHostedTransitionViewModel
    @Mock
    private lateinit var lockscreenToDreamingTransitionViewModel:
        LockscreenToDreamingTransitionViewModel
    @Mock
    private lateinit var lockscreenToGoneTransitionViewModel: LockscreenToGoneTransitionViewModel
    @Mock
    private lateinit var lockscreenToOccludedTransitionViewModel:
        LockscreenToOccludedTransitionViewModel
    @Mock
    private lateinit var lockscreenToPrimaryBouncerTransitionViewModel:
        LockscreenToPrimaryBouncerTransitionViewModel
    @Mock
    private lateinit var lockscreenToGlanceableHubTransitionViewModel:
        LockscreenToGlanceableHubTransitionViewModel
    @Mock
    private lateinit var glanceableHubToLockscreenTransitionViewModel:
        GlanceableHubToLockscreenTransitionViewModel
    @Mock private lateinit var transitionInteractor: KeyguardTransitionInteractor

    private lateinit var underTest: KeyguardQuickAffordancesCombinedViewModel

    private lateinit var testScope: TestScope
    private lateinit var repository: FakeKeyguardRepository
    private lateinit var homeControlsQuickAffordanceConfig: FakeKeyguardQuickAffordanceConfig
    private lateinit var quickAccessWalletAffordanceConfig: FakeKeyguardQuickAffordanceConfig
    private lateinit var qrCodeScannerAffordanceConfig: FakeKeyguardQuickAffordanceConfig
    private lateinit var dockManager: DockManagerFake
    private lateinit var biometricSettingsRepository: FakeBiometricSettingsRepository
    private lateinit var keyguardInteractor: KeyguardInteractor

    private val intendedAlphaMutableStateFlow: MutableStateFlow<Float> = MutableStateFlow(1f)
    // the viewModel does a `map { 1 - it }` on this value, which is why it's different
    private val intendedShadeAlphaMutableStateFlow: MutableStateFlow<Float> = MutableStateFlow(0f)

    private val intendedFinishedKeyguardStateFlow = MutableStateFlow(KeyguardState.LOCKSCREEN)

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

        homeControlsQuickAffordanceConfig =
            FakeKeyguardQuickAffordanceConfig(BuiltInKeyguardQuickAffordanceKeys.HOME_CONTROLS)
        quickAccessWalletAffordanceConfig =
            FakeKeyguardQuickAffordanceConfig(
                BuiltInKeyguardQuickAffordanceKeys.QUICK_ACCESS_WALLET
            )
        qrCodeScannerAffordanceConfig =
            FakeKeyguardQuickAffordanceConfig(BuiltInKeyguardQuickAffordanceKeys.QR_CODE_SCANNER)
        dockManager = DockManagerFake()
        biometricSettingsRepository = FakeBiometricSettingsRepository()

        mSetFlagsRule.enableFlags(AConfigFlags.FLAG_KEYGUARD_BOTTOM_AREA_REFACTOR)

        val featureFlags =
            FakeFeatureFlags().apply {
                set(Flags.LOCK_SCREEN_LONG_PRESS_ENABLED, false)
                set(Flags.LOCK_SCREEN_LONG_PRESS_DIRECT_TO_WPP, false)
            }

        val withDeps = KeyguardInteractorFactory.create(featureFlags = featureFlags)
        keyguardInteractor = withDeps.keyguardInteractor
        repository = withDeps.repository

        whenever(userTracker.userHandle).thenReturn(mock())
        whenever(lockPatternUtils.getStrongAuthForUser(ArgumentMatchers.anyInt()))
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
                                    ArgumentMatchers.anyString(),
                                    ArgumentMatchers.anyInt(),
                                    ArgumentMatchers.anyInt(),
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

        intendedAlphaMutableStateFlow.value = 1f
        intendedShadeAlphaMutableStateFlow.value = 0f
        intendedFinishedKeyguardStateFlow.value = KeyguardState.LOCKSCREEN
        whenever(aodToLockscreenTransitionViewModel.shortcutsAlpha)
            .thenReturn(intendedAlphaMutableStateFlow)
        whenever(dozingToLockscreenTransitionViewModel.shortcutsAlpha).thenReturn(emptyFlow())
        whenever(dreamingHostedToLockscreenTransitionViewModel.shortcutsAlpha)
            .thenReturn(emptyFlow())
        whenever(dreamingToLockscreenTransitionViewModel.shortcutsAlpha).thenReturn(emptyFlow())
        whenever(goneToLockscreenTransitionViewModel.shortcutsAlpha).thenReturn(emptyFlow())
        whenever(occludedToLockscreenTransitionViewModel.shortcutsAlpha).thenReturn(emptyFlow())
        whenever(offToLockscreenTransitionViewModel.shortcutsAlpha).thenReturn(emptyFlow())
        whenever(primaryBouncerToLockscreenTransitionViewModel.shortcutsAlpha)
            .thenReturn(emptyFlow())
        whenever(lockscreenToAodTransitionViewModel.shortcutsAlpha)
            .thenReturn(intendedAlphaMutableStateFlow)
        whenever(lockscreenToDozingTransitionViewModel.shortcutsAlpha).thenReturn(emptyFlow())
        whenever(lockscreenToDreamingHostedTransitionViewModel.shortcutsAlpha)
            .thenReturn(emptyFlow())
        whenever(lockscreenToDreamingTransitionViewModel.shortcutsAlpha).thenReturn(emptyFlow())
        whenever(lockscreenToGoneTransitionViewModel.shortcutsAlpha).thenReturn(emptyFlow())
        whenever(lockscreenToOccludedTransitionViewModel.shortcutsAlpha).thenReturn(emptyFlow())
        whenever(lockscreenToPrimaryBouncerTransitionViewModel.shortcutsAlpha)
            .thenReturn(emptyFlow())
        whenever(lockscreenToGlanceableHubTransitionViewModel.shortcutsAlpha)
            .thenReturn(emptyFlow())
        whenever(glanceableHubToLockscreenTransitionViewModel.shortcutsAlpha)
            .thenReturn(emptyFlow())
        whenever(shadeInteractor.anyExpansion).thenReturn(intendedShadeAlphaMutableStateFlow)
        whenever(transitionInteractor.finishedKeyguardState)
            .thenReturn(intendedFinishedKeyguardStateFlow)

        underTest =
            KeyguardQuickAffordancesCombinedViewModel(
                quickAffordanceInteractor =
                    KeyguardQuickAffordanceInteractor(
                        keyguardInteractor = keyguardInteractor,
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
                        backgroundDispatcher = testDispatcher,
                        appContext = mContext,
                    ),
                keyguardInteractor = keyguardInteractor,
                shadeInteractor = shadeInteractor,
                aodToLockscreenTransitionViewModel = aodToLockscreenTransitionViewModel,
                dozingToLockscreenTransitionViewModel = dozingToLockscreenTransitionViewModel,
                dreamingHostedToLockscreenTransitionViewModel =
                    dreamingHostedToLockscreenTransitionViewModel,
                dreamingToLockscreenTransitionViewModel = dreamingToLockscreenTransitionViewModel,
                goneToLockscreenTransitionViewModel = goneToLockscreenTransitionViewModel,
                occludedToLockscreenTransitionViewModel = occludedToLockscreenTransitionViewModel,
                offToLockscreenTransitionViewModel = offToLockscreenTransitionViewModel,
                primaryBouncerToLockscreenTransitionViewModel =
                    primaryBouncerToLockscreenTransitionViewModel,
                glanceableHubToLockscreenTransitionViewModel =
                    glanceableHubToLockscreenTransitionViewModel,
                lockscreenToAodTransitionViewModel = lockscreenToAodTransitionViewModel,
                lockscreenToDozingTransitionViewModel = lockscreenToDozingTransitionViewModel,
                lockscreenToDreamingHostedTransitionViewModel =
                    lockscreenToDreamingHostedTransitionViewModel,
                lockscreenToDreamingTransitionViewModel = lockscreenToDreamingTransitionViewModel,
                lockscreenToGoneTransitionViewModel = lockscreenToGoneTransitionViewModel,
                lockscreenToOccludedTransitionViewModel = lockscreenToOccludedTransitionViewModel,
                lockscreenToPrimaryBouncerTransitionViewModel =
                    lockscreenToPrimaryBouncerTransitionViewModel,
                lockscreenToGlanceableHubTransitionViewModel =
                    lockscreenToGlanceableHubTransitionViewModel,
                transitionInteractor = transitionInteractor,
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
            underTest.onPreviewSlotSelected(KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START)
            underTest.enablePreviewMode(KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START, true)

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
            Truth.assertThat(latest()?.isSelected).isTrue()
        }

    @Test
    fun endButton_inHiglightedPreviewMode_dimmedWhenOtherIsSelected() =
        testScope.runTest {
            underTest.onPreviewSlotSelected(KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START)
            underTest.enablePreviewMode(KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START, true)

            repository.setKeyguardShowing(false)
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

            Truth.assertThat(value()).isFalse()
            repository.setAnimateDozingTransitions(true)
            Truth.assertThat(value()).isTrue()
            repository.setAnimateDozingTransitions(false)
            Truth.assertThat(value()).isFalse()
        }

    @Test
    fun isClickable_trueWhenAlphaAtThreshold() =
        testScope.runTest {
            repository.setKeyguardShowing(true)
            repository.setKeyguardAlpha(
                KeyguardQuickAffordancesCombinedViewModel.AFFORDANCE_FULLY_OPAQUE_ALPHA_THRESHOLD
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
            repository.setKeyguardAlpha(
                min(
                    1f,
                    KeyguardQuickAffordancesCombinedViewModel
                        .AFFORDANCE_FULLY_OPAQUE_ALPHA_THRESHOLD + 0.1f
                ),
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
            intendedAlphaMutableStateFlow.value =
                KeyguardQuickAffordancesCombinedViewModel.AFFORDANCE_FULLY_OPAQUE_ALPHA_THRESHOLD -
                    .1f
            // the viewModel does a `map { 1 - it }` on this value, which is why it's different
            intendedShadeAlphaMutableStateFlow.value =
                KeyguardQuickAffordancesCombinedViewModel.AFFORDANCE_FULLY_OPAQUE_ALPHA_THRESHOLD +
                    .1f
            repository.setKeyguardShowing(true)
            val latest = collectLastValue(underTest.startButton)

            val testConfig =
                TestConfig(
                    isVisible = false,
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
            intendedAlphaMutableStateFlow.value = 0f
            intendedShadeAlphaMutableStateFlow.value = 1f
            repository.setKeyguardShowing(true)
            val latest = collectLastValue(underTest.startButton)

            val testConfig =
                TestConfig(
                    isVisible = false,
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
    fun shadeExpansionAlpha_changes_whenOnLockscreen() =
        testScope.runTest {
            intendedFinishedKeyguardStateFlow.value = KeyguardState.LOCKSCREEN
            intendedShadeAlphaMutableStateFlow.value = 0.25f
            val underTest = collectLastValue(underTest.transitionAlpha)
            assertEquals(0.75f, underTest())

            intendedShadeAlphaMutableStateFlow.value = 0.3f
            assertEquals(0.7f, underTest())
        }

    @Test
    fun shadeExpansionAlpha_alwaysZero_whenNotOnLockscreen() =
        testScope.runTest {
            intendedFinishedKeyguardStateFlow.value = KeyguardState.GONE
            intendedShadeAlphaMutableStateFlow.value = 0.5f
            val underTest = collectLastValue(underTest.transitionAlpha)
            assertEquals(0f, underTest())

            intendedShadeAlphaMutableStateFlow.value = 0.25f
            assertEquals(0f, underTest())
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
        return "${position.toSlotId()}::${config.key}"
    }

    private fun assertQuickAffordanceViewModel(
        viewModel: KeyguardQuickAffordanceViewModel?,
        testConfig: TestConfig,
        configKey: String,
    ) {
        checkNotNull(viewModel)
        Truth.assertThat(viewModel.isVisible).isEqualTo(testConfig.isVisible)
        Truth.assertThat(viewModel.isClickable).isEqualTo(testConfig.isClickable)
        Truth.assertThat(viewModel.isActivated).isEqualTo(testConfig.isActivated)
        Truth.assertThat(viewModel.isSelected).isEqualTo(testConfig.isSelected)
        Truth.assertThat(viewModel.isDimmed).isEqualTo(testConfig.isDimmed)
        Truth.assertThat(viewModel.slotId).isEqualTo(testConfig.slotId)
        if (testConfig.isVisible) {
            Truth.assertThat(viewModel.icon).isEqualTo(testConfig.icon)
            viewModel.onClicked.invoke(
                KeyguardQuickAffordanceViewModel.OnClickedParameters(
                    configKey = configKey,
                    expandable = expandable,
                    slotId = viewModel.slotId,
                )
            )
            if (testConfig.intent != null) {
                Truth.assertThat(Mockito.mockingDetails(activityStarter).invocations).hasSize(1)
            } else {
                Mockito.verifyZeroInteractions(activityStarter)
            }
        } else {
            Truth.assertThat(viewModel.isVisible).isFalse()
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
}

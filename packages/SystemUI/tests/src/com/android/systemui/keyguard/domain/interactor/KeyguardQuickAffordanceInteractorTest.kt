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
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.DialogLaunchAnimator
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.coroutines.collectLastValue
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
import com.android.systemui.keyguard.data.repository.KeyguardQuickAffordanceRepository
import com.android.systemui.keyguard.domain.model.KeyguardQuickAffordanceModel
import com.android.systemui.keyguard.domain.quickaffordance.FakeKeyguardQuickAffordanceRegistry
import com.android.systemui.keyguard.shared.model.KeyguardQuickAffordancePickerRepresentation
import com.android.systemui.keyguard.shared.quickaffordance.ActivationState
import com.android.systemui.keyguard.shared.quickaffordance.KeyguardQuickAffordancePosition
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.settings.UserFileManager
import com.android.systemui.settings.UserTracker
import com.android.systemui.shared.keyguard.shared.model.KeyguardQuickAffordanceSlots
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.FakeSharedPreferences
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.settings.FakeSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
    @Mock private lateinit var launchAnimator: DialogLaunchAnimator
    @Mock private lateinit var commandQueue: CommandQueue
    @Mock private lateinit var devicePolicyManager: DevicePolicyManager

    private lateinit var underTest: KeyguardQuickAffordanceInteractor

    private lateinit var testScope: TestScope
    private lateinit var repository: FakeKeyguardRepository
    private lateinit var homeControls: FakeKeyguardQuickAffordanceConfig
    private lateinit var quickAccessWallet: FakeKeyguardQuickAffordanceConfig
    private lateinit var qrCodeScanner: FakeKeyguardQuickAffordanceConfig
    private lateinit var featureFlags: FakeFeatureFlags

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

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
                configs = setOf(homeControls, quickAccessWallet, qrCodeScanner),
                dumpManager = mock(),
                userHandle = UserHandle.SYSTEM,
            )
        featureFlags =
            FakeFeatureFlags().apply {
                set(Flags.CUSTOMIZABLE_LOCK_SCREEN_QUICK_AFFORDANCES, false)
                set(Flags.FACE_AUTH_REFACTOR, true)
            }

        underTest =
            KeyguardQuickAffordanceInteractor(
                keyguardInteractor =
                    KeyguardInteractor(
                        repository = repository,
                        commandQueue = commandQueue,
                        featureFlags = featureFlags,
                        bouncerRepository = FakeKeyguardBouncerRepository(),
                    ),
                registry =
                    FakeKeyguardQuickAffordanceRegistry(
                        mapOf(
                            KeyguardQuickAffordancePosition.BOTTOM_START to
                                listOf(
                                    homeControls,
                                ),
                            KeyguardQuickAffordancePosition.BOTTOM_END to
                                listOf(
                                    quickAccessWallet,
                                    qrCodeScanner,
                                ),
                        ),
                    ),
                lockPatternUtils = lockPatternUtils,
                keyguardStateController = keyguardStateController,
                userTracker = userTracker,
                activityStarter = activityStarter,
                featureFlags = featureFlags,
                repository = { quickAffordanceRepository },
                launchAnimator = launchAnimator,
                devicePolicyManager = devicePolicyManager,
                backgroundDispatcher = testDispatcher,
            )
    }

    @Test
    fun `quickAffordance - bottom start affordance is visible`() =
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
            assertThat(visibleModel.configKey).isEqualTo(configKey)
            assertThat(visibleModel.icon).isEqualTo(ICON)
            assertThat(visibleModel.icon.contentDescription)
                .isEqualTo(ContentDescription.Resource(res = CONTENT_DESCRIPTION_RESOURCE_ID))
            assertThat(visibleModel.activationState).isEqualTo(ActivationState.Active)
        }

    @Test
    fun `quickAffordance - bottom end affordance is visible`() =
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
            assertThat(visibleModel.configKey).isEqualTo(configKey)
            assertThat(visibleModel.icon).isEqualTo(ICON)
            assertThat(visibleModel.icon.contentDescription)
                .isEqualTo(ContentDescription.Resource(res = CONTENT_DESCRIPTION_RESOURCE_ID))
            assertThat(visibleModel.activationState).isEqualTo(ActivationState.NotSupported)
        }

    @Test
    fun `quickAffordance - hidden when all features are disabled by device policy`() =
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
    fun `quickAffordance - hidden when shortcuts feature is disabled by device policy`() =
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
    fun `quickAffordance - hidden when quick settings is visible`() =
        testScope.runTest {
            repository.setQuickSettingsVisible(true)
            quickAccessWallet.setState(
                KeyguardQuickAffordanceConfig.LockScreenState.Visible(
                    icon = ICON,
                )
            )

            val collectedValue =
                collectLastValue(
                    underTest.quickAffordance(KeyguardQuickAffordancePosition.BOTTOM_END)
                )

            assertThat(collectedValue()).isEqualTo(KeyguardQuickAffordanceModel.Hidden)
        }

    @Test
    fun `quickAffordance - bottom start affordance hidden while dozing`() =
        testScope.runTest {
            repository.setDozing(true)
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
    fun `quickAffordance - bottom start affordance hidden when lockscreen is not showing`() =
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
    fun `quickAffordanceAlwaysVisible - even when lock screen not showing and dozing`() =
        testScope.runTest {
            repository.setKeyguardShowing(false)
            repository.setDozing(true)
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
            assertThat(visibleModel.configKey).isEqualTo(configKey)
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

            featureFlags.set(Flags.CUSTOMIZABLE_LOCK_SCREEN_QUICK_AFFORDANCES, true)
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
                                    name = homeControls.pickerName,
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
                                    name = quickAccessWallet.pickerName,
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
                                    name = quickAccessWallet.pickerName,
                                    iconResourceId = quickAccessWallet.pickerIconResourceId,
                                ),
                            ),
                        KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_END to
                            listOf(
                                KeyguardQuickAffordancePickerRepresentation(
                                    id = qrCodeScanner.key,
                                    name = qrCodeScanner.pickerName,
                                    iconResourceId = qrCodeScanner.pickerIconResourceId,
                                ),
                            ),
                    )
                )
        }

    @Test
    fun `unselect - one`() =
        testScope.runTest {
            featureFlags.set(Flags.CUSTOMIZABLE_LOCK_SCREEN_QUICK_AFFORDANCES, true)
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
                                    name = quickAccessWallet.pickerName,
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
    fun `unselect - all`() =
        testScope.runTest {
            featureFlags.set(Flags.CUSTOMIZABLE_LOCK_SCREEN_QUICK_AFFORDANCES, true)
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
                                    name = quickAccessWallet.pickerName,
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
        private val ICON: Icon = mock {
            whenever(this.contentDescription)
                .thenReturn(
                    ContentDescription.Resource(
                        res = CONTENT_DESCRIPTION_RESOURCE_ID,
                    )
                )
        }
        private const val CONTENT_DESCRIPTION_RESOURCE_ID = 1337
    }
}

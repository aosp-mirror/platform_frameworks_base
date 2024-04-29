/*
 * Copyright (C) 2024 The Android Open Source Project
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
import android.content.Intent
import android.os.UserHandle
import androidx.test.filters.FlakyTest
import androidx.test.filters.SmallTest
import com.android.internal.widget.LockPatternUtils
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.ActivityTransitionAnimator
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dock.DockManagerFake
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.keyguard.data.quickaffordance.BuiltInKeyguardQuickAffordanceKeys
import com.android.systemui.keyguard.data.quickaffordance.FakeKeyguardQuickAffordanceConfig
import com.android.systemui.keyguard.data.quickaffordance.FakeKeyguardQuickAffordanceProviderClientFactory
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceConfig
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceLegacySettingSyncer
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceLocalUserSelectionManager
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceRemoteUserSelectionManager
import com.android.systemui.keyguard.data.repository.FakeBiometricSettingsRepository
import com.android.systemui.keyguard.data.repository.KeyguardQuickAffordanceRepository
import com.android.systemui.keyguard.shared.quickaffordance.KeyguardQuickAffordancePosition
import com.android.systemui.keyguard.shared.quickaffordance.KeyguardQuickAffordancesMetricsLogger
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.settings.FakeUserTracker
import com.android.systemui.settings.UserFileManager
import com.android.systemui.settings.UserTracker
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.testKosmos
import com.android.systemui.util.FakeSharedPreferences
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.settings.FakeSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameter
import org.junit.runners.Parameterized.Parameters
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.same
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyZeroInteractions
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@FlakyTest(
    bugId = 292574995,
    detail = "on certain architectures all permutations with startActivity=true is causing failures"
)
@SmallTest
@RunWith(Parameterized::class)
@EnableSceneContainer
class KeyguardQuickAffordanceInteractorSceneContainerTest : SysuiTestCase() {

    companion object {
        private val INTENT = Intent("some.intent.action")
        private val DRAWABLE =
            mock<Icon> {
                whenever(this.contentDescription)
                    .thenReturn(
                        ContentDescription.Resource(
                            res = CONTENT_DESCRIPTION_RESOURCE_ID,
                        )
                    )
            }
        private const val CONTENT_DESCRIPTION_RESOURCE_ID = 1337

        @Parameters(
            name =
                "needStrongAuthAfterBoot={0}, canShowWhileLocked={1}," +
                    " keyguardIsUnlocked={2}, needsToUnlockFirst={3}, startActivity={4}"
        )
        @JvmStatic
        fun data() =
            listOf(
                arrayOf(
                    /* needStrongAuthAfterBoot= */ false,
                    /* canShowWhileLocked= */ false,
                    /* keyguardIsUnlocked= */ false,
                    /* needsToUnlockFirst= */ true,
                    /* startActivity= */ false,
                ),
                arrayOf(
                    /* needStrongAuthAfterBoot= */ false,
                    /* canShowWhileLocked= */ false,
                    /* keyguardIsUnlocked= */ true,
                    /* needsToUnlockFirst= */ false,
                    /* startActivity= */ false,
                ),
                arrayOf(
                    /* needStrongAuthAfterBoot= */ false,
                    /* canShowWhileLocked= */ true,
                    /* keyguardIsUnlocked= */ false,
                    /* needsToUnlockFirst= */ false,
                    /* startActivity= */ false,
                ),
                arrayOf(
                    /* needStrongAuthAfterBoot= */ false,
                    /* canShowWhileLocked= */ true,
                    /* keyguardIsUnlocked= */ true,
                    /* needsToUnlockFirst= */ false,
                    /* startActivity= */ false,
                ),
                arrayOf(
                    /* needStrongAuthAfterBoot= */ true,
                    /* canShowWhileLocked= */ false,
                    /* keyguardIsUnlocked= */ false,
                    /* needsToUnlockFirst= */ true,
                    /* startActivity= */ false,
                ),
                arrayOf(
                    /* needStrongAuthAfterBoot= */ true,
                    /* canShowWhileLocked= */ false,
                    /* keyguardIsUnlocked= */ true,
                    /* needsToUnlockFirst= */ true,
                    /* startActivity= */ false,
                ),
                arrayOf(
                    /* needStrongAuthAfterBoot= */ true,
                    /* canShowWhileLocked= */ true,
                    /* keyguardIsUnlocked= */ false,
                    /* needsToUnlockFirst= */ true,
                    /* startActivity= */ false,
                ),
                arrayOf(
                    /* needStrongAuthAfterBoot= */ true,
                    /* canShowWhileLocked= */ true,
                    /* keyguardIsUnlocked= */ true,
                    /* needsToUnlockFirst= */ true,
                    /* startActivity= */ false,
                ),
                arrayOf(
                    /* needStrongAuthAfterBoot= */ false,
                    /* canShowWhileLocked= */ false,
                    /* keyguardIsUnlocked= */ false,
                    /* needsToUnlockFirst= */ true,
                    /* startActivity= */ true,
                ),
                arrayOf(
                    /* needStrongAuthAfterBoot= */ false,
                    /* canShowWhileLocked= */ false,
                    /* keyguardIsUnlocked= */ true,
                    /* needsToUnlockFirst= */ false,
                    /* startActivity= */ true,
                ),
                arrayOf(
                    /* needStrongAuthAfterBoot= */ false,
                    /* canShowWhileLocked= */ true,
                    /* keyguardIsUnlocked= */ false,
                    /* needsToUnlockFirst= */ false,
                    /* startActivity= */ true,
                ),
                arrayOf(
                    /* needStrongAuthAfterBoot= */ false,
                    /* canShowWhileLocked= */ true,
                    /* keyguardIsUnlocked= */ true,
                    /* needsToUnlockFirst= */ false,
                    /* startActivity= */ true,
                ),
                arrayOf(
                    /* needStrongAuthAfterBoot= */ true,
                    /* canShowWhileLocked= */ false,
                    /* keyguardIsUnlocked= */ false,
                    /* needsToUnlockFirst= */ true,
                    /* startActivity= */ true,
                ),
                arrayOf(
                    /* needStrongAuthAfterBoot= */ true,
                    /* canShowWhileLocked= */ false,
                    /* keyguardIsUnlocked= */ true,
                    /* needsToUnlockFirst= */ true,
                    /* startActivity= */ true,
                ),
                arrayOf(
                    /* needStrongAuthAfterBoot= */ true,
                    /* canShowWhileLocked= */ true,
                    /* keyguardIsUnlocked= */ false,
                    /* needsToUnlockFirst= */ true,
                    /* startActivity= */ true,
                ),
                arrayOf(
                    /* needStrongAuthAfterBoot= */ true,
                    /* canShowWhileLocked= */ true,
                    /* keyguardIsUnlocked= */ true,
                    /* needsToUnlockFirst= */ true,
                    /* startActivity= */ true,
                ),
            )

        private val IMMEDIATE = Dispatchers.Main.immediate
    }

    @Mock private lateinit var lockPatternUtils: LockPatternUtils
    @Mock private lateinit var keyguardStateController: KeyguardStateController
    @Mock private lateinit var activityStarter: ActivityStarter
    @Mock private lateinit var animationController: ActivityTransitionAnimator.Controller
    @Mock private lateinit var expandable: Expandable
    @Mock private lateinit var launchAnimator: DialogTransitionAnimator
    @Mock private lateinit var devicePolicyManager: DevicePolicyManager
    @Mock private lateinit var logger: KeyguardQuickAffordancesMetricsLogger

    private lateinit var underTest: KeyguardQuickAffordanceInteractor
    private lateinit var testScope: TestScope

    @JvmField @Parameter(0) var needStrongAuthAfterBoot: Boolean = false
    @JvmField @Parameter(1) var canShowWhileLocked: Boolean = false
    @JvmField @Parameter(2) var keyguardIsUnlocked: Boolean = false
    @JvmField @Parameter(3) var needsToUnlockFirst: Boolean = false
    @JvmField @Parameter(4) var startActivity: Boolean = false
    private lateinit var homeControls: FakeKeyguardQuickAffordanceConfig
    private lateinit var dockManager: DockManagerFake
    private lateinit var biometricSettingsRepository: FakeBiometricSettingsRepository
    private lateinit var userTracker: UserTracker

    private val kosmos = testKosmos()

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(expandable.activityTransitionController()).thenReturn(animationController)

        userTracker = FakeUserTracker()
        homeControls =
            FakeKeyguardQuickAffordanceConfig(BuiltInKeyguardQuickAffordanceKeys.HOME_CONTROLS)
        dockManager = DockManagerFake()
        biometricSettingsRepository = FakeBiometricSettingsRepository()
        val quickAccessWallet =
            FakeKeyguardQuickAffordanceConfig(
                BuiltInKeyguardQuickAffordanceKeys.QUICK_ACCESS_WALLET
            )
        val qrCodeScanner =
            FakeKeyguardQuickAffordanceConfig(BuiltInKeyguardQuickAffordanceKeys.QR_CODE_SCANNER)
        val scope = CoroutineScope(IMMEDIATE)
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
                scope = scope,
                userTracker = userTracker,
                clientFactory = FakeKeyguardQuickAffordanceProviderClientFactory(userTracker),
                userHandle = UserHandle.SYSTEM,
            )
        val quickAffordanceRepository =
            KeyguardQuickAffordanceRepository(
                appContext = context,
                scope = scope,
                localUserSelectionManager = localUserSelectionManager,
                remoteUserSelectionManager = remoteUserSelectionManager,
                userTracker = userTracker,
                legacySettingSyncer =
                    KeyguardQuickAffordanceLegacySettingSyncer(
                        scope = scope,
                        backgroundDispatcher = IMMEDIATE,
                        secureSettings = FakeSettings(),
                        selectionsManager = localUserSelectionManager,
                    ),
                configs = setOf(homeControls, quickAccessWallet, qrCodeScanner),
                dumpManager = mock(),
                userHandle = UserHandle.SYSTEM,
            )
        val featureFlags = FakeFeatureFlags()
        val testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)
        underTest =
            KeyguardQuickAffordanceInteractor(
                keyguardInteractor =
                    KeyguardInteractorFactory.create(
                            featureFlags = featureFlags,
                        )
                        .keyguardInteractor,
                shadeInteractor = kosmos.shadeInteractor,
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
                sceneInteractor = { kosmos.sceneInteractor },
            )
    }

    @Test
    fun onQuickAffordanceTriggered() =
        testScope.runTest {
            val key = BuiltInKeyguardQuickAffordanceKeys.HOME_CONTROLS
            setUpMocks(
                needStrongAuthAfterBoot = needStrongAuthAfterBoot,
                keyguardIsUnlocked = keyguardIsUnlocked,
            )

            homeControls.setState(
                lockScreenState =
                    KeyguardQuickAffordanceConfig.LockScreenState.Visible(
                        icon = DRAWABLE,
                    )
            )
            homeControls.onTriggeredResult =
                if (startActivity) {
                    KeyguardQuickAffordanceConfig.OnTriggeredResult.StartActivity(
                        intent = INTENT,
                        canShowWhileLocked = canShowWhileLocked,
                    )
                } else {
                    KeyguardQuickAffordanceConfig.OnTriggeredResult.Handled
                }

            underTest.onQuickAffordanceTriggered(
                configKey = "${KeyguardQuickAffordancePosition.BOTTOM_START.toSlotId()}::$key",
                expandable = expandable,
                slotId = "",
            )

            if (startActivity) {
                if (needsToUnlockFirst) {
                    verify(activityStarter)
                        .postStartActivityDismissingKeyguard(
                            any(),
                            /* delay= */ eq(0),
                            same(animationController),
                        )
                } else {
                    verify(activityStarter)
                        .startActivity(
                            any(),
                            /* dismissShade= */ eq(true),
                            same(animationController),
                            /* showOverLockscreenWhenLocked= */ eq(true),
                        )
                }
            } else {
                verifyZeroInteractions(activityStarter)
            }
        }

    private fun setUpMocks(
        needStrongAuthAfterBoot: Boolean = true,
        keyguardIsUnlocked: Boolean = false,
    ) {
        whenever(lockPatternUtils.getStrongAuthForUser(any()))
            .thenReturn(
                if (needStrongAuthAfterBoot) {
                    LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_BOOT
                } else {
                    LockPatternUtils.StrongAuthTracker.STRONG_AUTH_NOT_REQUIRED
                }
            )
        whenever(keyguardStateController.isUnlocked).thenReturn(keyguardIsUnlocked)
    }
}

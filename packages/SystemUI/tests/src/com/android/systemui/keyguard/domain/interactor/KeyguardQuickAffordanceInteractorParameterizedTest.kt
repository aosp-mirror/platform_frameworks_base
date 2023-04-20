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

import android.content.Intent
import android.os.UserHandle
import androidx.test.filters.SmallTest
import com.android.internal.widget.LockPatternUtils
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.ActivityLaunchAnimator
import com.android.systemui.animation.DialogLaunchAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.quickaffordance.BuiltInKeyguardQuickAffordanceKeys
import com.android.systemui.keyguard.data.quickaffordance.FakeKeyguardQuickAffordanceConfig
import com.android.systemui.keyguard.data.quickaffordance.FakeKeyguardQuickAffordanceProviderClientFactory
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceConfig
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceLegacySettingSyncer
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceLocalUserSelectionManager
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceRemoteUserSelectionManager
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.KeyguardQuickAffordanceRepository
import com.android.systemui.keyguard.domain.quickaffordance.FakeKeyguardQuickAffordanceRegistry
import com.android.systemui.keyguard.shared.quickaffordance.KeyguardQuickAffordancePosition
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.settings.FakeUserTracker
import com.android.systemui.settings.UserFileManager
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.FakeSharedPreferences
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.settings.FakeSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runBlockingTest
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

@SmallTest
@RunWith(Parameterized::class)
class KeyguardQuickAffordanceInteractorParameterizedTest : SysuiTestCase() {

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
    @Mock private lateinit var animationController: ActivityLaunchAnimator.Controller
    @Mock private lateinit var expandable: Expandable
    @Mock private lateinit var launchAnimator: DialogLaunchAnimator

    private lateinit var underTest: KeyguardQuickAffordanceInteractor

    @JvmField @Parameter(0) var needStrongAuthAfterBoot: Boolean = false
    @JvmField @Parameter(1) var canShowWhileLocked: Boolean = false
    @JvmField @Parameter(2) var keyguardIsUnlocked: Boolean = false
    @JvmField @Parameter(3) var needsToUnlockFirst: Boolean = false
    @JvmField @Parameter(4) var startActivity: Boolean = false
    private lateinit var homeControls: FakeKeyguardQuickAffordanceConfig
    private lateinit var userTracker: UserTracker

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(expandable.activityLaunchController()).thenReturn(animationController)

        userTracker = FakeUserTracker()
        homeControls =
            FakeKeyguardQuickAffordanceConfig(BuiltInKeyguardQuickAffordanceKeys.HOME_CONTROLS)
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
        underTest =
            KeyguardQuickAffordanceInteractor(
                keyguardInteractor = KeyguardInteractor(repository = FakeKeyguardRepository()),
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
                featureFlags =
                    FakeFeatureFlags().apply {
                        set(Flags.CUSTOMIZABLE_LOCK_SCREEN_QUICK_AFFORDANCES, false)
                    },
                repository = { quickAffordanceRepository },
                launchAnimator = launchAnimator,
            )
    }

    @Test
    fun onQuickAffordanceTriggered() = runBlockingTest {
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
            configKey = BuiltInKeyguardQuickAffordanceKeys.HOME_CONTROLS,
            expandable = expandable,
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

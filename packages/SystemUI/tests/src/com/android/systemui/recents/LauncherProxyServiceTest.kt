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

package com.android.systemui.recents

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.PowerManager
import android.os.UserManager
import android.testing.TestableContext
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.app.AssistUtils
import com.android.internal.logging.UiEventLogger
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.KeyguardUnlockAnimationController
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.keyguard.ui.view.InWindowLauncherUnlockAnimationManager
import com.android.systemui.model.SysUiState
import com.android.systemui.model.sceneContainerPlugin
import com.android.systemui.navigationbar.NavigationBarController
import com.android.systemui.navigationbar.NavigationModeController
import com.android.systemui.process.ProcessWrapper
import com.android.systemui.recents.LauncherProxyService.ACTION_QUICKSTEP
import com.android.systemui.settings.FakeDisplayTracker
import com.android.systemui.settings.UserTracker
import com.android.systemui.shade.ShadeViewController
import com.android.systemui.shared.recents.ILauncherProxy
import com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_WAKEFULNESS_MASK
import com.android.systemui.shared.system.QuickStepContract.WAKEFULNESS_ASLEEP
import com.android.systemui.shared.system.QuickStepContract.WAKEFULNESS_AWAKE
import com.android.systemui.shared.system.QuickStepContract.WAKEFULNESS_GOING_TO_SLEEP
import com.android.systemui.shared.system.QuickStepContract.WAKEFULNESS_WAKING
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.NotificationShadeWindowController
import com.android.systemui.testKosmos
import com.android.systemui.unfold.progress.UnfoldTransitionProgressForwarder
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.android.wm.shell.back.BackAnimation
import com.android.wm.shell.sysui.ShellInterface
import com.google.common.util.concurrent.MoreExecutors
import java.util.Optional
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.atLeast
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.longThat
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class LauncherProxyServiceTest : SysuiTestCase() {

    @Main private val executor: Executor = MoreExecutors.directExecutor()

    private val kosmos = testKosmos()
    private lateinit var subject: LauncherProxyService
    @Mock private val dumpManager = DumpManager()
    @Mock private lateinit var processWrapper: ProcessWrapper
    private val displayTracker = FakeDisplayTracker(mContext)
    private val fakeSystemClock = FakeSystemClock()
    private val sysUiState = SysUiState(displayTracker, kosmos.sceneContainerPlugin)
    private val wakefulnessLifecycle =
        WakefulnessLifecycle(mContext, null, fakeSystemClock, dumpManager)

    @Mock private lateinit var launcherProxy: ILauncherProxy.Stub
    @Mock private lateinit var packageManager: PackageManager

    // The following mocks belong to not-yet-tested parts of LauncherProxyService.
    @Mock private lateinit var commandQueue: CommandQueue
    @Mock private lateinit var shellInterface: ShellInterface
    @Mock private lateinit var navBarController: NavigationBarController
    @Mock private lateinit var shadeViewController: ShadeViewController
    @Mock private lateinit var screenPinningRequest: ScreenPinningRequest
    @Mock private lateinit var navModeController: NavigationModeController
    @Mock private lateinit var statusBarWinController: NotificationShadeWindowController
    @Mock private lateinit var userTracker: UserTracker
    @Mock private lateinit var userManager: UserManager
    @Mock private lateinit var uiEventLogger: UiEventLogger
    @Mock private lateinit var sysuiUnlockAnimationController: KeyguardUnlockAnimationController
    @Mock
    private lateinit var inWindowLauncherUnlockAnimationManager:
        InWindowLauncherUnlockAnimationManager
    @Mock private lateinit var assistUtils: AssistUtils
    @Mock
    private lateinit var unfoldTransitionProgressForwarder:
        Optional<UnfoldTransitionProgressForwarder>
    @Mock private lateinit var broadcastDispatcher: BroadcastDispatcher
    @Mock private lateinit var backAnimation: Optional<BackAnimation>

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        val serviceComponent = ComponentName("test_package", "service_provider")
        context.addMockService(serviceComponent, launcherProxy)
        context.addMockServiceResolver(
            TestableContext.MockServiceResolver {
                if (it.action == ACTION_QUICKSTEP) serviceComponent else null
            }
        )
        whenever(launcherProxy.queryLocalInterface(ArgumentMatchers.anyString()))
            .thenReturn(launcherProxy)
        whenever(launcherProxy.asBinder()).thenReturn(launcherProxy)

        // packageManager.resolveServiceAsUser has to return non-null for
        // LauncherProxyService#isEnabled to become true.
        context.setMockPackageManager(packageManager)
        whenever(packageManager.resolveServiceAsUser(any(), anyInt(), anyInt()))
            .thenReturn(mock(ResolveInfo::class.java))

        mSetFlagsRule.disableFlags(com.android.systemui.Flags.FLAG_KEYGUARD_WM_STATE_REFACTOR)

        // return isSystemUser as true by default.
        `when`(processWrapper.isSystemUser).thenReturn(true)
        subject = createLauncherProxyService(context)
    }

    @After
    fun tearDown() {
        subject.shutdownForTest()
    }

    @Test
    fun wakefulnessLifecycle_dispatchFinishedWakingUpSetsSysUIflagToAWAKE() {
        // WakefulnessLifecycle is initialized to AWAKE initially, and won't emit a noop.
        wakefulnessLifecycle.dispatchFinishedGoingToSleep()
        clearInvocations(launcherProxy)

        wakefulnessLifecycle.dispatchFinishedWakingUp()

        verify(launcherProxy)
            .onSystemUiStateChanged(
                longThat { it and SYSUI_STATE_WAKEFULNESS_MASK == WAKEFULNESS_AWAKE }
            )
    }

    @Test
    fun wakefulnessLifecycle_dispatchStartedWakingUpSetsSysUIflagToWAKING() {
        wakefulnessLifecycle.dispatchStartedWakingUp(PowerManager.WAKE_REASON_UNKNOWN)

        verify(launcherProxy)
            .onSystemUiStateChanged(
                longThat { it and SYSUI_STATE_WAKEFULNESS_MASK == WAKEFULNESS_WAKING }
            )
    }

    @Test
    fun wakefulnessLifecycle_dispatchFinishedGoingToSleepSetsSysUIflagToASLEEP() {
        wakefulnessLifecycle.dispatchFinishedGoingToSleep()

        verify(launcherProxy)
            .onSystemUiStateChanged(
                longThat { it and SYSUI_STATE_WAKEFULNESS_MASK == WAKEFULNESS_ASLEEP }
            )
    }

    @Test
    fun wakefulnessLifecycle_dispatchStartedGoingToSleepSetsSysUIflagToGOING_TO_SLEEP() {
        wakefulnessLifecycle.dispatchStartedGoingToSleep(
            PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON
        )

        verify(launcherProxy)
            .onSystemUiStateChanged(
                longThat { it and SYSUI_STATE_WAKEFULNESS_MASK == WAKEFULNESS_GOING_TO_SLEEP }
            )
    }

    @Test
    fun connectToLauncherService_primaryUserNoVisibleBgUsersSupported_expectBindService() {
        `when`(processWrapper.isSystemUser).thenReturn(true)
        `when`(userManager.isVisibleBackgroundUsersSupported()).thenReturn(false)
        val spyContext = spy(context)
        val ops = createLauncherProxyService(spyContext)
        ops.startConnectionToCurrentUser()
        verify(spyContext, atLeast(1)).bindServiceAsUser(any(), any(), anyInt(), any())
    }

    @Test
    fun connectToLauncherService_nonPrimaryUserNoVisibleBgUsersSupported_expectNoBindService() {
        `when`(processWrapper.isSystemUser).thenReturn(false)
        `when`(userManager.isVisibleBackgroundUsersSupported()).thenReturn(false)
        val spyContext = spy(context)
        val ops = createLauncherProxyService(spyContext)
        ops.startConnectionToCurrentUser()
        verify(spyContext, times(0)).bindServiceAsUser(any(), any(), anyInt(), any())
    }

    @Test
    fun connectToLauncherService_nonPrimaryBgUserVisibleBgUsersSupported_expectBindService() {
        `when`(processWrapper.isSystemUser).thenReturn(false)
        `when`(userManager.isVisibleBackgroundUsersSupported()).thenReturn(true)
        `when`(userManager.isUserForeground()).thenReturn(false)
        val spyContext = spy(context)
        val ops = createLauncherProxyService(spyContext)
        ops.startConnectionToCurrentUser()
        verify(spyContext, atLeast(1)).bindServiceAsUser(any(), any(), anyInt(), any())
    }

    @Test
    fun connectToLauncherService_nonPrimaryFgUserVisibleBgUsersSupported_expectNoBindService() {
        `when`(processWrapper.isSystemUser).thenReturn(false)
        `when`(userManager.isVisibleBackgroundUsersSupported()).thenReturn(true)
        `when`(userManager.isUserForeground()).thenReturn(true)
        val spyContext = spy(context)
        val ops = createLauncherProxyService(spyContext)
        ops.startConnectionToCurrentUser()
        verify(spyContext, times(0)).bindServiceAsUser(any(), any(), anyInt(), any())
    }

    private fun createLauncherProxyService(ctx: Context): LauncherProxyService {
        return LauncherProxyService(
            ctx,
            executor,
            commandQueue,
            shellInterface,
            { navBarController },
            { shadeViewController },
            screenPinningRequest,
            navModeController,
            statusBarWinController,
            sysUiState,
            mock(),
            mock(),
            userTracker,
            userManager,
            wakefulnessLifecycle,
            uiEventLogger,
            displayTracker,
            sysuiUnlockAnimationController,
            inWindowLauncherUnlockAnimationManager,
            assistUtils,
            dumpManager,
            unfoldTransitionProgressForwarder,
            broadcastDispatcher,
            backAnimation,
            processWrapper,
        )
    }
}

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
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.testing.AndroidTestingRunner
import android.testing.TestableContext
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.internal.app.AssistUtils
import com.android.internal.logging.UiEventLogger
import com.android.systemui.SysuiTestCase
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.KeyguardUnlockAnimationController
import com.android.systemui.keyguard.ScreenLifecycle
import com.android.systemui.model.SysUiState
import com.android.systemui.navigationbar.NavigationBarController
import com.android.systemui.navigationbar.NavigationModeController
import com.android.systemui.recents.OverviewProxyService.ACTION_QUICKSTEP
import com.android.systemui.settings.FakeDisplayTracker
import com.android.systemui.settings.UserTracker
import com.android.systemui.shared.recents.IOverviewProxy
import com.android.systemui.shared.system.QuickStepContract.SCREEN_STATE_OFF
import com.android.systemui.shared.system.QuickStepContract.SCREEN_STATE_ON
import com.android.systemui.shared.system.QuickStepContract.SCREEN_STATE_TURNING_OFF
import com.android.systemui.shared.system.QuickStepContract.SCREEN_STATE_TURNING_ON
import com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_SCREEN_STATE_MASK
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.NotificationShadeWindowController
import com.android.systemui.statusbar.phone.CentralSurfaces
import com.android.systemui.unfold.progress.UnfoldTransitionProgressForwarder
import com.android.systemui.util.mockito.whenever
import com.android.wm.shell.sysui.ShellInterface
import com.google.common.util.concurrent.MoreExecutors
import dagger.Lazy
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
import org.mockito.Mockito.intThat
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class OverviewProxyServiceTest : SysuiTestCase() {

    @Main private val executor: Executor = MoreExecutors.directExecutor()

    private lateinit var subject: OverviewProxyService
    private val dumpManager = DumpManager()
    private val displayTracker = FakeDisplayTracker(mContext)
    private val sysUiState = SysUiState(displayTracker)
    private val screenLifecycle = ScreenLifecycle(dumpManager)

    @Mock private lateinit var overviewProxy: IOverviewProxy.Stub
    @Mock private lateinit var packageManager: PackageManager

    // The following mocks belong to not-yet-tested parts of OverviewProxyService.
    @Mock private lateinit var commandQueue: CommandQueue
    @Mock private lateinit var shellInterface: ShellInterface
    @Mock private lateinit var navBarController: NavigationBarController
    @Mock private lateinit var centralSurfaces: CentralSurfaces
    @Mock private lateinit var navModeController: NavigationModeController
    @Mock private lateinit var statusBarWinController: NotificationShadeWindowController
    @Mock private lateinit var userTracker: UserTracker
    @Mock private lateinit var uiEventLogger: UiEventLogger
    @Mock private lateinit var sysuiUnlockAnimationController: KeyguardUnlockAnimationController
    @Mock private lateinit var assistUtils: AssistUtils
    @Mock
    private lateinit var unfoldTransitionProgressForwarder:
        Optional<UnfoldTransitionProgressForwarder>

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        val serviceComponent = ComponentName("test_package", "service_provider")
        context.addMockService(serviceComponent, overviewProxy)
        context.addMockServiceResolver(
            TestableContext.MockServiceResolver {
                if (it.action == ACTION_QUICKSTEP) serviceComponent else null
            }
        )
        whenever(overviewProxy.queryLocalInterface(ArgumentMatchers.anyString()))
            .thenReturn(overviewProxy)
        whenever(overviewProxy.asBinder()).thenReturn(overviewProxy)

        // packageManager.resolveServiceAsUser has to return non-null for
        // OverviewProxyService#isEnabled to become true.
        context.setMockPackageManager(packageManager)
        whenever(packageManager.resolveServiceAsUser(any(), anyInt(), anyInt()))
            .thenReturn(mock(ResolveInfo::class.java))

        subject =
            OverviewProxyService(
                context,
                executor,
                commandQueue,
                shellInterface,
                Lazy { navBarController },
                Lazy { Optional.of(centralSurfaces) },
                navModeController,
                statusBarWinController,
                sysUiState,
                userTracker,
                screenLifecycle,
                uiEventLogger,
                displayTracker,
                sysuiUnlockAnimationController,
                assistUtils,
                dumpManager,
                unfoldTransitionProgressForwarder
            )
    }

    @After
    fun tearDown() {
        subject.shutdownForTest()
    }

    @Test
    fun `ScreenLifecycle - screenTurnedOn triggers SysUI state flag changes `() {
        screenLifecycle.dispatchScreenTurnedOn()

        verify(overviewProxy)
            .onSystemUiStateChanged(
                intThat { it and SYSUI_STATE_SCREEN_STATE_MASK == SCREEN_STATE_ON }
            )
    }

    @Test
    fun `ScreenLifecycle - screenTurningOn triggers SysUI state flag changes `() {
        screenLifecycle.dispatchScreenTurningOn()

        verify(overviewProxy)
            .onSystemUiStateChanged(
                intThat { it and SYSUI_STATE_SCREEN_STATE_MASK == SCREEN_STATE_TURNING_ON }
            )
    }

    @Test
    fun `ScreenLifecycle - screenTurnedOff triggers SysUI state flag changes `() {
        screenLifecycle.dispatchScreenTurnedOff()

        verify(overviewProxy)
            .onSystemUiStateChanged(
                intThat { it and SYSUI_STATE_SCREEN_STATE_MASK == SCREEN_STATE_OFF }
            )
    }

    @Test
    fun `ScreenLifecycle - screenTurningOff triggers SysUI state flag changes `() {
        screenLifecycle.dispatchScreenTurningOff()

        verify(overviewProxy)
            .onSystemUiStateChanged(
                intThat { it and SYSUI_STATE_SCREEN_STATE_MASK == SCREEN_STATE_TURNING_OFF }
            )
    }
}

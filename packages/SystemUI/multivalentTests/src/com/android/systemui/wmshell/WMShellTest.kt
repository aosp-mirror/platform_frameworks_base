/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.systemui.wmshell

import android.content.pm.UserInfo
import android.graphics.Color
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.keyguardUpdateMonitor
import com.android.systemui.Flags.FLAG_COMMUNAL_HUB
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.domain.interactor.setCommunalAvailable
import com.android.systemui.communal.ui.viewmodel.communalTransitionViewModel
import com.android.systemui.communal.util.fakeCommunalColors
import com.android.systemui.concurrency.fakeExecutor
import com.android.systemui.flags.Flags.COMMUNAL_SERVICE_ENABLED
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.keyguard.ScreenLifecycle
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.wakefulnessLifecycle
import com.android.systemui.kosmos.testScope
import com.android.systemui.model.SysUiState
import com.android.systemui.model.sysUiState
import com.android.systemui.notetask.NoteTaskInitializer
import com.android.systemui.settings.FakeDisplayTracker
import com.android.systemui.settings.UserTracker
import com.android.systemui.settings.userTracker
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.commandQueue
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.statusbar.policy.configurationController
import com.android.systemui.statusbar.policy.keyguardStateController
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.fakeUserRepository
import com.android.systemui.util.kotlin.JavaAdapter
import com.android.wm.shell.desktopmode.DesktopMode
import com.android.wm.shell.desktopmode.DesktopModeTaskRepository.VisibleTasksListener
import com.android.wm.shell.onehanded.OneHanded
import com.android.wm.shell.onehanded.OneHandedEventCallback
import com.android.wm.shell.onehanded.OneHandedTransitionCallback
import com.android.wm.shell.pip.Pip
import com.android.wm.shell.recents.RecentTasks
import com.android.wm.shell.splitscreen.SplitScreen
import com.android.wm.shell.sysui.ShellInterface
import java.util.Optional
import java.util.concurrent.Executor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

/**
 * Tests for [WMShell].
 *
 * Build/Install/Run: atest SystemUITests:WMShellTest
 */
@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class WMShellTest : SysuiTestCase() {
    val kosmos = testKosmos()
    val testScope = kosmos.testScope

    @Mock private lateinit var mShellInterface: ShellInterface
    @Mock private lateinit var mScreenLifecycle: ScreenLifecycle
    @Mock private lateinit var mPip: Pip
    @Mock private lateinit var mSplitScreen: SplitScreen
    @Mock private lateinit var mOneHanded: OneHanded
    @Mock private lateinit var mNoteTaskInitializer: NoteTaskInitializer
    @Mock private lateinit var mDesktopMode: DesktopMode
    @Mock private lateinit var mRecentTasks: RecentTasks

    private val mCommandQueue: CommandQueue = kosmos.commandQueue
    private val mConfigurationController: ConfigurationController = kosmos.configurationController
    private val mKeyguardStateController: KeyguardStateController = kosmos.keyguardStateController
    private val mKeyguardUpdateMonitor: KeyguardUpdateMonitor = kosmos.keyguardUpdateMonitor
    private val mSysUiState: SysUiState = kosmos.sysUiState
    private val mWakefulnessLifecycle: WakefulnessLifecycle = kosmos.wakefulnessLifecycle
    private val mUserTracker: UserTracker = kosmos.userTracker
    private val mSysUiMainExecutor: Executor = kosmos.fakeExecutor
    private val communalTransitionViewModel = kosmos.communalTransitionViewModel

    private lateinit var underTest: WMShell

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        val displayTracker = FakeDisplayTracker(mContext)

        kosmos.fakeUserRepository.setUserInfos(listOf(MAIN_USER_INFO))
        kosmos.fakeFeatureFlagsClassic.set(COMMUNAL_SERVICE_ENABLED, true)

        underTest =
            WMShell(
                mContext,
                mShellInterface,
                Optional.of(mPip),
                Optional.of(mSplitScreen),
                Optional.of(mOneHanded),
                Optional.of(mDesktopMode),
                Optional.of(mRecentTasks),
                mCommandQueue,
                mConfigurationController,
                mKeyguardStateController,
                mKeyguardUpdateMonitor,
                mScreenLifecycle,
                mSysUiState,
                mWakefulnessLifecycle,
                mUserTracker,
                displayTracker,
                mNoteTaskInitializer,
                communalTransitionViewModel,
                JavaAdapter(testScope.backgroundScope),
                mSysUiMainExecutor
            )
    }

    @Test
    fun initPip_registersCommandQueueCallback() {
        underTest.initPip(mPip)
        verify(mCommandQueue).addCallback(any(CommandQueue.Callbacks::class.java))
    }

    @Test
    fun initOneHanded_registersCallbacks() {
        underTest.initOneHanded(mOneHanded)
        verify(mCommandQueue).addCallback(any(CommandQueue.Callbacks::class.java))
        verify(mScreenLifecycle).addObserver(any(ScreenLifecycle.Observer::class.java))
        verify(mOneHanded).registerTransitionCallback(any(OneHandedTransitionCallback::class.java))
        verify(mOneHanded).registerEventCallback(any(OneHandedEventCallback::class.java))
    }

    @Test
    fun initDesktopMode_registersListener() {
        underTest.initDesktopMode(mDesktopMode)
        verify(mDesktopMode)
            .addVisibleTasksListener(
                any(VisibleTasksListener::class.java),
                any(Executor::class.java)
            )
    }

    @Test
    fun initRecentTasks_registersListener() {
        underTest.initRecentTasks(mRecentTasks)
        verify(mRecentTasks).addAnimationStateListener(any(Executor::class.java), any())
    }

    @Test
    @EnableFlags(FLAG_COMMUNAL_HUB)
    fun initRecentTasks_setRecentsBackgroundColorWhenCommunal() =
        testScope.runTest {
            val black = Color.valueOf(Color.BLACK)
            kosmos.fakeCommunalColors.setBackgroundColor(black)

            kosmos.fakeKeyguardRepository.setKeyguardShowing(false)

            underTest.initRecentTasks(mRecentTasks)
            runCurrent()
            verify(mRecentTasks).setTransitionBackgroundColor(null)
            verify(mRecentTasks, never()).setTransitionBackgroundColor(black)

            // Transition to occluded from the glanceable hub.
            kosmos.fakeKeyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.GLANCEABLE_HUB,
                to = KeyguardState.OCCLUDED,
                testScope
            )
            kosmos.setCommunalAvailable(true)
            runCurrent()

            verify(mRecentTasks).setTransitionBackgroundColor(black)
        }

    private companion object {
        val MAIN_USER_INFO = UserInfo(0, "primary", UserInfo.FLAG_MAIN)
    }
}

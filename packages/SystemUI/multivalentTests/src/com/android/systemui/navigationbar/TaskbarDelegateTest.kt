package com.android.systemui.navigationbar

import android.app.ActivityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.model.SysUiState
import com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.recents.OverviewProxyService
import com.android.systemui.shared.system.QuickStepContract
import com.android.systemui.shared.system.TaskStackChangeListeners
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.phone.AutoHideController
import com.android.systemui.statusbar.phone.LightBarController
import com.android.systemui.statusbar.phone.LightBarTransitionsController
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import com.android.wm.shell.back.BackAnimation
import com.android.wm.shell.pip.Pip
import java.util.Optional
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class TaskbarDelegateTest : SysuiTestCase() {
    val DISPLAY_ID = 0;
    val MODE_GESTURE = 0;
    val MODE_THREE_BUTTON = 1;

    private lateinit var mTaskStackChangeListeners: TaskStackChangeListeners
    private lateinit var mTaskbarDelegate: TaskbarDelegate
    @Mock
    lateinit var mEdgeBackGestureHandler : EdgeBackGestureHandler
    @Mock
    lateinit var mLightBarControllerFactory : LightBarTransitionsController.Factory
    @Mock
    lateinit var mLightBarTransitionController: LightBarTransitionsController
    @Mock
    lateinit var mCommandQueue: CommandQueue
    @Mock
    lateinit var mOverviewProxyService: OverviewProxyService
    @Mock
    lateinit var mNavBarHelper: NavBarHelper
    @Mock
    lateinit var mNavigationModeController: NavigationModeController
    @Mock
    lateinit var mSysUiState: SysUiState
    @Mock
    lateinit var mDumpManager: DumpManager
    @Mock
    lateinit var mAutoHideController: AutoHideController
    @Mock
    lateinit var mLightBarController: LightBarController
    @Mock
    lateinit var mOptionalPip: Optional<Pip>
    @Mock
    lateinit var mBackAnimation: BackAnimation
    @Mock
    lateinit var mCurrentSysUiState: NavBarHelper.CurrentSysuiState
    @Mock
    lateinit var mStatusBarKeyguardViewManager: StatusBarKeyguardViewManager
    @Mock
    lateinit var mStatusBarStateController: StatusBarStateController

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        `when`(mNavBarHelper.edgeBackGestureHandler).thenReturn(mEdgeBackGestureHandler)
        `when`(mLightBarControllerFactory.create(any())).thenReturn(mLightBarTransitionController)
        `when`(mNavBarHelper.currentSysuiState).thenReturn(mCurrentSysUiState)
        `when`(mSysUiState.setFlag(anyLong(), anyBoolean())).thenReturn(mSysUiState)
        mTaskStackChangeListeners = TaskStackChangeListeners.getTestInstance()
        mTaskbarDelegate = TaskbarDelegate(context, mLightBarControllerFactory,
            mStatusBarKeyguardViewManager, mStatusBarStateController)
        mTaskbarDelegate.setDependencies(mCommandQueue, mOverviewProxyService, mNavBarHelper,
        mNavigationModeController, mSysUiState, mDumpManager, mAutoHideController,
                mLightBarController, mOptionalPip, mBackAnimation, mTaskStackChangeListeners)
    }

    @Test
    fun navigationModeInitialized() {
        `when`(mNavigationModeController.addListener(any())).thenReturn(MODE_THREE_BUTTON)
        assert(mTaskbarDelegate.navigationMode == -1)
        mTaskbarDelegate.init(DISPLAY_ID)
        assert(mTaskbarDelegate.navigationMode == MODE_THREE_BUTTON)
    }

    @Test
    fun navigationModeInitialized_notifyEdgeBackHandler() {
        `when`(mNavigationModeController.addListener(any())).thenReturn(MODE_GESTURE)
        mTaskbarDelegate.init(DISPLAY_ID)
        verify(mEdgeBackGestureHandler, times(1)).onNavigationModeChanged(MODE_GESTURE)
    }

    @Test
    fun screenPinningEnabled_updatesSysuiState() {
        mTaskbarDelegate.init(DISPLAY_ID)
        mTaskStackChangeListeners.listenerImpl.onLockTaskModeChanged(
            ActivityManager.LOCK_TASK_MODE_PINNED)
        verify(mSysUiState, times(1)).setFlag(
            ArgumentMatchers.eq(QuickStepContract.SYSUI_STATE_SCREEN_PINNING),
            ArgumentMatchers.eq(true)
        )
    }
}

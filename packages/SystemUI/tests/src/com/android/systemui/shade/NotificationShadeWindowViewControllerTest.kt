/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.shade

import org.mockito.Mockito.`when` as whenever
import android.content.Context
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardSecurityContainerController
import com.android.keyguard.LockIconViewController
import com.android.keyguard.dagger.KeyguardBouncerComponent
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor
import com.android.systemui.bouncer.ui.binder.BouncerViewBinder
import com.android.systemui.classifier.FalsingCollectorFake
import com.android.systemui.dock.DockManager
import com.android.systemui.dump.DumpManager
import com.android.systemui.flags.FakeFeatureFlagsClassic
import com.android.systemui.flags.Flags.LOCKSCREEN_WALLPAPER_DREAM_ENABLED
import com.android.systemui.flags.Flags.SPLIT_SHADE_SUBPIXEL_OPTIMIZATION
import com.android.systemui.flags.Flags.TRACKPAD_GESTURE_COMMON
import com.android.systemui.flags.Flags.TRACKPAD_GESTURE_FEATURES
import com.android.systemui.keyevent.domain.interactor.SysUIKeyEventHandler
import com.android.systemui.keyguard.KeyguardUnlockAnimationController
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.keyguard.ui.viewmodel.AlternateBouncerDependencies
import com.android.systemui.res.R
import com.android.systemui.shade.NotificationShadeWindowView.InteractionEventHandler
import com.android.systemui.statusbar.DragDownHelper
import com.android.systemui.statusbar.LockscreenShadeTransitionController
import com.android.systemui.statusbar.NotificationInsetsController
import com.android.systemui.statusbar.NotificationShadeDepthController
import com.android.systemui.statusbar.NotificationShadeWindowController
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.notification.data.repository.NotificationLaunchAnimationRepository
import com.android.systemui.statusbar.notification.domain.interactor.NotificationLaunchAnimationInteractor
import com.android.systemui.statusbar.notification.stack.AmbientState
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.phone.CentralSurfaces
import com.android.systemui.statusbar.phone.DozeScrimController
import com.android.systemui.statusbar.phone.DozeServiceHost
import com.android.systemui.statusbar.phone.PhoneStatusBarViewController
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import com.android.systemui.statusbar.window.StatusBarWindowStateController
import com.android.systemui.unfold.UnfoldTransitionProgressProvider
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import com.android.systemui.util.kotlin.JavaAdapter
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import java.util.Optional
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.anyFloat
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper(setAsMainLooper = true)
class NotificationShadeWindowViewControllerTest : SysuiTestCase() {

    @Mock private lateinit var view: NotificationShadeWindowView
    @Mock private lateinit var sysuiStatusBarStateController: SysuiStatusBarStateController
    @Mock private lateinit var centralSurfaces: CentralSurfaces
    @Mock private lateinit var dozeServiceHost: DozeServiceHost
    @Mock private lateinit var dozeScrimController: DozeScrimController
    @Mock private lateinit var dockManager: DockManager
    @Mock private lateinit var notificationPanelViewController: NotificationPanelViewController
    @Mock private lateinit var notificationShadeDepthController: NotificationShadeDepthController
    @Mock private lateinit var notificationShadeWindowController: NotificationShadeWindowController
    @Mock private lateinit var keyguardUnlockAnimationController: KeyguardUnlockAnimationController
    @Mock private lateinit var shadeLogger: ShadeLogger
    @Mock private lateinit var dumpManager: DumpManager
    @Mock private lateinit var ambientState: AmbientState
    @Mock private lateinit var stackScrollLayoutController: NotificationStackScrollLayoutController
    @Mock private lateinit var statusBarKeyguardViewManager: StatusBarKeyguardViewManager
    @Mock private lateinit var statusBarWindowStateController: StatusBarWindowStateController
    @Mock private lateinit var quickSettingsController: QuickSettingsController
    @Mock
    private lateinit var lockscreenShadeTransitionController: LockscreenShadeTransitionController
    @Mock private lateinit var lockIconViewController: LockIconViewController
    @Mock private lateinit var phoneStatusBarViewController: PhoneStatusBarViewController
    @Mock private lateinit var pulsingGestureListener: PulsingGestureListener
    @Mock
    private lateinit var mLockscreenHostedDreamGestureListener: LockscreenHostedDreamGestureListener
    @Mock private lateinit var notificationInsetsController: NotificationInsetsController
    @Mock private lateinit var mGlanceableHubContainerController: GlanceableHubContainerController
    @Mock lateinit var keyguardBouncerComponentFactory: KeyguardBouncerComponent.Factory
    @Mock lateinit var keyguardBouncerComponent: KeyguardBouncerComponent
    @Mock lateinit var keyguardSecurityContainerController: KeyguardSecurityContainerController
    @Mock
    private lateinit var unfoldTransitionProgressProvider:
        Optional<UnfoldTransitionProgressProvider>
    @Mock lateinit var keyguardTransitionInteractor: KeyguardTransitionInteractor
    @Mock lateinit var dragDownHelper: DragDownHelper
    @Mock lateinit var mSelectedUserInteractor: SelectedUserInteractor
    @Mock lateinit var sysUIKeyEventHandler: SysUIKeyEventHandler
    @Mock lateinit var primaryBouncerInteractor: PrimaryBouncerInteractor
    @Mock lateinit var alternateBouncerInteractor: AlternateBouncerInteractor
    private val notificationLaunchAnimationRepository = NotificationLaunchAnimationRepository()
    private val notificationLaunchAnimationInteractor =
        NotificationLaunchAnimationInteractor(notificationLaunchAnimationRepository)

    private lateinit var fakeClock: FakeSystemClock
    private lateinit var interactionEventHandlerCaptor: ArgumentCaptor<InteractionEventHandler>
    private lateinit var interactionEventHandler: InteractionEventHandler

    private lateinit var underTest: NotificationShadeWindowViewController

    private lateinit var testScope: TestScope

    private lateinit var featureFlagsClassic: FakeFeatureFlagsClassic

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(view.bottom).thenReturn(VIEW_BOTTOM)
        whenever(view.findViewById<ViewGroup>(R.id.keyguard_bouncer_container))
            .thenReturn(mock(ViewGroup::class.java))
        whenever(keyguardBouncerComponentFactory.create(any(ViewGroup::class.java)))
            .thenReturn(keyguardBouncerComponent)
        whenever(keyguardBouncerComponent.securityContainerController)
            .thenReturn(keyguardSecurityContainerController)
        whenever(keyguardTransitionInteractor.lockscreenToDreamingTransition)
            .thenReturn(emptyFlow<TransitionStep>())

        featureFlagsClassic = FakeFeatureFlagsClassic()
        featureFlagsClassic.set(TRACKPAD_GESTURE_COMMON, true)
        featureFlagsClassic.set(TRACKPAD_GESTURE_FEATURES, false)
        featureFlagsClassic.set(SPLIT_SHADE_SUBPIXEL_OPTIMIZATION, true)
        featureFlagsClassic.set(LOCKSCREEN_WALLPAPER_DREAM_ENABLED, false)
        mSetFlagsRule.disableFlags(Flags.FLAG_DEVICE_ENTRY_UDFPS_REFACTOR)
        mSetFlagsRule.enableFlags(Flags.FLAG_REVAMPED_BOUNCER_MESSAGES)

        testScope = TestScope()
        fakeClock = FakeSystemClock()
        underTest =
            NotificationShadeWindowViewController(
                lockscreenShadeTransitionController,
                FalsingCollectorFake(),
                sysuiStatusBarStateController,
                dockManager,
                notificationShadeDepthController,
                view,
                notificationPanelViewController,
                ShadeExpansionStateManager(),
                stackScrollLayoutController,
                statusBarKeyguardViewManager,
                statusBarWindowStateController,
                lockIconViewController,
                centralSurfaces,
                dozeServiceHost,
                dozeScrimController,
                notificationShadeWindowController,
                unfoldTransitionProgressProvider,
                keyguardUnlockAnimationController,
                notificationInsetsController,
                ambientState,
                shadeLogger,
                dumpManager,
                pulsingGestureListener,
                mLockscreenHostedDreamGestureListener,
                keyguardTransitionInteractor,
                mGlanceableHubContainerController,
                notificationLaunchAnimationInteractor,
                featureFlagsClassic,
                fakeClock,
                sysUIKeyEventHandler,
                quickSettingsController,
                primaryBouncerInteractor,
                alternateBouncerInteractor,
                { mock(JavaAdapter::class.java) },
                { mock(AlternateBouncerDependencies::class.java) },
                mock(BouncerViewBinder::class.java)
            )
        underTest.setupExpandedStatusBar()
        underTest.setDragDownHelper(dragDownHelper)

        interactionEventHandlerCaptor = ArgumentCaptor.forClass(InteractionEventHandler::class.java)
        verify(view).setInteractionEventHandler(interactionEventHandlerCaptor.capture())
        interactionEventHandler = interactionEventHandlerCaptor.value
    }

    // Note: So far, these tests only cover interactions with the status bar view controller. More
    // tests need to be added to test the rest of handleDispatchTouchEvent.

    @Test
    fun handleDispatchTouchEvent_nullStatusBarViewController_returnsFalse() =
        testScope.runTest {
            underTest.setStatusBarViewController(null)

            val returnVal = interactionEventHandler.handleDispatchTouchEvent(DOWN_EVENT)

            assertThat(returnVal).isFalse()
        }

    @Test
    fun handleDispatchTouchEvent_downTouchBelowView_sendsTouchToSb() =
        testScope.runTest {
            underTest.setStatusBarViewController(phoneStatusBarViewController)
            val ev = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, VIEW_BOTTOM + 4f, 0)
            whenever(phoneStatusBarViewController.sendTouchToView(ev)).thenReturn(true)

            val returnVal = interactionEventHandler.handleDispatchTouchEvent(ev)

            verify(phoneStatusBarViewController).sendTouchToView(ev)
            assertThat(returnVal).isTrue()
        }

    @Test
    fun handleDispatchTouchEvent_downTouchBelowViewThenAnotherTouch_sendsTouchToSb() =
        testScope.runTest {
            underTest.setStatusBarViewController(phoneStatusBarViewController)
            val downEvBelow =
                MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, VIEW_BOTTOM + 4f, 0)
            interactionEventHandler.handleDispatchTouchEvent(downEvBelow)

            val nextEvent =
                MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_MOVE, 0f, VIEW_BOTTOM + 5f, 0)
            whenever(phoneStatusBarViewController.sendTouchToView(nextEvent)).thenReturn(true)

            val returnVal = interactionEventHandler.handleDispatchTouchEvent(nextEvent)

            verify(phoneStatusBarViewController).sendTouchToView(nextEvent)
            assertThat(returnVal).isTrue()
        }

    @Test
    fun handleDispatchTouchEvent_downAndPanelCollapsedAndInSbBoundAndSbWindowShow_sendsTouchToSb() =
        testScope.runTest {
            underTest.setStatusBarViewController(phoneStatusBarViewController)
            whenever(statusBarWindowStateController.windowIsShowing()).thenReturn(true)
            whenever(notificationPanelViewController.isFullyCollapsed).thenReturn(true)
            whenever(phoneStatusBarViewController.touchIsWithinView(anyFloat(), anyFloat()))
                .thenReturn(true)
            whenever(phoneStatusBarViewController.sendTouchToView(DOWN_EVENT)).thenReturn(true)

            val returnVal = interactionEventHandler.handleDispatchTouchEvent(DOWN_EVENT)

            verify(phoneStatusBarViewController).sendTouchToView(DOWN_EVENT)
            assertThat(returnVal).isTrue()
        }

    @Test
    fun handleDispatchTouchEvent_panelNotCollapsed_returnsNull() =
        testScope.runTest {
            underTest.setStatusBarViewController(phoneStatusBarViewController)
            whenever(statusBarWindowStateController.windowIsShowing()).thenReturn(true)
            whenever(phoneStatusBarViewController.touchIsWithinView(anyFloat(), anyFloat()))
                .thenReturn(true)
            // Item we're testing
            whenever(notificationPanelViewController.isFullyCollapsed).thenReturn(false)

            val returnVal = interactionEventHandler.handleDispatchTouchEvent(DOWN_EVENT)

            verify(phoneStatusBarViewController, never()).sendTouchToView(DOWN_EVENT)
            assertThat(returnVal).isNull()
        }

    @Test
    fun handleDispatchTouchEvent_touchNotInSbBounds_returnsNull() =
        testScope.runTest {
            underTest.setStatusBarViewController(phoneStatusBarViewController)
            whenever(statusBarWindowStateController.windowIsShowing()).thenReturn(true)
            whenever(notificationPanelViewController.isFullyCollapsed).thenReturn(true)
            // Item we're testing
            whenever(phoneStatusBarViewController.touchIsWithinView(anyFloat(), anyFloat()))
                .thenReturn(false)

            val returnVal = interactionEventHandler.handleDispatchTouchEvent(DOWN_EVENT)

            verify(phoneStatusBarViewController, never()).sendTouchToView(DOWN_EVENT)
            assertThat(returnVal).isNull()
        }

    @Test
    fun handleDispatchTouchEvent_sbWindowNotShowing_noSendTouchToSbAndReturnsTrue() =
        testScope.runTest {
            underTest.setStatusBarViewController(phoneStatusBarViewController)
            whenever(notificationPanelViewController.isFullyCollapsed).thenReturn(true)
            whenever(phoneStatusBarViewController.touchIsWithinView(anyFloat(), anyFloat()))
                .thenReturn(true)
            // Item we're testing
            whenever(statusBarWindowStateController.windowIsShowing()).thenReturn(false)

            val returnVal = interactionEventHandler.handleDispatchTouchEvent(DOWN_EVENT)

            verify(phoneStatusBarViewController, never()).sendTouchToView(DOWN_EVENT)
            assertThat(returnVal).isTrue()
        }

    @Test
    fun handleDispatchTouchEvent_downEventSentToSbThenAnotherEvent_sendsTouchToSb() =
        testScope.runTest {
            underTest.setStatusBarViewController(phoneStatusBarViewController)
            whenever(statusBarWindowStateController.windowIsShowing()).thenReturn(true)
            whenever(notificationPanelViewController.isFullyCollapsed).thenReturn(true)
            whenever(phoneStatusBarViewController.touchIsWithinView(anyFloat(), anyFloat()))
                .thenReturn(true)

            // Down event first
            interactionEventHandler.handleDispatchTouchEvent(DOWN_EVENT)

            // Then another event
            val nextEvent = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_MOVE, 0f, 0f, 0)
            whenever(phoneStatusBarViewController.sendTouchToView(nextEvent)).thenReturn(true)

            val returnVal = interactionEventHandler.handleDispatchTouchEvent(nextEvent)

            verify(phoneStatusBarViewController).sendTouchToView(nextEvent)
            assertThat(returnVal).isTrue()
        }

    @Test
    fun handleDispatchTouchEvent_launchAnimationRunningTimesOut() =
        testScope.runTest {
            // GIVEN touch dispatcher in a state that returns true
            underTest.setStatusBarViewController(phoneStatusBarViewController)
            whenever(keyguardUnlockAnimationController.isPlayingCannedUnlockAnimation())
                .thenReturn(true)
            assertThat(interactionEventHandler.handleDispatchTouchEvent(DOWN_EVENT)).isTrue()

            // WHEN launch animation is running for 2 seconds
            fakeClock.setUptimeMillis(10000)
            underTest.setExpandAnimationRunning(true)
            fakeClock.advanceTime(2000)

            // THEN touch is ignored
            assertThat(interactionEventHandler.handleDispatchTouchEvent(DOWN_EVENT)).isFalse()

            // WHEN Launch animation is running for 6 seconds
            fakeClock.advanceTime(4000)

            // THEN move is ignored, down is handled, and window is notified
            assertThat(interactionEventHandler.handleDispatchTouchEvent(MOVE_EVENT)).isFalse()
            assertThat(interactionEventHandler.handleDispatchTouchEvent(DOWN_EVENT)).isTrue()
            verify(notificationShadeWindowController).setLaunchingActivity(false)
        }

    @Test
    fun handleDispatchTouchEvent_nsslMigrationOff_userActivity_not_called() {
        mSetFlagsRule.disableFlags(Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
        underTest.setStatusBarViewController(phoneStatusBarViewController)

        interactionEventHandler.handleDispatchTouchEvent(DOWN_EVENT)

        verify(centralSurfaces, times(0)).userActivity()
    }

    @Test
    fun handleDispatchTouchEvent_nsslMigrationOn_userActivity() {
        mSetFlagsRule.enableFlags(Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
        underTest.setStatusBarViewController(phoneStatusBarViewController)

        interactionEventHandler.handleDispatchTouchEvent(DOWN_EVENT)

        verify(centralSurfaces).userActivity()
    }

    @Test
    fun handleDispatchTouchEvent_glanceableHubIntercepts_returnsTrue() {
        whenever(mGlanceableHubContainerController.onTouchEvent(DOWN_EVENT)).thenReturn(true)
        underTest.setStatusBarViewController(phoneStatusBarViewController)

        val returnVal = interactionEventHandler.handleDispatchTouchEvent(DOWN_EVENT)

        assertThat(returnVal).isTrue()
    }

    @Test
    fun shouldInterceptTouchEvent_statusBarKeyguardViewManagerShouldIntercept() {
        // down event should be intercepted by keyguardViewManager
        whenever(statusBarKeyguardViewManager.shouldInterceptTouchEvent(DOWN_EVENT))
            .thenReturn(true)

        // Then touch should not be intercepted
        val shouldIntercept = interactionEventHandler.shouldInterceptTouchEvent(DOWN_EVENT)
        assertThat(shouldIntercept).isTrue()
    }

    @Test
    fun shouldInterceptTouchEvent_dozing_touchInLockIconArea_touchNotIntercepted() {
        // GIVEN dozing
        whenever(sysuiStatusBarStateController.isDozing).thenReturn(true)
        // AND alternate bouncer doesn't want the touch
        whenever(statusBarKeyguardViewManager.shouldInterceptTouchEvent(DOWN_EVENT))
            .thenReturn(false)
        // AND quick settings controller doesn't want it
        whenever(quickSettingsController.shouldQuickSettingsIntercept(any(), any(), any()))
            .thenReturn(false)
        // AND the lock icon wants the touch
        whenever(lockIconViewController.willHandleTouchWhileDozing(DOWN_EVENT)).thenReturn(true)

        mSetFlagsRule.enableFlags(Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)

        // THEN touch should NOT be intercepted by NotificationShade
        assertThat(interactionEventHandler.shouldInterceptTouchEvent(DOWN_EVENT)).isFalse()
    }

    @Test
    fun shouldInterceptTouchEvent_dozing_touchNotInLockIconArea_touchIntercepted() {
        // GIVEN dozing
        whenever(sysuiStatusBarStateController.isDozing).thenReturn(true)
        // AND alternate bouncer doesn't want the touch
        whenever(statusBarKeyguardViewManager.shouldInterceptTouchEvent(DOWN_EVENT))
            .thenReturn(false)
        // AND the lock icon does NOT want the touch
        whenever(lockIconViewController.willHandleTouchWhileDozing(DOWN_EVENT)).thenReturn(false)
        // AND quick settings controller doesn't want it
        whenever(quickSettingsController.shouldQuickSettingsIntercept(any(), any(), any()))
            .thenReturn(false)

        mSetFlagsRule.enableFlags(Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)

        // THEN touch should be intercepted by NotificationShade
        assertThat(interactionEventHandler.shouldInterceptTouchEvent(DOWN_EVENT)).isTrue()
    }

    @Test
    fun shouldInterceptTouchEvent_dozing_touchInStatusBar_touchIntercepted() {
        // GIVEN dozing
        whenever(sysuiStatusBarStateController.isDozing).thenReturn(true)
        // AND alternate bouncer doesn't want the touch
        whenever(statusBarKeyguardViewManager.shouldInterceptTouchEvent(DOWN_EVENT))
            .thenReturn(false)
        // AND the lock icon does NOT want the touch
        whenever(lockIconViewController.willHandleTouchWhileDozing(DOWN_EVENT)).thenReturn(false)
        // AND quick settings controller DOES want it
        whenever(quickSettingsController.shouldQuickSettingsIntercept(any(), any(), any()))
            .thenReturn(true)

        mSetFlagsRule.enableFlags(Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)

        // THEN touch should be intercepted by NotificationShade
        assertThat(interactionEventHandler.shouldInterceptTouchEvent(DOWN_EVENT)).isTrue()
    }

    @Test
    fun shouldInterceptTouchEvent_dozingAndPulsing_touchIntercepted() {
        // GIVEN dozing
        whenever(sysuiStatusBarStateController.isDozing).thenReturn(true)
        // AND pulsing
        whenever(dozeServiceHost.isPulsing()).thenReturn(true)
        // AND status bar doesn't want it
        whenever(statusBarKeyguardViewManager.shouldInterceptTouchEvent(DOWN_EVENT))
            .thenReturn(false)
        // AND shade is not fully expanded
        whenever(notificationPanelViewController.isFullyExpanded()).thenReturn(false)
        // AND the lock icon does NOT want the touch
        whenever(lockIconViewController.willHandleTouchWhileDozing(DOWN_EVENT)).thenReturn(false)
        // AND quick settings controller DOES want it
        whenever(quickSettingsController.shouldQuickSettingsIntercept(any(), any(), any()))
            .thenReturn(true)
        // AND bouncer is not showing
        whenever(centralSurfaces.isBouncerShowing()).thenReturn(false)
        // AND panel view controller wants it
        whenever(notificationPanelViewController.handleExternalInterceptTouch(DOWN_EVENT))
            .thenReturn(true)

        mSetFlagsRule.enableFlags(Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)

        // THEN touch should be intercepted by NotificationShade
        assertThat(interactionEventHandler.shouldInterceptTouchEvent(DOWN_EVENT)).isTrue()
    }

    @Test
    fun testGetKeyguardMessageArea() =
        testScope.runTest {
            underTest.keyguardMessageArea
            verify(view).findViewById<ViewGroup>(R.id.keyguard_message_area)
        }

    @Test
    @Ignore("b/321332798")
    fun setsUpCommunalHubLayout_whenFlagEnabled() {
        whenever(mGlanceableHubContainerController.communalAvailable())
            .thenReturn(MutableStateFlow(true))

        val mockCommunalView = mock(View::class.java)
        whenever(mGlanceableHubContainerController.initView(any<Context>()))
            .thenReturn(mockCommunalView)

        val mockCommunalPlaceholder = mock(View::class.java)
        val fakeViewIndex = 20
        whenever(view.findViewById<View>(R.id.communal_ui_stub)).thenReturn(mockCommunalPlaceholder)
        whenever(view.indexOfChild(mockCommunalPlaceholder)).thenReturn(fakeViewIndex)
        whenever(view.context).thenReturn(context)

        underTest.setupCommunalHubLayout()

        // Communal view added as a child of the container at the proper index, the stub is removed.
        verify(view).removeView(mockCommunalPlaceholder)
        verify(view).addView(eq(mockCommunalView), eq(fakeViewIndex))
    }

    @Test
    fun doesNotSetupCommunalHubLayout_whenFlagDisabled() {
        whenever(mGlanceableHubContainerController.communalAvailable())
            .thenReturn(MutableStateFlow(false))

        val mockCommunalPlaceholder = mock(View::class.java)
        val fakeViewIndex = 20
        whenever(view.findViewById<View>(R.id.communal_ui_stub)).thenReturn(mockCommunalPlaceholder)
        whenever(view.indexOfChild(mockCommunalPlaceholder)).thenReturn(fakeViewIndex)
        whenever(view.context).thenReturn(context)

        underTest.setupCommunalHubLayout()

        // No adding of views occurs.
        verify(view, times(0)).addView(any(), eq(fakeViewIndex))
    }

    @Test
    fun forwardsDispatchKeyEvent() {
        val keyEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_B)
        interactionEventHandler.dispatchKeyEvent(keyEvent)
        verify(sysUIKeyEventHandler).dispatchKeyEvent(keyEvent)
    }

    @Test
    fun forwardsDispatchKeyEventPreIme() {
        val keyEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_B)
        interactionEventHandler.dispatchKeyEventPreIme(keyEvent)
        verify(sysUIKeyEventHandler).dispatchKeyEventPreIme(keyEvent)
    }

    @Test
    fun forwardsInterceptMediaKey() {
        val keyEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)
        interactionEventHandler.interceptMediaKey(keyEvent)
        verify(sysUIKeyEventHandler).interceptMediaKey(keyEvent)
    }

    companion object {
        private val DOWN_EVENT = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
        private val MOVE_EVENT = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_MOVE, 0f, 0f, 0)
        private const val VIEW_BOTTOM = 100
    }
}

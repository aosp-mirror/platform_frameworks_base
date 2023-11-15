/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.os.Handler
import android.os.SystemClock
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardMessageAreaController
import com.android.keyguard.KeyguardSecurityContainerController
import com.android.keyguard.KeyguardSecurityModel
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.LockIconViewController
import com.android.keyguard.dagger.KeyguardBouncerComponent
import com.android.systemui.SysuiTestCase
import com.android.systemui.back.domain.interactor.BackActionInteractor
import com.android.systemui.biometrics.data.repository.FakeFacePropertyRepository
import com.android.systemui.bouncer.data.repository.BouncerMessageRepositoryImpl
import com.android.systemui.bouncer.data.repository.FakeKeyguardBouncerRepository
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor
import com.android.systemui.bouncer.domain.interactor.BouncerMessageInteractor
import com.android.systemui.bouncer.domain.interactor.CountDownTimerUtil
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerCallbackInteractor
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor
import com.android.systemui.bouncer.ui.BouncerView
import com.android.systemui.bouncer.ui.viewmodel.KeyguardBouncerViewModel
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.classifier.FalsingCollectorFake
import com.android.systemui.communal.data.repository.FakeCommunalRepository
import com.android.systemui.communal.ui.viewmodel.CommunalViewModel
import com.android.systemui.dock.DockManager
import com.android.systemui.dump.DumpManager
import com.android.systemui.dump.logcatLogBuffer
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.flags.SystemPropertiesHelper
import com.android.systemui.keyevent.domain.interactor.SysUIKeyEventHandler
import com.android.systemui.keyguard.DismissCallbackRegistry
import com.android.systemui.keyguard.KeyguardUnlockAnimationController
import com.android.systemui.keyguard.data.repository.FakeBiometricSettingsRepository
import com.android.systemui.keyguard.data.repository.FakeDeviceEntryFaceAuthRepository
import com.android.systemui.keyguard.data.repository.FakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.FakeTrustRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.ui.viewmodel.PrimaryBouncerToGoneTransitionViewModel
import com.android.systemui.log.BouncerLogger
import com.android.systemui.power.domain.interactor.PowerInteractor
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
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.phone.CentralSurfaces
import com.android.systemui.statusbar.phone.DozeScrimController
import com.android.systemui.statusbar.phone.DozeServiceHost
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.statusbar.window.StatusBarWindowStateController
import com.android.systemui.unfold.UnfoldTransitionProgressProvider
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import java.util.Optional
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@RunWithLooper(setAsMainLooper = true)
@SmallTest
class NotificationShadeWindowViewTest : SysuiTestCase() {

    @Mock private lateinit var dragDownHelper: DragDownHelper
    @Mock private lateinit var statusBarStateController: SysuiStatusBarStateController
    @Mock private lateinit var shadeController: ShadeController
    @Mock private lateinit var centralSurfaces: CentralSurfaces
    @Mock private lateinit var dozeServiceHost: DozeServiceHost
    @Mock private lateinit var dozeScrimController: DozeScrimController
    @Mock private lateinit var backActionInteractor: BackActionInteractor
    @Mock private lateinit var powerInteractor: PowerInteractor
    @Mock private lateinit var dockManager: DockManager
    @Mock private lateinit var notificationPanelViewController: NotificationPanelViewController
    @Mock private lateinit var notificationStackScrollLayout: NotificationStackScrollLayout
    @Mock private lateinit var notificationShadeDepthController: NotificationShadeDepthController
    @Mock private lateinit var notificationShadeWindowController: NotificationShadeWindowController
    @Mock private lateinit var quickSettingsController: QuickSettingsController
    @Mock
    private lateinit var notificationStackScrollLayoutController:
        NotificationStackScrollLayoutController
    @Mock private lateinit var statusBarKeyguardViewManager: StatusBarKeyguardViewManager
    @Mock private lateinit var statusBarWindowStateController: StatusBarWindowStateController
    @Mock
    private lateinit var lockscreenShadeTransitionController: LockscreenShadeTransitionController
    @Mock private lateinit var lockIconViewController: LockIconViewController
    @Mock private lateinit var keyguardUnlockAnimationController: KeyguardUnlockAnimationController
    @Mock private lateinit var ambientState: AmbientState
    @Mock private lateinit var shadeLogger: ShadeLogger
    @Mock private lateinit var dumpManager: DumpManager
    @Mock private lateinit var pulsingGestureListener: PulsingGestureListener
    @Mock
    private lateinit var mLockscreenHostedDreamGestureListener: LockscreenHostedDreamGestureListener
    @Mock private lateinit var keyguardBouncerViewModel: KeyguardBouncerViewModel
    @Mock private lateinit var keyguardBouncerComponentFactory: KeyguardBouncerComponent.Factory
    @Mock private lateinit var keyguardBouncerComponent: KeyguardBouncerComponent
    @Mock
    private lateinit var keyguardSecurityContainerController: KeyguardSecurityContainerController
    @Mock
    private lateinit var unfoldTransitionProgressProvider:
        Optional<UnfoldTransitionProgressProvider>
    @Mock private lateinit var notificationInsetsController: NotificationInsetsController
    @Mock private lateinit var mCommunalViewModel: CommunalViewModel
    private lateinit var mCommunalRepository: FakeCommunalRepository
    @Mock private lateinit var keyguardTransitionInteractor: KeyguardTransitionInteractor
    @Mock lateinit var primaryBouncerInteractor: PrimaryBouncerInteractor
    @Mock lateinit var alternateBouncerInteractor: AlternateBouncerInteractor
    @Mock private lateinit var mSelectedUserInteractor: SelectedUserInteractor
    @Mock
    private lateinit var primaryBouncerToGoneTransitionViewModel:
        PrimaryBouncerToGoneTransitionViewModel
    @Captor
    private lateinit var interactionEventHandlerCaptor: ArgumentCaptor<InteractionEventHandler>

    private lateinit var underTest: NotificationShadeWindowView
    private lateinit var controller: NotificationShadeWindowViewController
    private lateinit var interactionEventHandler: InteractionEventHandler
    private lateinit var testScope: TestScope

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        underTest = spy(NotificationShadeWindowView(context, null))
        whenever(
                underTest.findViewById<NotificationStackScrollLayout>(
                    R.id.notification_stack_scroller
                )
            )
            .thenReturn(notificationStackScrollLayout)
        whenever(underTest.findViewById<FrameLayout>(R.id.keyguard_bouncer_container))
            .thenReturn(mock())
        whenever(keyguardBouncerComponentFactory.create(any())).thenReturn(keyguardBouncerComponent)
        whenever(keyguardBouncerComponent.securityContainerController)
            .thenReturn(keyguardSecurityContainerController)
        whenever(statusBarStateController.isDozing).thenReturn(false)
        mDependency.injectTestDependency(ShadeController::class.java, shadeController)
        whenever(dockManager.isDocked).thenReturn(false)
        whenever(keyguardTransitionInteractor.lockscreenToDreamingTransition)
            .thenReturn(emptyFlow())

        mCommunalRepository = FakeCommunalRepository()

        val featureFlags = FakeFeatureFlags()
        featureFlags.set(Flags.TRACKPAD_GESTURE_COMMON, true)
        featureFlags.set(Flags.TRACKPAD_GESTURE_FEATURES, false)
        featureFlags.set(Flags.SPLIT_SHADE_SUBPIXEL_OPTIMIZATION, true)
        featureFlags.set(Flags.REVAMPED_BOUNCER_MESSAGES, true)
        featureFlags.set(Flags.LOCKSCREEN_WALLPAPER_DREAM_ENABLED, false)
        featureFlags.set(Flags.ALTERNATE_BOUNCER_VIEW, false)
        testScope = TestScope()
        controller =
            NotificationShadeWindowViewController(
                lockscreenShadeTransitionController,
                FalsingCollectorFake(),
                statusBarStateController,
                dockManager,
                notificationShadeDepthController,
                underTest,
                notificationPanelViewController,
                ShadeExpansionStateManager(),
                notificationStackScrollLayoutController,
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
                keyguardBouncerViewModel,
                keyguardBouncerComponentFactory,
                Mockito.mock(KeyguardMessageAreaController.Factory::class.java),
                keyguardTransitionInteractor,
                primaryBouncerToGoneTransitionViewModel,
                mCommunalViewModel,
                mCommunalRepository,
                NotificationLaunchAnimationInteractor(NotificationLaunchAnimationRepository()),
                featureFlags,
                FakeSystemClock(),
                BouncerMessageInteractor(
                    repository = BouncerMessageRepositoryImpl(),
                    userRepository = FakeUserRepository(),
                    countDownTimerUtil = Mockito.mock(CountDownTimerUtil::class.java),
                    featureFlags = featureFlags,
                    updateMonitor = Mockito.mock(KeyguardUpdateMonitor::class.java),
                    biometricSettingsRepository = FakeBiometricSettingsRepository(),
                    applicationScope = testScope.backgroundScope,
                    trustRepository = FakeTrustRepository(),
                    systemPropertiesHelper = Mockito.mock(SystemPropertiesHelper::class.java),
                    primaryBouncerInteractor =
                        PrimaryBouncerInteractor(
                            FakeKeyguardBouncerRepository(),
                            Mockito.mock(BouncerView::class.java),
                            Mockito.mock(Handler::class.java),
                            Mockito.mock(KeyguardStateController::class.java),
                            Mockito.mock(KeyguardSecurityModel::class.java),
                            Mockito.mock(PrimaryBouncerCallbackInteractor::class.java),
                            Mockito.mock(FalsingCollector::class.java),
                            Mockito.mock(DismissCallbackRegistry::class.java),
                            context,
                            Mockito.mock(KeyguardUpdateMonitor::class.java),
                            FakeTrustRepository(),
                            testScope.backgroundScope,
                            mSelectedUserInteractor,
                        ),
                    facePropertyRepository = FakeFacePropertyRepository(),
                    deviceEntryFingerprintAuthRepository =
                        FakeDeviceEntryFingerprintAuthRepository(),
                    faceAuthRepository = FakeDeviceEntryFaceAuthRepository(),
                    securityModel = Mockito.mock(KeyguardSecurityModel::class.java),
                ),
                BouncerLogger(logcatLogBuffer("BouncerLog")),
                Mockito.mock(SysUIKeyEventHandler::class.java),
                quickSettingsController,
                primaryBouncerInteractor,
                alternateBouncerInteractor,
                mSelectedUserInteractor,
            )

        controller.setupExpandedStatusBar()
        controller.setDragDownHelper(dragDownHelper)
    }

    @Test
    fun testDragDownHelperCalledWhenDraggingDown() =
        testScope.runTest {
            whenever(dragDownHelper.isDraggingDown).thenReturn(true)
            val now = SystemClock.elapsedRealtime()
            val ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, 0f, 0f, 0 /* meta */)
            underTest.onTouchEvent(ev)
            verify(dragDownHelper).onTouchEvent(ev)
            ev.recycle()
        }

    @Test
    fun testInterceptTouchWhenShowingAltAuth() =
        testScope.runTest {
            captureInteractionEventHandler()

            // WHEN showing alt auth, not dozing, drag down helper doesn't want to intercept
            whenever(statusBarStateController.isDozing).thenReturn(false)
            whenever(statusBarKeyguardViewManager.shouldInterceptTouchEvent(any())).thenReturn(true)
            whenever(dragDownHelper.onInterceptTouchEvent(any())).thenReturn(false)

            // THEN we should intercept touch
            assertThat(interactionEventHandler.shouldInterceptTouchEvent(mock())).isTrue()
        }

    @Test
    fun testNoInterceptTouch() =
        testScope.runTest {
            captureInteractionEventHandler()

            // WHEN not showing alt auth, not dozing, drag down helper doesn't want to intercept
            whenever(statusBarStateController.isDozing).thenReturn(false)
            whenever(statusBarKeyguardViewManager.shouldInterceptTouchEvent(any()))
                .thenReturn(false)
            whenever(dragDownHelper.onInterceptTouchEvent(any())).thenReturn(false)

            // THEN we shouldn't intercept touch
            assertThat(interactionEventHandler.shouldInterceptTouchEvent(mock())).isFalse()
        }

    @Test
    fun testHandleTouchEventWhenShowingAltAuth() =
        testScope.runTest {
            captureInteractionEventHandler()

            // WHEN showing alt auth, not dozing, drag down helper doesn't want to intercept
            whenever(statusBarStateController.isDozing).thenReturn(false)
            whenever(statusBarKeyguardViewManager.onTouch(any())).thenReturn(true)
            whenever(dragDownHelper.onInterceptTouchEvent(any())).thenReturn(false)

            // THEN we should handle the touch
            assertThat(interactionEventHandler.handleTouchEvent(mock())).isTrue()
        }

    private fun captureInteractionEventHandler() {
        verify(underTest).setInteractionEventHandler(interactionEventHandlerCaptor.capture())
        interactionEventHandler = interactionEventHandlerCaptor.value
    }
}

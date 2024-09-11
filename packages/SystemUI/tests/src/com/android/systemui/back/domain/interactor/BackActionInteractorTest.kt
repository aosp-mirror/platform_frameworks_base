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

package com.android.systemui.back.domain.interactor

import android.platform.test.annotations.RequiresFlagsDisabled
import android.platform.test.annotations.RequiresFlagsEnabled
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.view.ViewRootImpl
import android.window.BackEvent
import android.window.BackEvent.EDGE_LEFT
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.window.WindowOnBackInvokedDispatcher
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.statusbar.IStatusBarService
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAsleepForTest
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAwakeForTest
import com.android.systemui.power.domain.interactor.PowerInteractorFactory
import com.android.systemui.scene.data.repository.WindowRootViewVisibilityRepository
import com.android.systemui.scene.domain.interactor.WindowRootViewVisibilityInteractor
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.ui.view.WindowRootView
import com.android.systemui.shade.QuickSettingsController
import com.android.systemui.shade.ShadeController
import com.android.systemui.shade.domain.interactor.ShadeBackActionInteractor
import com.android.systemui.statusbar.NotificationShadeWindowController
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.notification.data.repository.ActiveNotificationListRepository
import com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsInteractor
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import com.android.systemui.statusbar.policy.HeadsUpManager
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit

@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BackActionInteractorTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val testScope = kosmos.testScope
    private val executor = FakeExecutor(FakeSystemClock())

    @JvmField @Rule var mockitoRule = MockitoJUnit.rule()

    @Mock private lateinit var statusBarStateController: StatusBarStateController
    @Mock private lateinit var statusBarKeyguardViewManager: StatusBarKeyguardViewManager
    @Mock private lateinit var shadeController: ShadeController
    @Mock private lateinit var qsController: QuickSettingsController
    @Mock private lateinit var shadeBackActionInteractor: ShadeBackActionInteractor
    @Mock private lateinit var notificationShadeWindowController: NotificationShadeWindowController
    @Mock private lateinit var windowRootView: WindowRootView
    @Mock private lateinit var viewRootImpl: ViewRootImpl
    @Mock private lateinit var onBackInvokedDispatcher: WindowOnBackInvokedDispatcher
    @Mock private lateinit var iStatusBarService: IStatusBarService
    @Mock private lateinit var headsUpManager: HeadsUpManager
    private val activeNotificationsRepository = ActiveNotificationListRepository()
    private val activeNotificationsInteractor =
        ActiveNotificationsInteractor(activeNotificationsRepository, StandardTestDispatcher())

    private val keyguardRepository = FakeKeyguardRepository()
    private val windowRootViewVisibilityInteractor: WindowRootViewVisibilityInteractor by lazy {
        WindowRootViewVisibilityInteractor(
            testScope.backgroundScope,
            WindowRootViewVisibilityRepository(iStatusBarService, executor),
            keyguardRepository,
            headsUpManager,
            powerInteractor,
            activeNotificationsInteractor,
            kosmos::sceneInteractor,
        )
    }

    private val backActionInteractor: BackActionInteractor by lazy {
        BackActionInteractor(
                testScope.backgroundScope,
                statusBarStateController,
                statusBarKeyguardViewManager,
                shadeController,
                notificationShadeWindowController,
                windowRootViewVisibilityInteractor
            )
            .apply { this.setup(qsController, shadeBackActionInteractor) }
    }

    private val powerInteractor = PowerInteractorFactory.create().powerInteractor

    @get:Rule val checkFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()

    @Before
    fun setUp() {
        whenever(notificationShadeWindowController.windowRootView).thenReturn(windowRootView)
        whenever(windowRootView.viewRootImpl).thenReturn(viewRootImpl)
        whenever(viewRootImpl.onBackInvokedDispatcher).thenReturn(onBackInvokedDispatcher)
    }

    @Test
    fun testOnBackRequested_keyguardCanHandleBackPressed() {
        whenever(statusBarKeyguardViewManager.canHandleBackPressed()).thenReturn(true)

        val result = backActionInteractor.onBackRequested()

        assertTrue(result)
        verify(statusBarKeyguardViewManager, atLeastOnce()).onBackPressed()
    }

    @Test
    fun testOnBackRequested_quickSettingsIsCustomizing() {
        whenever(qsController.isCustomizing).thenReturn(true)

        val result = backActionInteractor.onBackRequested()

        assertTrue(result)
        verify(qsController, atLeastOnce()).closeQsCustomizer()
        verify(statusBarKeyguardViewManager, never()).onBackPressed()
    }

    @Test
    fun testOnBackRequested_quickSettingsExpanded() {
        whenever(qsController.expanded).thenReturn(true)

        val result = backActionInteractor.onBackRequested()

        assertTrue(result)
        verify(shadeBackActionInteractor, atLeastOnce()).animateCollapseQs(anyBoolean())
        verify(statusBarKeyguardViewManager, never()).onBackPressed()
    }

    @Test
    fun testOnBackRequested_closeUserSwitcherIfOpen() {
        whenever(shadeBackActionInteractor.closeUserSwitcherIfOpen()).thenReturn(true)

        val result = backActionInteractor.onBackRequested()

        assertTrue(result)
        verify(statusBarKeyguardViewManager, never()).onBackPressed()
        verify(shadeBackActionInteractor, never()).animateCollapseQs(anyBoolean())
    }

    @Test
    fun testOnBackRequested_returnsFalse() {
        // make shouldBackBeHandled return false
        whenever(statusBarStateController.state).thenReturn(StatusBarState.KEYGUARD)

        val result = backActionInteractor.onBackRequested()

        assertFalse(result)
        verify(statusBarKeyguardViewManager, never()).onBackPressed()
        verify(shadeBackActionInteractor, never()).animateCollapseQs(anyBoolean())
    }

    @Test
    fun shadeVisibleAndDeviceAwake_callbackRegistered() {
        backActionInteractor.start()
        windowRootViewVisibilityInteractor.setIsLockscreenOrShadeVisible(true)
        powerInteractor.setAwakeForTest()

        testScope.runCurrent()

        verify(onBackInvokedDispatcher)
            .registerOnBackInvokedCallback(eq(OnBackInvokedDispatcher.PRIORITY_DEFAULT), any())
    }

    @Test
    fun noWindowRootView_noCrashAttemptingCallbackRegistration() {
        whenever(notificationShadeWindowController.windowRootView).thenReturn(null)

        backActionInteractor.start()
        windowRootViewVisibilityInteractor.setIsLockscreenOrShadeVisible(true)
        powerInteractor.setAwakeForTest()

        testScope.runCurrent()
        // No assert necessary, just testing no crash
    }

    @Test
    fun shadeNotVisible_callbackUnregistered() {
        backActionInteractor.start()
        windowRootViewVisibilityInteractor.setIsLockscreenOrShadeVisible(true)
        powerInteractor.setAwakeForTest()
        val callback = getBackInvokedCallback()

        windowRootViewVisibilityInteractor.setIsLockscreenOrShadeVisible(false)
        testScope.runCurrent()

        verify(onBackInvokedDispatcher).unregisterOnBackInvokedCallback(callback)
    }

    @Test
    fun deviceAsleep_callbackUnregistered() {
        backActionInteractor.start()
        windowRootViewVisibilityInteractor.setIsLockscreenOrShadeVisible(true)
        powerInteractor.setAwakeForTest()
        val callback = getBackInvokedCallback()

        powerInteractor.setAsleepForTest()
        testScope.runCurrent()

        verify(onBackInvokedDispatcher).unregisterOnBackInvokedCallback(callback)
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_PREDICTIVE_BACK_ANIMATE_SHADE)
    fun animationFlagOff_onBackInvoked_keyguardNotified() {
        backActionInteractor.start()
        windowRootViewVisibilityInteractor.setIsLockscreenOrShadeVisible(true)
        powerInteractor.setAwakeForTest()
        val callback = getBackInvokedCallback()
        whenever(statusBarKeyguardViewManager.canHandleBackPressed()).thenReturn(true)

        callback.onBackInvoked()

        verify(statusBarKeyguardViewManager).onBackPressed()
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_PREDICTIVE_BACK_ANIMATE_SHADE)
    fun animationFlagOn_onBackInvoked_keyguardNotified() {
        backActionInteractor.start()
        windowRootViewVisibilityInteractor.setIsLockscreenOrShadeVisible(true)
        powerInteractor.setAwakeForTest()
        val callback = getBackInvokedCallback()
        whenever(statusBarKeyguardViewManager.canHandleBackPressed()).thenReturn(true)

        callback.onBackInvoked()

        verify(statusBarKeyguardViewManager).onBackPressed()
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_PREDICTIVE_BACK_ANIMATE_SHADE)
    fun animationFlagOn_callbackIsAnimationCallback() {
        backActionInteractor.start()
        windowRootViewVisibilityInteractor.setIsLockscreenOrShadeVisible(true)
        powerInteractor.setAwakeForTest()

        val callback = getBackInvokedCallback()

        assertThat(callback).isInstanceOf(OnBackAnimationCallback::class.java)
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_PREDICTIVE_BACK_ANIMATE_SHADE)
    fun onBackProgressed_shadeCannotBeCollapsed_shadeViewControllerNotNotified() {
        backActionInteractor.start()
        windowRootViewVisibilityInteractor.setIsLockscreenOrShadeVisible(true)
        powerInteractor.setAwakeForTest()
        val callback = getBackInvokedCallback() as OnBackAnimationCallback

        whenever(shadeBackActionInteractor.canBeCollapsed()).thenReturn(false)

        callback.onBackProgressed(createBackEvent(0.3f))

        verify(shadeBackActionInteractor, never()).onBackProgressed(0.3f)
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_PREDICTIVE_BACK_ANIMATE_SHADE)
    fun onBackProgressed_shadeCanBeCollapsed_shadeViewControllerNotified() {
        backActionInteractor.start()
        windowRootViewVisibilityInteractor.setIsLockscreenOrShadeVisible(true)
        powerInteractor.setAwakeForTest()
        val callback = getBackInvokedCallback() as OnBackAnimationCallback

        whenever(shadeBackActionInteractor.canBeCollapsed()).thenReturn(true)

        callback.onBackProgressed(createBackEvent(0.4f))

        verify(shadeBackActionInteractor).onBackProgressed(0.4f)
    }

    private fun getBackInvokedCallback(): OnBackInvokedCallback {
        testScope.runCurrent()
        val captor = argumentCaptor<OnBackInvokedCallback>()
        verify(onBackInvokedDispatcher).registerOnBackInvokedCallback(any(), captor.capture())
        return captor.value!!
    }

    private fun createBackEvent(progress: Float): BackEvent =
        BackEvent(/* touchX= */ 0f, /* touchY= */ 0f, progress, /* swipeEdge= */ EDGE_LEFT)
}

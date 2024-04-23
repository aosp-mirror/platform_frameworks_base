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
 */
package com.android.systemui.dreams

import android.content.ComponentName
import android.content.Intent
import android.os.RemoteException
import android.service.dreams.IDreamOverlay
import android.service.dreams.IDreamOverlayCallback
import android.service.dreams.IDreamOverlayClient
import android.service.dreams.IDreamOverlayClientCallback
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowManagerImpl
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.UiEventLogger
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.SysuiTestCase
import com.android.systemui.ambient.touch.TouchMonitor
import com.android.systemui.ambient.touch.dagger.AmbientTouchComponent
import com.android.systemui.complication.ComplicationHostViewController
import com.android.systemui.complication.ComplicationLayoutEngine
import com.android.systemui.complication.dagger.ComplicationComponent
import com.android.systemui.dreams.complication.HideComplicationTouchHandler
import com.android.systemui.dreams.dagger.DreamOverlayComponent
import com.android.systemui.touch.TouchInsetManager
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations
import org.mockito.invocation.InvocationOnMock

@SmallTest
@RunWith(AndroidJUnit4::class)
class DreamOverlayServiceTest : SysuiTestCase() {
    private val mFakeSystemClock = FakeSystemClock()
    private val mMainExecutor = FakeExecutor(mFakeSystemClock)

    @Mock lateinit var mLifecycleOwner: DreamOverlayLifecycleOwner

    @Mock lateinit var mLifecycleRegistry: LifecycleRegistry

    lateinit var mWindowParams: WindowManager.LayoutParams

    @Mock lateinit var mDreamOverlayCallback: IDreamOverlayCallback

    @Mock lateinit var mWindowManager: WindowManagerImpl

    @Mock lateinit var mComplicationComponentFactory: ComplicationComponent.Factory

    @Mock lateinit var mComplicationComponent: ComplicationComponent

    @Mock lateinit var mComplicationHostViewController: ComplicationHostViewController

    @Mock lateinit var mComplicationVisibilityController: ComplicationLayoutEngine

    @Mock
    lateinit var mDreamComplicationComponentFactory:
        com.android.systemui.dreams.complication.dagger.ComplicationComponent.Factory

    @Mock
    lateinit var mDreamComplicationComponent:
        com.android.systemui.dreams.complication.dagger.ComplicationComponent

    @Mock lateinit var mHideComplicationTouchHandler: HideComplicationTouchHandler

    @Mock lateinit var mDreamOverlayComponentFactory: DreamOverlayComponent.Factory

    @Mock lateinit var mDreamOverlayComponent: DreamOverlayComponent

    @Mock lateinit var mAmbientTouchComponentFactory: AmbientTouchComponent.Factory

    @Mock lateinit var mAmbientTouchComponent: AmbientTouchComponent

    @Mock lateinit var mDreamOverlayContainerView: DreamOverlayContainerView

    @Mock lateinit var mDreamOverlayContainerViewController: DreamOverlayContainerViewController

    @Mock lateinit var mKeyguardUpdateMonitor: KeyguardUpdateMonitor

    @Mock lateinit var mTouchMonitor: TouchMonitor

    @Mock lateinit var mStateController: DreamOverlayStateController

    @Mock lateinit var mDreamOverlayContainerViewParent: ViewGroup

    @Mock lateinit var mTouchInsetManager: TouchInsetManager

    @Mock lateinit var mUiEventLogger: UiEventLogger

    @Mock lateinit var mDreamOverlayCallbackController: DreamOverlayCallbackController

    @Captor var mViewCaptor: ArgumentCaptor<View>? = null
    var mService: DreamOverlayService? = null
    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        whenever(mDreamOverlayComponent.getDreamOverlayContainerViewController())
            .thenReturn(mDreamOverlayContainerViewController)
        whenever(mComplicationComponent.getComplicationHostViewController())
            .thenReturn(mComplicationHostViewController)
        whenever(mLifecycleOwner.registry).thenReturn(mLifecycleRegistry)
        whenever(mComplicationComponentFactory.create(any(), any(), any(), any()))
            .thenReturn(mComplicationComponent)
        whenever(mComplicationComponent.getVisibilityController())
            .thenReturn(mComplicationVisibilityController)
        whenever(mDreamComplicationComponent.getHideComplicationTouchHandler())
            .thenReturn(mHideComplicationTouchHandler)
        whenever(mDreamComplicationComponentFactory.create(any(), any()))
            .thenReturn(mDreamComplicationComponent)
        whenever(mDreamOverlayComponentFactory.create(any(), any(), any()))
            .thenReturn(mDreamOverlayComponent)
        whenever(mAmbientTouchComponentFactory.create(any(), any()))
            .thenReturn(mAmbientTouchComponent)
        whenever(mAmbientTouchComponent.getTouchMonitor()).thenReturn(mTouchMonitor)
        whenever(mDreamOverlayContainerViewController.containerView)
            .thenReturn(mDreamOverlayContainerView)
        mWindowParams = WindowManager.LayoutParams()
        mService =
            DreamOverlayService(
                mContext,
                mLifecycleOwner,
                mMainExecutor,
                mWindowManager,
                mComplicationComponentFactory,
                mDreamComplicationComponentFactory,
                mDreamOverlayComponentFactory,
                mAmbientTouchComponentFactory,
                mStateController,
                mKeyguardUpdateMonitor,
                mUiEventLogger,
                mTouchInsetManager,
                LOW_LIGHT_COMPONENT,
                HOME_CONTROL_PANEL_DREAM_COMPONENT,
                mDreamOverlayCallbackController,
                WINDOW_NAME
            )
    }

    @get:Throws(RemoteException::class)
    val client: IDreamOverlayClient
        get() {
            val proxy = mService!!.onBind(Intent())
            val overlay = IDreamOverlay.Stub.asInterface(proxy)
            val callback = Mockito.mock(IDreamOverlayClientCallback::class.java)
            overlay.getClient(callback)
            val clientCaptor = ArgumentCaptor.forClass(IDreamOverlayClient::class.java)
            Mockito.verify(callback).onDreamOverlayClient(clientCaptor.capture())
            return clientCaptor.value
        }

    @Test
    fun testOnStartMetricsLogged() {
        val client = client

        // Inform the overlay service of dream starting.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            DREAM_COMPONENT,
            false /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()
        Mockito.verify(mUiEventLogger)
            .log(DreamOverlayService.DreamOverlayEvent.DREAM_OVERLAY_ENTER_START)
        Mockito.verify(mUiEventLogger)
            .log(DreamOverlayService.DreamOverlayEvent.DREAM_OVERLAY_COMPLETE_START)
    }

    @Test
    fun testOverlayContainerViewAddedToWindow() {
        val client = client

        // Inform the overlay service of dream starting.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            DREAM_COMPONENT,
            false /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()
        Mockito.verify(mWindowManager).addView(any(), any())
    }

    // Validates that {@link DreamOverlayService} properly handles the case where the dream's
    // window is no longer valid by the time start is called.
    @Test
    fun testInvalidWindowAddStart() {
        val client = client
        Mockito.doThrow(WindowManager.BadTokenException())
            .`when`(mWindowManager)
            .addView(any(), any())
        // Inform the overlay service of dream starting.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            DREAM_COMPONENT,
            false /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()
        Mockito.verify(mWindowManager).addView(any(), any())
        Mockito.verify(mStateController).setOverlayActive(false)
        Mockito.verify(mStateController).setLowLightActive(false)
        Mockito.verify(mStateController).setEntryAnimationsFinished(false)
        Mockito.verify(mStateController, Mockito.never()).setOverlayActive(true)
        Mockito.verify(mUiEventLogger, Mockito.never())
            .log(DreamOverlayService.DreamOverlayEvent.DREAM_OVERLAY_COMPLETE_START)
        Mockito.verify(mDreamOverlayCallbackController, Mockito.never()).onStartDream()
    }

    @Test
    fun testDreamOverlayContainerViewControllerInitialized() {
        val client = client

        // Inform the overlay service of dream starting.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            DREAM_COMPONENT,
            false /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()
        Mockito.verify(mDreamOverlayContainerViewController).init()
    }

    @Test
    fun testDreamOverlayContainerViewRemovedFromOldParentWhenInitialized() {
        whenever(mDreamOverlayContainerView.parent)
            .thenReturn(mDreamOverlayContainerViewParent)
            .thenReturn(null)
        val client = client

        // Inform the overlay service of dream starting.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            DREAM_COMPONENT,
            false /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()
        Mockito.verify(mDreamOverlayContainerViewParent).removeView(mDreamOverlayContainerView)
    }

    @Test
    fun testShouldShowComplicationsSetByStartDream() {
        val client = client

        // Inform the overlay service of dream starting.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            DREAM_COMPONENT,
            true /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()
        Truth.assertThat(mService!!.shouldShowComplications()).isTrue()
    }

    @Test
    fun testLowLightSetByStartDream() {
        val client = client

        // Inform the overlay service of dream starting.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            LOW_LIGHT_COMPONENT.flattenToString(),
            false /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()
        Truth.assertThat(mService!!.dreamComponent).isEqualTo(LOW_LIGHT_COMPONENT)
        Mockito.verify(mStateController).setLowLightActive(true)
    }

    @Test
    fun testHomeControlPanelSetsByStartDream() {
        val client = client

        // Inform the overlay service of dream starting.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            HOME_CONTROL_PANEL_DREAM_COMPONENT.flattenToString(),
            false /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()
        Truth.assertThat(mService!!.dreamComponent).isEqualTo(HOME_CONTROL_PANEL_DREAM_COMPONENT)
        Mockito.verify(mStateController).setHomeControlPanelActive(true)
    }

    @Test
    fun testOnEndDream() {
        val client = client

        // Inform the overlay service of dream starting.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            LOW_LIGHT_COMPONENT.flattenToString(),
            false /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()

        // Verify view added.
        Mockito.verify(mWindowManager).addView(mViewCaptor!!.capture(), any())

        // Service destroyed.
        mService!!.onEndDream()
        mMainExecutor.runAllReady()

        // Verify view removed.
        Mockito.verify(mWindowManager).removeView(mViewCaptor!!.value)

        // Verify state correctly set.
        Mockito.verify(mStateController).setOverlayActive(false)
        Mockito.verify(mStateController).setLowLightActive(false)
        Mockito.verify(mStateController).setEntryAnimationsFinished(false)
    }

    @Test
    fun testImmediateEndDream() {
        val client = client

        // Start the dream, but don't execute any Runnables put on the executor yet. We delay
        // executing Runnables as the timing isn't guaranteed and we want to verify that the overlay
        // starts and finishes in the proper order even if Runnables are delayed.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            DREAM_COMPONENT,
            false /*shouldShowComplication*/
        )
        // Immediately end the dream.
        client.endDream()
        // Run any scheduled Runnables.
        mMainExecutor.runAllReady()

        // The overlay starts then finishes.
        val inOrder = Mockito.inOrder(mWindowManager)
        inOrder.verify(mWindowManager).addView(mViewCaptor!!.capture(), any())
        inOrder.verify(mWindowManager).removeView(mViewCaptor!!.value)
    }

    @Test
    fun testEndDreamDuringStartDream() {
        val client = client

        // Schedule the endDream call in the middle of the startDream implementation, as any
        // ordering is possible.
        Mockito.doAnswer { invocation: InvocationOnMock? ->
                client.endDream()
                null
            }
            .`when`(mStateController)
            .setOverlayActive(true)

        // Start the dream.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            DREAM_COMPONENT,
            false /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()

        // The overlay starts then finishes.
        val inOrder = Mockito.inOrder(mWindowManager)
        inOrder.verify(mWindowManager).addView(mViewCaptor!!.capture(), any())
        inOrder.verify(mWindowManager).removeView(mViewCaptor!!.value)
    }

    @Test
    fun testDestroy() {
        val client = client

        // Inform the overlay service of dream starting.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            LOW_LIGHT_COMPONENT.flattenToString(),
            false /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()

        // Verify view added.
        Mockito.verify(mWindowManager).addView(mViewCaptor!!.capture(), any())

        // Service destroyed.
        mService!!.onDestroy()
        mMainExecutor.runAllReady()

        // Verify view removed.
        Mockito.verify(mWindowManager).removeView(mViewCaptor!!.value)

        // Verify state correctly set.
        Mockito.verify(mKeyguardUpdateMonitor).removeCallback(any())
        Mockito.verify(mLifecycleRegistry).currentState = Lifecycle.State.DESTROYED
        Mockito.verify(mStateController).setOverlayActive(false)
        Mockito.verify(mStateController).setLowLightActive(false)
        Mockito.verify(mStateController).setEntryAnimationsFinished(false)
    }

    @Test
    fun testDoNotRemoveViewOnDestroyIfOverlayNotStarted() {
        // Service destroyed without ever starting dream.
        mService!!.onDestroy()
        mMainExecutor.runAllReady()

        // Verify no view is removed.
        Mockito.verify(mWindowManager, Mockito.never()).removeView(any())

        // Verify state still correctly set.
        Mockito.verify(mKeyguardUpdateMonitor).removeCallback(any())
        Mockito.verify(mLifecycleRegistry).currentState = Lifecycle.State.DESTROYED
        Mockito.verify(mStateController).setOverlayActive(false)
        Mockito.verify(mStateController).setLowLightActive(false)
    }

    @Test
    fun testDecorViewNotAddedToWindowAfterDestroy() {
        val client = client

        // Destroy the service.
        mService!!.onDestroy()
        mMainExecutor.runAllReady()

        // Inform the overlay service of dream starting.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            DREAM_COMPONENT,
            false /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()
        Mockito.verify(mWindowManager, Mockito.never()).addView(any(), any())
    }

    @Test
    fun testNeverRemoveDecorViewIfNotAdded() {
        // Service destroyed before dream started.
        mService!!.onDestroy()
        mMainExecutor.runAllReady()
        Mockito.verify(mWindowManager, Mockito.never()).removeView(any())
    }

    @Test
    fun testResetCurrentOverlayWhenConnectedToNewDream() {
        val client = client

        // Inform the overlay service of dream starting. Do not show dream complications.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            DREAM_COMPONENT,
            false /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()

        // Verify that a new window is added.
        Mockito.verify(mWindowManager).addView(mViewCaptor!!.capture(), any())
        val windowDecorView = mViewCaptor!!.value

        // Assert that the overlay is not showing complications.
        Truth.assertThat(mService!!.shouldShowComplications()).isFalse()
        Mockito.clearInvocations(mDreamOverlayComponent)
        Mockito.clearInvocations(mAmbientTouchComponent)
        Mockito.clearInvocations(mWindowManager)

        // New dream starting with dream complications showing. Note that when a new dream is
        // binding to the dream overlay service, it receives the same instance of IBinder as the
        // first one.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            DREAM_COMPONENT,
            true /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()

        // Assert that the overlay is showing complications.
        Truth.assertThat(mService!!.shouldShowComplications()).isTrue()

        // Verify that the old overlay window has been removed, and a new one created.
        Mockito.verify(mWindowManager).removeView(windowDecorView)
        Mockito.verify(mWindowManager).addView(any(), any())

        // Verify that new instances of overlay container view controller and overlay touch monitor
        // are created.
        Mockito.verify(mDreamOverlayComponent).getDreamOverlayContainerViewController()
        Mockito.verify(mAmbientTouchComponent).getTouchMonitor()
    }

    @Test
    fun testWakeUp() {
        val client = client

        // Inform the overlay service of dream starting.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            DREAM_COMPONENT,
            true /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()
        mService!!.onWakeUp()
        Mockito.verify(mDreamOverlayContainerViewController).wakeUp()
        Mockito.verify(mDreamOverlayCallbackController).onWakeUp()
    }

    @Test
    fun testWakeUpBeforeStartDoesNothing() {
        mService!!.onWakeUp()
        Mockito.verify(mDreamOverlayContainerViewController, Mockito.never()).wakeUp()
    }

    @Test
    fun testSystemFlagShowForAllUsersSetOnWindow() {
        val client = client

        // Inform the overlay service of dream starting. Do not show dream complications.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            DREAM_COMPONENT,
            false /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()
        val paramsCaptor = ArgumentCaptor.forClass(WindowManager.LayoutParams::class.java)

        // Verify that a new window is added.
        Mockito.verify(mWindowManager).addView(any(), paramsCaptor.capture())
        Truth.assertThat(
                paramsCaptor.value.privateFlags and
                    WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS ==
                    WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS
            )
            .isTrue()
    }

    companion object {
        private val LOW_LIGHT_COMPONENT = ComponentName("package", "lowlight")
        private val HOME_CONTROL_PANEL_DREAM_COMPONENT =
            ComponentName("package", "homeControlPanel")
        private const val DREAM_COMPONENT = "package/dream"
        private const val WINDOW_NAME = "test"
    }
}

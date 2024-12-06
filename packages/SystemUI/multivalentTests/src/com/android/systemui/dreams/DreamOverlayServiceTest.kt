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

import android.app.WindowConfiguration
import android.content.ComponentName
import android.content.Intent
import android.os.RemoteException
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.FlagsParameterization
import android.service.dreams.Flags
import android.service.dreams.IDreamOverlay
import android.service.dreams.IDreamOverlayCallback
import android.service.dreams.IDreamOverlayClient
import android.service.dreams.IDreamOverlayClientCallback
import android.testing.TestableLooper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowManagerImpl
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.filters.SmallTest
import com.android.app.viewcapture.ViewCaptureAwareWindowManager
import com.android.app.viewcapture.ViewCaptureFactory
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.internal.logging.UiEventLogger
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.Flags.FLAG_COMMUNAL_HUB
import com.android.systemui.Flags.FLAG_GLANCEABLE_HUB_V2
import com.android.systemui.Flags.FLAG_SCENE_CONTAINER
import com.android.systemui.SysuiTestCase
import com.android.systemui.ambient.touch.TouchHandler
import com.android.systemui.ambient.touch.TouchMonitor
import com.android.systemui.ambient.touch.dagger.AmbientTouchComponent
import com.android.systemui.ambient.touch.scrim.ScrimController
import com.android.systemui.ambient.touch.scrim.ScrimManager
import com.android.systemui.bouncer.data.repository.fakeKeyguardBouncerRepository
import com.android.systemui.communal.data.repository.fakeCommunalSceneRepository
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.communal.domain.interactor.communalInteractor
import com.android.systemui.communal.domain.interactor.communalSettingsInteractor
import com.android.systemui.communal.domain.interactor.setCommunalAvailable
import com.android.systemui.communal.domain.interactor.setCommunalV2ConfigEnabled
import com.android.systemui.communal.shared.log.CommunalUiEvent
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.complication.ComplicationHostViewController
import com.android.systemui.complication.ComplicationLayoutEngine
import com.android.systemui.complication.dagger.ComplicationComponent
import com.android.systemui.dreams.complication.HideComplicationTouchHandler
import com.android.systemui.dreams.complication.dagger.DreamComplicationComponent
import com.android.systemui.dreams.dagger.DreamOverlayComponent
import com.android.systemui.dreams.touch.CommunalTouchHandler
import com.android.systemui.flags.andSceneContainer
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.keyguard.gesture.domain.gestureInteractor
import com.android.systemui.kosmos.testScope
import com.android.systemui.navigationbar.gestural.domain.GestureInteractor
import com.android.systemui.navigationbar.gestural.domain.TaskInfo
import com.android.systemui.navigationbar.gestural.domain.TaskMatcher
import com.android.systemui.scene.data.repository.sceneContainerRepository
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.testKosmos
import com.android.systemui.touch.TouchInsetManager
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.isNull
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.firstValue
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@RunWith(ParameterizedAndroidJunit4::class)
class DreamOverlayServiceTest(flags: FlagsParameterization?) : SysuiTestCase() {
    private val mFakeSystemClock = FakeSystemClock()
    private val mMainExecutor = FakeExecutor(mFakeSystemClock)
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val mLifecycleOwner = mock<DreamOverlayLifecycleOwner>()
    private val mDreamOverlayCallback = mock<IDreamOverlayCallback>()
    private val mWindowManager = mock<WindowManagerImpl>()
    private val mComplicationComponentFactory = mock<ComplicationComponent.Factory>()
    private val mComplicationHostViewController = mock<ComplicationHostViewController>()
    private val mComplicationVisibilityController = mock<ComplicationLayoutEngine>()
    private val mDreamComplicationComponentFactory = mock<DreamComplicationComponent.Factory>()
    private val mHideComplicationTouchHandler = mock<HideComplicationTouchHandler>()
    private val mDreamOverlayComponentFactory = mock<DreamOverlayComponent.Factory>()
    private val mCommunalTouchHandler = mock<CommunalTouchHandler>()
    private val mAmbientTouchComponentFactory = mock<AmbientTouchComponent.Factory>()
    private val mDreamOverlayContainerView = mock<DreamOverlayContainerView>()
    private val mDreamOverlayContainerViewController =
        mock<DreamOverlayContainerViewController> {
            on { containerView }.thenReturn(mDreamOverlayContainerView)
        }
    private val mKeyguardUpdateMonitor = mock<KeyguardUpdateMonitor>()
    private val mTouchMonitor = mock<TouchMonitor>()
    private val mStateController = mock<DreamOverlayStateController>()
    private val mDreamOverlayContainerViewParent = mock<ViewGroup>()
    private val mTouchInsetManager = mock<TouchInsetManager>()
    private val mUiEventLogger = mock<UiEventLogger>()
    private val mScrimController = mock<ScrimController>()
    private val mScrimManager =
        mock<ScrimManager> { on { currentController }.thenReturn(mScrimController) }
    private val mSystemDialogsCloser = mock<SystemDialogsCloser>()
    private val mDreamOverlayCallbackController = mock<DreamOverlayCallbackController>()
    private val mLazyViewCapture = lazy { viewCaptureSpy }

    private val mViewCaptor = argumentCaptor<View>()
    private val mTouchHandlersCaptor = argumentCaptor<Set<TouchHandler>>()

    private val mWindowParams = WindowManager.LayoutParams()
    private val lifecycleRegistry = FakeLifecycleRegistry(mLifecycleOwner)
    private val bouncerRepository = kosmos.fakeKeyguardBouncerRepository
    private val communalRepository = kosmos.fakeCommunalSceneRepository
    private var viewCaptureSpy = spy(ViewCaptureFactory.getInstance(context))
    private val gestureInteractor = spy(kosmos.gestureInteractor)

    private lateinit var mCommunalInteractor: CommunalInteractor
    private lateinit var mViewCaptureAwareWindowManager: ViewCaptureAwareWindowManager
    private lateinit var environmentComponents: EnvironmentComponents

    private lateinit var mService: DreamOverlayService

    private class EnvironmentComponents(
        val dreamsComplicationComponent: DreamComplicationComponent,
        val dreamOverlayComponent: DreamOverlayComponent,
        val complicationComponent: ComplicationComponent,
        val ambientTouchComponent: AmbientTouchComponent,
    ) {
        fun clearInvocations() {
            clearInvocations(
                dreamsComplicationComponent,
                dreamOverlayComponent,
                complicationComponent,
                ambientTouchComponent,
            )
        }

        fun verifyNoMoreInteractions() {
            Mockito.verifyNoMoreInteractions(
                dreamsComplicationComponent,
                dreamOverlayComponent,
                complicationComponent,
                ambientTouchComponent,
            )
        }
    }

    private fun setupComponentFactories(
        dreamComplicationComponentFactory: DreamComplicationComponent.Factory,
        dreamOverlayComponentFactory: DreamOverlayComponent.Factory,
        complicationComponentFactory: ComplicationComponent.Factory,
        ambientTouchComponentFactory: AmbientTouchComponent.Factory,
    ): EnvironmentComponents {
        val dreamOverlayComponent = mock<DreamOverlayComponent>()
        whenever(dreamOverlayComponent.getDreamOverlayContainerViewController())
            .thenReturn(mDreamOverlayContainerViewController)

        val complicationComponent = mock<ComplicationComponent>()
        whenever(complicationComponent.getComplicationHostViewController())
            .thenReturn(mComplicationHostViewController)
        whenever(mLifecycleOwner.registry).thenReturn(lifecycleRegistry)

        mCommunalInteractor = Mockito.spy(kosmos.communalInteractor)

        whenever(complicationComponentFactory.create(any(), any(), any(), any()))
            .thenReturn(complicationComponent)
        whenever(complicationComponent.getVisibilityController())
            .thenReturn(mComplicationVisibilityController)

        val dreamComplicationComponent = mock<DreamComplicationComponent>()
        whenever(dreamComplicationComponent.getHideComplicationTouchHandler())
            .thenReturn(mHideComplicationTouchHandler)
        whenever(dreamOverlayComponent.communalTouchHandler).thenReturn(mCommunalTouchHandler)
        whenever(dreamComplicationComponentFactory.create(any(), any()))
            .thenReturn(dreamComplicationComponent)

        whenever(dreamOverlayComponentFactory.create(any(), any(), any()))
            .thenReturn(dreamOverlayComponent)

        val ambientTouchComponent = mock<AmbientTouchComponent>()
        whenever(ambientTouchComponentFactory.create(any(), any(), any()))
            .thenReturn(ambientTouchComponent)
        whenever(ambientTouchComponent.getTouchMonitor()).thenReturn(mTouchMonitor)

        return EnvironmentComponents(
            dreamComplicationComponent,
            dreamOverlayComponent,
            complicationComponent,
            ambientTouchComponent,
        )
    }

    init {
        mSetFlagsRule.setFlagsParameterization(flags!!)
    }

    @Before
    fun setup() {
        environmentComponents =
            setupComponentFactories(
                mDreamComplicationComponentFactory,
                mDreamOverlayComponentFactory,
                mComplicationComponentFactory,
                mAmbientTouchComponentFactory,
            )
        mViewCaptureAwareWindowManager =
            ViewCaptureAwareWindowManager(
                mWindowManager,
                mLazyViewCapture,
                isViewCaptureEnabled = false,
            )
        mService =
            DreamOverlayService(
                mContext,
                mLifecycleOwner,
                mMainExecutor,
                mViewCaptureAwareWindowManager,
                mComplicationComponentFactory,
                mDreamComplicationComponentFactory,
                mDreamOverlayComponentFactory,
                mAmbientTouchComponentFactory,
                mStateController,
                mKeyguardUpdateMonitor,
                mScrimManager,
                mCommunalInteractor,
                kosmos.communalSettingsInteractor,
                kosmos.sceneInteractor,
                mSystemDialogsCloser,
                mUiEventLogger,
                mTouchInsetManager,
                LOW_LIGHT_COMPONENT,
                HOME_CONTROL_PANEL_DREAM_COMPONENT,
                mDreamOverlayCallbackController,
                kosmos.keyguardInteractor,
                gestureInteractor,
                WINDOW_NAME,
            )
    }

    private val client: IDreamOverlayClient
        get() {
            mService.onCreate()
            TestableLooper.get(this).processAllMessages()

            val proxy = mService.onBind(Intent())
            val overlay = IDreamOverlay.Stub.asInterface(proxy)
            val callback = Mockito.mock(IDreamOverlayClientCallback::class.java)
            overlay.getClient(callback)
            val clientCaptor = ArgumentCaptor.forClass(IDreamOverlayClient::class.java)
            verify(callback).onDreamOverlayClient(clientCaptor.capture())
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
            false /*isPreview*/,
            false, /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()
        verify(mUiEventLogger).log(DreamOverlayService.DreamOverlayEvent.DREAM_OVERLAY_ENTER_START)
        verify(mUiEventLogger)
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
            false /*isPreview*/,
            false, /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()
        verify(mWindowManager).addView(any(), any())
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
            false /*isPreview*/,
            false, /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()
        verify(mWindowManager).addView(any(), any())
        verify(mStateController).setOverlayActive(false)
        verify(mStateController).setLowLightActive(false)
        verify(mStateController).setEntryAnimationsFinished(false)
        verify(mStateController, never()).setOverlayActive(true)
        verify(mUiEventLogger, never())
            .log(DreamOverlayService.DreamOverlayEvent.DREAM_OVERLAY_COMPLETE_START)
        verify(mDreamOverlayCallbackController, never()).onStartDream()
    }

    @Test
    fun testDreamOverlayContainerViewControllerInitialized() {
        val client = client

        // Inform the overlay service of dream starting.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            DREAM_COMPONENT,
            false /*isPreview*/,
            false, /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()
        verify(mDreamOverlayContainerViewController).init()
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
            false /*isPreview*/,
            false, /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()
        verify(mDreamOverlayContainerViewParent).removeView(mDreamOverlayContainerView)
    }

    @Test
    fun testShouldShowComplicationsSetByStartDream() {
        val client = client

        // Inform the overlay service of dream starting.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            DREAM_COMPONENT,
            false /*isPreview*/,
            true, /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()
        assertThat(mService.shouldShowComplications()).isTrue()
    }

    @Test
    fun testDeferredResetRespondsToAnimationEnd() {
        val client = client

        // Inform the overlay service of dream starting.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            DREAM_COMPONENT,
            false /*isPreview*/,
            true, /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()

        whenever(mStateController.areExitAnimationsRunning()).thenReturn(true)
        clearInvocations(mStateController, mTouchMonitor)

        // Starting a dream will cause it to end first.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            DREAM_COMPONENT,
            false /*isPreview*/,
            true, /*shouldShowComplication*/
        )

        mMainExecutor.runAllReady()

        verifyNoMoreInteractions(mTouchMonitor)

        val captor = ArgumentCaptor.forClass(DreamOverlayStateController.Callback::class.java)
        verify(mStateController).addCallback(captor.capture())

        whenever(mStateController.areExitAnimationsRunning()).thenReturn(false)

        captor.firstValue.onStateChanged()

        // Should only be called once since it should be null during the second reset.
        verify(mTouchMonitor).destroy()
    }

    @Test
    fun testLowLightSetByStartDream() {
        val client = client

        // Inform the overlay service of dream starting.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            LOW_LIGHT_COMPONENT.flattenToString(),
            false /*isPreview*/,
            false, /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()
        assertThat(mService.dreamComponent).isEqualTo(LOW_LIGHT_COMPONENT)
        verify(mStateController).setLowLightActive(true)
    }

    @Test
    fun testHomeControlPanelSetsByStartDream() {
        val client = client

        // Inform the overlay service of dream starting.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            HOME_CONTROL_PANEL_DREAM_COMPONENT.flattenToString(),
            false /*isPreview*/,
            false, /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()
        assertThat(mService.dreamComponent).isEqualTo(HOME_CONTROL_PANEL_DREAM_COMPONENT)
        verify(mStateController).setHomeControlPanelActive(true)
    }

    @Test
    fun testOnEndDream() {
        val client = client

        // Inform the overlay service of dream starting.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            LOW_LIGHT_COMPONENT.flattenToString(),
            false /*isPreview*/,
            false, /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()

        // Verify view added.
        verify(mWindowManager).addView(mViewCaptor.capture(), any())

        // Service destroyed.
        mService.onEndDream()
        mMainExecutor.runAllReady()

        // Verify view removed.
        verify(mWindowManager).removeView(mViewCaptor.firstValue)

        // Verify state correctly set.
        verify(mStateController).setOverlayActive(false)
        verify(mStateController).setLowLightActive(false)
        verify(mStateController).setEntryAnimationsFinished(false)

        // Verify touch monitor destroyed
        verify(mTouchMonitor).destroy()
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
            false /*isPreview*/,
            false, /*shouldShowComplication*/
        )
        // Immediately end the dream.
        client.endDream()
        // Run any scheduled Runnables.
        mMainExecutor.runAllReady()

        // The overlay starts then finishes.
        val inOrder = Mockito.inOrder(mWindowManager)
        inOrder.verify(mWindowManager).addView(mViewCaptor.capture(), any())
        inOrder.verify(mWindowManager).removeView(mViewCaptor.firstValue)
    }

    @Test
    fun testEndDreamDuringStartDream() {
        val client = client

        // Schedule the endDream call in the middle of the startDream implementation, as any
        // ordering is possible.
        Mockito.doAnswer {
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
            false /*isPreview*/,
            false, /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()

        // The overlay starts then finishes.
        val inOrder = Mockito.inOrder(mWindowManager)
        inOrder.verify(mWindowManager).addView(mViewCaptor.capture(), any())
        inOrder.verify(mWindowManager).removeView(mViewCaptor.firstValue)
    }

    @Test
    fun testDestroy() {
        val client = client

        // Inform the overlay service of dream starting.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            LOW_LIGHT_COMPONENT.flattenToString(),
            false /*isPreview*/,
            false, /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()

        // Verify view added.
        verify(mWindowManager).addView(mViewCaptor.capture(), any())

        // Service destroyed.
        mService.onDestroy()
        mMainExecutor.runAllReady()

        // Verify view removed.
        verify(mWindowManager).removeView(mViewCaptor.firstValue)

        // Verify state correctly set.
        verify(mKeyguardUpdateMonitor).removeCallback(any())
        assertThat(lifecycleRegistry.currentState).isEqualTo(Lifecycle.State.DESTROYED)
        verify(mStateController).setOverlayActive(false)
        verify(mStateController).setLowLightActive(false)
        verify(mStateController).setEntryAnimationsFinished(false)
    }

    @Test
    fun testDoNotRemoveViewOnDestroyIfOverlayNotStarted() {
        // Service destroyed without ever starting dream.
        mService.onDestroy()
        mMainExecutor.runAllReady()

        // Verify no view is removed.
        verify(mWindowManager, never()).removeView(any())

        // Verify state still correctly set.
        verify(mKeyguardUpdateMonitor).removeCallback(any())
        assertThat(lifecycleRegistry.currentState).isEqualTo(Lifecycle.State.DESTROYED)
        verify(mStateController).setOverlayActive(false)
        verify(mStateController).setLowLightActive(false)
    }

    @Test
    fun testDecorViewNotAddedToWindowAfterDestroy() {
        val client = client

        // Destroy the service.
        mService.onDestroy()
        mMainExecutor.runAllReady()

        // Inform the overlay service of dream starting.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            DREAM_COMPONENT,
            false /*isPreview*/,
            false, /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()
        verify(mWindowManager, never()).addView(any(), any())
    }

    @Test
    fun testNeverRemoveDecorViewIfNotAdded() {
        // Service destroyed before dream started.
        mService.onDestroy()
        mMainExecutor.runAllReady()
        verify(mWindowManager, never()).removeView(any())
    }

    @Test
    fun testResetCurrentOverlayWhenConnectedToNewDream() {
        val client = client

        // Inform the overlay service of dream starting. Do not show dream complications.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            DREAM_COMPONENT,
            false /*isPreview*/,
            false, /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()

        // Verify that a new window is added.
        verify(mWindowManager).addView(mViewCaptor.capture(), any())
        val windowDecorView = mViewCaptor.firstValue

        // Assert that the overlay is not showing complications.
        assertThat(mService.shouldShowComplications()).isFalse()
        environmentComponents.clearInvocations()
        clearInvocations(mWindowManager)

        // New dream starting with dream complications showing. Note that when a new dream is
        // binding to the dream overlay service, it receives the same instance of IBinder as the
        // first one.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            DREAM_COMPONENT,
            false /*isPreview*/,
            true, /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()

        // Assert that the overlay is showing complications.
        assertThat(mService.shouldShowComplications()).isTrue()

        // Verify that the old overlay window has been removed, and a new one created.
        verify(mWindowManager).removeView(windowDecorView)
        verify(mWindowManager).addView(any(), any())

        // Verify that new instances of overlay container view controller and overlay touch monitor
        // are created.
        verify(environmentComponents.dreamOverlayComponent).getDreamOverlayContainerViewController()
        verify(environmentComponents.ambientTouchComponent).getTouchMonitor()

        // Verify DreamOverlayContainerViewController is destroyed.
        verify(mDreamOverlayContainerViewController).destroy()

        // DreamOverlay callback receives onWakeUp.
        verify(mDreamOverlayCallbackController).onWakeUp()
    }

    @Test
    fun testWakeUp() {
        val client = client

        // Inform the overlay service of dream starting.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            DREAM_COMPONENT,
            false /*isPreview*/,
            true, /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()
        mService.onWakeUp()
        verify(mDreamOverlayContainerViewController).onWakeUp()
        verify(mDreamOverlayCallbackController).onWakeUp()
    }

    @Test
    fun testWakeUpBeforeStartDoesNothing() {
        mService.onWakeUp()
        verify(mDreamOverlayContainerViewController, never()).onWakeUp()
    }

    @Test
    fun testSystemFlagShowForAllUsersSetOnWindow() {
        val client = client

        // Inform the overlay service of dream starting. Do not show dream complications.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            DREAM_COMPONENT,
            false /*isPreview*/,
            false, /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()
        val paramsCaptor = ArgumentCaptor.forClass(WindowManager.LayoutParams::class.java)

        // Verify that a new window is added.
        verify(mWindowManager).addView(any(), paramsCaptor.capture())
        assertThat(
                paramsCaptor.value.privateFlags and
                    WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS ==
                    WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS
            )
            .isTrue()
    }

    @Test
    @EnableFlags(Flags.FLAG_DREAM_WAKE_REDIRECT, FLAG_COMMUNAL_HUB)
    @DisableFlags(FLAG_SCENE_CONTAINER)
    @kotlin.Throws(RemoteException::class)
    fun testTransitionToGlanceableHub() =
        testScope.runTest {
            // Inform the overlay service of dream starting. Do not show dream complications.
            client.startDream(
                mWindowParams,
                mDreamOverlayCallback,
                DREAM_COMPONENT,
                false /*isPreview*/,
                false, /*shouldShowComplication*/
            )
            mMainExecutor.runAllReady()

            verify(mDreamOverlayCallback).onRedirectWake(false)
            clearInvocations(mDreamOverlayCallback)
            kosmos.setCommunalAvailable(true)
            mMainExecutor.runAllReady()
            runCurrent()
            verify(mDreamOverlayCallback).onRedirectWake(true)
            client.onWakeRequested()
            verify(mCommunalInteractor).changeScene(eq(CommunalScenes.Communal), any(), isNull())
            verify(mUiEventLogger).log(CommunalUiEvent.DREAM_TO_COMMUNAL_HUB_DREAM_AWAKE_START)
        }

    @Test
    @EnableFlags(Flags.FLAG_DREAM_WAKE_REDIRECT, FLAG_SCENE_CONTAINER, FLAG_COMMUNAL_HUB)
    @kotlin.Throws(RemoteException::class)
    fun testTransitionToGlanceableHub_sceneContainer() =
        testScope.runTest {
            // Inform the overlay service of dream starting. Do not show dream complications.
            client.startDream(
                mWindowParams,
                mDreamOverlayCallback,
                DREAM_COMPONENT,
                false /*isPreview*/,
                false, /*shouldShowComplication*/
            )
            mMainExecutor.runAllReady()

            verify(mDreamOverlayCallback).onRedirectWake(false)
            clearInvocations(mDreamOverlayCallback)
            kosmos.setCommunalAvailable(true)
            mMainExecutor.runAllReady()
            runCurrent()
            verify(mDreamOverlayCallback).onRedirectWake(true)
            client.onWakeRequested()
            mMainExecutor.runAllReady()
            runCurrent()
            assertThat(kosmos.sceneContainerRepository.currentScene.value)
                .isEqualTo(Scenes.Communal)
            verify(mUiEventLogger).log(CommunalUiEvent.DREAM_TO_COMMUNAL_HUB_DREAM_AWAKE_START)
        }

    @Test
    @EnableFlags(Flags.FLAG_DREAM_WAKE_REDIRECT, FLAG_COMMUNAL_HUB)
    @Throws(RemoteException::class)
    fun testRedirectExit() =
        testScope.runTest {
            // Inform the overlay service of dream starting. Do not show dream complications.
            client.startDream(
                mWindowParams,
                mDreamOverlayCallback,
                DREAM_COMPONENT,
                false /*isPreview*/,
                false, /*shouldShowComplication*/
            )
            // Set communal available, verify that overlay callback is informed.
            kosmos.setCommunalAvailable(true)
            mMainExecutor.runAllReady()
            runCurrent()
            verify(mDreamOverlayCallback).onRedirectWake(true)

            clearInvocations(mDreamOverlayCallback)

            // Set communal unavailable, verify that overlay callback is informed.
            kosmos.setCommunalAvailable(false)
            mMainExecutor.runAllReady()
            runCurrent()
            verify(mDreamOverlayCallback).onRedirectWake(false)
        }

    // Tests that the bouncer closes when DreamOverlayService is told that the dream is coming to
    // the front.
    @Test
    fun testBouncerRetractedWhenDreamComesToFront() {
        val client = client

        // Inform the overlay service of dream starting.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            DREAM_COMPONENT,
            false /*isPreview*/,
            true, /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()

        whenever(mDreamOverlayContainerViewController.isBouncerShowing).thenReturn(true)
        mService.onComeToFront()
        verify(mScrimController).expand(any())
    }

    // Tests that glanceable hub is hidden when DreamOverlayService is told that the dream is
    // coming to the front.
    @Test
    fun testGlanceableHubHiddenWhenDreamComesToFront() {
        val client = client

        // Inform the overlay service of dream starting.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            DREAM_COMPONENT,
            false /*isPreview*/,
            true, /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()

        mService.onComeToFront()
        assertThat(communalRepository.currentScene.value).isEqualTo(CommunalScenes.Blank)
    }

    // Tests that system dialogs (e.g. notification shade) closes when DreamOverlayService is told
    // that the dream is coming to the front.
    @Test
    fun testSystemDialogsClosedWhenDreamComesToFront() {
        val client = client

        // Inform the overlay service of dream starting.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            DREAM_COMPONENT,
            false /*isPreview*/,
            true, /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()

        mService.onComeToFront()
        verify(mSystemDialogsCloser).closeSystemDialogs()
    }

    @Test
    fun testLifecycle_createdAfterConstruction() {
        mMainExecutor.runAllReady()
        assertThat(lifecycleRegistry.currentState).isEqualTo(Lifecycle.State.CREATED)
    }

    @Test
    fun testLifecycle_resumedAfterDreamStarts() {
        val client = client

        // Inform the overlay service of dream starting. Do not show dream complications.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            DREAM_COMPONENT,
            false /*isPreview*/,
            false, /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()
        assertThat(lifecycleRegistry.mLifecycles)
            .containsExactly(
                Lifecycle.State.CREATED,
                Lifecycle.State.STARTED,
                Lifecycle.State.RESUMED,
            )
    }

    // Verifies that the touch handling lifecycle is STARTED even if the dream starts while not
    // focused.
    @Test
    @DisableFlags(FLAG_SCENE_CONTAINER)
    fun testLifecycle_dreamNotFocusedOnStart_isStarted() {
        val transitionState: MutableStateFlow<ObservableTransitionState> =
            MutableStateFlow(ObservableTransitionState.Idle(CommunalScenes.Blank))
        communalRepository.setTransitionState(transitionState)

        // Communal becomes visible.
        transitionState.value = ObservableTransitionState.Idle(CommunalScenes.Communal)
        testScope.runCurrent()
        mMainExecutor.runAllReady()

        // Start dreaming.
        val client = client
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            DREAM_COMPONENT,
            false /*isPreview*/,
            false, /*shouldShowComplication*/
        )
        testScope.runCurrent()
        mMainExecutor.runAllReady()
        assertThat(lifecycleRegistry.currentState).isEqualTo(Lifecycle.State.STARTED)
    }

    @Test
    fun testLifecycle_destroyedAfterOnDestroy() {
        val client = client

        // Inform the overlay service of dream starting. Do not show dream complications.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            DREAM_COMPONENT,
            false /*isPreview*/,
            false, /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()
        mService.onDestroy()
        mMainExecutor.runAllReady()
        assertThat(lifecycleRegistry.mLifecycles)
            .containsExactly(
                Lifecycle.State.CREATED,
                Lifecycle.State.STARTED,
                Lifecycle.State.RESUMED,
                Lifecycle.State.DESTROYED,
            )
    }

    @Test
    fun testNotificationShadeShown_setsLifecycleState() {
        val client = client

        // Inform the overlay service of dream starting. Do not show dream complications.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            DREAM_COMPONENT,
            false /*isPreview*/,
            false, /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()
        assertThat(lifecycleRegistry.currentState).isEqualTo(Lifecycle.State.RESUMED)
        val callbackCaptor = ArgumentCaptor.forClass(KeyguardUpdateMonitorCallback::class.java)
        verify(mKeyguardUpdateMonitor).registerCallback(callbackCaptor.capture())

        // Notification shade opens.
        callbackCaptor.value.onShadeExpandedChanged(true)
        mMainExecutor.runAllReady()

        // Lifecycle state goes from resumed back to started when the notification shade shows.
        assertThat(lifecycleRegistry.currentState).isEqualTo(Lifecycle.State.STARTED)

        // Notification shade closes.
        callbackCaptor.value.onShadeExpandedChanged(false)
        mMainExecutor.runAllReady()

        // Lifecycle state goes back to RESUMED.
        assertThat(lifecycleRegistry.currentState).isEqualTo(Lifecycle.State.RESUMED)
    }

    @DisableFlags(FLAG_SCENE_CONTAINER)
    @Test
    fun testBouncerShown_setsLifecycleState() {
        val client = client

        // Inform the overlay service of dream starting. Do not show dream complications.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            DREAM_COMPONENT,
            false /*isPreview*/,
            false, /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()
        assertThat(lifecycleRegistry.currentState).isEqualTo(Lifecycle.State.RESUMED)

        // Bouncer shows.
        bouncerRepository.setPrimaryShow(true)
        testScope.runCurrent()
        mMainExecutor.runAllReady()

        // Lifecycle state goes from resumed back to started when the notification shade shows.
        assertThat(lifecycleRegistry.currentState).isEqualTo(Lifecycle.State.STARTED)

        // Bouncer closes.
        bouncerRepository.setPrimaryShow(false)
        testScope.runCurrent()
        mMainExecutor.runAllReady()

        // Lifecycle state goes back to RESUMED.
        assertThat(lifecycleRegistry.currentState).isEqualTo(Lifecycle.State.RESUMED)
    }

    @EnableFlags(FLAG_SCENE_CONTAINER)
    @Test
    fun testBouncerShown_withSceneContainer_setsLifecycleState() {
        val client = client

        // Inform the overlay service of dream starting.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            DREAM_COMPONENT,
            false /*isPreview*/,
            false, /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()
        assertThat(lifecycleRegistry.currentState).isEqualTo(Lifecycle.State.RESUMED)

        // Bouncer shows.
        kosmos.sceneInteractor.changeScene(Scenes.Bouncer, "test")
        testScope.runCurrent()
        mMainExecutor.runAllReady()

        // Lifecycle state goes from resumed back to started when the bouncer shows.
        assertThat(lifecycleRegistry.currentState).isEqualTo(Lifecycle.State.STARTED)

        // Bouncer closes.
        kosmos.sceneInteractor.changeScene(Scenes.Dream, "test")
        testScope.runCurrent()
        mMainExecutor.runAllReady()

        // Lifecycle state goes back to RESUMED.
        assertThat(lifecycleRegistry.currentState).isEqualTo(Lifecycle.State.RESUMED)
    }

    @Test
    @DisableFlags(FLAG_SCENE_CONTAINER)
    fun testCommunalVisible_setsLifecycleState() {
        val client = client

        // Inform the overlay service of dream starting. Do not show dream complications.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            DREAM_COMPONENT,
            false /*isPreview*/,
            false, /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()
        assertThat(lifecycleRegistry.currentState).isEqualTo(Lifecycle.State.RESUMED)
        val transitionState: MutableStateFlow<ObservableTransitionState> =
            MutableStateFlow(ObservableTransitionState.Idle(CommunalScenes.Blank))
        communalRepository.setTransitionState(transitionState)

        // Communal becomes visible.
        transitionState.value = ObservableTransitionState.Idle(CommunalScenes.Communal)
        testScope.runCurrent()
        mMainExecutor.runAllReady()

        // Lifecycle state goes from resumed back to started when the notification shade shows.
        assertThat(lifecycleRegistry.currentState).isEqualTo(Lifecycle.State.STARTED)

        // Communal closes.
        transitionState.value = ObservableTransitionState.Idle(CommunalScenes.Blank)
        testScope.runCurrent()
        mMainExecutor.runAllReady()

        // Lifecycle state goes back to RESUMED.
        assertThat(lifecycleRegistry.currentState).isEqualTo(Lifecycle.State.RESUMED)
    }

    // Verifies the dream's lifecycle
    @Test
    @DisableFlags(FLAG_SCENE_CONTAINER)
    fun testLifecycleStarted_whenAnyOcclusion() {
        val client = client

        // Inform the overlay service of dream starting. Do not show dream complications.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            DREAM_COMPONENT,
            false /*isPreview*/,
            false, /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()
        assertThat(lifecycleRegistry.currentState).isEqualTo(Lifecycle.State.RESUMED)
        val transitionState: MutableStateFlow<ObservableTransitionState> =
            MutableStateFlow(ObservableTransitionState.Idle(CommunalScenes.Blank))
        communalRepository.setTransitionState(transitionState)

        // Communal becomes visible.
        transitionState.value = ObservableTransitionState.Idle(CommunalScenes.Communal)
        testScope.runCurrent()
        mMainExecutor.runAllReady()

        // Lifecycle state goes from resumed back to started when the notification shade shows.
        assertThat(lifecycleRegistry.currentState).isEqualTo(Lifecycle.State.STARTED)

        // Communal closes.
        transitionState.value = ObservableTransitionState.Idle(CommunalScenes.Blank)
        testScope.runCurrent()
        mMainExecutor.runAllReady()

        // Lifecycle state goes back to RESUMED.
        assertThat(lifecycleRegistry.currentState).isEqualTo(Lifecycle.State.RESUMED)
    }

    @Test
    fun testDreamActivityGesturesBlockedWhenDreaming() {
        val client = client

        // Inform the overlay service of dream starting.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            DREAM_COMPONENT,
            false /*isPreview*/,
            false, /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()

        val matcherCaptor = argumentCaptor<TaskMatcher>()
        verify(gestureInteractor)
            .addGestureBlockedMatcher(matcherCaptor.capture(), eq(GestureInteractor.Scope.Global))
        val matcher = matcherCaptor.firstValue

        val dreamTaskInfo = TaskInfo(mock<ComponentName>(), WindowConfiguration.ACTIVITY_TYPE_DREAM)
        assertThat(matcher.matches(dreamTaskInfo)).isTrue()

        client.endDream()
        mMainExecutor.runAllReady()

        verify(gestureInteractor)
            .removeGestureBlockedMatcher(eq(matcher), eq(GestureInteractor.Scope.Global))
    }

    @Test
    fun testDreamActivityGesturesNotBlockedWhenPreview() {
        val client = client

        // Inform the overlay service of dream starting.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            DREAM_COMPONENT,
            true /*isPreview*/,
            false, /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()

        verify(gestureInteractor, never())
            .addGestureBlockedMatcher(any(), eq(GestureInteractor.Scope.Global))
    }

    @Test
    fun testDreamActivityGesturesNotBlockedWhenNotificationShadeShowing() {
        val client = client

        // Inform the overlay service of dream starting.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            DREAM_COMPONENT,
            false /*isPreview*/,
            false, /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()

        val matcherCaptor = argumentCaptor<TaskMatcher>()
        verify(gestureInteractor)
            .addGestureBlockedMatcher(matcherCaptor.capture(), eq(GestureInteractor.Scope.Global))
        val matcher = matcherCaptor.firstValue

        val dreamTaskInfo = TaskInfo(mock<ComponentName>(), WindowConfiguration.ACTIVITY_TYPE_DREAM)
        assertThat(matcher.matches(dreamTaskInfo)).isTrue()

        val callbackCaptor = ArgumentCaptor.forClass(KeyguardUpdateMonitorCallback::class.java)
        verify(mKeyguardUpdateMonitor).registerCallback(callbackCaptor.capture())

        // Notification shade opens.
        callbackCaptor.value.onShadeExpandedChanged(true)
        mMainExecutor.runAllReady()

        verify(gestureInteractor)
            .removeGestureBlockedMatcher(eq(matcher), eq(GestureInteractor.Scope.Global))
    }

    @Test
    fun testDreamActivityGesturesNotBlockedDreamEndedBeforeKeyguardStateChanged() {
        val client = client

        // Inform the overlay service of dream starting.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            DREAM_COMPONENT,
            false /*isPreview*/,
            false, /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()

        val matcherCaptor = argumentCaptor<TaskMatcher>()
        verify(gestureInteractor)
            .addGestureBlockedMatcher(matcherCaptor.capture(), eq(GestureInteractor.Scope.Global))
        val matcher = matcherCaptor.firstValue

        val dreamTaskInfo = TaskInfo(mock<ComponentName>(), WindowConfiguration.ACTIVITY_TYPE_DREAM)
        assertThat(matcher.matches(dreamTaskInfo)).isTrue()

        client.endDream()
        mMainExecutor.runAllReady()
        clearInvocations(gestureInteractor)

        val callbackCaptor = ArgumentCaptor.forClass(KeyguardUpdateMonitorCallback::class.java)
        verify(mKeyguardUpdateMonitor).registerCallback(callbackCaptor.capture())

        // Notification shade opens.
        callbackCaptor.value.onShadeExpandedChanged(true)
        mMainExecutor.runAllReady()

        verify(gestureInteractor)
            .removeGestureBlockedMatcher(eq(matcher), eq(GestureInteractor.Scope.Global))
    }

    @Test
    fun testComponentsRecreatedBetweenDreams() {
        clearInvocations(
            mDreamComplicationComponentFactory,
            mDreamOverlayComponentFactory,
            mComplicationComponentFactory,
            mAmbientTouchComponentFactory,
        )

        mService.onEndDream()

        setupComponentFactories(
            mDreamComplicationComponentFactory,
            mDreamOverlayComponentFactory,
            mComplicationComponentFactory,
            mAmbientTouchComponentFactory,
        )

        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            DREAM_COMPONENT,
            false /*isPreview*/,
            false, /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()
        environmentComponents.verifyNoMoreInteractions()
    }

    @DisableFlags(FLAG_GLANCEABLE_HUB_V2)
    @Test
    fun testAmbientTouchHandlersRegistration_registerHideComplicationAndCommunal() {
        val client = client

        // Inform the overlay service of dream starting.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            DREAM_COMPONENT,
            false /*isPreview*/,
            false, /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()

        verify(mAmbientTouchComponentFactory).create(any(), mTouchHandlersCaptor.capture(), any())
        assertThat(mTouchHandlersCaptor.firstValue)
            .containsExactly(mHideComplicationTouchHandler, mCommunalTouchHandler)
    }

    @EnableFlags(FLAG_GLANCEABLE_HUB_V2)
    @Test
    fun testAmbientTouchHandlersRegistration_v2_registerOnlyHideComplication() {
        kosmos.setCommunalV2ConfigEnabled(true)

        val client = client

        // Inform the overlay service of dream starting.
        client.startDream(
            mWindowParams,
            mDreamOverlayCallback,
            DREAM_COMPONENT,
            false /*isPreview*/,
            false, /*shouldShowComplication*/
        )
        mMainExecutor.runAllReady()

        verify(mAmbientTouchComponentFactory).create(any(), mTouchHandlersCaptor.capture(), any())
        assertThat(mTouchHandlersCaptor.firstValue).containsExactly(mHideComplicationTouchHandler)
    }

    internal class FakeLifecycleRegistry(provider: LifecycleOwner) : LifecycleRegistry(provider) {
        val mLifecycles: MutableList<State> = ArrayList()

        override var currentState: State
            get() = mLifecycles[mLifecycles.size - 1]
            set(state) {
                mLifecycles.add(state)
            }
    }

    companion object {
        private val LOW_LIGHT_COMPONENT = ComponentName("package", "lowlight")
        private val HOME_CONTROL_PANEL_DREAM_COMPONENT =
            ComponentName("package", "homeControlPanel")
        private const val DREAM_COMPONENT = "package/dream"
        private const val WINDOW_NAME = "test"

        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return FlagsParameterization.allCombinationsOf(FLAG_COMMUNAL_HUB).andSceneContainer()
        }
    }
}

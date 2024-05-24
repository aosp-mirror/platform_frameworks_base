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

package com.android.systemui.dreams;

import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.service.dreams.IDreamOverlay;
import android.service.dreams.IDreamOverlayCallback;
import android.service.dreams.IDreamOverlayClient;
import android.service.dreams.IDreamOverlayClientCallback;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowManagerImpl;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.logging.UiEventLogger;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.complication.ComplicationLayoutEngine;
import com.android.systemui.dreams.complication.HideComplicationTouchHandler;
import com.android.systemui.dreams.complication.dagger.ComplicationComponent;
import com.android.systemui.dreams.dagger.DreamOverlayComponent;
import com.android.systemui.dreams.touch.DreamOverlayTouchMonitor;
import com.android.systemui.touch.TouchInsetManager;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;
import com.android.systemui.utils.leaks.LeakCheckedTest;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class DreamOverlayServiceTest extends SysuiTestCase {
    private static final ComponentName LOW_LIGHT_COMPONENT = new ComponentName("package",
            "lowlight");

    private static final ComponentName HOME_CONTROL_PANEL_DREAM_COMPONENT =
            new ComponentName("package", "homeControlPanel");
    private static final String DREAM_COMPONENT = "package/dream";
    private static final String WINDOW_NAME = "test";
    private final FakeSystemClock mFakeSystemClock = new FakeSystemClock();
    private final FakeExecutor mMainExecutor = new FakeExecutor(mFakeSystemClock);

    @Mock
    DreamOverlayLifecycleOwner mLifecycleOwner;

    @Mock
    LifecycleRegistry mLifecycleRegistry;

    @Rule
    public final LeakCheckedTest.SysuiLeakCheck mLeakCheck = new LeakCheckedTest.SysuiLeakCheck();

    WindowManager.LayoutParams mWindowParams;

    @Mock
    IDreamOverlayCallback mDreamOverlayCallback;

    @Mock
    WindowManagerImpl mWindowManager;

    @Mock
    com.android.systemui.complication.dagger.ComplicationComponent.Factory
            mComplicationComponentFactory;

    @Mock
    com.android.systemui.complication.dagger.ComplicationComponent mComplicationComponent;

    @Mock
    ComplicationLayoutEngine mComplicationVisibilityController;

    @Mock
    ComplicationComponent.Factory mDreamComplicationComponentFactory;

    @Mock
    ComplicationComponent mDreamComplicationComponent;

    @Mock
    HideComplicationTouchHandler mHideComplicationTouchHandler;

    @Mock
    DreamOverlayComponent.Factory mDreamOverlayComponentFactory;

    @Mock
    DreamOverlayComponent mDreamOverlayComponent;

    @Mock
    DreamOverlayContainerView mDreamOverlayContainerView;

    @Mock
    DreamOverlayContainerViewController mDreamOverlayContainerViewController;

    @Mock
    KeyguardUpdateMonitor mKeyguardUpdateMonitor;

    @Mock
    DreamOverlayTouchMonitor mDreamOverlayTouchMonitor;

    @Mock
    DreamOverlayStateController mStateController;

    @Mock
    ViewGroup mDreamOverlayContainerViewParent;

    @Mock
    TouchInsetManager mTouchInsetManager;

    @Mock
    UiEventLogger mUiEventLogger;

    @Mock
    DreamOverlayCallbackController mDreamOverlayCallbackController;

    @Captor
    ArgumentCaptor<View> mViewCaptor;

    DreamOverlayService mService;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        when(mDreamOverlayComponent.getDreamOverlayContainerViewController())
                .thenReturn(mDreamOverlayContainerViewController);
        when(mLifecycleOwner.getRegistry())
                .thenReturn(mLifecycleRegistry);
        when(mDreamOverlayComponent.getDreamOverlayTouchMonitor())
                .thenReturn(mDreamOverlayTouchMonitor);
        when(mComplicationComponentFactory
                .create(any(), any(), any(), any()))
                .thenReturn(mComplicationComponent);
        when(mComplicationComponent.getVisibilityController())
                .thenReturn(mComplicationVisibilityController);
        when(mDreamComplicationComponent.getHideComplicationTouchHandler())
                .thenReturn(mHideComplicationTouchHandler);
        when(mDreamComplicationComponentFactory
                .create(any(), any()))
                .thenReturn(mDreamComplicationComponent);
        when(mDreamOverlayComponentFactory
                .create(any(), any(), any(), any()))
                .thenReturn(mDreamOverlayComponent);
        when(mDreamOverlayContainerViewController.getContainerView())
                .thenReturn(mDreamOverlayContainerView);

        mWindowParams = new WindowManager.LayoutParams();
        mService = new DreamOverlayService(
                mContext,
                mLifecycleOwner,
                mMainExecutor,
                mWindowManager,
                mComplicationComponentFactory,
                mDreamComplicationComponentFactory,
                mDreamOverlayComponentFactory,
                mStateController,
                mKeyguardUpdateMonitor,
                mUiEventLogger,
                mTouchInsetManager,
                LOW_LIGHT_COMPONENT,
                HOME_CONTROL_PANEL_DREAM_COMPONENT,
                mDreamOverlayCallbackController,
                WINDOW_NAME);
    }

    public IDreamOverlayClient getClient() throws RemoteException {
        final IBinder proxy = mService.onBind(new Intent());
        final IDreamOverlay overlay = IDreamOverlay.Stub.asInterface(proxy);
        final IDreamOverlayClientCallback callback =
                Mockito.mock(IDreamOverlayClientCallback.class);
        overlay.getClient(callback);
        final ArgumentCaptor<IDreamOverlayClient> clientCaptor =
                ArgumentCaptor.forClass(IDreamOverlayClient.class);
        verify(callback).onDreamOverlayClient(clientCaptor.capture());

        return clientCaptor.getValue();
    }

    @Test
    public void testOnStartMetricsLogged() throws Exception {
        final IDreamOverlayClient client = getClient();

        // Inform the overlay service of dream starting.
        client.startDream(mWindowParams, mDreamOverlayCallback, DREAM_COMPONENT,
                false /*shouldShowComplication*/);
        mMainExecutor.runAllReady();

        verify(mUiEventLogger).log(DreamOverlayService.DreamOverlayEvent.DREAM_OVERLAY_ENTER_START);
        verify(mUiEventLogger).log(
                DreamOverlayService.DreamOverlayEvent.DREAM_OVERLAY_COMPLETE_START);
    }

    @Test
    public void testOverlayContainerViewAddedToWindow() throws Exception {
        final IDreamOverlayClient client = getClient();

        // Inform the overlay service of dream starting.
        client.startDream(mWindowParams, mDreamOverlayCallback, DREAM_COMPONENT,
                false /*shouldShowComplication*/);
        mMainExecutor.runAllReady();

        verify(mWindowManager).addView(any(), any());
    }

    // Validates that {@link DreamOverlayService} properly handles the case where the dream's
    // window is no longer valid by the time start is called.
    @Test
    public void testInvalidWindowAddStart() throws Exception {
        final IDreamOverlayClient client = getClient();

        doThrow(new WindowManager.BadTokenException()).when(mWindowManager).addView(any(), any());
        // Inform the overlay service of dream starting.
        client.startDream(mWindowParams, mDreamOverlayCallback, DREAM_COMPONENT,
                false /*shouldShowComplication*/);
        mMainExecutor.runAllReady();

        verify(mWindowManager).addView(any(), any());

        verify(mStateController).setOverlayActive(false);
        verify(mStateController).setLowLightActive(false);
        verify(mStateController).setEntryAnimationsFinished(false);

        verify(mStateController, never()).setOverlayActive(true);
        verify(mUiEventLogger, never()).log(
                DreamOverlayService.DreamOverlayEvent.DREAM_OVERLAY_COMPLETE_START);

        verify(mDreamOverlayCallbackController, never()).onStartDream();
    }

    @Test
    public void testDreamOverlayContainerViewControllerInitialized() throws Exception {
        final IDreamOverlayClient client = getClient();

        // Inform the overlay service of dream starting.
        client.startDream(mWindowParams, mDreamOverlayCallback, DREAM_COMPONENT,
                false /*shouldShowComplication*/);
        mMainExecutor.runAllReady();

        verify(mDreamOverlayContainerViewController).init();
    }

    @Test
    public void testDreamOverlayContainerViewRemovedFromOldParentWhenInitialized()
            throws Exception {
        when(mDreamOverlayContainerView.getParent())
                .thenReturn(mDreamOverlayContainerViewParent)
                .thenReturn(null);

        final IDreamOverlayClient client = getClient();

        // Inform the overlay service of dream starting.
        client.startDream(mWindowParams, mDreamOverlayCallback, DREAM_COMPONENT,
                false /*shouldShowComplication*/);
        mMainExecutor.runAllReady();

        verify(mDreamOverlayContainerViewParent).removeView(mDreamOverlayContainerView);
    }

    @Test
    public void testShouldShowComplicationsSetByStartDream() throws RemoteException {
        final IDreamOverlayClient client = getClient();

        // Inform the overlay service of dream starting.
        client.startDream(mWindowParams, mDreamOverlayCallback, DREAM_COMPONENT,
                true /*shouldShowComplication*/);
        mMainExecutor.runAllReady();

        assertThat(mService.shouldShowComplications()).isTrue();
    }

    @Test
    public void testLowLightSetByStartDream() throws RemoteException {
        final IDreamOverlayClient client = getClient();

        // Inform the overlay service of dream starting.
        client.startDream(mWindowParams, mDreamOverlayCallback,
                LOW_LIGHT_COMPONENT.flattenToString(), false /*shouldShowComplication*/);
        mMainExecutor.runAllReady();

        assertThat(mService.getDreamComponent()).isEqualTo(LOW_LIGHT_COMPONENT);
        verify(mStateController).setLowLightActive(true);
    }

    @Test
    public void testHomeControlPanelSetsByStartDream() throws RemoteException {
        final IDreamOverlayClient client = getClient();

        // Inform the overlay service of dream starting.
        client.startDream(mWindowParams, mDreamOverlayCallback,
                HOME_CONTROL_PANEL_DREAM_COMPONENT.flattenToString(),
                false /*shouldShowComplication*/);
        mMainExecutor.runAllReady();
        assertThat(mService.getDreamComponent()).isEqualTo(HOME_CONTROL_PANEL_DREAM_COMPONENT);
        verify(mStateController).setHomeControlPanelActive(true);
    }

    @Test
    public void testOnEndDream() throws RemoteException {
        final IDreamOverlayClient client = getClient();

        // Inform the overlay service of dream starting.
        client.startDream(mWindowParams, mDreamOverlayCallback,
                LOW_LIGHT_COMPONENT.flattenToString(), false /*shouldShowComplication*/);
        mMainExecutor.runAllReady();

        // Verify view added.
        verify(mWindowManager).addView(mViewCaptor.capture(), any());

        // Service destroyed.
        mService.onEndDream();
        mMainExecutor.runAllReady();

        // Verify view removed.
        verify(mWindowManager).removeView(mViewCaptor.getValue());

        // Verify state correctly set.
        verify(mStateController).setOverlayActive(false);
        verify(mStateController).setLowLightActive(false);
        verify(mStateController).setEntryAnimationsFinished(false);
    }

    @Test
    public void testImmediateEndDream() throws Exception {
        final IDreamOverlayClient client = getClient();

        // Start the dream, but don't execute any Runnables put on the executor yet. We delay
        // executing Runnables as the timing isn't guaranteed and we want to verify that the overlay
        // starts and finishes in the proper order even if Runnables are delayed.
        client.startDream(mWindowParams, mDreamOverlayCallback, DREAM_COMPONENT,
                false /*shouldShowComplication*/);
        // Immediately end the dream.
        client.endDream();
        // Run any scheduled Runnables.
        mMainExecutor.runAllReady();

        // The overlay starts then finishes.
        InOrder inOrder = inOrder(mWindowManager);
        inOrder.verify(mWindowManager).addView(mViewCaptor.capture(), any());
        inOrder.verify(mWindowManager).removeView(mViewCaptor.getValue());
    }

    @Test
    public void testEndDreamDuringStartDream() throws Exception {
        final IDreamOverlayClient client = getClient();

        // Schedule the endDream call in the middle of the startDream implementation, as any
        // ordering is possible.
        doAnswer(invocation -> {
            client.endDream();
            return null;
        }).when(mStateController).setOverlayActive(true);

        // Start the dream.
        client.startDream(mWindowParams, mDreamOverlayCallback, DREAM_COMPONENT,
                false /*shouldShowComplication*/);
        mMainExecutor.runAllReady();

        // The overlay starts then finishes.
        InOrder inOrder = inOrder(mWindowManager);
        inOrder.verify(mWindowManager).addView(mViewCaptor.capture(), any());
        inOrder.verify(mWindowManager).removeView(mViewCaptor.getValue());
    }

    @Test
    public void testDestroy() throws RemoteException {
        final IDreamOverlayClient client = getClient();

        // Inform the overlay service of dream starting.
        client.startDream(mWindowParams, mDreamOverlayCallback,
                LOW_LIGHT_COMPONENT.flattenToString(), false /*shouldShowComplication*/);
        mMainExecutor.runAllReady();

        // Verify view added.
        verify(mWindowManager).addView(mViewCaptor.capture(), any());

        // Service destroyed.
        mService.onDestroy();
        mMainExecutor.runAllReady();

        // Verify view removed.
        verify(mWindowManager).removeView(mViewCaptor.getValue());

        // Verify state correctly set.
        verify(mKeyguardUpdateMonitor).removeCallback(any());
        verify(mLifecycleRegistry).setCurrentState(Lifecycle.State.DESTROYED);
        verify(mStateController).setOverlayActive(false);
        verify(mStateController).setLowLightActive(false);
        verify(mStateController).setEntryAnimationsFinished(false);
    }

    @Test
    public void testDoNotRemoveViewOnDestroyIfOverlayNotStarted() {
        // Service destroyed without ever starting dream.
        mService.onDestroy();
        mMainExecutor.runAllReady();

        // Verify no view is removed.
        verify(mWindowManager, never()).removeView(any());

        // Verify state still correctly set.
        verify(mKeyguardUpdateMonitor).removeCallback(any());
        verify(mLifecycleRegistry).setCurrentState(Lifecycle.State.DESTROYED);
        verify(mStateController).setOverlayActive(false);
        verify(mStateController).setLowLightActive(false);
    }

    @Test
    public void testDecorViewNotAddedToWindowAfterDestroy() throws Exception {
        final IDreamOverlayClient client = getClient();

        // Destroy the service.
        mService.onDestroy();
        mMainExecutor.runAllReady();

        // Inform the overlay service of dream starting.
        client.startDream(mWindowParams, mDreamOverlayCallback, DREAM_COMPONENT,
                false /*shouldShowComplication*/);
        mMainExecutor.runAllReady();

        verify(mWindowManager, never()).addView(any(), any());
    }

    @Test
    public void testNeverRemoveDecorViewIfNotAdded() {
        // Service destroyed before dream started.
        mService.onDestroy();
        mMainExecutor.runAllReady();

        verify(mWindowManager, never()).removeView(any());
    }

    @Test
    public void testResetCurrentOverlayWhenConnectedToNewDream() throws RemoteException {
        final IDreamOverlayClient client = getClient();

        // Inform the overlay service of dream starting. Do not show dream complications.
        client.startDream(mWindowParams, mDreamOverlayCallback, DREAM_COMPONENT,
                false /*shouldShowComplication*/);
        mMainExecutor.runAllReady();

        // Verify that a new window is added.
        verify(mWindowManager).addView(mViewCaptor.capture(), any());
        final View windowDecorView = mViewCaptor.getValue();

        // Assert that the overlay is not showing complications.
        assertThat(mService.shouldShowComplications()).isFalse();

        clearInvocations(mDreamOverlayComponent);
        clearInvocations(mWindowManager);

        // New dream starting with dream complications showing. Note that when a new dream is
        // binding to the dream overlay service, it receives the same instance of IBinder as the
        // first one.
        client.startDream(mWindowParams, mDreamOverlayCallback, DREAM_COMPONENT,
                true /*shouldShowComplication*/);
        mMainExecutor.runAllReady();

        // Assert that the overlay is showing complications.
        assertThat(mService.shouldShowComplications()).isTrue();

        // Verify that the old overlay window has been removed, and a new one created.
        verify(mWindowManager).removeView(windowDecorView);
        verify(mWindowManager).addView(any(), any());

        // Verify that new instances of overlay container view controller and overlay touch monitor
        // are created.
        verify(mDreamOverlayComponent).getDreamOverlayContainerViewController();
        verify(mDreamOverlayComponent).getDreamOverlayTouchMonitor();
    }

    @Test
    public void testWakeUp() throws RemoteException {
        final IDreamOverlayClient client = getClient();

        // Inform the overlay service of dream starting.
        client.startDream(mWindowParams, mDreamOverlayCallback, DREAM_COMPONENT,
                true /*shouldShowComplication*/);
        mMainExecutor.runAllReady();

        mService.onWakeUp();
        verify(mDreamOverlayContainerViewController).wakeUp();
        verify(mDreamOverlayCallbackController).onWakeUp();
    }

    @Test
    public void testWakeUpBeforeStartDoesNothing() {
        mService.onWakeUp();
        verify(mDreamOverlayContainerViewController, never()).wakeUp();
    }

    @Test
    public void testSystemFlagShowForAllUsersSetOnWindow() throws RemoteException {
        final IDreamOverlayClient client = getClient();

        // Inform the overlay service of dream starting. Do not show dream complications.
        client.startDream(mWindowParams, mDreamOverlayCallback, DREAM_COMPONENT,
                false /*shouldShowComplication*/);
        mMainExecutor.runAllReady();

        final ArgumentCaptor<WindowManager.LayoutParams> paramsCaptor =
                ArgumentCaptor.forClass(WindowManager.LayoutParams.class);

        // Verify that a new window is added.
        verify(mWindowManager).addView(any(), paramsCaptor.capture());

        assertThat((paramsCaptor.getValue().privateFlags & SYSTEM_FLAG_SHOW_FOR_ALL_USERS)
                == SYSTEM_FLAG_SHOW_FOR_ALL_USERS).isTrue();
    }
}

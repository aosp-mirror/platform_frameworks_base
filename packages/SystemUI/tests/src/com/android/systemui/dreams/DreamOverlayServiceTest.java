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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.service.dreams.IDreamOverlay;
import android.service.dreams.IDreamOverlayCallback;
import android.testing.AndroidTestingRunner;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowManagerImpl;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import androidx.test.filters.SmallTest;

import com.android.internal.logging.UiEventLogger;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.dreams.dagger.DreamOverlayComponent;
import com.android.systemui.dreams.touch.DreamOverlayTouchMonitor;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;
import com.android.systemui.utils.leaks.LeakCheckedTest;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class DreamOverlayServiceTest extends SysuiTestCase {
    private static final ComponentName LOW_LIGHT_COMPONENT = new ComponentName("package",
            "lowlight");
    private static final String DREAM_COMPONENT = "package/dream";
    private final FakeSystemClock mFakeSystemClock = new FakeSystemClock();
    private final FakeExecutor mMainExecutor = new FakeExecutor(mFakeSystemClock);

    @Mock
    LifecycleOwner mLifecycleOwner;

    @Mock
    LifecycleRegistry mLifecycleRegistry;

    @Rule
    public final LeakCheckedTest.SysuiLeakCheck mLeakCheck = new LeakCheckedTest.SysuiLeakCheck();

    WindowManager.LayoutParams mWindowParams = new WindowManager.LayoutParams();

    @Mock
    IDreamOverlayCallback mDreamOverlayCallback;

    @Mock
    WindowManagerImpl mWindowManager;

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
        when(mDreamOverlayComponent.getLifecycleOwner())
                .thenReturn(mLifecycleOwner);
        when(mDreamOverlayComponent.getLifecycleRegistry())
                .thenReturn(mLifecycleRegistry);
        when(mDreamOverlayComponent.getDreamOverlayTouchMonitor())
                .thenReturn(mDreamOverlayTouchMonitor);
        when(mDreamOverlayComponentFactory
                .create(any(), any()))
                .thenReturn(mDreamOverlayComponent);
        when(mDreamOverlayContainerViewController.getContainerView())
                .thenReturn(mDreamOverlayContainerView);

        mService = new DreamOverlayService(mContext, mMainExecutor, mWindowManager,
                mDreamOverlayComponentFactory,
                mStateController,
                mKeyguardUpdateMonitor,
                mUiEventLogger,
                LOW_LIGHT_COMPONENT,
                mDreamOverlayCallbackController);
    }

    @Test
    public void testOnStartMetricsLogged() throws Exception {
        final IBinder proxy = mService.onBind(new Intent());
        final IDreamOverlay overlay = IDreamOverlay.Stub.asInterface(proxy);

        // Inform the overlay service of dream starting.
        overlay.startDream(mWindowParams, mDreamOverlayCallback, DREAM_COMPONENT,
                false /*shouldShowComplication*/);
        mMainExecutor.runAllReady();

        verify(mUiEventLogger).log(DreamOverlayService.DreamOverlayEvent.DREAM_OVERLAY_ENTER_START);
        verify(mUiEventLogger).log(
                DreamOverlayService.DreamOverlayEvent.DREAM_OVERLAY_COMPLETE_START);
    }

    @Test
    public void testOverlayContainerViewAddedToWindow() throws Exception {
        final IBinder proxy = mService.onBind(new Intent());
        final IDreamOverlay overlay = IDreamOverlay.Stub.asInterface(proxy);

        // Inform the overlay service of dream starting.
        overlay.startDream(mWindowParams, mDreamOverlayCallback, DREAM_COMPONENT,
                false /*shouldShowComplication*/);
        mMainExecutor.runAllReady();

        verify(mWindowManager).addView(any(), any());
    }

    @Test
    public void testDreamOverlayContainerViewControllerInitialized() throws Exception {
        final IBinder proxy = mService.onBind(new Intent());
        final IDreamOverlay overlay = IDreamOverlay.Stub.asInterface(proxy);

        // Inform the overlay service of dream starting.
        overlay.startDream(mWindowParams, mDreamOverlayCallback, DREAM_COMPONENT,
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

        final IBinder proxy = mService.onBind(new Intent());
        final IDreamOverlay overlay = IDreamOverlay.Stub.asInterface(proxy);

        // Inform the overlay service of dream starting.
        overlay.startDream(mWindowParams, mDreamOverlayCallback, DREAM_COMPONENT,
                false /*shouldShowComplication*/);
        mMainExecutor.runAllReady();

        verify(mDreamOverlayContainerViewParent).removeView(mDreamOverlayContainerView);
    }

    @Test
    public void testShouldShowComplicationsSetByStartDream() throws RemoteException {
        final IBinder proxy = mService.onBind(new Intent());
        final IDreamOverlay overlay = IDreamOverlay.Stub.asInterface(proxy);

        // Inform the overlay service of dream starting.
        overlay.startDream(mWindowParams, mDreamOverlayCallback, DREAM_COMPONENT,
                true /*shouldShowComplication*/);

        assertThat(mService.shouldShowComplications()).isTrue();
    }

    @Test
    public void testLowLightSetByStartDream() throws RemoteException {
        final IBinder proxy = mService.onBind(new Intent());
        final IDreamOverlay overlay = IDreamOverlay.Stub.asInterface(proxy);

        // Inform the overlay service of dream starting.
        overlay.startDream(mWindowParams, mDreamOverlayCallback,
                LOW_LIGHT_COMPONENT.flattenToString(), false /*shouldShowComplication*/);
        mMainExecutor.runAllReady();

        assertThat(mService.getDreamComponent()).isEqualTo(LOW_LIGHT_COMPONENT);
        verify(mStateController).setLowLightActive(true);
    }

    @Test
    public void testDestroy() throws RemoteException {
        final IBinder proxy = mService.onBind(new Intent());
        final IDreamOverlay overlay = IDreamOverlay.Stub.asInterface(proxy);

        // Inform the overlay service of dream starting.
        overlay.startDream(mWindowParams, mDreamOverlayCallback,
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
        final IBinder proxy = mService.onBind(new Intent());
        final IDreamOverlay overlay = IDreamOverlay.Stub.asInterface(proxy);

        // Destroy the service.
        mService.onDestroy();
        mMainExecutor.runAllReady();

        // Inform the overlay service of dream starting.
        overlay.startDream(mWindowParams, mDreamOverlayCallback, DREAM_COMPONENT,
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
        final IBinder proxy = mService.onBind(new Intent());
        final IDreamOverlay overlay = IDreamOverlay.Stub.asInterface(proxy);

        // Inform the overlay service of dream starting. Do not show dream complications.
        overlay.startDream(mWindowParams, mDreamOverlayCallback, DREAM_COMPONENT,
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
        overlay.startDream(mWindowParams, mDreamOverlayCallback, DREAM_COMPONENT,
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
        final IBinder proxy = mService.onBind(new Intent());
        final IDreamOverlay overlay = IDreamOverlay.Stub.asInterface(proxy);

        // Inform the overlay service of dream starting.
        overlay.startDream(mWindowParams, mDreamOverlayCallback, DREAM_COMPONENT,
                true /*shouldShowComplication*/);
        mMainExecutor.runAllReady();

        final Runnable callback = mock(Runnable.class);
        mService.onWakeUp(callback);
        mMainExecutor.runAllReady();
        verify(mDreamOverlayContainerViewController).wakeUp(callback, mMainExecutor);
        verify(mDreamOverlayCallbackController).onWakeUp();
    }

    @Test
    public void testWakeUpBeforeStartDoesNothing() {
        final Runnable callback = mock(Runnable.class);
        mService.onWakeUp(callback);
        mMainExecutor.runAllReady();
        verify(mDreamOverlayContainerViewController, never()).wakeUp(callback, mMainExecutor);
    }
}

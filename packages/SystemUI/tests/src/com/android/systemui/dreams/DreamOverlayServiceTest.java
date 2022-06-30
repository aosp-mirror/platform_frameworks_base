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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Intent;
import android.os.IBinder;
import android.service.dreams.DreamService;
import android.service.dreams.IDreamOverlay;
import android.service.dreams.IDreamOverlayCallback;
import android.testing.AndroidTestingRunner;
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
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class DreamOverlayServiceTest extends SysuiTestCase {
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

    DreamOverlayService mService;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mContext.addMockSystemService(WindowManager.class, mWindowManager);

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

        mService = new DreamOverlayService(mContext, mMainExecutor,
                mDreamOverlayComponentFactory,
                mStateController,
                mKeyguardUpdateMonitor,
                mUiEventLogger);
    }

    @Test
    public void testOnStartMetricsLogged() throws Exception {
        final IBinder proxy = mService.onBind(new Intent());
        final IDreamOverlay overlay = IDreamOverlay.Stub.asInterface(proxy);

        // Inform the overlay service of dream starting.
        overlay.startDream(mWindowParams, mDreamOverlayCallback);
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
        overlay.startDream(mWindowParams, mDreamOverlayCallback);
        mMainExecutor.runAllReady();

        verify(mWindowManager).addView(any(), any());
    }

    @Test
    public void testDreamOverlayContainerViewControllerInitialized() throws Exception {
        final IBinder proxy = mService.onBind(new Intent());
        final IDreamOverlay overlay = IDreamOverlay.Stub.asInterface(proxy);

        // Inform the overlay service of dream starting.
        overlay.startDream(mWindowParams, mDreamOverlayCallback);
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
        overlay.startDream(mWindowParams, mDreamOverlayCallback);
        mMainExecutor.runAllReady();

        verify(mDreamOverlayContainerViewParent).removeView(mDreamOverlayContainerView);
    }

    @Test
    public void testShouldShowComplicationsFalseByDefault() {
        mService.onBind(new Intent());

        assertThat(mService.shouldShowComplications()).isFalse();
    }

    @Test
    public void testShouldShowComplicationsSetByIntentExtra() {
        final Intent intent = new Intent();
        intent.putExtra(DreamService.EXTRA_SHOW_COMPLICATIONS, true);
        mService.onBind(intent);

        assertThat(mService.shouldShowComplications()).isTrue();
    }

    @Test
    public void testDestroy() {
        mService.onDestroy();
        mMainExecutor.runAllReady();

        verify(mKeyguardUpdateMonitor).removeCallback(any());
        verify(mLifecycleRegistry).setCurrentState(Lifecycle.State.DESTROYED);
        verify(mStateController).setOverlayActive(false);
    }

    @Test
    public void testDecorViewNotAddedToWindowAfterDestroy() throws Exception {
        when(mDreamOverlayContainerView.getParent())
                .thenReturn(mDreamOverlayContainerViewParent)
                .thenReturn(null);

        final IBinder proxy = mService.onBind(new Intent());
        final IDreamOverlay overlay = IDreamOverlay.Stub.asInterface(proxy);

        // Inform the overlay service of dream starting.
        overlay.startDream(mWindowParams, mDreamOverlayCallback);

        // Destroy the service.
        mService.onDestroy();

        // Run executor tasks.
        mMainExecutor.runAllReady();

        verify(mWindowManager, never()).addView(any(), any());
    }
}

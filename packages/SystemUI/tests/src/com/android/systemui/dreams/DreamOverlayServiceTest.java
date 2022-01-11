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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Intent;
import android.os.IBinder;
import android.service.dreams.DreamService;
import android.service.dreams.IDreamOverlay;
import android.service.dreams.IDreamOverlayCallback;
import android.testing.AndroidTestingRunner;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowManagerImpl;

import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.SysuiTestableContext;
import com.android.systemui.dreams.dagger.DreamOverlayComponent;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;
import com.android.systemui.utils.leaks.LeakCheckedTest;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class DreamOverlayServiceTest extends SysuiTestCase {
    private final FakeSystemClock mFakeSystemClock = new FakeSystemClock();
    private final FakeExecutor mMainExecutor = new FakeExecutor(mFakeSystemClock);

    @Rule
    public final LeakCheckedTest.SysuiLeakCheck mLeakCheck = new LeakCheckedTest.SysuiLeakCheck();

    @Rule
    public SysuiTestableContext mContext = new SysuiTestableContext(
            InstrumentationRegistry.getContext(), mLeakCheck);

    WindowManager.LayoutParams mWindowParams = new WindowManager.LayoutParams();

    @Mock
    IDreamOverlayCallback mDreamOverlayCallback;

    @Mock
    WindowManagerImpl mWindowManager;

    @Mock
    ComplicationProvider mProvider;

    @Mock
    DreamOverlayStateController mDreamOverlayStateController;

    @Mock
    DreamOverlayComponent.Factory mDreamOverlayComponentFactory;

    @Mock
    DreamOverlayComponent mDreamOverlayComponent;

    @Mock
    DreamOverlayContainerView mDreamOverlayContainerView;

    @Mock
    DreamOverlayContainerViewController mDreamOverlayContainerViewController;

    DreamOverlayService mService;

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);
        mContext.addMockSystemService(WindowManager.class, mWindowManager);

        when(mDreamOverlayComponent.getDreamOverlayContainerViewController())
                .thenReturn(mDreamOverlayContainerViewController);
        when(mDreamOverlayComponentFactory.create())
                .thenReturn(mDreamOverlayComponent);
        when(mDreamOverlayContainerViewController.getContainerView())
                .thenReturn(mDreamOverlayContainerView);

        mService = new DreamOverlayService(mContext, mMainExecutor,
                mDreamOverlayStateController, mDreamOverlayComponentFactory);
        final IBinder proxy = mService.onBind(new Intent());
        final IDreamOverlay overlay = IDreamOverlay.Stub.asInterface(proxy);

        // Inform the overlay service of dream starting.
        overlay.startDream(mWindowParams, mDreamOverlayCallback);
        mMainExecutor.runAllReady();
    }

    @Test
    public void testOverlayContainerViewAddedToWindow() {
        verify(mWindowManager).addView(any(), any());
    }

    @Test
    public void testDreamOverlayContainerViewControllerInitialized() {
        verify(mDreamOverlayContainerViewController).init();
    }

    @Test
    public void testAddingOverlayToDream() throws Exception {
        // Add overlay.
        mService.addComplication(mProvider);
        mMainExecutor.runAllReady();

        final ArgumentCaptor<ComplicationHost.CreationCallback> creationCallbackCapture =
                ArgumentCaptor.forClass(ComplicationHost.CreationCallback.class);
        final ArgumentCaptor<ComplicationHost.InteractionCallback> interactionCallbackCapture =
                ArgumentCaptor.forClass(ComplicationHost.InteractionCallback.class);

        // Ensure overlay provider is asked to create view.
        verify(mProvider).onCreateComplication(any(), creationCallbackCapture.capture(),
                interactionCallbackCapture.capture());
        mMainExecutor.runAllReady();

        // Inform service of overlay view creation.
        final View view = new View(mContext);
        final ConstraintLayout.LayoutParams lp = new ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        );
        creationCallbackCapture.getValue().onCreated(view, lp);
        mMainExecutor.runAllReady();

        // Verify that DreamOverlayContainerViewController is asked to add an overlay for the view.
        verify(mDreamOverlayContainerViewController).addOverlay(view, lp);
    }

    @Test
    public void testDreamOverlayExit() throws Exception {
        // Add overlay.
        mService.addComplication(mProvider);
        mMainExecutor.runAllReady();

        // Capture interaction callback from overlay creation.
        final ArgumentCaptor<ComplicationHost.InteractionCallback> interactionCallbackCapture =
                ArgumentCaptor.forClass(ComplicationHost.InteractionCallback.class);
        verify(mProvider).onCreateComplication(any(), any(), interactionCallbackCapture.capture());

        // Ask service to exit.
        interactionCallbackCapture.getValue().onExit();
        mMainExecutor.runAllReady();

        // Ensure service informs dream host of exit.
        verify(mDreamOverlayCallback).onExitRequested();
    }

    @Test
    public void testListenerRegisteredWithDreamOverlayStateController() {
        // Verify overlay service registered as listener with DreamOverlayStateController
        // and inform callback of addition.
        final ArgumentCaptor<DreamOverlayStateController.Callback> callbackCapture =
                ArgumentCaptor.forClass(DreamOverlayStateController.Callback.class);

        verify(mDreamOverlayStateController).addCallback(callbackCapture.capture());
        when(mDreamOverlayStateController.getComplications()).thenReturn(Arrays.asList(mProvider));
        callbackCapture.getValue().onComplicationsChanged();
        mMainExecutor.runAllReady();

        // Verify provider is asked to create overlay.
        verify(mProvider).onCreateComplication(any(), any(), any());
    }

    @Test
    public void testOnDestroyRemovesOverlayStateCallback() {
        final ArgumentCaptor<DreamOverlayStateController.Callback> callbackCapture =
                ArgumentCaptor.forClass(DreamOverlayStateController.Callback.class);
        verify(mDreamOverlayStateController).addCallback(callbackCapture.capture());
        mService.onDestroy();
        verify(mDreamOverlayStateController).removeCallback(callbackCapture.getValue());
    }

    @Test
    public void testShouldShowComplicationsTrueByDefault() {
        assertThat(mService.shouldShowComplications()).isTrue();

        mService.onBind(new Intent());

        assertThat(mService.shouldShowComplications()).isTrue();
    }

    @Test
    public void testShouldShowComplicationsSetByIntentExtra() {
        final Intent intent = new Intent();
        intent.putExtra(DreamService.EXTRA_SHOW_COMPLICATIONS, false);
        mService.onBind(intent);

        assertThat(mService.shouldShowComplications()).isFalse();
    }
}

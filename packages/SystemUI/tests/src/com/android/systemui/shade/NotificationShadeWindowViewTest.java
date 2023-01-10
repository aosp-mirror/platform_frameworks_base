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

package com.android.systemui.shade;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.SystemClock;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.MotionEvent;

import androidx.test.filters.SmallTest;

import com.android.keyguard.LockIconViewController;
import com.android.keyguard.dagger.KeyguardBouncerComponent;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.classifier.FalsingCollectorFake;
import com.android.systemui.dock.DockManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.keyguard.KeyguardUnlockAnimationController;
import com.android.systemui.keyguard.domain.interactor.AlternateBouncerInteractor;
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor;
import com.android.systemui.keyguard.ui.viewmodel.KeyguardBouncerViewModel;
import com.android.systemui.statusbar.DragDownHelper;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.NotificationInsetsController;
import com.android.systemui.statusbar.NotificationShadeDepthController;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.stack.AmbientState;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.window.StatusBarWindowStateController;
import com.android.systemui.tuner.TunerService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
public class NotificationShadeWindowViewTest extends SysuiTestCase {

    private NotificationShadeWindowView mView;
    private NotificationShadeWindowViewController mController;

    @Mock private TunerService mTunerService;
    @Mock private DragDownHelper mDragDownHelper;
    @Mock private SysuiStatusBarStateController mStatusBarStateController;
    @Mock private ShadeController mShadeController;
    @Mock private CentralSurfaces mCentralSurfaces;
    @Mock private DockManager mDockManager;
    @Mock private NotificationPanelViewController mNotificationPanelViewController;
    @Mock private NotificationStackScrollLayout mNotificationStackScrollLayout;
    @Mock private NotificationShadeDepthController mNotificationShadeDepthController;
    @Mock private NotificationShadeWindowController mNotificationShadeWindowController;
    @Mock private NotificationStackScrollLayoutController mNotificationStackScrollLayoutController;
    @Mock private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    @Mock private StatusBarWindowStateController mStatusBarWindowStateController;
    @Mock private LockscreenShadeTransitionController mLockscreenShadeTransitionController;
    @Mock private LockIconViewController mLockIconViewController;
    @Mock private KeyguardUnlockAnimationController mKeyguardUnlockAnimationController;
    @Mock private AmbientState mAmbientState;
    @Mock private PulsingGestureListener mPulsingGestureListener;
    @Mock private FeatureFlags mFeatureFlags;
    @Mock private KeyguardBouncerViewModel mKeyguardBouncerViewModel;
    @Mock private KeyguardBouncerComponent.Factory mKeyguardBouncerComponentFactory;
    @Mock private NotificationInsetsController mNotificationInsetsController;
    @Mock private AlternateBouncerInteractor mAlternateBouncerInteractor;
    @Mock private KeyguardTransitionInteractor mKeyguardTransitionInteractor;

    @Captor private ArgumentCaptor<NotificationShadeWindowView.InteractionEventHandler>
            mInteractionEventHandlerCaptor;
    private NotificationShadeWindowView.InteractionEventHandler mInteractionEventHandler;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mView = spy(new NotificationShadeWindowView(getContext(), null));
        when(mView.findViewById(R.id.notification_stack_scroller))
                .thenReturn(mNotificationStackScrollLayout);

        when(mStatusBarStateController.isDozing()).thenReturn(false);
        mDependency.injectTestDependency(ShadeController.class, mShadeController);

        when(mDockManager.isDocked()).thenReturn(false);

        mController = new NotificationShadeWindowViewController(
                mLockscreenShadeTransitionController,
                new FalsingCollectorFake(),
                mStatusBarStateController,
                mDockManager,
                mNotificationShadeDepthController,
                mView,
                mNotificationPanelViewController,
                new ShadeExpansionStateManager(),
                mNotificationStackScrollLayoutController,
                mStatusBarKeyguardViewManager,
                mStatusBarWindowStateController,
                mLockIconViewController,
                mCentralSurfaces,
                mNotificationShadeWindowController,
                mKeyguardUnlockAnimationController,
                mNotificationInsetsController,
                mAmbientState,
                mPulsingGestureListener,
                mFeatureFlags,
                mKeyguardBouncerViewModel,
                mKeyguardBouncerComponentFactory,
                mAlternateBouncerInteractor,
                mKeyguardTransitionInteractor
        );
        mController.setupExpandedStatusBar();
        mController.setDragDownHelper(mDragDownHelper);
    }

    @Test
    public void testDragDownHelperCalledWhenDraggingDown() {
        when(mDragDownHelper.isDraggingDown()).thenReturn(true);
        long now = SystemClock.elapsedRealtime();
        MotionEvent ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, 0 /* x */, 0 /* y */,
                0 /* meta */);
        mView.onTouchEvent(ev);
        verify(mDragDownHelper).onTouchEvent(ev);
        ev.recycle();
    }

    @Test
    public void testInterceptTouchWhenShowingAltAuth() {
        captureInteractionEventHandler();

        // WHEN showing alt auth, not dozing, drag down helper doesn't want to intercept
        when(mStatusBarStateController.isDozing()).thenReturn(false);
        when(mAlternateBouncerInteractor.isVisibleState()).thenReturn(true);
        when(mDragDownHelper.onInterceptTouchEvent(any())).thenReturn(false);

        // THEN we should intercept touch
        assertTrue(mInteractionEventHandler.shouldInterceptTouchEvent(mock(MotionEvent.class)));
    }

    @Test
    public void testNoInterceptTouch() {
        captureInteractionEventHandler();

        // WHEN not showing alt auth, not dozing, drag down helper doesn't want to intercept
        when(mStatusBarStateController.isDozing()).thenReturn(false);
        when(mAlternateBouncerInteractor.isVisibleState()).thenReturn(false);
        when(mDragDownHelper.onInterceptTouchEvent(any())).thenReturn(false);

        // THEN we shouldn't intercept touch
        assertFalse(mInteractionEventHandler.shouldInterceptTouchEvent(mock(MotionEvent.class)));
    }

    @Test
    public void testHandleTouchEventWhenShowingAltAuth() {
        captureInteractionEventHandler();

        // WHEN showing alt auth, not dozing, drag down helper doesn't want to intercept
        when(mStatusBarStateController.isDozing()).thenReturn(false);
        when(mAlternateBouncerInteractor.isVisibleState()).thenReturn(true);
        when(mDragDownHelper.onInterceptTouchEvent(any())).thenReturn(false);

        // THEN we should handle the touch
        assertTrue(mInteractionEventHandler.handleTouchEvent(mock(MotionEvent.class)));
    }

    private void captureInteractionEventHandler() {
        verify(mView).setInteractionEventHandler(mInteractionEventHandlerCaptor.capture());
        mInteractionEventHandler = mInteractionEventHandlerCaptor.getValue();

    }
}

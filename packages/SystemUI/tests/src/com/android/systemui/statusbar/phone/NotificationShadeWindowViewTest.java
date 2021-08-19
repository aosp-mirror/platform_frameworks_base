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

package com.android.systemui.statusbar.phone;

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

import com.android.systemui.R;
import com.android.systemui.SystemUIFactory;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.classifier.FalsingCollectorFake;
import com.android.systemui.dock.DockManager;
import com.android.systemui.doze.DozeLog;
import com.android.systemui.shared.plugins.PluginManager;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.DragDownHelper;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationShadeDepthController;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.PulseExpansionHandler;
import com.android.systemui.statusbar.SuperStatusBarViewFactory;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.DynamicPrivacyController;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.util.InjectionInflationController;

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

    @Mock private NotificationWakeUpCoordinator mCoordinator;
    @Mock private PulseExpansionHandler mPulseExpansionHandler;
    @Mock private DynamicPrivacyController mDynamicPrivacyController;
    @Mock private KeyguardBypassController mBypassController;
    @Mock private PluginManager mPluginManager;
    @Mock private TunerService mTunerService;
    @Mock private DragDownHelper mDragDownHelper;
    @Mock private KeyguardStateController mKeyguardStateController;
    @Mock private SysuiStatusBarStateController mStatusBarStateController;
    @Mock private ShadeController mShadeController;
    @Mock private NotificationLockscreenUserManager mNotificationLockScreenUserManager;
    @Mock private NotificationEntryManager mNotificationEntryManager;
    @Mock private StatusBar mStatusBar;
    @Mock private DozeLog mDozeLog;
    @Mock private DozeParameters mDozeParameters;
    @Mock private DockManager mDockManager;
    @Mock private NotificationPanelViewController mNotificationPanelViewController;
    @Mock private NotificationStackScrollLayout mNotificationStackScrollLayout;
    @Mock private NotificationShadeDepthController mNotificationShadeDepthController;
    @Mock private SuperStatusBarViewFactory mStatusBarViewFactory;
    @Mock private NotificationShadeWindowController mNotificationShadeWindowController;
    @Mock private NotificationStackScrollLayoutController mNotificationStackScrollLayoutController;
    @Mock private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    @Mock private LockscreenShadeTransitionController mLockscreenShadeTransitionController;

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
                new InjectionInflationController(
                        SystemUIFactory.getInstance()
                                .getSysUIComponent()
                                .createViewInstanceCreatorFactory()),
                mCoordinator,
                mPulseExpansionHandler,
                mDynamicPrivacyController,
                mBypassController,
                mLockscreenShadeTransitionController,
                new FalsingCollectorFake(),
                mPluginManager,
                mTunerService,
                mNotificationLockScreenUserManager,
                mNotificationEntryManager,
                mKeyguardStateController,
                mStatusBarStateController,
                mDozeLog,
                mDozeParameters,
                new CommandQueue(mContext),
                mShadeController,
                mDockManager,
                mNotificationShadeDepthController,
                mView,
                mNotificationPanelViewController,
                mStatusBarViewFactory,
                mNotificationStackScrollLayoutController,
                mStatusBarKeyguardViewManager);
        mController.setupExpandedStatusBar();
        mController.setService(mStatusBar, mNotificationShadeWindowController);
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
        when(mStatusBarKeyguardViewManager.isShowingAlternateAuthOrAnimating()).thenReturn(true);
        when(mDragDownHelper.onInterceptTouchEvent(any())).thenReturn(false);

        // THEN we should intercept touch
        assertTrue(mInteractionEventHandler.shouldInterceptTouchEvent(mock(MotionEvent.class)));
    }

    @Test
    public void testNoInterceptTouch() {
        captureInteractionEventHandler();

        // WHEN not showing alt auth, not dozing, drag down helper doesn't want to intercept
        when(mStatusBarStateController.isDozing()).thenReturn(false);
        when(mStatusBarKeyguardViewManager.isShowingAlternateAuthOrAnimating()).thenReturn(false);
        when(mDragDownHelper.onInterceptTouchEvent(any())).thenReturn(false);

        // THEN we shouldn't intercept touch
        assertFalse(mInteractionEventHandler.shouldInterceptTouchEvent(mock(MotionEvent.class)));
    }

    @Test
    public void testHandleTouchEventWhenShowingAltAuth() {
        captureInteractionEventHandler();

        // WHEN showing alt auth, not dozing, drag down helper doesn't want to intercept
        when(mStatusBarStateController.isDozing()).thenReturn(false);
        when(mStatusBarKeyguardViewManager.isShowingAlternateAuthOrAnimating()).thenReturn(true);
        when(mDragDownHelper.onInterceptTouchEvent(any())).thenReturn(false);

        // THEN we should handle the touch
        assertTrue(mInteractionEventHandler.handleTouchEvent(mock(MotionEvent.class)));
    }

    private void captureInteractionEventHandler() {
        verify(mView).setInteractionEventHandler(mInteractionEventHandlerCaptor.capture());
        mInteractionEventHandler = mInteractionEventHandlerCaptor.getValue();

    }
}

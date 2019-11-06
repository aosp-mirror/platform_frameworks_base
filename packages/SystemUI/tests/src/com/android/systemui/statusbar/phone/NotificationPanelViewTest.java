/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.StatusBarManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.MotionEvent;
import android.view.ViewGroup;

import androidx.test.filters.SmallTest;

import com.android.keyguard.KeyguardStatusView;
import com.android.systemui.SystemUIFactory;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.KeyguardAffordanceView;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationShelf;
import com.android.systemui.statusbar.PulseExpansionHandler;
import com.android.systemui.statusbar.StatusBarStateControllerImpl;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.DynamicPrivacyController;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator;
import com.android.systemui.statusbar.notification.stack.NotificationRoundnessManager;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.util.InjectionInflationController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.function.Consumer;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class NotificationPanelViewTest extends SysuiTestCase {

    @Mock
    private StatusBar mStatusBar;
    @Mock
    private SysuiStatusBarStateController mStatusBarStateController;
    @Mock
    private NotificationStackScrollLayout mNotificationStackScrollLayout;
    @Mock
    private KeyguardStatusView mKeyguardStatusView;
    @Mock
    private KeyguardBottomAreaView mKeyguardBottomArea;
    @Mock
    private KeyguardBottomAreaView mQsFrame;
    @Mock
    private ViewGroup mBigClockContainer;
    @Mock
    private ScrimController mScrimController;
    @Mock
    private NotificationIconAreaController mNotificationAreaController;
    @Mock
    private HeadsUpManagerPhone mHeadsUpManager;
    @Mock
    private NotificationShelf mNotificationShelf;
    @Mock
    private NotificationGroupManager mGroupManager;
    @Mock
    private KeyguardStatusBarView mKeyguardStatusBar;
    @Mock
    private HeadsUpTouchHelper.Callback mHeadsUpCallback;
    @Mock
    private PanelBar mPanelBar;
    @Mock
    private KeyguardAffordanceHelper mAffordanceHelper;
    private NotificationPanelView mNotificationPanelView;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mNotificationStackScrollLayout.getHeight()).thenReturn(1000);
        when(mNotificationStackScrollLayout.getHeadsUpCallback()).thenReturn(mHeadsUpCallback);
        when(mHeadsUpCallback.getContext()).thenReturn(mContext);
        mDependency.injectTestDependency(StatusBarStateController.class,
                mStatusBarStateController);
        mDependency.injectMockDependency(ShadeController.class);
        mDependency.injectMockDependency(NotificationLockscreenUserManager.class);
        mDependency.injectMockDependency(ConfigurationController.class);
        mDependency.injectMockDependency(ZenModeController.class);
        KeyguardBypassController bypassController = new KeyguardBypassController(mContext,
                mock(TunerService.class), mStatusBarStateController,
                mock(NotificationLockscreenUserManager.class));
        NotificationWakeUpCoordinator coordinator =
                new NotificationWakeUpCoordinator(mContext,
                        mock(HeadsUpManagerPhone.class),
                        new StatusBarStateControllerImpl(),
                        bypassController);
        PulseExpansionHandler expansionHandler = new PulseExpansionHandler(mContext, coordinator,
                bypassController, mHeadsUpManager, mock(NotificationRoundnessManager.class));
        mNotificationPanelView = new TestableNotificationPanelView(coordinator, expansionHandler,
                bypassController);
        mNotificationPanelView.setHeadsUpManager(mHeadsUpManager);
        mNotificationPanelView.setBar(mPanelBar);

        when(mKeyguardBottomArea.getLeftView()).thenReturn(mock(KeyguardAffordanceView.class));
        when(mKeyguardBottomArea.getRightView()).thenReturn(mock(KeyguardAffordanceView.class));
    }

    @Test
    public void testSetDozing_notifiesNsslAndStateController() {
        mNotificationPanelView.setDozing(true /* dozing */, true /* animate */, null /* touch */);
        InOrder inOrder = inOrder(mNotificationStackScrollLayout, mStatusBarStateController);
        inOrder.verify(mNotificationStackScrollLayout).setDozing(eq(true), eq(true), eq(null));
        inOrder.verify(mStatusBarStateController).setDozeAmount(eq(1f), eq(true));
    }

    @Test
    public void testSetExpandedHeight() {
        mNotificationPanelView.setExpandedHeight(200);
        assertThat((int) mNotificationPanelView.getExpandedHeight()).isEqualTo(200);
    }

    @Test
    public void testAffordanceLaunchingListener() {
        Consumer<Boolean> listener = spy((showing) -> { });
        mNotificationPanelView.setExpandedFraction(1f);
        mNotificationPanelView.setLaunchAffordanceListener(listener);
        mNotificationPanelView.launchCamera(false /* animate */,
                StatusBarManager.CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP);
        verify(listener).accept(eq(true));

        mNotificationPanelView.onAffordanceLaunchEnded();
        verify(listener).accept(eq(false));
    }

    @Test
    public void testOnTouchEvent_expansionCanBeBlocked() {
        mNotificationPanelView.onTouchEvent(MotionEvent.obtain(0L /* downTime */,
                0L /* eventTime */, MotionEvent.ACTION_DOWN, 0f /* x */, 0f /* y */,
                0 /* metaState */));
        mNotificationPanelView.onTouchEvent(MotionEvent.obtain(0L /* downTime */,
                0L /* eventTime */, MotionEvent.ACTION_MOVE, 0f /* x */, 200f /* y */,
                0 /* metaState */));
        assertThat((int) mNotificationPanelView.getExpandedHeight()).isEqualTo(200);
        assertThat(mNotificationPanelView.isTrackingBlocked()).isFalse();

        mNotificationPanelView.blockExpansionForCurrentTouch();
        mNotificationPanelView.onTouchEvent(MotionEvent.obtain(0L /* downTime */,
                0L /* eventTime */, MotionEvent.ACTION_MOVE, 0f /* x */, 300f /* y */,
                0 /* metaState */));
        // Expansion should not have changed because it was blocked
        assertThat((int) mNotificationPanelView.getExpandedHeight()).isEqualTo(200);
        assertThat(mNotificationPanelView.isTrackingBlocked()).isTrue();

        mNotificationPanelView.onTouchEvent(MotionEvent.obtain(0L /* downTime */,
                0L /* eventTime */, MotionEvent.ACTION_UP, 0f /* x */, 300f /* y */,
                0 /* metaState */));
        assertThat(mNotificationPanelView.isTrackingBlocked()).isFalse();
    }

    private class TestableNotificationPanelView extends NotificationPanelView {
        TestableNotificationPanelView(NotificationWakeUpCoordinator coordinator,
                PulseExpansionHandler expansionHandler,
                KeyguardBypassController bypassController) {
            super(NotificationPanelViewTest.this.mContext, null,
                    new InjectionInflationController(
                            SystemUIFactory.getInstance().getRootComponent()),
                    coordinator, expansionHandler, mock(DynamicPrivacyController.class),
                    bypassController);
            mNotificationStackScroller = mNotificationStackScrollLayout;
            mKeyguardStatusView = NotificationPanelViewTest.this.mKeyguardStatusView;
            mKeyguardStatusBar = NotificationPanelViewTest.this.mKeyguardStatusBar;
            mKeyguardBottomArea = NotificationPanelViewTest.this.mKeyguardBottomArea;
            mBigClockContainer = NotificationPanelViewTest.this.mBigClockContainer;
            mQsFrame = NotificationPanelViewTest.this.mQsFrame;
            mAffordanceHelper = NotificationPanelViewTest.this.mAffordanceHelper;
            initDependencies(NotificationPanelViewTest.this.mStatusBar,
                    NotificationPanelViewTest.this.mGroupManager,
                    NotificationPanelViewTest.this.mNotificationShelf,
                    NotificationPanelViewTest.this.mHeadsUpManager,
                    NotificationPanelViewTest.this.mNotificationAreaController,
                    NotificationPanelViewTest.this.mScrimController);
        }
    }
}

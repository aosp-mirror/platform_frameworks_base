/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import static android.view.Display.DEFAULT_DISPLAY;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import android.app.Fragment;
import android.app.StatusBarManager;
import android.content.Context;
import android.os.Bundle;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.view.View;
import android.view.ViewPropertyAnimator;
import android.widget.FrameLayout;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiBaseFragmentTest;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.OperatorNameViewController;
import com.android.systemui.statusbar.events.SystemStatusAnimationScheduler;
import com.android.systemui.statusbar.phone.ongoingcall.OngoingCallController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.NetworkController;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

import java.util.Optional;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper(setAsMainLooper = true)
@SmallTest
public class CollapsedStatusBarFragmentTest extends SysuiBaseFragmentTest {

    private NotificationIconAreaController mMockNotificationAreaController;
    private View mNotificationAreaInner;
    private OngoingCallController mOngoingCallController;
    private SystemStatusAnimationScheduler mAnimationScheduler;
    private StatusBarLocationPublisher mLocationPublisher;
    // Set in instantiate()
    private StatusBarIconController mStatusBarIconController;
    private NetworkController mNetworkController;
    private StatusBarStateController mStatusBarStateController;
    private KeyguardStateController mKeyguardStateController;

    private final StatusBar mStatusBar = mock(StatusBar.class);
    private final CommandQueue mCommandQueue = mock(CommandQueue.class);
    private OperatorNameViewController.Factory mOperatorNameViewControllerFactory;
    private OperatorNameViewController mOperatorNameViewController;

    public CollapsedStatusBarFragmentTest() {
        super(CollapsedStatusBarFragment.class);
    }

    @Before
    public void setup() {
        mStatusBarStateController = mDependency
                .injectMockDependency(StatusBarStateController.class);
        injectLeakCheckedDependencies(ALL_SUPPORTED_CLASSES);
        when(mStatusBar.getPanelController()).thenReturn(
                mock(NotificationPanelViewController.class));
    }

    @Test
    public void testDisableNone() throws Exception {
        mFragments.dispatchResume();
        processAllMessages();
        CollapsedStatusBarFragment fragment = (CollapsedStatusBarFragment) mFragment;

        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        assertEquals(View.VISIBLE, mFragment.getView().findViewById(R.id.system_icon_area)
                .getVisibility());
        assertEquals(View.VISIBLE, mFragment.getView().findViewById(R.id.clock)
                .getVisibility());
    }

    @Test
    public void testDisableSystemInfo() throws Exception {
        mFragments.dispatchResume();
        processAllMessages();
        CollapsedStatusBarFragment fragment = (CollapsedStatusBarFragment) mFragment;

        fragment.disable(DEFAULT_DISPLAY, StatusBarManager.DISABLE_SYSTEM_INFO, 0, false);

        assertEquals(View.INVISIBLE, mFragment.getView().findViewById(R.id.system_icon_area)
                .getVisibility());

        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        assertEquals(View.VISIBLE, mFragment.getView().findViewById(R.id.system_icon_area)
                .getVisibility());
    }

    @Test
    public void testDisableNotifications() throws Exception {
        mFragments.dispatchResume();
        processAllMessages();
        CollapsedStatusBarFragment fragment = (CollapsedStatusBarFragment) mFragment;

        fragment.disable(DEFAULT_DISPLAY, StatusBarManager.DISABLE_NOTIFICATION_ICONS, 0, false);

        Mockito.verify(mNotificationAreaInner, atLeast(1)).setVisibility(eq(View.INVISIBLE));

        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        Mockito.verify(mNotificationAreaInner, atLeast(1)).setVisibility(eq(View.VISIBLE));
    }

    @Test
    public void testDisableClock() throws Exception {
        mFragments.dispatchResume();
        processAllMessages();
        CollapsedStatusBarFragment fragment = (CollapsedStatusBarFragment) mFragment;

        fragment.disable(DEFAULT_DISPLAY, StatusBarManager.DISABLE_CLOCK, 0, false);

        assertEquals(View.GONE, mFragment.getView().findViewById(R.id.clock).getVisibility());

        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        assertEquals(View.VISIBLE, mFragment.getView().findViewById(R.id.clock).getVisibility());
    }

    @Test
    public void disable_noOngoingCall_chipHidden() {
        mFragments.dispatchResume();
        processAllMessages();
        CollapsedStatusBarFragment fragment = (CollapsedStatusBarFragment) mFragment;

        when(mOngoingCallController.hasOngoingCall()).thenReturn(false);

        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        assertEquals(View.GONE,
                mFragment.getView().findViewById(R.id.ongoing_call_chip).getVisibility());
    }

    @Test
    public void disable_hasOngoingCall_chipDisplayedAndNotificationIconsHidden() {
        mFragments.dispatchResume();
        processAllMessages();
        CollapsedStatusBarFragment fragment = (CollapsedStatusBarFragment) mFragment;

        when(mOngoingCallController.hasOngoingCall()).thenReturn(true);

        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        assertEquals(View.VISIBLE,
                mFragment.getView().findViewById(R.id.ongoing_call_chip).getVisibility());
        Mockito.verify(mNotificationAreaInner, atLeast(1)).setVisibility(eq(View.INVISIBLE));

    }

    @Test
    public void disable_hasOngoingCallButNotificationIconsDisabled_chipHidden() {
        mFragments.dispatchResume();
        processAllMessages();
        CollapsedStatusBarFragment fragment = (CollapsedStatusBarFragment) mFragment;

        when(mOngoingCallController.hasOngoingCall()).thenReturn(true);

        fragment.disable(DEFAULT_DISPLAY,
                StatusBarManager.DISABLE_NOTIFICATION_ICONS, 0, false);

        assertEquals(View.GONE,
                mFragment.getView().findViewById(R.id.ongoing_call_chip).getVisibility());
    }

    @Test
    public void disable_ongoingCallEnded_chipHidden() {
        mFragments.dispatchResume();
        processAllMessages();
        CollapsedStatusBarFragment fragment = (CollapsedStatusBarFragment) mFragment;

        when(mOngoingCallController.hasOngoingCall()).thenReturn(true);

        // Ongoing call started
        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);
        assertEquals(View.VISIBLE,
                mFragment.getView().findViewById(R.id.ongoing_call_chip).getVisibility());

        // Ongoing call ended
        when(mOngoingCallController.hasOngoingCall()).thenReturn(false);

        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        assertEquals(View.GONE,
                mFragment.getView().findViewById(R.id.ongoing_call_chip).getVisibility());
    }

    @Ignore("b/192618546")
    @Test
    public void testOnDozingChanged() throws Exception {
        mFragments.dispatchResume();
        processAllMessages();
        CollapsedStatusBarFragment fragment = (CollapsedStatusBarFragment) mFragment;

        fragment.disable(DEFAULT_DISPLAY, StatusBarManager.DISABLE_NOTIFICATION_ICONS, 0, false);

        Mockito.verify(mNotificationAreaInner, atLeast(1)).setVisibility(eq(View.INVISIBLE));

        reset(mStatusBarStateController);
        when(mStatusBarStateController.isDozing()).thenReturn(true);
        fragment.onDozingChanged(true);

        Mockito.verify(mStatusBarStateController).isDozing();
        Mockito.verify(mNotificationAreaInner, atLeast(1)).setVisibility(eq(View.VISIBLE));
    }

    @Override
    protected Fragment instantiate(Context context, String className, Bundle arguments) {
        mOngoingCallController = mock(OngoingCallController.class);
        mAnimationScheduler = mock(SystemStatusAnimationScheduler.class);
        mLocationPublisher = mock(StatusBarLocationPublisher.class);
        mStatusBarIconController = mock(StatusBarIconController.class);
        mNetworkController = mock(NetworkController.class);
        mStatusBarStateController = mock(StatusBarStateController.class);
        mKeyguardStateController = mock(KeyguardStateController.class);
        mOperatorNameViewController = mock(OperatorNameViewController.class);
        mOperatorNameViewControllerFactory = mock(OperatorNameViewController.Factory.class);
        when(mOperatorNameViewControllerFactory.create(any()))
                .thenReturn(mOperatorNameViewController);

        setUpNotificationIconAreaController();
        return new CollapsedStatusBarFragment(
                mOngoingCallController,
                mAnimationScheduler,
                mLocationPublisher,
                mMockNotificationAreaController,
                mock(FeatureFlags.class),
                () -> Optional.of(mStatusBar),
                mStatusBarIconController,
                mKeyguardStateController,
                mNetworkController,
                mStatusBarStateController,
                mCommandQueue,
                mOperatorNameViewControllerFactory);
    }

    private void setUpNotificationIconAreaController() {
        mMockNotificationAreaController = mock(NotificationIconAreaController.class);

        mNotificationAreaInner = mock(View.class);
        View centeredNotificationAreaView = mock(View.class);

        when(mNotificationAreaInner.getLayoutParams()).thenReturn(
                new FrameLayout.LayoutParams(100, 100));
        when(centeredNotificationAreaView.getLayoutParams()).thenReturn(
               new FrameLayout.LayoutParams(100, 100));
        when(mNotificationAreaInner.animate()).thenReturn(mock(ViewPropertyAnimator.class));
        when(centeredNotificationAreaView.animate()).thenReturn(mock(ViewPropertyAnimator.class));

        when(mMockNotificationAreaController.getCenteredNotificationAreaView()).thenReturn(
                centeredNotificationAreaView);
        when(mMockNotificationAreaController.getNotificationInnerAreaView()).thenReturn(
                mNotificationAreaInner);
    }
}

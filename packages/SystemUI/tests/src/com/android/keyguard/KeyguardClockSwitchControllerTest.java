/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.keyguard;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

import com.android.internal.colorextraction.ColorExtractor;
import com.android.keyguard.clock.ClockManager;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.plugins.BcSmartspaceDataPlugin;
import com.android.systemui.plugins.ClockPlugin;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shared.plugins.PluginManager;
import com.android.systemui.statusbar.FeatureFlags;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.phone.NotificationIconAreaController;
import com.android.systemui.statusbar.phone.NotificationIconContainer;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.verification.VerificationMode;

import java.util.concurrent.Executor;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class KeyguardClockSwitchControllerTest extends SysuiTestCase {

    @Mock
    private StatusBarStateController mStatusBarStateController;
    @Mock
    private SysuiColorExtractor mColorExtractor;
    @Mock
    private ClockManager mClockManager;
    @Mock
    private KeyguardClockSwitch mView;
    @Mock
    private NotificationIconContainer mNotificationIcons;
    @Mock
    private ClockPlugin mClockPlugin;
    @Mock
    ColorExtractor.GradientColors mGradientColors;
    @Mock
    KeyguardSliceViewController mKeyguardSliceViewController;
    @Mock
    Resources mResources;
    @Mock
    NotificationIconAreaController mNotificationIconAreaController;
    @Mock
    ContentResolver mContentResolver;
    @Mock
    BroadcastDispatcher mBroadcastDispatcher;
    @Mock
    private PluginManager mPluginManager;
    @Mock
    private FeatureFlags mFeatureFlags;
    @Mock
    private Executor mExecutor;

    private KeyguardClockSwitchController mController;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        when(mView.findViewById(com.android.systemui.R.id.left_aligned_notification_icon_container))
                .thenReturn(mNotificationIcons);
        when(mView.getContext()).thenReturn(getContext());
        when(mFeatureFlags.isSmartspaceEnabled()).thenReturn(true);
        when(mView.isAttachedToWindow()).thenReturn(true);
        when(mResources.getString(anyInt())).thenReturn("h:mm");
        mController = new KeyguardClockSwitchController(
                mView,
                mResources,
                mStatusBarStateController,
                mColorExtractor,
                mClockManager,
                mKeyguardSliceViewController,
                mNotificationIconAreaController,
                mContentResolver,
                mBroadcastDispatcher,
                mPluginManager,
                mFeatureFlags,
                mExecutor);

        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.SHADE);
        when(mColorExtractor.getColors(anyInt())).thenReturn(mGradientColors);
    }

    @Test
    public void testInit_viewAlreadyAttached() {
        mController.init();

        verifyAttachment(times(1));
    }

    @Test
    public void testInit_viewNotYetAttached() {
        ArgumentCaptor<View.OnAttachStateChangeListener> listenerArgumentCaptor =
                ArgumentCaptor.forClass(View.OnAttachStateChangeListener.class);

        when(mView.isAttachedToWindow()).thenReturn(false);
        mController.init();
        verify(mView).addOnAttachStateChangeListener(listenerArgumentCaptor.capture());

        verifyAttachment(never());

        listenerArgumentCaptor.getValue().onViewAttachedToWindow(mView);

        verifyAttachment(times(1));
    }

    @Test
    public void testInitSubControllers() {
        mController.init();
        verify(mKeyguardSliceViewController).init();
    }

    @Test
    public void testInit_viewDetached() {
        ArgumentCaptor<View.OnAttachStateChangeListener> listenerArgumentCaptor =
                ArgumentCaptor.forClass(View.OnAttachStateChangeListener.class);
        mController.init();
        verify(mView).addOnAttachStateChangeListener(listenerArgumentCaptor.capture());

        verifyAttachment(times(1));

        listenerArgumentCaptor.getValue().onViewDetachedFromWindow(mView);

        verify(mStatusBarStateController).removeCallback(
                any(StatusBarStateController.StateListener.class));
        verify(mColorExtractor).removeOnColorsChangedListener(
                any(ColorExtractor.OnColorsChangedListener.class));
    }

    @Test
    public void testBigClockPassesStatusBarState() {
        ViewGroup testView = new FrameLayout(mContext);

        mController.init();
        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.SHADE);
        mController.setBigClockContainer(testView);
        verify(mView).setBigClockContainer(testView, StatusBarState.SHADE);


        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.KEYGUARD);
        mController.setBigClockContainer(testView);
        verify(mView).setBigClockContainer(testView, StatusBarState.KEYGUARD);


        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.SHADE_LOCKED);
        mController.setBigClockContainer(testView);
        verify(mView).setBigClockContainer(testView, StatusBarState.SHADE_LOCKED);
    }

    @Test
    public void testPluginPassesStatusBarState() {
        ArgumentCaptor<ClockManager.ClockChangedListener> listenerArgumentCaptor =
                ArgumentCaptor.forClass(ClockManager.ClockChangedListener.class);

        mController.init();
        verify(mClockManager).addOnClockChangedListener(listenerArgumentCaptor.capture());

        listenerArgumentCaptor.getValue().onClockChanged(mClockPlugin);
        verify(mView).setClockPlugin(mClockPlugin, StatusBarState.SHADE);
    }

    @Test
    public void testSmartspacePluginConnectedRemovesKeyguardStatusArea() {
        mController.init();

        View statusArea = mock(View.class);
        when(mView.findViewById(R.id.keyguard_status_area)).thenReturn(statusArea);

        View nic = mock(View.class);
        when(mView.findViewById(R.id.left_aligned_notification_icon_container)).thenReturn(nic);
        when(nic.getLayoutParams()).thenReturn(mock(RelativeLayout.LayoutParams.class));

        BcSmartspaceDataPlugin plugin = mock(BcSmartspaceDataPlugin.class);
        TestView view = mock(TestView.class);
        when(plugin.getView(any())).thenReturn(view);

        mController.mPluginListener.onPluginConnected(plugin, mContext);
        verify(statusArea).setVisibility(View.GONE);
    }

    @Test
    public void testSmartspacePluginDisconnectedShowsKeyguardStatusArea() {
        mController.init();

        View statusArea = mock(View.class);
        when(mView.findViewById(R.id.keyguard_status_area)).thenReturn(statusArea);

        View nic = mock(View.class);
        when(mView.findViewById(R.id.left_aligned_notification_icon_container)).thenReturn(nic);
        when(nic.getLayoutParams()).thenReturn(mock(RelativeLayout.LayoutParams.class));

        BcSmartspaceDataPlugin plugin = mock(BcSmartspaceDataPlugin.class);
        TestView view = mock(TestView.class);
        when(plugin.getView(any())).thenReturn(view);

        mController.mPluginListener.onPluginConnected(plugin, mContext);
        mController.mPluginListener.onPluginDisconnected(plugin);
        verify(statusArea).setVisibility(View.VISIBLE);
    }

    private void verifyAttachment(VerificationMode times) {
        verify(mClockManager, times).addOnClockChangedListener(
                any(ClockManager.ClockChangedListener.class));
        verify(mStatusBarStateController, times).addCallback(
                any(StatusBarStateController.StateListener.class));
        verify(mColorExtractor, times).addOnColorsChangedListener(
                any(ColorExtractor.OnColorsChangedListener.class));
        verify(mView, times).updateColors(mGradientColors);
    }

    private static class TestView extends View implements BcSmartspaceDataPlugin.SmartspaceView {
        TestView(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        public void registerDataProvider(BcSmartspaceDataPlugin plugin) { }
    }
}

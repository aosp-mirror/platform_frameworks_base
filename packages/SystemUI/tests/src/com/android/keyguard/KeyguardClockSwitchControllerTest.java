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

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import androidx.test.filters.SmallTest;

import com.android.internal.colorextraction.ColorExtractor;
import com.android.keyguard.clock.ClockManager;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.KeyguardUnlockAnimationController;
import com.android.systemui.plugins.ClockPlugin;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.lockscreen.LockscreenSmartspaceController;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.NotificationIconAreaController;
import com.android.systemui.statusbar.phone.NotificationIconContainer;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.settings.SecureSettings;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.verification.VerificationMode;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class KeyguardClockSwitchControllerTest extends SysuiTestCase {

    @Mock
    private KeyguardClockSwitch mView;
    @Mock
    private StatusBarStateController mStatusBarStateController;
    @Mock
    private SysuiColorExtractor mColorExtractor;
    @Mock
    private ClockManager mClockManager;
    @Mock
    KeyguardSliceViewController mKeyguardSliceViewController;
    @Mock
    NotificationIconAreaController mNotificationIconAreaController;
    @Mock
    BroadcastDispatcher mBroadcastDispatcher;
    @Mock
    BatteryController mBatteryController;
    @Mock
    KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock
    KeyguardBypassController mBypassController;
    @Mock
    LockscreenSmartspaceController mSmartspaceController;

    @Mock
    Resources mResources;
    @Mock
    KeyguardUnlockAnimationController mKeyguardUnlockAnimationController;
    @Mock
    private ClockPlugin mClockPlugin;
    @Mock
    ColorExtractor.GradientColors mGradientColors;
    @Mock
    DumpManager mDumpManager;

    @Mock
    private NotificationIconContainer mNotificationIcons;
    @Mock
    private AnimatableClockView mClockView;
    @Mock
    private AnimatableClockView mLargeClockView;
    @Mock
    private FrameLayout mLargeClockFrame;
    @Mock
    private SecureSettings mSecureSettings;

    private final View mFakeSmartspaceView = new View(mContext);

    private KeyguardClockSwitchController mController;
    private View mSliceView;
    private FakeExecutor mExecutor;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        when(mView.findViewById(R.id.left_aligned_notification_icon_container))
                .thenReturn(mNotificationIcons);
        when(mNotificationIcons.getLayoutParams()).thenReturn(
                mock(RelativeLayout.LayoutParams.class));
        when(mView.getContext()).thenReturn(getContext());
        when(mView.getResources()).thenReturn(mResources);

        when(mView.findViewById(R.id.animatable_clock_view)).thenReturn(mClockView);
        when(mView.findViewById(R.id.animatable_clock_view_large)).thenReturn(mLargeClockView);
        when(mView.findViewById(R.id.lockscreen_clock_view_large)).thenReturn(mLargeClockFrame);
        when(mClockView.getContext()).thenReturn(getContext());
        when(mLargeClockView.getContext()).thenReturn(getContext());

        when(mView.isAttachedToWindow()).thenReturn(true);
        when(mSmartspaceController.buildAndConnectView(any())).thenReturn(mFakeSmartspaceView);
        mExecutor = new FakeExecutor(new FakeSystemClock());
        mController = new KeyguardClockSwitchController(
                mView,
                mStatusBarStateController,
                mColorExtractor,
                mClockManager,
                mKeyguardSliceViewController,
                mNotificationIconAreaController,
                mBroadcastDispatcher,
                mBatteryController,
                mKeyguardUpdateMonitor,
                mSmartspaceController,
                mKeyguardUnlockAnimationController,
                mSecureSettings,
                mExecutor,
                mResources,
                mDumpManager
        );

        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.SHADE);
        when(mColorExtractor.getColors(anyInt())).thenReturn(mGradientColors);

        mSliceView = new View(getContext());
        when(mView.findViewById(R.id.keyguard_slice_view)).thenReturn(mSliceView);
        when(mView.findViewById(R.id.keyguard_status_area)).thenReturn(
                new LinearLayout(getContext()));
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
        verify(mColorExtractor).removeOnColorsChangedListener(
                any(ColorExtractor.OnColorsChangedListener.class));
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
    public void testSmartspaceEnabledRemovesKeyguardStatusArea() {
        when(mSmartspaceController.isEnabled()).thenReturn(true);
        mController.init();

        assertEquals(View.GONE, mSliceView.getVisibility());
    }

    @Test
    public void onLocaleListChangedRebuildsSmartspaceView() {
        when(mSmartspaceController.isEnabled()).thenReturn(true);
        mController.init();

        mController.onLocaleListChanged();
        // Should be called once on initial setup, then once again for locale change
        verify(mSmartspaceController, times(2)).buildAndConnectView(mView);
    }

    @Test
    public void testSmartspaceDisabledShowsKeyguardStatusArea() {
        when(mSmartspaceController.isEnabled()).thenReturn(false);
        mController.init();

        assertEquals(View.VISIBLE, mSliceView.getVisibility());
    }

    @Test
    public void testRefresh() {
        mController.refresh();

        verify(mSmartspaceController).requestSmartspaceUpdate();
    }

    @Test
    public void testChangeToDoubleLineClockSetsSmallClock() {
        when(mSecureSettings.getIntForUser(Settings.Secure.LOCKSCREEN_USE_DOUBLE_LINE_CLOCK, 1,
                UserHandle.USER_CURRENT))
                .thenReturn(0);
        ArgumentCaptor<ContentObserver> observerCaptor =
                ArgumentCaptor.forClass(ContentObserver.class);
        mController.init();
        verify(mSecureSettings).registerContentObserverForUser(any(Uri.class),
                anyBoolean(), observerCaptor.capture(), eq(UserHandle.USER_ALL));
        ContentObserver observer = observerCaptor.getValue();
        mExecutor.runAllReady();

        // When a settings change has occurred to the small clock, make sure the view is adjusted
        reset(mView);
        observer.onChange(true);
        mExecutor.runAllReady();
        verify(mView).switchToClock(KeyguardClockSwitch.SMALL, /* animate */ true);
    }

    private void verifyAttachment(VerificationMode times) {
        verify(mClockManager, times).addOnClockChangedListener(
                any(ClockManager.ClockChangedListener.class));
        verify(mColorExtractor, times).addOnColorsChangedListener(
                any(ColorExtractor.OnColorsChangedListener.class));
        verify(mView, times).updateColors(mGradientColors);
    }
}

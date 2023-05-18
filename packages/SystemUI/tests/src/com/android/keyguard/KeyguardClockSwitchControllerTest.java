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
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.Resources;
import android.database.ContentObserver;
import android.os.UserHandle;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.KeyguardUnlockAnimationController;
import com.android.systemui.log.LogBuffer;
import com.android.systemui.plugins.ClockAnimations;
import com.android.systemui.plugins.ClockController;
import com.android.systemui.plugins.ClockEvents;
import com.android.systemui.plugins.ClockFaceConfig;
import com.android.systemui.plugins.ClockFaceController;
import com.android.systemui.plugins.ClockFaceEvents;
import com.android.systemui.plugins.ClockTickRate;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shared.clocks.AnimatableClockView;
import com.android.systemui.shared.clocks.ClockRegistry;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.lockscreen.LockscreenSmartspaceController;
import com.android.systemui.statusbar.phone.NotificationIconAreaController;
import com.android.systemui.statusbar.phone.NotificationIconContainer;
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
    private ClockRegistry mClockRegistry;
    @Mock
    KeyguardSliceViewController mKeyguardSliceViewController;
    @Mock
    NotificationIconAreaController mNotificationIconAreaController;
    @Mock
    LockscreenSmartspaceController mSmartspaceController;

    @Mock
    Resources mResources;
    @Mock
    KeyguardUnlockAnimationController mKeyguardUnlockAnimationController;
    @Mock
    private ClockController mClockController;
    @Mock
    private ClockFaceController mLargeClockController;
    @Mock
    private ClockFaceController mSmallClockController;
    @Mock
    private ClockAnimations mClockAnimations;
    @Mock
    private ClockEvents mClockEvents;
    @Mock
    private ClockFaceEvents mClockFaceEvents;
    @Mock
    DumpManager mDumpManager;
    @Mock
    ClockEventController mClockEventController;

    @Mock
    private NotificationIconContainer mNotificationIcons;
    @Mock
    private AnimatableClockView mSmallClockView;
    @Mock
    private AnimatableClockView mLargeClockView;
    @Mock
    private FrameLayout mSmallClockFrame;
    @Mock
    private FrameLayout mLargeClockFrame;
    @Mock
    private SecureSettings mSecureSettings;
    @Mock
    private LogBuffer mLogBuffer;

    private final View mFakeDateView = (View) (new ViewGroup(mContext) {
        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {}
    });
    private final View mFakeWeatherView = new View(mContext);
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
        when(mResources.getDimensionPixelSize(R.dimen.keyguard_clock_top_margin))
                .thenReturn(100);
        when(mResources.getDimensionPixelSize(R.dimen.keyguard_large_clock_top_margin))
                .thenReturn(-200);

        when(mView.findViewById(R.id.lockscreen_clock_view_large)).thenReturn(mLargeClockFrame);
        when(mView.findViewById(R.id.lockscreen_clock_view)).thenReturn(mSmallClockFrame);
        when(mSmallClockView.getContext()).thenReturn(getContext());
        when(mLargeClockView.getContext()).thenReturn(getContext());

        when(mView.isAttachedToWindow()).thenReturn(true);
        when(mSmartspaceController.buildAndConnectDateView(any())).thenReturn(mFakeDateView);
        when(mSmartspaceController.buildAndConnectWeatherView(any())).thenReturn(mFakeWeatherView);
        when(mSmartspaceController.buildAndConnectView(any())).thenReturn(mFakeSmartspaceView);
        mExecutor = new FakeExecutor(new FakeSystemClock());
        mController = new KeyguardClockSwitchController(
                mView,
                mStatusBarStateController,
                mClockRegistry,
                mKeyguardSliceViewController,
                mNotificationIconAreaController,
                mSmartspaceController,
                mKeyguardUnlockAnimationController,
                mSecureSettings,
                mExecutor,
                mDumpManager,
                mClockEventController,
                mLogBuffer
        );

        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.SHADE);
        when(mLargeClockController.getView()).thenReturn(mLargeClockView);
        when(mSmallClockController.getView()).thenReturn(mSmallClockView);
        when(mClockController.getLargeClock()).thenReturn(mLargeClockController);
        when(mClockController.getSmallClock()).thenReturn(mSmallClockController);
        when(mClockController.getEvents()).thenReturn(mClockEvents);
        when(mSmallClockController.getEvents()).thenReturn(mClockFaceEvents);
        when(mLargeClockController.getEvents()).thenReturn(mClockFaceEvents);
        when(mLargeClockController.getAnimations()).thenReturn(mClockAnimations);
        when(mSmallClockController.getAnimations()).thenReturn(mClockAnimations);
        when(mClockRegistry.createCurrentClock()).thenReturn(mClockController);
        when(mClockEventController.getClock()).thenReturn(mClockController);
        when(mSmallClockController.getConfig())
                .thenReturn(new ClockFaceConfig(ClockTickRate.PER_MINUTE, false, false));
        when(mLargeClockController.getConfig())
                .thenReturn(new ClockFaceConfig(ClockTickRate.PER_MINUTE, false, false));

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
        verify(mClockEventController).unregisterListeners();
    }

    @Test
    public void testPluginPassesStatusBarState() {
        ArgumentCaptor<ClockRegistry.ClockChangeListener> listenerArgumentCaptor =
                ArgumentCaptor.forClass(ClockRegistry.ClockChangeListener.class);

        mController.init();
        verify(mClockRegistry).registerClockChangeListener(listenerArgumentCaptor.capture());

        listenerArgumentCaptor.getValue().onCurrentClockChanged();
        verify(mView, times(2)).setClock(mClockController, StatusBarState.SHADE);
        verify(mClockEventController, times(2)).setClock(mClockController);
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
    public void onLocaleListChanged_rebuildsSmartspaceViews_whenDecouplingEnabled() {
        when(mSmartspaceController.isEnabled()).thenReturn(true);
        when(mSmartspaceController.isDateWeatherDecoupled()).thenReturn(true);
        mController.init();

        mController.onLocaleListChanged();
        // Should be called once on initial setup, then once again for locale change
        verify(mSmartspaceController, times(2)).buildAndConnectDateView(mView);
        verify(mSmartspaceController, times(2)).buildAndConnectWeatherView(mView);
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
        verify(mSecureSettings).registerContentObserverForUser(
                eq(Settings.Secure.LOCKSCREEN_USE_DOUBLE_LINE_CLOCK),
                    anyBoolean(), observerCaptor.capture(), eq(UserHandle.USER_ALL));
        ContentObserver observer = observerCaptor.getValue();
        mExecutor.runAllReady();

        // When a settings change has occurred to the small clock, make sure the view is adjusted
        reset(mView);
        observer.onChange(true);
        mExecutor.runAllReady();
        verify(mView).switchToClock(KeyguardClockSwitch.SMALL, /* animate */ true);
    }

    @Test
    public void testGetClock_ForwardsToClock() {
        assertEquals(mClockController, mController.getClock());
    }

    @Test
    public void testGetLargeClockBottom_returnsExpectedValue() {
        when(mLargeClockFrame.getVisibility()).thenReturn(View.VISIBLE);
        when(mLargeClockFrame.getHeight()).thenReturn(100);
        when(mSmallClockFrame.getHeight()).thenReturn(50);
        when(mLargeClockView.getHeight()).thenReturn(40);
        when(mSmallClockView.getHeight()).thenReturn(20);
        mController.init();

        assertEquals(170, mController.getClockBottom(1000));
    }

    @Test
    public void testGetSmallLargeClockBottom_returnsExpectedValue() {
        when(mLargeClockFrame.getVisibility()).thenReturn(View.GONE);
        when(mLargeClockFrame.getHeight()).thenReturn(100);
        when(mSmallClockFrame.getHeight()).thenReturn(50);
        when(mLargeClockView.getHeight()).thenReturn(40);
        when(mSmallClockView.getHeight()).thenReturn(20);
        mController.init();

        assertEquals(1120, mController.getClockBottom(1000));
    }

    @Test
    public void testGetClockBottom_nullClock_returnsZero() {
        when(mClockEventController.getClock()).thenReturn(null);
        assertEquals(0, mController.getClockBottom(10));
    }

    @Test
    public void testChangeLockscreenWeatherEnabledSetsWeatherViewVisible() {
        when(mSmartspaceController.isWeatherEnabled()).thenReturn(true);
        ArgumentCaptor<ContentObserver> observerCaptor =
                ArgumentCaptor.forClass(ContentObserver.class);
        mController.init();
        verify(mSecureSettings).registerContentObserverForUser(
                eq(Settings.Secure.LOCK_SCREEN_WEATHER_ENABLED), anyBoolean(),
                    observerCaptor.capture(), eq(UserHandle.USER_ALL));
        ContentObserver observer = observerCaptor.getValue();
        mExecutor.runAllReady();
        // When a settings change has occurred, check that view is visible.
        observer.onChange(true);
        mExecutor.runAllReady();
        assertEquals(View.VISIBLE, mFakeWeatherView.getVisibility());
    }

    @Test
    public void testChangeClockDateWeatherEnabled_SetsDateWeatherViewVisibility() {
        ArgumentCaptor<ClockRegistry.ClockChangeListener> listenerArgumentCaptor =
                ArgumentCaptor.forClass(ClockRegistry.ClockChangeListener.class);
        when(mSmartspaceController.isEnabled()).thenReturn(true);
        when(mSmartspaceController.isDateWeatherDecoupled()).thenReturn(true);
        when(mSmartspaceController.isWeatherEnabled()).thenReturn(true);
        mController.init();
        mExecutor.runAllReady();
        assertEquals(View.VISIBLE, mFakeDateView.getVisibility());

        when(mSmallClockController.getConfig())
                .thenReturn(new ClockFaceConfig(ClockTickRate.PER_MINUTE, true, false));
        when(mLargeClockController.getConfig())
                .thenReturn(new ClockFaceConfig(ClockTickRate.PER_MINUTE, true, false));
        verify(mClockRegistry).registerClockChangeListener(listenerArgumentCaptor.capture());
        listenerArgumentCaptor.getValue().onCurrentClockChanged();

        mExecutor.runAllReady();
        assertEquals(View.INVISIBLE, mFakeDateView.getVisibility());
    }

    @Test
    public void testGetClock_nullClock_returnsNull() {
        when(mClockEventController.getClock()).thenReturn(null);
        assertNull(mController.getClock());
    }

    private void verifyAttachment(VerificationMode times) {
        verify(mClockRegistry, times).registerClockChangeListener(
                any(ClockRegistry.ClockChangeListener.class));
        verify(mClockEventController, times).registerListeners(mView);
    }
}

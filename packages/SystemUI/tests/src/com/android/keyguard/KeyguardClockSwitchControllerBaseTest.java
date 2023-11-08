/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.view.View.INVISIBLE;

import static com.android.systemui.flags.Flags.FACE_AUTH_REFACTOR;
import static com.android.systemui.flags.Flags.LOCKSCREEN_WALLPAPER_DREAM_ENABLED;
import static com.android.systemui.flags.Flags.MIGRATE_CLOCKS_TO_BLUEPRINT;
import static com.android.systemui.flags.Flags.MIGRATE_KEYGUARD_STATUS_VIEW;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.common.ui.ConfigurationState;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FakeFeatureFlags;
import com.android.systemui.keyguard.KeyguardUnlockAnimationController;
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor;
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractorFactory;
import com.android.systemui.keyguard.ui.view.InWindowLauncherUnlockAnimationManager;
import com.android.systemui.keyguard.ui.viewmodel.KeyguardRootViewModel;
import com.android.systemui.log.LogBuffer;
import com.android.systemui.plugins.ClockAnimations;
import com.android.systemui.plugins.ClockController;
import com.android.systemui.plugins.ClockEvents;
import com.android.systemui.plugins.ClockFaceConfig;
import com.android.systemui.plugins.ClockFaceController;
import com.android.systemui.plugins.ClockFaceEvents;
import com.android.systemui.plugins.ClockTickRate;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.res.R;
import com.android.systemui.shared.clocks.AnimatableClockView;
import com.android.systemui.shared.clocks.ClockRegistry;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.lockscreen.LockscreenSmartspaceController;
import com.android.systemui.statusbar.notification.icon.ui.viewbinder.AlwaysOnDisplayNotificationIconViewStore;
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerAlwaysOnDisplayViewModel;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.NotificationIconAreaController;
import com.android.systemui.statusbar.phone.NotificationIconContainer;
import com.android.systemui.statusbar.phone.ScreenOffAnimationController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.settings.SecureSettings;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class KeyguardClockSwitchControllerBaseTest extends SysuiTestCase {

    @Mock
    protected KeyguardClockSwitch mView;
    @Mock
    protected StatusBarStateController mStatusBarStateController;
    @Mock
    protected ClockRegistry mClockRegistry;
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
    protected ClockController mClockController;
    @Mock
    protected ClockFaceController mLargeClockController;
    @Mock
    protected ClockFaceController mSmallClockController;
    @Mock
    protected ClockAnimations mClockAnimations;
    @Mock
    protected ClockEvents mClockEvents;
    @Mock
    protected ClockFaceEvents mClockFaceEvents;
    @Mock
    DumpManager mDumpManager;
    @Mock
    ClockEventController mClockEventController;

    @Mock
    protected NotificationIconContainer mNotificationIcons;
    @Mock
    protected AnimatableClockView mSmallClockView;
    @Mock
    protected AnimatableClockView mLargeClockView;
    @Mock
    protected FrameLayout mSmallClockFrame;
    @Mock
    protected FrameLayout mLargeClockFrame;
    @Mock
    protected SecureSettings mSecureSettings;
    @Mock
    protected LogBuffer mLogBuffer;

    @Mock
    protected KeyguardClockInteractor mKeyguardClockInteractor;

    protected final View mFakeDateView = (View) (new ViewGroup(mContext) {
        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {}
    });
    protected final View mFakeWeatherView = new View(mContext);
    protected final View mFakeSmartspaceView = new View(mContext);

    protected KeyguardClockSwitchController mController;
    protected View mSliceView;
    protected LinearLayout mStatusArea;
    protected FakeExecutor mExecutor;
    protected FakeFeatureFlags mFakeFeatureFlags;
    @Captor protected ArgumentCaptor<View.OnAttachStateChangeListener> mAttachCaptor =
            ArgumentCaptor.forClass(View.OnAttachStateChangeListener.class);

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        mFakeDateView.setTag(R.id.tag_smartspace_view, new Object());
        mFakeWeatherView.setTag(R.id.tag_smartspace_view, new Object());
        mFakeSmartspaceView.setTag(R.id.tag_smartspace_view, new Object());

        when(mView.findViewById(R.id.left_aligned_notification_icon_container))
                .thenReturn(mNotificationIcons);
        when(mNotificationIcons.getLayoutParams()).thenReturn(
                mock(RelativeLayout.LayoutParams.class));
        when(mView.getContext()).thenReturn(getContext());
        when(mView.getResources()).thenReturn(mResources);
        when(mResources.getDimensionPixelSize(R.dimen.keyguard_clock_top_margin))
                .thenReturn(100);
        when(mResources.getDimensionPixelSize(com.android.systemui.customization.R.dimen.keyguard_large_clock_top_margin))
                .thenReturn(-200);
        when(mResources.getInteger(com.android.internal.R.integer.config_doublelineClockDefault))
                .thenReturn(1);
        when(mResources.getInteger(R.integer.keyguard_date_weather_view_invisibility))
                .thenReturn(INVISIBLE);

        when(mView.findViewById(R.id.lockscreen_clock_view_large)).thenReturn(mLargeClockFrame);
        when(mView.findViewById(R.id.lockscreen_clock_view)).thenReturn(mSmallClockFrame);
        when(mSmallClockView.getContext()).thenReturn(getContext());
        when(mLargeClockView.getContext()).thenReturn(getContext());

        when(mView.isAttachedToWindow()).thenReturn(true);
        when(mSmartspaceController.buildAndConnectDateView(any())).thenReturn(mFakeDateView);
        when(mSmartspaceController.buildAndConnectWeatherView(any())).thenReturn(mFakeWeatherView);
        when(mSmartspaceController.buildAndConnectView(any())).thenReturn(mFakeSmartspaceView);
        mExecutor = new FakeExecutor(new FakeSystemClock());
        mFakeFeatureFlags = new FakeFeatureFlags();
        mFakeFeatureFlags.set(FACE_AUTH_REFACTOR, false);
        mFakeFeatureFlags.set(LOCKSCREEN_WALLPAPER_DREAM_ENABLED, false);
        mFakeFeatureFlags.set(MIGRATE_KEYGUARD_STATUS_VIEW, false);
        mFakeFeatureFlags.set(MIGRATE_CLOCKS_TO_BLUEPRINT, false);
        mController = new KeyguardClockSwitchController(
                mView,
                mStatusBarStateController,
                mClockRegistry,
                mKeyguardSliceViewController,
                mNotificationIconAreaController,
                mSmartspaceController,
                mock(ConfigurationController.class),
                mock(ScreenOffAnimationController.class),
                mKeyguardUnlockAnimationController,
                mSecureSettings,
                mExecutor,
                mDumpManager,
                mClockEventController,
                mLogBuffer,
                mock(NotificationIconContainerAlwaysOnDisplayViewModel.class),
                mock(KeyguardRootViewModel.class),
                mock(ConfigurationState.class),
                mock(DozeParameters.class),
                mock(AlwaysOnDisplayNotificationIconViewStore.class),
                KeyguardInteractorFactory.create(mFakeFeatureFlags).getKeyguardInteractor(),
                mKeyguardClockInteractor,
                mFakeFeatureFlags,
                mock(InWindowLauncherUnlockAnimationManager.class)
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
        mStatusArea = new LinearLayout(getContext());
        when(mView.findViewById(R.id.keyguard_status_area)).thenReturn(mStatusArea);
    }

    private void removeView(View v) {
        ViewGroup group = ((ViewGroup) v.getParent());
        if (group != null) {
            group.removeView(v);
        }
    }

    protected void init() {
        mController.init();

        verify(mView, atLeast(1)).addOnAttachStateChangeListener(mAttachCaptor.capture());
        mAttachCaptor.getValue().onViewAttachedToWindow(mView);
    }
}

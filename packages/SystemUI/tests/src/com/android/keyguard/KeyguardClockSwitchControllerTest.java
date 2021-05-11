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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.smartspace.SmartspaceTarget;
import android.content.Context;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.UserHandle;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

import androidx.annotation.Nullable;

import com.android.internal.colorextraction.ColorExtractor;
import com.android.keyguard.clock.ClockManager;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.BcSmartspaceDataPlugin;
import com.android.systemui.plugins.BcSmartspaceDataPlugin.IntentStarter;
import com.android.systemui.plugins.ClockPlugin;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.FeatureFlags;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.NotificationIconAreaController;
import com.android.systemui.statusbar.phone.NotificationIconContainer;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.util.settings.SecureSettings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.verification.VerificationMode;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
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
    BroadcastDispatcher mBroadcastDispatcher;
    @Mock
    private FeatureFlags mFeatureFlags;
    @Mock
    private Executor mExecutor;
    @Mock
    private AnimatableClockView mClockView;
    @Mock
    private AnimatableClockView mLargeClockView;
    @Mock
    private FrameLayout mLargeClockFrame;
    @Mock
    BatteryController mBatteryController;
    @Mock
    ConfigurationController mConfigurationController;
    @Mock
    Optional<BcSmartspaceDataPlugin> mOptionalSmartspaceDataProvider;
    @Mock
    BcSmartspaceDataPlugin mSmartspaceDataProvider;
    @Mock
    SmartspaceView mSmartspaceView;
    @Mock
    ActivityStarter mActivityStarter;
    @Mock
    FalsingManager mFalsingManager;
    @Mock
    KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock
    KeyguardBypassController mBypassController;
    @Mock
    Handler mHandler;
    @Mock
    UserTracker mUserTracker;
    @Mock
    SecureSettings mSecureSettings;

    private KeyguardClockSwitchController mController;
    private View mStatusArea;

    private static final int USER_ID = 5;
    private static final int MANAGED_USER_ID = 15;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        when(mView.findViewById(R.id.left_aligned_notification_icon_container))
                .thenReturn(mNotificationIcons);
        when(mNotificationIcons.getLayoutParams()).thenReturn(
                mock(RelativeLayout.LayoutParams.class));
        when(mView.getContext()).thenReturn(getContext());

        when(mView.findViewById(R.id.animatable_clock_view)).thenReturn(mClockView);
        when(mView.findViewById(R.id.animatable_clock_view_large)).thenReturn(mLargeClockView);
        when(mView.findViewById(R.id.lockscreen_clock_view_large)).thenReturn(mLargeClockFrame);
        when(mClockView.getContext()).thenReturn(getContext());
        when(mLargeClockView.getContext()).thenReturn(getContext());

        when(mFeatureFlags.isSmartspaceEnabled()).thenReturn(true);
        when(mView.isAttachedToWindow()).thenReturn(true);
        when(mResources.getString(anyInt())).thenReturn("h:mm");
        mController = new KeyguardClockSwitchController(
                mView,
                mStatusBarStateController,
                mColorExtractor,
                mClockManager,
                mKeyguardSliceViewController,
                mNotificationIconAreaController,
                mBroadcastDispatcher,
                mFeatureFlags,
                mExecutor,
                mBatteryController,
                mConfigurationController,
                mActivityStarter,
                mFalsingManager,
                mKeyguardUpdateMonitor,
                mBypassController,
                mHandler,
                mUserTracker,
                mSecureSettings,
                mOptionalSmartspaceDataProvider
        );

        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.SHADE);
        when(mColorExtractor.getColors(anyInt())).thenReturn(mGradientColors);

        mStatusArea = new View(getContext());
        when(mView.findViewById(R.id.keyguard_status_area)).thenReturn(mStatusArea);
        when(mOptionalSmartspaceDataProvider.isPresent()).thenReturn(true);
        when(mOptionalSmartspaceDataProvider.get()).thenReturn(mSmartspaceDataProvider);
        when(mSmartspaceDataProvider.getView(any())).thenReturn(mSmartspaceView);
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
        when(mFeatureFlags.isSmartspaceEnabled()).thenReturn(true);
        mController.init();

        assertEquals(View.GONE, mStatusArea.getVisibility());
    }

    @Test
    public void testSmartspaceEnabledNoDataProviderShowsKeyguardStatusArea() {
        when(mFeatureFlags.isSmartspaceEnabled()).thenReturn(true);
        when(mOptionalSmartspaceDataProvider.isPresent()).thenReturn(false);
        mController.init();

        assertEquals(View.VISIBLE, mStatusArea.getVisibility());
    }

    @Test
    public void testSmartspaceDisabledShowsKeyguardStatusArea() {
        when(mFeatureFlags.isSmartspaceEnabled()).thenReturn(false);
        mController.init();

        assertEquals(View.VISIBLE, mStatusArea.getVisibility());
    }

    @Test
    public void testThemeChangeNotifiesSmartspace() {
        mController.init();
        verify(mSmartspaceView).setPrimaryTextColor(anyInt());

        mController.getConfigurationListener().onThemeChanged();
        verify(mSmartspaceView, times(2)).setPrimaryTextColor(anyInt());
    }

    @Test
    public void doNotFilterRegularTarget() {
        setupPrimaryAndManagedUser();
        mController.init();

        when(mSecureSettings.getIntForUser(anyString(), anyInt(), eq(USER_ID))).thenReturn(0);
        when(mSecureSettings.getIntForUser(anyString(), anyInt(), eq(MANAGED_USER_ID)))
                .thenReturn(0);

        mController.getSettingsObserver().onChange(true, null);

        SmartspaceTarget t = mock(SmartspaceTarget.class);
        when(t.isSensitive()).thenReturn(false);
        when(t.getUserHandle()).thenReturn(new UserHandle(USER_ID));
        assertEquals(false, mController.filterSmartspaceTarget(t));

        reset(t);
        when(t.isSensitive()).thenReturn(false);
        when(t.getUserHandle()).thenReturn(new UserHandle(MANAGED_USER_ID));
        assertEquals(false, mController.filterSmartspaceTarget(t));
    }

    @Test
    public void filterAllSensitiveTargetsAllUsers() {
        setupPrimaryAndManagedUser();
        mController.init();

        when(mSecureSettings.getIntForUser(anyString(), anyInt(), eq(USER_ID))).thenReturn(0);
        when(mSecureSettings.getIntForUser(anyString(), anyInt(), eq(MANAGED_USER_ID)))
                .thenReturn(0);

        mController.getSettingsObserver().onChange(true, null);

        SmartspaceTarget t = mock(SmartspaceTarget.class);
        when(t.isSensitive()).thenReturn(true);
        when(t.getUserHandle()).thenReturn(new UserHandle(USER_ID));
        assertEquals(true, mController.filterSmartspaceTarget(t));

        reset(t);
        when(t.isSensitive()).thenReturn(true);
        when(t.getUserHandle()).thenReturn(new UserHandle(MANAGED_USER_ID));
        assertEquals(true, mController.filterSmartspaceTarget(t));
    }

    @Test
    public void filterSensitiveManagedUserTargets() {
        setupPrimaryAndManagedUser();
        mController.init();

        when(mSecureSettings.getIntForUser(anyString(), anyInt(), eq(USER_ID))).thenReturn(1);
        when(mSecureSettings.getIntForUser(anyString(), anyInt(), eq(MANAGED_USER_ID)))
                .thenReturn(0);

        mController.getSettingsObserver().onChange(true, null);

        SmartspaceTarget t = mock(SmartspaceTarget.class);
        when(t.isSensitive()).thenReturn(true);
        when(t.getUserHandle()).thenReturn(new UserHandle(USER_ID));
        assertEquals(false, mController.filterSmartspaceTarget(t));

        reset(t);
        when(t.isSensitive()).thenReturn(true);
        when(t.getUserHandle()).thenReturn(new UserHandle(MANAGED_USER_ID));
        assertEquals(true, mController.filterSmartspaceTarget(t));
    }

    private void setupPrimaryAndManagedUser() {
        UserInfo userInfo = mock(UserInfo.class);
        when(userInfo.isManagedProfile()).thenReturn(true);
        when(userInfo.getUserHandle()).thenReturn(new UserHandle(MANAGED_USER_ID));
        when(mUserTracker.getUserProfiles()).thenReturn(List.of(userInfo));

        when(mUserTracker.getUserId()).thenReturn(USER_ID);
        when(mUserTracker.getUserHandle()).thenReturn(new UserHandle(USER_ID));
    }

    private void setupPrimaryAndNoManagedUser() {
        when(mUserTracker.getUserProfiles()).thenReturn(Collections.emptyList());

        when(mUserTracker.getUserId()).thenReturn(USER_ID);
        when(mUserTracker.getUserHandle()).thenReturn(new UserHandle(USER_ID));
    }

    private void verifyAttachment(VerificationMode times) {
        verify(mClockManager, times).addOnClockChangedListener(
                any(ClockManager.ClockChangedListener.class));
        verify(mColorExtractor, times).addOnColorsChangedListener(
                any(ColorExtractor.OnColorsChangedListener.class));
        verify(mView, times).updateColors(mGradientColors);
    }

    private static class SmartspaceView extends View
            implements BcSmartspaceDataPlugin.SmartspaceView {
        SmartspaceView(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        public void registerDataProvider(BcSmartspaceDataPlugin plugin) { }

        public void setPrimaryTextColor(int color) { }

        public void setDozeAmount(float amount) { }

        public void setIntentStarter(IntentStarter intentStarter) { }

        public void setFalsingManager(FalsingManager falsingManager) { }

        public void setDnd(@Nullable Drawable dndIcon, @Nullable String description) { }

        public void setNextAlarm(@Nullable Drawable dndIcon, @Nullable String description) { }
    }
}

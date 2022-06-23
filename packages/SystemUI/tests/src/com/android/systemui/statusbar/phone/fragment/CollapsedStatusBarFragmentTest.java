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

package com.android.systemui.statusbar.phone.fragment;

import static android.view.Display.DEFAULT_DISPLAY;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Fragment;
import android.app.StatusBarManager;
import android.content.Context;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.view.View;
import android.view.ViewPropertyAnimator;
import android.widget.FrameLayout;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiBaseFragmentTest;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.log.LogBuffer;
import com.android.systemui.log.LogcatEchoTracker;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.DisableFlagsLogger;
import com.android.systemui.statusbar.OperatorNameViewController;
import com.android.systemui.statusbar.events.SystemStatusAnimationScheduler;
import com.android.systemui.statusbar.phone.HeadsUpAppearanceController;
import com.android.systemui.statusbar.phone.NotificationIconAreaController;
import com.android.systemui.statusbar.phone.NotificationPanelViewController;
import com.android.systemui.statusbar.phone.StatusBarHideIconsForBouncerManager;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.phone.StatusBarLocationPublisher;
import com.android.systemui.statusbar.phone.fragment.dagger.StatusBarFragmentComponent;
import com.android.systemui.statusbar.phone.ongoingcall.OngoingCallController;
import com.android.systemui.statusbar.phone.panelstate.PanelExpansionStateManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.CarrierConfigTracker;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.settings.SecureSettings;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

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
    private KeyguardStateController mKeyguardStateController;

    private final CommandQueue mCommandQueue = mock(CommandQueue.class);
    private OperatorNameViewController.Factory mOperatorNameViewControllerFactory;
    private OperatorNameViewController mOperatorNameViewController;
    private SecureSettings mSecureSettings;
    private FakeExecutor mExecutor = new FakeExecutor(new FakeSystemClock());
    private final CarrierConfigTracker mCarrierConfigTracker = mock(CarrierConfigTracker.class);

    @Mock
    private StatusBarFragmentComponent.Factory mStatusBarFragmentComponentFactory;
    @Mock
    private StatusBarFragmentComponent mStatusBarFragmentComponent;
    @Mock
    private StatusBarStateController mStatusBarStateController;
    @Mock
    private HeadsUpAppearanceController mHeadsUpAppearanceController;
    @Mock
    private NotificationPanelViewController mNotificationPanelViewController;
    @Mock
    private StatusBarHideIconsForBouncerManager mStatusBarHideIconsForBouncerManager;

    public CollapsedStatusBarFragmentTest() {
        super(CollapsedStatusBarFragment.class);
    }

    @Before
    public void setup() {
        injectLeakCheckedDependencies(ALL_SUPPORTED_CLASSES);
    }

    @Test
    public void testDisableNone() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();

        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        assertEquals(View.VISIBLE, getSystemIconAreaView().getVisibility());
        assertEquals(View.VISIBLE, getClockView().getVisibility());
    }

    @Test
    public void testDisableSystemInfo() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();

        fragment.disable(DEFAULT_DISPLAY, StatusBarManager.DISABLE_SYSTEM_INFO, 0, false);

        assertEquals(View.INVISIBLE, getSystemIconAreaView().getVisibility());

        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        assertEquals(View.VISIBLE, getSystemIconAreaView().getVisibility());
    }

    @Test
    public void testDisableNotifications() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();

        fragment.disable(DEFAULT_DISPLAY, StatusBarManager.DISABLE_NOTIFICATION_ICONS, 0, false);

        Mockito.verify(mNotificationAreaInner, atLeast(1)).setVisibility(eq(View.INVISIBLE));

        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        Mockito.verify(mNotificationAreaInner, atLeast(1)).setVisibility(eq(View.VISIBLE));
    }

    @Test
    public void testDisableClock() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();

        fragment.disable(DEFAULT_DISPLAY, StatusBarManager.DISABLE_CLOCK, 0, false);

        assertEquals(View.GONE, getClockView().getVisibility());

        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        assertEquals(View.VISIBLE, getClockView().getVisibility());
    }

    @Test
    public void disable_noOngoingCall_chipHidden() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();

        when(mOngoingCallController.hasOngoingCall()).thenReturn(false);

        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        assertEquals(View.GONE,
                mFragment.getView().findViewById(R.id.ongoing_call_chip).getVisibility());
    }

    @Test
    public void disable_hasOngoingCall_chipDisplayedAndNotificationIconsHidden() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();

        when(mOngoingCallController.hasOngoingCall()).thenReturn(true);

        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        assertEquals(View.VISIBLE,
                mFragment.getView().findViewById(R.id.ongoing_call_chip).getVisibility());
        Mockito.verify(mNotificationAreaInner, atLeast(1)).setVisibility(eq(View.INVISIBLE));

    }

    @Test
    public void disable_hasOngoingCallButNotificationIconsDisabled_chipHidden() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();

        when(mOngoingCallController.hasOngoingCall()).thenReturn(true);

        fragment.disable(DEFAULT_DISPLAY,
                StatusBarManager.DISABLE_NOTIFICATION_ICONS, 0, false);

        assertEquals(View.GONE,
                mFragment.getView().findViewById(R.id.ongoing_call_chip).getVisibility());
    }

    @Test
    public void disable_ongoingCallEnded_chipHidden() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();

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

    @Test
    public void disable_isDozing_clockAndSystemInfoVisible() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();
        when(mStatusBarStateController.isDozing()).thenReturn(true);

        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        assertEquals(View.VISIBLE, getSystemIconAreaView().getVisibility());
        assertEquals(View.VISIBLE, getClockView().getVisibility());
    }

    @Test
    public void disable_NotDozing_clockAndSystemInfoVisible() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();
        when(mStatusBarStateController.isDozing()).thenReturn(false);

        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        assertEquals(View.VISIBLE, getSystemIconAreaView().getVisibility());
        assertEquals(View.VISIBLE, getClockView().getVisibility());
    }

    @Test
    public void disable_headsUpShouldBeVisibleTrue_clockDisabled() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();
        when(mHeadsUpAppearanceController.shouldBeVisible()).thenReturn(true);

        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        assertEquals(View.GONE, getClockView().getVisibility());
    }

    @Test
    public void disable_headsUpShouldBeVisibleFalse_clockNotDisabled() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();
        when(mHeadsUpAppearanceController.shouldBeVisible()).thenReturn(false);

        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        assertEquals(View.VISIBLE, getClockView().getVisibility());
    }

    @Test
    public void setUp_fragmentCreatesDaggerComponent() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();

        assertEquals(mStatusBarFragmentComponent, fragment.getStatusBarFragmentComponent());
    }

    @Test
    public void testBlockedIcons_obeysSettingForVibrateIcon_settingOff() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();
        String str = mContext.getString(com.android.internal.R.string.status_bar_volume);

        // GIVEN the setting is off
        when(mSecureSettings.getInt(Settings.Secure.STATUS_BAR_SHOW_VIBRATE_ICON, 0))
                .thenReturn(0);

        // WHEN CollapsedStatusBarFragment builds the blocklist
        fragment.updateBlockedIcons();

        // THEN status_bar_volume SHOULD be present in the list
        boolean contains = fragment.getBlockedIcons().contains(str);
        assertTrue(contains);
    }

    @Test
    public void testBlockedIcons_obeysSettingForVibrateIcon_settingOn() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();
        String str = mContext.getString(com.android.internal.R.string.status_bar_volume);

        // GIVEN the setting is ON
        when(mSecureSettings.getIntForUser(Settings.Secure.STATUS_BAR_SHOW_VIBRATE_ICON, 0,
                UserHandle.USER_CURRENT))
                .thenReturn(1);

        // WHEN CollapsedStatusBarFragment builds the blocklist
        fragment.updateBlockedIcons();

        // THEN status_bar_volume SHOULD NOT be present in the list
        boolean contains = fragment.getBlockedIcons().contains(str);
        assertFalse(contains);
    }

    @Override
    protected Fragment instantiate(Context context, String className, Bundle arguments) {
        MockitoAnnotations.initMocks(this);
        setUpDaggerComponent();
        mOngoingCallController = mock(OngoingCallController.class);
        mAnimationScheduler = mock(SystemStatusAnimationScheduler.class);
        mLocationPublisher = mock(StatusBarLocationPublisher.class);
        mStatusBarIconController = mock(StatusBarIconController.class);
        mStatusBarStateController = mock(StatusBarStateController.class);
        mKeyguardStateController = mock(KeyguardStateController.class);
        mOperatorNameViewController = mock(OperatorNameViewController.class);
        mOperatorNameViewControllerFactory = mock(OperatorNameViewController.Factory.class);
        when(mOperatorNameViewControllerFactory.create(any()))
                .thenReturn(mOperatorNameViewController);
        mSecureSettings = mock(SecureSettings.class);

        setUpNotificationIconAreaController();
        return new CollapsedStatusBarFragment(
                mStatusBarFragmentComponentFactory,
                mOngoingCallController,
                mAnimationScheduler,
                mLocationPublisher,
                mMockNotificationAreaController,
                new PanelExpansionStateManager(),
                mock(FeatureFlags.class),
                mStatusBarIconController,
                mStatusBarHideIconsForBouncerManager,
                mKeyguardStateController,
                mNotificationPanelViewController,
                mStatusBarStateController,
                mCommandQueue,
                mCarrierConfigTracker,
                new CollapsedStatusBarFragmentLogger(
                        new LogBuffer("TEST", 1, mock(LogcatEchoTracker.class)),
                        new DisableFlagsLogger()
                        ),
                mOperatorNameViewControllerFactory,
                mSecureSettings,
                mExecutor);
    }

    private void setUpDaggerComponent() {
        when(mStatusBarFragmentComponentFactory.create(any()))
                .thenReturn(mStatusBarFragmentComponent);
        when(mStatusBarFragmentComponent.getHeadsUpAppearanceController())
                .thenReturn(mHeadsUpAppearanceController);
    }

    private void setUpNotificationIconAreaController() {
        mMockNotificationAreaController = mock(NotificationIconAreaController.class);

        mNotificationAreaInner = mock(View.class);

        when(mNotificationAreaInner.getLayoutParams()).thenReturn(
                new FrameLayout.LayoutParams(100, 100));
        when(mNotificationAreaInner.animate()).thenReturn(mock(ViewPropertyAnimator.class));

        when(mMockNotificationAreaController.getNotificationInnerAreaView()).thenReturn(
                mNotificationAreaInner);
    }

    private CollapsedStatusBarFragment resumeAndGetFragment() {
        mFragments.dispatchResume();
        processAllMessages();
        return (CollapsedStatusBarFragment) mFragment;
    }

    private View getClockView() {
        return mFragment.getView().findViewById(R.id.clock);
    }

    private View getSystemIconAreaView() {
        return mFragment.getView().findViewById(R.id.system_icon_area);
    }
}

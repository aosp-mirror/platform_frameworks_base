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

import static com.android.systemui.Flags.FLAG_STATUS_BAR_SCREEN_SHARING_CHIPS;
import static com.android.systemui.shade.ShadeExpansionStateManagerKt.STATE_CLOSED;
import static com.android.systemui.shade.ShadeExpansionStateManagerKt.STATE_OPEN;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Fragment;
import android.app.StatusBarManager;
import android.content.Context;
import android.os.Bundle;
import android.os.UserHandle;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.view.LayoutInflater;
import android.view.View;

import androidx.test.filters.SmallTest;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.SysuiBaseFragmentTest;
import com.android.systemui.animation.AnimatorTestRule;
import com.android.systemui.demomode.DemoModeController;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.log.LogBuffer;
import com.android.systemui.log.LogcatEchoTracker;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.res.R;
import com.android.systemui.shade.ShadeExpansionStateManager;
import com.android.systemui.shade.domain.interactor.PanelExpansionInteractor;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.OperatorNameViewController;
import com.android.systemui.statusbar.disableflags.DisableFlagsLogger;
import com.android.systemui.statusbar.events.SystemStatusAnimationScheduler;
import com.android.systemui.statusbar.notification.icon.ui.viewbinder.NotificationIconContainerStatusBarViewBinder;
import com.android.systemui.statusbar.phone.HeadsUpAppearanceController;
import com.android.systemui.statusbar.phone.NotificationIconAreaController;
import com.android.systemui.statusbar.phone.StatusBarHideIconsForBouncerManager;
import com.android.systemui.statusbar.phone.StatusBarLocationPublisher;
import com.android.systemui.statusbar.phone.fragment.dagger.StatusBarFragmentComponent;
import com.android.systemui.statusbar.phone.ongoingcall.OngoingCallController;
import com.android.systemui.statusbar.phone.ui.DarkIconManager;
import com.android.systemui.statusbar.phone.ui.StatusBarIconController;
import com.android.systemui.statusbar.pipeline.shared.ui.viewmodel.FakeCollapsedStatusBarViewBinder;
import com.android.systemui.statusbar.pipeline.shared.ui.viewmodel.FakeCollapsedStatusBarViewModel;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.window.StatusBarWindowStateController;
import com.android.systemui.statusbar.window.StatusBarWindowStateListener;
import com.android.systemui.util.CarrierConfigTracker;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.settings.SecureSettings;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper(setAsMainLooper = true)
@SmallTest
public class CollapsedStatusBarFragmentTest extends SysuiBaseFragmentTest {
    private NotificationIconAreaController mMockNotificationAreaController;
    private ShadeExpansionStateManager mShadeExpansionStateManager;
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
    private PanelExpansionInteractor mPanelExpansionInteractor;
    @Mock
    private DarkIconManager.Factory mIconManagerFactory;
    @Mock
    private DarkIconManager mIconManager;
    private FakeCollapsedStatusBarViewModel mCollapsedStatusBarViewModel;
    private FakeCollapsedStatusBarViewBinder mCollapsedStatusBarViewBinder;
    @Mock
    private StatusBarHideIconsForBouncerManager mStatusBarHideIconsForBouncerManager;
    @Mock
    private DumpManager mDumpManager;
    @Mock
    private StatusBarWindowStateController mStatusBarWindowStateController;
    @Mock
    private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Rule
    public final AnimatorTestRule mAnimatorTestRule = new AnimatorTestRule(this);

    private List<StatusBarWindowStateListener> mStatusBarWindowStateListeners = new ArrayList<>();

    public CollapsedStatusBarFragmentTest() {
        super(CollapsedStatusBarFragment.class);
    }

    @Before
    public void setup() {
        injectLeakCheckedDependencies(ALL_SUPPORTED_CLASSES);
        mDependency.injectMockDependency(DarkIconDispatcher.class);

        // Keep the window state listeners so we can dispatch to them to test the status bar
        // fragment's response.
        doAnswer(invocation -> {
            mStatusBarWindowStateListeners.add(invocation.getArgument(0));
            return null;
        }).when(mStatusBarWindowStateController).addListener(
                any(StatusBarWindowStateListener.class));
    }

    @Test
    public void testDisableNone() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();

        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        assertEquals(View.VISIBLE, getEndSideContentView().getVisibility());
        assertEquals(View.VISIBLE, getClockView().getVisibility());
    }

    @Test
    public void testDisableSystemInfo_systemAnimationIdle_doesHide() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();

        fragment.disable(DEFAULT_DISPLAY, StatusBarManager.DISABLE_SYSTEM_INFO, 0, false);

        assertEquals(View.INVISIBLE, getEndSideContentView().getVisibility());

        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        assertEquals(View.VISIBLE, getEndSideContentView().getVisibility());

        fragment.disable(DEFAULT_DISPLAY, StatusBarManager.DISABLE_SYSTEM_INFO, 0, false);

        assertEquals(View.INVISIBLE, getEndSideContentView().getVisibility());
    }

    @Test
    public void testSystemStatusAnimation_startedDisabled_finishedWithAnimator_showsSystemInfo() {
        // GIVEN the status bar hides the system info via disable flags, while there is no event
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();
        fragment.disable(DEFAULT_DISPLAY, StatusBarManager.DISABLE_SYSTEM_INFO, 0, false);
        assertEquals(View.INVISIBLE, getEndSideContentView().getVisibility());

        // WHEN the system event animation starts
        fragment.onSystemEventAnimationBegin().start();

        // THEN the view remains invisible during the animation
        assertEquals(0f, getEndSideContentView().getAlpha(), 0.01);
        mAnimatorTestRule.advanceTimeBy(500);
        assertEquals(0f, getEndSideContentView().getAlpha(), 0.01);

        // WHEN the disable flags are cleared during a system event animation
        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        // THEN the view remains invisible
        assertEquals(0, getEndSideContentView().getAlpha(), 0.01);

        // WHEN the system event animation finishes
        fragment.onSystemEventAnimationFinish(false).start();
        mAnimatorTestRule.advanceTimeBy(500);

        // THEN the system info is full alpha
        assertEquals(1, getEndSideContentView().getAlpha(), 0.01);
    }

    @Test
    public void testSystemStatusAnimation_systemInfoDisabled_staysInvisible() {
        // GIVEN the status bar hides the system info via disable flags, while there is no event
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();
        fragment.disable(DEFAULT_DISPLAY, StatusBarManager.DISABLE_SYSTEM_INFO, 0, false);
        assertEquals(View.INVISIBLE, getEndSideContentView().getVisibility());

        // WHEN the system event animation finishes
        fragment.onSystemEventAnimationFinish(false).start();
        mAnimatorTestRule.advanceTimeBy(500);

        // THEN the system info remains invisible (since the disable flag is still set)
        assertEquals(0, getEndSideContentView().getAlpha(), 0.01);
        assertEquals(View.INVISIBLE, getEndSideContentView().getVisibility());
    }


    @Test
    public void testSystemStatusAnimation_notDisabled_animatesAlphaZero() {
        // GIVEN the status bar is not disabled
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();
        assertEquals(1, getEndSideContentView().getAlpha(), 0.01);

        // WHEN the system event animation begins
        fragment.onSystemEventAnimationBegin().start();
        mAnimatorTestRule.advanceTimeBy(500);

        // THEN the system info is invisible
        assertEquals(View.INVISIBLE, getEndSideContentView().getVisibility());
        assertEquals(0, getEndSideContentView().getAlpha(), 0.01);
    }

    @Test
    public void testSystemStatusAnimation_notDisabled_animatesBackToAlphaOne() {
        // GIVEN the status bar is not disabled
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();
        assertEquals(1, getEndSideContentView().getAlpha(), 0.01);

        // WHEN the system event animation begins
        fragment.onSystemEventAnimationBegin().start();
        mAnimatorTestRule.advanceTimeBy(500);

        // THEN the system info is invisible
        assertEquals(View.INVISIBLE, getEndSideContentView().getVisibility());
        assertEquals(0, getEndSideContentView().getAlpha(), 0.01);

        // WHEN the system event animation finishes
        fragment.onSystemEventAnimationFinish(false).start();
        mAnimatorTestRule.advanceTimeBy(500);

        // THEN the system info is full alpha and VISIBLE
        assertEquals(View.VISIBLE, getEndSideContentView().getVisibility());
        assertEquals(1, getEndSideContentView().getAlpha(), 0.01);
    }

    @Test
    public void testDisableNotifications() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();

        fragment.disable(DEFAULT_DISPLAY, StatusBarManager.DISABLE_NOTIFICATION_ICONS, 0, false);

        assertEquals(View.INVISIBLE, getNotificationAreaView().getVisibility());

        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        assertEquals(View.VISIBLE, getNotificationAreaView().getVisibility());

        fragment.disable(DEFAULT_DISPLAY, StatusBarManager.DISABLE_NOTIFICATION_ICONS, 0, false);

        assertEquals(View.INVISIBLE, getNotificationAreaView().getVisibility());
    }

    @Test
    public void testDisableClock() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();

        fragment.disable(DEFAULT_DISPLAY, StatusBarManager.DISABLE_CLOCK, 0, false);

        assertEquals(View.GONE, getClockView().getVisibility());

        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        assertEquals(View.VISIBLE, getClockView().getVisibility());

        fragment.disable(DEFAULT_DISPLAY, StatusBarManager.DISABLE_CLOCK, 0, false);

        assertEquals(View.GONE, getClockView().getVisibility());
    }

    @Test
    public void disable_shadeOpenAndShouldHide_everythingHidden() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();

        // WHEN the shade is open and configured to hide the status bar icons
        mShadeExpansionStateManager.updateState(STATE_OPEN);
        when(mPanelExpansionInteractor.shouldHideStatusBarIconsWhenExpanded()).thenReturn(true);

        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        // THEN all views are hidden
        assertEquals(View.INVISIBLE, getClockView().getVisibility());
        assertEquals(View.INVISIBLE, getNotificationAreaView().getVisibility());
        assertEquals(View.INVISIBLE, getEndSideContentView().getVisibility());
    }

    @Test
    public void disable_shadeOpenButNotShouldHide_everythingShown() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();

        // WHEN the shade is open but *not* configured to hide the status bar icons
        mShadeExpansionStateManager.updateState(STATE_OPEN);
        when(mPanelExpansionInteractor.shouldHideStatusBarIconsWhenExpanded()).thenReturn(false);

        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        // THEN all views are shown
        assertEquals(View.VISIBLE, getClockView().getVisibility());
        assertEquals(View.VISIBLE, getNotificationAreaView().getVisibility());
        assertEquals(View.VISIBLE, getEndSideContentView().getVisibility());
    }

    /** Regression test for b/279790651. */
    @Test
    public void disable_shadeOpenAndShouldHide_thenShadeNotOpenAndDozingUpdate_everythingShown() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();

        // WHEN the shade is open and configured to hide the status bar icons
        mShadeExpansionStateManager.updateState(STATE_OPEN);
        when(mPanelExpansionInteractor.shouldHideStatusBarIconsWhenExpanded()).thenReturn(true);

        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        // THEN all views are hidden
        assertEquals(View.INVISIBLE, getClockView().getVisibility());
        assertEquals(View.INVISIBLE, getNotificationAreaView().getVisibility());
        assertEquals(View.INVISIBLE, getEndSideContentView().getVisibility());

        // WHEN the shade is updated to no longer be open
        mShadeExpansionStateManager.updateState(STATE_CLOSED);

        // AND we internally request an update via dozing change
        fragment.onDozingChanged(true);

        // THEN all views are shown
        assertEquals(View.VISIBLE, getClockView().getVisibility());
        assertEquals(View.VISIBLE, getNotificationAreaView().getVisibility());
        assertEquals(View.VISIBLE, getEndSideContentView().getVisibility());
    }

    @Test
    public void disable_notTransitioningToOccluded_everythingShown() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();

        mCollapsedStatusBarViewModel.isTransitioningFromLockscreenToOccluded().setValue(false);

        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        // THEN all views are shown
        assertEquals(View.VISIBLE, getClockView().getVisibility());
        assertEquals(View.VISIBLE, getNotificationAreaView().getVisibility());
        assertEquals(View.VISIBLE, getEndSideContentView().getVisibility());
    }

    @Test
    public void disable_isTransitioningToOccluded_everythingHidden() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();

        mCollapsedStatusBarViewModel.isTransitioningFromLockscreenToOccluded().setValue(true);

        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        // THEN all views are hidden
        assertEquals(View.GONE, getClockView().getVisibility());
        assertEquals(View.INVISIBLE, getNotificationAreaView().getVisibility());
        assertEquals(View.INVISIBLE, getEndSideContentView().getVisibility());
    }

    @Test
    public void disable_wasTransitioningToOccluded_transitionFinished_everythingShown() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();

        // WHEN the transition is occurring
        mCollapsedStatusBarViewModel.isTransitioningFromLockscreenToOccluded().setValue(true);
        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        // THEN all views are hidden
        assertEquals(View.GONE, getClockView().getVisibility());
        assertEquals(View.INVISIBLE, getNotificationAreaView().getVisibility());
        assertEquals(View.INVISIBLE, getEndSideContentView().getVisibility());

        // WHEN the transition has finished
        mCollapsedStatusBarViewModel.isTransitioningFromLockscreenToOccluded().setValue(false);
        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        // THEN all views are shown
        assertEquals(View.VISIBLE, getClockView().getVisibility());
        assertEquals(View.VISIBLE, getNotificationAreaView().getVisibility());
        assertEquals(View.VISIBLE, getEndSideContentView().getVisibility());
    }

    @Test
    public void userChip_defaultVisibilityIsGone() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();

        assertEquals(View.GONE, getUserChipView().getVisibility());
    }

    @Test
    @DisableFlags(FLAG_STATUS_BAR_SCREEN_SHARING_CHIPS)
    public void disable_noOngoingCall_chipHidden() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();

        when(mOngoingCallController.hasOngoingCall()).thenReturn(false);

        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        assertEquals(View.GONE,
                mFragment.getView().findViewById(R.id.ongoing_activity_chip).getVisibility());
    }

    @Test
    @DisableFlags(FLAG_STATUS_BAR_SCREEN_SHARING_CHIPS)
    public void disable_hasOngoingCall_chipDisplayedAndNotificationIconsHidden() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();

        when(mOngoingCallController.hasOngoingCall()).thenReturn(true);

        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        assertEquals(View.VISIBLE,
                mFragment.getView().findViewById(R.id.ongoing_activity_chip).getVisibility());
        assertEquals(View.INVISIBLE, getNotificationAreaView().getVisibility());
    }

    @Test
    @DisableFlags(FLAG_STATUS_BAR_SCREEN_SHARING_CHIPS)
    public void disable_hasOngoingCallButNotificationIconsDisabled_chipHidden() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();

        when(mOngoingCallController.hasOngoingCall()).thenReturn(true);

        fragment.disable(DEFAULT_DISPLAY,
                StatusBarManager.DISABLE_NOTIFICATION_ICONS, 0, false);

        assertEquals(View.GONE,
                mFragment.getView().findViewById(R.id.ongoing_activity_chip).getVisibility());
    }

    @Test
    @DisableFlags(FLAG_STATUS_BAR_SCREEN_SHARING_CHIPS)
    public void disable_hasOngoingCallButAlsoHun_chipHidden() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();

        when(mOngoingCallController.hasOngoingCall()).thenReturn(true);
        when(mHeadsUpAppearanceController.shouldBeVisible()).thenReturn(true);

        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        assertEquals(View.GONE,
                mFragment.getView().findViewById(R.id.ongoing_activity_chip).getVisibility());
    }

    @Test
    @DisableFlags(FLAG_STATUS_BAR_SCREEN_SHARING_CHIPS)
    public void disable_ongoingCallEnded_chipHidden() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();

        // Ongoing call started
        when(mOngoingCallController.hasOngoingCall()).thenReturn(true);
        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        assertEquals(View.VISIBLE,
                mFragment.getView().findViewById(R.id.ongoing_activity_chip).getVisibility());

        // Ongoing call ended
        when(mOngoingCallController.hasOngoingCall()).thenReturn(false);
        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        assertEquals(View.GONE,
                mFragment.getView().findViewById(R.id.ongoing_activity_chip).getVisibility());

        // Ongoing call started
        when(mOngoingCallController.hasOngoingCall()).thenReturn(true);
        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        assertEquals(View.VISIBLE,
                mFragment.getView().findViewById(R.id.ongoing_activity_chip).getVisibility());
    }

    @Test
    @DisableFlags(FLAG_STATUS_BAR_SCREEN_SHARING_CHIPS)
    public void disable_hasOngoingCall_hidesNotifsWithoutAnimation() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();
        // Enable animations for testing so that we can verify we still aren't animating
        fragment.enableAnimationsForTesting();
        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        // Ongoing call started
        when(mOngoingCallController.hasOngoingCall()).thenReturn(true);
        fragment.disable(DEFAULT_DISPLAY, 0, 0, true);

        // Notification area is hidden without delay
        assertEquals(0f, getNotificationAreaView().getAlpha(), 0.01);
        assertEquals(View.INVISIBLE, getNotificationAreaView().getVisibility());
    }

    @Test
    @DisableFlags(FLAG_STATUS_BAR_SCREEN_SHARING_CHIPS)
    public void screenSharingChipsDisabled_ignoresNewCallback() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();

        // WHEN there *is* an ongoing call via old callback
        when(mOngoingCallController.hasOngoingCall()).thenReturn(true);
        fragment.disable(DEFAULT_DISPLAY, 0, 0, true);

        // WHEN there's *no* ongoing activity via new callback
        mCollapsedStatusBarViewBinder.getListener().onOngoingActivityStatusChanged(
                /* hasOngoingActivity= */ false);

        // THEN the old callback value is used, so the view is shown
        assertEquals(View.VISIBLE,
                mFragment.getView().findViewById(R.id.ongoing_activity_chip).getVisibility());

        // WHEN there's *no* ongoing call via old callback
        when(mOngoingCallController.hasOngoingCall()).thenReturn(false);
        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        // WHEN there *is* an ongoing activity via new callback
        mCollapsedStatusBarViewBinder.getListener().onOngoingActivityStatusChanged(
                /* hasOngoingActivity= */ true);

        // THEN the old callback value is used, so the view is hidden
        assertEquals(View.GONE,
                mFragment.getView().findViewById(R.id.ongoing_activity_chip).getVisibility());
    }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_SCREEN_SHARING_CHIPS)
    public void noOngoingActivity_chipHidden() {
        resumeAndGetFragment();

        // TODO(b/332662551): We *should* be able to just set a value on
        // mCollapsedStatusBarViewModel.getOngoingActivityChip() instead of manually invoking the
        // listener, but I'm unable to get the fragment to get attached so that the binder starts
        // listening to flows.
        mCollapsedStatusBarViewBinder.getListener().onOngoingActivityStatusChanged(
                /* hasOngoingActivity= */ false);

        assertEquals(View.GONE,
                mFragment.getView().findViewById(R.id.ongoing_activity_chip).getVisibility());
    }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_SCREEN_SHARING_CHIPS)
    public void hasOngoingActivity_chipDisplayedAndNotificationIconsHidden() {
        resumeAndGetFragment();

        mCollapsedStatusBarViewBinder.getListener().onOngoingActivityStatusChanged(
                /* hasOngoingActivity= */ true);

        assertEquals(View.VISIBLE,
                mFragment.getView().findViewById(R.id.ongoing_activity_chip).getVisibility());
        assertEquals(View.INVISIBLE, getNotificationAreaView().getVisibility());
    }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_SCREEN_SHARING_CHIPS)
    public void hasOngoingActivityButNotificationIconsDisabled_chipHidden() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();

        mCollapsedStatusBarViewBinder.getListener().onOngoingActivityStatusChanged(
                /* hasOngoingActivity= */ true);

        fragment.disable(DEFAULT_DISPLAY,
                StatusBarManager.DISABLE_NOTIFICATION_ICONS, 0, false);

        assertEquals(View.GONE,
                mFragment.getView().findViewById(R.id.ongoing_activity_chip).getVisibility());
    }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_SCREEN_SHARING_CHIPS)
    public void hasOngoingActivityButAlsoHun_chipHidden() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();

        mCollapsedStatusBarViewBinder.getListener().onOngoingActivityStatusChanged(
                /* hasOngoingActivity= */ true);
        when(mHeadsUpAppearanceController.shouldBeVisible()).thenReturn(true);

        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        assertEquals(View.GONE,
                mFragment.getView().findViewById(R.id.ongoing_activity_chip).getVisibility());
    }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_SCREEN_SHARING_CHIPS)
    public void ongoingActivityEnded_chipHidden() {
        resumeAndGetFragment();

        // Ongoing activity started
        mCollapsedStatusBarViewBinder.getListener().onOngoingActivityStatusChanged(
                /* hasOngoingActivity= */ true);

        assertEquals(View.VISIBLE,
                mFragment.getView().findViewById(R.id.ongoing_activity_chip).getVisibility());

        // Ongoing activity ended
        mCollapsedStatusBarViewBinder.getListener().onOngoingActivityStatusChanged(
                /* hasOngoingActivity= */ false);

        assertEquals(View.GONE,
                mFragment.getView().findViewById(R.id.ongoing_activity_chip).getVisibility());
    }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_SCREEN_SHARING_CHIPS)
    public void hasOngoingActivity_hidesNotifsWithoutAnimation() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();
        // Enable animations for testing so that we can verify we still aren't animating
        fragment.enableAnimationsForTesting();

        // Ongoing call started
        mCollapsedStatusBarViewBinder.getListener().onOngoingActivityStatusChanged(
                /* hasOngoingActivity= */ true);

        // Notification area is hidden without delay
        assertEquals(0f, getNotificationAreaView().getAlpha(), 0.01);
        assertEquals(View.INVISIBLE, getNotificationAreaView().getVisibility());
    }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_SCREEN_SHARING_CHIPS)
    public void screenSharingChipsEnabled_ignoresOngoingCallController() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();

        // WHEN there *is* an ongoing call via old callback
        when(mOngoingCallController.hasOngoingCall()).thenReturn(true);
        fragment.disable(DEFAULT_DISPLAY, 0, 0, true);

        // WHEN there's *no* ongoing activity via new callback
        mCollapsedStatusBarViewBinder.getListener().onOngoingActivityStatusChanged(
                /* hasOngoingActivity= */ false);

        // THEN the new callback value is used, so the view is hidden
        assertEquals(View.GONE,
                mFragment.getView().findViewById(R.id.ongoing_activity_chip).getVisibility());

        // WHEN there's *no* ongoing call via old callback
        when(mOngoingCallController.hasOngoingCall()).thenReturn(false);
        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        // WHEN there *is* an ongoing activity via new callback
        mCollapsedStatusBarViewBinder.getListener().onOngoingActivityStatusChanged(
                /* hasOngoingActivity= */ true);

        // THEN the new callback value is used, so the view is shown
        assertEquals(View.VISIBLE,
                mFragment.getView().findViewById(R.id.ongoing_activity_chip).getVisibility());
    }

    @Test
    public void disable_isDozing_clockAndSystemInfoVisible() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();
        when(mStatusBarStateController.isDozing()).thenReturn(true);

        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        assertEquals(View.VISIBLE, getEndSideContentView().getVisibility());
        assertEquals(View.VISIBLE, getClockView().getVisibility());
    }

    @Test
    public void disable_NotDozing_clockAndSystemInfoVisible() {
        CollapsedStatusBarFragment fragment = resumeAndGetFragment();
        when(mStatusBarStateController.isDozing()).thenReturn(false);

        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        assertEquals(View.VISIBLE, getEndSideContentView().getVisibility());
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

    @Test
    public void testStatusBarIcons_hiddenThroughoutCameraLaunch() {
        final CollapsedStatusBarFragment fragment = resumeAndGetFragment();

        mockSecureCameraLaunch(fragment, true /* launched */);

        // Status icons should be invisible or gone, but certainly not VISIBLE.
        assertNotEquals(View.VISIBLE, getEndSideContentView().getVisibility());
        assertNotEquals(View.VISIBLE, getClockView().getVisibility());

        mockSecureCameraLaunchFinished();

        assertNotEquals(View.VISIBLE, getEndSideContentView().getVisibility());
        assertNotEquals(View.VISIBLE, getClockView().getVisibility());

        mockSecureCameraLaunch(fragment, false /* launched */);

        assertEquals(View.VISIBLE, getEndSideContentView().getVisibility());
        assertEquals(View.VISIBLE, getClockView().getVisibility());
    }

    @Test
    public void testStatusBarIcons_hiddenThroughoutLockscreenToDreamTransition() {
        final CollapsedStatusBarFragment fragment = resumeAndGetFragment();

        // WHEN a transition to dream has started
        mCollapsedStatusBarViewBinder.getListener().onTransitionFromLockscreenToDreamStarted();
        when(mKeyguardUpdateMonitor.isDreaming()).thenReturn(true);
        when(mKeyguardStateController.isOccluded()).thenReturn(true);
        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        // THEN status icons should be invisible or gone, but certainly not VISIBLE.
        assertNotEquals(View.VISIBLE, getEndSideContentView().getVisibility());
        assertNotEquals(View.VISIBLE, getClockView().getVisibility());

        // WHEN the transition has finished and dream is displaying
        mockLockscreenToDreamTransitionFinished();
        // (This approximates "dream is displaying")
        when(mStatusBarHideIconsForBouncerManager.getShouldHideStatusBarIconsForBouncer())
                .thenReturn(true);
        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        // THEN the views still aren't visible because dream is hiding them
        assertNotEquals(View.VISIBLE, getEndSideContentView().getVisibility());
        assertNotEquals(View.VISIBLE, getClockView().getVisibility());

        // WHEN dream has ended
        when(mStatusBarHideIconsForBouncerManager.getShouldHideStatusBarIconsForBouncer())
                .thenReturn(false);
        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        // THEN the views can be visible again
        assertEquals(View.VISIBLE, getEndSideContentView().getVisibility());
        assertEquals(View.VISIBLE, getClockView().getVisibility());
    }

    @Test
    public void testStatusBarIcons_lockscreenToDreamTransitionButNotDreaming_iconsVisible() {
        final CollapsedStatusBarFragment fragment = resumeAndGetFragment();

        // WHEN a transition to dream has started but we're *not* dreaming
        mCollapsedStatusBarViewBinder.getListener().onTransitionFromLockscreenToDreamStarted();
        when(mKeyguardUpdateMonitor.isDreaming()).thenReturn(false);
        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);

        // THEN the views are still visible
        assertEquals(View.VISIBLE, getEndSideContentView().getVisibility());
        assertEquals(View.VISIBLE, getClockView().getVisibility());
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
        when(mIconManagerFactory.create(any(), any())).thenReturn(mIconManager);
        mSecureSettings = mock(SecureSettings.class);

        mShadeExpansionStateManager = new ShadeExpansionStateManager();
        mCollapsedStatusBarViewModel = new FakeCollapsedStatusBarViewModel();
        mCollapsedStatusBarViewBinder = new FakeCollapsedStatusBarViewBinder();

        setUpNotificationIconAreaController();
        return new CollapsedStatusBarFragment(
                mStatusBarFragmentComponentFactory,
                mOngoingCallController,
                mAnimationScheduler,
                mLocationPublisher,
                mMockNotificationAreaController,
                mShadeExpansionStateManager,
                mStatusBarIconController,
                mIconManagerFactory,
                mCollapsedStatusBarViewModel,
                mCollapsedStatusBarViewBinder,
                mStatusBarHideIconsForBouncerManager,
                mKeyguardStateController,
                mPanelExpansionInteractor,
                mStatusBarStateController,
                mock(NotificationIconContainerStatusBarViewBinder.class),
                mCommandQueue,
                mCarrierConfigTracker,
                new CollapsedStatusBarFragmentLogger(
                        new LogBuffer("TEST", 1, mock(LogcatEchoTracker.class)),
                        new DisableFlagsLogger()
                        ),
                mOperatorNameViewControllerFactory,
                mSecureSettings,
                mExecutor,
                mDumpManager,
                mStatusBarWindowStateController,
                mKeyguardUpdateMonitor,
                mock(DemoModeController.class));
    }

    private void setUpDaggerComponent() {
        when(mStatusBarFragmentComponentFactory.create(any()))
                .thenReturn(mStatusBarFragmentComponent);
        when(mStatusBarFragmentComponent.getHeadsUpAppearanceController())
                .thenReturn(mHeadsUpAppearanceController);
    }

    private void setUpNotificationIconAreaController() {
        mMockNotificationAreaController = mock(NotificationIconAreaController.class);
        View notificationAreaInner =
                LayoutInflater.from(mContext).inflate(R.layout.notification_icon_area, null);
        when(mMockNotificationAreaController.getNotificationInnerAreaView())
                .thenReturn(notificationAreaInner);
    }

    /**
     * Configure mocks to return values consistent with the secure camera animating itself launched
     * over the keyguard.
     */
    private void mockSecureCameraLaunch(CollapsedStatusBarFragment fragment, boolean launched) {
        when(mKeyguardUpdateMonitor.isSecureCameraLaunchedOverKeyguard()).thenReturn(launched);
        when(mKeyguardStateController.isOccluded()).thenReturn(launched);

        if (launched) {
            fragment.onCameraLaunchGestureDetected(0 /* source */);
        } else {
            for (StatusBarWindowStateListener listener : mStatusBarWindowStateListeners) {
                listener.onStatusBarWindowStateChanged(StatusBarManager.WINDOW_STATE_SHOWING);
            }
        }

        fragment.disable(DEFAULT_DISPLAY, 0, 0, false);
    }

    /**
     * Configure mocks to return values consistent with the secure camera showing over the keyguard
     * with its launch animation finished.
     */
    private void mockSecureCameraLaunchFinished() {
        for (StatusBarWindowStateListener listener : mStatusBarWindowStateListeners) {
            listener.onStatusBarWindowStateChanged(StatusBarManager.WINDOW_STATE_HIDDEN);
        }
    }

    private void mockLockscreenToDreamTransitionFinished() {
        for (StatusBarWindowStateListener listener : mStatusBarWindowStateListeners) {
            listener.onStatusBarWindowStateChanged(StatusBarManager.WINDOW_STATE_HIDDEN);
        }
    }

    private CollapsedStatusBarFragment resumeAndGetFragment() {
        mFragments.dispatchResume();
        processAllMessages();
        CollapsedStatusBarFragment fragment = (CollapsedStatusBarFragment) mFragment;
        fragment.disableAnimationsForTesting();
        return fragment;
    }

    private View getUserChipView() {
        return mFragment.getView().findViewById(R.id.user_switcher_container);
    }

    private View getClockView() {
        return mFragment.getView().findViewById(R.id.clock);
    }

    private View getEndSideContentView() {
        return mFragment.getView().findViewById(R.id.status_bar_end_side_content);
    }

    private View getNotificationAreaView() {
        return mFragment.getView().findViewById(R.id.notificationIcons);
    }
}

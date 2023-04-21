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

import static android.app.StatusBarManager.DISABLE2_SYSTEM_ICONS;
import static android.app.StatusBarManager.DISABLE_CLOCK;
import static android.app.StatusBarManager.DISABLE_NOTIFICATION_ICONS;
import static android.app.StatusBarManager.DISABLE_ONGOING_CALL_CHIP;
import static android.app.StatusBarManager.DISABLE_SYSTEM_INFO;

import static com.android.systemui.statusbar.events.SystemStatusAnimationSchedulerKt.IDLE;
import static com.android.systemui.statusbar.events.SystemStatusAnimationSchedulerKt.SHOWING_PERSISTENT_DOT;

import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.Fragment;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.SubscriptionManager;
import android.util.ArrayMap;
import android.util.IndentingPrintWriter;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.widget.LinearLayout;

import androidx.annotation.VisibleForTesting;
import androidx.core.animation.Animator;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.Dumpable;
import com.android.systemui.R;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shade.NotificationPanelViewController;
import com.android.systemui.shade.ShadeExpansionStateManager;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.DisableFlagsLogger.DisableState;
import com.android.systemui.statusbar.OperatorNameView;
import com.android.systemui.statusbar.OperatorNameViewController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.events.SystemStatusAnimationCallback;
import com.android.systemui.statusbar.events.SystemStatusAnimationScheduler;
import com.android.systemui.statusbar.phone.NotificationIconAreaController;
import com.android.systemui.statusbar.phone.PhoneStatusBarView;
import com.android.systemui.statusbar.phone.StatusBarHideIconsForBouncerManager;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.phone.StatusBarIconController.DarkIconManager;
import com.android.systemui.statusbar.phone.StatusBarLocation;
import com.android.systemui.statusbar.phone.StatusBarLocationPublisher;
import com.android.systemui.statusbar.phone.fragment.dagger.StatusBarFragmentComponent;
import com.android.systemui.statusbar.phone.fragment.dagger.StatusBarFragmentComponent.Startable;
import com.android.systemui.statusbar.phone.ongoingcall.OngoingCallController;
import com.android.systemui.statusbar.phone.ongoingcall.OngoingCallListener;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.window.StatusBarWindowStateController;
import com.android.systemui.statusbar.window.StatusBarWindowStateListener;
import com.android.systemui.util.CarrierConfigTracker;
import com.android.systemui.util.CarrierConfigTracker.CarrierConfigChangedListener;
import com.android.systemui.util.CarrierConfigTracker.DefaultDataSubscriptionChangedListener;
import com.android.systemui.util.settings.SecureSettings;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Contains the collapsed status bar and handles hiding/showing based on disable flags
 * and keyguard state. Also manages lifecycle to make sure the views it contains are being
 * updated by the StatusBarIconController and DarkIconManager while it is attached.
 */
@SuppressLint("ValidFragment")
public class CollapsedStatusBarFragment extends Fragment implements CommandQueue.Callbacks,
        StatusBarStateController.StateListener,
        SystemStatusAnimationCallback, Dumpable {

    public static final String TAG = "CollapsedStatusBarFragment";
    private static final String EXTRA_PANEL_STATE = "panel_state";
    public static final String STATUS_BAR_ICON_MANAGER_TAG = "status_bar_icon_manager";
    public static final int FADE_IN_DURATION = 320;
    public static final int FADE_IN_DELAY = 50;
    private StatusBarFragmentComponent mStatusBarFragmentComponent;
    private PhoneStatusBarView mStatusBar;
    private final StatusBarStateController mStatusBarStateController;
    private final KeyguardStateController mKeyguardStateController;
    private final NotificationPanelViewController mNotificationPanelViewController;
    private LinearLayout mEndSideContent;
    private View mClockView;
    private View mOngoingCallChip;
    private View mNotificationIconAreaInner;
    private int mDisabled1;
    private int mDisabled2;
    private DarkIconManager mDarkIconManager;
    private final StatusBarFragmentComponent.Factory mStatusBarFragmentComponentFactory;
    private final CommandQueue mCommandQueue;
    private final CollapsedStatusBarFragmentLogger mCollapsedStatusBarFragmentLogger;
    private final OperatorNameViewController.Factory mOperatorNameViewControllerFactory;
    private final OngoingCallController mOngoingCallController;
    private final SystemStatusAnimationScheduler mAnimationScheduler;
    private final StatusBarLocationPublisher mLocationPublisher;
    private final FeatureFlags mFeatureFlags;
    private final NotificationIconAreaController mNotificationIconAreaController;
    private final ShadeExpansionStateManager mShadeExpansionStateManager;
    private final StatusBarIconController mStatusBarIconController;
    private final CarrierConfigTracker mCarrierConfigTracker;
    private final StatusBarHideIconsForBouncerManager mStatusBarHideIconsForBouncerManager;
    private final StatusBarIconController.DarkIconManager.Factory mDarkIconManagerFactory;
    private final SecureSettings mSecureSettings;
    private final Executor mMainExecutor;
    private final DumpManager mDumpManager;
    private final StatusBarWindowStateController mStatusBarWindowStateController;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;

    private List<String> mBlockedIcons = new ArrayList<>();
    private Map<Startable, Startable.State> mStartableStates = new ArrayMap<>();

    private final OngoingCallListener mOngoingCallListener = new OngoingCallListener() {
        @Override
        public void onOngoingCallStateChanged(boolean animate) {
            disable(getContext().getDisplayId(), mDisabled1, mDisabled2, animate);
        }
    };
    private OperatorNameViewController mOperatorNameViewController;
    private StatusBarSystemEventAnimator mSystemEventAnimator;

    private final CarrierConfigChangedListener mCarrierConfigCallback =
            new CarrierConfigChangedListener() {
                @Override
                public void onCarrierConfigChanged() {
                    if (mOperatorNameViewController == null) {
                        initOperatorName();
                    } else {
                        // Already initialized, KeyguardUpdateMonitorCallback will handle the update
                    }
                }
            };

    private final DefaultDataSubscriptionChangedListener mDefaultDataListener =
            new DefaultDataSubscriptionChangedListener() {
                @Override
                public void onDefaultSubscriptionChanged(int subId) {
                    if (mOperatorNameViewController == null) {
                        initOperatorName();
                    }
                }
            };

    /**
     * Whether we've launched the secure camera over the lockscreen, but haven't yet received a
     * status bar window state change afterward.
     *
     * We wait for this state change (which will tell us whether to show/hide the status bar icons)
     * so that there is no flickering/jump cutting during the camera launch.
     */
    private boolean mWaitingForWindowStateChangeAfterCameraLaunch = false;

    /**
     * Listener that updates {@link #mWaitingForWindowStateChangeAfterCameraLaunch} when it receives
     * a new status bar window state.
     */
    private final StatusBarWindowStateListener mStatusBarWindowStateListener = state ->
            mWaitingForWindowStateChangeAfterCameraLaunch = false;

    @SuppressLint("ValidFragment")
    public CollapsedStatusBarFragment(
            StatusBarFragmentComponent.Factory statusBarFragmentComponentFactory,
            OngoingCallController ongoingCallController,
            SystemStatusAnimationScheduler animationScheduler,
            StatusBarLocationPublisher locationPublisher,
            NotificationIconAreaController notificationIconAreaController,
            ShadeExpansionStateManager shadeExpansionStateManager,
            FeatureFlags featureFlags,
            StatusBarIconController statusBarIconController,
            StatusBarIconController.DarkIconManager.Factory darkIconManagerFactory,
            StatusBarHideIconsForBouncerManager statusBarHideIconsForBouncerManager,
            KeyguardStateController keyguardStateController,
            NotificationPanelViewController notificationPanelViewController,
            StatusBarStateController statusBarStateController,
            CommandQueue commandQueue,
            CarrierConfigTracker carrierConfigTracker,
            CollapsedStatusBarFragmentLogger collapsedStatusBarFragmentLogger,
            OperatorNameViewController.Factory operatorNameViewControllerFactory,
            SecureSettings secureSettings,
            @Main Executor mainExecutor,
            DumpManager dumpManager,
            StatusBarWindowStateController statusBarWindowStateController,
            KeyguardUpdateMonitor keyguardUpdateMonitor
    ) {
        mStatusBarFragmentComponentFactory = statusBarFragmentComponentFactory;
        mOngoingCallController = ongoingCallController;
        mAnimationScheduler = animationScheduler;
        mLocationPublisher = locationPublisher;
        mNotificationIconAreaController = notificationIconAreaController;
        mShadeExpansionStateManager = shadeExpansionStateManager;
        mFeatureFlags = featureFlags;
        mStatusBarIconController = statusBarIconController;
        mStatusBarHideIconsForBouncerManager = statusBarHideIconsForBouncerManager;
        mDarkIconManagerFactory = darkIconManagerFactory;
        mKeyguardStateController = keyguardStateController;
        mNotificationPanelViewController = notificationPanelViewController;
        mStatusBarStateController = statusBarStateController;
        mCommandQueue = commandQueue;
        mCarrierConfigTracker = carrierConfigTracker;
        mCollapsedStatusBarFragmentLogger = collapsedStatusBarFragmentLogger;
        mOperatorNameViewControllerFactory = operatorNameViewControllerFactory;
        mSecureSettings = secureSettings;
        mMainExecutor = mainExecutor;
        mDumpManager = dumpManager;
        mStatusBarWindowStateController = statusBarWindowStateController;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mStatusBarWindowStateController.addListener(mStatusBarWindowStateListener);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mStatusBarWindowStateController.removeListener(mStatusBarWindowStateListener);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
            Bundle savedInstanceState) {
        return inflater.inflate(R.layout.status_bar, container, false);
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mDumpManager.registerDumpable(getClass().getSimpleName(), this);
        mStatusBarFragmentComponent = mStatusBarFragmentComponentFactory.create(this);
        mStatusBarFragmentComponent.init();
        mStartableStates.clear();
        for (Startable startable : mStatusBarFragmentComponent.getStartables()) {
            mStartableStates.put(startable, Startable.State.STARTING);
            startable.start();
            mStartableStates.put(startable, Startable.State.STARTED);
        }

        mStatusBar = (PhoneStatusBarView) view;
        View contents = mStatusBar.findViewById(R.id.status_bar_contents);
        contents.addOnLayoutChangeListener(mStatusBarLayoutListener);
        updateStatusBarLocation(contents.getLeft(), contents.getRight());
        if (savedInstanceState != null && savedInstanceState.containsKey(EXTRA_PANEL_STATE)) {
            mStatusBar.restoreHierarchyState(
                    savedInstanceState.getSparseParcelableArray(EXTRA_PANEL_STATE));
        }
        mDarkIconManager = mDarkIconManagerFactory.create(
                view.findViewById(R.id.statusIcons), StatusBarLocation.HOME);
        mDarkIconManager.setShouldLog(true);
        updateBlockedIcons();
        mStatusBarIconController.addIconGroup(mDarkIconManager);
        mEndSideContent = mStatusBar.findViewById(R.id.status_bar_end_side_content);
        mClockView = mStatusBar.findViewById(R.id.clock);
        mOngoingCallChip = mStatusBar.findViewById(R.id.ongoing_call_chip);
        showEndSideContent(false);
        showClock(false);
        initOperatorName();
        initNotificationIconArea();
        mSystemEventAnimator =
                new StatusBarSystemEventAnimator(mEndSideContent, getResources());
        mCarrierConfigTracker.addCallback(mCarrierConfigCallback);
        mCarrierConfigTracker.addDefaultDataSubscriptionChangedListener(mDefaultDataListener);
    }

    @Override
    public void onCameraLaunchGestureDetected(int source) {
        mWaitingForWindowStateChangeAfterCameraLaunch = true;
    }

    @VisibleForTesting
    void updateBlockedIcons() {
        mBlockedIcons.clear();

        // Reload the blocklist from res
        List<String> blockList = Arrays.asList(getResources().getStringArray(
                R.array.config_collapsed_statusbar_icon_blocklist));
        String vibrateIconSlot = getString(com.android.internal.R.string.status_bar_volume);
        boolean showVibrateIcon =
                mSecureSettings.getIntForUser(
                        Settings.Secure.STATUS_BAR_SHOW_VIBRATE_ICON,
                        0,
                        UserHandle.USER_CURRENT) == 0;

        // Filter out vibrate icon from the blocklist if the setting is on
        for (int i = 0; i < blockList.size(); i++) {
            if (blockList.get(i).equals(vibrateIconSlot)) {
                if (showVibrateIcon) {
                    mBlockedIcons.add(blockList.get(i));
                }
            } else {
                mBlockedIcons.add(blockList.get(i));
            }
        }

        mMainExecutor.execute(() -> mDarkIconManager.setBlockList(mBlockedIcons));
    }

    @VisibleForTesting
    List<String> getBlockedIcons() {
        return mBlockedIcons;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        SparseArray<Parcelable> states = new SparseArray<>();
        mStatusBar.saveHierarchyState(states);
        outState.putSparseParcelableArray(EXTRA_PANEL_STATE, states);
    }

    @Override
    public void onResume() {
        super.onResume();
        mCommandQueue.addCallback(this);
        mStatusBarStateController.addCallback(this);
        initOngoingCallChip();
        mAnimationScheduler.addCallback(this);

        mSecureSettings.registerContentObserverForUser(
                Settings.Secure.STATUS_BAR_SHOW_VIBRATE_ICON,
                false,
                mVolumeSettingObserver,
                UserHandle.USER_ALL);
    }

    @Override
    public void onPause() {
        super.onPause();
        mCommandQueue.removeCallback(this);
        mStatusBarStateController.removeCallback(this);
        mOngoingCallController.removeCallback(mOngoingCallListener);
        mAnimationScheduler.removeCallback(this);
        mSecureSettings.unregisterContentObserver(mVolumeSettingObserver);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mStatusBarIconController.removeIconGroup(mDarkIconManager);
        mCarrierConfigTracker.removeCallback(mCarrierConfigCallback);
        mCarrierConfigTracker.removeDataSubscriptionChangedListener(mDefaultDataListener);

        for (Startable startable : mStatusBarFragmentComponent.getStartables()) {
            mStartableStates.put(startable, Startable.State.STOPPING);
            startable.stop();
            mStartableStates.put(startable, Startable.State.STOPPED);
        }
        mDumpManager.unregisterDumpable(getClass().getSimpleName());
    }

    /** Initializes views related to the notification icon area. */
    public void initNotificationIconArea() {
        ViewGroup notificationIconArea = mStatusBar.findViewById(R.id.notification_icon_area);
        mNotificationIconAreaInner =
                mNotificationIconAreaController.getNotificationInnerAreaView();
        if (mNotificationIconAreaInner.getParent() != null) {
            ((ViewGroup) mNotificationIconAreaInner.getParent())
                    .removeView(mNotificationIconAreaInner);
        }
        notificationIconArea.addView(mNotificationIconAreaInner);

        // #disable should have already been called, so use the disable values to set visibility.
        updateNotificationIconAreaAndCallChip(mDisabled1, false);
    }

    /**
     * Returns the dagger component for this fragment.
     *
     * TODO(b/205609837): Eventually, the dagger component should encapsulate all status bar
     *   fragment functionality and we won't need to expose it here anymore.
     */
    @Nullable
    public StatusBarFragmentComponent getStatusBarFragmentComponent() {
        return mStatusBarFragmentComponent;
    }

    @Override
    public void disable(int displayId, int state1, int state2, boolean animate) {
        if (displayId != getContext().getDisplayId()) {
            return;
        }

        int state1BeforeAdjustment = state1;
        state1 = adjustDisableFlags(state1);

        mCollapsedStatusBarFragmentLogger.logDisableFlagChange(
                /* new= */ new DisableState(state1BeforeAdjustment, state2),
                /* newAfterLocalModification= */ new DisableState(state1, state2));

        final int old1 = mDisabled1;
        final int diff1 = state1 ^ old1;
        final int old2 = mDisabled2;
        final int diff2 = state2 ^ old2;
        mDisabled1 = state1;
        mDisabled2 = state2;
        if ((diff1 & DISABLE_SYSTEM_INFO) != 0 || ((diff2 & DISABLE2_SYSTEM_ICONS) != 0)) {
            if ((state1 & DISABLE_SYSTEM_INFO) != 0 || ((state2 & DISABLE2_SYSTEM_ICONS) != 0)) {
                hideEndSideContent(animate);
                hideOperatorName(animate);
            } else {
                showEndSideContent(animate);
                showOperatorName(animate);
            }
        }

        // The ongoing call chip and notification icon visibilities are intertwined, so update both
        // if either change.
        if (((diff1 & DISABLE_ONGOING_CALL_CHIP) != 0)
                || ((diff1 & DISABLE_NOTIFICATION_ICONS) != 0)) {
            updateNotificationIconAreaAndCallChip(state1, animate);
        }

        // The clock may have already been hidden, but we might want to shift its
        // visibility to GONE from INVISIBLE or vice versa
        if ((diff1 & DISABLE_CLOCK) != 0 || mClockView.getVisibility() != clockHiddenMode()) {
            if ((state1 & DISABLE_CLOCK) != 0) {
                hideClock(animate);
            } else {
                showClock(animate);
            }
        }
    }

    protected int adjustDisableFlags(int state) {
        boolean headsUpVisible =
                mStatusBarFragmentComponent.getHeadsUpAppearanceController().shouldBeVisible();

        if (!mKeyguardStateController.isLaunchTransitionFadingAway()
                && !mKeyguardStateController.isKeyguardFadingAway()
                && shouldHideNotificationIcons()
                && !(mStatusBarStateController.getState() == StatusBarState.KEYGUARD
                        && headsUpVisible)) {
            state |= DISABLE_NOTIFICATION_ICONS;
            state |= DISABLE_SYSTEM_INFO;
            state |= DISABLE_CLOCK;
        }

        if (mOngoingCallController.hasOngoingCall()) {
            state &= ~DISABLE_ONGOING_CALL_CHIP;
        } else {
            state |= DISABLE_ONGOING_CALL_CHIP;
        }

        if (headsUpVisible) {
            // Disable everything on the left side of the status bar, since the app name for the
            // heads up notification appears there instead.
            state |= DISABLE_CLOCK;
            state |= DISABLE_ONGOING_CALL_CHIP;
        }

        return state;
    }

    /**
     * Updates the visibility of the notification icon area and ongoing call chip based on disabled1
     * state.
     */
    private void updateNotificationIconAreaAndCallChip(int state1, boolean animate) {
        boolean disableNotifications = (state1 & DISABLE_NOTIFICATION_ICONS) != 0;
        boolean hasOngoingCall = (state1 & DISABLE_ONGOING_CALL_CHIP) == 0;

        // Hide notifications if the disable flag is set or we have an ongoing call.
        if (disableNotifications || hasOngoingCall) {
            hideNotificationIconArea(animate);
        } else {
            showNotificationIconArea(animate);
        }

        // Show the ongoing call chip only if there is an ongoing call *and* notification icons
        // are allowed. (The ongoing call chip occupies the same area as the notification icons,
        // so if the icons are disabled then the call chip should be, too.)
        boolean showOngoingCallChip = hasOngoingCall && !disableNotifications;
        if (showOngoingCallChip) {
            showOngoingCallChip(animate);
        } else {
            hideOngoingCallChip(animate);
        }
        mOngoingCallController.notifyChipVisibilityChanged(showOngoingCallChip);
    }

    private boolean shouldHideNotificationIcons() {
        if (!mShadeExpansionStateManager.isClosed()
                && mNotificationPanelViewController.hideStatusBarIconsWhenExpanded()) {
            return true;
        }

        // When launching the camera over the lockscreen, the icons become visible momentarily
        // before animating out, since we're not yet aware that the launching camera activity is
        // fullscreen. Even once the activity finishes launching, it takes a short time before WM
        // decides that the top app wants to hide the icons and tells us to hide them. To ensure
        // that this high-visibility animation is smooth, keep the icons hidden during a camera
        // launch until we receive a window state change which indicates that the activity is done
        // launching and WM has decided to show/hide the icons. For extra safety (to ensure the
        // icons don't remain hidden somehow) we double check that the camera is still showing, the
        // status bar window isn't hidden, and we're still occluded as well, though these checks
        // are typically unnecessary.
        final boolean hideIconsForSecureCamera =
                (mWaitingForWindowStateChangeAfterCameraLaunch ||
                        !mStatusBarWindowStateController.windowIsShowing()) &&
                        mKeyguardUpdateMonitor.isSecureCameraLaunchedOverKeyguard() &&
                        mKeyguardStateController.isOccluded();

        if (hideIconsForSecureCamera) {
            return true;
        }

        return mStatusBarHideIconsForBouncerManager.getShouldHideStatusBarIconsForBouncer();
    }

    private void hideEndSideContent(boolean animate) {
        animateHide(mEndSideContent, animate);
    }

    private void showEndSideContent(boolean animate) {
        // Only show the system icon area if we are not currently animating
        int state = mAnimationScheduler.getAnimationState();
        if (state == IDLE || state == SHOWING_PERSISTENT_DOT) {
            animateShow(mEndSideContent, animate);
        } else {
            // We are in the middle of a system status event animation, which will animate the
            // alpha (but not the visibility). Allow the view to become visible again
            mEndSideContent.setVisibility(View.VISIBLE);
        }
    }

    private void hideClock(boolean animate) {
        animateHiddenState(mClockView, clockHiddenMode(), animate);
    }

    private void showClock(boolean animate) {
        animateShow(mClockView, animate);
    }

    /** Hides the ongoing call chip. */
    public void hideOngoingCallChip(boolean animate) {
        animateHiddenState(mOngoingCallChip, View.GONE, animate);
    }

    /** Displays the ongoing call chip. */
    public void showOngoingCallChip(boolean animate) {
        animateShow(mOngoingCallChip, animate);
    }

    /**
     * If panel is expanded/expanding it usually means QS shade is opening, so
     * don't set the clock GONE otherwise it'll mess up the animation.
     */
    private int clockHiddenMode() {
        if (!mShadeExpansionStateManager.isClosed() && !mKeyguardStateController.isShowing()
                && !mStatusBarStateController.isDozing()) {
            return View.INVISIBLE;
        }
        return View.GONE;
    }

    public void hideNotificationIconArea(boolean animate) {
        animateHide(mNotificationIconAreaInner, animate);
    }

    public void showNotificationIconArea(boolean animate) {
        animateShow(mNotificationIconAreaInner, animate);
    }

    public void hideOperatorName(boolean animate) {
        if (mOperatorNameViewController != null) {
            animateHide(mOperatorNameViewController.getView(), animate);
        }
    }

    public void showOperatorName(boolean animate) {
        if (mOperatorNameViewController != null) {
            animateShow(mOperatorNameViewController.getView(), animate);
        }
    }

    /**
     * Animate a view to INVISIBLE or GONE
     */
    private void animateHiddenState(final View v, int state, boolean animate) {
        v.animate().cancel();
        if (!animate) {
            v.setAlpha(0f);
            v.setVisibility(state);
            return;
        }

        v.animate()
                .alpha(0f)
                .setDuration(160)
                .setStartDelay(0)
                .setInterpolator(Interpolators.ALPHA_OUT)
                .withEndAction(() -> v.setVisibility(state));
    }

    /**
     * Hides a view.
     */
    private void animateHide(final View v, boolean animate) {
        animateHiddenState(v, View.INVISIBLE, animate);
    }

    /**
     * Shows a view, and synchronizes the animation with Keyguard exit animations, if applicable.
     */
    private void animateShow(View v, boolean animate) {
        v.animate().cancel();
        v.setVisibility(View.VISIBLE);
        if (!animate) {
            v.setAlpha(1f);
            return;
        }
        v.animate()
                .alpha(1f)
                .setDuration(FADE_IN_DURATION)
                .setInterpolator(Interpolators.ALPHA_IN)
                .setStartDelay(FADE_IN_DELAY)

                // We need to clean up any pending end action from animateHide if we call
                // both hide and show in the same frame before the animation actually gets started.
                // cancel() doesn't really remove the end action.
                .withEndAction(null);

        // Synchronize the motion with the Keyguard fading if necessary.
        if (mKeyguardStateController.isKeyguardFadingAway()) {
            v.animate()
                    .setDuration(mKeyguardStateController.getKeyguardFadingAwayDuration())
                    .setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN)
                    .setStartDelay(mKeyguardStateController.getKeyguardFadingAwayDelay())
                    .start();
        }
    }

    private void initOperatorName() {
        int subId = SubscriptionManager.getDefaultDataSubscriptionId();
        if (mCarrierConfigTracker.getShowOperatorNameInStatusBarConfig(subId)) {
            ViewStub stub = mStatusBar.findViewById(R.id.operator_name);
            mOperatorNameViewController =
                    mOperatorNameViewControllerFactory.create((OperatorNameView) stub.inflate());
            mOperatorNameViewController.init();
            // This view should not be visible on lock-screen
            if (mKeyguardStateController.isShowing()) {
                hideOperatorName(false);
            }
        }
    }

    private void initOngoingCallChip() {
        mOngoingCallController.addCallback(mOngoingCallListener);
        mOngoingCallController.setChipView(mOngoingCallChip);
    }

    @Override
    public void onStateChanged(int newState) { }

    @Override
    public void onDozingChanged(boolean isDozing) {
        disable(getContext().getDisplayId(), mDisabled1, mDisabled2, false /* animate */);
    }

    @Nullable
    @Override
    public Animator onSystemEventAnimationBegin() {
        return mSystemEventAnimator.onSystemEventAnimationBegin();
    }

    @Nullable
    @Override
    public Animator onSystemEventAnimationFinish(boolean hasPersistentDot) {
        return mSystemEventAnimator.onSystemEventAnimationFinish(hasPersistentDot);
    }

    private boolean isSystemIconAreaDisabled() {
        return (mDisabled1 & DISABLE_SYSTEM_INFO) != 0 || (mDisabled2 & DISABLE2_SYSTEM_ICONS) != 0;
    }

    private void updateStatusBarLocation(int left, int right) {
        int leftMargin = left - mStatusBar.getLeft();
        int rightMargin = mStatusBar.getRight() - right;

        mLocationPublisher.updateStatusBarMargin(leftMargin, rightMargin);
    }

    private final ContentObserver mVolumeSettingObserver = new ContentObserver(null) {
        @Override
        public void onChange(boolean selfChange) {
            updateBlockedIcons();
        }
    };

    // Listen for view end changes of PhoneStatusBarView and publish that to the privacy dot
    private View.OnLayoutChangeListener mStatusBarLayoutListener =
            (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                if (left != oldLeft || right != oldRight) {
                    updateStatusBarLocation(left, right);
                }
            };

    @Override
    public void dump(PrintWriter printWriter, String[] args) {
        IndentingPrintWriter pw = new IndentingPrintWriter(printWriter, /* singleIndent= */"  ");
        StatusBarFragmentComponent component = mStatusBarFragmentComponent;
        if (component == null) {
            pw.println("StatusBarFragmentComponent is null");
        } else {
            Set<Startable> startables = component.getStartables();
            pw.println("Startables: " + startables.size());
            pw.increaseIndent();
            for (Startable startable : startables) {
                Startable.State startableState = mStartableStates.getOrDefault(startable,
                        Startable.State.NONE);
                pw.println(startable + ", state: " + startableState);
            }
            pw.decreaseIndent();
        }
    }
}

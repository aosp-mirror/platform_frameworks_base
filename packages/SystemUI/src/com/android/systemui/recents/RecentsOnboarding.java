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

package com.android.systemui.recents;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;

import static com.android.systemui.Prefs.Key.HAS_DISMISSED_RECENTS_QUICK_SCRUB_ONBOARDING_ONCE;
import static com.android.systemui.Prefs.Key.DISMISSED_RECENTS_SWIPE_UP_ONBOARDING_COUNT;
import static com.android.systemui.Prefs.Key.HAS_SEEN_RECENTS_QUICK_SCRUB_ONBOARDING;
import static com.android.systemui.Prefs.Key.HAS_SEEN_RECENTS_SWIPE_UP_ONBOARDING;
import static com.android.systemui.Prefs.Key.OVERVIEW_OPENED_COUNT;
import static com.android.systemui.Prefs.Key.OVERVIEW_OPENED_FROM_HOME_COUNT;
import static com.android.systemui.shared.system.LauncherEventUtil.VISIBLE;
import static com.android.systemui.shared.system.LauncherEventUtil.DISMISS;
import static com.android.systemui.shared.system.LauncherEventUtil.RECENTS_QUICK_SCRUB_ONBOARDING_TIP;
import static com.android.systemui.shared.system.LauncherEventUtil.RECENTS_SWIPE_UP_ONBOARDING_TIP;

import android.annotation.StringRes;
import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.ShapeDrawable;
import android.os.Build;
import android.os.SystemProperties;
import android.os.UserManager;
import android.os.RemoteException;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.OverviewProxyService;
import com.android.systemui.Prefs;
import com.android.systemui.R;
import com.android.systemui.recents.misc.SysUiTaskStackChangeListener;
import com.android.systemui.shared.recents.IOverviewProxy;
import com.android.systemui.shared.system.ActivityManagerWrapper;

import java.io.PrintWriter;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Shows onboarding for the new recents interaction in P (codenamed quickstep).
 */
@TargetApi(Build.VERSION_CODES.P)
public class RecentsOnboarding {

    private static final String TAG = "RecentsOnboarding";
    private static final boolean RESET_PREFS_FOR_DEBUG = false;
    private static final boolean ONBOARDING_ENABLED = true;
    private static final long SHOW_DELAY_MS = 500;
    private static final long SHOW_DURATION_MS = 300;
    private static final long HIDE_DURATION_MS = 100;
    // Show swipe-up tips after opening overview from home this number of times.
    private static final int SWIPE_UP_SHOW_ON_OVERVIEW_OPENED_FROM_HOME_COUNT = 3;
    // Show quick scrub tips after opening overview this number of times.
    private static final int QUICK_SCRUB_SHOW_ON_OVERVIEW_OPENED_COUNT = 10;
    // Maximum number of dismissals while still showing swipe-up tips.
    private static final int MAX_DISMISSAL_ON_SWIPE_UP_SHOW = 2;
    // Number of dismissals for swipe-up tips when exponential backoff starts.
    private static final int BACKOFF_DISMISSAL_COUNT_ON_SWIPE_UP_SHOW = 1;
    // After explicitly dismissing for <= BACKOFF_DISMISSAL_COUNT_ON_SWIPE_UP_SHOW times, show again
    // after launching this number of apps for swipe-up tips.
    private static final int SWIPE_UP_SHOW_ON_APP_LAUNCH_AFTER_DISMISS = 5;
    // After explicitly dismissing for > BACKOFF_DISMISSAL_COUNT_ON_SWIPE_UP_SHOW but
    // <= MAX_DISMISSAL_ON_SWIPE_UP_SHOW times, show again after launching this number of apps for
    // swipe-up tips.
    private static final int SWIPE_UP_SHOW_ON_APP_LAUNCH_AFTER_DISMISS_BACK_OFF = 40;

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final OverviewProxyService mOverviewProxyService;
    private Set<String> mBlacklistedPackages;
    private final View mLayout;
    private final TextView mTextView;
    private final ImageView mDismissView;
    private final View mArrowView;
    private final int mOnboardingToastColor;
    private final int mOnboardingToastArrowRadius;
    private int mNavBarHeight;

    private boolean mOverviewProxyListenerRegistered;
    private boolean mTaskListenerRegistered;
    private boolean mLayoutAttachedToWindow;
    private boolean mHasDismissedSwipeUpTip;
    private boolean mHasDismissedQuickScrubTip;
    private int mNumAppsLaunchedSinceSwipeUpTipDismiss;
    private int mOverviewOpenedCountSinceQuickScrubTipDismiss;

    private final SysUiTaskStackChangeListener mTaskListener = new SysUiTaskStackChangeListener() {
        private String mLastPackageName;

        @Override
        public void onTaskCreated(int taskId, ComponentName componentName) {
            onAppLaunch();
        }

        @Override
        public void onTaskMovedToFront(int taskId) {
            onAppLaunch();
        }

        private void onAppLaunch() {
            ActivityManager.RunningTaskInfo info = ActivityManagerWrapper.getInstance()
                    .getRunningTask(ACTIVITY_TYPE_UNDEFINED /* ignoreActivityType */);
            if (info == null) {
                return;
            }
            if (mBlacklistedPackages.contains(info.baseActivity.getPackageName())) {
                hide(true);
                return;
            }
            if (info.baseActivity.getPackageName().equals(mLastPackageName)) {
                return;
            }
            mLastPackageName = info.baseActivity.getPackageName();
            int activityType = info.configuration.windowConfiguration.getActivityType();
            if (activityType == ACTIVITY_TYPE_STANDARD) {
                boolean alreadySeenSwipeUpOnboarding = hasSeenSwipeUpOnboarding();
                boolean alreadySeenQuickScrubsOnboarding = hasSeenQuickScrubOnboarding();
                if (alreadySeenSwipeUpOnboarding && alreadySeenQuickScrubsOnboarding) {
                    onDisconnectedFromLauncher();
                    return;
                }

                boolean shouldLog = false;
                if (!alreadySeenSwipeUpOnboarding) {
                    if (getOpenedOverviewFromHomeCount()
                            >= SWIPE_UP_SHOW_ON_OVERVIEW_OPENED_FROM_HOME_COUNT) {
                        if (mHasDismissedSwipeUpTip) {
                            int hasDimissedSwipeUpOnboardingCount =
                                    getDismissedSwipeUpOnboardingCount();
                            if (hasDimissedSwipeUpOnboardingCount
                                    > MAX_DISMISSAL_ON_SWIPE_UP_SHOW) {
                                return;
                            }
                            final int swipeUpShowOnAppLauncherAfterDismiss =
                                    hasDimissedSwipeUpOnboardingCount
                                            <= BACKOFF_DISMISSAL_COUNT_ON_SWIPE_UP_SHOW
                                            ? SWIPE_UP_SHOW_ON_APP_LAUNCH_AFTER_DISMISS
                                            : SWIPE_UP_SHOW_ON_APP_LAUNCH_AFTER_DISMISS_BACK_OFF;
                            mNumAppsLaunchedSinceSwipeUpTipDismiss++;
                            if (mNumAppsLaunchedSinceSwipeUpTipDismiss
                                    >= swipeUpShowOnAppLauncherAfterDismiss) {
                                mNumAppsLaunchedSinceSwipeUpTipDismiss = 0;
                                shouldLog = show(R.string.recents_swipe_up_onboarding);
                            }
                        } else {
                            shouldLog = show(R.string.recents_swipe_up_onboarding);
                        }
                        if (shouldLog) {
                            notifyOnTip(VISIBLE, RECENTS_SWIPE_UP_ONBOARDING_TIP);
                        }
                    }
                } else {
                    if (getOpenedOverviewCount() >= QUICK_SCRUB_SHOW_ON_OVERVIEW_OPENED_COUNT) {
                        if (mHasDismissedQuickScrubTip) {
                            if (mOverviewOpenedCountSinceQuickScrubTipDismiss
                                    >= QUICK_SCRUB_SHOW_ON_OVERVIEW_OPENED_COUNT) {
                                mOverviewOpenedCountSinceQuickScrubTipDismiss = 0;
                                shouldLog = show(R.string.recents_quick_scrub_onboarding);
                            }
                        } else {
                            shouldLog = show(R.string.recents_quick_scrub_onboarding);
                        }
                        if (shouldLog) {
                            notifyOnTip(VISIBLE, RECENTS_QUICK_SCRUB_ONBOARDING_TIP);
                        }
                    }

                }
            } else {
                hide(false);
            }
        }
    };

    private OverviewProxyService.OverviewProxyListener mOverviewProxyListener =
            new OverviewProxyService.OverviewProxyListener() {
                @Override
                public void onOverviewShown(boolean fromHome) {
                    if (!hasSeenSwipeUpOnboarding() && !fromHome) {
                        setHasSeenSwipeUpOnboarding(true);
                    }
                    if (fromHome) {
                        incrementOpenedOverviewFromHomeCount();
                    }
                    incrementOpenedOverviewCount();

                    if (getOpenedOverviewCount() >= QUICK_SCRUB_SHOW_ON_OVERVIEW_OPENED_COUNT) {
                        if (mHasDismissedQuickScrubTip) {
                            mOverviewOpenedCountSinceQuickScrubTipDismiss++;
                        }
                    }
                }

                @Override
                public void onQuickStepStarted() {
                    hide(true);
                }

                @Override
                public void onQuickScrubStarted() {
                    boolean alreadySeenQuickScrubsOnboarding = hasSeenQuickScrubOnboarding();
                    if (!alreadySeenQuickScrubsOnboarding) {
                        setHasSeenQuickScrubOnboarding(true);
                    }
                }
            };

    private final View.OnAttachStateChangeListener mOnAttachStateChangeListener
            = new View.OnAttachStateChangeListener() {
        @Override
        public void onViewAttachedToWindow(View view) {
            if (view == mLayout) {
                mContext.registerReceiver(mReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF));
                mLayoutAttachedToWindow = true;
                if (view.getTag().equals(R.string.recents_swipe_up_onboarding)) {
                    mHasDismissedSwipeUpTip = false;
                } else {
                    mHasDismissedQuickScrubTip = false;
                }
            }
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            if (view == mLayout) {
                mLayoutAttachedToWindow = false;
                if (view.getTag().equals(R.string.recents_quick_scrub_onboarding)) {
                    mHasDismissedQuickScrubTip = true;
                    if (hasDismissedQuickScrubOnboardingOnce()) {
                        // If user dismisses the quick scrub tip twice, we consider user has seen it
                        // and do not show it again.
                        setHasSeenQuickScrubOnboarding(true);
                    } else {
                        setHasDismissedQuickScrubOnboardingOnce(true);
                    }
                    mOverviewOpenedCountSinceQuickScrubTipDismiss = 0;
                }
                mContext.unregisterReceiver(mReceiver);
            }
        }
    };

    public RecentsOnboarding(Context context, OverviewProxyService overviewProxyService) {
        mContext = context;
        mOverviewProxyService = overviewProxyService;
        final Resources res = context.getResources();
        mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        mBlacklistedPackages = new HashSet<>();
        Collections.addAll(mBlacklistedPackages, res.getStringArray(
                R.array.recents_onboarding_blacklisted_packages));
        mLayout = LayoutInflater.from(mContext).inflate(R.layout.recents_onboarding, null);
        mTextView = mLayout.findViewById(R.id.onboarding_text);
        mDismissView = mLayout.findViewById(R.id.dismiss);
        mArrowView = mLayout.findViewById(R.id.arrow);

        TypedValue typedValue = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.colorAccent, typedValue, true);
        mOnboardingToastColor = res.getColor(typedValue.resourceId);
        mOnboardingToastArrowRadius = res.getDimensionPixelSize(
                R.dimen.recents_onboarding_toast_arrow_corner_radius);

        mLayout.addOnAttachStateChangeListener(mOnAttachStateChangeListener);
        mDismissView.setOnClickListener(v -> {
            hide(true);
            if (v.getTag().equals(R.string.recents_swipe_up_onboarding)) {
                mHasDismissedSwipeUpTip = true;
                mNumAppsLaunchedSinceSwipeUpTipDismiss = 0;
                setDismissedSwipeUpOnboardingCount(getDismissedSwipeUpOnboardingCount() + 1);
                if (getDismissedSwipeUpOnboardingCount() > MAX_DISMISSAL_ON_SWIPE_UP_SHOW) {
                    setHasSeenSwipeUpOnboarding(true);
                }
                notifyOnTip(DISMISS, RECENTS_SWIPE_UP_ONBOARDING_TIP);
            } else {
                notifyOnTip(DISMISS, RECENTS_QUICK_SCRUB_ONBOARDING_TIP);
            }
        });

        ViewGroup.LayoutParams arrowLp = mArrowView.getLayoutParams();
        ShapeDrawable arrowDrawable = new ShapeDrawable(TriangleShape.create(
                arrowLp.width, arrowLp.height, false));
        Paint arrowPaint = arrowDrawable.getPaint();
        arrowPaint.setColor(mOnboardingToastColor);
        // The corner path effect won't be reflected in the shadow, but shouldn't be noticeable.
        arrowPaint.setPathEffect(new CornerPathEffect(mOnboardingToastArrowRadius));
        mArrowView.setBackground(arrowDrawable);

        if (RESET_PREFS_FOR_DEBUG) {
            setHasSeenSwipeUpOnboarding(false);
            setHasSeenQuickScrubOnboarding(false);
            setDismissedSwipeUpOnboardingCount(0);
            setHasDismissedQuickScrubOnboardingOnce(false);
            setOpenedOverviewCount(0);
            setOpenedOverviewFromHomeCount(0);
        }
    }

    private void notifyOnTip(int action, int target) {
        try {
            IOverviewProxy overviewProxy = mOverviewProxyService.getProxy();
            if(overviewProxy != null) {
                overviewProxy.onTip(action, target);
            }
        } catch (RemoteException e) {}
    }

    public void onConnectedToLauncher() {
        if (!ONBOARDING_ENABLED) {
            return;
        }

        if (hasSeenSwipeUpOnboarding() && hasSeenQuickScrubOnboarding()) {
            return;
        }

        if (!mOverviewProxyListenerRegistered) {
            mOverviewProxyService.addCallback(mOverviewProxyListener);
            mOverviewProxyListenerRegistered = true;
        }
        if (!mTaskListenerRegistered) {
            ActivityManagerWrapper.getInstance().registerTaskStackListener(mTaskListener);
            mTaskListenerRegistered = true;
        }
    }

    public void onDisconnectedFromLauncher() {

        if (mOverviewProxyListenerRegistered) {
            mOverviewProxyService.removeCallback(mOverviewProxyListener);
            mOverviewProxyListenerRegistered = false;
        }
        if (mTaskListenerRegistered) {
            ActivityManagerWrapper.getInstance().unregisterTaskStackListener(mTaskListener);
            mTaskListenerRegistered = false;
        }

        mHasDismissedSwipeUpTip = false;
        mHasDismissedQuickScrubTip = false;
        mNumAppsLaunchedSinceSwipeUpTipDismiss = 0;
        mOverviewOpenedCountSinceQuickScrubTipDismiss = 0;
        hide(true);
    }

    public void onConfigurationChanged(Configuration newConfiguration) {
        if (newConfiguration.orientation != Configuration.ORIENTATION_PORTRAIT) {
            hide(false);
        }
    }

    public boolean show(@StringRes int stringRes) {
        if (!shouldShow()) {
            return false;
        }
        mDismissView.setTag(stringRes);
        mLayout.setTag(stringRes);
        mTextView.setText(stringRes);
        // Only show in portrait.
        int orientation = mContext.getResources().getConfiguration().orientation;
        if (!mLayoutAttachedToWindow && orientation == Configuration.ORIENTATION_PORTRAIT) {
            mLayout.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

            final int gravity;
            final int x;
            if (stringRes == R.string.recents_swipe_up_onboarding) {
                gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                x = 0;
            } else {
                int layoutDirection =
                        mContext.getResources().getConfiguration().getLayoutDirection();
                gravity = Gravity.BOTTOM | (layoutDirection == View.LAYOUT_DIRECTION_LTR
                        ? Gravity.LEFT : Gravity.RIGHT);
                x = mContext.getResources().getDimensionPixelSize(
                        R.dimen.recents_quick_scrub_onboarding_margin_start);
            }
            mWindowManager.addView(mLayout, getWindowLayoutParams(gravity, x));
            mLayout.setAlpha(0);
            mLayout.animate()
                    .alpha(1f)
                    .withLayer()
                    .setStartDelay(SHOW_DELAY_MS)
                    .setDuration(SHOW_DURATION_MS)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
            return true;
        }
        return false;
    }

    /**
     * @return True unless setprop has been set to false, we're in demo mode, or running tests in
     * automation.
     */
    private boolean shouldShow() {
        return SystemProperties.getBoolean("persist.quickstep.onboarding.enabled",
                !(mContext.getSystemService(UserManager.class)).isDemoUser() &&
                        !ActivityManager.isRunningInTestHarness());
    }

    public void hide(boolean animate) {
        if (mLayoutAttachedToWindow) {
            if (animate) {
                mLayout.animate()
                        .alpha(0f)
                        .withLayer()
                        .setStartDelay(0)
                        .setDuration(HIDE_DURATION_MS)
                        .setInterpolator(new AccelerateInterpolator())
                        .withEndAction(() -> mWindowManager.removeViewImmediate(mLayout))
                        .start();
            } else {
                mLayout.animate().cancel();
                mWindowManager.removeViewImmediate(mLayout);
            }
        }
    }

    public void setNavBarHeight(int navBarHeight) {
        mNavBarHeight = navBarHeight;
    }

    public void dump(PrintWriter pw) {
        pw.println("RecentsOnboarding {");
        pw.println("      mTaskListenerRegistered: " + mTaskListenerRegistered);
        pw.println("      mOverviewProxyListenerRegistered: " + mOverviewProxyListenerRegistered);
        pw.println("      mLayoutAttachedToWindow: " + mLayoutAttachedToWindow);
        pw.println("      mHasDismissedSwipeUpTip: " + mHasDismissedSwipeUpTip);
        pw.println("      mHasDismissedQuickScrubTip: " + mHasDismissedQuickScrubTip);
        pw.println("      mNumAppsLaunchedSinceSwipeUpTipDismiss: "
                + mNumAppsLaunchedSinceSwipeUpTipDismiss);
        pw.println("      hasSeenSwipeUpOnboarding: " + hasSeenSwipeUpOnboarding());
        pw.println("      hasSeenQuickScrubOnboarding: " + hasSeenQuickScrubOnboarding());
        pw.println("      getDismissedSwipeUpOnboardingCount: "
                + getDismissedSwipeUpOnboardingCount());
        pw.println("      hasDismissedQuickScrubOnboardingOnce: "
                + hasDismissedQuickScrubOnboardingOnce());
        pw.println("      getOpenedOverviewCount: " + getOpenedOverviewCount());
        pw.println("      getOpenedOverviewFromHomeCount: " + getOpenedOverviewFromHomeCount());
        pw.println("    }");
    }

    private WindowManager.LayoutParams getWindowLayoutParams(int gravity, int x) {
        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                x, -mNavBarHeight / 2,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT);
        lp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
        lp.setTitle("RecentsOnboarding");
        lp.gravity = gravity;
        return lp;
    }

    private boolean hasSeenSwipeUpOnboarding() {
        return Prefs.getBoolean(mContext, HAS_SEEN_RECENTS_SWIPE_UP_ONBOARDING, false);
    }

    private void setHasSeenSwipeUpOnboarding(boolean hasSeenSwipeUpOnboarding) {
        Prefs.putBoolean(mContext, HAS_SEEN_RECENTS_SWIPE_UP_ONBOARDING, hasSeenSwipeUpOnboarding);
        if (hasSeenSwipeUpOnboarding && hasSeenQuickScrubOnboarding()) {
            onDisconnectedFromLauncher();
        }
    }

    private boolean hasSeenQuickScrubOnboarding() {
        return Prefs.getBoolean(mContext, HAS_SEEN_RECENTS_QUICK_SCRUB_ONBOARDING, false);
    }

    private void setHasSeenQuickScrubOnboarding(boolean hasSeenQuickScrubOnboarding) {
        Prefs.putBoolean(mContext, HAS_SEEN_RECENTS_QUICK_SCRUB_ONBOARDING,
                hasSeenQuickScrubOnboarding);
        if (hasSeenQuickScrubOnboarding && hasSeenSwipeUpOnboarding()) {
            onDisconnectedFromLauncher();
        }
    }

    private int getDismissedSwipeUpOnboardingCount() {
        return Prefs.getInt(mContext, DISMISSED_RECENTS_SWIPE_UP_ONBOARDING_COUNT, 0);
    }

    private void setDismissedSwipeUpOnboardingCount(int dismissedSwipeUpOnboardingCount) {
        Prefs.putInt(mContext, DISMISSED_RECENTS_SWIPE_UP_ONBOARDING_COUNT,
                dismissedSwipeUpOnboardingCount);
    }

    private boolean hasDismissedQuickScrubOnboardingOnce() {
        return Prefs.getBoolean(mContext, HAS_DISMISSED_RECENTS_QUICK_SCRUB_ONBOARDING_ONCE, false);
    }

    private void setHasDismissedQuickScrubOnboardingOnce(
            boolean hasDismissedQuickScrubOnboardingOnce) {
        Prefs.putBoolean(mContext, HAS_DISMISSED_RECENTS_QUICK_SCRUB_ONBOARDING_ONCE,
                hasDismissedQuickScrubOnboardingOnce);
    }

    private int getOpenedOverviewFromHomeCount() {
        return Prefs.getInt(mContext, OVERVIEW_OPENED_FROM_HOME_COUNT, 0);
    }

    private void incrementOpenedOverviewFromHomeCount() {
        int openedOverviewFromHomeCount = getOpenedOverviewFromHomeCount();
        if (openedOverviewFromHomeCount >= SWIPE_UP_SHOW_ON_OVERVIEW_OPENED_FROM_HOME_COUNT) {
            return;
        }
        setOpenedOverviewFromHomeCount(openedOverviewFromHomeCount + 1);
    }

    private void setOpenedOverviewFromHomeCount(int openedOverviewFromHomeCount) {
        Prefs.putInt(mContext, OVERVIEW_OPENED_FROM_HOME_COUNT, openedOverviewFromHomeCount);
    }

    private int getOpenedOverviewCount() {
        return Prefs.getInt(mContext, OVERVIEW_OPENED_COUNT, 0);
    }

    private void incrementOpenedOverviewCount() {
        int openedOverviewCount = getOpenedOverviewCount();
        if (openedOverviewCount >= QUICK_SCRUB_SHOW_ON_OVERVIEW_OPENED_COUNT) {
            return;
        }
        setOpenedOverviewCount(openedOverviewCount + 1);
    }

    private void setOpenedOverviewCount(int openedOverviewCount) {
        Prefs.putInt(mContext, OVERVIEW_OPENED_COUNT, openedOverviewCount);
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                hide(false);
            }
        }
    };
}

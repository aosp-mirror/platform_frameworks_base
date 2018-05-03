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
import static com.android.systemui.Prefs.Key.HAS_SEEN_RECENTS_QUICK_SCRUB_ONBOARDING;
import static com.android.systemui.Prefs.Key.HAS_SEEN_RECENTS_SWIPE_UP_ONBOARDING;
import static com.android.systemui.Prefs.Key.OVERVIEW_OPENED_COUNT;
import static com.android.systemui.Prefs.Key.OVERVIEW_OPENED_FROM_HOME_COUNT;

import android.annotation.StringRes;
import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.ShapeDrawable;
import android.os.Build;
import android.os.SystemProperties;
import android.os.UserManager;
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
import com.android.systemui.shared.system.ActivityManagerWrapper;

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
    private static final boolean ONBOARDING_ENABLED = false;
    private static final long SHOW_DELAY_MS = 500;
    private static final long SHOW_HIDE_DURATION_MS = 300;
    // Show swipe-up tips after opening overview from home this number of times.
    private static final int SWIPE_UP_SHOW_ON_OVERVIEW_OPENED_FROM_HOME_COUNT = 3;
    // Show quick scrub tips after opening overview this number of times.
    private static final int QUICK_SCRUB_SHOW_ON_OVERVIEW_OPENED_COUNT = 10;
    // After explicitly dismissing, show again after launching this number of apps for swipe-up
    // tips.
    private static final int SWIPE_UP_SHOW_ON_APP_LAUNCH_AFTER_DISMISS = 5;

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
    private int mLastTaskId;
    private boolean mHasDismissedSwipeUpTip;
    private boolean mHasDismissedQuickScrubTip;
    private int mNumAppsLaunchedSinceSwipeUpTipDismiss;
    private int mOverviewOpenedCountSinceQuickScrubTipDismiss;

    private final SysUiTaskStackChangeListener mTaskListener = new SysUiTaskStackChangeListener() {
        @Override
        public void onTaskStackChanged() {
            ActivityManager.RunningTaskInfo info = ActivityManagerWrapper.getInstance()
                    .getRunningTask(ACTIVITY_TYPE_UNDEFINED /* ignoreActivityType */);
            if (info == null) {
                return;
            }
            if (mBlacklistedPackages.contains(info.baseActivity.getPackageName())) {
                hide(true);
                return;
            }
            if (info.id == mLastTaskId) {
                // We only count launches that go to a new task.
                return;
            }
            int activityType = info.configuration.windowConfiguration.getActivityType();
            if (activityType == ACTIVITY_TYPE_STANDARD) {
                mLastTaskId = info.id;

                boolean alreadySeenSwipeUpOnboarding = hasSeenSwipeUpOnboarding();
                boolean alreadySeenQuickScrubsOnboarding = hasSeenQuickScrubOnboarding();
                if (alreadySeenSwipeUpOnboarding && alreadySeenQuickScrubsOnboarding) {
                    onDisconnectedFromLauncher();
                    return;
                }

                if (!alreadySeenSwipeUpOnboarding) {
                    if (getOpenedOverviewFromHomeCount()
                            >= SWIPE_UP_SHOW_ON_OVERVIEW_OPENED_FROM_HOME_COUNT) {
                        if (mHasDismissedSwipeUpTip) {
                            mNumAppsLaunchedSinceSwipeUpTipDismiss++;
                            if (mNumAppsLaunchedSinceSwipeUpTipDismiss
                                    == SWIPE_UP_SHOW_ON_APP_LAUNCH_AFTER_DISMISS) {
                                mNumAppsLaunchedSinceSwipeUpTipDismiss = 0;
                                show(R.string.recents_swipe_up_onboarding);
                            }
                        } else {
                            show(R.string.recents_swipe_up_onboarding);
                        }
                    }
                } else {
                    if (getOpenedOverviewCount() >= QUICK_SCRUB_SHOW_ON_OVERVIEW_OPENED_COUNT) {
                        if (mHasDismissedQuickScrubTip) {
                            if (mOverviewOpenedCountSinceQuickScrubTipDismiss
                                    == QUICK_SCRUB_SHOW_ON_OVERVIEW_OPENED_COUNT) {
                                mOverviewOpenedCountSinceQuickScrubTipDismiss = 0;
                                show(R.string.recents_quick_scrub_onboarding);
                            }
                        } else {
                            show(R.string.recents_quick_scrub_onboarding);
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
                        setOpenedOverviewFromHomeCount(getOpenedOverviewFromHomeCount() + 1);
                    }
                    setOpenedOverviewCount(getOpenedOverviewCount() + 1);

                    if (getOpenedOverviewCount() >= QUICK_SCRUB_SHOW_ON_OVERVIEW_OPENED_COUNT) {
                        if (mHasDismissedQuickScrubTip) {
                            mOverviewOpenedCountSinceQuickScrubTipDismiss++;
                        }
                    }
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
            setHasDismissedQuickScrubOnboardingOnce(false);
            setOpenedOverviewCount(0);
            setOpenedOverviewFromHomeCount(0);
        }
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
        hide(false);
    }

    public void onConfigurationChanged(Configuration newConfiguration) {
        if (newConfiguration.orientation != Configuration.ORIENTATION_PORTRAIT) {
            hide(false);
        }
    }

    public void show(@StringRes int stringRes) {
        if (!shouldShow()) {
            return;
        }
        if (mLayoutAttachedToWindow) {
            hide(false);
        }
        mDismissView.setTag(stringRes);
        mLayout.setTag(stringRes);
        mTextView.setText(stringRes);
        // Only show in portrait.
        int orientation = mContext.getResources().getConfiguration().orientation;
        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            mLayout.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

            mWindowManager.addView(mLayout, getWindowLayoutParams());
            int layoutHeight = mLayout.getHeight();
            if (layoutHeight == 0) {
                mLayout.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
                layoutHeight = mLayout.getMeasuredHeight();
            }
            mLayout.setTranslationY(layoutHeight);
            mLayout.setAlpha(0);
            mLayout.animate()
                    .translationY(0)
                    .alpha(1f)
                    .withLayer()
                    .setStartDelay(SHOW_DELAY_MS)
                    .setDuration(SHOW_HIDE_DURATION_MS)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
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
                        .translationY(mLayout.getHeight())
                        .alpha(0f)
                        .withLayer()
                        .setDuration(SHOW_HIDE_DURATION_MS)
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

    private WindowManager.LayoutParams getWindowLayoutParams() {
        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                0, -mNavBarHeight / 2,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT);
        lp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
        lp.setTitle("RecentsOnboarding");
        lp.gravity = Gravity.BOTTOM;
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

    private void setOpenedOverviewFromHomeCount(int openedOverviewFromHomeCount) {
        Prefs.putInt(mContext, OVERVIEW_OPENED_FROM_HOME_COUNT, openedOverviewFromHomeCount);
    }

    private int getOpenedOverviewCount() {
        return Prefs.getInt(mContext, OVERVIEW_OPENED_COUNT, 0);
    }

    private void setOpenedOverviewCount(int openedOverviewCount) {
        Prefs.putInt(mContext, OVERVIEW_OPENED_COUNT, openedOverviewCount);
    }
}

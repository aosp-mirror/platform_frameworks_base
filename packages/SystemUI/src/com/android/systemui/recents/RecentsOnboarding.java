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
import android.text.TextUtils;
import android.util.Log;
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
    // Don't show the onboarding until the user has launched this number of apps.
    private static final int SHOW_ON_APP_LAUNCH = 2;
    // After explicitly dismissing, show again after launching this number of apps.
    private static final int SHOW_ON_APP_LAUNCH_AFTER_DISMISS = 5;

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

    private boolean mTaskListenerRegistered;
    private boolean mLayoutAttachedToWindow;
    private int mLastTaskId;
    private boolean mHasDismissed;
    private int mNumAppsLaunchedSinceDismiss;

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
                int numAppsLaunched = mHasDismissed ? mNumAppsLaunchedSinceDismiss
                        : Prefs.getInt(mContext, Prefs.Key.NUM_APPS_LAUNCHED, 0);
                int showOnAppLaunch = mHasDismissed ? SHOW_ON_APP_LAUNCH_AFTER_DISMISS
                        : SHOW_ON_APP_LAUNCH;
                numAppsLaunched++;
                if (numAppsLaunched >= showOnAppLaunch) {
                    show();
                } else {
                    if (mHasDismissed) {
                        mNumAppsLaunchedSinceDismiss = numAppsLaunched;
                    } else {
                        Prefs.putInt(mContext, Prefs.Key.NUM_APPS_LAUNCHED, numAppsLaunched);
                    }
                }
            } else {
                hide(false);
            }
        }
    };

    private final View.OnAttachStateChangeListener mOnAttachStateChangeListener
            = new View.OnAttachStateChangeListener() {
        @Override
        public void onViewAttachedToWindow(View view) {
            if (view == mLayout) {
                mLayoutAttachedToWindow = true;
                mHasDismissed = false;
            }
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            if (view == mLayout) {
                mLayoutAttachedToWindow = false;
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
            mHasDismissed = true;
            mNumAppsLaunchedSinceDismiss = 0;
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
            Prefs.putBoolean(mContext, Prefs.Key.HAS_SEEN_RECENTS_ONBOARDING, false);
            Prefs.putInt(mContext, Prefs.Key.NUM_APPS_LAUNCHED, 0);
        }
    }

    public void onConnectedToLauncher() {
        if (!ONBOARDING_ENABLED) {
            return;
        }
        boolean alreadySeenRecentsOnboarding = Prefs.getBoolean(mContext,
                Prefs.Key.HAS_SEEN_RECENTS_ONBOARDING, false);
        if (!mTaskListenerRegistered && !alreadySeenRecentsOnboarding) {
            ActivityManagerWrapper.getInstance().registerTaskStackListener(mTaskListener);
            mTaskListenerRegistered = true;
        }
    }

    public void onQuickStepStarted() {
        boolean alreadySeenRecentsOnboarding = Prefs.getBoolean(mContext,
                Prefs.Key.HAS_SEEN_RECENTS_ONBOARDING, false);
        if (!alreadySeenRecentsOnboarding) {
            Prefs.putBoolean(mContext, Prefs.Key.HAS_SEEN_RECENTS_ONBOARDING, true);
            onDisconnectedFromLauncher();
        }
    }

    public void onDisconnectedFromLauncher() {
        if (mTaskListenerRegistered) {
            ActivityManagerWrapper.getInstance().unregisterTaskStackListener(mTaskListener);
            mTaskListenerRegistered = false;
        }
        mHasDismissed = false;
        mNumAppsLaunchedSinceDismiss = 0;
        hide(false);
    }

    public void onConfigurationChanged(Configuration newConfiguration) {
        if (newConfiguration.orientation != Configuration.ORIENTATION_PORTRAIT) {
            hide(false);
        }
    }

    public void show() {
        if (!shouldShow()) {
            return;
        }
        CharSequence onboardingText = mOverviewProxyService.getOnboardingText();
        if (TextUtils.isEmpty(onboardingText)) {
            Log.w(TAG, "Unable to get onboarding text");
            return;
        }
        mTextView.setText(onboardingText);
        // Only show in portrait.
        int orientation = mContext.getResources().getConfiguration().orientation;
        if (!mLayoutAttachedToWindow && orientation == Configuration.ORIENTATION_PORTRAIT) {
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
                        .withEndAction(() -> mWindowManager.removeView(mLayout))
                        .start();
            } else {
                mLayout.animate().cancel();
                mWindowManager.removeView(mLayout);
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
}

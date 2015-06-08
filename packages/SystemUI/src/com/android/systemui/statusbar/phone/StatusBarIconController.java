/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.ArraySet;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.util.NotificationColorUtil;
import com.android.systemui.BatteryMeterView;
import com.android.systemui.FontSizeUtils;
import com.android.systemui.R;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.SignalClusterView;
import com.android.systemui.statusbar.StatusBarIconView;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Controls everything regarding the icons in the status bar and on Keyguard, including, but not
 * limited to: notification icons, signal cluster, additional status icons, and clock in the status
 * bar.
 */
public class StatusBarIconController implements Tunable {

    public static final long DEFAULT_TINT_ANIMATION_DURATION = 120;

    public static final String ICON_BLACKLIST = "icon_blacklist";

    private Context mContext;
    private PhoneStatusBar mPhoneStatusBar;
    private Interpolator mLinearOutSlowIn;
    private Interpolator mFastOutSlowIn;
    private DemoStatusIcons mDemoStatusIcons;
    private NotificationColorUtil mNotificationColorUtil;

    private LinearLayout mSystemIconArea;
    private LinearLayout mStatusIcons;
    private SignalClusterView mSignalCluster;
    private LinearLayout mStatusIconsKeyguard;
    private IconMerger mNotificationIcons;
    private View mNotificationIconArea;
    private ImageView mMoreIcon;
    private BatteryMeterView mBatteryMeterView;
    private TextView mClock;

    private int mIconSize;
    private int mIconHPadding;

    private int mIconTint = Color.WHITE;
    private float mDarkIntensity;

    private boolean mTransitionPending;
    private boolean mTintChangePending;
    private float mPendingDarkIntensity;
    private ValueAnimator mTintAnimator;

    private int mDarkModeIconColorSingleTone;
    private int mLightModeIconColorSingleTone;

    private final Handler mHandler;
    private boolean mTransitionDeferring;
    private long mTransitionDeferringStartTime;
    private long mTransitionDeferringDuration;

    private final ArraySet<String> mIconBlacklist = new ArraySet<>();

    private final Runnable mTransitionDeferringDoneRunnable = new Runnable() {
        @Override
        public void run() {
            mTransitionDeferring = false;
        }
    };

    public StatusBarIconController(Context context, View statusBar, View keyguardStatusBar,
            PhoneStatusBar phoneStatusBar) {
        mContext = context;
        mPhoneStatusBar = phoneStatusBar;
        mNotificationColorUtil = NotificationColorUtil.getInstance(context);
        mSystemIconArea = (LinearLayout) statusBar.findViewById(R.id.system_icon_area);
        mStatusIcons = (LinearLayout) statusBar.findViewById(R.id.statusIcons);
        mSignalCluster = (SignalClusterView) statusBar.findViewById(R.id.signal_cluster);
        mNotificationIconArea = statusBar.findViewById(R.id.notification_icon_area_inner);
        mNotificationIcons = (IconMerger) statusBar.findViewById(R.id.notificationIcons);
        mMoreIcon = (ImageView) statusBar.findViewById(R.id.moreIcon);
        mNotificationIcons.setOverflowIndicator(mMoreIcon);
        mStatusIconsKeyguard = (LinearLayout) keyguardStatusBar.findViewById(R.id.statusIcons);
        mBatteryMeterView = (BatteryMeterView) statusBar.findViewById(R.id.battery);
        mClock = (TextView) statusBar.findViewById(R.id.clock);
        mLinearOutSlowIn = AnimationUtils.loadInterpolator(mContext,
                android.R.interpolator.linear_out_slow_in);
        mFastOutSlowIn = AnimationUtils.loadInterpolator(mContext,
                android.R.interpolator.fast_out_slow_in);
        mDarkModeIconColorSingleTone = context.getColor(R.color.dark_mode_icon_color_single_tone);
        mLightModeIconColorSingleTone = context.getColor(R.color.light_mode_icon_color_single_tone);
        mHandler = new Handler();
        updateResources();

        TunerService.get(mContext).addTunable(this, ICON_BLACKLIST);
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (!ICON_BLACKLIST.equals(key)) {
            return;
        }
        mIconBlacklist.clear();
        mIconBlacklist.addAll(getIconBlacklist(newValue));
        ArrayList<StatusBarIconView> views = new ArrayList<StatusBarIconView>();
        // Get all the current views.
        for (int i = 0; i < mStatusIcons.getChildCount(); i++) {
            views.add((StatusBarIconView) mStatusIcons.getChildAt(i));
        }
        // Remove all the icons.
        for (int i = views.size() - 1; i >= 0; i--) {
            removeSystemIcon(views.get(i).getSlot(), i, i);
        }
        // Add them all back
        for (int i = 0; i < views.size(); i++) {
            addSystemIcon(views.get(i).getSlot(), i, i, views.get(i).getStatusBarIcon());
        }
    };

    public void updateResources() {
        mIconSize = mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_icon_size);
        mIconHPadding = mContext.getResources().getDimensionPixelSize(
                R.dimen.status_bar_icon_padding);
        FontSizeUtils.updateFontSize(mClock, R.dimen.status_bar_clock_size);
    }

    public void addSystemIcon(String slot, int index, int viewIndex, StatusBarIcon icon) {
        boolean blocked = mIconBlacklist.contains(slot);
        StatusBarIconView view = new StatusBarIconView(mContext, slot, null, blocked);
        view.set(icon);
        mStatusIcons.addView(view, viewIndex, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, mIconSize));
        view = new StatusBarIconView(mContext, slot, null, blocked);
        view.set(icon);
        mStatusIconsKeyguard.addView(view, viewIndex, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, mIconSize));
        applyIconTint();
    }

    public void updateSystemIcon(String slot, int index, int viewIndex,
            StatusBarIcon old, StatusBarIcon icon) {
        StatusBarIconView view = (StatusBarIconView) mStatusIcons.getChildAt(viewIndex);
        view.set(icon);
        view = (StatusBarIconView) mStatusIconsKeyguard.getChildAt(viewIndex);
        view.set(icon);
        applyIconTint();
    }

    public void removeSystemIcon(String slot, int index, int viewIndex) {
        mStatusIcons.removeViewAt(viewIndex);
        mStatusIconsKeyguard.removeViewAt(viewIndex);
    }

    public void updateNotificationIcons(NotificationData notificationData) {
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                mIconSize + 2*mIconHPadding, mPhoneStatusBar.getStatusBarHeight());

        ArrayList<NotificationData.Entry> activeNotifications =
                notificationData.getActiveNotifications();
        final int N = activeNotifications.size();
        ArrayList<StatusBarIconView> toShow = new ArrayList<>(N);

        // Filter out ambient notifications and notification children.
        for (int i = 0; i < N; i++) {
            NotificationData.Entry ent = activeNotifications.get(i);
            if (notificationData.isAmbient(ent.key)
                    && !NotificationData.showNotificationEvenIfUnprovisioned(ent.notification)) {
                continue;
            }
            if (!PhoneStatusBar.isTopLevelChild(ent)) {
                continue;
            }
            toShow.add(ent.icon);
        }

        ArrayList<View> toRemove = new ArrayList<>();
        for (int i=0; i<mNotificationIcons.getChildCount(); i++) {
            View child = mNotificationIcons.getChildAt(i);
            if (!toShow.contains(child)) {
                toRemove.add(child);
            }
        }

        final int toRemoveCount = toRemove.size();
        for (int i = 0; i < toRemoveCount; i++) {
            mNotificationIcons.removeView(toRemove.get(i));
        }

        for (int i=0; i<toShow.size(); i++) {
            View v = toShow.get(i);
            if (v.getParent() == null) {
                mNotificationIcons.addView(v, i, params);
            }
        }

        // Resort notification icons
        final int childCount = mNotificationIcons.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View actual = mNotificationIcons.getChildAt(i);
            StatusBarIconView expected = toShow.get(i);
            if (actual == expected) {
                continue;
            }
            mNotificationIcons.removeView(expected);
            mNotificationIcons.addView(expected, i);
        }

        applyNotificationIconsTint();
    }

    public void hideSystemIconArea(boolean animate) {
        animateHide(mSystemIconArea, animate);
    }

    public void showSystemIconArea(boolean animate) {
        animateShow(mSystemIconArea, animate);
    }

    public void hideNotificationIconArea(boolean animate) {
        animateHide(mNotificationIconArea, animate);
    }

    public void showNotificationIconArea(boolean animate) {
        animateShow(mNotificationIconArea, animate);
    }

    public void setClockVisibility(boolean visible) {
        mClock.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    public void dump(PrintWriter pw) {
        int N = mStatusIcons.getChildCount();
        pw.println("  system icons: " + N);
        for (int i=0; i<N; i++) {
            StatusBarIconView ic = (StatusBarIconView) mStatusIcons.getChildAt(i);
            pw.println("    [" + i + "] icon=" + ic);
        }
    }

    public void dispatchDemoCommand(String command, Bundle args) {
        if (mDemoStatusIcons == null) {
            mDemoStatusIcons = new DemoStatusIcons(mStatusIcons, mIconSize);
        }
        mDemoStatusIcons.dispatchDemoCommand(command, args);
    }

    /**
     * Hides a view.
     */
    private void animateHide(final View v, boolean animate) {
        v.animate().cancel();
        if (!animate) {
            v.setAlpha(0f);
            v.setVisibility(View.INVISIBLE);
            return;
        }
        v.animate()
                .alpha(0f)
                .setDuration(160)
                .setStartDelay(0)
                .setInterpolator(PhoneStatusBar.ALPHA_OUT)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        v.setVisibility(View.INVISIBLE);
                    }
                });
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
                .setDuration(320)
                .setInterpolator(PhoneStatusBar.ALPHA_IN)
                .setStartDelay(50)

                // We need to clean up any pending end action from animateHide if we call
                // both hide and show in the same frame before the animation actually gets started.
                // cancel() doesn't really remove the end action.
                .withEndAction(null);

        // Synchronize the motion with the Keyguard fading if necessary.
        if (mPhoneStatusBar.isKeyguardFadingAway()) {
            v.animate()
                    .setDuration(mPhoneStatusBar.getKeyguardFadingAwayDuration())
                    .setInterpolator(mLinearOutSlowIn)
                    .setStartDelay(mPhoneStatusBar.getKeyguardFadingAwayDelay())
                    .start();
        }
    }

    public void setIconsDark(boolean dark) {
        if (mTransitionPending) {
            deferIconTintChange(dark ? 1.0f : 0.0f);
        } else if (mTransitionDeferring) {
            animateIconTint(dark ? 1.0f : 0.0f,
                    Math.max(0, mTransitionDeferringStartTime - SystemClock.uptimeMillis()),
                    mTransitionDeferringDuration);
        } else {
            animateIconTint(dark ? 1.0f : 0.0f, 0 /* delay */, DEFAULT_TINT_ANIMATION_DURATION);
        }
    }

    private void animateIconTint(float targetDarkIntensity, long delay,
            long duration) {
        if (mTintAnimator != null) {
            mTintAnimator.cancel();
        }
        if (mDarkIntensity == targetDarkIntensity) {
            return;
        }
        mTintAnimator = ValueAnimator.ofFloat(mDarkIntensity, targetDarkIntensity);
        mTintAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                setIconTintInternal((Float) animation.getAnimatedValue());
            }
        });
        mTintAnimator.setDuration(duration);
        mTintAnimator.setStartDelay(delay);
        mTintAnimator.setInterpolator(mFastOutSlowIn);
        mTintAnimator.start();
    }

    private void setIconTintInternal(float darkIntensity) {
        mDarkIntensity = darkIntensity;
        mIconTint = (int) ArgbEvaluator.getInstance().evaluate(darkIntensity,
                mLightModeIconColorSingleTone, mDarkModeIconColorSingleTone);
        applyIconTint();
    }

    private void deferIconTintChange(float darkIntensity) {
        if (mTintChangePending && darkIntensity == mPendingDarkIntensity) {
            return;
        }
        mTintChangePending = true;
        mPendingDarkIntensity = darkIntensity;
    }

    private void applyIconTint() {
        for (int i = 0; i < mStatusIcons.getChildCount(); i++) {
            StatusBarIconView v = (StatusBarIconView) mStatusIcons.getChildAt(i);
            v.setImageTintList(ColorStateList.valueOf(mIconTint));
        }
        mSignalCluster.setIconTint(mIconTint, mDarkIntensity);
        mMoreIcon.setImageTintList(ColorStateList.valueOf(mIconTint));
        mBatteryMeterView.setDarkIntensity(mDarkIntensity);
        mClock.setTextColor(mIconTint);
        applyNotificationIconsTint();
    }

    private void applyNotificationIconsTint() {
        for (int i = 0; i < mNotificationIcons.getChildCount(); i++) {
            StatusBarIconView v = (StatusBarIconView) mNotificationIcons.getChildAt(i);
            boolean isPreL = Boolean.TRUE.equals(v.getTag(R.id.icon_is_pre_L));
            boolean colorize = !isPreL || isGrayscale(v);
            if (colorize) {
                v.setImageTintList(ColorStateList.valueOf(mIconTint));
            }
        }
    }

    private boolean isGrayscale(StatusBarIconView v) {
        Object isGrayscale = v.getTag(R.id.icon_is_grayscale);
        if (isGrayscale != null) {
            return Boolean.TRUE.equals(isGrayscale);
        }
        boolean grayscale = mNotificationColorUtil.isGrayscaleIcon(v.getDrawable());
        v.setTag(R.id.icon_is_grayscale, grayscale);
        return grayscale;
    }

    public void appTransitionPending() {
        mTransitionPending = true;
    }

    public void appTransitionCancelled() {
        if (mTransitionPending && mTintChangePending) {
            mTintChangePending = false;
            animateIconTint(mPendingDarkIntensity, 0 /* delay */, DEFAULT_TINT_ANIMATION_DURATION);
        }
        mTransitionPending = false;
    }

    public void appTransitionStarting(long startTime, long duration) {
        if (mTransitionPending && mTintChangePending) {
            mTintChangePending = false;
            animateIconTint(mPendingDarkIntensity,
                    Math.max(0, startTime - SystemClock.uptimeMillis()),
                    duration);

        } else if (mTransitionPending) {

            // If we don't have a pending tint change yet, the change might come in the future until
            // startTime is reached.
            mTransitionDeferring = true;
            mTransitionDeferringStartTime = startTime;
            mTransitionDeferringDuration = duration;
            mHandler.removeCallbacks(mTransitionDeferringDoneRunnable);
            mHandler.postAtTime(mTransitionDeferringDoneRunnable, startTime);
        }
        mTransitionPending = false;
    }

    public static ArraySet<String> getIconBlacklist(String blackListStr) {
        ArraySet<String> ret = new ArraySet<String>();
        if (blackListStr != null) {
            String[] blacklist = blackListStr.split(",");
            for (String slot : blacklist) {
                if (!TextUtils.isEmpty(slot)) {
                    ret.add(slot);
                }
            }
        }
        return ret;
    }
}

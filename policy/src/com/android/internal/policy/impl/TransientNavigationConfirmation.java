/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.internal.policy.impl;

import android.animation.Animator;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Message;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;

import com.android.internal.R;

import java.util.Arrays;

/**
 *  Helper to manage showing/hiding a confirmation prompt when the transient navigation bar
 *  is hidden.
 */
public class TransientNavigationConfirmation {
    private static final String TAG = "TransientNavigationConfirmation";
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_SHOW_EVERY_TIME = false; // super annoying, use with caution

    private final Context mContext;
    private final H mHandler;
    private final ArraySet<String> mConfirmedPackages = new ArraySet<String>();
    private final long mShowDelayMs;
    private final long mPanicThresholdMs;

    private ClingWindowView mClingWindow;
    private String mLastPackage;
    private String mPromptPackage;
    private long mPanicTime;
    private String mPanicPackage;
    private WindowManager mWindowManager;

    public TransientNavigationConfirmation(Context context) {
        mContext = context;
        mHandler = new H();
        mShowDelayMs = getNavBarExitDuration() * 3;
        mPanicThresholdMs = context.getResources()
                .getInteger(R.integer.config_transient_navigation_confirmation_panic);
        mWindowManager = (WindowManager)
                mContext.getSystemService(Context.WINDOW_SERVICE);
    }

    private long getNavBarExitDuration() {
        Animation exit = AnimationUtils.loadAnimation(mContext, R.anim.dock_bottom_exit);
        return exit != null ? exit.getDuration() : 0;
    }

    public void loadSetting() {
        if (DEBUG) Slog.d(TAG, "loadSetting()");
        mConfirmedPackages.clear();
        String packages = null;
        try {
            packages = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                    Settings.Secure.TRANSIENT_NAV_CONFIRMATIONS,
                    UserHandle.USER_CURRENT);
            if (packages != null) {
                mConfirmedPackages.addAll(Arrays.asList(packages.split(",")));
                if (DEBUG) Slog.d(TAG, "Loaded mConfirmedPackages=" + mConfirmedPackages);
            }
        } catch (Throwable t) {
            Slog.w(TAG, "Error loading confirmations, packages=" + packages, t);
        }
    }

    private void saveSetting() {
        if (DEBUG) Slog.d(TAG, "saveSetting()");
        try {
            final String packages = TextUtils.join(",", mConfirmedPackages);
            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                    Settings.Secure.TRANSIENT_NAV_CONFIRMATIONS,
                    packages,
                    UserHandle.USER_CURRENT);
            if (DEBUG) Slog.d(TAG, "Saved packages=" + packages);
        } catch (Throwable t) {
            Slog.w(TAG, "Error saving confirmations, mConfirmedPackages=" + mConfirmedPackages, t);
        }
    }

    public void transientNavigationChanged(String pkg, boolean isNavTransient) {
        if (pkg == null) {
            return;
        }
        mHandler.removeMessages(H.SHOW);
        if (isNavTransient) {
            mLastPackage = pkg;
            if (DEBUG_SHOW_EVERY_TIME || !mConfirmedPackages.contains(pkg)) {
                mHandler.sendMessageDelayed(mHandler.obtainMessage(H.SHOW, pkg), mShowDelayMs);
            }
        } else {
            mLastPackage = null;
            mHandler.sendEmptyMessage(H.HIDE);
        }
    }

    public void onPowerKeyDown(boolean isScreenOn, long time, boolean transientNavigationAllowed) {
        if (mPanicPackage != null && !isScreenOn && (time - mPanicTime < mPanicThresholdMs)) {
            // turning the screen back on within the panic threshold
            unconfirmPackage(mPanicPackage);
        }
        if (isScreenOn && transientNavigationAllowed) {
            // turning the screen off, remember if we were hiding the transient nav
            mPanicTime = time;
            mPanicPackage = mLastPackage;
        } else {
            mPanicTime = 0;
            mPanicPackage = null;
        }
    }

    public void confirmCurrentPrompt() {
        mHandler.post(confirmAction(mPromptPackage));
    }

    private void unconfirmPackage(String pkg) {
        if (pkg != null) {
            if (DEBUG) Slog.d(TAG, "Unconfirming transient navigation for " + pkg);
            mConfirmedPackages.remove(pkg);
            saveSetting();
        }
    }

    private void handleHide() {
        if (mClingWindow != null) {
            if (DEBUG) Slog.d(TAG,
                    "Hiding transient navigation confirmation for " + mPromptPackage);
            mWindowManager.removeView(mClingWindow);
            mClingWindow = null;
        }
    }

    private class ClingWindowView extends FrameLayout {
        private static final int BGCOLOR = 0x80000000;
        private static final int OFFSET_DP = 48;

        private final Runnable mConfirm;
        private final ColorDrawable mColor = new ColorDrawable(0);
        private ValueAnimator mColorAnim;

        public ClingWindowView(Context context, Runnable confirm) {
            super(context);
            mConfirm = confirm;
            setClickable(true);
            setBackground(mColor);
        }

        @Override
        public void onAttachedToWindow() {
            super.onAttachedToWindow();

            DisplayMetrics metrics = new DisplayMetrics();
            mWindowManager.getDefaultDisplay().getMetrics(metrics);
            float density = metrics.density;

            // create the confirmation cling
            final ViewGroup clingLayout = (ViewGroup)
                    View.inflate(getContext(), R.layout.transient_navigation_cling, null);

            final Button ok = (Button) clingLayout.findViewById(R.id.ok);
            ok.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    mConfirm.run();
                }
            });
            addView(clingLayout, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
            ));

            if (ActivityManager.isHighEndGfx()) {
                final View bubble = clingLayout.findViewById(R.id.text);
                bubble.setAlpha(0f);
                bubble.setTranslationY(-OFFSET_DP*density);
                bubble.animate()
                        .alpha(1f)
                        .translationY(0)
                        .setDuration(300)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();

                ok.setAlpha(0f);
                ok.setTranslationY(-OFFSET_DP*density);
                ok.animate().alpha(1f)
                        .translationY(0)
                        .setDuration(300)
                        .setStartDelay(200)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();

                mColorAnim = ValueAnimator.ofObject(new ArgbEvaluator(), 0, BGCOLOR);
                mColorAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        final int c = (Integer) animation.getAnimatedValue();
                        mColor.setColor(c);
                    }
                });
                mColorAnim.setDuration(1000);
                mColorAnim.start();
            } else {
                mColor.setColor(BGCOLOR);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent motion) {
            Slog.v(TAG, "ClingWindowView.onTouchEvent");
            return true;
        }
    }

    private void handleShow(String pkg) {
        mPromptPackage = pkg;
        if (DEBUG) Slog.d(TAG, "Showing transient navigation confirmation for " + pkg);

        mClingWindow = new ClingWindowView(mContext, confirmAction(pkg));

        // we will be hiding the nav bar, so layout as if it's already hidden
        mClingWindow.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
              | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        // show the confirmation
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_TOAST,
                0
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                ,
                PixelFormat.TRANSLUCENT);
        lp.setTitle("TransientNavigationConfirmation");
        lp.windowAnimations = com.android.internal.R.style.Animation_RecentApplications;
        lp.gravity = Gravity.FILL;
        mWindowManager.addView(mClingWindow, lp);
    }

    private Runnable confirmAction(final String pkg) {
        return new Runnable() {
            @Override
            public void run() {
                if (pkg != null && !mConfirmedPackages.contains(pkg)) {
                    if (DEBUG) Slog.d(TAG, "Confirming transient navigation for " + pkg);
                    mConfirmedPackages.add(pkg);
                    saveSetting();
                }
                handleHide();
            }
        };
    }

    private final class H extends Handler {
        private static final int SHOW = 0;
        private static final int HIDE = 1;

        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case SHOW:
                    handleShow((String)msg.obj);
                    break;
                case HIDE:
                    handleHide();
                    break;
            }
        }
    }
}

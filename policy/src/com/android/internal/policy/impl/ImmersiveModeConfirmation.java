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

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.util.SparseBooleanArray;
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

/**
 *  Helper to manage showing/hiding a confirmation prompt when the navigation bar is hidden
 *  entering immersive mode.
 */
public class ImmersiveModeConfirmation {
    private static final String TAG = "ImmersiveModeConfirmation";
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_SHOW_EVERY_TIME = false; // super annoying, use with caution
    private static final String CONFIRMED = "confirmed";

    private final Context mContext;
    private final H mHandler;
    private final long mShowDelayMs;
    private final long mPanicThresholdMs;
    private final SparseBooleanArray mUserPanicResets = new SparseBooleanArray();

    private boolean mConfirmed;
    private ClingWindowView mClingWindow;
    private long mPanicTime;
    private WindowManager mWindowManager;
    private int mCurrentUserId;

    public ImmersiveModeConfirmation(Context context) {
        mContext = context;
        mHandler = new H();
        mShowDelayMs = getNavBarExitDuration() * 3;
        mPanicThresholdMs = context.getResources()
                .getInteger(R.integer.config_immersive_mode_confirmation_panic);
        mWindowManager = (WindowManager)
                mContext.getSystemService(Context.WINDOW_SERVICE);
    }

    private long getNavBarExitDuration() {
        Animation exit = AnimationUtils.loadAnimation(mContext, R.anim.dock_bottom_exit);
        return exit != null ? exit.getDuration() : 0;
    }

    public void loadSetting(int currentUserId) {
        mConfirmed = false;
        mCurrentUserId = currentUserId;
        if (DEBUG) Slog.d(TAG, String.format("loadSetting() mCurrentUserId=%d resetForPanic=%s",
                mCurrentUserId, mUserPanicResets.get(mCurrentUserId, false)));
        String value = null;
        try {
            value = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                    Settings.Secure.IMMERSIVE_MODE_CONFIRMATIONS,
                    UserHandle.USER_CURRENT);
            mConfirmed = CONFIRMED.equals(value);
            if (DEBUG) Slog.d(TAG, "Loaded mConfirmed=" + mConfirmed);
        } catch (Throwable t) {
            Slog.w(TAG, "Error loading confirmations, value=" + value, t);
        }
    }

    private void saveSetting() {
        if (DEBUG) Slog.d(TAG, "saveSetting()");
        try {
            final String value = mConfirmed ? CONFIRMED : null;
            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                    Settings.Secure.IMMERSIVE_MODE_CONFIRMATIONS,
                    value,
                    UserHandle.USER_CURRENT);
            if (DEBUG) Slog.d(TAG, "Saved value=" + value);
        } catch (Throwable t) {
            Slog.w(TAG, "Error saving confirmations, mConfirmed=" + mConfirmed, t);
        }
    }

    public void immersiveModeChanged(String pkg, boolean isImmersiveMode,
            boolean userSetupComplete) {
        mHandler.removeMessages(H.SHOW);
        if (isImmersiveMode) {
            final boolean disabled = PolicyControl.disableImmersiveConfirmation(pkg);
            if (DEBUG) Slog.d(TAG, String.format("immersiveModeChanged() disabled=%s mConfirmed=%s",
                    disabled, mConfirmed));
            if (!disabled && (DEBUG_SHOW_EVERY_TIME || !mConfirmed) && userSetupComplete) {
                mHandler.sendEmptyMessageDelayed(H.SHOW, mShowDelayMs);
            }
        } else {
            mHandler.sendEmptyMessage(H.HIDE);
        }
    }

    public boolean onPowerKeyDown(boolean isScreenOn, long time, boolean inImmersiveMode) {
        if (!isScreenOn && (time - mPanicTime < mPanicThresholdMs)) {
            // turning the screen back on within the panic threshold
            mHandler.sendEmptyMessage(H.PANIC);
            return mClingWindow == null;
        }
        if (isScreenOn && inImmersiveMode) {
            // turning the screen off, remember if we were in immersive mode
            mPanicTime = time;
        } else {
            mPanicTime = 0;
        }
        return false;
    }

    public void confirmCurrentPrompt() {
        if (mClingWindow != null) {
            if (DEBUG) Slog.d(TAG, "confirmCurrentPrompt()");
            mHandler.post(mConfirm);
        }
    }

    private void handlePanic() {
        if (DEBUG) Slog.d(TAG, "handlePanic()");
        if (mUserPanicResets.get(mCurrentUserId, false)) return;  // already reset for panic
        mUserPanicResets.put(mCurrentUserId, true);
        mConfirmed = false;
        saveSetting();
    }

    private void handleHide() {
        if (mClingWindow != null) {
            if (DEBUG) Slog.d(TAG, "Hiding immersive mode confirmation");
            mWindowManager.removeView(mClingWindow);
            mClingWindow = null;
        }
    }

    public WindowManager.LayoutParams getClingWindowLayoutParams() {
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_TOAST,
                0
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                ,
                PixelFormat.TRANSLUCENT);
        lp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
        lp.setTitle("ImmersiveModeConfirmation");
        lp.windowAnimations = com.android.internal.R.style.Animation_RecentApplications;
        lp.gravity = Gravity.FILL;
        return lp;
    }

    public FrameLayout.LayoutParams getBubbleLayoutParams() {
        return new FrameLayout.LayoutParams(
                mContext.getResources().getDimensionPixelSize(
                        R.dimen.immersive_mode_cling_width),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL | Gravity.TOP);
    }

    private class ClingWindowView extends FrameLayout {
        private static final int BGCOLOR = 0x80000000;
        private static final int OFFSET_DP = 48;

        private final Runnable mConfirm;
        private final ColorDrawable mColor = new ColorDrawable(0);
        private ValueAnimator mColorAnim;
        private ViewGroup mClingLayout;

        private Runnable mUpdateLayoutRunnable = new Runnable() {
            @Override
            public void run() {
                if (mClingLayout != null && mClingLayout.getParent() != null) {
                    mClingLayout.setLayoutParams(getBubbleLayoutParams());
                }
            }
        };

        private BroadcastReceiver mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getAction().equals(Intent.ACTION_CONFIGURATION_CHANGED)) {
                    post(mUpdateLayoutRunnable);
                }
            }
        };

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
            mClingLayout = (ViewGroup)
                    View.inflate(getContext(), R.layout.immersive_mode_cling, null);

            final Button ok = (Button) mClingLayout.findViewById(R.id.ok);
            ok.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    mConfirm.run();
                }
            });
            addView(mClingLayout, getBubbleLayoutParams());

            if (ActivityManager.isHighEndGfx()) {
                final View bubble = mClingLayout.findViewById(R.id.text);
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

            mContext.registerReceiver(mReceiver, new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED));
        }

        @Override
        public void onDetachedFromWindow() {
            mContext.unregisterReceiver(mReceiver);
        }

        @Override
        public boolean onTouchEvent(MotionEvent motion) {
            return true;
        }
    }

    private void handleShow() {
        if (DEBUG) Slog.d(TAG, "Showing immersive mode confirmation");

        mClingWindow = new ClingWindowView(mContext, mConfirm);

        // we will be hiding the nav bar, so layout as if it's already hidden
        mClingWindow.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
              | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        // show the confirmation
        WindowManager.LayoutParams lp = getClingWindowLayoutParams();
        mWindowManager.addView(mClingWindow, lp);
    }

    private final Runnable mConfirm = new Runnable() {
        @Override
        public void run() {
            if (DEBUG) Slog.d(TAG, "mConfirm.run()");
            if (!mConfirmed) {
                mConfirmed = true;
                saveSetting();
            }
            handleHide();
        }
    };

    private final class H extends Handler {
        private static final int SHOW = 1;
        private static final int HIDE = 2;
        private static final int PANIC = 3;

        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case SHOW:
                    handleShow();
                    break;
                case HIDE:
                    handleHide();
                    break;
                case PANIC:
                    handlePanic();
                    break;
            }
        }
    }
}

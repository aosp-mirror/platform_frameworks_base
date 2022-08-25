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

package com.android.server.wm;

import static android.app.ActivityManager.LOCK_TASK_MODE_LOCKED;
import static android.app.ActivityManager.LOCK_TASK_MODE_NONE;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.window.DisplayAreaOrganizer.FEATURE_UNDEFINED;
import static android.window.DisplayAreaOrganizer.KEY_ROOT_DISPLAY_AREA_ID;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityThread;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.view.Display;
import android.view.Gravity;
import android.view.IWindowManager;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowInsets.Type;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
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
    private static final int IMMERSIVE_MODE_CONFIRMATION_WINDOW_TYPE =
            WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL;

    private static boolean sConfirmed;

    private final Context mContext;
    private final H mHandler;
    private final long mShowDelayMs;
    private final long mPanicThresholdMs;
    private final IBinder mWindowToken = new Binder();

    private ClingWindowView mClingWindow;
    private long mPanicTime;
    /** The last {@link WindowManager} that is used to add the confirmation window. */
    @Nullable
    private WindowManager mWindowManager;
    /**
     * The WindowContext that is registered with {@link #mWindowManager} with options to specify the
     * {@link RootDisplayArea} to attach the confirmation window.
     */
    @Nullable
    private Context mWindowContext;
    // Local copy of vr mode enabled state, to avoid calling into VrManager with
    // the lock held.
    private boolean mVrModeEnabled;
    private int mLockTaskState = LOCK_TASK_MODE_NONE;

    ImmersiveModeConfirmation(Context context, Looper looper, boolean vrModeEnabled) {
        final Display display = context.getDisplay();
        final Context uiContext = ActivityThread.currentActivityThread().getSystemUiContext();
        mContext = display.getDisplayId() == DEFAULT_DISPLAY
                ? uiContext : uiContext.createDisplayContext(display);
        mHandler = new H(looper);
        mShowDelayMs = getNavBarExitDuration() * 3;
        mPanicThresholdMs = context.getResources()
                .getInteger(R.integer.config_immersive_mode_confirmation_panic);
        mVrModeEnabled = vrModeEnabled;
    }

    private long getNavBarExitDuration() {
        Animation exit = AnimationUtils.loadAnimation(mContext, R.anim.dock_bottom_exit);
        return exit != null ? exit.getDuration() : 0;
    }

    static boolean loadSetting(int currentUserId, Context context) {
        final boolean wasConfirmed = sConfirmed;
        sConfirmed = false;
        if (DEBUG) Slog.d(TAG, String.format("loadSetting() currentUserId=%d", currentUserId));
        String value = null;
        try {
            value = Settings.Secure.getStringForUser(context.getContentResolver(),
                    Settings.Secure.IMMERSIVE_MODE_CONFIRMATIONS,
                    UserHandle.USER_CURRENT);
            sConfirmed = CONFIRMED.equals(value);
            if (DEBUG) Slog.d(TAG, "Loaded sConfirmed=" + sConfirmed);
        } catch (Throwable t) {
            Slog.w(TAG, "Error loading confirmations, value=" + value, t);
        }
        return sConfirmed != wasConfirmed;
    }

    private static void saveSetting(Context context) {
        if (DEBUG) Slog.d(TAG, "saveSetting()");
        try {
            final String value = sConfirmed ? CONFIRMED : null;
            Settings.Secure.putStringForUser(context.getContentResolver(),
                    Settings.Secure.IMMERSIVE_MODE_CONFIRMATIONS,
                    value,
                    UserHandle.USER_CURRENT);
            if (DEBUG) Slog.d(TAG, "Saved value=" + value);
        } catch (Throwable t) {
            Slog.w(TAG, "Error saving confirmations, sConfirmed=" + sConfirmed, t);
        }
    }

    void release() {
        mHandler.removeMessages(H.SHOW);
        mHandler.removeMessages(H.HIDE);
    }

    boolean onSettingChanged(int currentUserId) {
        final boolean changed = loadSetting(currentUserId, mContext);
        // Remove the window if the setting changes to be confirmed.
        if (changed && sConfirmed) {
            mHandler.sendEmptyMessage(H.HIDE);
        }
        return changed;
    }

    void immersiveModeChangedLw(int rootDisplayAreaId, boolean isImmersiveMode,
            boolean userSetupComplete, boolean navBarEmpty) {
        mHandler.removeMessages(H.SHOW);
        if (isImmersiveMode) {
            if (DEBUG) Slog.d(TAG, "immersiveModeChanged() sConfirmed=" +  sConfirmed);
            if ((DEBUG_SHOW_EVERY_TIME || !sConfirmed)
                    && userSetupComplete
                    && !mVrModeEnabled
                    && !navBarEmpty
                    && !UserManager.isDeviceInDemoMode(mContext)
                    && (mLockTaskState != LOCK_TASK_MODE_LOCKED)) {
                final Message msg = mHandler.obtainMessage(H.SHOW);
                msg.arg1 = rootDisplayAreaId;
                mHandler.sendMessageDelayed(msg, mShowDelayMs);
            }
        } else {
            mHandler.sendEmptyMessage(H.HIDE);
        }
    }

    boolean onPowerKeyDown(boolean isScreenOn, long time, boolean inImmersiveMode,
            boolean navBarEmpty) {
        if (!isScreenOn && (time - mPanicTime < mPanicThresholdMs)) {
            // turning the screen back on within the panic threshold
            return mClingWindow == null;
        }
        if (isScreenOn && inImmersiveMode && !navBarEmpty) {
            // turning the screen off, remember if we were in immersive mode
            mPanicTime = time;
        } else {
            mPanicTime = 0;
        }
        return false;
    }

    void confirmCurrentPrompt() {
        if (mClingWindow != null) {
            if (DEBUG) Slog.d(TAG, "confirmCurrentPrompt()");
            mHandler.post(mConfirm);
        }
    }

    private void handleHide() {
        if (mClingWindow != null) {
            if (DEBUG) Slog.d(TAG, "Hiding immersive mode confirmation");
            // We don't care which root display area the window manager is specifying for removal.
            try {
                getWindowManager(FEATURE_UNDEFINED).removeView(mClingWindow);
            } catch (WindowManager.InvalidDisplayException e) {
                Slog.w(TAG, "Fail to hide the immersive confirmation window because of " + e);
                return;
            }
            mClingWindow = null;
        }
    }

    private WindowManager.LayoutParams getClingWindowLayoutParams() {
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                IMMERSIVE_MODE_CONFIRMATION_WINDOW_TYPE,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.setFitInsetsTypes(lp.getFitInsetsTypes() & ~Type.statusBars());
        // Trusted overlay so touches outside the touchable area are allowed to pass through
        lp.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS
                | WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;
        lp.setTitle("ImmersiveModeConfirmation");
        lp.windowAnimations = com.android.internal.R.style.Animation_ImmersiveModeConfirmation;
        lp.token = getWindowToken();
        return lp;
    }

    private FrameLayout.LayoutParams getBubbleLayoutParams() {
        return new FrameLayout.LayoutParams(
                mContext.getResources().getDimensionPixelSize(
                        R.dimen.immersive_mode_cling_width),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL | Gravity.TOP);
    }

    /**
     * @return the window token that's used by all ImmersiveModeConfirmation windows.
     */
    IBinder getWindowToken() {
        return mWindowToken;
    }

    private class ClingWindowView extends FrameLayout {
        private static final int BGCOLOR = 0x80000000;
        private static final int OFFSET_DP = 96;
        private static final int ANIMATION_DURATION = 250;

        private final Runnable mConfirm;
        private final ColorDrawable mColor = new ColorDrawable(0);
        private final Interpolator mInterpolator;
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

        private ViewTreeObserver.OnComputeInternalInsetsListener mInsetsListener =
                new ViewTreeObserver.OnComputeInternalInsetsListener() {
                    private final int[] mTmpInt2 = new int[2];

                    @Override
                    public void onComputeInternalInsets(
                            ViewTreeObserver.InternalInsetsInfo inoutInfo) {
                        // Set touchable region to cover the cling layout.
                        mClingLayout.getLocationInWindow(mTmpInt2);
                        inoutInfo.setTouchableInsets(
                                ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
                        inoutInfo.touchableRegion.set(
                                mTmpInt2[0],
                                mTmpInt2[1],
                                mTmpInt2[0] + mClingLayout.getWidth(),
                                mTmpInt2[1] + mClingLayout.getHeight());
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

        ClingWindowView(Context context, Runnable confirm) {
            super(context);
            mConfirm = confirm;
            setBackground(mColor);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            mInterpolator = AnimationUtils
                    .loadInterpolator(mContext, android.R.interpolator.linear_out_slow_in);
        }

        @Override
        public void onAttachedToWindow() {
            super.onAttachedToWindow();

            DisplayMetrics metrics = new DisplayMetrics();
            mContext.getDisplay().getMetrics(metrics);
            float density = metrics.density;

            getViewTreeObserver().addOnComputeInternalInsetsListener(mInsetsListener);

            // create the confirmation cling
            mClingLayout = (ViewGroup)
                    View.inflate(getContext(), R.layout.immersive_mode_cling, null);

            final Button ok = mClingLayout.findViewById(R.id.ok);
            ok.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    mConfirm.run();
                }
            });
            addView(mClingLayout, getBubbleLayoutParams());

            if (ActivityManager.isHighEndGfx()) {
                final View cling = mClingLayout;
                cling.setAlpha(0f);
                cling.setTranslationY(-OFFSET_DP * density);

                postOnAnimation(new Runnable() {
                    @Override
                    public void run() {
                        cling.animate()
                                .alpha(1f)
                                .translationY(0)
                                .setDuration(ANIMATION_DURATION)
                                .setInterpolator(mInterpolator)
                                .withLayer()
                                .start();

                        mColorAnim = ValueAnimator.ofObject(new ArgbEvaluator(), 0, BGCOLOR);
                        mColorAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                            @Override
                            public void onAnimationUpdate(ValueAnimator animation) {
                                final int c = (Integer) animation.getAnimatedValue();
                                mColor.setColor(c);
                            }
                        });
                        mColorAnim.setDuration(ANIMATION_DURATION);
                        mColorAnim.setInterpolator(mInterpolator);
                        mColorAnim.start();
                    }
                });
            } else {
                mColor.setColor(BGCOLOR);
            }

            mContext.registerReceiver(mReceiver,
                    new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED));
        }

        @Override
        public void onDetachedFromWindow() {
            mContext.unregisterReceiver(mReceiver);
        }

        @Override
        public boolean onTouchEvent(MotionEvent motion) {
            return true;
        }

        @Override
        public WindowInsets onApplyWindowInsets(WindowInsets insets) {
            // we will be hiding the nav bar, so layout as if it's already hidden
            return new WindowInsets.Builder(insets).setInsets(
                    Type.systemBars(), Insets.NONE).build();
        }
    }

    /**
     * DO HOLD THE WINDOW MANAGER LOCK WHEN CALLING THIS METHOD
     * The reason why we add this method is to avoid the deadlock of WMG->WMS and WMS->WMG
     * when ImmersiveModeConfirmation object is created.
     *
     * @return the WindowManager specifying with the {@code rootDisplayAreaId} to attach the
     *         confirmation window.
     */
    private WindowManager getWindowManager(int rootDisplayAreaId) {
        if (mWindowManager == null || mWindowContext == null) {
            // Create window context to specify the RootDisplayArea
            final Bundle options = getOptionsForWindowContext(rootDisplayAreaId);
            mWindowContext = mContext.createWindowContext(
                    IMMERSIVE_MODE_CONFIRMATION_WINDOW_TYPE, options);
            mWindowManager = mWindowContext.getSystemService(WindowManager.class);
            return mWindowManager;
        }

        // Update the window context and window manager to specify the RootDisplayArea
        final Bundle options = getOptionsForWindowContext(rootDisplayAreaId);
        final IWindowManager wms = WindowManagerGlobal.getWindowManagerService();
        try {
            wms.attachWindowContextToDisplayArea(mWindowContext.getWindowContextToken(),
                    IMMERSIVE_MODE_CONFIRMATION_WINDOW_TYPE, mContext.getDisplayId(), options);
        }  catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }

        return mWindowManager;
    }

    /**
     * Returns options that specify the {@link RootDisplayArea} to attach the confirmation window.
     *         {@code null} if the {@code rootDisplayAreaId} is {@link FEATURE_UNDEFINED}.
     */
    @Nullable
    private Bundle getOptionsForWindowContext(int rootDisplayAreaId) {
        // In case we don't care which root display area the window manager is specifying.
        if (rootDisplayAreaId == FEATURE_UNDEFINED) {
            return null;
        }

        final Bundle options = new Bundle();
        options.putInt(KEY_ROOT_DISPLAY_AREA_ID, rootDisplayAreaId);
        return options;
    }

    private void handleShow(int rootDisplayAreaId) {
        if (DEBUG) Slog.d(TAG, "Showing immersive mode confirmation");

        mClingWindow = new ClingWindowView(mContext, mConfirm);

        // show the confirmation
        WindowManager.LayoutParams lp = getClingWindowLayoutParams();
        try {
            getWindowManager(rootDisplayAreaId).addView(mClingWindow, lp);
        } catch (WindowManager.InvalidDisplayException e) {
            Slog.w(TAG, "Fail to show the immersive confirmation window because of " + e);
        }
    }

    private final Runnable mConfirm = new Runnable() {
        @Override
        public void run() {
            if (DEBUG) Slog.d(TAG, "mConfirm.run()");
            if (!sConfirmed) {
                sConfirmed = true;
                saveSetting(mContext);
            }
            handleHide();
        }
    };

    private final class H extends Handler {
        private static final int SHOW = 1;
        private static final int HIDE = 2;

        H(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case SHOW:
                    handleShow(msg.arg1);
                    break;
                case HIDE:
                    handleHide();
                    break;
            }
        }
    }

    void onVrStateChangedLw(boolean enabled) {
        mVrModeEnabled = enabled;
        if (mVrModeEnabled) {
            mHandler.removeMessages(H.SHOW);
            mHandler.sendEmptyMessage(H.HIDE);
        }
    }

    void onLockTaskModeChangedLw(int lockTaskState) {
        mLockTaskState = lockTaskState;
    }
}

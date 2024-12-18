/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar;

import static android.app.ActivityManager.LOCK_TASK_MODE_LOCKED;
import static android.app.ActivityManager.LOCK_TASK_MODE_NONE;
import static android.app.StatusBarManager.DISABLE_BACK;
import static android.app.StatusBarManager.DISABLE_HOME;
import static android.app.StatusBarManager.DISABLE_RECENT;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.ViewRootImpl.CLIENT_IMMERSIVE_CONFIRMATION;
import static android.view.ViewRootImpl.CLIENT_TRANSIENT;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
import static android.window.DisplayAreaOrganizer.FEATURE_UNDEFINED;
import static android.window.DisplayAreaOrganizer.KEY_ROOT_DISPLAY_AREA_ID;

import static com.android.systemui.Flags.enableViewCaptureTracing;
import static com.android.systemui.util.ConvenienceExtensionsKt.toKotlinLazy;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.service.vr.IVrManager;
import android.service.vr.IVrStateCallbacks;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowInsets.Type;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

import com.android.app.viewcapture.ViewCapture;
import com.android.app.viewcapture.ViewCaptureAwareWindowManager;
import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.res.R;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.system.TaskStackChangeListeners;
import com.android.systemui.util.settings.SecureSettings;

import kotlin.Lazy;

import javax.inject.Inject;

/**
 *  Helper to manage showing/hiding a confirmation prompt when the navigation bar is hidden
 *  entering immersive mode.
 */
public class ImmersiveModeConfirmation implements CoreStartable, CommandQueue.Callbacks,
        TaskStackChangeListener {
    private static final String TAG = "ImmersiveModeConfirm";
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_SHOW_EVERY_TIME = false; // super annoying, use with caution
    private static final String CONFIRMED = "confirmed";
    private static final int IMMERSIVE_MODE_CONFIRMATION_WINDOW_TYPE =
            WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL;

    private static boolean sConfirmed;
    private final SecureSettings mSecureSettings;

    private Context mDisplayContext;
    private final Context mSysUiContext;
    private final Handler mHandler = new H(Looper.getMainLooper());
    private final Handler mBackgroundHandler;
    private long mShowDelayMs = 0L;
    private final IBinder mWindowToken = new Binder();
    private final CommandQueue mCommandQueue;

    private ClingWindowView mClingWindow;
    /** The wrapper on the last {@link WindowManager} used to add the confirmation window. */
    @Nullable
    private ViewCaptureAwareWindowManager mViewCaptureAwareWindowManager;
    /**
     * The WindowContext that is registered with {@link #mViewCaptureAwareWindowManager} with
     * options to specify the {@link RootDisplayArea} to attach the confirmation window.
     */
    @Nullable
    private Context mWindowContext;
    /**
     * The root display area feature id that the {@link #mWindowContext} is attaching to.
     */
    private int mWindowContextRootDisplayAreaId = FEATURE_UNDEFINED;
    // Local copy of vr mode enabled state, to avoid calling into VrManager with
    // the lock held.
    private boolean mVrModeEnabled = false;
    private boolean mCanSystemBarsBeShownByUser = true;
    private int mLockTaskState = LOCK_TASK_MODE_NONE;
    private boolean mNavBarEmpty;

    private ContentObserver mContentObserver;

    private Lazy<ViewCapture> mLazyViewCapture;

    @Inject
    public ImmersiveModeConfirmation(Context context, CommandQueue commandQueue,
                                     SecureSettings secureSettings,
                                     dagger.Lazy<ViewCapture> daggerLazyViewCapture,
                                     @Background Handler backgroundHandler) {
        mSysUiContext = context;
        final Display display = mSysUiContext.getDisplay();
        mDisplayContext = display.getDisplayId() == DEFAULT_DISPLAY
                ? mSysUiContext : mSysUiContext.createDisplayContext(display);
        mCommandQueue = commandQueue;
        mSecureSettings = secureSettings;
        mLazyViewCapture = toKotlinLazy(daggerLazyViewCapture);
        mBackgroundHandler = backgroundHandler;
    }

    boolean loadSetting(int currentUserId) {
        final boolean wasConfirmed = sConfirmed;
        sConfirmed = false;
        if (DEBUG) Log.d(TAG, String.format("loadSetting() currentUserId=%d", currentUserId));
        String value = null;
        try {
            value = mSecureSettings.getStringForUser(Settings.Secure.IMMERSIVE_MODE_CONFIRMATIONS,
                    UserHandle.USER_CURRENT);
            sConfirmed = CONFIRMED.equals(value);
            if (DEBUG) Log.d(TAG, "Loaded sConfirmed=" + sConfirmed);
        } catch (Throwable t) {
            Log.w(TAG, "Error loading confirmations, value=" + value, t);
        }
        return sConfirmed != wasConfirmed;
    }

    private static void saveSetting(Context context) {
        if (DEBUG) Log.d(TAG, "saveSetting()");
        try {
            final String value = sConfirmed ? CONFIRMED : null;
            Settings.Secure.putStringForUser(context.getContentResolver(),
                    Settings.Secure.IMMERSIVE_MODE_CONFIRMATIONS,
                    value,
                    UserHandle.USER_CURRENT);
            if (DEBUG) Log.d(TAG, "Saved value=" + value);
        } catch (Throwable t) {
            Log.w(TAG, "Error saving confirmations, sConfirmed=" + sConfirmed, t);
        }
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        if (displayId != mSysUiContext.getDisplayId()) {
            return;
        }
        mHandler.removeMessages(H.SHOW);
        mHandler.removeMessages(H.HIDE);
        IVrManager vrManager = IVrManager.Stub.asInterface(
                ServiceManager.getService(Context.VR_SERVICE));
        if (vrManager != null) {
            try {
                vrManager.unregisterListener(mVrStateCallbacks);
            } catch (RemoteException ex) {
            }
        }
        mCommandQueue.removeCallback(this);
    }

    private void onSettingChanged(int currentUserId) {
        final boolean changed = loadSetting(currentUserId);
        // Remove the window if the setting changes to be confirmed.
        if (changed && sConfirmed) {
            mHandler.sendEmptyMessage(H.HIDE);
        }
    }

    @Override
    public void immersiveModeChanged(int rootDisplayAreaId, boolean isImmersiveMode) {
        mHandler.removeMessages(H.SHOW);
        if (isImmersiveMode) {
            if (DEBUG) Log.d(TAG, "immersiveModeChanged() sConfirmed=" +  sConfirmed);
            boolean userSetupComplete = (mSecureSettings.getIntForUser(
                    Settings.Secure.USER_SETUP_COMPLETE, 0, UserHandle.USER_CURRENT) != 0);

            if ((DEBUG_SHOW_EVERY_TIME || !sConfirmed)
                    && userSetupComplete
                    && !mVrModeEnabled
                    && mCanSystemBarsBeShownByUser
                    && !mNavBarEmpty
                    && !UserManager.isDeviceInDemoMode(mDisplayContext)
                    && (mLockTaskState != LOCK_TASK_MODE_LOCKED)) {
                final Message msg = mHandler.obtainMessage(
                        H.SHOW);
                msg.arg1 = rootDisplayAreaId;
                mHandler.sendMessageDelayed(msg, mShowDelayMs);
            }
        } else {
            mHandler.sendEmptyMessage(H.HIDE);
        }
    }

    @Override
    public void disable(int displayId, int disableFlag, int disableFlag2, boolean animate) {
        if (mSysUiContext.getDisplayId() != displayId) {
            return;
        }
        final int disableNavigationBar = (DISABLE_HOME | DISABLE_BACK | DISABLE_RECENT);
        mNavBarEmpty = (disableFlag & disableNavigationBar) == disableNavigationBar;
    }

    @Override
    public void confirmImmersivePrompt() {
        if (mClingWindow != null) {
            if (DEBUG) Log.d(TAG, "confirmImmersivePrompt()");
            mHandler.post(mConfirm);
        }
    }

    private void handleHide() {
        if (mClingWindow != null) {
            if (DEBUG) Log.d(TAG, "Hiding immersive mode confirmation");
            if (mViewCaptureAwareWindowManager != null) {
                try {
                    mViewCaptureAwareWindowManager.removeView(mClingWindow);
                } catch (WindowManager.InvalidDisplayException e) {
                    Log.w(TAG, "Fail to hide the immersive confirmation window because of "
                            + e);
                }
                mViewCaptureAwareWindowManager = null;
                mWindowContext = null;
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
        lp.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        // Trusted overlay so touches outside the touchable area are allowed to pass through
        lp.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS
                | WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY
                | WindowManager.LayoutParams.PRIVATE_FLAG_IMMERSIVE_CONFIRMATION_WINDOW;
        lp.setTitle("ImmersiveModeConfirmation");
        lp.windowAnimations = com.android.internal.R.style.Animation_ImmersiveModeConfirmation;
        lp.token = getWindowToken();
        return lp;
    }

    private FrameLayout.LayoutParams getBubbleLayoutParams() {
        return new FrameLayout.LayoutParams(
                getClingWindowWidth(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL | Gravity.TOP);
    }

    /**
     * Returns the width of the cling window.
     */
    private int getClingWindowWidth() {
        return mSysUiContext.getResources().getDimensionPixelSize(
                R.dimen.immersive_mode_cling_width);
    }

    /**
     * @return the window token that's used by all ImmersiveModeConfirmation windows.
     */
    IBinder getWindowToken() {
        return mWindowToken;
    }

    @Override
    public void start() {
        if (CLIENT_TRANSIENT || CLIENT_IMMERSIVE_CONFIRMATION) {
            mCommandQueue.addCallback(this);

            final Resources r = mSysUiContext.getResources();
            mShowDelayMs = r.getInteger(R.integer.dock_enter_exit_duration) * 3L;
            mCanSystemBarsBeShownByUser = !r.getBoolean(
                    R.bool.config_remoteInsetsControllerControlsSystemBars) || r.getBoolean(
                    R.bool.config_remoteInsetsControllerSystemBarsCanBeShownByUserAction);
            IVrManager vrManager = IVrManager.Stub.asInterface(
                    ServiceManager.getService(Context.VR_SERVICE));
            if (vrManager != null) {
                try {
                    mVrModeEnabled = vrManager.getVrModeState();
                    vrManager.registerListener(mVrStateCallbacks);
                    mVrStateCallbacks.onVrStateChanged(mVrModeEnabled);
                } catch (RemoteException e) {
                    // Ignore, we cannot do anything if we failed to access vr manager.
                }
            }
            TaskStackChangeListeners.getInstance().registerTaskStackListener(this);
            mContentObserver = new ContentObserver(mBackgroundHandler) {
                @Override
                public void onChange(boolean selfChange) {
                    onSettingChanged(mSysUiContext.getUserId());
                }
            };

            // Register to listen for changes in Settings.Secure settings.
            mSecureSettings.registerContentObserverForUserSync(
                    Settings.Secure.IMMERSIVE_MODE_CONFIRMATIONS, mContentObserver,
                    UserHandle.USER_CURRENT);
            mSecureSettings.registerContentObserverForUserSync(
                    Settings.Secure.USER_SETUP_COMPLETE, mContentObserver,
                    UserHandle.USER_CURRENT);
            mBackgroundHandler.post(() -> {
                loadSetting(UserHandle.USER_CURRENT);
            });
        }
    }

    private final IVrStateCallbacks mVrStateCallbacks = new IVrStateCallbacks.Stub() {
        @Override
        public void onVrStateChanged(boolean enabled) {
            mVrModeEnabled = enabled;
            if (mVrModeEnabled) {
                mHandler.removeMessages(H.SHOW);
                mHandler.sendEmptyMessage(H.HIDE);
            }
        }
    };

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
                    View.inflate(mSysUiContext, R.layout.immersive_mode_cling, null);

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
            // If the top display cutout overlaps with the full-width (windowWidth=-1)/centered
            // dialog, then adjust the dialog contents by the cutout
            final int width = getWidth();
            final int windowWidth = getClingWindowWidth();
            final Rect topDisplayCutout = insets.getDisplayCutout() != null
                    ? insets.getDisplayCutout().getBoundingRectTop()
                    : new Rect();
            final boolean intersectsTopCutout = topDisplayCutout.intersects(
                    width - (windowWidth / 2), 0,
                    width + (windowWidth / 2), topDisplayCutout.bottom);
            if (windowWidth < 0 || (width > 0 && intersectsTopCutout)) {
                final View iconView = findViewById(R.id.immersive_cling_icon);
                RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams)
                        iconView.getLayoutParams();
                lp.topMargin = topDisplayCutout.bottom;
                iconView.setLayoutParams(lp);
            }
            // we will be hiding the nav bar, so layout as if it's already hidden
            return new WindowInsets.Builder(insets).setInsets(
                    Type.systemBars(), Insets.NONE).build();
        }
    }

    /**
     * To get window manager for the display.
     *
     * @return the WindowManager specifying with the {@code rootDisplayAreaId} to attach the
     *         confirmation window.
     */
    @NonNull
    private ViewCaptureAwareWindowManager createWindowManager(int rootDisplayAreaId) {
        if (mViewCaptureAwareWindowManager != null) {
            throw new IllegalStateException(
                    "Must not create a new WindowManager while there is an existing one");
        }
        // Create window context to specify the RootDisplayArea
        final Bundle options = getOptionsForWindowContext(rootDisplayAreaId);
        mWindowContextRootDisplayAreaId = rootDisplayAreaId;
        mWindowContext = mDisplayContext.createWindowContext(
                IMMERSIVE_MODE_CONFIRMATION_WINDOW_TYPE, options);
        WindowManager wm = mWindowContext.getSystemService(WindowManager.class);
        mViewCaptureAwareWindowManager = new ViewCaptureAwareWindowManager(wm, mLazyViewCapture,
                enableViewCaptureTracing());
        return mViewCaptureAwareWindowManager;
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
        if (mClingWindow != null) {
            if (rootDisplayAreaId == mWindowContextRootDisplayAreaId) {
                if (DEBUG) Log.d(TAG, "Immersive mode confirmation has already been shown");
                return;
            } else {
                // Hide the existing confirmation before show a new one in the new root.
                if (DEBUG) Log.d(TAG, "Immersive mode confirmation was shown in a different root");
                handleHide();
            }
        }
        if (DEBUG) Log.d(TAG, "Showing immersive mode confirmation");
        mClingWindow = new ClingWindowView(mDisplayContext, mConfirm);
        // show the confirmation
        final WindowManager.LayoutParams lp = getClingWindowLayoutParams();
        try {
            createWindowManager(rootDisplayAreaId).addView(mClingWindow, lp);
        } catch (WindowManager.InvalidDisplayException e) {
            Log.w(TAG, "Fail to show the immersive confirmation window because of " + e);
        }
    }

    private final Runnable mConfirm = new Runnable() {
        @Override
        public void run() {
            if (DEBUG) Log.d(TAG, "mConfirm.run()");
            if (!sConfirmed) {
                sConfirmed = true;
                saveSetting(mDisplayContext);
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
            if (!CLIENT_TRANSIENT && !CLIENT_IMMERSIVE_CONFIRMATION) {
                return;
            }
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

    @Override
    public void onLockTaskModeChanged(int lockTaskState) {
        mLockTaskState = lockTaskState;
    }
}

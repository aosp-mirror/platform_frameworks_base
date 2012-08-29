/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.internal.policy.impl.keyguard;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.os.SystemProperties;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewManager;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.android.internal.widget.LockPatternUtils;
import com.android.internal.R;

/**
 * Manages creating, showing, hiding and resetting the keyguard.  Calls back
 * via {@link com.android.internal.policy.impl.KeyguardViewCallback} to poke
 * the wake lock and report that the keyguard is done, which is in turn,
 * reported to this class by the current {@link KeyguardViewBase}.
 */
public class KeyguardViewManager {
    private final static boolean DEBUG = false;
    private static String TAG = "KeyguardViewManager";

    private final Context mContext;
    private final ViewManager mViewManager;
    private final KeyguardViewMediator.ViewMediatorCallback mViewMediatorCallback;

    private WindowManager.LayoutParams mWindowLayoutParams;
    private boolean mNeedsInput = false;

    private FrameLayout mKeyguardHost;
    private KeyguardHostView mKeyguardView;

    private boolean mScreenOn = false;
    private LockPatternUtils mLockPatternUtils;

    public interface ShowListener {
        void onShown(IBinder windowToken);
    };

    /**
     * @param context Used to create views.
     * @param viewManager Keyguard will be attached to this.
     * @param callback Used to notify of changes.
     * @param lockPatternUtils
     */
    public KeyguardViewManager(Context context, ViewManager viewManager,
            KeyguardViewMediator.ViewMediatorCallback callback,
            LockPatternUtils lockPatternUtils) {
        mContext = context;
        mViewManager = viewManager;
        mViewMediatorCallback = callback;
        mLockPatternUtils = lockPatternUtils;
    }

    /**
     * Show the keyguard.  Will handle creating and attaching to the view manager
     * lazily.
     */
    public synchronized void show() {
        if (DEBUG) Log.d(TAG, "show(); mKeyguardView==" + mKeyguardView);

        boolean enableScreenRotation = shouldEnableScreenRotation();

        maybeCreateKeyguardLocked(enableScreenRotation);
        maybeEnableScreenRotation(enableScreenRotation);

        // Disable aspects of the system/status/navigation bars that are not appropriate or
        // useful for the lockscreen but can be re-shown by dialogs or SHOW_WHEN_LOCKED activities.
        // Other disabled bits are handled by the KeyguardViewMediator talking directly to the
        // status bar service.
        int visFlags = View.STATUS_BAR_DISABLE_BACK | View.STATUS_BAR_DISABLE_HOME;
        if (DEBUG) Log.v(TAG, "KGVM: Set visibility on " + mKeyguardHost + " to " + visFlags);
        mKeyguardHost.setSystemUiVisibility(visFlags);

        mViewManager.updateViewLayout(mKeyguardHost, mWindowLayoutParams);
        mKeyguardHost.setVisibility(View.VISIBLE);
        mKeyguardView.show();
        mKeyguardView.requestFocus();
    }

    private boolean shouldEnableScreenRotation() {
        Resources res = mContext.getResources();
        return SystemProperties.getBoolean("lockscreen.rot_override",false)
                || res.getBoolean(com.android.internal.R.bool.config_enableLockScreenRotation);
    }

    class ViewManagerHost extends FrameLayout {
        public ViewManagerHost(Context context) {
            super(context);
        }

        @Override
        protected void onConfigurationChanged(Configuration newConfig) {
            super.onConfigurationChanged(newConfig);
            maybeCreateKeyguardLocked(shouldEnableScreenRotation());
        }
    }

    private void maybeCreateKeyguardLocked(boolean enableScreenRotation) {
        final boolean isActivity = (mContext instanceof Activity); // for test activity

        if (mKeyguardHost == null) {
            if (DEBUG) Log.d(TAG, "keyguard host is null, creating it...");

            mKeyguardHost = new ViewManagerHost(mContext);

            int flags = WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN
                    | WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER
                    | WindowManager.LayoutParams.FLAG_SLIPPERY;

            if (!mNeedsInput) {
                flags |= WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
            }
            if (ActivityManager.isHighEndGfx()) {
                flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
            }

            final int stretch = ViewGroup.LayoutParams.MATCH_PARENT;
            final int type = isActivity ? WindowManager.LayoutParams.TYPE_APPLICATION
                    : WindowManager.LayoutParams.TYPE_KEYGUARD;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    stretch, stretch, type, flags, PixelFormat.TRANSLUCENT);
            lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
            lp.windowAnimations = com.android.internal.R.style.Animation_LockScreen;
            if (ActivityManager.isHighEndGfx()) {
                lp.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
                lp.privateFlags |=
                        WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_HARDWARE_ACCELERATED;
            }
            lp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_SET_NEEDS_MENU_KEY;
            lp.setTitle(isActivity ? "KeyguardMock" : "Keyguard");
            mWindowLayoutParams = lp;
            mViewManager.addView(mKeyguardHost, lp);
        }
        inflateKeyguardView();
        mViewManager.updateViewLayout(mKeyguardHost, mWindowLayoutParams);
    }

    private void inflateKeyguardView() {
        if (mKeyguardView != null) {
            mKeyguardHost.removeView(mKeyguardView);
        }
        final LayoutInflater inflater = LayoutInflater.from(mContext);
        View view = inflater.inflate(R.layout.keyguard_host_view, mKeyguardHost, true);
        mKeyguardView = (KeyguardHostView) view.findViewById(R.id.keyguard_host_view);
        mKeyguardView.setLockPatternUtils(mLockPatternUtils);
        mKeyguardView.setViewMediatorCallback(mViewMediatorCallback);

        if (mScreenOn) {
            mKeyguardView.show();
        }
    }

    private void maybeEnableScreenRotation(boolean enableScreenRotation) {
        // TODO: move this outside
        if (enableScreenRotation) {
            if (DEBUG) Log.d(TAG, "Rotation sensor for lock screen On!");
            mWindowLayoutParams.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_USER;
        } else {
            if (DEBUG) Log.d(TAG, "Rotation sensor for lock screen Off!");
            mWindowLayoutParams.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_NOSENSOR;
        }
        mViewManager.updateViewLayout(mKeyguardHost, mWindowLayoutParams);
    }

    public void setNeedsInput(boolean needsInput) {
        mNeedsInput = needsInput;
        if (mWindowLayoutParams != null) {
            if (needsInput) {
                mWindowLayoutParams.flags &=
                    ~WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
            } else {
                mWindowLayoutParams.flags |=
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
            }

            try {
                mViewManager.updateViewLayout(mKeyguardHost, mWindowLayoutParams);
            } catch (java.lang.IllegalArgumentException e) {
                // TODO: Ensure this method isn't called on views that are changing...
                Log.w(TAG,"Can't update input method on " + mKeyguardHost + " window not attached");
            }
        }
    }

    /**
     * Reset the state of the view.
     */
    public synchronized void reset() {
        if (DEBUG) Log.d(TAG, "reset()");
        if (mKeyguardView != null) {
            mKeyguardView.reset();
        }
    }

    public synchronized void onScreenTurnedOff() {
        if (DEBUG) Log.d(TAG, "onScreenTurnedOff()");
        mScreenOn = false;
        if (mKeyguardView != null) {
            mKeyguardView.onScreenTurnedOff();
        }
    }

    public synchronized void onScreenTurnedOn(
            final KeyguardViewManager.ShowListener showListener) {
        if (DEBUG) Log.d(TAG, "onScreenTurnedOn()");
        mScreenOn = true;
        if (mKeyguardView != null) {
            mKeyguardView.onScreenTurnedOn();

            // Caller should wait for this window to be shown before turning
            // on the screen.
            if (mKeyguardHost.getVisibility() == View.VISIBLE) {
                // Keyguard may be in the process of being shown, but not yet
                // updated with the window manager...  give it a chance to do so.
                mKeyguardHost.post(new Runnable() {
                    public void run() {
                        if (mKeyguardHost.getVisibility() == View.VISIBLE) {
                            showListener.onShown(mKeyguardHost.getWindowToken());
                        } else {
                            showListener.onShown(null);
                        }
                    }
                });
            } else {
                showListener.onShown(null);
            }
        } else {
            showListener.onShown(null);
        }
    }

    public synchronized void verifyUnlock() {
        if (DEBUG) Log.d(TAG, "verifyUnlock()");
        show();
        mKeyguardView.verifyUnlock();
    }

    /**
     * A key has woken the device.  We use this to potentially adjust the state
     * of the lock screen based on the key.
     *
     * The 'Tq' suffix is per the documentation in {@link android.view.WindowManagerPolicy}.
     * Be sure not to take any action that takes a long time; any significant
     * action should be posted to a handler.
     *
     * @param keyCode The wake key.  May be {@link KeyEvent#KEYCODE_UNKNOWN} if waking
     * for a reason other than a key press.
     */
    public boolean wakeWhenReadyTq(int keyCode) {
        if (DEBUG) Log.d(TAG, "wakeWhenReady(" + keyCode + ")");
        if (mKeyguardView != null) {
            mKeyguardView.wakeWhenReadyTq(keyCode);
            return true;
        } else {
            Log.w(TAG, "mKeyguardView is null in wakeWhenReadyTq");
            return false;
        }
    }

    /**
     * Hides the keyguard view
     */
    public synchronized void hide() {
        if (DEBUG) Log.d(TAG, "hide()");

        if (mKeyguardHost != null) {
            mKeyguardHost.setVisibility(View.GONE);
            // Don't do this right away, so we can let the view continue to animate
            // as it goes away.
            if (mKeyguardView != null) {
                final KeyguardViewBase lastView = mKeyguardView;
                mKeyguardView = null;
                mKeyguardHost.postDelayed(new Runnable() {
                    public void run() {
                        synchronized (KeyguardViewManager.this) {
                            lastView.cleanUp();
                            mKeyguardHost.removeView(lastView);
                        }
                    }
                }, 500);
            }
        }
    }

    /**
     * @return Whether the keyguard is showing
     */
    public synchronized boolean isShowing() {
        return (mKeyguardHost != null && mKeyguardHost.getVisibility() == View.VISIBLE);
    }
}

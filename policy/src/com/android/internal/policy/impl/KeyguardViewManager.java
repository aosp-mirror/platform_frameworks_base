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

package com.android.internal.policy.impl;

import com.android.internal.R;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.PixelFormat;
import android.graphics.Canvas;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewManager;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * Manages creating, showing, hiding and resetting the keyguard.  Calls back
 * via {@link com.android.internal.policy.impl.KeyguardViewCallback} to poke
 * the wake lock and report that the keyguard is done, which is in turn,
 * reported to this class by the current {@link KeyguardViewBase}.
 */
public class KeyguardViewManager implements KeyguardWindowController {
    private final static boolean DEBUG = false;
    private static String TAG = "KeyguardViewManager";

    private final Context mContext;
    private final ViewManager mViewManager;
    private final KeyguardViewCallback mCallback;
    private final KeyguardViewProperties mKeyguardViewProperties;

    private final KeyguardUpdateMonitor mUpdateMonitor;

    private WindowManager.LayoutParams mWindowLayoutParams;
    private boolean mNeedsInput = false;

    private FrameLayout mKeyguardHost;
    private KeyguardViewBase mKeyguardView;

    private boolean mScreenOn = false;

    /**
     * @param context Used to create views.
     * @param viewManager Keyguard will be attached to this.
     * @param callback Used to notify of changes.
     */
    public KeyguardViewManager(Context context, ViewManager viewManager,
            KeyguardViewCallback callback, KeyguardViewProperties keyguardViewProperties, KeyguardUpdateMonitor updateMonitor) {
        mContext = context;
        mViewManager = viewManager;
        mCallback = callback;
        mKeyguardViewProperties = keyguardViewProperties;

        mUpdateMonitor = updateMonitor;
    }

    /**
     * Helper class to host the keyguard view.
     */
    private static class KeyguardViewHost extends FrameLayout {
        private final KeyguardViewCallback mCallback;

        private KeyguardViewHost(Context context, KeyguardViewCallback callback) {
            super(context);
            mCallback = callback;
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            mCallback.keyguardDoneDrawing();
        }
    }

    /**
     * Show the keyguard.  Will handle creating and attaching to the view manager
     * lazily.
     */
    public synchronized void show() {
        if (DEBUG) Log.d(TAG, "show(); mKeyguardView==" + mKeyguardView);

        if (mKeyguardHost == null) {
            if (DEBUG) Log.d(TAG, "keyguard host is null, creating it...");

            mKeyguardHost = new KeyguardViewHost(mContext, mCallback);

            final int stretch = ViewGroup.LayoutParams.MATCH_PARENT;
            int flags = WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN
                    | WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER
                    | WindowManager.LayoutParams.FLAG_KEEP_SURFACE_WHILE_ANIMATING
                    /*| WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR*/ ;
            if (!mNeedsInput) {
                flags |= WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
            }
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    stretch, stretch, WindowManager.LayoutParams.TYPE_KEYGUARD,
                    flags, PixelFormat.TRANSLUCENT);
            lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;
            lp.windowAnimations = com.android.internal.R.style.Animation_LockScreen;
            lp.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_NOSENSOR;
            lp.setTitle("Keyguard");
            mWindowLayoutParams = lp;

            mViewManager.addView(mKeyguardHost, lp);
        }

        if (mKeyguardView == null) {
            if (DEBUG) Log.d(TAG, "keyguard view is null, creating it...");
            mKeyguardView = mKeyguardViewProperties.createKeyguardView(mContext, mUpdateMonitor, this);
            mKeyguardView.setId(R.id.lock_screen);
            mKeyguardView.setCallback(mCallback);

            final ViewGroup.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);

            mKeyguardHost.addView(mKeyguardView, lp);

            if (mScreenOn) {
                mKeyguardView.onScreenTurnedOn();
            }
        }

        mKeyguardHost.setVisibility(View.VISIBLE);
        mKeyguardView.requestFocus();
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
            mViewManager.updateViewLayout(mKeyguardHost, mWindowLayoutParams);
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

    public synchronized void onScreenTurnedOn() {
        if (DEBUG) Log.d(TAG, "onScreenTurnedOn()");
        mScreenOn = true;
        if (mKeyguardView != null) {
            mKeyguardView.onScreenTurnedOn();
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
     * @param keyCode The wake key.
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

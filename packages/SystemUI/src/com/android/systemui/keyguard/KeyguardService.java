/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.keyguard;

import com.android.internal.policy.IKeyguardExitCallback;
import com.android.internal.policy.IKeyguardService;
import com.android.internal.policy.IKeyguardServiceConstants;
import com.android.internal.policy.IKeyguardShowCallback;
import com.android.systemui.statusbar.CommandQueue;

import android.app.ActivityManagerNative;
import android.app.Service;
import android.app.StatusBarManager;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Debug;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.MotionEvent;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

public class KeyguardService extends Service {
    static final String TAG = "KeyguardService";
    static final String PERMISSION = android.Manifest.permission.CONTROL_KEYGUARD;

    public static final String ACTION_STATUS_BAR_BIND = "action.status_bar_bind";

    private CommandQueue mCommandQueue;
    private StatusBarManager mStatusBarManager;

    @Override
    public void onCreate() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (ACTION_STATUS_BAR_BIND.equals(intent.getAction())) {
            return mKeyguardStatusBarBinder;
        } else {
            return mBinder;
        }
    }

    void checkPermission() {
        if (getBaseContext().checkCallingOrSelfPermission(PERMISSION) != PERMISSION_GRANTED) {
            Log.w(TAG, "Caller needs permission '" + PERMISSION + "' to call " + Debug.getCaller());
            throw new SecurityException("Access denied to process: " + Binder.getCallingPid()
                    + ", must have permission " + PERMISSION);
        }
    }

    private final KeyguardStatusBarBinder mKeyguardStatusBarBinder =
            new KeyguardStatusBarBinder() {

        @Override
        public void register(CommandQueue commandQueue) {
            mCommandQueue = commandQueue;
        }

        @Override
        public void dismissKeyguard() {
            try {
                mBinder.dismiss();
            } catch (RemoteException e) {
                Log.e(TAG, "Could not dismiss keyguard", e);
            }
        }
    };

    private final IKeyguardService.Stub mBinder = new IKeyguardService.Stub() {

        /** Whether the Keyguard is visible. */
        private boolean mShowing;

        /**
         * Whether the Keyguard is hidden by a window with
         * {@link android.view.WindowManager.LayoutParams#FLAG_SHOW_WHEN_LOCKED}. So mShowing might
         * be true, but also mHidden. So in the end, the Keyguard would not be visible.
         */
        private boolean mHidden;
        private boolean mShowingDream;

        @Override
        public synchronized boolean isShowing() {
            return mShowing;
        }

        @Override
        public synchronized boolean isSecure() {
            return true;
        }

        @Override
        public synchronized boolean isShowingAndNotHidden() {
            return mShowing && !mHidden;
        }

        @Override
        public synchronized boolean isInputRestricted() {
            return false;
        }

        @Override
        public synchronized void verifyUnlock(IKeyguardExitCallback callback) {
        }

        @Override
        public synchronized void keyguardDone(boolean authenticated, boolean wakeup) {
            checkPermission();
            mShowing = false;
            adjustStatusBarLocked();
            if (mCommandQueue != null) {
                mCommandQueue.setKeyguardShown(false, null, true);
            }
        }

        @Override
        public synchronized int setHidden(boolean isHidden) {
            checkPermission();
            if (mHidden == isHidden) {
                return IKeyguardServiceConstants.KEYGUARD_SERVICE_HIDE_RESULT_NONE;
            }
            mHidden = isHidden;
            try {
                ActivityManagerNative.getDefault().setLockScreenShown(mShowing && !mHidden
                        || mShowingDream);
            } catch (RemoteException e) {
                Log.e(TAG, "Could not update activity manager lock screen state", e);
            }
            adjustStatusBarLocked();
            if (mCommandQueue != null) {
                mCommandQueue.setKeyguardShown(!isHidden, null, false);
            }
            return isShowingAndNotHidden()
                    ? IKeyguardServiceConstants.KEYGUARD_SERVICE_HIDE_RESULT_SET_FLAGS
                    : IKeyguardServiceConstants.KEYGUARD_SERVICE_HIDE_RESULT_UNSET_FLAGS;
        }

        @Override
        public synchronized void dismiss() {
            checkPermission();
            mShowing = false;
            adjustStatusBarLocked();
            if (mCommandQueue != null) {
                mCommandQueue.setKeyguardShown(false, null, true);
            }
        }

        @Override
        public synchronized void onDreamingStarted() {
            checkPermission();
            mShowingDream = true;
        }

        @Override
        public synchronized void onDreamingStopped() {
            checkPermission();
            mShowingDream = false;
        }

        @Override
        public synchronized void onScreenTurnedOff(int reason) {
            checkPermission();
        }

        @Override
        public synchronized void onScreenTurnedOn(IKeyguardShowCallback callback) {
            checkPermission();
            mShowing = true;
            adjustStatusBarLocked();
            if (mCommandQueue != null) {
                mCommandQueue.setKeyguardShown(isShowingAndNotHidden(), callback, true);
            }
        }

        @Override
        public synchronized void setKeyguardEnabled(boolean enabled) {
            checkPermission();
        }

        @Override
        public synchronized boolean isDismissable() {
            return !isSecure();
        }

        @Override
        public synchronized void onSystemReady() {
            checkPermission();
        }

        @Override
        public synchronized void doKeyguardTimeout(Bundle options) {
            checkPermission();
        }

        @Override
        public synchronized void setCurrentUser(int userId) {
            checkPermission();
        }

        @Override
        public synchronized void showAssistant() {
            checkPermission();
        }

        @Override
        public synchronized void dispatch(MotionEvent event) {
            checkPermission();
        }

        @Override
        public synchronized void launchCamera() {
            checkPermission();
        }

        @Override
        public synchronized void onBootCompleted() {
            checkPermission();
            onScreenTurnedOn(null);
        }

        private void adjustStatusBarLocked() {
            if (mStatusBarManager == null) {
                mStatusBarManager = (StatusBarManager) getSystemService(Context.STATUS_BAR_SERVICE);
            }
            if (mStatusBarManager == null) {
                Log.w(TAG, "Could not get status bar manager");
            } else {
                // Disable aspects of the system/status/navigation bars that must not be re-enabled by
                // windows that appear on top, ever
                int flags = StatusBarManager.DISABLE_NONE;
                if (isShowing()) {
                    // Permanently disable components not available when keyguard is enabled
                    // (like recents). Temporary enable/disable (e.g. the "back" button) are
                    // done in KeyguardHostView.
                    flags |= StatusBarManager.DISABLE_RECENT;
                    if (isSecure()) {
                        // showing secure lockscreen; disable ticker and switch private notifications
                        // to show their public versions, if available.
                        flags |= StatusBarManager.DISABLE_PRIVATE_NOTIFICATIONS;
                    }
                    if (false) {
                        flags |= StatusBarManager.DISABLE_SEARCH;
                    }
                }
                if (isShowingAndNotHidden()) {
                    flags |= StatusBarManager.DISABLE_HOME;
                }
                mStatusBarManager.disable(flags);
            }
        }
    };
}


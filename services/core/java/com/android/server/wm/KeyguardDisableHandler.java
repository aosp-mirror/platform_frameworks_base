/*
 * Copyright (C) 2012 The Android Open Source Project
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

import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManagerInternal;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.utils.UserTokenWatcher;
import com.android.server.wm.LockTaskController.LockTaskToken;

class KeyguardDisableHandler {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "KeyguardDisableHandler" : TAG_WM;

    private final UserTokenWatcher mAppTokenWatcher;
    private final UserTokenWatcher mSystemTokenWatcher;

    private int mCurrentUser = UserHandle.USER_SYSTEM;
    private Injector mInjector;

    @VisibleForTesting
    KeyguardDisableHandler(Injector injector, Handler handler) {
        mInjector = injector;
        mAppTokenWatcher = new UserTokenWatcher(mCallback, handler, TAG);
        mSystemTokenWatcher = new UserTokenWatcher(mCallback, handler, TAG);
    }

    public void setCurrentUser(int user) {
        synchronized (this) {
            mCurrentUser = user;
            updateKeyguardEnabledLocked(UserHandle.USER_ALL);
        }
    }

    void updateKeyguardEnabled(int userId) {
        synchronized (this) {
            updateKeyguardEnabledLocked(userId);
        }
    }

    private void updateKeyguardEnabledLocked(int userId) {
        if (mCurrentUser == userId || userId == UserHandle.USER_ALL) {
            mInjector.enableKeyguard(shouldKeyguardBeEnabled(mCurrentUser));
        }
    }

    void disableKeyguard(IBinder token, String tag, int callingUid, int userId) {
        UserTokenWatcher watcherForCaller = watcherForCallingUid(token, callingUid);
        watcherForCaller.acquire(token, tag, mInjector.getProfileParentId(userId));
    }

    void reenableKeyguard(IBinder token, int callingUid, int userId) {
        UserTokenWatcher watcherForCaller = watcherForCallingUid(token, callingUid);
        watcherForCaller.release(token, mInjector.getProfileParentId(userId));
    }

    private UserTokenWatcher watcherForCallingUid(IBinder token, int callingUid) {
        if (Process.isApplicationUid(callingUid)) {
            return mAppTokenWatcher;
        } else if (callingUid == Process.SYSTEM_UID && token instanceof LockTaskToken) {
            // We allow the lock task token here as a legacy case, because it enforces its own
            // security guarantees.
            // NOTE: DO NOT add new usages of this API in system server. It is deprecated and
            // easily misused.
            return mSystemTokenWatcher;
        } else {
            throw new UnsupportedOperationException("Only apps can use the KeyguardLock API");
        }
    }

    private boolean shouldKeyguardBeEnabled(int userId) {
        final boolean dpmRequiresPassword = mInjector.dpmRequiresPassword(mCurrentUser);
        final boolean keyguardSecure = mInjector.isKeyguardSecure(mCurrentUser);

        final boolean allowedFromApps = !dpmRequiresPassword && !keyguardSecure;
        // The system can disable the keyguard for lock task mode even if the keyguard is secure,
        // because it enforces its own security guarantees.
        final boolean allowedFromSystem = !dpmRequiresPassword;

        final boolean shouldBeDisabled = allowedFromApps && mAppTokenWatcher.isAcquired(userId)
                        || allowedFromSystem && mSystemTokenWatcher.isAcquired(userId);
        return !shouldBeDisabled;
    }

    // Callback happens on mHandler thread.
    private final UserTokenWatcher.Callback mCallback = new UserTokenWatcher.Callback() {
        @Override
        public void acquired(int userId) {
            updateKeyguardEnabled(userId);
        }

        @Override
        public void released(int userId) {
            updateKeyguardEnabled(userId);
        }
    };

    static KeyguardDisableHandler create(Context context, WindowManagerPolicy policy,
            Handler handler) {
        final UserManagerInternal userManager = LocalServices.getService(UserManagerInternal.class);
        return new KeyguardDisableHandler(new Injector() {
            @Override
            public boolean dpmRequiresPassword(int userId) {
                DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(
                        Context.DEVICE_POLICY_SERVICE);
                return dpm == null || dpm.getPasswordQuality(null, userId)
                        != DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED;
            }

            @Override
            public boolean isKeyguardSecure(int userId) {
                return policy.isKeyguardSecure(userId);
            }

            @Override
            public int getProfileParentId(int userId) {
                return userManager.getProfileParentId(userId);
            }

            @Override
            public void enableKeyguard(boolean enabled) {
                policy.enableKeyguard(enabled);
            }
        }, handler);
    }

    interface Injector {
        boolean dpmRequiresPassword(int userId);

        boolean isKeyguardSecure(int userId);

        int getProfileParentId(int userId);

        void enableKeyguard(boolean enabled);
    }
}

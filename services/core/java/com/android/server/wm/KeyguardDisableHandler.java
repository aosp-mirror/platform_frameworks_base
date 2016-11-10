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

import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.TokenWatcher;
import android.util.Log;
import android.util.Pair;
import android.view.WindowManagerPolicy;

public class KeyguardDisableHandler extends Handler {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "KeyguardDisableHandler" : TAG_WM;

    private static final int ALLOW_DISABLE_YES = 1;
    private static final int ALLOW_DISABLE_NO = 0;
    private static final int ALLOW_DISABLE_UNKNOWN = -1; // check with DevicePolicyManager
    private int mAllowDisableKeyguard = ALLOW_DISABLE_UNKNOWN; // sync'd by mKeyguardTokenWatcher

    // Message.what constants
    static final int KEYGUARD_DISABLE = 1;
    static final int KEYGUARD_REENABLE = 2;
    static final int KEYGUARD_POLICY_CHANGED = 3;

    final Context mContext;
    final WindowManagerPolicy mPolicy;
    KeyguardTokenWatcher mKeyguardTokenWatcher;

    public KeyguardDisableHandler(final Context context, final WindowManagerPolicy policy) {
        mContext = context;
        mPolicy = policy;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void handleMessage(Message msg) {
        if (mKeyguardTokenWatcher == null) {
            mKeyguardTokenWatcher = new KeyguardTokenWatcher(this);
        }

        switch (msg.what) {
            case KEYGUARD_DISABLE:
                final Pair<IBinder, String> pair = (Pair<IBinder, String>)msg.obj;
                mKeyguardTokenWatcher.acquire(pair.first, pair.second);
                break;

            case KEYGUARD_REENABLE:
                mKeyguardTokenWatcher.release((IBinder)msg.obj);
                break;

            case KEYGUARD_POLICY_CHANGED:
                mAllowDisableKeyguard = ALLOW_DISABLE_UNKNOWN;
                if (mKeyguardTokenWatcher.isAcquired()) {
                    // If we are currently disabled we need to know if the keyguard
                    // should be re-enabled, so determine the allow state immediately.
                    mKeyguardTokenWatcher.updateAllowState();
                    if (mAllowDisableKeyguard != ALLOW_DISABLE_YES) {
                        mPolicy.enableKeyguard(true);
                    }
                } else {
                    // lazily evaluate this next time we're asked to disable keyguard
                    mPolicy.enableKeyguard(true);
                }
                break;
        }
    }

    class KeyguardTokenWatcher extends TokenWatcher {

        public KeyguardTokenWatcher(final Handler handler) {
            super(handler, TAG);
        }

        public void updateAllowState() {
            // We fail safe and prevent disabling keyguard in the unlikely event this gets
            // called before DevicePolicyManagerService has started.
            DevicePolicyManager dpm = (DevicePolicyManager) mContext.getSystemService(
                    Context.DEVICE_POLICY_SERVICE);
            if (dpm != null) {
                try {
                    mAllowDisableKeyguard = dpm.getPasswordQuality(null,
                            ActivityManager.getService().getCurrentUser().id)
                            == DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED ?
                                    ALLOW_DISABLE_YES : ALLOW_DISABLE_NO;
                } catch (RemoteException re) {
                    // Nothing much we can do
                }
            }
        }

        @Override
        public void acquired() {
            if (mAllowDisableKeyguard == ALLOW_DISABLE_UNKNOWN) {
                updateAllowState();
            }
            if (mAllowDisableKeyguard == ALLOW_DISABLE_YES) {
                mPolicy.enableKeyguard(false);
            } else {
                Log.v(TAG, "Not disabling keyguard since device policy is enforced");
            }
        }

        @Override
        public void released() {
            mPolicy.enableKeyguard(true);
        }
    }
}

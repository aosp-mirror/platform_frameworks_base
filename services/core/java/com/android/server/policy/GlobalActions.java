/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.server.policy;

import com.android.server.LocalServices;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.statusbar.StatusBarManagerInternal.GlobalActionsListener;

import android.content.Context;
import android.os.Handler;
import android.util.Slog;
import android.view.WindowManagerPolicy.WindowManagerFuncs;

class GlobalActions implements GlobalActionsListener {

    private static final String TAG = "GlobalActions";
    private static final boolean DEBUG = false;

    private final Context mContext;
    private final StatusBarManagerInternal mStatusBarInternal;
    private final Handler mHandler;
    private final WindowManagerFuncs mWindowManagerFuncs;
    private LegacyGlobalActions mLegacyGlobalActions;
    private boolean mKeyguardShowing;
    private boolean mDeviceProvisioned;
    private boolean mStatusBarConnected;
    private boolean mShowing;

    public GlobalActions(Context context, WindowManagerFuncs windowManagerFuncs) {
        mContext = context;
        mHandler = new Handler();
        mWindowManagerFuncs = windowManagerFuncs;
        mStatusBarInternal = LocalServices.getService(StatusBarManagerInternal.class);

        // Some form factors do not have a status bar.
        if (mStatusBarInternal != null) {
            mStatusBarInternal.setGlobalActionsListener(this);
        }
    }

    private void ensureLegacyCreated() {
        if (mLegacyGlobalActions != null) return;
        mLegacyGlobalActions = new LegacyGlobalActions(mContext, mWindowManagerFuncs,
                this::onGlobalActionsDismissed);
    }

    public void showDialog(boolean keyguardShowing, boolean deviceProvisioned) {
        if (DEBUG) Slog.d(TAG, "showDialog " + keyguardShowing + " " + deviceProvisioned);
        mKeyguardShowing = keyguardShowing;
        mDeviceProvisioned = deviceProvisioned;
        mShowing = true;
        if (mStatusBarConnected) {
            mStatusBarInternal.showGlobalActions();
            mHandler.postDelayed(mShowTimeout, 5000);
        } else {
            // SysUI isn't alive, show legacy menu.
            ensureLegacyCreated();
            mLegacyGlobalActions.showDialog(mKeyguardShowing, mDeviceProvisioned);
        }
    }

    @Override
    public void onGlobalActionsShown() {
        if (DEBUG) Slog.d(TAG, "onGlobalActionsShown");
        // SysUI is showing, remove timeout callbacks.
        mHandler.removeCallbacks(mShowTimeout);
    }

    @Override
    public void onGlobalActionsDismissed() {
        if (DEBUG) Slog.d(TAG, "onGlobalActionsDismissed");
        mShowing = false;
    }

    @Override
    public void onStatusBarConnectedChanged(boolean connected) {
        if (DEBUG) Slog.d(TAG, "onStatusBarConnectedChanged " + connected);
        mStatusBarConnected = connected;
        if (mShowing && !mStatusBarConnected) {
            // Status bar died but we need to be showing global actions still, show the legacy.
            ensureLegacyCreated();
            mLegacyGlobalActions.showDialog(mKeyguardShowing, mDeviceProvisioned);
        }
    }

    private final Runnable mShowTimeout = new Runnable() {
        @Override
        public void run() {
            if (DEBUG) Slog.d(TAG, "Global actions timeout");
            // We haven't heard from sysui, show the legacy dialog.
            ensureLegacyCreated();
            mLegacyGlobalActions.showDialog(mKeyguardShowing, mDeviceProvisioned);
        }
    };
}

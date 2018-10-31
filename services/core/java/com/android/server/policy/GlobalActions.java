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

import android.content.Context;
import android.os.Handler;
import android.util.Slog;

import com.android.server.LocalServices;
import com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs;
import com.android.server.policy.GlobalActionsProvider;

class GlobalActions implements GlobalActionsProvider.GlobalActionsListener {

    private static final String TAG = "GlobalActions";
    private static final boolean DEBUG = false;

    private final Context mContext;
    private final GlobalActionsProvider mGlobalActionsProvider;
    private final Handler mHandler;
    private final WindowManagerFuncs mWindowManagerFuncs;
    private LegacyGlobalActions mLegacyGlobalActions;
    private boolean mKeyguardShowing;
    private boolean mDeviceProvisioned;
    private boolean mGlobalActionsAvailable;
    private boolean mShowing;

    public GlobalActions(Context context, WindowManagerFuncs windowManagerFuncs) {
        mContext = context;
        mHandler = new Handler();
        mWindowManagerFuncs = windowManagerFuncs;

        mGlobalActionsProvider = LocalServices.getService(GlobalActionsProvider.class);
        if (mGlobalActionsProvider != null) {
            mGlobalActionsProvider.setGlobalActionsListener(this);
        } else {
            Slog.i(TAG, "No GlobalActionsProvider found, defaulting to LegacyGlobalActions");
        }
    }

    private void ensureLegacyCreated() {
        if (mLegacyGlobalActions != null) return;
        mLegacyGlobalActions = new LegacyGlobalActions(mContext, mWindowManagerFuncs,
                this::onGlobalActionsDismissed);
    }

    public void showDialog(boolean keyguardShowing, boolean deviceProvisioned) {
        if (DEBUG) Slog.d(TAG, "showDialog " + keyguardShowing + " " + deviceProvisioned);
        if (mGlobalActionsProvider != null && mGlobalActionsProvider.isGlobalActionsDisabled()) {
            return;
        }
        mKeyguardShowing = keyguardShowing;
        mDeviceProvisioned = deviceProvisioned;
        mShowing = true;
        if (mGlobalActionsAvailable) {
            mHandler.postDelayed(mShowTimeout, 5000);
            mGlobalActionsProvider.showGlobalActions();
        /*} else {
            // SysUI isn't alive, show legacy menu.
            ensureLegacyCreated();
            mLegacyGlobalActions.showDialog(mKeyguardShowing, mDeviceProvisioned);*/
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
    public void onGlobalActionsAvailableChanged(boolean available) {
        if (DEBUG) Slog.d(TAG, "onGlobalActionsAvailableChanged " + available);
        mGlobalActionsAvailable = available;
        if (mShowing && !mGlobalActionsAvailable) {
            // Global actions provider died but we need to be showing global actions still, show the
            // legacy global acrions provider.
            //ensureLegacyCreated();
            //mLegacyGlobalActions.showDialog(mKeyguardShowing, mDeviceProvisioned);
        }
    }

    private final Runnable mShowTimeout = new Runnable() {
        @Override
        public void run() {
            if (DEBUG) Slog.d(TAG, "Global actions timeout");
            // We haven't heard from sysui, show the legacy dialog.
            //ensureLegacyCreated();
            //mLegacyGlobalActions.showDialog(mKeyguardShowing, mDeviceProvisioned);
        }
    };
}

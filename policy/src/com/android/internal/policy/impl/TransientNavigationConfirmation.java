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

import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Slog;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Toast;

import com.android.internal.R;

import java.util.Arrays;

/**
 *  Helper to manage showing/hiding a confirmation prompt when the transient navigation bar
 *  is hidden.
 */
public class TransientNavigationConfirmation {
    private static final String TAG = "TransientNavigationConfirmation";
    private static final boolean DEBUG = false;

    private final Context mContext;
    private final H mHandler;
    private final ArraySet<String> mConfirmedPackages = new ArraySet<String>();
    private final long mShowDelayMs;
    private final long mPanicThresholdMs;

    private Toast mToast;
    private String mLastPackage;
    private String mPromptPackage;
    private long mPanicTime;
    private String mPanicPackage;

    public TransientNavigationConfirmation(Context context) {
        mContext = context;
        mHandler = new H();
        mShowDelayMs = getNavBarExitDuration() * 3;
        mPanicThresholdMs = context.getResources()
                .getInteger(R.integer.config_transient_navigation_confirmation_panic);
    }

    private long getNavBarExitDuration() {
        Animation exit = AnimationUtils.loadAnimation(mContext, R.anim.dock_bottom_exit);
        return exit != null ? exit.getDuration() : 0;
    }

    public void loadSetting() {
        if (DEBUG) Slog.d(TAG, "loadSetting()");
        mConfirmedPackages.clear();
        String packages = null;
        try {
            packages = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                    Settings.Secure.TRANSIENT_NAV_CONFIRMATIONS,
                    UserHandle.USER_CURRENT);
            if (packages != null) {
                mConfirmedPackages.addAll(Arrays.asList(packages.split(",")));
                if (DEBUG) Slog.d(TAG, "Loaded mConfirmedPackages=" + mConfirmedPackages);
            }
        } catch (Throwable t) {
            Slog.w(TAG, "Error loading confirmations, packages=" + packages, t);
        }
    }

    private void saveSetting() {
        if (DEBUG) Slog.d(TAG, "saveSetting()");
        try {
            final String packages = TextUtils.join(",", mConfirmedPackages);
            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                    Settings.Secure.TRANSIENT_NAV_CONFIRMATIONS,
                    packages,
                    UserHandle.USER_CURRENT);
            if (DEBUG) Slog.d(TAG, "Saved packages=" + packages);
        } catch (Throwable t) {
            Slog.w(TAG, "Error saving confirmations, mConfirmedPackages=" + mConfirmedPackages, t);
        }
    }

    public void transientNavigationChanged(String pkg, boolean isNavTransient) {
        if (pkg == null) {
            return;
        }
        mHandler.removeMessages(H.SHOW);
        if (isNavTransient) {
            mLastPackage = pkg;
            if (!mConfirmedPackages.contains(pkg)) {
                mHandler.sendMessageDelayed(mHandler.obtainMessage(H.SHOW, pkg), mShowDelayMs);
            }
        } else {
            mLastPackage = null;
            mHandler.sendEmptyMessage(H.HIDE);
        }
    }

    public void onPowerKeyDown(boolean isScreenOn, long time, boolean transientNavigationAllowed) {
        if (mPanicPackage != null && !isScreenOn && (time - mPanicTime < mPanicThresholdMs)) {
            // turning the screen back on within the panic threshold
            unconfirmPackage(mPanicPackage);
        }
        if (isScreenOn && transientNavigationAllowed) {
            // turning the screen off, remember if we were hiding the transient nav
            mPanicTime = time;
            mPanicPackage = mLastPackage;
        } else {
            mPanicTime = 0;
            mPanicPackage = null;
        }
    }

    public void confirmCurrentPrompt() {
        mHandler.post(confirmAction(mPromptPackage));
    }

    private void unconfirmPackage(String pkg) {
        if (pkg != null) {
            if (DEBUG) Slog.d(TAG, "Unconfirming transient navigation for " + pkg);
            mConfirmedPackages.remove(pkg);
            saveSetting();
        }
    }

    private void handleHide() {
        if (mToast != null) {
            if (DEBUG) Slog.d(TAG,
                    "Hiding transient navigation confirmation for " + mPromptPackage);
            mToast.cancel();
            mToast = null;
        }
    }

    private void handleShow(String pkg) {
        mPromptPackage = pkg;
        if (DEBUG) Slog.d(TAG, "Showing transient navigation confirmation for " + pkg);

        // create the confirmation toast bar
        final int msg = R.string.transient_navigation_confirmation;
        mToast = Toast.makeBar(mContext, msg, Toast.LENGTH_INFINITE);
        mToast.setAction(R.string.ok, confirmAction(pkg));

        // we will be hiding the nav bar, so layout as if it's already hidden
        mToast.getView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        // show the confirmation
        mToast.show();
    }

    private Runnable confirmAction(final String pkg) {
        return new Runnable() {
            @Override
            public void run() {
                if (pkg != null && !mConfirmedPackages.contains(pkg)) {
                    if (DEBUG) Slog.d(TAG, "Confirming transient navigation for " + pkg);
                    mConfirmedPackages.add(pkg);
                    saveSetting();
                }
                handleHide();
            }
        };
    }

    private final class H extends Handler {
        private static final int SHOW = 0;
        private static final int HIDE = 1;

        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case SHOW:
                    handleShow((String)msg.obj);
                    break;
                case HIDE:
                    handleHide();
                    break;
            }
        }
    }
}

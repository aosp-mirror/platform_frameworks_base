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
import android.util.ArraySet;
import android.util.Slog;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.AnimationUtils;
import android.widget.Toast;

import com.android.internal.R;

/**
 *  Helper to manage showing/hiding a confirmation prompt when the transient navigation bar
 *  is hidden.
 */
public class TransientNavigationConfirmation {
    private static final String TAG = "TransientNavigationConfirmation";
    private static final boolean DEBUG = false;

    private final Context mContext;
    private final H mHandler;
    private final ArraySet<String> mConfirmedUserPackages = new ArraySet<String>();
    private final long mShowDelayMs;

    private Toast mToast;
    private String mLastUserPackage;

    public TransientNavigationConfirmation(Context context) {
        mContext = context;
        mHandler = new H();
        mShowDelayMs = getNavBarExitDuration() * 3;
    }

    private long getNavBarExitDuration() {
        Animation exit = AnimationUtils.loadAnimation(mContext, R.anim.dock_bottom_exit);
        return exit != null ? exit.getDuration() : 0;
    }

    public void transientNavigationChanged(int userId, String pkg, boolean isNavTransient) {
        if (pkg == null) {
            return;
        }
        String userPkg = userId + ":" + pkg;
        mHandler.removeMessages(H.SHOW);
        if (isNavTransient) {
            mLastUserPackage = userPkg;
            if (!mConfirmedUserPackages.contains(userPkg)) {
                if (DEBUG) Slog.d(TAG, "Showing transient navigation confirmation for " + userPkg);
                mHandler.sendMessageDelayed(mHandler.obtainMessage(H.SHOW, userPkg), mShowDelayMs);
            }
        } else {
            mLastUserPackage = null;
            if (DEBUG) Slog.d(TAG, "Hiding transient navigation confirmation for " + userPkg);
            mHandler.sendEmptyMessage(H.HIDE);
        }
    }

    public void unconfirmLastPackage() {
        if (mLastUserPackage != null) {
            if (DEBUG) Slog.d(TAG, "Unconfirming transient navigation for " + mLastUserPackage);
            mConfirmedUserPackages.remove(mLastUserPackage);
        }
    }

    private void handleHide() {
        if (mToast != null) {
            mToast.cancel();
            mToast = null;
        }
    }

    private void handleShow(String userPkg) {
        // create the confirmation toast bar
        final int msg = R.string.transient_navigation_confirmation;
        mToast = Toast.makeBar(mContext, msg, Toast.LENGTH_INFINITE);
        mToast.setAction(R.string.ok, confirmAction(userPkg));

        // we will be hiding the nav bar, so layout as if it's already hidden
        mToast.getView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        // show the confirmation
        mToast.show();
    }

    private Runnable confirmAction(final String userPkg) {
        return new Runnable() {
            @Override
            public void run() {
                mConfirmedUserPackages.add(userPkg);
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

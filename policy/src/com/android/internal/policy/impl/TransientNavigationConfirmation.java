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
import android.util.ArraySet;
import android.util.Slog;
import android.view.View;
import android.widget.Toast;

import com.android.internal.R;

/**
 *  Helper to manage showing/hiding a confirmation prompt when the transient navigation bar
 *  is hidden.
 */
public class TransientNavigationConfirmation {
    private final String TAG = "TransientNavigationConfirmation";
    private final boolean DEBUG = false;

    private final Context mContext;
    private final Handler mHandler;
    private final ArraySet<String> mConfirmedUserPackages = new ArraySet<String>();

    private final Runnable mHandleDismiss = new Runnable() {
        @Override
        public void run() {
            if (mToast != null) {
                mToast.cancel();
                mToast = null;
            }
        }
    };

    private Toast mToast;
    private String mLastUserPackage;

    public TransientNavigationConfirmation(Context context, Handler handler) {
        mContext = context;
        mHandler = handler;
    }

    public void transientNavigationChanged(int userId, String pkg, boolean isNavTransient) {
        if (pkg == null) {
            return;
        }
        String userPkg = userId + ":" + pkg;
        if (isNavTransient) {
            mLastUserPackage = userPkg;
            if (!mConfirmedUserPackages.contains(userPkg)) {
                if (DEBUG) Slog.d(TAG, "Showing transient navigation confirmation for " + userPkg);
                mHandler.post(handleShowConfirmation(userPkg));
            }
        } else {
            mLastUserPackage = null;
            if (DEBUG) Slog.d(TAG, "Hiding transient navigation confirmation for " + userPkg);
            mHandler.post(mHandleDismiss);
        }
    }

    public void unconfirmLastPackage() {
        if (mLastUserPackage != null) {
            if (DEBUG) Slog.d(TAG, "Unconfirming transient navigation for " + mLastUserPackage);
            mConfirmedUserPackages.remove(mLastUserPackage);
        }
    }

    private Runnable handleShowConfirmation(final String userPkg) {
        return new Runnable() {
            @Override
            public void run() {
                // create the confirmation toast bar
                final int msg = R.string.transient_navigation_confirmation;
                mToast = Toast.makeBar(mContext, msg, Toast.LENGTH_INFINITE);
                mToast.setAction(R.string.ok, confirmAction(userPkg));

                // we will be hiding the nav bar, so layout as if it's already hidden
                mToast.getView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

                // show the confirmation
                mToast.show();
            }
        };
    }

    private Runnable confirmAction(final String userPkg) {
        return new Runnable() {
            @Override
            public void run() {
                mConfirmedUserPackages.add(userPkg);
                mHandleDismiss.run();
            }
        };
    }
}

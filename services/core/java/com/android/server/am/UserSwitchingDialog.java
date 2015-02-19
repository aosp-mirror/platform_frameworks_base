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
 * limitations under the License.
 */

package com.android.server.am;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Resources;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.TextView;

import com.android.internal.R;

/**
 * Dialog to show when a user switch it about to happen. The intent is to snapshot the screen
 * immediately after the dialog shows so that the user is informed that something is happening
 * in the background rather than just freeze the screen and not know if the user-switch affordance
 * was being handled.
 */
final class UserSwitchingDialog extends AlertDialog
        implements ViewTreeObserver.OnWindowShownListener {
    private static final String TAG = "ActivityManagerUserSwitchingDialog";

    private final ActivityManagerService mService;
    private final int mUserId;

    public UserSwitchingDialog(ActivityManagerService service, Context context,
            int userId, String userName, boolean aboveSystem) {
        super(context);

        mService = service;
        mUserId = userId;

        // Set up the dialog contents
        setCancelable(false);
        Resources res = getContext().getResources();
        // Custom view due to alignment and font size requirements
        View view = LayoutInflater.from(getContext()).inflate(R.layout.user_switching_dialog, null);
        ((TextView) view.findViewById(R.id.message)).setText(
                res.getString(com.android.internal.R.string.user_switching_message, userName));
        setView(view);

        if (aboveSystem) {
            getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ERROR);
        }
        WindowManager.LayoutParams attrs = getWindow().getAttributes();
        attrs.privateFlags = WindowManager.LayoutParams.PRIVATE_FLAG_SYSTEM_ERROR |
                WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
        getWindow().setAttributes(attrs);
    }

    @Override
    public void show() {
        // Slog.v(TAG, "show called");
        super.show();
        final View decorView = getWindow().getDecorView();
        if (decorView != null) {
            decorView.getViewTreeObserver().addOnWindowShownListener(this);
        }
    }

    @Override
    public void onWindowShown() {
        // Slog.v(TAG, "onWindowShown called");
        mService.startUserInForeground(mUserId, this);
        final View decorView = getWindow().getDecorView();
        if (decorView != null) {
            decorView.getViewTreeObserver().removeOnWindowShownListener(this);
        }
    }
}

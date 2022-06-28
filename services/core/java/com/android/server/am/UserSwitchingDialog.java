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
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Message;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Slog;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.TextView;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;

/**
 * Dialog to show when a user switch it about to happen. The intent is to snapshot the screen
 * immediately after the dialog shows so that the user is informed that something is happening
 * in the background rather than just freeze the screen and not know if the user-switch affordance
 * was being handled.
 */
class UserSwitchingDialog extends AlertDialog
        implements ViewTreeObserver.OnWindowShownListener {
    private static final String TAG = "ActivityManagerUserSwitchingDialog";

    // Time to wait for the onWindowShown() callback before continuing the user switch
    private static final int WINDOW_SHOWN_TIMEOUT_MS = 3000;

    // User switching doesn't happen that frequently, so it doesn't hurt to have it always on
    protected static final boolean DEBUG = true;

    private final ActivityManagerService mService;
    private final int mUserId;
    private static final int MSG_START_USER = 1;
    @GuardedBy("this")
    private boolean mStartedUser;
    final protected UserInfo mOldUser;
    final protected UserInfo mNewUser;
    final private String mSwitchingFromSystemUserMessage;
    final private String mSwitchingToSystemUserMessage;
    final protected Context mContext;

    public UserSwitchingDialog(ActivityManagerService service, Context context, UserInfo oldUser,
            UserInfo newUser, boolean aboveSystem, String switchingFromSystemUserMessage,
            String switchingToSystemUserMessage) {
        super(context);

        mContext = context;
        mService = service;
        mUserId = newUser.id;
        mOldUser = oldUser;
        mNewUser = newUser;
        mSwitchingFromSystemUserMessage = switchingFromSystemUserMessage;
        mSwitchingToSystemUserMessage = switchingToSystemUserMessage;

        inflateContent();

        if (aboveSystem) {
            getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ERROR);
        }

        WindowManager.LayoutParams attrs = getWindow().getAttributes();
        attrs.privateFlags = WindowManager.LayoutParams.PRIVATE_FLAG_SYSTEM_ERROR |
            WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        getWindow().setAttributes(attrs);
    }

    void inflateContent() {
        // Set up the dialog contents
        setCancelable(false);
        Resources res = getContext().getResources();
        // Custom view due to alignment and font size requirements
        TextView view = (TextView) LayoutInflater.from(getContext()).inflate(
                R.layout.user_switching_dialog, null);

        String viewMessage = null;
        if (UserManager.isSplitSystemUser() && mNewUser.id == UserHandle.USER_SYSTEM) {
            viewMessage = res.getString(R.string.user_logging_out_message, mOldUser.name);
        } else if (UserManager.isDeviceInDemoMode(mContext)) {
            if (mOldUser.isDemo()) {
                viewMessage = res.getString(R.string.demo_restarting_message);
            } else {
                viewMessage = res.getString(R.string.demo_starting_message);
            }
        } else {
            if (mOldUser.id == UserHandle.USER_SYSTEM) {
                viewMessage = mSwitchingFromSystemUserMessage;
            } else if (mNewUser.id == UserHandle.USER_SYSTEM) {
                viewMessage = mSwitchingToSystemUserMessage;
            }

            // If switchingFromSystemUserMessage or switchingToSystemUserMessage is null, fallback
            // to system message.
            if (viewMessage == null) {
                viewMessage = res.getString(R.string.user_switching_message, mNewUser.name);
            }

            view.setCompoundDrawablesWithIntrinsicBounds(null,
                    getContext().getDrawable(R.drawable.ic_swap_horiz), null, null);
        }
        view.setAccessibilityPaneTitle(viewMessage);
        view.setText(viewMessage);
        setView(view);
    }

    @Override
    public void show() {
        if (DEBUG) Slog.d(TAG, "show called");
        super.show();
        final View decorView = getWindow().getDecorView();
        if (decorView != null) {
            decorView.getViewTreeObserver().addOnWindowShownListener(this);
        }
        // Add a timeout as a safeguard, in case a race in screen on/off causes the window
        // callback to never come.
        mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_START_USER),
                WINDOW_SHOWN_TIMEOUT_MS);
    }

    @Override
    public void onWindowShown() {
        if (DEBUG) Slog.d(TAG, "onWindowShown called");
        startUser();
    }

    void startUser() {
        synchronized (this) {
            if (!mStartedUser) {
                Slog.i(TAG, "starting user " + mUserId);
                mService.mUserController.startUserInForeground(mUserId);
                dismiss();
                mStartedUser = true;
                final View decorView = getWindow().getDecorView();
                if (decorView != null) {
                    decorView.getViewTreeObserver().removeOnWindowShownListener(this);
                }
                mHandler.removeMessages(MSG_START_USER);
            } else {
                Slog.i(TAG, "user " + mUserId + " already started");
            }
        }
    }

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_START_USER:
                    Slog.w(TAG, "user switch window not shown in "
                            + WINDOW_SHOWN_TIMEOUT_MS + " ms");
                    startUser();
                    break;
            }
        }
    };
}

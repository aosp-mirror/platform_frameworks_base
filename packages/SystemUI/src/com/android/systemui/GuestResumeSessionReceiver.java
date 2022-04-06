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

package com.android.systemui;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.os.UserHandle;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.UiEventLogger;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.qs.QSUserSwitcherEvent;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.UserSwitcherController;
import com.android.systemui.util.settings.SecureSettings;

/**
 * Manages notification when a guest session is resumed.
 */
public class GuestResumeSessionReceiver extends BroadcastReceiver {

    private static final String TAG = "GuestResumeSessionReceiver";

    @VisibleForTesting
    public static final String SETTING_GUEST_HAS_LOGGED_IN = "systemui.guest_has_logged_in";

    @VisibleForTesting
    public AlertDialog mNewSessionDialog;
    private final UserTracker mUserTracker;
    private final UserSwitcherController mUserSwitcherController;
    private final UiEventLogger mUiEventLogger;
    private final SecureSettings mSecureSettings;

    public GuestResumeSessionReceiver(UserSwitcherController userSwitcherController,
            UserTracker userTracker, UiEventLogger uiEventLogger,
            SecureSettings secureSettings) {
        mUserSwitcherController = userSwitcherController;
        mUserTracker = userTracker;
        mUiEventLogger = uiEventLogger;
        mSecureSettings = secureSettings;
    }

    /**
     * Register this receiver with the {@link BroadcastDispatcher}
     *
     * @param broadcastDispatcher to register the receiver.
     */
    public void register(BroadcastDispatcher broadcastDispatcher) {
        IntentFilter f = new IntentFilter(Intent.ACTION_USER_SWITCHED);
        broadcastDispatcher.registerReceiver(this, f, null /* handler */, UserHandle.SYSTEM);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if (Intent.ACTION_USER_SWITCHED.equals(action)) {
            cancelDialog();

            int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
            if (userId == UserHandle.USER_NULL) {
                Log.e(TAG, intent + " sent to " + TAG + " without EXTRA_USER_HANDLE");
                return;
            }

            UserInfo currentUser = mUserTracker.getUserInfo();
            if (!currentUser.isGuest()) {
                return;
            }

            int notFirstLogin = mSecureSettings.getIntForUser(
                    SETTING_GUEST_HAS_LOGGED_IN, 0, userId);
            if (notFirstLogin != 0) {
                mNewSessionDialog = new ResetSessionDialog(context, mUserSwitcherController,
                        mUiEventLogger, userId);
                mNewSessionDialog.show();
            } else {
                mSecureSettings.putIntForUser(SETTING_GUEST_HAS_LOGGED_IN, 1, userId);
            }
        }
    }

    private void cancelDialog() {
        if (mNewSessionDialog != null && mNewSessionDialog.isShowing()) {
            mNewSessionDialog.cancel();
            mNewSessionDialog = null;
        }
    }

    /**
     * Dialog shown when user when asking for confirmation before deleting guest user.
     */
    @VisibleForTesting
    public static class ResetSessionDialog extends SystemUIDialog implements
            DialogInterface.OnClickListener {

        @VisibleForTesting
        public static final int BUTTON_WIPE = BUTTON_NEGATIVE;
        @VisibleForTesting
        public static final int BUTTON_DONTWIPE = BUTTON_POSITIVE;

        private final UserSwitcherController mUserSwitcherController;
        private final UiEventLogger mUiEventLogger;
        private final int mUserId;

        ResetSessionDialog(Context context,
                UserSwitcherController userSwitcherController,
                UiEventLogger uiEventLogger,
                int userId) {
            super(context, false /* dismissOnDeviceLock */);

            setTitle(context.getString(R.string.guest_wipe_session_title));
            setMessage(context.getString(R.string.guest_wipe_session_message));
            setCanceledOnTouchOutside(false);

            setButton(BUTTON_WIPE,
                    context.getString(R.string.guest_wipe_session_wipe), this);
            setButton(BUTTON_DONTWIPE,
                    context.getString(R.string.guest_wipe_session_dontwipe), this);

            mUserSwitcherController = userSwitcherController;
            mUiEventLogger = uiEventLogger;
            mUserId = userId;
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            if (which == BUTTON_WIPE) {
                mUiEventLogger.log(QSUserSwitcherEvent.QS_USER_GUEST_WIPE);
                mUserSwitcherController.removeGuestUser(mUserId, UserHandle.USER_NULL);
                dismiss();
            } else if (which == BUTTON_DONTWIPE) {
                mUiEventLogger.log(QSUserSwitcherEvent.QS_USER_GUEST_CONTINUE);
                cancel();
            }
        }
    }
}

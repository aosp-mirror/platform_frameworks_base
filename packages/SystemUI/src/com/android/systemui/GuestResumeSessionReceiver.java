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

import android.app.ActivityManager;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.qs.QSUserSwitcherEvent;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.UserSwitcherController;

/**
 * Manages notification when a guest session is resumed.
 */
public class GuestResumeSessionReceiver extends BroadcastReceiver {

    private static final String TAG = "GuestResumeSessionReceiver";

    private static final String SETTING_GUEST_HAS_LOGGED_IN = "systemui.guest_has_logged_in";

    private Dialog mNewSessionDialog;
    private final UserSwitcherController mUserSwitcherController;
    private final UiEventLogger mUiEventLogger;

    public GuestResumeSessionReceiver(UserSwitcherController userSwitcherController,
            UiEventLogger uiEventLogger) {
        mUserSwitcherController = userSwitcherController;
        mUiEventLogger = uiEventLogger;
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

            UserInfo currentUser;
            try {
                currentUser = ActivityManager.getService().getCurrentUser();
            } catch (RemoteException e) {
                return;
            }
            if (!currentUser.isGuest()) {
                return;
            }

            ContentResolver cr = context.getContentResolver();
            int notFirstLogin = Settings.System.getIntForUser(
                    cr, SETTING_GUEST_HAS_LOGGED_IN, 0, userId);
            if (notFirstLogin != 0) {
                mNewSessionDialog = new ResetSessionDialog(context, mUserSwitcherController,
                        mUiEventLogger, userId);
                mNewSessionDialog.show();
            } else {
                Settings.System.putIntForUser(
                        cr, SETTING_GUEST_HAS_LOGGED_IN, 1, userId);
            }
        }
    }

    private void cancelDialog() {
        if (mNewSessionDialog != null && mNewSessionDialog.isShowing()) {
            mNewSessionDialog.cancel();
            mNewSessionDialog = null;
        }
    }

    private static class ResetSessionDialog extends SystemUIDialog implements
            DialogInterface.OnClickListener {

        private static final int BUTTON_WIPE = BUTTON_NEGATIVE;
        private static final int BUTTON_DONTWIPE = BUTTON_POSITIVE;

        private final UserSwitcherController mUserSwitcherController;
        private final UiEventLogger mUiEventLogger;
        private final int mUserId;

        ResetSessionDialog(Context context, UserSwitcherController userSwitcherController,
                UiEventLogger uiEventLogger, int userId) {
            super(context);

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

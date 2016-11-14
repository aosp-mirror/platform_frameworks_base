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
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;
import android.view.WindowManagerGlobal;

import com.android.systemui.statusbar.phone.SystemUIDialog;

/**
 * Manages notification when a guest session is resumed.
 */
public class GuestResumeSessionReceiver extends BroadcastReceiver {

    private static final String TAG = "GuestResumeSessionReceiver";

    private static final String SETTING_GUEST_HAS_LOGGED_IN = "systemui.guest_has_logged_in";

    private Dialog mNewSessionDialog;

    public void register(Context context) {
        IntentFilter f = new IntentFilter(Intent.ACTION_USER_SWITCHED);
        context.registerReceiverAsUser(this, UserHandle.SYSTEM,
                f, null /* permission */, null /* scheduler */);
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
                mNewSessionDialog = new ResetSessionDialog(context, userId);
                mNewSessionDialog.show();
            } else {
                Settings.System.putIntForUser(
                        cr, SETTING_GUEST_HAS_LOGGED_IN, 1, userId);
            }
        }
    }

    /**
     * Wipes the guest session.
     *
     * The guest must be the current user and its id must be {@param userId}.
     */
    private static void wipeGuestSession(Context context, int userId) {
        UserManager userManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
        UserInfo currentUser;
        try {
            currentUser = ActivityManager.getService().getCurrentUser();
        } catch (RemoteException e) {
            Log.e(TAG, "Couldn't wipe session because ActivityManager is dead");
            return;
        }
        if (currentUser.id != userId) {
            Log.w(TAG, "User requesting to start a new session (" + userId + ")"
                    + " is not current user (" + currentUser.id + ")");
            return;
        }
        if (!currentUser.isGuest()) {
            Log.w(TAG, "User requesting to start a new session (" + userId + ")"
                    + " is not a guest");
            return;
        }

        boolean marked = userManager.markGuestForDeletion(currentUser.id);
        if (!marked) {
            Log.w(TAG, "Couldn't mark the guest for deletion for user " + userId);
            return;
        }
        UserInfo newGuest = userManager.createGuest(context, currentUser.name);

        try {
            if (newGuest == null) {
                Log.e(TAG, "Could not create new guest, switching back to system user");
                ActivityManager.getService().switchUser(UserHandle.USER_SYSTEM);
                userManager.removeUser(currentUser.id);
                WindowManagerGlobal.getWindowManagerService().lockNow(null /* options */);
                return;
            }
            ActivityManager.getService().switchUser(newGuest.id);
            userManager.removeUser(currentUser.id);
        } catch (RemoteException e) {
            Log.e(TAG, "Couldn't wipe session because ActivityManager or WindowManager is dead");
            return;
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

        private final int mUserId;

        public ResetSessionDialog(Context context, int userId) {
            super(context);

            setTitle(context.getString(R.string.guest_wipe_session_title));
            setMessage(context.getString(R.string.guest_wipe_session_message));
            setCanceledOnTouchOutside(false);

            setButton(BUTTON_WIPE,
                    context.getString(R.string.guest_wipe_session_wipe), this);
            setButton(BUTTON_DONTWIPE,
                    context.getString(R.string.guest_wipe_session_dontwipe), this);

            mUserId = userId;
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            if (which == BUTTON_WIPE) {
                wipeGuestSession(getContext(), mUserId);
                dismiss();
            } else if (which == BUTTON_DONTWIPE) {
                cancel();
            }
        }
    }
}

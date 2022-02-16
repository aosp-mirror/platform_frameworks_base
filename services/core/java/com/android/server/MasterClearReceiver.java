/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.server;

import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.RecoverySystem;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.StorageManager;
import android.text.TextUtils;
import android.util.Slog;
import android.view.WindowManager;

import com.android.internal.R;
import com.android.internal.messages.nano.SystemMessageProto;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.server.utils.Slogf;

import java.io.IOException;

public class MasterClearReceiver extends BroadcastReceiver {
    private static final String TAG = "MasterClear";
    private boolean mWipeExternalStorage;
    private boolean mWipeEsims;

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (intent.getAction().equals(Intent.ACTION_REMOTE_INTENT)) {
            if (!"google.com".equals(intent.getStringExtra("from"))) {
                Slog.w(TAG, "Ignoring master clear request -- not from trusted server.");
                return;
            }
        }
        if (Intent.ACTION_MASTER_CLEAR.equals(intent.getAction())) {
            Slog.w(TAG, "The request uses the deprecated Intent#ACTION_MASTER_CLEAR, "
                    + "Intent#ACTION_FACTORY_RESET should be used instead.");
        }
        if (intent.hasExtra(Intent.EXTRA_FORCE_MASTER_CLEAR)) {
            Slog.w(TAG, "The request uses the deprecated Intent#EXTRA_FORCE_MASTER_CLEAR, "
                    + "Intent#EXTRA_FORCE_FACTORY_RESET should be used instead.");
        }

        final String factoryResetPackage = context
                .getString(com.android.internal.R.string.config_factoryResetPackage);
        if (Intent.ACTION_FACTORY_RESET.equals(intent.getAction())
                && !TextUtils.isEmpty(factoryResetPackage)) {
            Slog.i(TAG, "Re-directing intent to " + factoryResetPackage);
            intent.setPackage(factoryResetPackage).setComponent(null);
            context.sendBroadcastAsUser(intent, UserHandle.SYSTEM);
            return;
        }

        final boolean shutdown = intent.getBooleanExtra("shutdown", false);
        final String reason = intent.getStringExtra(Intent.EXTRA_REASON);
        mWipeExternalStorage = intent.getBooleanExtra(Intent.EXTRA_WIPE_EXTERNAL_STORAGE, false);
        mWipeEsims = intent.getBooleanExtra(Intent.EXTRA_WIPE_ESIMS, false);
        final boolean forceWipe = intent.getBooleanExtra(Intent.EXTRA_FORCE_MASTER_CLEAR, false)
                || intent.getBooleanExtra(Intent.EXTRA_FORCE_FACTORY_RESET, false);

        // TODO(b/189938391): properly handle factory reset on headless system user mode.
        final int sendingUserId = getSendingUserId();
        if (sendingUserId != UserHandle.USER_SYSTEM && !UserManager.isHeadlessSystemUserMode()) {
            Slogf.w(
                    TAG,
                    "ACTION_FACTORY_RESET received on a non-system user %d, WIPING THE USER!!",
                    sendingUserId);
            if (!Binder.withCleanCallingIdentity(() -> wipeUser(context, sendingUserId, reason))) {
                Slogf.e(TAG, "Failed to wipe user %d", sendingUserId);
            }
            return;
        }

        Slog.w(TAG, "!!! FACTORY RESET !!!");
        // The reboot call is blocking, so we need to do it on another thread.
        Thread thr = new Thread("Reboot") {
            @Override
            public void run() {
                try {
                    Slog.i(TAG, "Calling RecoverySystem.rebootWipeUserData(context, "
                            + "shutdown=" + shutdown + ", reason=" + reason
                            + ", forceWipe=" + forceWipe + ", wipeEsims=" + mWipeEsims + ")");
                    RecoverySystem
                            .rebootWipeUserData(context, shutdown, reason, forceWipe, mWipeEsims);
                    Slog.wtf(TAG, "Still running after master clear?!");
                } catch (IOException e) {
                    Slog.e(TAG, "Can't perform master clear/factory reset", e);
                } catch (SecurityException e) {
                    Slog.e(TAG, "Can't perform master clear/factory reset", e);
                }
            }
        };

        if (mWipeExternalStorage) {
            // thr will be started at the end of this task.
            Slog.i(TAG, "Wiping external storage on async task");
            new WipeDataTask(context, thr).execute();
        } else {
            Slog.i(TAG, "NOT wiping external storage; starting thread " + thr.getName());
            thr.start();
        }
    }

    private boolean wipeUser(Context context, @UserIdInt int userId, String wipeReason) {
        final UserManager userManager = context.getSystemService(UserManager.class);
        final int result = userManager.removeUserOrSetEphemeral(
                userId, /* evenWhenDisallowed= */ false);
        if (result == UserManager.REMOVE_RESULT_ERROR) {
            Slogf.e(TAG, "Can't remove user %d", userId);
            return false;
        }
        if (getCurrentForegroundUserId() == userId) {
            try {
                if (!ActivityManager.getService().switchUser(UserHandle.USER_SYSTEM)) {
                    Slogf.w(TAG, "Can't switch from current user %d, user will get removed when "
                                    + "it is stopped.", userId);

                }
            } catch (RemoteException e) {
                Slogf.w(TAG, "Can't switch from current user %d, user will get removed when "
                        + "it is stopped.", userId);
            }
        }
        if (userManager.isManagedProfile(userId)) {
            sendWipeProfileNotification(context, wipeReason);
        }
        return true;
    }

    // This method is copied from DevicePolicyManagedService.
    private void sendWipeProfileNotification(Context context, String wipeReason) {
        final Notification notification =
                new Notification.Builder(context, SystemNotificationChannels.DEVICE_ADMIN)
                        .setSmallIcon(android.R.drawable.stat_sys_warning)
                        .setContentTitle(context.getString(R.string.work_profile_deleted))
                        .setContentText(wipeReason)
                        .setColor(context.getColor(R.color.system_notification_accent_color))
                        .setStyle(new Notification.BigTextStyle().bigText(wipeReason))
                        .build();
        context.getSystemService(NotificationManager.class).notify(
                SystemMessageProto.SystemMessage.NOTE_PROFILE_WIPED, notification);
    }

    private @UserIdInt int getCurrentForegroundUserId() {
        try {
            return ActivityManager.getCurrentUser();
        } catch (Exception e) {
            Slogf.e(TAG, "Can't get current user", e);
        }
        return UserHandle.USER_NULL;
    }

    private class WipeDataTask extends AsyncTask<Void, Void, Void> {
        private final Thread mChainedTask;
        private final Context mContext;
        private final ProgressDialog mProgressDialog;

        public WipeDataTask(Context context, Thread chainedTask) {
            mContext = context;
            mChainedTask = chainedTask;
            mProgressDialog = new ProgressDialog(context);
        }

        @Override
        protected void onPreExecute() {
            mProgressDialog.setIndeterminate(true);
            mProgressDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
            mProgressDialog.setMessage(mContext.getText(R.string.progress_erasing));
            mProgressDialog.show();
        }

        @Override
        protected Void doInBackground(Void... params) {
            Slog.w(TAG, "Wiping adoptable disks");
            if (mWipeExternalStorage) {
                StorageManager sm = (StorageManager) mContext.getSystemService(
                        Context.STORAGE_SERVICE);
                sm.wipeAdoptableDisks();
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            mProgressDialog.dismiss();
            mChainedTask.start();
        }

    }
}

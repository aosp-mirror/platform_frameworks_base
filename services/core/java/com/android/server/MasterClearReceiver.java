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

import static android.app.admin.DevicePolicyResources.Strings.Core.WORK_PROFILE_DELETED_TITLE;

import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.ProgressDialog;
import android.app.admin.DevicePolicyManager;
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
    private boolean mShowWipeProgress = true;

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

        final String reason = intent.getStringExtra(Intent.EXTRA_REASON);

        // Factory reset dialog has its own UI for the reset process, so suppress ours if indicated.
        mShowWipeProgress = intent.getBooleanExtra(Intent.EXTRA_SHOW_WIPE_PROGRESS, true);

        final boolean shutdown = intent.getBooleanExtra("shutdown", false);
        mWipeExternalStorage = intent.getBooleanExtra(Intent.EXTRA_WIPE_EXTERNAL_STORAGE, false);
        mWipeEsims = intent.getBooleanExtra(Intent.EXTRA_WIPE_ESIMS, false);
        final boolean forceWipe = intent.getBooleanExtra(Intent.EXTRA_FORCE_MASTER_CLEAR, false)
                || intent.getBooleanExtra(Intent.EXTRA_FORCE_FACTORY_RESET, false);
        // This is ONLY used by TestHarnessService within System Server, so we don't add a proper
        // API constant in Intent for this.
        final boolean keepMemtagMode = intent.getBooleanExtra("keep_memtag_mode", false);

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
                            + ", forceWipe=" + forceWipe + ", wipeEsims=" + mWipeEsims
                            + ", keepMemtagMode=" + keepMemtagMode + ")");
                    RecoverySystem
                            .rebootWipeUserData(
                                context, shutdown, reason, forceWipe, mWipeEsims, keepMemtagMode);
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
        final int result = userManager.removeUserWhenPossible(
                UserHandle.of(userId), /* overrideDevicePolicy= */ false);
        if (!UserManager.isRemoveResultSuccessful(result)) {
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
                        .setContentTitle(getWorkProfileDeletedTitle(context))
                        .setContentText(wipeReason)
                        .setColor(context.getColor(R.color.system_notification_accent_color))
                        .setStyle(new Notification.BigTextStyle().bigText(wipeReason))
                        .build();
        context.getSystemService(NotificationManager.class).notify(
                SystemMessageProto.SystemMessage.NOTE_PROFILE_WIPED, notification);
    }

    private String getWorkProfileDeletedTitle(Context context) {
        final DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        return dpm.getResources().getString(WORK_PROFILE_DELETED_TITLE,
                () -> context.getString(R.string.work_profile_deleted));
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
            mProgressDialog = mShowWipeProgress
                    ? new ProgressDialog(context, R.style.Theme_DeviceDefault_System)
                    : null;
        }

        @Override
        protected void onPreExecute() {
            if (mProgressDialog != null) {
                mProgressDialog.setIndeterminate(true);
                mProgressDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
                mProgressDialog.setMessage(mContext.getText(R.string.progress_erasing));
                mProgressDialog.show();
            }
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
            if (mProgressDialog != null && mProgressDialog.isShowing()) {
                mProgressDialog.dismiss();
            }
            mChainedTask.start();
        }

    }
}

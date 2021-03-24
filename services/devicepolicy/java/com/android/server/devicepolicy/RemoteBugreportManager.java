/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.devicepolicy;

import static android.app.admin.DevicePolicyManager.ACTION_BUGREPORT_SHARING_ACCEPTED;
import static android.app.admin.DevicePolicyManager.ACTION_BUGREPORT_SHARING_DECLINED;
import static android.app.admin.DevicePolicyManager.ACTION_REMOTE_BUGREPORT_DISPATCH;
import static android.app.admin.DevicePolicyManager.EXTRA_BUGREPORT_NOTIFICATION_TYPE;
import static android.app.admin.DevicePolicyManager.EXTRA_REMOTE_BUGREPORT_HASH;
import static android.app.admin.DevicePolicyManager.NOTIFICATION_BUGREPORT_ACCEPTED_NOT_FINISHED;
import static android.app.admin.DevicePolicyManager.NOTIFICATION_BUGREPORT_FINISHED_NOT_ACCEPTED;
import static android.app.admin.DevicePolicyManager.NOTIFICATION_BUGREPORT_STARTED;

import static com.android.server.devicepolicy.DevicePolicyManagerService.LOG_TAG;

import android.annotation.IntDef;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.admin.DeviceAdminReceiver;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.notification.SystemNotificationChannels;

import java.io.FileNotFoundException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Class managing bugreport collection upon device owner's request.
 */
public class RemoteBugreportManager {

    static final String BUGREPORT_MIMETYPE = "application/vnd.android.bugreport";

    private static final long REMOTE_BUGREPORT_TIMEOUT_MILLIS = 10 * DateUtils.MINUTE_IN_MILLIS;
    private static final String CTL_STOP = "ctl.stop";
    private static final String REMOTE_BUGREPORT_SERVICE = "bugreportd";
    private static final int NOTIFICATION_ID = SystemMessage.NOTE_REMOTE_BUGREPORT;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            NOTIFICATION_BUGREPORT_STARTED,
            NOTIFICATION_BUGREPORT_ACCEPTED_NOT_FINISHED,
            NOTIFICATION_BUGREPORT_FINISHED_NOT_ACCEPTED
    })
    @interface RemoteBugreportNotificationType {}
    private final DevicePolicyManagerService mService;
    private final DevicePolicyManagerService.Injector mInjector;

    private final AtomicBoolean mRemoteBugreportServiceIsActive = new AtomicBoolean();
    private final AtomicBoolean mRemoteBugreportSharingAccepted = new AtomicBoolean();
    private final Context mContext;

    private final Handler mHandler;

    private final Runnable mRemoteBugreportTimeoutRunnable = () -> {
        if (mRemoteBugreportServiceIsActive.get()) {
            onBugreportFailed();
        }
    };

    private final BroadcastReceiver mRemoteBugreportFinishedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_REMOTE_BUGREPORT_DISPATCH.equals(intent.getAction())
                    && mRemoteBugreportServiceIsActive.get()) {
                onBugreportFinished(intent);
            }
        }
    };

    private final BroadcastReceiver mRemoteBugreportConsentReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            mInjector.getNotificationManager().cancel(LOG_TAG, NOTIFICATION_ID);
            if (ACTION_BUGREPORT_SHARING_ACCEPTED.equals(action)) {
                onBugreportSharingAccepted();
            } else if (ACTION_BUGREPORT_SHARING_DECLINED.equals(action)) {
                onBugreportSharingDeclined();
            }
            mContext.unregisterReceiver(mRemoteBugreportConsentReceiver);
        }
    };

    public RemoteBugreportManager(
            DevicePolicyManagerService service, DevicePolicyManagerService.Injector injector) {
        mService = service;
        mInjector = injector;
        mContext = service.mContext;
        mHandler = service.mHandler;
    }

    private Notification buildNotification(@RemoteBugreportNotificationType int type) {
        final Intent dialogIntent = new Intent(Settings.ACTION_SHOW_REMOTE_BUGREPORT_DIALOG);
        dialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        dialogIntent.putExtra(EXTRA_BUGREPORT_NOTIFICATION_TYPE, type);

        // Fill the component explicitly to prevent the PendingIntent from being intercepted
        // and fired with crafted target. b/155183624
        final ActivityInfo targetInfo = dialogIntent.resolveActivityInfo(
                mContext.getPackageManager(), PackageManager.MATCH_SYSTEM_ONLY);
        if (targetInfo != null) {
            dialogIntent.setComponent(targetInfo.getComponentName());
        } else {
            Slog.wtf(LOG_TAG, "Failed to resolve intent for remote bugreport dialog");
        }

        // Simple notification clicks are immutable
        final PendingIntent pendingDialogIntent = PendingIntent.getActivityAsUser(mContext, type,
                dialogIntent, PendingIntent.FLAG_IMMUTABLE, null, UserHandle.CURRENT);

        final Notification.Builder builder =
                new Notification.Builder(mContext, SystemNotificationChannels.DEVICE_ADMIN)
                        .setSmallIcon(com.android.internal.R.drawable.stat_sys_adb)
                        .setOngoing(true)
                        .setLocalOnly(true)
                        .setContentIntent(pendingDialogIntent)
                        .setColor(mContext.getColor(
                                com.android.internal.R.color.system_notification_accent_color))
                        .extend(new Notification.TvExtender());

        if (type == NOTIFICATION_BUGREPORT_ACCEPTED_NOT_FINISHED) {
            builder.setContentTitle(mContext.getString(
                        R.string.sharing_remote_bugreport_notification_title))
                    .setProgress(0, 0, true);
        } else if (type == NOTIFICATION_BUGREPORT_STARTED) {
            builder.setContentTitle(mContext.getString(
                        R.string.taking_remote_bugreport_notification_title))
                    .setProgress(0, 0, true);
        } else if (type == NOTIFICATION_BUGREPORT_FINISHED_NOT_ACCEPTED) {
            // Simple notification action button clicks are immutable
            final PendingIntent pendingIntentAccept = PendingIntent.getBroadcast(mContext,
                    NOTIFICATION_ID, new Intent(ACTION_BUGREPORT_SHARING_ACCEPTED),
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            // Simple notification action button clicks are immutable
            final PendingIntent pendingIntentDecline = PendingIntent.getBroadcast(mContext,
                    NOTIFICATION_ID, new Intent(ACTION_BUGREPORT_SHARING_DECLINED),
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(null /* icon */, mContext.getString(
                        R.string.decline_remote_bugreport_action), pendingIntentDecline).build())
                    .addAction(new Notification.Action.Builder(null /* icon */, mContext.getString(
                        R.string.share_remote_bugreport_action), pendingIntentAccept).build())
                    .setContentTitle(mContext.getString(
                        R.string.share_remote_bugreport_notification_title))
                    .setContentText(mContext.getString(
                        R.string.share_remote_bugreport_notification_message_finished))
                    .setStyle(new Notification.BigTextStyle().bigText(mContext.getString(
                        R.string.share_remote_bugreport_notification_message_finished)));
        }

        return builder.build();
    }

    /**
     * Initiates bugreport collection.
     * @return whether collection was initiated successfully.
     */
    public boolean requestBugreport() {
        if (mRemoteBugreportServiceIsActive.get()
                || (mService.getDeviceOwnerRemoteBugreportUriAndHash() != null)) {
            Slog.d(LOG_TAG, "Remote bugreport wasn't started because there's already one running.");
            return false;
        }

        final long callingIdentity = mInjector.binderClearCallingIdentity();
        try {
            mInjector.getIActivityManager().requestRemoteBugReport();

            mRemoteBugreportServiceIsActive.set(true);
            mRemoteBugreportSharingAccepted.set(false);
            registerRemoteBugreportReceivers();
            mInjector.getNotificationManager().notifyAsUser(LOG_TAG, NOTIFICATION_ID,
                    buildNotification(NOTIFICATION_BUGREPORT_STARTED), UserHandle.ALL);
            mHandler.postDelayed(mRemoteBugreportTimeoutRunnable, REMOTE_BUGREPORT_TIMEOUT_MILLIS);
            return true;
        } catch (RemoteException re) {
            // should never happen
            Slog.e(LOG_TAG, "Failed to make remote calls to start bugreportremote service", re);
            return false;
        } finally {
            mInjector.binderRestoreCallingIdentity(callingIdentity);
        }
    }

    private void registerRemoteBugreportReceivers() {
        try {
            final IntentFilter filterFinished =
                    new IntentFilter(ACTION_REMOTE_BUGREPORT_DISPATCH, BUGREPORT_MIMETYPE);
            mContext.registerReceiver(mRemoteBugreportFinishedReceiver, filterFinished);
        } catch (IntentFilter.MalformedMimeTypeException e) {
            // should never happen, as setting a constant
            Slog.w(LOG_TAG, e, "Failed to set type %s", BUGREPORT_MIMETYPE);
        }
        final IntentFilter filterConsent = new IntentFilter();
        filterConsent.addAction(ACTION_BUGREPORT_SHARING_DECLINED);
        filterConsent.addAction(ACTION_BUGREPORT_SHARING_ACCEPTED);
        mContext.registerReceiver(mRemoteBugreportConsentReceiver, filterConsent);
    }

    private void onBugreportFinished(Intent intent) {
        mHandler.removeCallbacks(mRemoteBugreportTimeoutRunnable);
        mRemoteBugreportServiceIsActive.set(false);
        final Uri bugreportUri = intent.getData();
        String bugreportUriString = null;
        if (bugreportUri != null) {
            bugreportUriString = bugreportUri.toString();
        }
        final String bugreportHash = intent.getStringExtra(EXTRA_REMOTE_BUGREPORT_HASH);
        if (mRemoteBugreportSharingAccepted.get()) {
            shareBugreportWithDeviceOwnerIfExists(bugreportUriString, bugreportHash);
            mInjector.getNotificationManager().cancel(LOG_TAG,
                    NOTIFICATION_ID);
        } else {
            mService.setDeviceOwnerRemoteBugreportUriAndHash(bugreportUriString, bugreportHash);
            mInjector.getNotificationManager().notifyAsUser(LOG_TAG, NOTIFICATION_ID,
                    buildNotification(NOTIFICATION_BUGREPORT_FINISHED_NOT_ACCEPTED),
                    UserHandle.ALL);
        }
        mContext.unregisterReceiver(mRemoteBugreportFinishedReceiver);
    }

    private void onBugreportFailed() {
        mRemoteBugreportServiceIsActive.set(false);
        mInjector.systemPropertiesSet(CTL_STOP, REMOTE_BUGREPORT_SERVICE);
        mRemoteBugreportSharingAccepted.set(false);
        mService.setDeviceOwnerRemoteBugreportUriAndHash(null, null);
        mInjector.getNotificationManager().cancel(LOG_TAG, NOTIFICATION_ID);
        final Bundle extras = new Bundle();
        extras.putInt(DeviceAdminReceiver.EXTRA_BUGREPORT_FAILURE_REASON,
                DeviceAdminReceiver.BUGREPORT_FAILURE_FAILED_COMPLETING);
        mService.sendDeviceOwnerCommand(DeviceAdminReceiver.ACTION_BUGREPORT_FAILED, extras);
        mContext.unregisterReceiver(mRemoteBugreportConsentReceiver);
        mContext.unregisterReceiver(mRemoteBugreportFinishedReceiver);
    }

    private void onBugreportSharingAccepted() {
        mRemoteBugreportSharingAccepted.set(true);
        final Pair<String, String> uriAndHash = mService.getDeviceOwnerRemoteBugreportUriAndHash();
        if (uriAndHash != null) {
            shareBugreportWithDeviceOwnerIfExists(uriAndHash.first, uriAndHash.second);
        } else if (mRemoteBugreportServiceIsActive.get()) {
            mInjector.getNotificationManager().notifyAsUser(LOG_TAG, NOTIFICATION_ID,
                    buildNotification(NOTIFICATION_BUGREPORT_ACCEPTED_NOT_FINISHED),
                    UserHandle.ALL);
        }
    }

    private void onBugreportSharingDeclined() {
        if (mRemoteBugreportServiceIsActive.get()) {
            mInjector.systemPropertiesSet(CTL_STOP,
                    REMOTE_BUGREPORT_SERVICE);
            mRemoteBugreportServiceIsActive.set(false);
            mHandler.removeCallbacks(mRemoteBugreportTimeoutRunnable);
            mContext.unregisterReceiver(mRemoteBugreportFinishedReceiver);
        }
        mRemoteBugreportSharingAccepted.set(false);
        mService.setDeviceOwnerRemoteBugreportUriAndHash(null, null);
        mService.sendDeviceOwnerCommand(
                DeviceAdminReceiver.ACTION_BUGREPORT_SHARING_DECLINED, null);
    }

    private void shareBugreportWithDeviceOwnerIfExists(
            String bugreportUriString, String bugreportHash) {
        try {
            if (bugreportUriString == null) {
                throw new FileNotFoundException();
            }
            final Uri bugreportUri = Uri.parse(bugreportUriString);
            mService.sendBugreportToDeviceOwner(bugreportUri, bugreportHash);
        } catch (FileNotFoundException e) {
            final Bundle extras = new Bundle();
            extras.putInt(DeviceAdminReceiver.EXTRA_BUGREPORT_FAILURE_REASON,
                    DeviceAdminReceiver.BUGREPORT_FAILURE_FILE_NO_LONGER_AVAILABLE);
            mService.sendDeviceOwnerCommand(DeviceAdminReceiver.ACTION_BUGREPORT_FAILED, extras);
        } finally {
            mRemoteBugreportSharingAccepted.set(false);
            mService.setDeviceOwnerRemoteBugreportUriAndHash(null, null);
        }
    }

    /**
     * Check if a bugreport was collected but not shared before reboot because the user didn't act
     * upon sharing notification.
     */
    public void checkForPendingBugreportAfterBoot() {
        if (mService.getDeviceOwnerRemoteBugreportUriAndHash() == null) {
            return;
        }
        final IntentFilter filterConsent = new IntentFilter();
        filterConsent.addAction(ACTION_BUGREPORT_SHARING_DECLINED);
        filterConsent.addAction(ACTION_BUGREPORT_SHARING_ACCEPTED);
        mContext.registerReceiver(mRemoteBugreportConsentReceiver, filterConsent);
        mInjector.getNotificationManager().notifyAsUser(LOG_TAG, NOTIFICATION_ID,
                buildNotification(NOTIFICATION_BUGREPORT_FINISHED_NOT_ACCEPTED), UserHandle.ALL);
    }
}

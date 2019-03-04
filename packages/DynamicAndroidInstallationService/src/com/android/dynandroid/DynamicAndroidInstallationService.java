/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.dynandroid;

import static android.content.DynamicAndroidClient.ACTION_NOTIFY_IF_IN_USE;
import static android.content.DynamicAndroidClient.ACTION_START_INSTALL;
import static android.content.DynamicAndroidClient.CAUSE_ERROR_EXCEPTION;
import static android.content.DynamicAndroidClient.CAUSE_ERROR_INVALID_URL;
import static android.content.DynamicAndroidClient.CAUSE_ERROR_IO;
import static android.content.DynamicAndroidClient.CAUSE_INSTALL_CANCELLED;
import static android.content.DynamicAndroidClient.CAUSE_INSTALL_COMPLETED;
import static android.content.DynamicAndroidClient.CAUSE_NOT_SPECIFIED;
import static android.content.DynamicAndroidClient.STATUS_IN_PROGRESS;
import static android.content.DynamicAndroidClient.STATUS_IN_USE;
import static android.content.DynamicAndroidClient.STATUS_NOT_STARTED;
import static android.content.DynamicAndroidClient.STATUS_READY;
import static android.os.AsyncTask.Status.FINISHED;
import static android.os.AsyncTask.Status.PENDING;
import static android.os.AsyncTask.Status.RUNNING;

import static com.android.dynandroid.InstallationAsyncTask.RESULT_ERROR_EXCEPTION;
import static com.android.dynandroid.InstallationAsyncTask.RESULT_ERROR_INVALID_URL;
import static com.android.dynandroid.InstallationAsyncTask.RESULT_ERROR_IO;
import static com.android.dynandroid.InstallationAsyncTask.RESULT_OK;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.DynamicAndroidClient;
import android.content.Intent;
import android.os.Bundle;
import android.os.DynamicAndroidManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.RemoteException;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * This class is the service in charge of DynamicAndroid installation.
 * It also posts status to notification bar and wait for user's
 * cancel and confirm commnands.
 */
public class DynamicAndroidInstallationService extends Service
        implements InstallationAsyncTask.InstallStatusListener {

    private static final String TAG = "DynAndroidInstallationService";

    /*
     * Intent actions
     */
    private static final String ACTION_CANCEL_INSTALL =
            "com.android.dynandroid.ACTION_CANCEL_INSTALL";
    private static final String ACTION_REBOOT_TO_DYN_ANDROID =
            "com.android.dynandroid.ACTION_REBOOT_TO_DYN_ANDROID";
    private static final String ACTION_REBOOT_TO_NORMAL =
            "com.android.dynandroid.ACTION_REBOOT_TO_NORMAL";

    /*
     * For notification
     */
    private static final String NOTIFICATION_CHANNEL_ID = "com.android.dynandroid";
    private static final int NOTIFICATION_ID = 1;

    /*
     * IPC
     */
    /** Keeps track of all current registered clients. */
    ArrayList<Messenger> mClients = new ArrayList<>();

    /** Handler of incoming messages from clients. */
    final Messenger mMessenger = new Messenger(new IncomingHandler(this));

    static class IncomingHandler extends Handler {
        private final WeakReference<DynamicAndroidInstallationService> mWeakService;

        IncomingHandler(DynamicAndroidInstallationService service) {
            mWeakService = new WeakReference<>(service);
        }

        @Override
        public void handleMessage(Message msg) {
            DynamicAndroidInstallationService service = mWeakService.get();

            if (service != null) {
                service.handleMessage(msg);
            }
        }
    }

    private DynamicAndroidManager mDynAndroid;
    private NotificationManager mNM;

    private long mSystemSize;
    private long mUserdataSize;
    private long mInstalledSize;
    private boolean mJustCancelledByUser;

    private PendingIntent mPiCancel;
    private PendingIntent mPiRebootToDynamicAndroid;
    private PendingIntent mPiUninstallAndReboot;

    private InstallationAsyncTask mInstallTask;


    @Override
    public void onCreate() {
        super.onCreate();

        prepareNotification();

        mDynAndroid = (DynamicAndroidManager) getSystemService(Context.DYNAMIC_ANDROID_SERVICE);
    }

    @Override
    public void onDestroy() {
        // Cancel the persistent notification.
        mNM.cancel(NOTIFICATION_ID);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mMessenger.getBinder();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent.getAction();

        Log.d(TAG, "onStartCommand(): action=" + action);

        if (ACTION_START_INSTALL.equals(action)) {
            executeInstallCommand(intent);
        } else if (ACTION_CANCEL_INSTALL.equals(action)) {
            executeCancelCommand();
        } else if (ACTION_REBOOT_TO_DYN_ANDROID.equals(action)) {
            executeRebootToDynAndroidCommand();
        } else if (ACTION_REBOOT_TO_NORMAL.equals(action)) {
            executeRebootToNormalCommand();
        } else if (ACTION_NOTIFY_IF_IN_USE.equals(action)) {
            executeNotifyIfInUseCommand();
        }

        return Service.START_NOT_STICKY;
    }

    @Override
    public void onProgressUpdate(long installedSize) {
        mInstalledSize = installedSize;
        postStatus(STATUS_IN_PROGRESS, CAUSE_NOT_SPECIFIED);
    }

    @Override
    public void onResult(int result) {
        if (result == RESULT_OK) {
            postStatus(STATUS_READY, CAUSE_INSTALL_COMPLETED);
            return;
        }

        // if it's not successful, reset the task and stop self.
        resetTaskAndStop();

        switch (result) {
            case RESULT_ERROR_IO:
                postStatus(STATUS_NOT_STARTED, CAUSE_ERROR_IO);
                break;

            case RESULT_ERROR_INVALID_URL:
                postStatus(STATUS_NOT_STARTED, CAUSE_ERROR_INVALID_URL);
                break;

            case RESULT_ERROR_EXCEPTION:
                postStatus(STATUS_NOT_STARTED, CAUSE_ERROR_EXCEPTION);
                break;
        }
    }

    @Override
    public void onCancelled() {
        resetTaskAndStop();
        postStatus(STATUS_NOT_STARTED, CAUSE_INSTALL_CANCELLED);
    }

    private void executeInstallCommand(Intent intent) {
        if (!verifyRequest(intent)) {
            Log.e(TAG, "Verification failed. Did you use VerificationActivity?");
            return;
        }

        if (mInstallTask != null) {
            Log.e(TAG, "There is already an install task running");
            return;
        }

        if (isInDynamicAndroid()) {
            Log.e(TAG, "We are already running in DynamicAndroid");
            return;
        }

        String url = intent.getStringExtra(DynamicAndroidClient.KEY_SYSTEM_URL);
        mSystemSize = intent.getLongExtra(DynamicAndroidClient.KEY_SYSTEM_SIZE, 0);
        mUserdataSize = intent.getLongExtra(DynamicAndroidClient.KEY_USERDATA_SIZE, 0);

        mInstallTask = new InstallationAsyncTask(
                url, mSystemSize, mUserdataSize, mDynAndroid, this);

        mInstallTask.execute();

        // start fore ground
        startForeground(NOTIFICATION_ID,
                buildNotification(STATUS_IN_PROGRESS, CAUSE_NOT_SPECIFIED));
    }

    private void executeCancelCommand() {
        if (mInstallTask == null || mInstallTask.getStatus() == PENDING) {
            Log.e(TAG, "Cancel command triggered, but there is no task running");
            mNM.cancel(NOTIFICATION_ID);

            return;
        }

        mJustCancelledByUser = true;

        if (mInstallTask.cancel(false)) {
            // Will cleanup and post status in onCancelled()
            Log.d(TAG, "Cancel request filed successfully");
        } else {
            Log.d(TAG, "Requested cancel, completed task will be discarded");

            resetTaskAndStop();
            postStatus(STATUS_NOT_STARTED, CAUSE_INSTALL_CANCELLED);
        }

    }

    private void executeRebootToDynAndroidCommand() {
        if (mInstallTask == null || mInstallTask.getStatus() != FINISHED) {
            Log.e(TAG, "Trying to reboot to DynamicAndroid, but there is no complete installation");
            return;
        }

        if (!mInstallTask.commit()) {
            // TODO: b/123673280 better UI response
            Log.e(TAG, "Failed to commit installation because of native runtime error.");
            mNM.cancel(NOTIFICATION_ID);

            return;
        }

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);

        if (powerManager != null) {
            powerManager.reboot("dynandroid");
        }
    }

    private void executeRebootToNormalCommand() {
        mDynAndroid.remove();

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);

        if (powerManager != null) {
            powerManager.reboot(null);
        }
    }

    private void executeNotifyIfInUseCommand() {
        if (isInDynamicAndroid()) {
            startForeground(NOTIFICATION_ID,
                    buildNotification(STATUS_IN_USE, CAUSE_NOT_SPECIFIED));
        }
    }

    private void resetTaskAndStop() {
        mInstallTask = null;

        stopForeground(true);

        // stop self, but this service is not destroyed yet if it's still bound
        stopSelf();
    }

    private void prepareNotification() {
        NotificationChannel chan = new NotificationChannel(NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);

        mNM = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (mNM != null) {
            mNM.createNotificationChannel(chan);
        }

        Intent intentCancel = new Intent(this, DynamicAndroidInstallationService.class);
        intentCancel.setAction(ACTION_CANCEL_INSTALL);
        mPiCancel = PendingIntent.getService(this, 0, intentCancel, 0);

        Intent intentRebootToDyn = new Intent(this, DynamicAndroidInstallationService.class);
        intentRebootToDyn.setAction(ACTION_REBOOT_TO_DYN_ANDROID);
        mPiRebootToDynamicAndroid = PendingIntent.getService(this, 0, intentRebootToDyn, 0);

        Intent intentUninstallAndReboot = new Intent(this, DynamicAndroidInstallationService.class);
        intentUninstallAndReboot.setAction(ACTION_REBOOT_TO_NORMAL);
        mPiUninstallAndReboot = PendingIntent.getService(this, 0, intentUninstallAndReboot, 0);
    }

    private Notification buildNotification(int status, int cause) {
        Notification.Builder builder = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_system_update_googblue_24dp)
                .setProgress(0, 0, false);

        switch (status) {
            case STATUS_IN_PROGRESS:
                builder.setContentText(getString(R.string.notification_install_inprogress));

                int max = (int) Math.max((mSystemSize + mUserdataSize) >> 20, 1);
                int progress = (int) (mInstalledSize >> 20);

                builder.setProgress(max, progress, false);

                builder.addAction(new Notification.Action.Builder(
                        null, getString(R.string.notification_action_cancel),
                        mPiCancel).build());

                break;

            case STATUS_READY:
                builder.setContentText(getString(R.string.notification_install_completed));

                builder.addAction(new Notification.Action.Builder(
                        null, getString(R.string.notification_action_reboot_to_dynandroid),
                        mPiRebootToDynamicAndroid).build());

                builder.addAction(new Notification.Action.Builder(
                        null, getString(R.string.notification_action_cancel),
                        mPiCancel).build());

                break;

            case STATUS_IN_USE:
                builder.setContentText(getString(R.string.notification_dynandroid_in_use));

                builder.addAction(new Notification.Action.Builder(
                        null, getString(R.string.notification_action_uninstall),
                        mPiUninstallAndReboot).build());

                break;

            case STATUS_NOT_STARTED:
                if (cause != CAUSE_NOT_SPECIFIED && cause != CAUSE_INSTALL_CANCELLED) {
                    builder.setContentText(getString(R.string.notification_install_failed));
                } else {
                    // no need to notify the user if the task is not started, or cancelled.
                }
                break;

            default:
                throw new IllegalStateException("status is invalid");
        }

        return builder.build();
    }

    private boolean verifyRequest(Intent intent) {
        String url = intent.getStringExtra(DynamicAndroidClient.KEY_SYSTEM_URL);

        return VerificationActivity.isVerified(url);
    }

    private void postStatus(int status, int cause) {
        Log.d(TAG, "postStatus(): statusCode=" + status + ", causeCode=" + cause);

        boolean notifyOnNotificationBar = true;

        if (status == STATUS_NOT_STARTED
                && cause == CAUSE_INSTALL_CANCELLED
                && mJustCancelledByUser) {
            // if task is cancelled by user, do not notify them
            notifyOnNotificationBar = false;
            mJustCancelledByUser = false;
        }

        if (notifyOnNotificationBar) {
            mNM.notify(NOTIFICATION_ID, buildNotification(status, cause));
        }

        for (int i = mClients.size() - 1; i >= 0; i--) {
            try {
                notifyOneClient(mClients.get(i), status, cause);
            } catch (RemoteException e) {
                mClients.remove(i);
            }
        }
    }

    private void notifyOneClient(Messenger client, int status, int cause) throws RemoteException {
        Bundle bundle = new Bundle();

        bundle.putLong(DynamicAndroidClient.KEY_INSTALLED_SIZE, mInstalledSize);

        client.send(Message.obtain(null,
                  DynamicAndroidClient.MSG_POST_STATUS, status, cause, bundle));
    }

    private int getStatus() {
        if (isInDynamicAndroid()) {
            return STATUS_IN_USE;

        } else if (mInstallTask == null) {
            return STATUS_NOT_STARTED;

        }

        switch (mInstallTask.getStatus()) {
            case PENDING:
                return STATUS_NOT_STARTED;

            case RUNNING:
                return STATUS_IN_PROGRESS;

            case FINISHED:
                int result = mInstallTask.getResult();

                if (result == RESULT_OK) {
                    return STATUS_READY;
                } else {
                    throw new IllegalStateException("A failed InstallationTask is not reset");
                }

            default:
                return STATUS_NOT_STARTED;
        }
    }

    private boolean isInDynamicAndroid() {
        return mDynAndroid.isInUse();
    }

    void handleMessage(Message msg) {
        switch (msg.what) {
            case DynamicAndroidClient.MSG_REGISTER_LISTENER:
                try {
                    Messenger client = msg.replyTo;

                    int status = getStatus();

                    // tell just registered client my status, but do not specify cause
                    notifyOneClient(client, status, CAUSE_NOT_SPECIFIED);

                    mClients.add(client);
                } catch (RemoteException e) {
                    // do nothing if we cannot send update to the client
                    e.printStackTrace();
                }

                break;
            case DynamicAndroidClient.MSG_UNREGISTER_LISTENER:
                mClients.remove(msg.replyTo);
                break;
            default:
                // do nothing
        }
    }
}

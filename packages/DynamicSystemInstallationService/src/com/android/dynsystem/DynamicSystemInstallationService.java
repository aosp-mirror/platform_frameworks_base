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

package com.android.dynsystem;

import static android.os.AsyncTask.Status.FINISHED;
import static android.os.AsyncTask.Status.PENDING;
import static android.os.AsyncTask.Status.RUNNING;
import static android.os.image.DynamicSystemClient.ACTION_NOTIFY_IF_IN_USE;
import static android.os.image.DynamicSystemClient.ACTION_START_INSTALL;
import static android.os.image.DynamicSystemClient.CAUSE_ERROR_EXCEPTION;
import static android.os.image.DynamicSystemClient.CAUSE_ERROR_INVALID_URL;
import static android.os.image.DynamicSystemClient.CAUSE_ERROR_IO;
import static android.os.image.DynamicSystemClient.CAUSE_INSTALL_CANCELLED;
import static android.os.image.DynamicSystemClient.CAUSE_INSTALL_COMPLETED;
import static android.os.image.DynamicSystemClient.CAUSE_NOT_SPECIFIED;
import static android.os.image.DynamicSystemClient.STATUS_IN_PROGRESS;
import static android.os.image.DynamicSystemClient.STATUS_IN_USE;
import static android.os.image.DynamicSystemClient.STATUS_NOT_STARTED;
import static android.os.image.DynamicSystemClient.STATUS_READY;

import static com.android.dynsystem.InstallationAsyncTask.RESULT_ERROR_EXCEPTION;
import static com.android.dynsystem.InstallationAsyncTask.RESULT_ERROR_INVALID_URL;
import static com.android.dynsystem.InstallationAsyncTask.RESULT_ERROR_IO;
import static com.android.dynsystem.InstallationAsyncTask.RESULT_OK;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelableException;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.image.DynamicSystemClient;
import android.os.image.DynamicSystemManager;
import android.util.Log;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * This class is the service in charge of DynamicSystem installation.
 * It also posts status to notification bar and wait for user's
 * cancel and confirm commnands.
 */
public class DynamicSystemInstallationService extends Service
        implements InstallationAsyncTask.InstallStatusListener {

    private static final String TAG = "DynSystemInstallationService";


    // TODO (b/131866826): This is currently for test only. Will move this to System API.
    static final String KEY_ENABLE_WHEN_COMPLETED = "KEY_ENABLE_WHEN_COMPLETED";

    /*
     * Intent actions
     */
    private static final String ACTION_CANCEL_INSTALL =
            "com.android.dynsystem.ACTION_CANCEL_INSTALL";
    private static final String ACTION_DISCARD_INSTALL =
            "com.android.dynsystem.ACTION_DISCARD_INSTALL";
    private static final String ACTION_REBOOT_TO_DYN_SYSTEM =
            "com.android.dynsystem.ACTION_REBOOT_TO_DYN_SYSTEM";
    private static final String ACTION_REBOOT_TO_NORMAL =
            "com.android.dynsystem.ACTION_REBOOT_TO_NORMAL";

    /*
     * For notification
     */
    private static final String NOTIFICATION_CHANNEL_ID = "com.android.dynsystem";
    private static final int NOTIFICATION_ID = 1;

    /*
     * IPC
     */
    /** Keeps track of all current registered clients. */
    ArrayList<Messenger> mClients = new ArrayList<>();

    /** Handler of incoming messages from clients. */
    final Messenger mMessenger = new Messenger(new IncomingHandler(this));

    static class IncomingHandler extends Handler {
        private final WeakReference<DynamicSystemInstallationService> mWeakService;

        IncomingHandler(DynamicSystemInstallationService service) {
            mWeakService = new WeakReference<>(service);
        }

        @Override
        public void handleMessage(Message msg) {
            DynamicSystemInstallationService service = mWeakService.get();

            if (service != null) {
                service.handleMessage(msg);
            }
        }
    }

    private DynamicSystemManager mDynSystem;
    private NotificationManager mNM;

    private long mSystemSize;
    private long mUserdataSize;
    private long mInstalledSize;
    private boolean mJustCancelledByUser;

    // This is for testing only now
    private boolean mEnableWhenCompleted;

    private InstallationAsyncTask mInstallTask;


    @Override
    public void onCreate() {
        super.onCreate();

        prepareNotification();

        mDynSystem = (DynamicSystemManager) getSystemService(Context.DYNAMIC_SYSTEM_SERVICE);
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
        } else if (ACTION_DISCARD_INSTALL.equals(action)) {
            executeDiscardCommand();
        } else if (ACTION_REBOOT_TO_DYN_SYSTEM.equals(action)) {
            executeRebootToDynSystemCommand();
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
        postStatus(STATUS_IN_PROGRESS, CAUSE_NOT_SPECIFIED, null);
    }

    @Override
    public void onResult(int result, Throwable detail) {
        if (result == RESULT_OK) {
            postStatus(STATUS_READY, CAUSE_INSTALL_COMPLETED, null);

            // For testing: enable DSU and restart the device when install completed
            if (mEnableWhenCompleted) {
                executeRebootToDynSystemCommand();
            }
            return;
        }

        // if it's not successful, reset the task and stop self.
        resetTaskAndStop();

        switch (result) {
            case RESULT_ERROR_IO:
                postStatus(STATUS_NOT_STARTED, CAUSE_ERROR_IO, detail);
                break;

            case RESULT_ERROR_INVALID_URL:
                postStatus(STATUS_NOT_STARTED, CAUSE_ERROR_INVALID_URL, detail);
                break;

            case RESULT_ERROR_EXCEPTION:
                postStatus(STATUS_NOT_STARTED, CAUSE_ERROR_EXCEPTION, detail);
                break;
        }
    }

    @Override
    public void onCancelled() {
        resetTaskAndStop();
        postStatus(STATUS_NOT_STARTED, CAUSE_INSTALL_CANCELLED, null);
    }

    private void executeInstallCommand(Intent intent) {
        if (!verifyRequest(intent)) {
            Log.e(TAG, "Verification failed. Did you use VerificationActivity?");
            return;
        }

        if (mInstallTask != null) {
            Log.e(TAG, "There is already an installation task running");
            return;
        }

        if (isInDynamicSystem()) {
            Log.e(TAG, "We are already running in DynamicSystem");
            return;
        }

        String url = intent.getDataString();
        mSystemSize = intent.getLongExtra(DynamicSystemClient.KEY_SYSTEM_SIZE, 0);
        mUserdataSize = intent.getLongExtra(DynamicSystemClient.KEY_USERDATA_SIZE, 0);
        mEnableWhenCompleted = intent.getBooleanExtra(KEY_ENABLE_WHEN_COMPLETED, false);

        mInstallTask = new InstallationAsyncTask(
                url, mSystemSize, mUserdataSize, this, mDynSystem, this);

        mInstallTask.execute();

        // start fore ground
        startForeground(NOTIFICATION_ID,
                buildNotification(STATUS_IN_PROGRESS, CAUSE_NOT_SPECIFIED));
    }

    private void executeCancelCommand() {
        if (mInstallTask == null || mInstallTask.getStatus() != RUNNING) {
            Log.e(TAG, "Cancel command triggered, but there is no task running");
            return;
        }

        mJustCancelledByUser = true;

        if (mInstallTask.cancel(false)) {
            // Will cleanup and post status in onCancelled()
            Log.d(TAG, "Cancel request filed successfully");
        } else {
            Log.e(TAG, "Trying to cancel installation while it's already completed.");
        }
    }

    private void executeDiscardCommand() {
        if (isInDynamicSystem()) {
            Log.e(TAG, "We are now running in AOT, please reboot to normal system first");
            return;
        }

        if (!isDynamicSystemInstalled() && (getStatus() != STATUS_READY)) {
            Log.e(TAG, "Trying to discard AOT while there is no complete installation");
            return;
        }

        Toast.makeText(this,
                getString(R.string.toast_dynsystem_discarded),
                Toast.LENGTH_LONG).show();

        resetTaskAndStop();
        postStatus(STATUS_NOT_STARTED, CAUSE_INSTALL_CANCELLED, null);

        mDynSystem.remove();
    }

    private void executeRebootToDynSystemCommand() {
        boolean enabled = false;

        if (mInstallTask != null && mInstallTask.getResult() == RESULT_OK) {
            enabled = mInstallTask.commit();
        } else if (isDynamicSystemInstalled()) {
            enabled = mDynSystem.setEnable(true, true);
        } else {
            Log.e(TAG, "Trying to reboot to AOT while there is no complete installation");
            return;
        }

        if (enabled) {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);

            if (powerManager != null) {
                powerManager.reboot("dynsystem");
            }
        } else {
            Log.e(TAG, "Failed to enable DynamicSystem because of native runtime error.");
            mNM.cancel(NOTIFICATION_ID);

            Toast.makeText(this,
                    getString(R.string.toast_failed_to_reboot_to_dynsystem),
                    Toast.LENGTH_LONG).show();

            mDynSystem.remove();
        }
    }

    private void executeRebootToNormalCommand() {
        if (!isInDynamicSystem()) {
            Log.e(TAG, "It's already running in normal system.");
            return;
        }

        // Per current design, we don't have disable() API. AOT is disabled on next reboot.
        // TODO: Use better status query when b/125079548 is done.
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);

        if (powerManager != null) {
            powerManager.reboot(null);
        }
    }

    private void executeNotifyIfInUseCommand() {
        int status = getStatus();

        if (status == STATUS_IN_USE) {
            startForeground(NOTIFICATION_ID,
                    buildNotification(STATUS_IN_USE, CAUSE_NOT_SPECIFIED));
        } else if (status == STATUS_READY) {
            startForeground(NOTIFICATION_ID,
                    buildNotification(STATUS_READY, CAUSE_NOT_SPECIFIED));
        } else {
            stopSelf();
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
    }

    private PendingIntent createPendingIntent(String action) {
        Intent intent = new Intent(this, DynamicSystemInstallationService.class);
        intent.setAction(action);
        return PendingIntent.getService(this, 0, intent, 0);
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
                        createPendingIntent(ACTION_CANCEL_INSTALL)).build());

                break;

            case STATUS_READY:
                builder.setContentText(getString(R.string.notification_install_completed));

                builder.addAction(new Notification.Action.Builder(
                        null, getString(R.string.notification_action_discard),
                        createPendingIntent(ACTION_DISCARD_INSTALL)).build());

                builder.addAction(new Notification.Action.Builder(
                        null, getString(R.string.notification_action_reboot_to_dynsystem),
                        createPendingIntent(ACTION_REBOOT_TO_DYN_SYSTEM)).build());

                break;

            case STATUS_IN_USE:
                builder.setContentText(getString(R.string.notification_dynsystem_in_use));

                builder.addAction(new Notification.Action.Builder(
                        null, getString(R.string.notification_action_uninstall),
                        createPendingIntent(ACTION_REBOOT_TO_NORMAL)).build());

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
        String url = intent.getDataString();

        return VerificationActivity.isVerified(url);
    }

    private void postStatus(int status, int cause, Throwable detail) {
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
                notifyOneClient(mClients.get(i), status, cause, detail);
            } catch (RemoteException e) {
                mClients.remove(i);
            }
        }
    }

    private void notifyOneClient(Messenger client, int status, int cause, Throwable detail)
            throws RemoteException {
        Bundle bundle = new Bundle();

        bundle.putLong(DynamicSystemClient.KEY_INSTALLED_SIZE, mInstalledSize);

        if (detail != null) {
            bundle.putSerializable(DynamicSystemClient.KEY_EXCEPTION_DETAIL,
                    new ParcelableException(detail));
        }

        client.send(Message.obtain(null,
                  DynamicSystemClient.MSG_POST_STATUS, status, cause, bundle));
    }

    private int getStatus() {
        if (isInDynamicSystem()) {
            return STATUS_IN_USE;
        } else if (isDynamicSystemInstalled()) {
            return STATUS_READY;
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

    private boolean isInDynamicSystem() {
        return mDynSystem.isInUse();
    }

    private boolean isDynamicSystemInstalled() {
        return mDynSystem.isInstalled();
    }

    void handleMessage(Message msg) {
        switch (msg.what) {
            case DynamicSystemClient.MSG_REGISTER_LISTENER:
                try {
                    Messenger client = msg.replyTo;

                    int status = getStatus();

                    // tell just registered client my status, but do not specify cause
                    notifyOneClient(client, status, CAUSE_NOT_SPECIFIED, null);

                    mClients.add(client);
                } catch (RemoteException e) {
                    // do nothing if we cannot send update to the client
                    e.printStackTrace();
                }

                break;
            case DynamicSystemClient.MSG_UNREGISTER_LISTENER:
                mClients.remove(msg.replyTo);
                break;
            default:
                // do nothing
        }
    }
}

/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.printspooler;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.print.IPrintManager;
import android.print.PrintJobId;
import android.print.PrintJobInfo;
import android.print.PrintManager;
import android.provider.Settings;
import android.util.Log;

/**
 * This class is responsible for updating the print notifications
 * based on print job state transitions.
 */
public class NotificationController {
    public static final boolean DEBUG = false;

    public static final String LOG_TAG = "NotificationController";

    private static final String INTENT_ACTION_CANCEL_PRINTJOB = "INTENT_ACTION_CANCEL_PRINTJOB";
    private static final String INTENT_ACTION_RESTART_PRINTJOB = "INTENT_ACTION_RESTART_PRINTJOB";

    private static final String EXTRA_PRINT_JOB_ID = "EXTRA_PRINT_JOB_ID";
    private static final String EXTRA_PRINTJOB_LABEL = "EXTRA_PRINTJOB_LABEL";
    private static final String EXTRA_PRINTER_NAME = "EXTRA_PRINTER_NAME";

    private final Context mContext;
    private final NotificationManager mNotificationManager;

    public NotificationController(Context context) {
        mContext = context;
        mNotificationManager = (NotificationManager)
                mContext.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    public void onPrintJobStateChanged(PrintJobInfo printJob) {
        if (DEBUG) {
            Log.i(LOG_TAG, "onPrintJobStateChanged() printJobId: "
                    + printJob.getId().flattenToString() + " state:"
                    + PrintJobInfo.stateToString(printJob.getState()));
        }
        switch (printJob.getState()) {
            case PrintJobInfo.STATE_QUEUED:
            case PrintJobInfo.STATE_STARTED: {
                createPrintingNotification(printJob);
            } break;

            case PrintJobInfo.STATE_FAILED: {
                createFailedNotification(printJob);
            } break;

            case PrintJobInfo.STATE_COMPLETED:
            case PrintJobInfo.STATE_CANCELED: {
                removeNotification(printJob.getId());
            } break;

            case PrintJobInfo.STATE_BLOCKED: {
                createBlockedNotification(printJob);
            } break;
        }
    }

    private void createPrintingNotification(PrintJobInfo printJob) {
        Notification.Builder builder = new Notification.Builder(mContext)
                .setContentIntent(createContentIntent(printJob.getId()))
                .setSmallIcon(com.android.internal.R.drawable.ic_print)
                .setContentTitle(mContext.getString(R.string.printing_notification_title_template,
                        printJob.getLabel()))
                .addAction(R.drawable.stat_notify_cancelling, mContext.getString(R.string.cancel),
                        createCancelIntent(printJob))
                .setContentText(printJob.getPrinterName())
                .setWhen(System.currentTimeMillis())
                .setOngoing(true)
                .setShowWhen(true);
        mNotificationManager.notify(printJob.getId().flattenToString(), 0, builder.build());
    }

    private void createFailedNotification(PrintJobInfo printJob) {
        Notification.Builder builder = new Notification.Builder(mContext)
                .setContentIntent(createContentIntent(printJob.getId()))
                .setSmallIcon(com.android.internal.R.drawable.ic_print_error)
                .setContentTitle(mContext.getString(R.string.failed_notification_title_template,
                        printJob.getLabel()))
                .addAction(R.drawable.stat_notify_cancelling, mContext.getString(R.string.cancel),
                        createCancelIntent(printJob))
                .addAction(R.drawable.ic_restart, mContext.getString(R.string.restart),
                        createRestartIntent(printJob.getId()))
                .setContentText(printJob.getPrinterName())
                .setWhen(System.currentTimeMillis())
                .setOngoing(true)
                .setShowWhen(true);
        mNotificationManager.notify(printJob.getId().flattenToString(), 0, builder.build());
    }

    private void createBlockedNotification(PrintJobInfo printJob) {
        Notification.Builder builder = new Notification.Builder(mContext)
                .setContentIntent(createContentIntent(printJob.getId()))
                .setSmallIcon(com.android.internal.R.drawable.ic_print_error)
                .setContentTitle(mContext.getString(R.string.blocked_notification_title_template,
                        printJob.getLabel()))
                .addAction(R.drawable.stat_notify_cancelling, mContext.getString(R.string.cancel),
                        createCancelIntent(printJob))
                .setContentText(printJob.getPrinterName())
                .setWhen(System.currentTimeMillis())
                .setOngoing(true)
                .setShowWhen(true);
        mNotificationManager.notify(printJob.getId().flattenToString(), 0, builder.build());
    }

    private void removeNotification(PrintJobId printJobId) {
        mNotificationManager.cancel(printJobId.flattenToString(), 0);
    }

    private PendingIntent createContentIntent(PrintJobId printJobId) {
        Intent intent = new Intent(Settings.ACTION_PRINT_SETTINGS);
        intent.putExtra(EXTRA_PRINT_JOB_ID, printJobId.flattenToString());
        return PendingIntent.getActivity(mContext, 0, intent, PendingIntent.FLAG_ONE_SHOT);
    }

    private PendingIntent createCancelIntent(PrintJobInfo printJob) {
        Intent intent = new Intent(mContext, NotificationBroadcastReceiver.class);
        intent.setAction(INTENT_ACTION_CANCEL_PRINTJOB + "_" + printJob.getId().flattenToString());
        intent.putExtra(EXTRA_PRINT_JOB_ID, printJob.getId());
        intent.putExtra(EXTRA_PRINTJOB_LABEL, printJob.getLabel());
        intent.putExtra(EXTRA_PRINTER_NAME, printJob.getPrinterName());
        return PendingIntent.getBroadcast(mContext, 0, intent, PendingIntent.FLAG_ONE_SHOT);
    }

    private PendingIntent createRestartIntent(PrintJobId printJobId) {
        Intent intent = new Intent(mContext, NotificationBroadcastReceiver.class);
        intent.setAction(INTENT_ACTION_RESTART_PRINTJOB + "_" + printJobId.flattenToString());
        intent.putExtra(EXTRA_PRINT_JOB_ID, printJobId);
        return PendingIntent.getBroadcast(mContext, 0, intent, PendingIntent.FLAG_ONE_SHOT);
    }

    public static final class NotificationBroadcastReceiver extends BroadcastReceiver {
        private static final String LOG_TAG = "NotificationBroadcastReceiver";

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action != null && action.startsWith(INTENT_ACTION_CANCEL_PRINTJOB)) {
                PrintJobId printJobId = intent.getExtras().getParcelable(EXTRA_PRINT_JOB_ID);
                String printJobLabel = intent.getExtras().getString(EXTRA_PRINTJOB_LABEL);
                String printerName = intent.getExtras().getString(EXTRA_PRINTER_NAME);
                handleCancelPrintJob(context, printJobId, printJobLabel, printerName);
            } else if (action != null && action.startsWith(INTENT_ACTION_RESTART_PRINTJOB)) {
                PrintJobId printJobId = intent.getExtras().getParcelable(EXTRA_PRINT_JOB_ID);
                handleRestartPrintJob(context, printJobId);
            }
        }

        private void handleCancelPrintJob(final Context context, final PrintJobId printJobId,
                final String printJobLabel, final String printerName) {
            if (DEBUG) {
                Log.i(LOG_TAG, "handleCancelPrintJob() printJobId:" + printJobId);
            }

            // Put up a notification that we are trying to cancel.
            NotificationManager notificationManager = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            Notification.Builder builder = new Notification.Builder(context)
                    .setSmallIcon(R.drawable.stat_notify_cancelling)
                    .setContentTitle(context.getString(
                            R.string.cancelling_notification_title_template,
                            printJobLabel))
                    .setContentText(printerName)
                    .setWhen(System.currentTimeMillis())
                    .setOngoing(true)
                    .setShowWhen(true);
            notificationManager.notify(printJobId.flattenToString(), 0, builder.build());

            // Call into the print manager service off the main thread since
            // the print manager service may end up binding to the print spooler
            // service which binding is handled on the main thread.
            PowerManager powerManager = (PowerManager)
                    context.getSystemService(Context.POWER_SERVICE);
            final WakeLock wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    LOG_TAG);
            wakeLock.acquire();

            new AsyncTask<Void, Void, Void>() {
                @Override
                protected Void doInBackground(Void... params) {
                    // We need to request the cancellation to be done by the print
                    // manager service since it has to communicate with the managing
                    // print service to request the cancellation. Also we need the
                    // system service to be bound to the spooler since canceling a
                    // print job will trigger persistence of current jobs which is
                    // done on another thread and until it finishes the spooler has
                    // to be kept around.
                    try {
                    IPrintManager printManager = IPrintManager.Stub.asInterface(
                            ServiceManager.getService(Context.PRINT_SERVICE));
                        printManager.cancelPrintJob(printJobId, PrintManager.APP_ID_ANY,
                                UserHandle.myUserId());
                    } catch (RemoteException re) {
                        Log.i(LOG_TAG, "Error requestion print job cancellation", re);
                    } finally {
                        wakeLock.release();
                    }
                    return null;
                }
            }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, (Void[]) null);
        }

        private void handleRestartPrintJob(final Context context, final PrintJobId printJobId) {
            if (DEBUG) {
                Log.i(LOG_TAG, "handleRestartPrintJob() printJobId:" + printJobId);
            }

            // Call into the print manager service off the main thread since
            // the print manager service may end up binding to the print spooler
            // service which binding is handled on the main thread.
            PowerManager powerManager = (PowerManager)
                    context.getSystemService(Context.POWER_SERVICE);
            final WakeLock wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    LOG_TAG);
            wakeLock.acquire();

            new AsyncTask<Void, Void, Void>() {
                @Override
                protected Void doInBackground(Void... params) {
                    // We need to request the restart to be done by the print manager
                    // service since the latter must be bound to the spooler because
                    // restarting a print job will trigger persistence of current jobs
                    // which is done on another thread and until it finishes the spooler has
                    // to be kept around.
                    try {
                        IPrintManager printManager = IPrintManager.Stub.asInterface(
                                ServiceManager.getService(Context.PRINT_SERVICE));
                        printManager.restartPrintJob(printJobId, PrintManager.APP_ID_ANY,
                                UserHandle.myUserId());
                    } catch (RemoteException re) {
                        Log.i(LOG_TAG, "Error requestion print job restart", re);
                    } finally {
                        wakeLock.release();
                    }
                    return null;
                }
            }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, (Void[]) null);
        }
    }
}

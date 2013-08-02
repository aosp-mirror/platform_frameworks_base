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
import android.os.Build;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.print.IPrintManager;
import android.print.PrintJobInfo;
import android.print.PrintManager;
import android.util.Log;

/**
 * This class is responsible for updating the print notifications
 * based on print job state transitions.
 */
public class NotificationController {
    public static final boolean DEBUG = true && Build.IS_DEBUGGABLE;

    public static final String LOG_TAG = "NotificationController";

    private static final String INTENT_ACTION_CANCEL_PRINTJOB = "INTENT_ACTION_CANCEL_PRINTJOB";
    private static final String INTENT_ACTION_RESTART_PRINTJOB = "INTENT_ACTION_RESTART_PRINTJOB";
    private static final String INTENT_EXTRA_PRINTJOB_ID = "INTENT_EXTRA_PRINTJOB_ID";

    private final Context mContext;
    private final NotificationManager mNotificationManager;

    public NotificationController(Context context) {
        mContext = context;
        mNotificationManager = (NotificationManager)
                mContext.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    public void onPrintJobStateChanged(PrintJobInfo printJob, int oldState) {
        if (DEBUG) {
            Log.i(LOG_TAG, "onPrintJobStateChanged() printJobId: " + printJob.getId()
                    + " oldState: " + PrintJobInfo.stateToString(oldState)
                    + " newState:" + PrintJobInfo.stateToString(printJob.getState()));
        }
        switch (printJob.getState()) {
            case PrintJobInfo.STATE_QUEUED: {
                createQueuingNotificaiton(printJob);
            } break;

            case PrintJobInfo.STATE_STARTED: {
                createPrintingNotificaiton(printJob);
            } break;

            case PrintJobInfo.STATE_FAILED: {
                createFailedNotificaiton(printJob);
            } break;

            case PrintJobInfo.STATE_COMPLETED:
            case PrintJobInfo.STATE_CANCELED: {
                removeNotification(printJob.getId());
            } break;
        }
    }

    private void createQueuingNotificaiton(PrintJobInfo printJob) {
        Notification.Builder builder = new Notification.Builder(mContext)
                // TODO: Use appropriate icon when assets are ready
                .setSmallIcon(android.R.drawable.ic_secure)
                .setContentTitle(mContext.getString(R.string.queued_notification_title_template,
                        printJob.getLabel()))
                // TODO: Use appropriate icon when assets are ready
                .addAction(android.R.drawable.ic_secure, mContext.getString(R.string.cancel),
                        createCancelIntent(printJob.getId()))
                .setContentText(printJob.getPrinterId().getPrinterName())
                .setOngoing(true)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true);
        mNotificationManager.notify(printJob.getId(), builder.build());
    }

    private void createPrintingNotificaiton(PrintJobInfo printJob) {
        Notification.Builder builder = new Notification.Builder(mContext)
                // TODO: Use appropriate icon when assets are ready
                .setSmallIcon(android.R.drawable.ic_secure)
                .setContentTitle(mContext.getString(R.string.printing_notification_title_template,
                        printJob.getLabel()))
                // TODO: Use appropriate icon when assets are ready
                .addAction(android.R.drawable.ic_secure, mContext.getString(R.string.cancel),
                        createCancelIntent(printJob.getId()))
                .setContentText(printJob.getPrinterId().getPrinterName())
                .setOngoing(true)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true);
        mNotificationManager.notify(printJob.getId(), builder.build());
    }

    private void createFailedNotificaiton(PrintJobInfo printJob) {
        Notification.Builder builder = new Notification.Builder(mContext)
                // TODO: Use appropriate icon when assets are ready
                .setSmallIcon(android.R.drawable.ic_secure)
                .setContentTitle(mContext.getString(R.string.failed_notification_title_template,
                        printJob.getLabel()))
                // TODO: Use appropriate icon when assets are ready
                .addAction(android.R.drawable.ic_secure, mContext.getString(R.string.cancel),
                        createCancelIntent(printJob.getId()))
                // TODO: Use appropriate icon when assets are ready
                .addAction(android.R.drawable.ic_secure, mContext.getString(R.string.restart),
                        createRestartIntent(printJob.getId()))
                .setContentText(printJob.getFailureReason())
                .setOngoing(true)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true);
        mNotificationManager.notify(printJob.getId(), builder.build());
    }

    private void removeNotification(int printJobId) {
        mNotificationManager.cancel(printJobId);
    }

    private PendingIntent createCancelIntent(int printJobId) {
        Intent intent = new Intent(mContext, NotificationBroadcastReceiver.class);
        intent.setAction(INTENT_ACTION_CANCEL_PRINTJOB + "_" + String.valueOf(printJobId));
        intent.putExtra(INTENT_EXTRA_PRINTJOB_ID, printJobId);
        return PendingIntent.getBroadcast(mContext, 0, intent, PendingIntent.FLAG_ONE_SHOT);
    }

    private PendingIntent createRestartIntent(int printJobId) {
        Intent intent = new Intent(mContext, NotificationBroadcastReceiver.class);
        intent.setAction(INTENT_ACTION_RESTART_PRINTJOB + "_" + String.valueOf(printJobId));
        intent.putExtra(INTENT_EXTRA_PRINTJOB_ID, printJobId);
        return PendingIntent.getBroadcast(mContext, 0, intent, PendingIntent.FLAG_ONE_SHOT);
    }

    public static final class NotificationBroadcastReceiver extends BroadcastReceiver {
        private static final String LOG_TAG = "NotificationBroadcastReceiver";

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action != null && action.startsWith(INTENT_ACTION_CANCEL_PRINTJOB)) {
                final int printJobId = intent.getExtras().getInt(INTENT_EXTRA_PRINTJOB_ID);
                handleCancelPrintJob(context, printJobId);
            } else if (action != null && action.startsWith(INTENT_ACTION_RESTART_PRINTJOB)) {
                final int printJobId = intent.getExtras().getInt(INTENT_EXTRA_PRINTJOB_ID);
                handleRestartPrintJob(context, printJobId);
            }
        }

        private void handleCancelPrintJob(final Context context, final int printJobId) {
            if (DEBUG) {
                Log.i(LOG_TAG, "handleCancelPrintJob() printJobId:" + printJobId);
            }

            PrintSpooler printSpooler = PrintSpooler.getInstance(context);

            final PrintJobInfo printJob = printSpooler.getPrintJobInfo(printJobId,
                    PrintManager.APP_ID_ANY);

            if (printJob == null || printJob.getState() == PrintJobInfo.STATE_CANCELED) {
                return;
            }

            // Put up a notification that we are trying to cancel.
            NotificationManager notificationManager = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);

            Notification.Builder builder = new Notification.Builder(context)
                    // TODO: Use appropriate icon when assets are ready
                    .setSmallIcon(android.R.drawable.ic_secure)
                    .setContentTitle(context.getString(
                            R.string.cancelling_notification_title_template,
                            printJob.getLabel()))
                    .setContentText(printJob.getPrinterId().getPrinterName())
                    .setOngoing(true)
                    .setWhen(System.currentTimeMillis())
                    .setShowWhen(true);

            notificationManager.notify(printJob.getId(), builder.build());

            // We need to request the cancellation to be done by the print
            // manager service since it has to communicate with the managing
            // print service to request the cancellation. Also we need the
            // system service to be bound to the spooler since canceling a
            // print job will trigger persistence of current jobs which is
            // done on another thread and until it finishes the spooler has
            // to be kept around.
            IPrintManager printManager = IPrintManager.Stub.asInterface(
                    ServiceManager.getService(Context.PRINT_SERVICE));

            try {
                printManager.cancelPrintJob(printJobId, PrintManager.APP_ID_ANY,
                        UserHandle.myUserId());
            } catch (RemoteException re) {
                Log.i(LOG_TAG, "Error requestion print job cancellation", re);
            }
        }

        private void handleRestartPrintJob(final Context context, final int printJobId) {
            if (DEBUG) {
                Log.i(LOG_TAG, "handleRestartPrintJob() printJobId:" + printJobId);
            }

            PrintSpooler printSpooler = PrintSpooler.getInstance(context);

            PrintJobInfo printJob = printSpooler.getPrintJobInfo(printJobId,
                    PrintManager.APP_ID_ANY);

            if (printJob == null || printJob.getState() != PrintJobInfo.STATE_FAILED) {
                return;
            }

            // We need to request the restart to be done by the print manager
            // service since the latter must be bound to the spooler because
            // restarting a print job will trigger persistence of current jobs
            // which is done on another thread and until it finishes the spooler has
            // to be kept around.
            IPrintManager printManager = IPrintManager.Stub.asInterface(
                    ServiceManager.getService(Context.PRINT_SERVICE));

            try {
                printManager.restartPrintJob(printJobId, PrintManager.APP_ID_ANY,
                        UserHandle.myUserId());
            } catch (RemoteException re) {
                Log.i(LOG_TAG, "Error requestion print job restart", re);
            }
        }
    }
}

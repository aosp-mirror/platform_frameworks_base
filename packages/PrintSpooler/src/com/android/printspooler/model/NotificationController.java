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

package com.android.printspooler.model;

import android.app.Notification;
import android.app.Notification.InboxStyle;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
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

import com.android.printspooler.R;

import java.util.ArrayList;
import java.util.List;

/**
 * This class is responsible for updating the print notifications
 * based on print job state transitions.
 */
final class NotificationController {
    public static final boolean DEBUG = false;

    public static final String LOG_TAG = "NotificationController";

    private static final String INTENT_ACTION_CANCEL_PRINTJOB = "INTENT_ACTION_CANCEL_PRINTJOB";
    private static final String INTENT_ACTION_RESTART_PRINTJOB = "INTENT_ACTION_RESTART_PRINTJOB";

    private static final String EXTRA_PRINT_JOB_ID = "EXTRA_PRINT_JOB_ID";

    private final Context mContext;
    private final NotificationManager mNotificationManager;

    public NotificationController(Context context) {
        mContext = context;
        mNotificationManager = (NotificationManager)
                mContext.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    public void onUpdateNotifications(List<PrintJobInfo> printJobs) {
        List<PrintJobInfo> notifyPrintJobs = new ArrayList<>();

        final int printJobCount = printJobs.size();
        for (int i = 0; i < printJobCount; i++) {
            PrintJobInfo printJob = printJobs.get(i);
            if (shouldNotifyForState(printJob.getState())) {
                notifyPrintJobs.add(printJob);
            }
        }

        updateNotification(notifyPrintJobs);
    }

    private void updateNotification(List<PrintJobInfo> printJobs) {
        if (printJobs.size() <= 0) {
            removeNotification();
        } else if (printJobs.size() == 1) {
            createSimpleNotification(printJobs.get(0));
        } else {
            createStackedNotification(printJobs);
        }
    }

    private void createSimpleNotification(PrintJobInfo printJob) {
        switch (printJob.getState()) {
            case PrintJobInfo.STATE_FAILED: {
                createFailedNotification(printJob);
            } break;

            case PrintJobInfo.STATE_BLOCKED: {
                if (!printJob.isCancelling()) {
                    createBlockedNotification(printJob);
                } else {
                    createCancellingNotification(printJob);
                }
            } break;

            default: {
                if (!printJob.isCancelling()) {
                    createPrintingNotification(printJob);
                } else {
                    createCancellingNotification(printJob);
                }
            } break;
        }
    }

    private void createPrintingNotification(PrintJobInfo printJob) {
        Notification.Builder builder = new Notification.Builder(mContext)
                .setContentIntent(createContentIntent(printJob.getId()))
                .setSmallIcon(computeNotificationIcon(printJob))
                .setContentTitle(computeNotificationTitle(printJob))
                .addAction(R.drawable.stat_notify_cancelling, mContext.getString(R.string.cancel),
                        createCancelIntent(printJob))
                .setContentText(printJob.getPrinterName())
                .setWhen(System.currentTimeMillis())
                .setOngoing(true)
                .setShowWhen(true)
                .setColor(mContext.getResources().getColor(
                        com.android.internal.R.color.system_notification_accent_color));
        mNotificationManager.notify(0, builder.build());
    }

    private void createFailedNotification(PrintJobInfo printJob) {
        Notification.Builder builder = new Notification.Builder(mContext)
                .setContentIntent(createContentIntent(printJob.getId()))
                .setSmallIcon(computeNotificationIcon(printJob))
                .setContentTitle(computeNotificationTitle(printJob))
                .addAction(R.drawable.stat_notify_cancelling, mContext.getString(R.string.cancel),
                        createCancelIntent(printJob))
                .addAction(R.drawable.ic_restart, mContext.getString(R.string.restart),
                        createRestartIntent(printJob.getId()))
                .setContentText(printJob.getPrinterName())
                .setWhen(System.currentTimeMillis())
                .setOngoing(true)
                .setShowWhen(true)
                .setColor(mContext.getResources().getColor(
                        com.android.internal.R.color.system_notification_accent_color));
        mNotificationManager.notify(0, builder.build());
    }

    private void createBlockedNotification(PrintJobInfo printJob) {
        Notification.Builder builder = new Notification.Builder(mContext)
                .setContentIntent(createContentIntent(printJob.getId()))
                .setSmallIcon(computeNotificationIcon(printJob))
                .setContentTitle(computeNotificationTitle(printJob))
                .addAction(R.drawable.stat_notify_cancelling, mContext.getString(R.string.cancel),
                        createCancelIntent(printJob))
                .setContentText(printJob.getPrinterName())
                .setWhen(System.currentTimeMillis())
                .setOngoing(true)
                .setShowWhen(true)
                .setColor(mContext.getResources().getColor(
                        com.android.internal.R.color.system_notification_accent_color));
           mNotificationManager.notify(0, builder.build());
    }

    private void createCancellingNotification(PrintJobInfo printJob) {
        Notification.Builder builder = new Notification.Builder(mContext)
                .setContentIntent(createContentIntent(printJob.getId()))
                .setSmallIcon(computeNotificationIcon(printJob))
                .setContentTitle(computeNotificationTitle(printJob))
                .setContentText(printJob.getPrinterName())
                .setWhen(System.currentTimeMillis())
                .setOngoing(true)
                .setShowWhen(true)
                .setColor(mContext.getResources().getColor(
                        com.android.internal.R.color.system_notification_accent_color));
        mNotificationManager.notify(0, builder.build());
    }

    private void createStackedNotification(List<PrintJobInfo> printJobs) {
        Notification.Builder builder = new Notification.Builder(mContext)
                .setContentIntent(createContentIntent(null))
                .setWhen(System.currentTimeMillis())
                .setOngoing(true)
                .setShowWhen(true);

        final int printJobCount = printJobs.size();

        InboxStyle inboxStyle = new InboxStyle();
        inboxStyle.setBigContentTitle(String.format(mContext.getResources().getQuantityText(
                R.plurals.composite_notification_title_template,
                printJobCount).toString(), printJobCount));

        for (int i = printJobCount - 1; i>= 0; i--) {
            PrintJobInfo printJob = printJobs.get(i);
            if (i == printJobCount - 1) {
                builder.setLargeIcon(((BitmapDrawable) mContext.getResources().getDrawable(
                        computeNotificationIcon(printJob))).getBitmap());
                builder.setSmallIcon(computeNotificationIcon(printJob));
                builder.setContentTitle(computeNotificationTitle(printJob));
                builder.setContentText(printJob.getPrinterName());
            }
            inboxStyle.addLine(computeNotificationTitle(printJob));
        }

        builder.setNumber(printJobCount);
        builder.setStyle(inboxStyle);
        builder.setColor(mContext.getResources().getColor(
                com.android.internal.R.color.system_notification_accent_color));

        mNotificationManager.notify(0, builder.build());
    }

    private String computeNotificationTitle(PrintJobInfo printJob) {
        switch (printJob.getState()) {
            case PrintJobInfo.STATE_FAILED: {
                return mContext.getString(R.string.failed_notification_title_template,
                        printJob.getLabel());
            }

            case PrintJobInfo.STATE_BLOCKED: {
                if (!printJob.isCancelling()) {
                    return mContext.getString(R.string.blocked_notification_title_template,
                            printJob.getLabel());
                } else {
                    return mContext.getString(
                            R.string.cancelling_notification_title_template,
                            printJob.getLabel());
                }
            }

            default: {
                if (!printJob.isCancelling()) {
                    return mContext.getString(R.string.printing_notification_title_template,
                            printJob.getLabel());
                } else {
                    return mContext.getString(
                            R.string.cancelling_notification_title_template,
                            printJob.getLabel());
                }
            }
        }
    }

    private void removeNotification() {
        mNotificationManager.cancel(0);
    }

    private PendingIntent createContentIntent(PrintJobId printJobId) {
        Intent intent = new Intent(Settings.ACTION_PRINT_SETTINGS);
        if (printJobId != null) {
            intent.putExtra(EXTRA_PRINT_JOB_ID, printJobId.flattenToString());
            intent.setData(Uri.fromParts("printjob", printJobId.flattenToString(), null));
        }
        return PendingIntent.getActivity(mContext, 0, intent, 0);
    }

    private PendingIntent createCancelIntent(PrintJobInfo printJob) {
        Intent intent = new Intent(mContext, NotificationBroadcastReceiver.class);
        intent.setAction(INTENT_ACTION_CANCEL_PRINTJOB + "_" + printJob.getId().flattenToString());
        intent.putExtra(EXTRA_PRINT_JOB_ID, printJob.getId());
        return PendingIntent.getBroadcast(mContext, 0, intent, PendingIntent.FLAG_ONE_SHOT);
    }

    private PendingIntent createRestartIntent(PrintJobId printJobId) {
        Intent intent = new Intent(mContext, NotificationBroadcastReceiver.class);
        intent.setAction(INTENT_ACTION_RESTART_PRINTJOB + "_" + printJobId.flattenToString());
        intent.putExtra(EXTRA_PRINT_JOB_ID, printJobId);
        return PendingIntent.getBroadcast(mContext, 0, intent, PendingIntent.FLAG_ONE_SHOT);
    }

    private static boolean shouldNotifyForState(int state) {
        switch (state) {
            case PrintJobInfo.STATE_QUEUED:
            case PrintJobInfo.STATE_STARTED:
            case PrintJobInfo.STATE_FAILED:
            case PrintJobInfo.STATE_COMPLETED:
            case PrintJobInfo.STATE_CANCELED:
            case PrintJobInfo.STATE_BLOCKED: {
                return true;
            }
        }
        return false;
    }

    private static int computeNotificationIcon(PrintJobInfo printJob) {
        switch (printJob.getState()) {
            case PrintJobInfo.STATE_FAILED:
            case PrintJobInfo.STATE_BLOCKED: {
                return com.android.internal.R.drawable.ic_print_error;
            }
            default: {
                if (!printJob.isCancelling()) {
                    return com.android.internal.R.drawable.ic_print;
                } else {
                    return R.drawable.stat_notify_cancelling;
                }
            }
        }
    }

    public static final class NotificationBroadcastReceiver extends BroadcastReceiver {
        private static final String LOG_TAG = "NotificationBroadcastReceiver";

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action != null && action.startsWith(INTENT_ACTION_CANCEL_PRINTJOB)) {
                PrintJobId printJobId = intent.getExtras().getParcelable(EXTRA_PRINT_JOB_ID);
                handleCancelPrintJob(context, printJobId);
            } else if (action != null && action.startsWith(INTENT_ACTION_RESTART_PRINTJOB)) {
                PrintJobId printJobId = intent.getExtras().getParcelable(EXTRA_PRINT_JOB_ID);
                handleRestartPrintJob(context, printJobId);
            }
        }

        private void handleCancelPrintJob(final Context context, final PrintJobId printJobId) {
            if (DEBUG) {
                Log.i(LOG_TAG, "handleCancelPrintJob() printJobId:" + printJobId);
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
                        Log.i(LOG_TAG, "Error requesting print job cancellation", re);
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
                        Log.i(LOG_TAG, "Error requesting print job restart", re);
                    } finally {
                        wakeLock.release();
                    }
                    return null;
                }
            }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, (Void[]) null);
        }
    }
}

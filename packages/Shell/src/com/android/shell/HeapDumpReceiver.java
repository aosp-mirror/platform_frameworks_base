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

package com.android.shell;

import static com.android.shell.BugreportProgressService.isTv;

import android.annotation.Nullable;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.FileUtils;
import android.os.Process;
import android.text.format.DateUtils;
import android.util.Log;

import java.io.File;

/**
 * Receiver that handles finished heap dumps.
 */
public class HeapDumpReceiver extends BroadcastReceiver {
    private static final String TAG = "HeapDumpReceiver";

    /**
     * Broadcast action to determine when to delete a specific dump heap. Must include a {@link
     * HeapDumpActivity#KEY_URI} String extra.
     */
    static final String ACTION_DELETE_HEAP_DUMP = "com.android.shell.action.DELETE_HEAP_DUMP";

    /** Broadcast sent when heap dump collection has been completed. */
    private static final String ACTION_HEAP_DUMP_FINISHED =
            "com.android.internal.intent.action.HEAP_DUMP_FINISHED";

    /** The process we are reporting */
    static final String EXTRA_PROCESS_NAME = "com.android.internal.extra.heap_dump.PROCESS_NAME";

    /** The size limit the process reached. */
    static final String EXTRA_SIZE_BYTES = "com.android.internal.extra.heap_dump.SIZE_BYTES";

    /** Whether the user initiated the dump or not. */
    static final String EXTRA_IS_USER_INITIATED =
            "com.android.internal.extra.heap_dump.IS_USER_INITIATED";

    /** Optional name of package to directly launch. */
    static final String EXTRA_REPORT_PACKAGE =
            "com.android.internal.extra.heap_dump.REPORT_PACKAGE";

    private static final String NOTIFICATION_CHANNEL_ID = "heapdumps";
    private static final int NOTIFICATION_ID = 2019;

    /**
     * Always keep heap dumps taken in the last week.
     */
    private static final long MIN_KEEP_AGE_MS = DateUtils.WEEK_IN_MILLIS;

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "onReceive(): " + intent);
        final String action = intent.getAction();
        if (action == null) {
            Log.e(TAG, "null action received");
            return;
        }
        switch (action) {
            case Intent.ACTION_BOOT_COMPLETED:
                cleanupOldFiles(context);
                break;
            case ACTION_DELETE_HEAP_DUMP:
                deleteHeapDump(context, intent.getStringExtra(HeapDumpActivity.KEY_URI));
                break;
            case ACTION_HEAP_DUMP_FINISHED:
                showDumpNotification(context, intent);
                break;
        }
    }

    private void cleanupOldFiles(Context context) {
        final PendingResult result = goAsync();
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                try {
                    Log.d(TAG, "Deleting from " + new File(context.getFilesDir(), "heapdumps"));
                    FileUtils.deleteOlderFiles(new File(context.getFilesDir(), "heapdumps"), 0,
                            MIN_KEEP_AGE_MS);
                } catch (RuntimeException e) {
                    Log.e(TAG, "Couldn't delete old files", e);
                }
                result.finish();
                return null;
            }
        }.execute();
    }

    private void deleteHeapDump(Context context, @Nullable final String uri) {
        if (uri == null) {
            Log.e(TAG, "null URI for delete heap dump intent");
            return;
        }
        final PendingResult result = goAsync();
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                context.getContentResolver().delete(Uri.parse(uri), null, null);
                result.finish();
                return null;
            }
        }.execute();
    }

    private void showDumpNotification(Context context, Intent intent) {
        final boolean isUserInitiated = intent.getBooleanExtra(
                EXTRA_IS_USER_INITIATED, false);
        final String procName = intent.getStringExtra(EXTRA_PROCESS_NAME);
        final int uid = intent.getIntExtra(Intent.EXTRA_UID, 0);

        final String reportPackage = intent.getStringExtra(
                EXTRA_REPORT_PACKAGE);
        final long size = intent.getLongExtra(EXTRA_SIZE_BYTES, 0);

        if (procName == null) {
            Log.e(TAG, "No process name sent over");
            return;
        }

        NotificationManager nm = NotificationManager.from(context);
        nm.createNotificationChannel(
                new NotificationChannel(NOTIFICATION_CHANNEL_ID,
                        "Heap dumps",
                        NotificationManager.IMPORTANCE_DEFAULT));

        final int titleId = isUserInitiated
                ? com.android.internal.R.string.dump_heap_ready_notification
                : com.android.internal.R.string.dump_heap_notification;
        final String procDisplayName = uid == Process.SYSTEM_UID
                ? context.getString(com.android.internal.R.string.android_system_label)
                : procName;
        String text = context.getString(titleId, procDisplayName);

        Intent shareIntent = new Intent();
        shareIntent.setClassName(context, HeapDumpActivity.class.getName());
        shareIntent.putExtra(EXTRA_PROCESS_NAME, procName);
        shareIntent.putExtra(EXTRA_SIZE_BYTES, size);
        shareIntent.putExtra(EXTRA_IS_USER_INITIATED, isUserInitiated);
        shareIntent.putExtra(Intent.EXTRA_UID, uid);
        if (reportPackage != null) {
            shareIntent.putExtra(EXTRA_REPORT_PACKAGE, reportPackage);
        }
        final Notification.Builder builder = new Notification.Builder(context,
                NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(
                        isTv(context) ? R.drawable.ic_bug_report_black_24dp
                                : com.android.internal.R.drawable.stat_sys_adb)
                .setLocalOnly(true)
                .setColor(context.getColor(
                        com.android.internal.R.color.system_notification_accent_color))
                .setContentTitle(text)
                .setTicker(text)
                .setAutoCancel(true)
                .setContentText(context.getText(
                        com.android.internal.R.string.dump_heap_notification_detail))
                .setContentIntent(PendingIntent.getActivity(context, 2, shareIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT));

        Log.v(TAG, "Creating share heap dump notification");
        NotificationManager.from(context).notify(NOTIFICATION_ID, builder.build());
    }
}

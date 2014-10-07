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

package com.android.shell;

import static com.android.shell.BugreportPrefs.STATE_SHOW;
import static com.android.shell.BugreportPrefs.getWarningState;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.FileUtils;
import android.os.SystemProperties;
import android.support.v4.content.FileProvider;
import android.text.format.DateUtils;
import android.util.Patterns;

import com.google.android.collect.Lists;

import java.io.File;
import java.util.ArrayList;

/**
 * Receiver that handles finished bugreports, usually by attaching them to an
 * {@link Intent#ACTION_SEND}.
 */
public class BugreportReceiver extends BroadcastReceiver {
    private static final String TAG = "Shell";

    private static final String AUTHORITY = "com.android.shell";

    private static final String EXTRA_BUGREPORT = "android.intent.extra.BUGREPORT";
    private static final String EXTRA_SCREENSHOT = "android.intent.extra.SCREENSHOT";

    /**
     * Always keep the newest 8 bugreport files; 4 reports and 4 screenshots are
     * roughly 17MB of disk space.
     */
    private static final int MIN_KEEP_COUNT = 8;

    /**
     * Always keep bugreports taken in the last week.
     */
    private static final long MIN_KEEP_AGE = DateUtils.WEEK_IN_MILLIS;

    @Override
    public void onReceive(Context context, Intent intent) {
        final File bugreportFile = getFileExtra(intent, EXTRA_BUGREPORT);
        final File screenshotFile = getFileExtra(intent, EXTRA_SCREENSHOT);

        // Files are kept on private storage, so turn into Uris that we can
        // grant temporary permissions for.
        final Uri bugreportUri = FileProvider.getUriForFile(context, AUTHORITY, bugreportFile);
        final Uri screenshotUri = FileProvider.getUriForFile(context, AUTHORITY, screenshotFile);

        Intent sendIntent = buildSendIntent(context, bugreportUri, screenshotUri);
        Intent notifIntent;

        // Send through warning dialog by default
        if (getWarningState(context, STATE_SHOW) == STATE_SHOW) {
            notifIntent = buildWarningIntent(context, sendIntent);
        } else {
            notifIntent = sendIntent;
        }
        notifIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        final Notification.Builder builder = new Notification.Builder(context)
                .setSmallIcon(com.android.internal.R.drawable.stat_sys_adb)
                .setContentTitle(context.getString(R.string.bugreport_finished_title))
                .setTicker(context.getString(R.string.bugreport_finished_title))
                .setContentText(context.getString(R.string.bugreport_finished_text))
                .setContentIntent(PendingIntent.getActivity(
                        context, 0, notifIntent, PendingIntent.FLAG_CANCEL_CURRENT))
                .setAutoCancel(true)
                .setLocalOnly(true)
                .setColor(context.getResources().getColor(
                        com.android.internal.R.color.system_notification_accent_color));

        NotificationManager.from(context).notify(TAG, 0, builder.build());

        // Clean up older bugreports in background
        final PendingResult result = goAsync();
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                FileUtils.deleteOlderFiles(
                        bugreportFile.getParentFile(), MIN_KEEP_COUNT, MIN_KEEP_AGE);
                result.finish();
                return null;
            }
        }.execute();
    }

    private static Intent buildWarningIntent(Context context, Intent sendIntent) {
        final Intent intent = new Intent(context, BugreportWarningActivity.class);
        intent.putExtra(Intent.EXTRA_INTENT, sendIntent);
        return intent;
    }

    /**
     * Build {@link Intent} that can be used to share the given bugreport.
     */
    private static Intent buildSendIntent(Context context, Uri bugreportUri, Uri screenshotUri) {
        final Intent intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.setType("application/vnd.android.bugreport");

        intent.putExtra(Intent.EXTRA_SUBJECT, bugreportUri.getLastPathSegment());
        intent.putExtra(Intent.EXTRA_TEXT, SystemProperties.get("ro.build.description"));

        final ArrayList<Uri> attachments = Lists.newArrayList(bugreportUri, screenshotUri);
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, attachments);

        final Account sendToAccount = findSendToAccount(context);
        if (sendToAccount != null) {
            intent.putExtra(Intent.EXTRA_EMAIL, new String[] { sendToAccount.name });
        }

        return intent;
    }

    /**
     * Find the best matching {@link Account} based on build properties.
     */
    private static Account findSendToAccount(Context context) {
        final AccountManager am = (AccountManager) context.getSystemService(
                Context.ACCOUNT_SERVICE);

        String preferredDomain = SystemProperties.get("sendbug.preferred.domain");
        if (!preferredDomain.startsWith("@")) {
            preferredDomain = "@" + preferredDomain;
        }

        final Account[] accounts = am.getAccounts();
        Account foundAccount = null;
        for (Account account : accounts) {
            if (Patterns.EMAIL_ADDRESS.matcher(account.name).matches()) {
                if (!preferredDomain.isEmpty()) {
                    // if we have a preferred domain and it matches, return; otherwise keep
                    // looking
                    if (account.name.endsWith(preferredDomain)) {
                        return account;
                    } else {
                        foundAccount = account;
                    }
                    // if we don't have a preferred domain, just return since it looks like
                    // an email address
                } else {
                    return account;
                }
            }
        }
        return foundAccount;
    }

    private static File getFileExtra(Intent intent, String key) {
        final String path = intent.getStringExtra(key);
        if (path != null) {
            return new File(path);
        } else {
            return null;
        }
    }
}

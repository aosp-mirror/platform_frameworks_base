/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.util.leak;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Debug;
import android.os.SystemProperties;
import android.os.UserHandle;
import androidx.core.content.FileProvider;
import android.util.Log;

import com.google.android.collect.Lists;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Dumps data to debug leaks and posts a notification to share the data.
 */
public class LeakReporter {

    static final String TAG = "LeakReporter";

    public static final String FILEPROVIDER_AUTHORITY = "com.android.systemui.fileprovider";

    static final String LEAK_DIR = "leak";
    static final String LEAK_HPROF = "leak.hprof";
    static final String LEAK_DUMP = "leak.dump";

    private final Context mContext;
    private final LeakDetector mLeakDetector;
    private final String mLeakReportEmail;

    public LeakReporter(Context context, LeakDetector leakDetector, String leakReportEmail) {
        mContext = context;
        mLeakDetector = leakDetector;
        mLeakReportEmail = leakReportEmail;
    }

    public void dumpLeak(int garbageCount) {
        try {
            File leakDir = new File(mContext.getCacheDir(), LEAK_DIR);
            leakDir.mkdir();

            File hprofFile = new File(leakDir, LEAK_HPROF);
            Debug.dumpHprofData(hprofFile.getAbsolutePath());

            File dumpFile = new File(leakDir, LEAK_DUMP);
            try (FileOutputStream fos = new FileOutputStream(dumpFile)) {
                PrintWriter w = new PrintWriter(fos);
                w.print("Build: "); w.println(SystemProperties.get("ro.build.description"));
                w.println();
                w.flush();
                mLeakDetector.dump(fos.getFD(), w, new String[0]);
                w.close();
            }

            NotificationManager notiMan = mContext.getSystemService(NotificationManager.class);

            NotificationChannel channel = new NotificationChannel("leak", "Leak Alerts",
                    NotificationManager.IMPORTANCE_HIGH);
            channel.enableVibration(true);

            notiMan.createNotificationChannel(channel);
            Notification.Builder builder = new Notification.Builder(mContext, channel.getId())
                    .setAutoCancel(true)
                    .setShowWhen(true)
                    .setContentTitle("Memory Leak Detected")
                    .setContentText(String.format(
                            "SystemUI has detected %d leaked objects. Tap to send", garbageCount))
                    .setSmallIcon(com.android.internal.R.drawable.stat_sys_adb)
                    .setContentIntent(PendingIntent.getActivityAsUser(mContext, 0,
                            getIntent(hprofFile, dumpFile),
                            PendingIntent.FLAG_UPDATE_CURRENT, null, UserHandle.CURRENT));
            notiMan.notify(TAG, 0, builder.build());
        } catch (IOException e) {
            Log.e(TAG, "Couldn't dump heap for leak", e);
        }
    }

    private Intent getIntent(File hprofFile, File dumpFile) {
        Uri dumpUri = FileProvider.getUriForFile(mContext, FILEPROVIDER_AUTHORITY, dumpFile);
        Uri hprofUri = FileProvider.getUriForFile(mContext, FILEPROVIDER_AUTHORITY, hprofFile);

        Intent intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
        String mimeType = "application/vnd.android.leakreport";

        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.setType(mimeType);

        final String subject = "SystemUI leak report";
        intent.putExtra(Intent.EXTRA_SUBJECT, subject);

        // EXTRA_TEXT should be an ArrayList, but some clients are expecting a single String.
        // So, to avoid an exception on Intent.migrateExtraStreamToClipData(), we need to manually
        // create the ClipData object with the attachments URIs.
        final StringBuilder messageBody = new StringBuilder("Build info: ")
                .append(SystemProperties.get("ro.build.description"));
        intent.putExtra(Intent.EXTRA_TEXT, messageBody.toString());
        final ClipData clipData = new ClipData(null, new String[] { mimeType },
                new ClipData.Item(null, null, null, dumpUri));
        final ArrayList<Uri> attachments = Lists.newArrayList(dumpUri);

        clipData.addItem(new ClipData.Item(null, null, null, hprofUri));
        attachments.add(hprofUri);

        intent.setClipData(clipData);
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, attachments);

        String leakReportEmail = mLeakReportEmail;
        if (leakReportEmail != null) {
            intent.putExtra(Intent.EXTRA_EMAIL, new String[] { leakReportEmail });
        }

        return intent;
    }
}

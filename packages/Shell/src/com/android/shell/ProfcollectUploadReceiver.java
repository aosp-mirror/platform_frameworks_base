/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.util.Log;

import androidx.core.content.FileProvider;

import com.android.internal.R;

import java.io.File;
import java.util.List;

/**
 * A proxy service that relays report upload requests to the uploader app, while translating
 * the path to the report to a content URI owned by this service.
 */
public final class ProfcollectUploadReceiver extends BroadcastReceiver {
    private static final String AUTHORITY = "com.android.shell";
    private static final String PROFCOLLECT_DATA_ROOT = "/data/misc/profcollectd/report/";

    private static final String LOG_TAG = "ProfcollectUploadReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(LOG_TAG, "Received upload intent");

        String uploaderPkg = getUploaderPackageName(context);
        String uploaderAction = getUploaderActionName(context);

        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(uploaderPkg,
                    0);
            if ((info.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                Log.e(LOG_TAG, "The profcollect uploader app " + uploaderPkg
                        + " must be a system application");
                return;
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(LOG_TAG, "Cannot find profcollect uploader app " + uploaderPkg);
            return;
        }

        String filename = intent.getStringExtra("filename");
        File reportFile = new File(PROFCOLLECT_DATA_ROOT + filename);
        Uri reportUri = FileProvider.getUriForFile(context, AUTHORITY, reportFile);
        Intent uploadIntent =
                new Intent(uploaderAction)
                        .setPackage(uploaderPkg)
                        .putExtra("EXTRA_DESTINATION", "PROFCOLLECT")
                        .putExtra("EXTRA_PACKAGE_NAME", context.getPackageName())
                        .setData(reportUri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);

        List<ResolveInfo> receivers =
                context.getPackageManager().queryBroadcastReceivers(uploadIntent, 0);
        if (receivers == null || receivers.isEmpty()) {
            Log.e(LOG_TAG, "No one to receive upload intent, abort upload.");
            return;
        }

        context.grantUriPermission(uploaderPkg, reportUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.sendBroadcast(uploadIntent);
    }

    private String getUploaderPackageName(Context context) {
        return context.getResources().getString(
                R.string.config_defaultProfcollectReportUploaderApp);
    }

    private String getUploaderActionName(Context context) {
        return context.getResources().getString(
                R.string.config_defaultProfcollectReportUploaderAction);
    }
}

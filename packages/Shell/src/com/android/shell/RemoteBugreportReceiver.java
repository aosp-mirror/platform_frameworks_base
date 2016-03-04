/*
 * Copyright (C) 2015 The Android Open Source Project
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

import static com.android.shell.BugreportProgressService.EXTRA_BUGREPORT;
import static com.android.shell.BugreportProgressService.INTENT_REMOTE_BUGREPORT_FINISHED;
import static com.android.shell.BugreportProgressService.getFileExtra;
import static com.android.shell.BugreportProgressService.getUri;
import static com.android.shell.BugreportReceiver.cleanupOldFiles;

import java.io.File;

import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.UserHandle;
import android.text.format.DateUtils;

/**
 * Receiver that handles finished remote bugreports, by re-sending
 * the intent with appended bugreport zip file URI.
 *
 * <p> Remote bugreport never contains a screenshot.
 */
public class RemoteBugreportReceiver extends BroadcastReceiver {

    private static final String BUGREPORT_MIMETYPE = "application/vnd.android.bugreport";

    /** Always keep just the last remote bugreport's files around. */
    private static final int REMOTE_BUGREPORT_FILES_AMOUNT = 3;

    /** Always keep remote bugreport files created in the last day. */
    private static final long MIN_KEEP_AGE = DateUtils.DAY_IN_MILLIS;

    @Override
    public void onReceive(Context context, Intent intent) {
        cleanupOldFiles(this, intent, INTENT_REMOTE_BUGREPORT_FINISHED,
                REMOTE_BUGREPORT_FILES_AMOUNT, MIN_KEEP_AGE);

        final File bugreportFile = getFileExtra(intent, EXTRA_BUGREPORT);
        final Uri bugreportUri = getUri(context, bugreportFile);
        final String bugreportHash = intent.getStringExtra(
                DevicePolicyManager.EXTRA_REMOTE_BUGREPORT_HASH);

        final Intent newIntent = new Intent(DevicePolicyManager.ACTION_REMOTE_BUGREPORT_DISPATCH);
        newIntent.setDataAndType(bugreportUri, BUGREPORT_MIMETYPE);
        newIntent.putExtra(DevicePolicyManager.EXTRA_REMOTE_BUGREPORT_HASH, bugreportHash);
        context.sendBroadcastAsUser(newIntent, UserHandle.SYSTEM,
                android.Manifest.permission.DUMP);
    }
}

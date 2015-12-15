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
import static com.android.shell.BugreportProgressService.INTENT_REMOTE_BUGREPORT_DISPATCH;
import static com.android.shell.BugreportProgressService.getFileExtra;
import static com.android.shell.BugreportProgressService.getUri;
import static com.android.shell.BugreportReceiver.cleanupOldFiles;

import java.io.File;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.UserHandle;

/**
 * Receiver that handles finished remote bugreports, by re-sending
 * the intent with appended bugreport zip file URI.
 *
 * <p> Remote bugreport never contains a screenshot.
 */
public class RemoteBugreportReceiver extends BroadcastReceiver {

    private static final String BUGREPORT_MIMETYPE = "application/vnd.android.bugreport";
    private static final String EXTRA_REMOTE_BUGREPORT_HASH =
            "android.intent.extra.REMOTE_BUGREPORT_HASH";

    /** Always keep just the last remote bugreport zip file */
    private static final int MIN_KEEP_COUNT = 1;

    @Override
    public void onReceive(Context context, Intent intent) {
        cleanupOldFiles(this, intent, INTENT_REMOTE_BUGREPORT_FINISHED, MIN_KEEP_COUNT, 0);

        final File bugreportFile = getFileExtra(intent, EXTRA_BUGREPORT);
        final Uri bugreportUri = getUri(context, bugreportFile);
        final String bugreportHash = intent.getStringExtra(EXTRA_REMOTE_BUGREPORT_HASH);

        final Intent newIntent = new Intent(INTENT_REMOTE_BUGREPORT_DISPATCH);
        newIntent.setDataAndType(bugreportUri, BUGREPORT_MIMETYPE);
        newIntent.putExtra(EXTRA_REMOTE_BUGREPORT_HASH, bugreportHash);
        context.sendBroadcastAsUser(newIntent, UserHandle.SYSTEM,
                android.Manifest.permission.DUMP);
    }
}

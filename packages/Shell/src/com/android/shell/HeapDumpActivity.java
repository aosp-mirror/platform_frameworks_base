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

import static com.android.shell.HeapDumpProvider.makeUri;
import static com.android.shell.HeapDumpReceiver.ACTION_DELETE_HEAP_DUMP;
import static com.android.shell.HeapDumpReceiver.EXTRA_IS_USER_INITIATED;
import static com.android.shell.HeapDumpReceiver.EXTRA_PROCESS_NAME;
import static com.android.shell.HeapDumpReceiver.EXTRA_REPORT_PACKAGE;
import static com.android.shell.HeapDumpReceiver.EXTRA_SIZE_BYTES;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.util.DebugUtils;
import android.util.Log;

import com.android.internal.R;

/**
 * This activity is displayed when the system has collected a heap dump.
 */
public class HeapDumpActivity extends Activity {
    private static final String TAG = "HeapDumpActivity";

    static final String KEY_URI = "uri";

    private AlertDialog mDialog;
    private Uri mDumpUri;
    private boolean mHandled = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String process = getIntent().getStringExtra(EXTRA_PROCESS_NAME);
        long size = getIntent().getLongExtra(EXTRA_SIZE_BYTES, 0);
        final boolean isUserInitiated = getIntent().getBooleanExtra(EXTRA_IS_USER_INITIATED, false);
        final int uid = getIntent().getIntExtra(Intent.EXTRA_UID, 0);
        final boolean isSystemProcess = uid == Process.SYSTEM_UID;
        mDumpUri = makeUri(process);
        final String procDisplayName = isSystemProcess
                ? getString(com.android.internal.R.string.android_system_label)
                : process;

        final Intent sendIntent = new Intent();
        ClipData clip = ClipData.newUri(getContentResolver(), "Heap Dump", mDumpUri);
        sendIntent.setClipData(clip);
        sendIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        sendIntent.setType(clip.getDescription().getMimeType(0));
        sendIntent.putExtra(Intent.EXTRA_STREAM, mDumpUri);

        String directLaunchPackage = getIntent().getStringExtra(EXTRA_REPORT_PACKAGE);
        if (directLaunchPackage != null) {
            sendIntent.setAction(ActivityManager.ACTION_REPORT_HEAP_LIMIT);
            sendIntent.setPackage(directLaunchPackage);
            try {
                startActivity(sendIntent);
                mHandled = true;
                finish();
                return;
            } catch (ActivityNotFoundException e) {
                Log.e(TAG, "Unable to direct launch to " + directLaunchPackage, e);
            }
        }

        final int messageId;
        if (isUserInitiated) {
            messageId = com.android.internal.R.string.dump_heap_ready_text;
        } else if (isSystemProcess) {
            messageId = com.android.internal.R.string.dump_heap_system_text;
        } else {
            messageId = com.android.internal.R.string.dump_heap_text;
        }
        mDialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle(com.android.internal.R.string.dump_heap_title)
                .setMessage(getString(messageId, procDisplayName,
                        DebugUtils.sizeValueToString(size, null)))
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                    mHandled = true;
                    finish();
                })
                .setNeutralButton(R.string.delete, (dialog, which) -> {
                    mHandled = true;
                    Intent deleteIntent = new Intent(ACTION_DELETE_HEAP_DUMP);
                    deleteIntent.setClass(getApplicationContext(), HeapDumpReceiver.class);
                    deleteIntent.putExtra(KEY_URI, mDumpUri.toString());
                    sendBroadcast(deleteIntent);
                    finish();
                })
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    mHandled = true;
                    sendIntent.setAction(Intent.ACTION_SEND);
                    sendIntent.setPackage(null);
                    startActivity(Intent.createChooser(sendIntent,
                            getText(com.android.internal.R.string.dump_heap_title)));
                    finish();
                })
                .show();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations()) {
            if (!mHandled) {
                Intent deleteIntent = new Intent(ACTION_DELETE_HEAP_DUMP);
                deleteIntent.setClass(getApplicationContext(), HeapDumpReceiver.class);
                deleteIntent.putExtra(KEY_URI, mDumpUri.toString());
                sendBroadcast(deleteIntent);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mDialog != null) {
            mDialog.dismiss();
        }
    }
}

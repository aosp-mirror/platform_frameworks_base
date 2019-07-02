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

package com.android.internal.app;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.DebugUtils;
import android.util.Slog;

/**
 * This activity is displayed when the system has collected a heap dump from
 * a large process and the user has selected to share it.
 */
public class DumpHeapActivity extends Activity {
    /** The process we are reporting */
    public static final String KEY_PROCESS = "process";
    /** The size limit the process reached */
    public static final String KEY_SIZE = "size";
    /** Whether the user initiated the dump or not. */
    public static final String KEY_IS_USER_INITIATED = "is_user_initiated";
    /** Whether the process is a system process (eg: Android System) or not. */
    public static final String KEY_IS_SYSTEM_PROCESS = "is_system_process";
    /** Optional name of package to directly launch */
    public static final String KEY_DIRECT_LAUNCH = "direct_launch";

    // Broadcast action to determine when to delete the current dump heap data.
    public static final String ACTION_DELETE_DUMPHEAP = "com.android.server.am.DELETE_DUMPHEAP";

    // Extra for above: delay delete of data, since the user is in the process of sharing it.
    public static final String EXTRA_DELAY_DELETE = "delay_delete";

    static final public Uri JAVA_URI = Uri.parse("content://com.android.server.heapdump/java");

    String mProcess;
    long mSize;
    AlertDialog mDialog;
    boolean mHandled = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mProcess = getIntent().getStringExtra(KEY_PROCESS);
        mSize = getIntent().getLongExtra(KEY_SIZE, 0);
        final boolean isUserInitiated = getIntent().getBooleanExtra(KEY_IS_USER_INITIATED, false);
        final boolean isSystemProcess = getIntent().getBooleanExtra(KEY_IS_SYSTEM_PROCESS, false);

        String directLaunch = getIntent().getStringExtra(KEY_DIRECT_LAUNCH);
        if (directLaunch != null) {
            Intent intent = new Intent(ActivityManager.ACTION_REPORT_HEAP_LIMIT);
            intent.setPackage(directLaunch);
            ClipData clip = ClipData.newUri(getContentResolver(), "Heap Dump", JAVA_URI);
            intent.setClipData(clip);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setType(clip.getDescription().getMimeType(0));
            intent.putExtra(Intent.EXTRA_STREAM, JAVA_URI);
            try {
                startActivity(intent);
                scheduleDelete();
                mHandled = true;
                finish();
                return;
            } catch (ActivityNotFoundException e) {
                Slog.i("DumpHeapActivity", "Unable to direct launch to " + directLaunch
                        + ": " + e.getMessage());
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
        AlertDialog.Builder b = new AlertDialog.Builder(this,
                android.R.style.Theme_Material_Light_Dialog_Alert);
        b.setTitle(com.android.internal.R.string.dump_heap_title);
        b.setMessage(getString(
                messageId, mProcess, DebugUtils.sizeValueToString(mSize, null)));
        b.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                mHandled = true;
                sendBroadcast(new Intent(ACTION_DELETE_DUMPHEAP));
                finish();
            }
        });
        b.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                mHandled = true;
                scheduleDelete();
                Intent intent = new Intent(Intent.ACTION_SEND);
                ClipData clip = ClipData.newUri(getContentResolver(), "Heap Dump", JAVA_URI);
                intent.setClipData(clip);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.setType(clip.getDescription().getMimeType(0));
                intent.putExtra(Intent.EXTRA_STREAM, JAVA_URI);
                startActivity(Intent.createChooser(intent,
                        getText(com.android.internal.R.string.dump_heap_title)));
                finish();
        }
        });
        mDialog = b.show();
    }

    void scheduleDelete() {
        Intent broadcast = new Intent(ACTION_DELETE_DUMPHEAP);
        broadcast.putExtra(EXTRA_DELAY_DELETE, true);
        sendBroadcast(broadcast);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations()) {
            if (!mHandled) {
                sendBroadcast(new Intent(ACTION_DELETE_DUMPHEAP));
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mDialog.dismiss();
    }
}

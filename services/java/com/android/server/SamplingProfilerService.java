/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.server;

import android.content.ContentResolver;
import android.os.DropBoxManager;
import android.os.FileObserver;
import android.os.Binder;

import android.util.Slog;
import android.content.Context;
import android.database.ContentObserver;
import android.os.SystemProperties;
import android.provider.Settings;
import com.android.internal.os.SamplingProfilerIntegration;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;

public class SamplingProfilerService extends Binder {

    private static final String TAG = "SamplingProfilerService";
    private static final boolean LOCAL_LOGV = false;
    public static final String SNAPSHOT_DIR = SamplingProfilerIntegration.SNAPSHOT_DIR;

    private FileObserver snapshotObserver;

    public SamplingProfilerService(Context context) {
        registerSettingObserver(context);
        startWorking(context);
    }

    private void startWorking(Context context) {
        if (LOCAL_LOGV) Slog.v(TAG, "starting SamplingProfilerService!");

        final DropBoxManager dropbox =
                (DropBoxManager) context.getSystemService(Context.DROPBOX_SERVICE);

        // before FileObserver is ready, there could have already been some snapshots
        // in the directory, we don't want to miss them
        File[] snapshotFiles = new File(SNAPSHOT_DIR).listFiles();
        for (int i = 0; snapshotFiles != null && i < snapshotFiles.length; i++) {
            handleSnapshotFile(snapshotFiles[i], dropbox);
        }

        // detect new snapshot and put it in dropbox
        // delete it afterwards no matter what happened before
        // Note: needs listening at event ATTRIB rather than CLOSE_WRITE, because we set the
        // readability of snapshot files after writing them!
        snapshotObserver = new FileObserver(SNAPSHOT_DIR, FileObserver.ATTRIB) {
            @Override
            public void onEvent(int event, String path) {
                handleSnapshotFile(new File(SNAPSHOT_DIR, path), dropbox);
            }
        };
        snapshotObserver.startWatching();

        if (LOCAL_LOGV) Slog.v(TAG, "SamplingProfilerService activated");
    }

    private void handleSnapshotFile(File file, DropBoxManager dropbox) {
        try {
            dropbox.addFile(TAG, file, 0);
            if (LOCAL_LOGV) Slog.v(TAG, file.getPath() + " added to dropbox");
        } catch (IOException e) {
            Slog.e(TAG, "Can't add " + file.getPath() + " to dropbox", e);
        } finally {
            file.delete();
        }
    }

    private void registerSettingObserver(Context context) {
        ContentResolver contentResolver = context.getContentResolver();
        contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.SAMPLING_PROFILER_MS),
                false, new SamplingProfilerSettingsObserver(contentResolver));
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("SamplingProfilerService:");
        pw.println("Watching directory: " + SNAPSHOT_DIR);
    }

    private class SamplingProfilerSettingsObserver extends ContentObserver {
        private ContentResolver mContentResolver;
        public SamplingProfilerSettingsObserver(ContentResolver contentResolver) {
            super(null);
            mContentResolver = contentResolver;
            onChange(false);
        }
        @Override
        public void onChange(boolean selfChange) {
            Integer samplingProfilerMs = Settings.Secure.getInt(
                    mContentResolver, Settings.Secure.SAMPLING_PROFILER_MS, 0);
            // setting this secure property will start or stop sampling profiler,
            // as well as adjust the the time between taking snapshots.
            SystemProperties.set("persist.sys.profiler_ms", samplingProfilerMs.toString());
        }
    }
}

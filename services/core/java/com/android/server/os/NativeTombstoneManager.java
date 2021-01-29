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

package com.android.server.os;

import static android.os.Process.THREAD_PRIORITY_BACKGROUND;

import android.annotation.Nullable;
import android.content.Context;
import android.os.FileObserver;
import android.os.Handler;

import com.android.server.BootReceiver;
import com.android.server.ServiceThread;

import java.io.File;

/**
 * A class to manage native tombstones.
 */
public final class NativeTombstoneManager {
    private static final String TAG = NativeTombstoneManager.class.getSimpleName();

    private static final File TOMBSTONE_DIR = new File("/data/tombstones");

    private final Context mContext;
    private final Handler mHandler;
    private final TombstoneWatcher mWatcher;

    NativeTombstoneManager(Context context) {
        mContext = context;

        final ServiceThread thread = new ServiceThread(TAG + ":tombstoneWatcher",
                THREAD_PRIORITY_BACKGROUND, true /* allowIo */);
        thread.start();
        mHandler = thread.getThreadHandler();

        mWatcher = new TombstoneWatcher();
        mWatcher.startWatching();
    }

    void onSystemReady() {
        // Scan existing tombstones.
        mHandler.post(() -> {
            final File[] tombstoneFiles = TOMBSTONE_DIR.listFiles();
            for (int i = 0; tombstoneFiles != null && i < tombstoneFiles.length; i++) {
                if (tombstoneFiles[i].isFile()) {
                    handleTombstone(tombstoneFiles[i]);
                }
            }
        });
    }

    private void handleTombstone(File path) {
        final String filename = path.getName();
        if (!filename.startsWith("tombstone_")) {
            return;
        }

        BootReceiver.addTombstoneToDropBox(mContext, path);
    }

    class TombstoneWatcher extends FileObserver {
        TombstoneWatcher() {
            // Tombstones can be created either by linking an O_TMPFILE temporary file (CREATE),
            // or by moving a named temporary file in the same directory on kernels where O_TMPFILE
            // isn't supported (MOVED_TO).
            super(TOMBSTONE_DIR, FileObserver.CREATE | FileObserver.MOVED_TO);
        }

        @Override
        public void onEvent(int event, @Nullable String path) {
            mHandler.post(() -> {
                handleTombstone(new File(TOMBSTONE_DIR, path));
            });
        }
    }
}

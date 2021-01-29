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

import static android.os.ParcelFileDescriptor.MODE_READ_WRITE;
import static android.os.Process.THREAD_PRIORITY_BACKGROUND;

import android.annotation.AppIdInt;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.Context;
import android.os.FileObserver;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;
import android.util.Slog;
import android.util.SparseArray;
import android.util.proto.ProtoInputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.server.BootReceiver;
import com.android.server.ServiceThread;
import com.android.server.os.TombstoneProtos.Tombstone;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Optional;

/**
 * A class to manage native tombstones.
 */
public final class NativeTombstoneManager {
    private static final String TAG = NativeTombstoneManager.class.getSimpleName();

    private static final File TOMBSTONE_DIR = new File("/data/tombstones");

    private final Context mContext;
    private final Handler mHandler;
    private final TombstoneWatcher mWatcher;

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final SparseArray<TombstoneFile> mTombstones;

    NativeTombstoneManager(Context context) {
        mTombstones = new SparseArray<TombstoneFile>();
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

        if (filename.endsWith(".pb")) {
            handleProtoTombstone(path);
        } else {
            BootReceiver.addTombstoneToDropBox(mContext, path);
        }
    }

    private void handleProtoTombstone(File path) {
        final String filename = path.getName();
        if (!filename.endsWith(".pb")) {
            Slog.w(TAG, "unexpected tombstone name: " + path);
            return;
        }

        final String suffix = filename.substring("tombstone_".length());
        final String numberStr = suffix.substring(0, suffix.length() - 3);

        int number;
        try {
            number = Integer.parseInt(numberStr);
            if (number < 0 || number > 99) {
                Slog.w(TAG, "unexpected tombstone name: " + path);
                return;
            }
        } catch (NumberFormatException ex) {
            Slog.w(TAG, "unexpected tombstone name: " + path);
            return;
        }

        ParcelFileDescriptor pfd;
        try {
            pfd = ParcelFileDescriptor.open(path, MODE_READ_WRITE);
        } catch (FileNotFoundException ex) {
            Slog.w(TAG, "failed to open " + path, ex);
            return;
        }

        final Optional<TombstoneFile> parsedTombstone = TombstoneFile.parse(pfd);
        if (!parsedTombstone.isPresent()) {
            IoUtils.closeQuietly(pfd);
            return;
        }

        synchronized (mLock) {
            TombstoneFile previous = mTombstones.get(number);
            if (previous != null) {
                previous.dispose();
            }

            mTombstones.put(number, parsedTombstone.get());
        }
    }

    static class TombstoneFile {
        final ParcelFileDescriptor mPfd;

        final @UserIdInt int mUserId;
        final @AppIdInt int mAppId;

        boolean mPurged = false;

        TombstoneFile(ParcelFileDescriptor pfd, @UserIdInt int userId, @AppIdInt int appId) {
            mPfd = pfd;
            mUserId = userId;
            mAppId = appId;
        }

        public boolean matches(Optional<Integer> userId, Optional<Integer> appId) {
            if (mPurged) {
                return false;
            }

            if (userId.isPresent() && userId.get() != mUserId) {
                return false;
            }

            if (appId.isPresent() && appId.get() != mAppId) {
                return false;
            }

            return true;
        }

        public void dispose() {
            IoUtils.closeQuietly(mPfd);
        }

        static Optional<TombstoneFile> parse(ParcelFileDescriptor pfd) {
            final FileInputStream is = new FileInputStream(pfd.getFileDescriptor());
            final ProtoInputStream stream = new ProtoInputStream(is);

            int uid = 0;
            String selinuxLabel = "";

            try {
                while (stream.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
                    switch (stream.getFieldNumber()) {
                        case (int) Tombstone.UID:
                            uid = stream.readInt(Tombstone.UID);
                            break;

                        case (int) Tombstone.SELINUX_LABEL:
                            selinuxLabel = stream.readString(Tombstone.SELINUX_LABEL);
                            break;

                        default:
                            break;
                    }
                }
            } catch (IOException ex) {
                Slog.e(TAG, "Failed to parse tombstone", ex);
                return Optional.empty();
            }

            if (!UserHandle.isApp(uid)) {
                Slog.e(TAG, "Tombstone's UID (" + uid + ") not an app, ignoring");
                return Optional.empty();
            }

            final int userId = UserHandle.getUserId(uid);
            final int appId = UserHandle.getAppId(uid);

            if (!selinuxLabel.startsWith("u:r:untrusted_app")) {
                Slog.e(TAG, "Tombstone has invalid selinux label (" + selinuxLabel + "), ignoring");
                return Optional.empty();
            }

            return Optional.of(new TombstoneFile(pfd, userId, appId));
        }
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

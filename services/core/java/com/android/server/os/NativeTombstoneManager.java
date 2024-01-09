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

import static android.app.ApplicationExitInfo.REASON_CRASH_NATIVE;
import static android.os.ParcelFileDescriptor.MODE_READ_ONLY;
import static android.os.ParcelFileDescriptor.MODE_READ_WRITE;
import static android.os.Process.THREAD_PRIORITY_BACKGROUND;

import android.annotation.AppIdInt;
import android.annotation.CurrentTimeMillisLong;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.ApplicationExitInfo;
import android.app.IParcelFileDescriptorRetriever;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.FileObserver;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.server.BootReceiver;
import com.android.server.ServiceThread;
import com.android.server.os.TombstoneProtos.Cause;
import com.android.server.os.TombstoneProtos.Tombstone;
import com.android.server.os.protobuf.CodedInputStream;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A class to manage native tombstones.
 */
public final class NativeTombstoneManager {
    private static final String TAG = NativeTombstoneManager.class.getSimpleName();

    private static final File TOMBSTONE_DIR = new File("/data/tombstones");

    private final Context mContext;
    private final Handler mHandler;
    private final TombstoneWatcher mWatcher;

    private final ReentrantLock mTmpFileLock = new ReentrantLock();

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
        registerForUserRemoval();
        registerForPackageRemoval();

        BootReceiver.initDropboxRateLimiter();

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

        // Clean up temporary files if they made it this far (e.g. if system server crashes).
        if (filename.endsWith(".tmp")) {
            mTmpFileLock.lock();
            try {
                path.delete();
            } finally {
                mTmpFileLock.unlock();
            }
            return;
        }

        if (!filename.startsWith("tombstone_")) {
            return;
        }

        final boolean isProtoFile = filename.endsWith(".pb");
        if (!isProtoFile) {
            return;
        }

        Optional<ParsedTombstone> parsedTombstone = handleProtoTombstone(path, true);
        if (parsedTombstone.isPresent()) {
            BootReceiver.addTombstoneToDropBox(
                    mContext, path, parsedTombstone.get().getTombstone(),
                    parsedTombstone.get().getProcessName(), mTmpFileLock);
        }
    }

    private Optional<ParsedTombstone> handleProtoTombstone(
            File path, boolean addToList) {
        final String filename = path.getName();
        if (!filename.endsWith(".pb")) {
            Slog.w(TAG, "unexpected tombstone name: " + path);
            return Optional.empty();
        }

        final String suffix = filename.substring("tombstone_".length());
        final String numberStr = suffix.substring(0, suffix.length() - 3);

        int number;
        try {
            number = Integer.parseInt(numberStr);
            if (number < 0 || number > 99) {
                Slog.w(TAG, "unexpected tombstone name: " + path);
                return Optional.empty();
            }
        } catch (NumberFormatException ex) {
            Slog.w(TAG, "unexpected tombstone name: " + path);
            return Optional.empty();
        }

        ParcelFileDescriptor pfd;
        try {
            pfd = ParcelFileDescriptor.open(path, MODE_READ_WRITE);
        } catch (FileNotFoundException ex) {
            Slog.w(TAG, "failed to open " + path, ex);
            return Optional.empty();
        }

        final Optional<ParsedTombstone> parsedTombstone = TombstoneFile.parse(pfd);
        if (!parsedTombstone.isPresent()) {
            IoUtils.closeQuietly(pfd);
            return Optional.empty();
        }

        if (addToList) {
            synchronized (mLock) {
                TombstoneFile previous = mTombstones.get(number);
                if (previous != null) {
                    previous.dispose();
                }

                mTombstones.put(number, parsedTombstone.get().getTombstoneFile());
            }
        }

        return parsedTombstone;
    }

    /**
     * Remove native tombstones matching a user and/or app.
     *
     * @param userId user id to filter by, selects all users if empty
     * @param appId app id to filter by, selects all users if empty
     */
    public void purge(Optional<Integer> userId, Optional<Integer> appId) {
        mHandler.post(() -> {
            synchronized (mLock) {
                for (int i = mTombstones.size() - 1; i >= 0; --i) {
                    TombstoneFile tombstone = mTombstones.valueAt(i);
                    if (tombstone.matches(userId, appId)) {
                        tombstone.purge();
                        mTombstones.removeAt(i);
                    }
                }
            }
        });
    }

    private void purgePackage(int uid, boolean allUsers) {
        final int appId = UserHandle.getAppId(uid);
        Optional<Integer> userId;
        if (allUsers) {
            userId = Optional.empty();
        } else {
            userId = Optional.of(UserHandle.getUserId(uid));
        }
        purge(userId, Optional.of(appId));
    }

    private void purgeUser(int uid) {
        purge(Optional.of(uid), Optional.empty());
    }

    private void registerForPackageRemoval() {
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        filter.addDataScheme("package");
        mContext.registerReceiverForAllUsers(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final int uid = intent.getIntExtra(Intent.EXTRA_UID, UserHandle.USER_NULL);
                if (uid == UserHandle.USER_NULL) return;

                final boolean allUsers = intent.getBooleanExtra(
                        Intent.EXTRA_REMOVED_FOR_ALL_USERS, false);

                purgePackage(uid, allUsers);
            }
        }, filter, null, mHandler);
    }

    private void registerForUserRemoval() {
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_REMOVED);
        mContext.registerReceiverForAllUsers(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                if (userId < 1) return;

                purgeUser(userId);
            }
        }, filter, null, mHandler);
    }

    /**
     * Collect native tombstones.
     *
     * @param output list to append to
     * @param callingUid POSIX uid to filter by
     * @param pid pid to filter by, ignored if zero
     * @param maxNum maximum number of elements in output
     */
    public void collectTombstones(ArrayList<ApplicationExitInfo> output, int callingUid, int pid,
            int maxNum) {
        CompletableFuture<Object> future = new CompletableFuture<>();

        if (!UserHandle.isApp(callingUid)) {
            return;
        }

        final int userId = UserHandle.getUserId(callingUid);
        final int appId = UserHandle.getAppId(callingUid);

        mHandler.post(() -> {
            boolean appendedTombstones = false;

            synchronized (mLock) {
                final int tombstonesSize = mTombstones.size();

            tombstoneIter:
                for (int i = 0; i < tombstonesSize; ++i) {
                    TombstoneFile tombstone = mTombstones.valueAt(i);
                    if (tombstone.matches(Optional.of(userId), Optional.of(appId))) {
                        if (pid != 0 && tombstone.mPid != pid) {
                            continue;
                        }

                        // Try to attach to an existing REASON_CRASH_NATIVE.
                        final int outputSize = output.size();
                        for (int j = 0; j < outputSize; ++j) {
                            ApplicationExitInfo exitInfo = output.get(j);
                            if (tombstone.matches(exitInfo)) {
                                exitInfo.setNativeTombstoneRetriever(tombstone.getPfdRetriever());
                                continue tombstoneIter;
                            }
                        }

                        if (output.size() < maxNum) {
                            appendedTombstones = true;
                            output.add(tombstone.toAppExitInfo());
                        }
                    }
                }
            }

            if (appendedTombstones) {
                Collections.sort(output, (lhs, rhs) -> {
                    // Reports should be ordered with newest reports first.
                    long diff = rhs.getTimestamp() - lhs.getTimestamp();
                    if (diff < 0) {
                        return -1;
                    } else if (diff == 0) {
                        return 0;
                    } else {
                        return 1;
                    }
                });
            }
            future.complete(null);
        });

        try {
            future.get();
        } catch (ExecutionException | InterruptedException ex) {
            throw new RuntimeException(ex);
        }
    }

    static class ParsedTombstone {
        TombstoneFile mTombstoneFile;
        Tombstone mTombstone;
        ParsedTombstone(TombstoneFile tombstoneFile, Tombstone tombstone) {
            mTombstoneFile = tombstoneFile;
            mTombstone = tombstone;
        }

        public String getProcessName() {
            return mTombstoneFile.getProcessName();
        }

        public TombstoneFile getTombstoneFile() {
            return mTombstoneFile;
        }

        public Tombstone getTombstone() {
            return mTombstone;
        }
    }

    static class TombstoneFile {
        final ParcelFileDescriptor mPfd;

        @UserIdInt int mUserId;
        @AppIdInt int mAppId;

        int mPid;
        int mUid;
        String mProcessName;
        @CurrentTimeMillisLong long mTimestampMs;
        String mCrashReason;

        boolean mPurged = false;
        final IParcelFileDescriptorRetriever mRetriever = new ParcelFileDescriptorRetriever();

        TombstoneFile(ParcelFileDescriptor pfd) {
            mPfd = pfd;
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

        public boolean matches(ApplicationExitInfo exitInfo) {
            if (exitInfo.getReason() != REASON_CRASH_NATIVE) {
                return false;
            }

            if (exitInfo.getPid() != mPid) {
                return false;
            }

            if (exitInfo.getRealUid() != mUid) {
                return false;
            }

            if (Math.abs(exitInfo.getTimestamp() - mTimestampMs) > 10000) {
                return false;
            }

            return true;
        }

        public String getProcessName() {
            return mProcessName;
        }

        public void dispose() {
            IoUtils.closeQuietly(mPfd);
        }

        public void purge() {
            if (!mPurged) {
                // There's no way to atomically unlink a specific file for which we have an fd from
                // a path, which means that we can't safely delete a tombstone without coordination
                // with tombstoned (which has a risk of deadlock if for example, system_server hangs
                // with a flock). Do the next best thing, and just truncate the file.
                //
                // We don't have to worry about inflicting a SIGBUS on a process that has the
                // tombstone mmaped, because we only clear if the package has been removed, which
                // means no one with access to the tombstone should be left.
                try {
                    Os.ftruncate(mPfd.getFileDescriptor(), 0);
                } catch (ErrnoException ex) {
                    Slog.e(TAG, "Failed to truncate tombstone", ex);
                }
                mPurged = true;
            }
        }

        static Optional<ParsedTombstone> parse(ParcelFileDescriptor pfd) {
            Tombstone tombstoneProto;
            try (FileInputStream is = new FileInputStream(pfd.getFileDescriptor())) {
                final byte[] tombstoneBytes = is.readAllBytes();

                tombstoneProto = Tombstone.parseFrom(
                        CodedInputStream.newInstance(tombstoneBytes));
            } catch (IOException ex) {
                Slog.e(TAG, "Failed to parse tombstone", ex);
                return Optional.empty();
            }

            int pid = tombstoneProto.getPid();
            int uid = tombstoneProto.getUid();

            if (!UserHandle.isApp(uid)) {
                Slog.e(TAG, "Tombstone's UID (" + uid + ") not an app, ignoring");
                return Optional.empty();
            }

            long timestampMs = 0;
            try {
                StructStat stat = Os.fstat(pfd.getFileDescriptor());
                timestampMs = stat.st_atim.tv_sec * 1000 + stat.st_atim.tv_nsec / 1000000;
            } catch (ErrnoException ex) {
                Slog.e(TAG, "Failed to get timestamp of tombstone", ex);
            }

            final int userId = UserHandle.getUserId(uid);
            final int appId = UserHandle.getAppId(uid);

            String selinuxLabel = tombstoneProto.getSelinuxLabel();
            if (!selinuxLabel.startsWith("u:r:untrusted_app")) {
                Slog.e(TAG, "Tombstone has invalid selinux label (" + selinuxLabel + "), ignoring");
                return Optional.empty();
            }

            TombstoneFile result = new TombstoneFile(pfd);

            result.mUserId = userId;
            result.mAppId = appId;
            result.mPid = pid;
            result.mUid = uid;
            result.mProcessName = getCmdLineProcessName(tombstoneProto);
            result.mTimestampMs = timestampMs;
            result.mCrashReason = getCrashReason(tombstoneProto);

            return Optional.of(new ParsedTombstone(result, tombstoneProto));
        }

        private static String getCmdLineProcessName(Tombstone tombstoneProto) {
            for (String cmdline : tombstoneProto.getCommandLineList()) {
                if (cmdline != null) {
                    return cmdline;
                }
            }
            return "";
        }

        private static String getCrashReason(Tombstone tombstoneProto) {
            for (Cause cause : tombstoneProto.getCausesList()) {
                if (cause.getHumanReadable() != null
                        && !cause.getHumanReadable().equals("")) {
                    return cause.getHumanReadable();
                }
            }
            return "";
        }

        public IParcelFileDescriptorRetriever getPfdRetriever() {
            return mRetriever;
        }

        public ApplicationExitInfo toAppExitInfo() {
            ApplicationExitInfo info = new ApplicationExitInfo();
            info.setPid(mPid);
            info.setRealUid(mUid);
            info.setPackageUid(mUid);
            info.setDefiningUid(mUid);
            info.setProcessName(mProcessName);
            info.setReason(ApplicationExitInfo.REASON_CRASH_NATIVE);

            // Signal numbers are architecture-specific!
            // We choose to provide nothing here, to avoid leading users astray.
            info.setStatus(0);

            // No way for us to find out.
            info.setImportance(RunningAppProcessInfo.IMPORTANCE_GONE);
            info.setPackageName("");
            info.setProcessStateSummary(null);

            // We could find out, but they didn't get OOM-killed...
            info.setPss(0);
            info.setRss(0);

            info.setTimestamp(mTimestampMs);
            info.setDescription(mCrashReason);

            info.setSubReason(ApplicationExitInfo.SUBREASON_UNKNOWN);
            info.setNativeTombstoneRetriever(mRetriever);

            return info;
        }


        class ParcelFileDescriptorRetriever extends IParcelFileDescriptorRetriever.Stub {
            ParcelFileDescriptorRetriever() {}

            public @Nullable ParcelFileDescriptor getPfd() {
                if (mPurged) {
                    return null;
                }

                // Reopen the file descriptor as read-only.
                try {
                    final String path = "/proc/self/fd/" + mPfd.getFd();
                    ParcelFileDescriptor pfd = ParcelFileDescriptor.open(new File(path),
                            MODE_READ_ONLY);
                    return pfd;
                } catch (FileNotFoundException ex) {
                    Slog.e(TAG, "failed to reopen file descriptor as read-only", ex);
                    return null;
                }
            }
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
            if (path == null) {
                Slog.w(TAG, "path is null at TombstoneWatcher.onEvent()");
                return;
            }
            mHandler.post(() -> {
                // Ignore .tmp files.
                if (path.endsWith(".tmp")) {
                    return;
                }
                handleTombstone(new File(TOMBSTONE_DIR, path));
            });
        }
    }
}

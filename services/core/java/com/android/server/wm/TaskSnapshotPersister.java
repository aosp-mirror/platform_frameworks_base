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

package com.android.server.wm;

import android.annotation.NonNull;
import android.util.ArraySet;
import android.window.TaskSnapshot;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.File;
import java.util.Arrays;

/**
 * Persists {@link TaskSnapshot}s to disk.
 * <p>
 * Test class: {@link TaskSnapshotPersisterLoaderTest}
 */
class TaskSnapshotPersister {

    private static final String LOW_RES_FILE_POSTFIX = "_reduced";
    private static final String PROTO_EXTENSION = ".proto";
    private static final String BITMAP_EXTENSION = ".jpg";

    // Shared with SnapshotPersistQueue
    private final Object mLock;

    /**
     * The list of ids of the tasks that have been persisted since {@link #removeObsoleteFiles} was
     * called.
     */
    @GuardedBy("mLock")
    private final ArraySet<Integer> mPersistedTaskIdsSinceLastRemoveObsolete = new ArraySet<>();

    private final SnapshotPersistQueue mSnapshotPersistQueue;
    private final PersistInfoProvider mPersistInfoProvider;
    TaskSnapshotPersister(SnapshotPersistQueue persistQueue,
            PersistInfoProvider persistInfoProvider) {
        mSnapshotPersistQueue = persistQueue;
        mPersistInfoProvider = persistInfoProvider;
        mLock = persistQueue.getLock();
    }

    interface DirectoryResolver {
        File getSystemDirectoryForUser(int userId);
    }
    /**
     * Persist information provider, the snapshot persister and loader can know where the file is,
     * and the scale of a snapshot, etc.
     */
    static class PersistInfoProvider {
        protected final DirectoryResolver mDirectoryResolver;
        private final String mDirName;
        private final boolean mEnableLowResSnapshots;
        private final float mLowResScaleFactor;
        private final boolean mUse16BitFormat;

        PersistInfoProvider(DirectoryResolver directoryResolver, String dirName,
                boolean enableLowResSnapshots, float lowResScaleFactor, boolean use16BitFormat) {
            mDirectoryResolver = directoryResolver;
            mDirName = dirName;
            mEnableLowResSnapshots = enableLowResSnapshots;
            mLowResScaleFactor = lowResScaleFactor;
            mUse16BitFormat = use16BitFormat;
        }

        @NonNull
        File getDirectory(int userId) {
            return new File(mDirectoryResolver.getSystemDirectoryForUser(userId), mDirName);
        }

        /**
         * Return if task snapshots are stored in 16 bit pixel format.
         *
         * @return true if task snapshots are stored in 16 bit pixel format.
         */
        boolean use16BitFormat() {
            return mUse16BitFormat;
        }

        boolean createDirectory(int userId) {
            final File dir = getDirectory(userId);
            return dir.exists() || dir.mkdir();
        }

        File getProtoFile(int index, int userId) {
            return new File(getDirectory(userId), index + PROTO_EXTENSION);
        }

        File getLowResolutionBitmapFile(int index, int userId) {
            return new File(getDirectory(userId), index + LOW_RES_FILE_POSTFIX + BITMAP_EXTENSION);
        }

        File getHighResolutionBitmapFile(int index, int userId) {
            return new File(getDirectory(userId), index + BITMAP_EXTENSION);
        }

        boolean enableLowResSnapshots() {
            return mEnableLowResSnapshots;
        }

        float lowResScaleFactor() {
            return mLowResScaleFactor;
        }
    }

    /**
     * Persists a snapshot of a task to disk.
     *
     * @param taskId The id of the task that needs to be persisted.
     * @param userId The id of the user this tasks belongs to.
     * @param snapshot The snapshot to persist.
     */
    void persistSnapshot(int taskId, int userId, TaskSnapshot snapshot) {
        synchronized (mLock) {
            mPersistedTaskIdsSinceLastRemoveObsolete.add(taskId);
            mSnapshotPersistQueue.sendToQueueLocked(mSnapshotPersistQueue
                    .createStoreWriteQueueItem(taskId, userId, snapshot, mPersistInfoProvider));
        }
    }

    /**
     * Callend when a task has been removed.
     *
     * @param taskId The id of task that has been removed.
     * @param userId The id of the user the task belonged to.
     */
    void onTaskRemovedFromRecents(int taskId, int userId) {
        synchronized (mLock) {
            mPersistedTaskIdsSinceLastRemoveObsolete.remove(taskId);
            mSnapshotPersistQueue.sendToQueueLocked(mSnapshotPersistQueue
                    .createDeleteWriteQueueItem(taskId, userId, mPersistInfoProvider));
        }
    }

    /**
     * In case a write/delete operation was lost because the system crashed, this makes sure to
     * clean up the directory to remove obsolete files.
     *
     * @param persistentTaskIds A set of task ids that exist in our in-memory model.
     * @param runningUserIds The ids of the list of users that have tasks loaded in our in-memory
     *                       model.
     */
    void removeObsoleteFiles(ArraySet<Integer> persistentTaskIds, int[] runningUserIds) {
        synchronized (mLock) {
            mPersistedTaskIdsSinceLastRemoveObsolete.clear();
            mSnapshotPersistQueue.sendToQueueLocked(new RemoveObsoleteFilesQueueItem(
                    persistentTaskIds, runningUserIds, mPersistInfoProvider));
        }
    }

    @VisibleForTesting
    class RemoveObsoleteFilesQueueItem extends SnapshotPersistQueue.WriteQueueItem {
        private final ArraySet<Integer> mPersistentTaskIds;
        private final int[] mRunningUserIds;

        @VisibleForTesting
        RemoveObsoleteFilesQueueItem(ArraySet<Integer> persistentTaskIds,
                int[] runningUserIds, PersistInfoProvider provider) {
            super(provider);
            mPersistentTaskIds = new ArraySet<>(persistentTaskIds);
            mRunningUserIds = Arrays.copyOf(runningUserIds, runningUserIds.length);
        }

        @Override
        void write() {
            final ArraySet<Integer> newPersistedTaskIds;
            synchronized (mLock) {
                newPersistedTaskIds = new ArraySet<>(mPersistedTaskIdsSinceLastRemoveObsolete);
            }
            for (int userId : mRunningUserIds) {
                final File dir = mPersistInfoProvider.getDirectory(userId);
                final String[] files = dir.list();
                if (files == null) {
                    continue;
                }
                for (String file : files) {
                    final int taskId = getTaskId(file);
                    if (!mPersistentTaskIds.contains(taskId)
                            && !newPersistedTaskIds.contains(taskId)) {
                        new File(dir, file).delete();
                    }
                }
            }
        }

        @VisibleForTesting
        int getTaskId(String fileName) {
            if (!fileName.endsWith(PROTO_EXTENSION) && !fileName.endsWith(BITMAP_EXTENSION)) {
                return -1;
            }
            final int end = fileName.lastIndexOf('.');
            if (end == -1) {
                return -1;
            }
            String name = fileName.substring(0, end);
            if (name.endsWith(LOW_RES_FILE_POSTFIX)) {
                name = name.substring(0, name.length() - LOW_RES_FILE_POSTFIX.length());
            }
            try {
                return Integer.parseInt(name);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
    }
}

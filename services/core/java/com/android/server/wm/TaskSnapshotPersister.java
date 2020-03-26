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

import static android.graphics.Bitmap.CompressFormat.JPEG;

import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.annotation.NonNull;
import android.annotation.TestApi;
import android.app.ActivityManager.TaskSnapshot;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.os.Process;
import android.os.SystemClock;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wm.nano.WindowManagerProtos.TaskSnapshotProto;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Persists {@link TaskSnapshot}s to disk.
 * <p>
 * Test class: {@link TaskSnapshotPersisterLoaderTest}
 */
class TaskSnapshotPersister {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "TaskSnapshotPersister" : TAG_WM;
    private static final String SNAPSHOTS_DIRNAME = "snapshots";
    private static final String LOW_RES_FILE_POSTFIX = "_reduced";
    private static final long DELAY_MS = 100;
    private static final int QUALITY = 95;
    private static final String PROTO_EXTENSION = ".proto";
    private static final String BITMAP_EXTENSION = ".jpg";
    private static final int MAX_STORE_QUEUE_DEPTH = 2;

    @GuardedBy("mLock")
    private final ArrayDeque<WriteQueueItem> mWriteQueue = new ArrayDeque<>();
    @GuardedBy("mLock")
    private final ArrayDeque<StoreWriteQueueItem> mStoreQueueItems = new ArrayDeque<>();
    @GuardedBy("mLock")
    private boolean mQueueIdling;
    @GuardedBy("mLock")
    private boolean mPaused;
    private boolean mStarted;
    private final Object mLock = new Object();
    private final DirectoryResolver mDirectoryResolver;
    private final float mLowResScaleFactor;
    private boolean mEnableLowResSnapshots;
    private final boolean mUse16BitFormat;

    /**
     * The list of ids of the tasks that have been persisted since {@link #removeObsoleteFiles} was
     * called.
     */
    @GuardedBy("mLock")
    private final ArraySet<Integer> mPersistedTaskIdsSinceLastRemoveObsolete = new ArraySet<>();

    TaskSnapshotPersister(WindowManagerService service, DirectoryResolver resolver) {
        mDirectoryResolver = resolver;
        final float highResTaskSnapshotScale = service.mContext.getResources().getFloat(
                com.android.internal.R.dimen.config_highResTaskSnapshotScale);
        final float lowResTaskSnapshotScale = service.mContext.getResources().getFloat(
                com.android.internal.R.dimen.config_lowResTaskSnapshotScale);

        if (lowResTaskSnapshotScale < 0 || 1 <= lowResTaskSnapshotScale) {
            throw new RuntimeException("Low-res scale must be between 0 and 1");
        }
        if (highResTaskSnapshotScale <= 0 || 1 < highResTaskSnapshotScale) {
            throw new RuntimeException("High-res scale must be between 0 and 1");
        }
        if (highResTaskSnapshotScale <= lowResTaskSnapshotScale) {
            throw new RuntimeException("High-res scale must be greater than low-res scale");
        }

        if (lowResTaskSnapshotScale > 0) {
            mLowResScaleFactor = lowResTaskSnapshotScale / highResTaskSnapshotScale;
            mEnableLowResSnapshots = true;
        } else {
            mLowResScaleFactor = 0;
            mEnableLowResSnapshots = false;
        }

        mUse16BitFormat = service.mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_use16BitTaskSnapshotPixelFormat);
    }

    /**
     * Starts persisting.
     */
    void start() {
        if (!mStarted) {
            mStarted = true;
            mPersister.start();
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
            sendToQueueLocked(new StoreWriteQueueItem(taskId, userId, snapshot));
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
            sendToQueueLocked(new DeleteWriteQueueItem(taskId, userId));
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
            sendToQueueLocked(new RemoveObsoleteFilesQueueItem(persistentTaskIds, runningUserIds));
        }
    }

    void setPaused(boolean paused) {
        synchronized (mLock) {
            mPaused = paused;
            if (!paused) {
                mLock.notifyAll();
            }
        }
    }

    boolean enableLowResSnapshots() {
        return mEnableLowResSnapshots;
    }

    /**
     * Return if task snapshots are stored in 16 bit pixel format.
     *
     * @return true if task snapshots are stored in 16 bit pixel format.
     */
    boolean use16BitFormat() {
        return mUse16BitFormat;
    }

    @TestApi
    void waitForQueueEmpty() {
        while (true) {
            synchronized (mLock) {
                if (mWriteQueue.isEmpty() && mQueueIdling) {
                    return;
                }
            }
            SystemClock.sleep(100);
        }
    }

    @GuardedBy("mLock")
    private void sendToQueueLocked(WriteQueueItem item) {
        mWriteQueue.offer(item);
        item.onQueuedLocked();
        ensureStoreQueueDepthLocked();
        if (!mPaused) {
            mLock.notifyAll();
        }
    }

    @GuardedBy("mLock")
    private void ensureStoreQueueDepthLocked() {
        while (mStoreQueueItems.size() > MAX_STORE_QUEUE_DEPTH) {
            final StoreWriteQueueItem item = mStoreQueueItems.poll();
            mWriteQueue.remove(item);
            Slog.i(TAG, "Queue is too deep! Purged item with taskid=" + item.mTaskId);
        }
    }

    private File getDirectory(int userId) {
        return new File(mDirectoryResolver.getSystemDirectoryForUser(userId), SNAPSHOTS_DIRNAME);
    }

    File getProtoFile(int taskId, int userId) {
        return new File(getDirectory(userId), taskId + PROTO_EXTENSION);
    }

    File getHighResolutionBitmapFile(int taskId, int userId) {
        return new File(getDirectory(userId), taskId + BITMAP_EXTENSION);
    }

    @NonNull
    File getLowResolutionBitmapFile(int taskId, int userId) {
        return new File(getDirectory(userId), taskId + LOW_RES_FILE_POSTFIX + BITMAP_EXTENSION);
    }

    private boolean createDirectory(int userId) {
        final File dir = getDirectory(userId);
        return dir.exists() || dir.mkdirs();
    }

    private void deleteSnapshot(int taskId, int userId) {
        final File protoFile = getProtoFile(taskId, userId);
        final File bitmapLowResFile = getLowResolutionBitmapFile(taskId, userId);
        protoFile.delete();
        if (bitmapLowResFile.exists()) {
            bitmapLowResFile.delete();
        }
        final File bitmapFile = getHighResolutionBitmapFile(taskId, userId);
        if (bitmapFile.exists()) {
            bitmapFile.delete();
        }
    }

    interface DirectoryResolver {
        File getSystemDirectoryForUser(int userId);
    }

    private Thread mPersister = new Thread("TaskSnapshotPersister") {
        public void run() {
            android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            while (true) {
                WriteQueueItem next;
                synchronized (mLock) {
                    if (mPaused) {
                        next = null;
                    } else {
                        next = mWriteQueue.poll();
                        if (next != null) {
                            next.onDequeuedLocked();
                        }
                    }
                }
                if (next != null) {
                    next.write();
                    SystemClock.sleep(DELAY_MS);
                }
                synchronized (mLock) {
                    final boolean writeQueueEmpty = mWriteQueue.isEmpty();
                    if (!writeQueueEmpty && !mPaused) {
                        continue;
                    }
                    try {
                        mQueueIdling = writeQueueEmpty;
                        mLock.wait();
                        mQueueIdling = false;
                    } catch (InterruptedException e) {
                    }
                }
            }
        }
    };

    private abstract class WriteQueueItem {
        abstract void write();

        /**
         * Called when this queue item has been put into the queue.
         */
        void onQueuedLocked() {
        }

        /**
         * Called when this queue item has been taken out of the queue.
         */
        void onDequeuedLocked() {
        }
    }

    private class StoreWriteQueueItem extends WriteQueueItem {
        private final int mTaskId;
        private final int mUserId;
        private final TaskSnapshot mSnapshot;

        StoreWriteQueueItem(int taskId, int userId, TaskSnapshot snapshot) {
            mTaskId = taskId;
            mUserId = userId;
            mSnapshot = snapshot;
        }

        @GuardedBy("mLock")
        @Override
        void onQueuedLocked() {
            mStoreQueueItems.offer(this);
        }

        @GuardedBy("mLock")
        @Override
        void onDequeuedLocked() {
            mStoreQueueItems.remove(this);
        }

        @Override
        void write() {
            if (!createDirectory(mUserId)) {
                Slog.e(TAG, "Unable to create snapshot directory for user dir="
                        + getDirectory(mUserId));
            }
            boolean failed = false;
            if (!writeProto()) {
                failed = true;
            }
            if (!writeBuffer()) {
                failed = true;
            }
            if (failed) {
                deleteSnapshot(mTaskId, mUserId);
            }
        }

        boolean writeProto() {
            final TaskSnapshotProto proto = new TaskSnapshotProto();
            proto.orientation = mSnapshot.getOrientation();
            proto.rotation = mSnapshot.getRotation();
            proto.taskWidth = mSnapshot.getTaskSize().x;
            proto.taskHeight = mSnapshot.getTaskSize().y;
            proto.insetLeft = mSnapshot.getContentInsets().left;
            proto.insetTop = mSnapshot.getContentInsets().top;
            proto.insetRight = mSnapshot.getContentInsets().right;
            proto.insetBottom = mSnapshot.getContentInsets().bottom;
            proto.isRealSnapshot = mSnapshot.isRealSnapshot();
            proto.windowingMode = mSnapshot.getWindowingMode();
            proto.systemUiVisibility = mSnapshot.getSystemUiVisibility();
            proto.isTranslucent = mSnapshot.isTranslucent();
            proto.topActivityComponent = mSnapshot.getTopActivityComponent().flattenToString();
            proto.id = mSnapshot.getId();
            final byte[] bytes = TaskSnapshotProto.toByteArray(proto);
            final File file = getProtoFile(mTaskId, mUserId);
            final AtomicFile atomicFile = new AtomicFile(file);
            FileOutputStream fos = null;
            try {
                fos = atomicFile.startWrite();
                fos.write(bytes);
                atomicFile.finishWrite(fos);
            } catch (IOException e) {
                atomicFile.failWrite(fos);
                Slog.e(TAG, "Unable to open " + file + " for persisting. " + e);
                return false;
            }
            return true;
        }

        boolean writeBuffer() {
            final Bitmap bitmap = Bitmap.wrapHardwareBuffer(
                    mSnapshot.getHardwareBuffer(), mSnapshot.getColorSpace());
            if (bitmap == null) {
                Slog.e(TAG, "Invalid task snapshot hw bitmap");
                return false;
            }

            final Bitmap swBitmap = bitmap.copy(Config.ARGB_8888, false /* isMutable */);

            final File file = getHighResolutionBitmapFile(mTaskId, mUserId);
            try {
                FileOutputStream fos = new FileOutputStream(file);
                swBitmap.compress(JPEG, QUALITY, fos);
                fos.close();
            } catch (IOException e) {
                Slog.e(TAG, "Unable to open " + file + " for persisting.", e);
                return false;
            }

            if (!mEnableLowResSnapshots) {
                swBitmap.recycle();
                return true;
            }

            final Bitmap lowResBitmap = Bitmap.createScaledBitmap(swBitmap,
                    (int) (bitmap.getWidth() * mLowResScaleFactor),
                    (int) (bitmap.getHeight() * mLowResScaleFactor), true /* filter */);
            swBitmap.recycle();

            final File lowResFile = getLowResolutionBitmapFile(mTaskId, mUserId);
            try {
                FileOutputStream lowResFos = new FileOutputStream(lowResFile);
                lowResBitmap.compress(JPEG, QUALITY, lowResFos);
                lowResFos.close();
            } catch (IOException e) {
                Slog.e(TAG, "Unable to open " + lowResFile + " for persisting.", e);
                return false;
            }
            lowResBitmap.recycle();

            return true;
        }
    }

    private class DeleteWriteQueueItem extends WriteQueueItem {
        private final int mTaskId;
        private final int mUserId;

        DeleteWriteQueueItem(int taskId, int userId) {
            mTaskId = taskId;
            mUserId = userId;
        }

        @Override
        void write() {
            deleteSnapshot(mTaskId, mUserId);
        }
    }

    @VisibleForTesting
    class RemoveObsoleteFilesQueueItem extends WriteQueueItem {
        private final ArraySet<Integer> mPersistentTaskIds;
        private final int[] mRunningUserIds;

        @VisibleForTesting
        RemoveObsoleteFilesQueueItem(ArraySet<Integer> persistentTaskIds,
                int[] runningUserIds) {
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
                final File dir = getDirectory(userId);
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

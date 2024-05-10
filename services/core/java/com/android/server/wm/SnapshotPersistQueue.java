/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.wm;

import static android.graphics.Bitmap.CompressFormat.JPEG;
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;

import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.annotation.NonNull;
import android.graphics.Bitmap;
import android.os.Process;
import android.os.SystemClock;
import android.os.Trace;
import android.util.AtomicFile;
import android.util.Slog;
import android.window.TaskSnapshot;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;
import com.android.server.wm.BaseAppSnapshotPersister.PersistInfoProvider;
import com.android.server.wm.nano.WindowManagerProtos.TaskSnapshotProto;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;

/**
 * Singleton worker thread to queue up persist or delete tasks of {@link TaskSnapshot}s to disk.
 */
class SnapshotPersistQueue {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "TaskSnapshotPersister" : TAG_WM;
    private static final long DELAY_MS = 100;
    private static final int MAX_STORE_QUEUE_DEPTH = 2;
    private static final int COMPRESS_QUALITY = 95;

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
    private final UserManagerInternal mUserManagerInternal;

    SnapshotPersistQueue() {
        mUserManagerInternal = LocalServices.getService(UserManagerInternal.class);
    }

    Object getLock() {
        return mLock;
    }

    void systemReady() {
        start();
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
     * Temporarily pauses/unpauses persisting of task snapshots.
     *
     * @param paused Whether task snapshot persisting should be paused.
     */
    void setPaused(boolean paused) {
        synchronized (mLock) {
            mPaused = paused;
            if (!paused) {
                mLock.notifyAll();
            }
        }
    }

    @VisibleForTesting
    void waitForQueueEmpty() {
        while (true) {
            synchronized (mLock) {
                if (mWriteQueue.isEmpty() && mQueueIdling) {
                    return;
                }
            }
            SystemClock.sleep(DELAY_MS);
        }
    }

    @VisibleForTesting
    int peekQueueSize() {
        synchronized (mLock) {
            return mWriteQueue.size();
        }
    }

    private void addToQueueInternal(WriteQueueItem item, boolean insertToFront) {
        mWriteQueue.removeFirstOccurrence(item);
        if (insertToFront) {
            mWriteQueue.addFirst(item);
        } else {
            mWriteQueue.addLast(item);
        }
        item.onQueuedLocked();
        ensureStoreQueueDepthLocked();
        if (!mPaused) {
            mLock.notifyAll();
        }
    }

    @GuardedBy("mLock")
    void sendToQueueLocked(WriteQueueItem item) {
        addToQueueInternal(item, false /* insertToFront */);
    }

    @GuardedBy("mLock")
    void insertQueueAtFirstLocked(WriteQueueItem item) {
        addToQueueInternal(item, true /* insertToFront */);
    }

    @GuardedBy("mLock")
    private void ensureStoreQueueDepthLocked() {
        while (mStoreQueueItems.size() > MAX_STORE_QUEUE_DEPTH) {
            final StoreWriteQueueItem item = mStoreQueueItems.poll();
            mWriteQueue.remove(item);
            Slog.i(TAG, "Queue is too deep! Purged item with index=" + item.mId);
        }
    }

    void deleteSnapshot(int index, int userId, PersistInfoProvider provider) {
        final File protoFile = provider.getProtoFile(index, userId);
        final File bitmapLowResFile = provider.getLowResolutionBitmapFile(index, userId);
        protoFile.delete();
        if (bitmapLowResFile.exists()) {
            bitmapLowResFile.delete();
        }
        final File bitmapFile = provider.getHighResolutionBitmapFile(index, userId);
        if (bitmapFile.exists()) {
            bitmapFile.delete();
        }
    }

    private final Thread mPersister = new Thread("TaskSnapshotPersister") {
        public void run() {
            android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            while (true) {
                WriteQueueItem next;
                boolean isReadyToWrite = false;
                synchronized (mLock) {
                    if (mPaused) {
                        next = null;
                    } else {
                        next = mWriteQueue.poll();
                        if (next != null) {
                            if (next.isReady()) {
                                isReadyToWrite = true;
                                next.onDequeuedLocked();
                            } else {
                                mWriteQueue.addLast(next);
                            }
                        }
                    }
                }
                if (next != null) {
                    if (isReadyToWrite) {
                        next.write();
                    }
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

    abstract static class WriteQueueItem {
        protected final PersistInfoProvider mPersistInfoProvider;
        WriteQueueItem(@NonNull PersistInfoProvider persistInfoProvider) {
            mPersistInfoProvider = persistInfoProvider;
        }
        /**
         * @return {@code true} if item is ready to have {@link WriteQueueItem#write} called
         */
        boolean isReady() {
            return true;
        }

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

    StoreWriteQueueItem createStoreWriteQueueItem(int id, int userId, TaskSnapshot snapshot,
            PersistInfoProvider provider) {
        return new StoreWriteQueueItem(id, userId, snapshot, provider);
    }

    class StoreWriteQueueItem extends WriteQueueItem {
        private final int mId;
        private final int mUserId;
        private final TaskSnapshot mSnapshot;

        StoreWriteQueueItem(int id, int userId, TaskSnapshot snapshot,
                PersistInfoProvider provider) {
            super(provider);
            mId = id;
            mUserId = userId;
            mSnapshot = snapshot;
        }

        @GuardedBy("mLock")
        @Override
        void onQueuedLocked() {
            // Remove duplicate request.
            mStoreQueueItems.remove(this);
            mStoreQueueItems.offer(this);
        }

        @GuardedBy("mLock")
        @Override
        void onDequeuedLocked() {
            mStoreQueueItems.remove(this);
        }

        @Override
        boolean isReady() {
            return mUserManagerInternal.isUserUnlocked(mUserId);
        }

        @Override
        void write() {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "StoreWriteQueueItem");
            if (!mPersistInfoProvider.createDirectory(mUserId)) {
                Slog.e(TAG, "Unable to create snapshot directory for user dir="
                        + mPersistInfoProvider.getDirectory(mUserId));
            }
            boolean failed = false;
            if (!writeProto()) {
                failed = true;
            }
            if (!writeBuffer()) {
                failed = true;
            }
            if (failed) {
                deleteSnapshot(mId, mUserId, mPersistInfoProvider);
            }
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
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
            proto.letterboxInsetLeft = mSnapshot.getLetterboxInsets().left;
            proto.letterboxInsetTop = mSnapshot.getLetterboxInsets().top;
            proto.letterboxInsetRight = mSnapshot.getLetterboxInsets().right;
            proto.letterboxInsetBottom = mSnapshot.getLetterboxInsets().bottom;
            proto.isRealSnapshot = mSnapshot.isRealSnapshot();
            proto.windowingMode = mSnapshot.getWindowingMode();
            proto.appearance = mSnapshot.getAppearance();
            proto.isTranslucent = mSnapshot.isTranslucent();
            proto.topActivityComponent = mSnapshot.getTopActivityComponent().flattenToString();
            proto.id = mSnapshot.getId();
            final byte[] bytes = TaskSnapshotProto.toByteArray(proto);
            final File file = mPersistInfoProvider.getProtoFile(mId, mUserId);
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
            if (AbsAppSnapshotController.isInvalidHardwareBuffer(mSnapshot.getHardwareBuffer())) {
                Slog.e(TAG, "Invalid task snapshot hw buffer, taskId=" + mId);
                return false;
            }
            final Bitmap bitmap = Bitmap.wrapHardwareBuffer(
                    mSnapshot.getHardwareBuffer(), mSnapshot.getColorSpace());
            if (bitmap == null) {
                Slog.e(TAG, "Invalid task snapshot hw bitmap");
                return false;
            }

            final Bitmap swBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false /* isMutable */);
            if (swBitmap == null) {
                Slog.e(TAG, "Bitmap conversion from (config=" + bitmap.getConfig() + ", isMutable="
                        + bitmap.isMutable() + ") to (config=ARGB_8888, isMutable=false) failed.");
                return false;
            }

            final File file = mPersistInfoProvider.getHighResolutionBitmapFile(mId, mUserId);
            try {
                FileOutputStream fos = new FileOutputStream(file);
                swBitmap.compress(JPEG, COMPRESS_QUALITY, fos);
                fos.close();
            } catch (IOException e) {
                Slog.e(TAG, "Unable to open " + file + " for persisting.", e);
                return false;
            }

            if (!mPersistInfoProvider.enableLowResSnapshots()) {
                swBitmap.recycle();
                return true;
            }

            final Bitmap lowResBitmap = Bitmap.createScaledBitmap(swBitmap,
                    (int) (bitmap.getWidth() * mPersistInfoProvider.lowResScaleFactor()),
                    (int) (bitmap.getHeight() * mPersistInfoProvider.lowResScaleFactor()),
                    true /* filter */);
            swBitmap.recycle();

            final File lowResFile = mPersistInfoProvider.getLowResolutionBitmapFile(mId, mUserId);
            try {
                FileOutputStream lowResFos = new FileOutputStream(lowResFile);
                lowResBitmap.compress(JPEG, COMPRESS_QUALITY, lowResFos);
                lowResFos.close();
            } catch (IOException e) {
                Slog.e(TAG, "Unable to open " + lowResFile + " for persisting.", e);
                return false;
            }
            lowResBitmap.recycle();

            return true;
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            final StoreWriteQueueItem other = (StoreWriteQueueItem) o;
            return mId == other.mId && mUserId == other.mUserId
                    && mPersistInfoProvider == other.mPersistInfoProvider;
        }
    }

    DeleteWriteQueueItem createDeleteWriteQueueItem(int id, int userId,
            PersistInfoProvider provider) {
        return new DeleteWriteQueueItem(id, userId, provider);
    }

    private class DeleteWriteQueueItem extends WriteQueueItem {
        private final int mId;
        private final int mUserId;

        DeleteWriteQueueItem(int id, int userId, PersistInfoProvider provider) {
            super(provider);
            mId = id;
            mUserId = userId;
        }

        @Override
        void write() {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "DeleteWriteQueueItem");
            deleteSnapshot(mId, mUserId, mPersistInfoProvider);
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }
    }
}

/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.inputmethod;

import android.annotation.AnyThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.annotation.WorkerThread;
import android.content.Context;
import android.content.pm.UserInfo;
import android.os.Handler;
import android.os.Process;
import android.util.IntArray;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.inputmethod.DirectBootAwareness;
import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;

import java.util.ArrayList;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Provides accesses to per-user additional {@link android.view.inputmethod.InputMethodSubtype}
 * persistent storages.
 */
final class AdditionalSubtypeMapRepository {
    @GuardedBy("ImfLock.class")
    @NonNull
    private static final SparseArray<AdditionalSubtypeMap> sPerUserMap = new SparseArray<>();

    record WriteTask(@UserIdInt int userId, @NonNull AdditionalSubtypeMap subtypeMap,
                     @NonNull InputMethodMap inputMethodMap) {
    }

    static final class SingleThreadedBackgroundWriter {
        /**
         * A {@link ReentrantLock} used to guard {@link #mPendingTasks} and {@link #mRemovedUsers}.
         */
        @NonNull
        private final ReentrantLock mLock = new ReentrantLock();
        /**
         * A {@link Condition} associated with {@link #mLock} for producer to unblock consumer.
         */
        @NonNull
        private final Condition mLockNotifier = mLock.newCondition();

        @GuardedBy("mLock")
        @NonNull
        private final SparseArray<WriteTask> mPendingTasks = new SparseArray<>();

        @GuardedBy("mLock")
        private final IntArray mRemovedUsers = new IntArray();

        @NonNull
        private final Thread mWriterThread = new Thread("android.ime.as") {

            /**
             * Waits until the next data has come then return the result after filtering out any
             * already removed users.
             *
             * @return A list of {@link WriteTask} to be written into persistent storage
             */
            @WorkerThread
            private ArrayList<WriteTask> fetchNextTasks() {
                final SparseArray<WriteTask> tasks;
                final IntArray removedUsers;
                mLock.lock();
                try {
                    while (true) {
                        if (mPendingTasks.size() != 0) {
                            tasks = mPendingTasks.clone();
                            mPendingTasks.clear();
                            if (mRemovedUsers.size() == 0) {
                                removedUsers = null;
                            } else {
                                removedUsers = mRemovedUsers.clone();
                            }
                            break;
                        }
                        mLockNotifier.awaitUninterruptibly();
                    }
                } finally {
                    mLock.unlock();
                }
                final int size = tasks.size();
                final ArrayList<WriteTask> result = new ArrayList<>(size);
                for (int i = 0; i < size; ++i) {
                    final int userId = tasks.keyAt(i);
                    if (removedUsers != null && removedUsers.contains(userId)) {
                        continue;
                    }
                    result.add(tasks.valueAt(i));
                }
                return result;
            }

            @WorkerThread
            @Override
            public void run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

                while (true) {
                    final ArrayList<WriteTask> tasks = fetchNextTasks();
                    tasks.forEach(task -> AdditionalSubtypeUtils.save(
                            task.subtypeMap, task.inputMethodMap, task.userId));
                }
            }
        };

        /**
         * Schedules a write operation
         *
         * @param userId the target user ID of this operation
         * @param subtypeMap {@link AdditionalSubtypeMap} to be saved
         * @param inputMethodMap {@link InputMethodMap} to be used to filter our {@code subtypeMap}
         */
        @AnyThread
        void scheduleWriteTask(@UserIdInt int userId, @NonNull AdditionalSubtypeMap subtypeMap,
                @NonNull InputMethodMap inputMethodMap) {
            final var task = new WriteTask(userId, subtypeMap, inputMethodMap);
            mLock.lock();
            try {
                if (mRemovedUsers.contains(userId)) {
                    return;
                }
                mPendingTasks.put(userId, task);
                mLockNotifier.signalAll();
            } finally {
                mLock.unlock();
            }
        }

        /**
         * Called back when a user is being created.
         *
         * @param userId The user ID to be created
         */
        @AnyThread
        void onUserCreated(@UserIdInt int userId) {
            mLock.lock();
            try {
                for (int i = mRemovedUsers.size() - 1; i >= 0; --i) {
                    if (mRemovedUsers.get(i) == userId) {
                        mRemovedUsers.remove(i);
                    }
                }
            } finally {
                mLock.unlock();
            }
        }

        /**
         * Called back when a user is being removed. Any pending task will be effectively canceled
         * if the user is removed before the task is fulfilled.
         *
         * @param userId The user ID to be removed
         */
        @AnyThread
        void onUserRemoved(@UserIdInt int userId) {
            mLock.lock();
            try {
                mRemovedUsers.add(userId);
                mPendingTasks.remove(userId);
            } finally {
                mLock.unlock();
            }
        }

        void startThread() {
            mWriterThread.start();
        }
    }

    private static final SingleThreadedBackgroundWriter sWriter =
            new SingleThreadedBackgroundWriter();

    /**
     * Not intended to be instantiated.
     */
    private AdditionalSubtypeMapRepository() {
    }

    @NonNull
    @GuardedBy("ImfLock.class")
    static AdditionalSubtypeMap get(@UserIdInt int userId) {
        final AdditionalSubtypeMap map = sPerUserMap.get(userId);
        if (map != null) {
            return map;
        }
        final AdditionalSubtypeMap newMap = AdditionalSubtypeUtils.load(userId);
        sPerUserMap.put(userId, newMap);
        return newMap;
    }

    @GuardedBy("ImfLock.class")
    static void putAndSave(@UserIdInt int userId, @NonNull AdditionalSubtypeMap map,
            @NonNull InputMethodMap inputMethodMap) {
        final AdditionalSubtypeMap previous = sPerUserMap.get(userId);
        if (previous == map) {
            return;
        }
        sPerUserMap.put(userId, map);
        sWriter.scheduleWriteTask(userId, map, inputMethodMap);
    }

    static void startWriterThread() {
        sWriter.startThread();
    }

    static void initialize(@NonNull Handler handler, @NonNull Context context) {
        final UserManagerInternal userManagerInternal =
                LocalServices.getService(UserManagerInternal.class);
        handler.post(() -> {
            userManagerInternal.addUserLifecycleListener(
                    new UserManagerInternal.UserLifecycleListener() {
                        @Override
                        public void onUserCreated(UserInfo user, @Nullable Object token) {
                            final int userId = user.id;
                            sWriter.onUserCreated(userId);
                            handler.post(() -> {
                                synchronized (ImfLock.class) {
                                    if (!sPerUserMap.contains(userId)) {
                                        final AdditionalSubtypeMap additionalSubtypeMap =
                                                AdditionalSubtypeUtils.load(userId);
                                        sPerUserMap.put(userId, additionalSubtypeMap);
                                        final InputMethodSettings settings =
                                                InputMethodManagerService
                                                        .queryInputMethodServicesInternal(context,
                                                                userId,
                                                                additionalSubtypeMap,
                                                                DirectBootAwareness.AUTO);
                                        InputMethodSettingsRepository.put(userId, settings);
                                    }
                                }
                            });
                        }

                        @Override
                        public void onUserRemoved(UserInfo user) {
                            final int userId = user.id;
                            sWriter.onUserRemoved(userId);
                            handler.post(() -> {
                                synchronized (ImfLock.class) {
                                    sPerUserMap.remove(userId);
                                }
                            });
                        }
                    });
            synchronized (ImfLock.class) {
                for (int userId : userManagerInternal.getUserIds()) {
                    sPerUserMap.put(userId, AdditionalSubtypeUtils.load(userId));
                }
            }
        });
    }
}

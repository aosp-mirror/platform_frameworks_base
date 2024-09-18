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

package com.android.server.appfunctions;

import android.annotation.NonNull;
import android.os.UserHandle;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Executors for App function operations. */
public final class AppFunctionExecutors {

    /** Executor for operations that do not need to block. */
    public static final Executor THREAD_POOL_EXECUTOR =
            new ThreadPoolExecutor(
                    /* corePoolSize= */ Runtime.getRuntime().availableProcessors(),
                    /* maxConcurrency= */ Runtime.getRuntime().availableProcessors(),
                    /* keepAliveTime= */ 0L,
                    /* unit= */ TimeUnit.SECONDS,
                    /* workQueue= */ new LinkedBlockingQueue<>());

    /** A map of per-user executors for queued work. */
    @GuardedBy("sLock")
    private static final SparseArray<ExecutorService> mPerUserExecutorsLocked = new SparseArray<>();

    private static final Object sLock = new Object();

    /**
     * Returns a per-user executor for queued metadata sync request.
     *
     * <p>The work submitted to these executor (Sync request) needs to be synchronous per user hence
     * the use of a single thread.
     *
     * <p>Note: Use a different executor if not calling {@code submitSyncRequest} on a {@code
     * MetadataSyncAdapter}.
     */
    // TODO(b/357551503): Restrict the scope of this executor to the MetadataSyncAdapter itself.
    public static ExecutorService getPerUserSyncExecutor(@NonNull UserHandle user) {
        synchronized (sLock) {
            ExecutorService executor = mPerUserExecutorsLocked.get(user.getIdentifier(), null);
            if (executor == null) {
                executor = Executors.newSingleThreadExecutor();
                mPerUserExecutorsLocked.put(user.getIdentifier(), executor);
            }
            return executor;
        }
    }

    /**
     * Shuts down and removes the per-user executor for queued work.
     *
     * <p>This should be called when the user is removed.
     */
    public static void shutDownAndRemoveUserExecutor(@NonNull UserHandle user)
            throws InterruptedException {
        ExecutorService executor;
        synchronized (sLock) {
            executor = mPerUserExecutorsLocked.get(user.getIdentifier());
            mPerUserExecutorsLocked.remove(user.getIdentifier());
        }
        if (executor != null) {
            executor.shutdown();
            var unused = executor.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    private AppFunctionExecutors() {}
}

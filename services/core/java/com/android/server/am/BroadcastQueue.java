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

package com.android.server.am;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Objects;
import java.util.Set;

/**
 * Queue of broadcast intents and associated bookkeeping.
 */
public abstract class BroadcastQueue {
    public static final String TAG = "BroadcastQueue";

    final @NonNull ActivityManagerService mService;
    final @NonNull Handler mHandler;
    final @NonNull BroadcastConstants mConstants;
    final @NonNull BroadcastSkipPolicy mSkipPolicy;
    final @NonNull BroadcastHistory mHistory;
    final @NonNull String mQueueName;

    BroadcastQueue(@NonNull ActivityManagerService service, @NonNull Handler handler,
            @NonNull String name, @NonNull BroadcastConstants constants,
            @NonNull BroadcastSkipPolicy skipPolicy, @NonNull BroadcastHistory history) {
        mService = Objects.requireNonNull(service);
        mHandler = Objects.requireNonNull(handler);
        mQueueName = Objects.requireNonNull(name);
        mConstants = Objects.requireNonNull(constants);
        mSkipPolicy = Objects.requireNonNull(skipPolicy);
        mHistory = Objects.requireNonNull(history);
    }

    void start(@NonNull ContentResolver resolver) {
        mConstants.startObserving(mHandler, resolver);
    }

    static void checkState(boolean state, String msg) {
        if (!state) {
            Slog.wtf(TAG, msg, new Throwable());
        }
    }

    static void logv(String msg) {
        Slog.v(TAG, msg);
    }

    @Override
    public String toString() {
        return mQueueName;
    }

    public abstract boolean isDelayBehindServices();

    /**
     * Return the preferred scheduling group for the given process, typically
     * influenced by a broadcast being actively dispatched.
     *
     * @return scheduling group such as {@link ProcessList#SCHED_GROUP_DEFAULT},
     *         otherwise {@link ProcessList#SCHED_GROUP_UNDEFINED} if this queue
     *         has no opinion.
     */
    @GuardedBy("mService")
    public abstract int getPreferredSchedulingGroupLocked(@NonNull ProcessRecord app);

    /**
     * Enqueue the given broadcast to be eventually dispatched.
     * <p>
     * Callers must populate {@link BroadcastRecord#receivers} with the relevant
     * targets before invoking this method.
     * <p>
     * When {@link Intent#FLAG_RECEIVER_REPLACE_PENDING} is set, this method
     * internally handles replacement of any matching broadcasts.
     */
    @GuardedBy("mService")
    public abstract void enqueueBroadcastLocked(@NonNull BroadcastRecord r);

    /**
     * Signal delivered back from the given process to indicate that it's
     * finished processing the current broadcast being dispatched to it.
     * <p>
     * If this signal isn't delivered back in a timely fashion, we assume the
     * receiver has somehow wedged and we trigger an ANR.
     */
    @GuardedBy("mService")
    public abstract boolean finishReceiverLocked(@NonNull ProcessRecord app, int resultCode,
            @Nullable String resultData, @Nullable Bundle resultExtras, boolean resultAbort,
            boolean waitForServices);

    @GuardedBy("mService")
    public abstract void backgroundServicesFinishedLocked(int userId);

    /**
     * Signal from OS internals that the given process has just been actively
     * attached, and is ready to begin receiving broadcasts.
     */
    @GuardedBy("mService")
    public abstract boolean onApplicationAttachedLocked(@NonNull ProcessRecord app);

    /**
     * Signal from OS internals that the given process has timed out during
     * an attempted start and attachment.
     */
    @GuardedBy("mService")
    public abstract boolean onApplicationTimeoutLocked(@NonNull ProcessRecord app);

    /**
     * Signal from OS internals that the given process, which had already been
     * previously attached, has now encountered a problem such as crashing or
     * not responding.
     */
    @GuardedBy("mService")
    public abstract boolean onApplicationProblemLocked(@NonNull ProcessRecord app);

    /**
     * Signal from OS internals that the given process has been killed, and is
     * no longer actively running.
     */
    @GuardedBy("mService")
    public abstract boolean onApplicationCleanupLocked(@NonNull ProcessRecord app);

    /**
     * Signal from OS internals that the given package (or some subset of that
     * package) has been disabled or uninstalled, and that any pending
     * broadcasts should be cleaned up.
     */
    @GuardedBy("mService")
    public abstract boolean cleanupDisabledPackageReceiversLocked(@Nullable String packageName,
            @Nullable Set<String> filterByClasses, int userId, boolean doit);

    /**
     * Quickly determine if this queue has broadcasts that are still waiting to
     * be delivered at some point in the future.
     *
     * @see #waitForIdle
     * @see #waitForBarrier
     */
    @GuardedBy("mService")
    public abstract boolean isIdleLocked();

    /**
     * Wait until this queue becomes completely idle.
     * <p>
     * Any broadcasts waiting to be delivered at some point in the future will
     * be dispatched as quickly as possible.
     * <p>
     * Callers are cautioned that the queue may take a long time to go idle,
     * since running apps can continue sending new broadcasts in perpetuity;
     * consider using {@link #waitForBarrier} instead.
     */
    public abstract void waitForIdle(@Nullable PrintWriter pw);

    /**
     * Wait until any currently waiting broadcasts have been dispatched.
     * <p>
     * Any broadcasts waiting to be delivered at some point in the future will
     * be dispatched as quickly as possible.
     * <p>
     * Callers are advised that this method will <em>not</em> wait for any
     * future broadcasts that are newly enqueued after being invoked.
     */
    public abstract void waitForBarrier(@Nullable PrintWriter pw);

    /**
     * Brief summary of internal state, useful for debugging purposes.
     */
    @GuardedBy("mService")
    public abstract @NonNull String describeStateLocked();

    public abstract void dumpDebug(@NonNull ProtoOutputStream proto, long fieldId);

    @GuardedBy("mService")
    public abstract boolean dumpLocked(@NonNull FileDescriptor fd, @NonNull PrintWriter pw,
            @NonNull String[] args, int opti, boolean dumpAll, @Nullable String dumpPackage,
            boolean needSep);
}

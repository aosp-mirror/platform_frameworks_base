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
import android.annotation.UptimeMillisLong;
import android.content.ContentResolver;
import android.content.Intent;
import android.os.Bundle;
import android.os.DropBoxManager;
import android.os.Handler;
import android.os.Trace;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.server.DropBoxManagerInternal;
import com.android.server.LocalServices;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.Objects;
import java.util.Set;

/**
 * Queue of broadcast intents and associated bookkeeping.
 */
public abstract class BroadcastQueue {
    public static final String TAG = "BroadcastQueue";
    public static final String TAG_DUMP = "broadcast_queue_dump";

    final @NonNull ActivityManagerService mService;
    final @NonNull Handler mHandler;
    final @NonNull BroadcastSkipPolicy mSkipPolicy;
    final @NonNull BroadcastHistory mHistory;
    final @NonNull String mQueueName;

    BroadcastQueue(@NonNull ActivityManagerService service, @NonNull Handler handler,
            @NonNull String name, @NonNull BroadcastSkipPolicy skipPolicy,
            @NonNull BroadcastHistory history) {
        mService = Objects.requireNonNull(service);
        mHandler = Objects.requireNonNull(handler);
        mQueueName = Objects.requireNonNull(name);
        mSkipPolicy = Objects.requireNonNull(skipPolicy);
        mHistory = Objects.requireNonNull(history);
    }

    static void logw(@NonNull String msg) {
        Slog.w(TAG, msg);
    }

    static void logv(@NonNull String msg) {
        Slog.v(TAG, msg);
    }

    static void checkState(boolean expression, @NonNull String msg) {
        if (!expression) {
            throw new IllegalStateException(msg);
        }
    }

    static int traceBegin(@NonNull String methodName) {
        final int cookie = methodName.hashCode();
        Trace.asyncTraceForTrackBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                TAG, methodName, cookie);
        return cookie;
    }

    static void traceEnd(int cookie) {
        Trace.asyncTraceForTrackEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                TAG, cookie);
    }

    @Override
    public String toString() {
        return mQueueName;
    }

    public abstract void start(@NonNull ContentResolver resolver);

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
     *
     * @return if the queue performed an action on the given process, such as
     *         dispatching a pending broadcast
     */
    @GuardedBy("mService")
    public abstract boolean onApplicationAttachedLocked(@NonNull ProcessRecord app)
            throws BroadcastDeliveryFailedException;

    /**
     * Signal from OS internals that the given process has timed out during
     * an attempted start and attachment.
     */
    @GuardedBy("mService")
    public abstract void onApplicationTimeoutLocked(@NonNull ProcessRecord app);

    /**
     * Signal from OS internals that the given process, which had already been
     * previously attached, has now encountered a problem such as crashing or
     * not responding.
     */
    @GuardedBy("mService")
    public abstract void onApplicationProblemLocked(@NonNull ProcessRecord app);

    /**
     * Signal from OS internals that the given process has been killed, and is
     * no longer actively running.
     */
    @GuardedBy("mService")
    public abstract void onApplicationCleanupLocked(@NonNull ProcessRecord app);

    /**
     * Signal from OS internals that the given process is in a freezable state and will be
     * frozen soon after.
     */
    @GuardedBy("mService")
    public abstract void onProcessFreezableChangedLocked(@NonNull ProcessRecord app);

    /**
     * Signal from OS internals that the given package (or some subset of that
     * package) has been disabled or uninstalled, and that any pending
     * broadcasts should be cleaned up.
     */
    @GuardedBy("mService")
    public abstract boolean cleanupDisabledPackageReceiversLocked(@Nullable String packageName,
            @Nullable Set<String> filterByClasses, int userId);

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
     * Quickly determine if this queue has non-deferred broadcasts enqueued before the given
     * barrier timestamp that are still waiting to be delivered.
     *
     * @see #waitForIdle
     * @see #waitForBarrier
     */
    @GuardedBy("mService")
    public abstract boolean isBeyondBarrierLocked(@UptimeMillisLong long barrierTime);

    /**
     * Quickly determine if this queue has non-deferred broadcasts waiting to be dispatched,
     * that match {@code intent}, as defined by {@link Intent#filterEquals(Intent)}.
     *
     * @see #waitForDispatched(Intent, PrintWriter)
     */
    @GuardedBy("mService")
    public abstract boolean isDispatchedLocked(@NonNull Intent intent);

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
    public abstract void waitForIdle(@NonNull PrintWriter pw);

    /**
     * Wait until any currently waiting non-deferred broadcasts have been dispatched.
     * <p>
     * Any broadcasts waiting to be delivered at some point in the future will
     * be dispatched as quickly as possible.
     * <p>
     * Callers are advised that this method will <em>not</em> wait for any
     * future broadcasts that are newly enqueued after being invoked.
     */
    public abstract void waitForBarrier(@NonNull PrintWriter pw);

    /**
     * Wait until all non-deferred broadcasts matching {@code intent}, as defined by
     * {@link Intent#filterEquals(Intent)}, have been dispatched.
     * <p>
     * Any broadcasts waiting to be delivered at some point in the future will
     * be dispatched as quickly as possible.
     */
    public abstract void waitForDispatched(@NonNull Intent intent, @NonNull PrintWriter pw);

    /**
     * Delays delivering broadcasts to the specified package.
     *
     * <p> Note that this is only valid for modern queue.
     */
    public void forceDelayBroadcastDelivery(@NonNull String targetPackage,
            long delayedDurationMs) {
        // No default implementation.
    }

    /**
     * Brief summary of internal state, useful for debugging purposes.
     */
    @GuardedBy("mService")
    public abstract @NonNull String describeStateLocked();

    @GuardedBy("mService")
    public abstract void dumpDebug(@NonNull ProtoOutputStream proto, long fieldId);

    @GuardedBy("mService")
    public abstract boolean dumpLocked(@NonNull FileDescriptor fd, @NonNull PrintWriter pw,
            @NonNull String[] args, int opti, boolean dumpConstants, boolean dumpHistory,
            boolean dumpAll, @Nullable String dumpPackage, boolean needSep);

    /**
     * Execute {@link #dumpLocked} and store the output into
     * {@link DropBoxManager} for later inspection.
     */
    public void dumpToDropBoxLocked(@Nullable String msg) {
        LocalServices.getService(DropBoxManagerInternal.class).addEntry(TAG_DUMP, (fd) -> {
            try (FileOutputStream out = new FileOutputStream(fd);
                    PrintWriter pw = new PrintWriter(out)) {
                pw.print("Message: ");
                pw.println(msg);
                dumpLocked(fd, pw, null, 0, false, false, false, null, false);
                pw.flush();
            }
        }, DropBoxManager.IS_TEXT);
    }
}

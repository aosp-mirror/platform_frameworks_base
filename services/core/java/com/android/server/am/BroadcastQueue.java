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

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.proto.ProtoOutputStream;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Set;

/**
 * Queue of broadcast intents and associated bookkeeping.
 */
public abstract class BroadcastQueue {
    public static final String TAG = "BroadcastQueue";

    final ActivityManagerService mService;
    final Handler mHandler;
    final BroadcastConstants mConstants;
    final BroadcastSkipPolicy mSkipPolicy;
    final String mQueueName;

    BroadcastQueue(ActivityManagerService service, Handler handler,
            String name, BroadcastConstants constants, BroadcastSkipPolicy skipPolicy) {
        mService = service;
        mHandler = handler;
        mQueueName = name;
        mConstants = constants;
        mSkipPolicy = skipPolicy;
    }

    void start(ContentResolver resolver) {
        mConstants.startObserving(mHandler, resolver);
    }

    @Override
    public String toString() {
        return mQueueName;
    }

    public abstract boolean isDelayBehindServices();

    public abstract BroadcastRecord getPendingBroadcastLocked();

    public abstract BroadcastRecord getActiveBroadcastLocked();

    /**
     * Enqueue the given broadcast to be eventually dispatched.
     * <p>
     * Callers must populate {@link BroadcastRecord#receivers} with the relevant
     * targets before invoking this method.
     * <p>
     * When {@link Intent#FLAG_RECEIVER_REPLACE_PENDING} is set, this method
     * internally handles replacement of any matching broadcasts.
     */
    public abstract void enqueueBroadcastLocked(BroadcastRecord r);

    /**
     * Signal delivered back from a {@link BroadcastReceiver} to indicate that
     * it's finished processing the current broadcast being dispatched to it.
     * <p>
     * If this signal isn't delivered back in a timely fashion, we assume the
     * receiver has somehow wedged and we trigger an ANR.
     *
     * @param receiver the value to match against
     *            {@link BroadcastRecord#receiver} to identify the caller.
     */
    public abstract boolean finishReceiverLocked(IBinder receiver, int resultCode,
            String resultData, Bundle resultExtras, boolean resultAbort, boolean waitForServices);

    public abstract void backgroundServicesFinishedLocked(int userId);

    /**
     * Signal from OS internals that the given process has just been actively
     * attached, and is ready to begin receiving broadcasts.
     */
    public abstract boolean onApplicationAttachedLocked(ProcessRecord app);

    /**
     * Signal from OS internals that the given process has timed out during
     * an attempted start and attachment.
     */
    public abstract boolean onApplicationTimeoutLocked(ProcessRecord app);

    /**
     * Signal from OS internals that the given process, which had already been
     * previously attached, has now encountered a problem such as crashing or
     * not responding.
     */
    public abstract boolean onApplicationProblemLocked(ProcessRecord app);

    /**
     * Signal from OS internals that the given process has been killed, and is
     * no longer actively running.
     */
    public abstract boolean onApplicationCleanupLocked(ProcessRecord app);

    /**
     * Signal from OS internals that the given package (or some subset of that
     * package) has been disabled or uninstalled, and that any pending
     * broadcasts should be cleaned up.
     */
    public abstract boolean cleanupDisabledPackageReceiversLocked(
            String packageName, Set<String> filterByClasses, int userId, boolean doit);

    /**
     * Quickly determine if this queue has broadcasts that are still waiting to
     * be delivered at some point in the future.
     *
     * @see #flush()
     */
    public abstract boolean isIdle();

    /**
     * Brief summary of internal state, useful for debugging purposes.
     */
    public abstract String describeState();

    /**
     * Flush any broadcasts still waiting to be delivered, causing them to be
     * delivered as soon as possible.
     *
     * @see #isIdle()
     */
    public abstract void flush();

    public abstract void dumpDebug(ProtoOutputStream proto, long fieldId);

    public abstract boolean dumpLocked(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll, String dumpPackage, boolean needSep);
}
